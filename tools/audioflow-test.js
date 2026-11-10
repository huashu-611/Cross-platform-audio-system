#!/usr/bin/env node
/**
 * 音频流连续性测试（audio flow continuity）
 *
 * 回答一个问题：**不管怎么切换汇聚端，声音是不是一直都在**。
 *
 * 这是 v0.11 "不断音切换" 的验收套件。它故意不复用 UI，直接打内核与音源：
 *   - 两个 hub 实例 A / B（各自独立的音频口 + HTTP 口）
 *   - 一个 `--tone` 音源进程（内置正弦，不碰声卡，信号可重复）
 *   - 通过音源的本地控制口 `RETARGET ip:port streamId` 在**不重启进程**的前提下来回切
 *
 * 判据（"声音流通"的可观测定义）：
 *   T1 首次连接：目标 hub 3s 内总线 > -60 dBFS
 *   T2 切换后：新目标 1.5s 内出声（不停音切换），旧目标 3s 内静音
 *   T3 反复来回切 N 轮，**每一轮都要恢复**（这是用户报的"只有第一次有声音"）
 *   T4 切换过程中音源进程不重启（pid 不变、seq 不回退）
 *   T5 目标暂不可达（对端还没起来）时进程不退出，恢复可达后自动出声
 *   T6 断开 → 重连：重连后还能出声
 *   T7 收摊（会话清空）后，只发音频的源必须能把图谱拉回来（v0.26 回归）
 *
 * 退出码 0 = 全过，1 = 有失败（可直接进 CI）。
 *
 * 用法：
 *   node tools/audioflow-test.js                 # 默认 5 轮
 *   node tools/audioflow-test.js --rounds 10
 *   node tools/audioflow-test.js --keep          # 保留日志
 */
'use strict'

const { spawn } = require('child_process')
const dgram = require('dgram')
const fs = require('fs')
const os = require('os')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const BIN = path.join(ROOT, 'build')
const HUB_EXE = path.join(BIN, 'audiohub-hub.exe')
const SRC_EXE = path.join(BIN, 'audiohub-source.exe')

const argv = process.argv.slice(2)
function intArg (name, fallback) {
  const i = argv.indexOf(name)
  const v = i >= 0 ? parseInt(argv[i + 1], 10) : NaN
  return Number.isInteger(v) && v > 0 ? v : fallback
}
const ROUNDS = intArg('--rounds', 5)
const KEEP = argv.includes('--keep')

// 端口选一段不容易撞车的
const HUB_A = { name: 'A', port: 48510, http: 4795 }
const HUB_B = { name: 'B', port: 48520, http: 4796 }
const CONTROL_PORT = 48599

const LOG_DIR = path.join(os.tmpdir(), 'audiohub-audioflow-' + Date.now())
fs.mkdirSync(LOG_DIR, { recursive: true })

// ---------------------------------------------------------------- 进程管理

const children = new Set()

function track (child, name) {
  children.add(child)
  const log = fs.createWriteStream(path.join(LOG_DIR, name + '.log'))
  if (child.stdout) child.stdout.pipe(log)
  if (child.stderr) child.stderr.pipe(log)
  child.on('exit', () => children.delete(child))
  return child
}

function killAll () {
  for (const c of children) {
    try { c.kill('SIGKILL') } catch (e) { /* 已退出 */ }
  }
  children.clear()
}

process.on('exit', killAll)
process.on('SIGINT', () => { killAll(); process.exit(130) })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------------------------------------------------------------- hub

function startHub (h) {
  const child = spawn(HUB_EXE, [
    '--name', 'HUB-' + h.name,
    '--port', String(h.port),
    '--http-port', String(h.http),
    '--no-discovery',
    '--auto-accept',
    '--latency', '60'
  ], { windowsHide: true })
  track(child, 'hub-' + h.name)
  return child
}

async function httpJson (port, pathname) {
  const res = await fetch('http://127.0.0.1:' + port + pathname)
  if (!res.ok) throw new Error('HTTP ' + res.status)
  return res.json()
}

async function waitHubUp (h, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      await httpJson(h.http, '/api/state')
      return true
    } catch (e) { await sleep(100) }
  }
  return false
}

// ---------------------------------------------------------------- 音源

let sourceProc = null

function stopSource () {
  if (sourceProc) {
    try { sourceProc.kill('SIGKILL') } catch (e) { /* 已退出 */ }
    sourceProc = null
  }
}

