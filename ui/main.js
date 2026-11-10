// 主进程：负责起 C++ 音频核心、轮询它的 HTTP 接口、把状态推给渲染进程。
//
// 进程边界（electron-assistant 的安全底线）：
//   主进程（本文件，Node）  ──IPC──▶  preload（窄接口）  ──▶  渲染进程（纯网页）
//   渲染进程不碰 Node，只通过 window.hub.* 这几个函数通信。
const electronModule = require('electron')

// 这个坑很隐蔽：某些 Electron 宿主环境（包括本机的 WorkBuddy）会给子进程设
// ELECTRON_RUN_AS_NODE=1，Electron 就退化成纯 Node，内置 API 不再注入，
// require('electron') 只能落到 npm 包那个"返回二进制路径"的分支。
// 不检查的话，报错是 "Cannot read properties of undefined (reading 'whenReady')"，
// 完全指不到真正原因。
if (typeof electronModule === 'string') {
  console.error('[AudioHub] require("electron") 返回了路径而不是 API 对象。')
  console.error('[AudioHub] 原因基本可以确定：环境里设置了 ELECTRON_RUN_AS_NODE=1。')
  console.error('[AudioHub] 请先执行  unset ELECTRON_RUN_AS_NODE  再 npm start')
  process.exit(1)
}

const { app, BrowserWindow, ipcMain, dialog } = electronModule
const path = require('path')
const { spawn, execFile } = require('child_process')
const os = require('os')
const http = require('http')
const fs = require('fs')

const DATA_PORT = 47000   // 音频 UDP 口，手机上填的就是它
const HTTP_PORT = 4780    // 本地控制口，只绑 127.0.0.1
const LATENCY_MS = 60

let hubProcess = null
let mainWindow = null
let pollTimer = null

// ------------------------------------------------------------------ 启动 C++ 核心

function hubExePath () {
  if (app.isPackaged) {
    // asarUnpack 过的外部可执行文件：asar 是只读归档，
    // 要 spawn 的二进制必须解包到真实路径，否则加载失败。
    return path.join(process.resourcesPath, 'app.asar.unpacked', 'bin', 'audiohub-hub.exe')
  }
  // 开发态：直接用仓库 build 目录里的产物
  return path.join(__dirname, '..', 'build', 'audiohub-hub.exe')
}

function stopHub () {
  if (hubProcess) {
    hubProcess.kill()
    hubProcess = null
  }
}

// 本机作为音源：把电脑的声音送到远端汇聚端。
// 用现成的 audiohub-source，避免在 Electron 里重写采集。
let pcSourceProcess = null
// 当前音源进程的目标。图谱 reconcile 靠它判断"要不要重启"：
// 目标或 streamId 变了才重启，没变就不动 —— 否则每 200ms 一次轮询会把进程杀来杀去。
let pcSourceTarget = null
let pcSourceStartedAt = 0
const PC_SOURCE_RETRY_MS = 3000
// 新目标要连续稳定这么久才真正切换音源进程（见 ensurePcSource）。
// 理由：图谱里有人上下线会让本机 streamId 重新排号，200ms 一次轮询下
// 目标抖两下就会把音源进程杀两次，汇聚端那边就是"没声音"。
const PC_SOURCE_TARGET_STABLE_MS = 900
// 待生效的目标：{ key, since }
let pendingTarget = null
// 图谱里找不到汇聚端（切换中/对端 GRAPH 还没到）的起始时间，0 = 正常
let hubUnknownSince = 0
// 找不到汇聚端时保持现有音源进程的宽限期（切换汇聚端要让新帧有时间到达）
const HUB_UNKNOWN_GRACE_MS = 4000
// 音源进程的最新采集状态：界面用它回答"电脑到底采到声音没有"
let pcSrc = { level: null, pkts: 0, kfps: 0 }
// 音源进程的本地控制口（RETARGET / STATUS / STOP）。
// 有了它，切换汇聚端 = 改一个目标，不再 kill + spawn：
//   以前每次切换都要重启进程，新进程重新握手（最多 10 秒）＋ 抖动缓冲重新蓄水，
//   用户听到的就是"只有第一次有声音，之后怎么切都没有"。
const SOURCE_CONTROL_PORT = 47990
let sourceControlReady = false

