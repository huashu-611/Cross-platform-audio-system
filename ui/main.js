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

const { app, BrowserWindow, ipcMain } = electronModule
const path = require('path')
const { spawn } = require('child_process')
const os = require('os')
const http = require('http')

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
// 音源进程的最新采集状态：界面用它回答"电脑到底采到声音没有"
let pcSrc = { level: null, pkts: 0, kfps: 0 }

function startPcSource (ip, port, streamId) {
  stopPcSource()
  const sid = Number.isInteger(streamId) && streamId > 0 ? streamId : 1
  const exe = sourceExePath()
  pcSourceStartedAt = Date.now()
  pcSourceTarget = { ip: ip, port: port, streamId: sid }
  console.log('[graph] 启动音源进程 -> ' + ip + ':' + port + '（stream ' + sid + '）')
  const proc = spawn(exe, [
    '--hub', ip + ':' + port,
    '--handshake',
    // 本机作音源时先把自己静音（用户 2026-10-27 定稿：连接后只让汇聚端出声），
    // 退出/被杀时由 restoreMute() 兜底恢复（见 stopPcSource / before-quit）。
    '--mute-local',
    '--device-name', os.hostname(),
    '--stream-id', String(sid)
  ], { windowsHide: true, stdio: 'ignore' })
  pcSourceProcess = proc
  proc.on('exit', (code) => {
    // 旧进程的 exit 可能在新进程起来之后才到，必须比对身份再清空
    if (pcSourceProcess !== proc) return
    pcSourceProcess = null
    // 保留 pcSourceTarget：让 reconcile 冷却 3 秒后按图谱重试，而不是 200ms 一次刷屏
    console.error('[graph] 音源进程退出（code=' + code + '），稍后按图谱重试')
  })
  proc.on('error', (err) => {
    if (pcSourceProcess === proc) pcSourceProcess = null
    console.error('[graph] 音源进程启动失败: ' + err.message)
  })
}

function sourceExePath () {
  return process.resourcesPath
    ? path.join(process.resourcesPath, 'app.asar.unpacked', 'bin', 'audiohub-source.exe')
    : path.join(__dirname, 'bin', 'audiohub-source.exe')
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
    if (pcSourceProcess || pcSourceTarget) {
      console.log('[graph] 本机是汇聚端，停止音源进程')
      stopPcSource()
    }
    return
  }

  const hubNode = hubId ? nodes.find((n) => n.id === hubId) : null
  if (!hubNode) {
    // hubId 为空 / 指向不存在的节点：没有可发往的目标，停在空闲
    if (pcSourceProcess || pcSourceTarget) {
      console.log('[graph] 汇聚端未知，停止音源进程')
      stopPcSource()
    }
    return
  }

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
  if (pcSourceProcess && sameTarget) return          // 已在正确目标上，绝不重启
  if (!pcSourceProcess && sameTarget &&
      Date.now() - pcSourceStartedAt < PC_SOURCE_RETRY_MS) {
    return                                            // 刚退出/刚拉起，等冷却再重试
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

function startHub () {
  const exe = hubExePath()
  hubProcess = spawn(exe, [
    '--name', os.hostname(),
    '--port', String(DATA_PORT),
    '--http-port', String(HTTP_PORT),
    '--latency', String(LATENCY_MS)
  ], { stdio: ['ignore', 'pipe', 'pipe'] })

  // 音频核心的输出必须能看到：端口被占、设备打不开、握手异常
  // 都只在这里体现。之前用 stdio:'ignore' 等于把唯一的线索丢了。
  hubProcess.stdout.on('data', (d) => process.stdout.write('[hub] ' + d))
  hubProcess.stderr.on('data', (d) => process.stderr.write('[hub] ' + d))

  hubProcess.on('exit', (code) => {
    hubProcess = null
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hub:push', { error: '音频核心已退出（code=' + code + '）' })
    }
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
  if (typeof p.ip !== 'string' || !Number.isInteger(p.audioPort)) return { ok: false }
  return httpRequest('POST', '/api/invite', { ip: p.ip, audioPort: p.audioPort })
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