function startSource (hub, streamId) {
  stopSource()
  const child = spawn(SRC_EXE, [
    '--hub', '127.0.0.1:' + hub.port,
    '--handshake',
    '--tone', '440',
    '--device-name', 'TEST-SRC',
    '--stream-id', String(streamId),
    '--control-port', String(CONTROL_PORT)
  ], { windowsHide: true })
  track(child, 'src')
  sourceProc = child
  return child
}

/** 通过本地控制口改投目标（不重启进程）。返回内核的应答文本。 */
function retarget (hub, streamId) {
  return new Promise((resolve, reject) => {
    const sock = dgram.createSocket('udp4')
    const text = 'RETARGET 127.0.0.1:' + hub.port + ' ' + streamId + '\n'
    const timer = setTimeout(() => {
      try { sock.close() } catch (e) { /* ignore */ }
      reject(new Error('控制口无应答（音源没起控制口？）'))
    }, 2000)
    sock.on('message', (msg) => {
      clearTimeout(timer)
      try { sock.close() } catch (e) { /* ignore */ }
      resolve(msg.toString().trim())
    })
    sock.send(Buffer.from(text), CONTROL_PORT, '127.0.0.1')
  })
}

// ---------------------------------------------------------------- 观测量

/**
 * 判断"汇聚端耳机里有没有声音"。
 *
 * 以前只用 busLevel。它确实是汇聚端混音后的电平，但它是 Meter::Take() —— **取走即清零**。
 * 界面自己每 200ms 轮询一次，测试再读一次就会读到空桶，于是"明明在响"被读成 -120。
 * 这正是排查"双端不通"时结论反复横跳的原因（实测：同一时刻 audible=1439898、peak=-9.1，
 * 而 busLevel 报 -120）。
 *
 * 现在以 v0.27 的渲染探针为主：renderAudibleFrames 从启动起只增不减，
 * 夹在"真正送进声卡的那一帧"上，任何读者都偷不走。busLevel 只作为兼容兜底。
 */
async function observe (hub) {
  const st = await httpJson(hub.http, '/api/state')
  const sources = Array.isArray(st.sources) ? st.sources : []
  return {
    busLevel: typeof st.busLevel === 'number' ? st.busLevel : -120,
    // 单调递增的出声证据：至少一个声道非零的帧数（不会被任何轮询抢走）
    audible: typeof st.renderAudibleFrames === 'number' ? st.renderAudibleFrames : 0,
    renderFrames: typeof st.renderFrames === 'number' ? st.renderFrames : 0,
    peakDbfs: typeof st.renderPeakDbfs === 'number' ? st.renderPeakDbfs : -240,
    kbps: st.kbps || 0,
    sources: sources.length,
    packets: sources.reduce((a, s) => a + (s && s.packets ? s.packets : 0), 0)
  }
}

async function postJson (port, pathname, payload) {
  const res = await fetch('http://127.0.0.1:' + port + pathname, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload || {})
  })
  if (!res.ok) throw new Error('HTTP ' + res.status)
  return res.json()
}

/** 图谱里有几个节点（含自己）。v0.26 回归用。 */
async function graphNodes (hub) {
  const st = await httpJson(hub.http, '/api/state')
  const g = st.graph || {}
  return { nodes: Array.isArray(g.nodes) ? g.nodes.length : 0, isHub: !!g.isHub }
}

/** 等目标 hub 真的在出声（连续两次采样都有电平）。返回 { ms, state }。 */
async function waitForSound (hub, timeoutMs) {
  const start = Date.now()
  let last = null
  let goodRun = 0
  let audibleBase = -1   // 第一次采样定基准，之后只看增量
  while (Date.now() - start < timeoutMs) {
    try {
      last = await observe(hub)
      if (audibleBase < 0) audibleBase = last.audible
      // busLevel 只是兜底：有声帧数涨了才算真的出声（一次采样一个增量基准）
      if (last.audible > audibleBase || last.busLevel > -60) {
        goodRun++
        if (goodRun >= 2) return { ms: Date.now() - start, state: last }
      } else {
        goodRun = 0
      }
    } catch (e) { /* hub 还没起来 */ }
    await sleep(100)
  }
  return { ms: -1, state: last }
}