function startPcSource (ip, port, streamId) {
  stopPcSource()
  const sid = Number.isInteger(streamId) && streamId > 0 ? streamId : 1
  const exe = sourceExePath()
  pcSourceStartedAt = Date.now()
  pcSourceTarget = { ip: ip, port: port, streamId: sid }
  sourceControlReady = false
  console.log('[graph] 启动音源进程 -> ' + ip + ':' + port + '（stream ' + sid + '）')
  const proc = spawn(exe, [
    '--hub', ip + ':' + port,
    '--handshake',
    // 本机作音源时先把自己静音（用户 2026-10-27 定稿：连接后只让汇聚端出声），
    // 退出/被杀时由 restoreMute() 兜底恢复（见 stopPcSource / before-quit）。
    '--mute-local',
    '--device-name', os.hostname(),
    '--stream-id', String(sid),
    // 运行中改投目标的入口（见 retargetPcSource）
    '--control-port', String(SOURCE_CONTROL_PORT)
  ], { windowsHide: true, stdio: 'ignore' })
  pcSourceProcess = proc
  proc.on('exit', (code) => {
    // 旧进程的 exit 可能在新进程起来之后才到，必须比对身份再清空
    if (pcSourceProcess !== proc) return
    pcSourceProcess = null
    sourceControlReady = false
    // 保留 pcSourceTarget：让 reconcile 冷却 3 秒后按图谱重试，而不是 200ms 一次刷屏
    console.error('[graph] 音源进程退出（code=' + code + '），稍后按图谱重试')
  })
  proc.on('error', (err) => {
    if (pcSourceProcess === proc) pcSourceProcess = null
    sourceControlReady = false
    console.error('[graph] 音源进程启动失败: ' + err.message)
  })
  // 控制口是 UDP，没有"已就绪"事件；给进程一点启动时间后直接试。
  setTimeout(() => {
    if (pcSourceProcess === proc) sourceControlReady = true
  }, 400)
}

/**
 * 运行中改投目标：`RETARGET ip:port streamId`。
 *
 * 成功返回 true（进程不重启、采集不中断），失败返回 false 交给上层重启。
 * 这是"切换汇聚端不断音"在 Electron 侧的那一半。
 */
function retargetPcSource (ip, port, streamId) {
  if (!pcSourceProcess || !sourceControlReady) return Promise.resolve(false)
  const text = 'RETARGET ' + ip + ':' + port + ' ' + streamId + '\n'
  return new Promise((resolve) => {
    let done = false
    const finish = (ok) => {
      if (done) return
      done = true
      clearTimeout(timer)
      try { sock.close() } catch (e) { /* ignore */ }
      resolve(ok)
    }
    let sock = null
    const timer = setTimeout(() => finish(false), 700)
    try {
      const dgram = require('dgram')
      sock = dgram.createSocket('udp4')
      sock.on('error', () => finish(false))
      sock.on('message', (msg) => {
        const reply = msg.toString().trim()
        if (reply.startsWith('OK')) {
          console.log('[graph] 已改投目标 -> ' + ip + ':' + port + '（stream ' + streamId + '）')
          finish(true)
        } else {
          console.warn('[graph] 改投目标被拒绝: ' + reply)
          finish(false)
        }
      })
      sock.send(Buffer.from(text), SOURCE_CONTROL_PORT, '127.0.0.1')
    } catch (e) {
      finish(false)
    }
  })
}

function sourceExePath () {
  return coreExePath('audiohub-source.exe')
}

/**
 * C++ 核心二进制的路径。
 *
 * 注意 `process.resourcesPath` 在**开发态也存在**（electron . 时会指向
 * node_modules/electron/dist/resources），所以不能拿它判断"是不是打包态" —
 * 老实现这么做，开发运行时永远解析到 app.asar.unpacked/bin 那个只有打包后才有
 * 的目录，spawn 直接 ENOENT：界面上表现为"本机当音源"时手机一直收到 0 路，
 * 而日志里只有一行启动失败。
 *
 * 判断打包态要用 app.isPackaged。开发态走仓库里的 ui/bin/，打包态走 asarUnpack。
 */
function coreExePath (name) {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'app.asar.unpacked', 'bin', name)
  }
  return path.join(__dirname, 'bin', name)
}

// 取消静音。必须单独调：Electron 用 kill() 终止音源时，
// 音源进程走不到自己的退出路径，静音不会自动恢复 —— 电脑会一直哑着。
function restoreMute () {
  try {
    spawn(sourceExePath(), ['--restore-mute'], {
      windowsHide: true, stdio: 'ignore'
    }).on('error', () => {})
  } catch (err) {
    /* 尽力而为 */
  }
}

function stopPcSource () {
  pendingTarget = null
  if (pcSourceProcess) {
    const proc = pcSourceProcess
    pcSourceProcess = null
    proc.kill()
    if (pcSourceTarget) {
      console.log('[graph] 停止音源进程（原目标 ' + pcSourceTarget.ip + ':' +
                  pcSourceTarget.port + '）')
    }
    pcSourceTarget = null
    restoreMute()
  } else if (pcSourceTarget) {
    pcSourceTarget = null
  }
}

// ------------------------------------------------------------------ 图谱职责协调（协议 §6.1）
//
// 每 200ms 轮询 /api/state 之后执行一次：
//   isHub            → 停掉音源进程（本机收流/混音，靠常驻 hub 进程）
//   hub 存在         → 音源目标 = hub 节点的 ip:audioPort，streamId = 本机在 nodes 里的 1-based 名次
//   目标/streamId 没变 → 什么都不做（不能重启，重启会断音）
//   hub 未知         → 停在空闲（没有可发往的目标）
// 后端还没实现 graph（旧版本 / 启动瞬间）时直接跳过，保留旧行为，绝不因缺字段抛错。

// 节点地址：优先 ip/audioPort 字段，缺失时从 id（ip:audioPort）里拆
function nodeAddress (node) {
  let ip = node && typeof node.ip === 'string' ? node.ip : ''
  let port = node && Number.isInteger(node.audioPort) && node.audioPort > 0
    ? node.audioPort
    : 0
  if ((!ip || !port) && node && typeof node.id === 'string') {
    const colon = node.id.lastIndexOf(':')
    if (colon > 0) {
      if (!ip) ip = node.id.slice(0, colon)
      if (!port) {
        const parsed = parseInt(node.id.slice(colon + 1), 10)
        if (Number.isInteger(parsed) && parsed > 0) port = parsed
      }
    }
  }
  if (!port) port = DATA_PORT
  return { ip: ip, port: port }
}

function graphNodesOf (graph) {
  const out = []
  const raw = graph && Array.isArray(graph.nodes) ? graph.nodes : []
  for (const n of raw) {
    if (n && typeof n === 'object' && typeof n.id === 'string' && n.id) out.push(n)
  }
  return out
}

// §6：streamId = 1 + 本机在 nodes 按 id 升序的序号（从 0 计）
function streamIdForGraph (nodes, selfId) {
  const sorted = nodes.slice().sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
  for (let i = 0; i < sorted.length; ++i) {
    if (sorted[i].id === selfId) return i + 1
  }
  return 1
}

function reconcileGraph (graph) {
  if (!graph || typeof graph !== 'object') return
  const nodes = graphNodesOf(graph)
  let selfId = typeof graph.selfId === 'string' ? graph.selfId : ''
  if (!selfId) {
    const selfNode = nodes.find((n) => n.isSelf === true)
    if (selfNode) selfId = selfNode.id
  }
  const hubId = typeof graph.hubId === 'string' ? graph.hubId : ''

  // 本机 ID 都不知道就没法算职责，先停在空闲
  if (!selfId) {
    if (pcSourceProcess || pcSourceTarget) {
      console.log('[graph] 本机节点未知，停止音源进程')
      stopPcSource()
    }
    return
  }

  if (hubId === selfId) {
    hubUnknownSince = 0
    if (pcSourceProcess || pcSourceTarget) {
      console.log('[graph] 本机是汇聚端，停止音源进程')
      stopPcSource()
    }
    return
  }

  const hubNode = hubId ? nodes.find((n) => n.id === hubId) : null
  if (!hubNode) {
    // hubId 为空 / 指向不存在的节点：没有可发往的目标。
    //
    // 但**不要立刻停**：切换汇聚端的瞬间，本机图谱会先变成"hub = 还没见过的新节点"
    // （对端 GRAPH 帧还在路上），若这一拍就把音源进程杀掉，下一拍再起来 ——
    // 用户听到的是「反复切换之后汇聚端没声音」（每次切换都断一次流，
    // 而汇聚端那边要重新握手 + 重建抖动缓冲，往往刚好卡在没声音的状态）。
    // 所以给一个宽限期：这期间保持现有音源进程不动，超时才真正停。
    const now = Date.now()
    if (hubUnknownSince === 0) hubUnknownSince = now
    if (now - hubUnknownSince < HUB_UNKNOWN_GRACE_MS) return
    if (pcSourceProcess || pcSourceTarget) {
      console.log('[graph] 汇聚端未知（超过 ' + HUB_UNKNOWN_GRACE_MS + 'ms），停止音源进程')
      stopPcSource()
    }
    return
  }

  hubUnknownSince = 0
  const addr = nodeAddress(hubNode)
  if (!addr.ip) {
    if (pcSourceProcess || pcSourceTarget) stopPcSource()
    return
  }
  ensurePcSource(addr.ip, addr.port, streamIdForGraph(nodes, selfId))
}