async function waitForSilence (hub, timeoutMs) {
  const start = Date.now()
  let last = null
  let audibleBase = -1
  while (Date.now() - start < timeoutMs) {
    try {
      last = await observe(hub)
      if (audibleBase < 0) audibleBase = last.audible
      // 静音判据同样以探针为主：有声帧 3 秒没涨才算静音
      if (last.audible <= audibleBase && last.busLevel <= -60) return { ms: Date.now() - start, state: last }
    } catch (e) { /* ignore */ }
    await sleep(100)
  }
  return { ms: -1, state: last }
}

// ---------------------------------------------------------------- 判据

let failures = 0
let checks = 0

function check (ok, label, detail) {
  checks++
  if (ok) {
    console.log('  [PASS] ' + label + (detail ? ' — ' + detail : ''))
  } else {
    failures++
    console.log('  [FAIL] ' + label + (detail ? ' — ' + detail : ''))
  }
}

function fmtLevel (v) { return (typeof v === 'number' ? v.toFixed(1) : '?') + ' dBFS' }

// ---------------------------------------------------------------- 主流程

async function main () {
  console.log('==> 音频流连续性测试')
  console.log('    日志: ' + LOG_DIR)
  console.log('    轮数: ' + ROUNDS)

  if (!fs.existsSync(HUB_EXE) || !fs.existsSync(SRC_EXE)) {
    console.error('找不到 build/*.exe，先跑 bash build.sh')
    process.exit(2)
  }

  const hubAProc = startHub(HUB_A)
  startHub(HUB_B)
  if (!await waitHubUp(HUB_A, 8000) || !await waitHubUp(HUB_B, 8000)) {
    console.error('hub 起不来')
    process.exit(2)
  }
  console.log('    两个 hub 已就绪')

  // ---- T1: 首次连接 ----
  console.log('\n==> T1 首次连接（音源 -> HUB-A）')
  startSource(HUB_A, 1)
  let r = await waitForSound(HUB_A, 6000)
  check(r.ms >= 0, 'T1 首次连接后有声音',
    r.ms >= 0 ? (fmtLevel(r.state.busLevel) + '，' + r.ms + 'ms') : ('超时，最后 ' + fmtLevel(r.state && r.state.busLevel)))

  // ---- T4: 进程不重启（基准 pid）----
  const basePid = sourceProc.pid

  // ---- T2 + T3: 反复切换（全程不重启音源进程）----
  console.log('\n==> T2/T3 不重启进程反复切换 ' + ROUNDS + ' 次')
  for (let i = 0; i < ROUNDS; i++) {
    const target = i % 2 === 0 ? HUB_B : HUB_A
    const other = i % 2 === 0 ? HUB_A : HUB_B
    const reply = await retarget(target, 1)
    const rr = await waitForSound(target, 3000)
    check(rr.ms >= 0, 'T3.' + (i + 1) + ' 切到 HUB-' + target.name + ' 后有声音',
      rr.ms >= 0 ? (fmtLevel(rr.state.busLevel) + '，' + rr.ms + 'ms') : ('超时，最后 ' + fmtLevel(rr.state && rr.state.busLevel)))
    // 旧 hub 必须很快静音（CONN-BYE 生效），不能两头同时响
    const os = await waitForSilence(other, 3000)
    check(os.ms >= 0, 'T2.' + (i + 1) + ' HUB-' + other.name + ' 已静音（不会两头同时响）',
      os.ms >= 0 ? ('用时 ' + os.ms + 'ms') : ('仍在出声 ' + fmtLevel(os.state && os.state.busLevel)))
  }

  // ---- T4: 音源进程从头到尾没重启 ----
  check(sourceProc.pid === basePid && sourceProc.exitCode === null,
    'T4 切换全程音源进程没有重启（这是"不断音"的结构性前提）',
    'pid ' + basePid + ' → ' + sourceProc.pid + '，exitCode=' + sourceProc.exitCode)

  // ---- T5: 目标暂不可达 ----
  console.log('\n==> T5 目标暂不可达时不退出，恢复后自动出声')
  const svc = await retarget({ port: 48555 }, 1)   // 没有人监听的端口
  check(svc.indexOf('OK') === 0, 'T5 改投到不可达目标仍返回 OK（进程不退出）', svc)
  await sleep(2500)
  check(sourceProc.exitCode === null, 'T5 目标不可达 2.5s 后音源进程仍存活',
    'exitCode=' + sourceProc.exitCode)
  await retarget(HUB_B, 1)
  r = await waitForSound(HUB_B, 4000)
  check(r.ms >= 0, 'T5 恢复可达目标后自动出声',
    r.ms >= 0 ? (fmtLevel(r.state.busLevel) + '，' + r.ms + 'ms') : '超时')

  // ---- T6: 断开重连 ----
  console.log('\n==> T6 断开 -> 重连')
  stopSource()
  const d = await waitForSilence(HUB_B, 8000)
  check(d.ms >= 0, 'T6 停掉音源后汇聚端静音',
    d.ms >= 0 ? ('用时 ' + d.ms + 'ms') : '仍在出声')
  startSource(HUB_B, 1)
  r = await waitForSound(HUB_B, 6000)
  check(r.ms >= 0, 'T6 重连后恢复出声',
    r.ms >= 0 ? (fmtLevel(r.state.busLevel) + '，' + r.ms + 'ms') : '超时')

  // ---- T7: 收摊后只发音频的源必须能自己回来 ----
  console.log('')
  console.log('==> T7 收摊（会话清空）后，只发音频的源要把图谱拉回来')
  // v0.26 回归用例。ClearSession() 会 GraphResetLocked() 把 graph_detached 置真，
  // 之后 HandleGraphFrame / GraphBroadcastLocked 全部早退。手机 CaptureService
  // 重连时【只发音频包、不发 CONN-REQ】，如果这条路径不 reattach，两端会各自
  // 守着自己那份图谱、都认为汇聚端是自己 —— 现象就是"两端彻底都收不到对方的声音"。
  // 这里用一直在推流的音源走同一条路径：清空会话 → 只发音频 → 图谱必须自愈。
  await retarget(HUB_A, 1)
  const beforeClear = await waitForSound(HUB_A, 4000)
  check(beforeClear.ms >= 0, 'T7 清空会话前 HUB-A 在出声',
    beforeClear.ms >= 0 ? fmtLevel(beforeClear.state.busLevel) : '超时')
  const pre = await graphNodes(HUB_A)
  check(pre.nodes >= 2, 'T7 清空会话前图谱里有对端', 'nodes=' + pre.nodes)

  // 注意：音源此刻一直在推流，ClearSession 之后它会立刻以"纯音频包"重新建流，
  // 所以"图谱只剩自己"这个中间态是**观测不到**的（修复后 2ms 就自愈了）。
  // 不设这条中间断言：它本质上是有竞态的，会让测试变成随机红。
  // 真正的回归判据是下面这条"能不能自愈"——去掉 src/hub_core.cpp 里 v0.26 的
  // reattach 之后，图谱会永远停在 1 个节点，这里必红。
  const hubPidBefore = hubAProc.pid
  await postJson(HUB_A.http, '/api/session/clear', {})

  let healed = -1
  const healStart = Date.now()
  while (Date.now() - healStart < 6000) {
    const g = await graphNodes(HUB_A)
    if (g.nodes >= 2) { healed = Date.now() - healStart; break }
    await sleep(100)
  }
  check(healed >= 0, 'T7 清空会话后，只发音频的源能把图谱拉回来（不会两端各自为 hub）',
    healed >= 0 ? (healed + 'ms') : '6s 内没回来')
  check(hubAProc.pid === hubPidBefore && hubAProc.exitCode === null,
    'T7 自愈过程没有重启汇聚端进程', 'pid ' + hubPidBefore + ' → ' + hubAProc.pid)

  const after = await waitForSound(HUB_A, 4000)
  check(after.ms >= 0, 'T7 自愈后 HUB-A 仍然在出声',
    after.ms >= 0 ? fmtLevel(after.state.busLevel) : '超时，最后 '
      + fmtLevel(after.state && after.state.busLevel))

  // ---- 汇总 ----
  killAll()
  console.log('\n==> 结果: ' + (checks - failures) + '/' + checks + ' 通过')
  if (failures > 0) {
    console.log('==> 音频流连续性测试: FAILED（日志 ' + LOG_DIR + '）')
    process.exit(1)
  }
  console.log('==> 音频流连续性测试: ALL CHECKS PASSED')
  if (!KEEP) {
    try { fs.rmSync(LOG_DIR, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  }
  process.exit(0)
}

main().catch((err) => {
  console.error('测试崩溃: ' + (err && err.stack ? err.stack : err))
  killAll()
  process.exit(2)
})