function ensurePcSource (ip, port, streamId) {
  const sameTarget = !!pcSourceTarget &&
    pcSourceTarget.ip === ip &&
    pcSourceTarget.port === port &&
    pcSourceTarget.streamId === streamId
  if (pcSourceProcess && sameTarget) {
    pendingTarget = null
    return                                          // 已在正确目标上，绝不重启
  }
  if (!pcSourceProcess && sameTarget &&
      Date.now() - pcSourceStartedAt < PC_SOURCE_RETRY_MS) {
    return                                            // 刚退出/刚拉起，等冷却再重试
  }
  // 目标变了先别急着重启：图谱每次变化（有人上下线）都会让 streamId 重新排号，
  // 200ms 一次的轮询里来回抖两下就会把音源进程杀两次 —— 汇聚端那边听到的就是
  // 「切换/有人进出之后没声音」。要求新目标稳定一小段时间再切。
  const key = ip + ':' + port + '#' + streamId
  const now = Date.now()
  if (!pendingTarget || pendingTarget.key !== key) {
    pendingTarget = { key, since: now }
    return
  }
  if (now - pendingTarget.since < PC_SOURCE_TARGET_STABLE_MS) return
  pendingTarget = null
  // 优先"改投目标"：进程不重启，采集与抖动缓冲都不重建 —— 这是不断音的关键。
  // 只有控制口不可用（老进程 / 还没起来）时才回退到重启。
  if (pcSourceProcess) {
    retargetPcSource(ip, port, streamId).then((ok) => {
      if (ok) {
        if (pcSourceTarget) {
          pcSourceTarget = { ip: ip, port: port, streamId: streamId }
        }
        return
      }
      console.warn('[graph] 改投失败，退回重启音源进程')
      startPcSource(ip, port, streamId)
    })
    return
  }
  startPcSource(ip, port, streamId)
}

// 汇聚端设定：旧接口，保留给旧脚本兼容。
// 新界面不再调用它 —— 汇聚端由"图谱"决定（点节点 → hub:switch → 后端改图谱），
// 本机该收还是该发由下面的 reconcileGraph 每次轮询校正。
ipcMain.handle('hub:setRole', async (_e, arg) => {
  const self = arg && arg.self !== undefined ? arg.self : true

  // 切换角色时绝不能停掉核心进程。
  // 核心不只是收流，它还承载着 /api/state —— 界面的全部状态都从它来。
  // 之前这里调了 stopHub()，结果一切换界面就报"音频核心已退出"，
  // 整个控制界面变成死的。
  //
  // 正确做法：核心一直活着；本机不是汇聚端时，只是额外起一个音源进程
  // 把电脑的声音送到远端，核心那头自然收不到流（也没人往它发）。
  if (!hubProcess) startHub()

  if (self) {
    stopPcSource()
    // 把对端变回"音源"：告诉它往本机送
    if (arg && arg.prevIp) {
      const st = await httpRequest('GET', '/api/state')
      const colon = st && st.ip ? st.ip.lastIndexOf(':') : -1
      const hubIp = colon > 0 ? st.ip.slice(0, colon) : ''
      const hubPort = colon > 0 ? parseInt(st.ip.slice(colon + 1), 10) : DATA_PORT
      await httpRequest('POST', '/api/role', {
        ip: arg.prevIp, audioPort: arg.prevPort, hub: 0,
        hubIp, hubPort
      })
    }
    return { ok: true }
  }

  // 把对端变成"汇聚端"：让它自己开始接收
  // 先发邀请（对方界面会出现请求），再发角色指令（对方自动切过去）
  await httpRequest('POST', '/api/invite', { ip: arg.ip, audioPort: arg.audioPort })
  await httpRequest('POST', '/api/role', { ip: arg.ip, audioPort: arg.audioPort, hub: 1 })

  // 本机转为音源：把电脑的声音送到对端
  startPcSource(arg.ip, arg.audioPort)
  return { ok: true }
})

// ---------------------------------------------------------------- 入站放行（v0.44）
//
// Windows 防火墙按**可执行文件的完整路径**放行入站 UDP，不是按进程名、也不是
// 按端口。而我们是 spawn 一个独立的 audiohub-hub.exe，它的路径随运行方式变：
//
//   开发态 : <仓库>uildudiohub-hub.exe                    —— 跑过很多次，早有放行规则
//   便携版 : %TEMP%\AudioHub\...inudiohub-hub.exe        —— 固定解包目录，从未授权过
//
// 本机 InboundUserNotification 是禁用的，所以连"是否允许"的弹框都不会出现，
// 入站包被**静默丢弃**。表现就是：手机 send() 全部成功、界面显示"发送 91000 包"，
// 而电脑侧 rxPackets 恒 0、界面显示对方"没响应" —— 看上去像"没联网"，
// 实际上是"包到了网卡，但没交给这个 exe"。
//
// 实测证据（同一份字节 4b85310d...，只换路径，各跑 8 秒，不发任何注入包）：
//   buildudiohub-hub.exe          rxPackets +3200  sources=1
//   %TEMP%\...udiohub-hub.exe    rxPackets +0     hsPackets +0
//   build\_diag\hub_copy\...       15 秒全零
//
// 结论：这是"我们把监听器放到了一个系统不认识的路径上，而且从不自查"的问题，
// 属于代码该负责的部分 —— 所以在这里自查并补上，不让用户去点防火墙设置。
// 用户拒绝 UAC 也不会坏：只是收不到网络音频，本地功能照常，界面会给出提示。
function firewallRuleCount (exe, done) {
  if (process.platform !== 'win32') return done(0)
  // 只让 PowerShell 把"指向 hub 的全部路径"吐出来，路径比对在 Node 里做。
  //
  // 两个坑都在这里踩过：
  //   1. 不能用 -eq '<路径>' 把路径塞进命令行 —— 含中文的路径经
  //      powershell -Command 传参会变成乱码，永远匹配 0 条，于是
  //      "明明有规则也判定为没有"，把正常状态误判成故障。
  //   2. 必须先把 OutputEncoding 设成 UTF8，否则中文路径按 GBK 输出、
  //      Node 按 UTF-8 解码，比对同样永远不相等。
  const ps = '$OutputEncoding=[Console]::OutputEncoding=[Text.Encoding]::UTF8; ' +
    'Get-NetFirewallApplicationFilter | ' +
    "Where-Object { $_.Program } | Select-Object -ExpandProperty Program"
  execFile('powershell', ['-NoProfile', '-NonInteractive', '-Command', ps],
    { windowsHide: true, timeout: 10000, maxBuffer: 1 << 20, encoding: 'utf8' },
    (err, stdout) => {
      if (err) return done(null)            // 查不到就别乱下结论
      const want = String(exe).toLowerCase()
      const suffix = 'hub.exe'
      let hit = 0
      for (const raw of String(stdout).split(String.fromCharCode(10))) {
        const line = raw.replace(/\s+$/, '').toLowerCase()
        if (!line) continue
        if (line.endsWith(suffix) && line === want) hit += 1
      }
      done(hit)
    })
}

function addFirewallRule (exe) {
  // 用临时 .bat 承载 netsh，避免 PowerShell 里的多层引号转义出错。
  const bat = path.join(os.tmpdir(), 'audiohub-fw-' + Date.now() + '.bat')
  const lines = [
    '@echo off',
    'netsh advfirewall firewall delete rule name="AudioHub Hub" >nul 2>&1',
    'netsh advfirewall firewall add rule name="AudioHub Hub" dir=in action=allow ' +
      'program="' + exe + '" enable=yes profile=any protocol=udp',
    'netsh advfirewall firewall add rule name="AudioHub Hub" dir=in action=allow ' +
      'program="' + exe + '" enable=yes profile=any protocol=tcp'
  ]
  try { fs.writeFileSync(bat, lines.join('\r\n'), 'utf8') } catch (e) { return false }
  // -Verb RunAs 会弹一次 UAC；-Wait 保证后面的端口确认能立刻看到结果。
  execFile('powershell', ['-NoProfile', '-NonInteractive', '-Command',
    "Start-Process -FilePath '" + bat.replace(/'/g, "''") + "' -Verb RunAs -WindowStyle Hidden -Wait"],
  { windowsHide: true, timeout: 30000 }, () => {
    try { fs.unlinkSync(bat) } catch (e) {}
  })
  return true
}

// 启动核心后调用一次：缺规则就补，补不上就把原因**明确告诉用户**。
//
// v0.44：只在控制台打一行等于没说 —— 便携版没有控制台，用户看到的现象是
// "手机明明在发、电脑没声"，和网络故障一模一样。所以补完必须复查一次，
// 没成功就用对话框讲清楚该做什么。
function ensureInboundRule (exe) {
  firewallRuleCount(exe, (count) => {
    if (count === null) {
      console.log('[AudioHub] 无法查询防火墙规则，若收不到网络音频请运行 tools/修复防火墙-管理员运行.bat')
      return
    }
    if (count > 0) { console.log('[AudioHub] 入站放行规则已存在（' + count + ' 条）'); return }
    console.log('[AudioHub] 这个 exe 路径没有入站放行规则：', exe)
    console.log('[AudioHub] 正在申请管理员权限添加（会弹一次 UAC）…')
    addFirewallRule(exe)
    // 复查：UAC 被拒、或被组策略拦下时，addFirewallRule 本身不会报错。
    setTimeout(() => {
      firewallRuleCount(exe, (after) => {
        if (after !== null && after > 0) {
          console.log('[AudioHub] 已放行（' + after + ' 条）')
          return
        }
        console.error('[AudioHub] 入站放行没有生效，收不到手机的音频包')
        if (!mainWindow || mainWindow.isDestroyed()) return
        const detail = [
          'Windows 防火墙按程序的完整路径放行。当前路径没有规则，手机的音频包会被系统直接丢掉 ——',
          '手机上会显示"正在发送"，电脑这边却一个包都收不到。',
          '',
          '解决办法（任选其一）：',
          '1. 右键以管理员身份运行仓库里的 tools' + String.fromCharCode(92) + '修复防火墙-管理员运行.bat',
          '2. 安装 0.44 及以后的安装包（安装时会自动放行）'
        ].join(String.fromCharCode(10))
        dialog.showMessageBox(mainWindow, {
          type: 'warning',
          title: 'AudioHub 收不到网络音频',
          message: '这台电脑还没有允许 AudioHub 接收网络数据。',
          detail: detail,
          buttons: ['知道了']
        }).catch(() => {})
      })
    }, 4000)
  })
}

function startHub () {
  const exe = hubExePath()
  hubProcess = spawn(exe, [
    '--name', os.hostname(),
    '--port', String(DATA_PORT),
    '--http-port', String(HTTP_PORT),
    '--latency', String(LATENCY_MS)
  ], { stdio: ['ignore', 'pipe', 'pipe'] })

  // v0.44：核心一起来就自查入站放行。放在 spawn 之后、不阻塞启动。
  ensureInboundRule(exe)

  // 音频核心的输出必须能看到：端口被占、设备打不开、握手异常
  // 都只在这里体现。之前用 stdio:'ignore' 等于把唯一的线索丢了。
  //
  // v0.20：把 stderr 的最后一行记下来。核心进程退出时（最典型的就是
  // "端口已被占用，多半是上一个进程没退干净"）只有这句话能说清原因，
  // 否则界面只能显示一个 code=1，用户完全不知道该去做什么。
  let lastError = ''
  hubProcess.stdout.on('data', (d) => process.stdout.write('[hub] ' + d))
  hubProcess.stderr.on('data', (d) => {
    process.stderr.write('[hub] ' + d)
    const text = String(d).trim()
    if (text) lastError = text.split('\n').pop().trim() || text
  })

  hubProcess.on('exit', (code) => {
    hubProcess = null
    if (!mainWindow || mainWindow.isDestroyed()) return
    // 把核心自己的说明原样带给用户：它比"音频核心已退出（code=1）"有用得多。
    const detail = lastError
      ? lastError
      : '音频核心已退出（code=' + code + '）'
    mainWindow.webContents.send('hub:push', { error: detail })
  })
  hubProcess.on('error', (err) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hub:push', { error: '无法启动音频核心: ' + err.message })
    }
  })
}

function stopHub () {
  if (hubProcess) {
    hubProcess.kill()
    hubProcess = null
  }
}

// ------------------------------------------------------------------ 本地 HTTP 客户端

function httpRequest (method, urlPath, body) {
  return new Promise((resolve) => {
    const data = body ? JSON.stringify(body) : null
    const req = http.request({
      host: '127.0.0.1',
      port: HTTP_PORT,
      path: urlPath,
      method: method,
      headers: data
        ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }
        : {}
    }, (res) => {
      let buf = ''
      res.on('data', (c) => { buf += c })
      res.on('end', () => {
        try { resolve(JSON.parse(buf)) } catch (e) { resolve(null) }
      })
    })
    // 核心还没起来时连接会被拒，这里静默失败，等下一次轮询
    req.on('error', () => resolve(null))
    if (data) req.write(data)
    req.end()
  })
}

async function fetchState () {
  const state = await httpRequest('GET', '/api/state')
  if (!state) return
  // 每次轮询都按图谱校正职责（协议 §6.1）。图谱缺失/字段缺失时内部直接跳过。
  try {
    reconcileGraph(state.graph)
  } catch (err) {
    console.error('[graph] 职责协调失败: ' + err.message)
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    // 本机作为音源时的状态：核心只管收流，不知道我们另起了一个发送进程
    state.pcSource = pcSourceProcess !== null
    state.pcSourceLevel = pcSrc.level
    state.pcSourcePkts = pcSrc.pkts
    state.pcSourceKfps = pcSrc.kfps
    mainWindow.webContents.send('hub:push', state)
  }
}

// ------------------------------------------------------------------ 窗口

function createWindow () {
  mainWindow = new BrowserWindow({
    width: 1080,
    height: 760,
    minWidth: 720,
    minHeight: 560,
    backgroundColor: '#04060f',
    // 窗口/任务栏图标：打包后 exe 自带图标，这里管的是开发态与任务栏小图标，
    // 不指定的话 Windows 会退回 Electron 默认图标（用户反馈的"桌面图标显示问题"之一）。
    icon: path.join(__dirname, 'icon.ico'),
    title: 'AudioHub 汇聚端',
    // 安全底线：渲染进程是"没牙的老虎"，所有能力都从 preload 窄接口出
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      preload: path.join(__dirname, 'preload.js')
    }
  })

  // 渲染进程的 console 默认不会进主进程 stdout，
  // 排查"界面没反应"时看不到任何线索。转发出来。
  mainWindow.webContents.on('console-message', (_e, level, message, line, source) => {
    if (level >= 2) {
      console.error('[renderer] ' + message + ' (' + source + ':' + line + ')')
    }
  })
  mainWindow.webContents.on('render-process-gone', (_e, details) => {
    console.error('[renderer] 进程异常退出: ' + JSON.stringify(details))
  })

  mainWindow.loadFile('index.html')

  // HUB_DEBUG=1 时定时打印渲染层状态（排查界面不切换用）
  if (process.env.HUB_DEBUG === '1') {
    setInterval(async () => {
      try {
        const info = await mainWindow.webContents.executeJavaScript(
          'window.__hubDebug ? JSON.stringify(window.__hubDebug()) : "no-hook"')
        console.log('[ui] ' + info)
      } catch (err) {
        console.log('[ui] 读取失败: ' + err.message)
      }
    }, 2000)
  }

  // 开发态自动开 DevTools，排障省一步
  if (!app.isPackaged) {
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  }
}

// 界面是纯 CSS，用不到 GPU 合成；关掉硬件加速还能避免
// 虚拟化/无 GPU 环境里 GPU 进程崩溃导致整个应用起不来。
app.disableHardwareAcceleration()

app.whenReady().then(() => {
  createWindow()
  startHub()
  // 200 ms 轮询：电平条要顺滑，但也没必要更频
  pollTimer = setInterval(fetchState, 200)
  fetchState()

  // 每 5 秒让音频核心广播一轮探测：只靠对端每秒一次的信标，
  // 丢一两个包设备就会从列表里"闪没"，体感像搜不到。
  setInterval(() => httpRequest('POST', '/api/refresh', {}), 5000)

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', () => {
  if (pollTimer) clearInterval(pollTimer)
  stopPcSource()
  stopHub()
})

// ------------------------------------------------------------------ IPC

// 只注册这几个具体动作，不暴露 ipcRenderer 本身
ipcMain.handle('hub:state', async () => httpRequest('GET', '/api/state'))

// 图谱切换汇聚端（协议 §4.5 / §7.2）。
// 参数形态兼容：nodeId 字符串，或 { nodeId }。返回 { ok, graph }。
ipcMain.handle('hub:switch', async (_e, arg) => {
  const nodeId = typeof arg === 'string'
    ? arg
    : (arg && typeof arg.nodeId === 'string' ? arg.nodeId : '')
  if (!nodeId || nodeId.length > 128) return { ok: false, error: 'invalid nodeId' }
  const res = await httpRequest('POST', '/api/graph/switch', { nodeId: nodeId })
  if (!res) return { ok: false, error: '音频核心没有响应' }
  // 切换成功后立即按返回的图谱校正职责，不必等下一次 200ms 轮询
  if (res.ok && res.graph) {
    try {
      reconcileGraph(res.graph)
    } catch (err) {
      console.error('[graph] 切换后协调失败: ' + err.message)
    }
  }
  if (typeof res.ok !== 'boolean') res.ok = false
  return res
})

ipcMain.handle('hub:setSource', async (_e, arg) => {
  const { id, patch } = arg || {}
  if (!Number.isInteger(id)) return { ok: false }
  // 参数收窄：只认这两个字段，且取值范围固定，防止页面塞奇怪值进来
  const body = { id }
  if (patch && typeof patch.gain === 'number') {
    body.gain = Math.min(Math.max(patch.gain, 0), 4)
  }
  if (patch && typeof patch.muted === 'boolean') {
    body.muted = patch.muted
  }
  return httpRequest('POST', '/api/source', body)
})

ipcMain.handle('hub:setMaster', async (_e, arg) => {
  const gain = arg && typeof arg.gain === 'number' ? Math.min(Math.max(arg.gain, 0), 4) : null
  if (gain === null) return { ok: false }
  return httpRequest('POST', '/api/master', { gain })
})

// 3D 场景 → 空间音频（docs/3D-界面与空间音频-可行性与实现方案.md §2.4/§3.3）。
// 旧内核没有这些端点时，httpRequest 拿到 404 的 JSON 或 null，这里统一回 unsupported，
// 渲染进程静默停发 —— 界面不能因为"内核是旧版"而弹错、卡顿。
ipcMain.handle('hub:setListener', async (_e, arg) => {
  if (!arg || typeof arg !== 'object') return { ok: false }
  const clampNum = (v, lo, hi) => Math.min(Math.max(Number(v) || 0, lo), hi)
  const round2 = (v) => Math.round(v * 100) / 100
  const body = {
    x: clampNum(arg.x, -10, 10),
    y: clampNum(arg.y, -10, 10),
    z: clampNum(arg.z, -10, 10),
    yaw: round2(clampNum(arg.yaw, -180, 180)),
    pitch: round2(clampNum(arg.pitch, -90, 90))
  }
  const res = await httpRequest('POST', '/api/listener', body)
  if (!res) return { ok: false, unsupported: true }
  return res
})

ipcMain.handle('hub:setSourceSpatial', async (_e, arg) => {
  const a = arg || {}
  if (typeof a.nodeId !== 'string' || !a.nodeId) return { ok: false }
  const clampNum = (v, lo, hi) => Math.min(Math.max(Number(v) || 0, lo), hi)
  const body = {
    nodeId: a.nodeId.slice(0, 128),
    x: clampNum(a.x, -20, 20),
    y: clampNum(a.y, -20, 20),
    z: clampNum(a.z, -20, 20),
    enabled: a.enabled !== false
  }
  const res = await httpRequest('POST', '/api/source/spatial', body)
  if (!res) return { ok: false, unsupported: true }
  return res
})

// 房间摆位同步（docs/GRAPH-PROTOCOL.md §13）：拖动落点交给本机内核，
// 内核 version+1 后广播给图谱里所有节点（手机端只读显示，多台电脑位置一致）。
// 注意这里不做任何本地持久化：布局是全网一份，存在各端内核里。
ipcMain.handle('hub:setNodePlacement', async (_e, arg) => {
  const a = arg || {}
  if (typeof a.nodeId !== 'string' || !a.nodeId) return { ok: false }
  const body = { nodeId: a.nodeId.slice(0, 128) }
  if (a.clear === true) {
    body.clear = true
  } else {
    const clamp = (v) => Math.min(Math.max(Number(v) || 0, -20), 20)
    body.x = Math.round(clamp(a.x) * 100) / 100
    body.z = Math.round(clamp(a.z) * 100) / 100
  }
  const res = await httpRequest('POST', '/api/graph/placement', body)
  if (!res) return { ok: false, unsupported: true }
  return res
})

// 主动扫描：让音频核心发一轮 WHO 探测，等对端回信标后再回读状态。
// 只调 refresh 不等待的话，界面拿到的还是探测前那份 peers。
// 接受对端邀请：把本机的声音送过去，并告诉它"你来当汇聚端"
ipcMain.handle('hub:accept-invite', async (_e, arg) => {
  const ip = arg && arg.ip
  if (!ip) return { ok: false }
  await httpRequest('POST', '/api/accept-invite', { ip })
  // 让对方开始接收（ROLE|hub），本机转为音源
  await httpRequest('POST', '/api/role', { ip, audioPort: DATA_PORT, hub: 1 })
  startPcSource(ip, DATA_PORT)
  return { ok: true }
})

ipcMain.handle('hub:reject-invite', async (_e, arg) => {
  const ip = arg && arg.ip
  if (!ip) return { ok: false }
  await httpRequest('POST', '/api/reject-invite', { ip })
  return { ok: true }
})

ipcMain.handle('hub:clearSession', async () => {
  await httpRequest('POST', '/api/clear-session', {})
  return { ok: true }
})

ipcMain.handle('hub:scan', async () => {
  await httpRequest('POST', '/api/refresh', {})
  await new Promise((resolve) => setTimeout(resolve, 800))
  const state = await httpRequest('GET', '/api/state')
  if (!state) return { ok: false, peers: [] }
  return { ok: true, peers: state.peers || [] }
})

ipcMain.handle('hub:invite', async (_e, arg) => {
  const p = arg || {}
  if (typeof p.ip !== 'string' || !p.ip.trim()) return { ok: false }
  // 端口可省：界面上就写着「端口可省」，只填 IP 是最常见的用法。
  // 这里不再强制整数，字符串/缺省都按默认 47000 处理，交给内核再兜一次。
  let port = Number.parseInt(p.audioPort, 10)
  if (!Number.isInteger(port) || port <= 0 || port > 65535) port = 47000
  return httpRequest('POST', '/api/invite', { ip: p.ip.trim(), audioPort: port })
})

ipcMain.handle('hub:accept', async (_e, arg) => {
  const id = arg && Number.isInteger(arg.streamId) ? arg.streamId : null
  if (id === null) return { ok: false }
  return httpRequest('POST', '/api/accept', { streamId: id })
})

ipcMain.handle('hub:reject', async (_e, arg) => {
  const id = arg && Number.isInteger(arg.streamId) ? arg.streamId : null
  if (id === null) return { ok: false }
  return httpRequest('POST', '/api/reject', { streamId: id })
})

ipcMain.handle('hub:disconnect', async (_e, arg) => {
  const id = arg && Number.isInteger(arg.streamId) ? arg.streamId : null
  if (id === null) return { ok: false }
  return httpRequest('POST', '/api/disconnect', { streamId: id })
})

ipcMain.handle('hub:setAutoAccept', async (_e, arg) => {
  const on = arg && typeof arg.on === 'boolean' ? arg.on : null
  if (on === null) return { ok: false }
  return httpRequest('POST', '/api/auto-accept', { on })
})

ipcMain.handle('hub:setLatency', async (_e, arg) => {
  const ms = arg && typeof arg.ms === 'number' ? Math.round(arg.ms) : null
  if (ms === null || ms < 20 || ms > 250) return { ok: false }
  return httpRequest('POST', '/api/latency', { ms })
})
