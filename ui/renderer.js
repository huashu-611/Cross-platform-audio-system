// 渲染层：两个屏幕。
//
//   连接界面 —— 建立连接（设备列表 / 待确认请求）
//   控制界面 —— 全网图谱（协议 §9）：节点 = 图谱里的所有设备；
//               汇聚端大球在中心，本机/离线有角标；点非汇聚端节点可把汇聚端切过去。
//
// 界面切换：图谱节点数 >= 2（网络已形成）或本机有音频流 → 控制界面。
// 角色不在这里"猜"：state.graph 说了算，本层不调 setRole（旧接口已废弃）。
// 本文件必须容忍 graph 缺失 / 字段缺失（旧后端、启动瞬间）：退化成用 sources 拼图，不白屏。

let screen = 'connect'          // 'connect' | 'control'
let selected = null             // 被点选的节点 { nodeId } 或 null
let lastState = null
let autoTouched = false
let volDragging = false

// 切换汇聚端的交互状态
let pendingSwitch = null        // { nodeId, fromVersion, timer }：等新图谱确认
let action = null               // { mode: 'switch'|'hub', nodeId }
let syncText = ''
let syncTone = ''               // '' | 'ok' | 'warn'

const $ = (id) => document.getElementById(id)

// ============================================================ 工具

const pct = (v) => Math.round(v * 100) + '%'

function dbText (v) {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
  if (v <= -119) return '静音'
  return v.toFixed(1) + ' dBFS'
}

const levelPct = (level) => Math.max(0, Math.min(100, (level + 60) / 0.6))

const ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
function esc (s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/[&<>"']/g, (c) => ESC_MAP[c])
}

function shortName (name, max) {
  const chars = Array.from(String(name === null || name === undefined ? '' : name))
  if (chars.length <= max) return chars.join('')
  return chars.slice(0, max).join('') + '…'
}

function ipFromId (id) {
  if (typeof id !== 'string') return ''
  const colon = id.lastIndexOf(':')
  return colon > 0 ? id.slice(0, colon) : (id === 'self' ? '' : id)
}

function portFromId (id) {
  if (typeof id !== 'string') return 47000
  const colon = id.lastIndexOf(':')
  if (colon <= 0) return 47000
  const parsed = parseInt(id.slice(colon + 1), 10)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : 47000
}

function fmtNumber (v, digits) {
  return typeof v === 'number' && Number.isFinite(v) ? v.toFixed(digits) : '0'
}

// ============================================================ 屏幕切换

function showScreen (next) {
  screen = next
  const connectEl = $('screenConnect')
  const controlEl = $('screenControl')
  if (connectEl) connectEl.style.display = next === 'connect' ? '' : 'none'
  if (controlEl) controlEl.style.display = next === 'control' ? '' : 'none'
  const title = document.querySelector('h1 span')
  if (title) title.textContent = next === 'control' ? '控制台' : '连接设备'
}

// ============================================================ 图谱数据
//
// 统一成一份"渲染用图谱"：
//   { hasGraph, version, origin, hubId, selfId, nodes[], hubNode, isHubSelf }
// graph 缺失时用 sources + 本机拼一张兜底图谱（旧后端兼容），此时 hasGraph=false，
// 不允许点节点切换汇聚端（后端没有 switch 接口，切了也没用）。

function normalizeNode (raw, hubId, selfId) {
  const id = raw.id
  const isHub = hubId ? id === hubId : raw.isHub === true
  const isSelf = selfId ? id === selfId : raw.isSelf === true
  return {
    id: id,
    name: typeof raw.name === 'string' && raw.name ? raw.name : id,
    kind: typeof raw.kind === 'string' ? raw.kind : 'other',
    ip: typeof raw.ip === 'string' && raw.ip ? raw.ip : ipFromId(id),
    audioPort: Number.isInteger(raw.audioPort) && raw.audioPort > 0
      ? raw.audioPort
      : portFromId(id),
    online: raw.online !== false,
    isHub: !!isHub,
    isSelf: !!isSelf,
    streamId: Number.isInteger(raw.streamId) && raw.streamId > 0 ? raw.streamId : 0,
    level: typeof raw.level === 'number' && Number.isFinite(raw.level) ? raw.level : -120,
    occupancy: typeof raw.occupancy === 'number' && Number.isFinite(raw.occupancy) ? raw.occupancy : 0,
    gain: typeof raw.gain === 'number' && Number.isFinite(raw.gain) ? raw.gain : 1,
    muted: raw.muted === true
  }
}

// 旧后端 / 启动瞬间没有 graph：用 sources + 本机拼一张能看的图
function legacyGraph (state) {
  const sources = state && Array.isArray(state.sources) ? state.sources : []
  const selfId = state && typeof state.ip === 'string' && state.ip ? state.ip : 'self'
  const nodes = [{
    id: selfId,
    name: '本机',
    kind: 'pc',
    ip: ipFromId(selfId),
    audioPort: portFromId(selfId),
    online: true,
    isHub: true,
    isSelf: true,
    streamId: 0,
    level: typeof state.busLevel === 'number' ? state.busLevel : -120,
    occupancy: 0,
    gain: 1,
    muted: false
  }]
  for (const s of sources) {
    if (!s || typeof s !== 'object' || !s.confirmed) continue
    const streamId = Number.isInteger(s.id) && s.id > 0 ? s.id : 0
    if (!streamId) continue
    nodes.push({
      id: (typeof s.peerIp === 'string' && s.peerIp ? s.peerIp : 'peer') + ':stream-' + streamId,
      name: typeof s.peerName === 'string' && s.peerName ? s.peerName : ('设备 ' + streamId),
      kind: 'other',
      ip: typeof s.peerIp === 'string' ? s.peerIp : '',
      audioPort: 47000,
      online: true,
      isHub: false,
      isSelf: false,
      streamId: streamId,
      level: typeof s.level === 'number' ? s.level : -120,
      occupancy: typeof s.occupancy === 'number' ? s.occupancy : 0,
      gain: typeof s.gain === 'number' ? s.gain : 1,
      muted: s.muted === true
    })
  }
  return {
    hasGraph: false,
    version: 0,
    origin: '',
    hubId: selfId,
    selfId: selfId,
    nodes: nodes,
    hubNode: nodes[0],
    isHubSelf: true
  }
}

function collectGraph (state) {
  const g = state && state.graph && typeof state.graph === 'object' ? state.graph : null
  const raw = g && Array.isArray(g.nodes) ? g.nodes : []
  const clean = []
  for (const n of raw) {
    if (!n || typeof n !== 'object' || typeof n.id !== 'string' || !n.id) continue
    clean.push(n)
  }
  if (!g || clean.length === 0) return legacyGraph(state)

  let selfId = typeof g.selfId === 'string' ? g.selfId : ''
  if (!selfId) {
    const selfNode = clean.find((n) => n.isSelf === true)
    if (selfNode) selfId = selfNode.id
  }
  const hubId = typeof g.hubId === 'string' ? g.hubId : ''
  const nodes = clean.map((n) => normalizeNode(n, hubId, selfId))
  nodes.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
  let hubNode = hubId ? nodes.find((n) => n.id === hubId) : null
  if (!hubNode && !hubId) hubNode = nodes.find((n) => n.isHub) || null
  return {
    hasGraph: true,
    version: typeof g.version === 'number' && Number.isFinite(g.version) ? g.version : 0,
    origin: typeof g.origin === 'string' ? g.origin : '',
    hubId: hubNode ? hubNode.id : hubId,
    selfId: selfId,
    nodes: nodes,
    hubNode: hubNode,
    isHubSelf: !!selfId && !!hubNode && hubNode.id === selfId
  }
}

// 该节点对应的音频流 id（0 = 没有流）。优先用后端图谱给的 streamId，
// 旧后端时退回 sources（按 IP / 名字匹配）。
function streamIdForNode (node, state) {
  if (!node || node.ghost) return 0
  if (Number.isInteger(node.streamId) && node.streamId > 0) return node.streamId
  const sources = state && Array.isArray(state.sources) ? state.sources : []
  if (node.ip) {
    const byIp = sources.find((s) => s && s.confirmed && s.peerIp === node.ip)
    if (byIp && Number.isInteger(byIp.id) && byIp.id > 0) return byIp.id
  }
  if (node.name) {
    const byName = sources.find((s) => s && s.confirmed && s.peerName === node.name)
    if (byName && Number.isInteger(byName.id) && byName.id > 0) return byName.id
  }
  return 0
}

// 节点显示电平：PC 作为音源时自节点取 pcSource*（协议 §9.2）；
// 本机作为汇聚端时用总线电平更直观。
function displayLevel (state, node) {
  if (!node || node.ghost) return null
  if (node.isSelf) {
    if (state && state.pcSource) {
      return typeof state.pcSourceLevel === 'number' ? state.pcSourceLevel : null
    }
    if (state && typeof state.busLevel === 'number') return state.busLevel
    return typeof node.level === 'number' ? node.level : null
  }
  return typeof node.level === 'number' ? node.level : null
}

function hasFlow (state, node) {
  if (!node || node.ghost) return false
  if (node.isSelf) return !!(state && state.pcSource)
  return streamIdForNode(node, state) > 0
}

// ============================================================ 网络图（3D 场景）
//
// 绘制层从 SVG 换成 <canvas id="graph"> + ui/scene3d.js（纯 Canvas 2D 手写透视投影，
// 不引依赖；为什么不用 WebGL 见 docs/3D-...方案.md §2.1.1 与 ui/scene3d.js 头注释）。
//   - v1.3 物理房间模型：听者固定在原点朝 -Z，声源在地面自由摆位（默认落在预计算散点槽位，
//     距离 0.85–2.2 m，按设备 id 持久化）；球的屏幕大小随距离缩放（近大远小），
//     与音量衰减同向；名称行同时显示 "x.xx m"，双击切换汇聚端、拖动自由摆位；
//   - hub 永远在原点 → 投影恒为屏幕 (460,208)，与旧中心完全一致；
//   - 交互（点选 / 切换 / 音量面板 / 操作条 / 同步状态）全部留在本文件，
//     scene 只负责绘制与"拾取后回调"，回调仍走 onNodeClick 旧路径。
// scene3d.js 缺失（旧包 / 加载失败）时静默降级：图谱区空白，其他界面照常可用。

let scene = null
let sceneBroken = false

function ensureScene () {
  if (scene || sceneBroken) return scene
  const canvas = $('graph')
  const api = window.Scene3D
  if (!canvas || !api || typeof api.create !== 'function') { sceneBroken = true; return null }
  try {
    scene = api.create({
      canvas: canvas,
      onSelect: (nodeId) => onNodeClick(nodeId),
      onActivate: (nodeId) => onNodeActivate(nodeId),
      onDragEnd: () => { pumpSpatial() }     // 拖完立即推一次位置，不等 50 ms tick
    })
  } catch (err) {
    sceneBroken = true
    scene = null
    console.error('[scene] 3D 场景初始化失败，图谱区降级为空: ' + (err && err.message))
  }
  return scene
}

// 场景只吃动态字段（电平/流强/选中/PC 音源旁路）；结构变化走 setGraph。
function sceneDynamic (state, graph) {
  const levels = {}
  const flows = {}
  for (const n of graph.nodes) {
    levels[n.id] = displayLevel(state, n)
    flows[n.id] = hasFlow(state, n)
  }
  return {
    levels: levels,
    flows: flows,
    selectedId: selected ? selected.nodeId : null,
    pcSource: !!(state && state.pcSource)
  }
}

// 点选/切换后的即时反馈：不走签名判断，只刷新动态字段
function refreshScene (state, graph) {
  const s = ensureScene()
  if (!s) return
  s.setDynamic(sceneDynamic(state, graph))
}

// ============================================================ 3D 位置 → 空间音频（20 Hz 上报）
//
// docs/3D-...方案.md §2.4/§3.2：listener 变化才发、静止 2 s 心跳；source 位置变化才发
// （拖拽结束 / 首次布局 / 结构变化 / hub 切换都体现为"变了"）；失败 3 次后静默退避 5 s 再试。
// 旧内核没有 /api/listener、/api/source/spatial 时主进程回 unsupported：停发，不弹错。
const SP = { dt: 50, epsPos: 1e-3, epsAng: 0.5, epsSrc: 0.01, heartbeat: 2000, maxFails: 3 }

let spatialTimer = 0
let spatialInflight = false
let spatialFails = 0
let spatialDisabled = false
let spatialRetryAt = 0
let spatialLastSentAt = 0
let spatialLastL = null
const spatialLastSrc = new Map()

function spatialSupported () {
  return !!(window.hub && typeof window.hub.setListener === 'function' &&
    typeof window.hub.setSourceSpatial === 'function')
}

function startSpatialPump () {
  if (spatialTimer || !spatialSupported()) return
  spatialTimer = setInterval(pumpSpatial, SP.dt)
}

function listenerChanged (l) {
  const p = spatialLastL
  if (!p) return true
  return Math.abs(l.x - p.x) > SP.epsPos || Math.abs(l.y - p.y) > SP.epsPos ||
    Math.abs(l.z - p.z) > SP.epsPos || Math.abs(l.yaw - p.yaw) > SP.epsAng ||
    Math.abs(l.pitch - p.pitch) > SP.epsAng
}

function pumpSpatial () {
  const s = scene
  if (!s || !lastState || screen !== 'control' || !spatialSupported()) return
  if (spatialDisabled) {
    if (Date.now() < spatialRetryAt) return
    spatialDisabled = false
    spatialFails = 0
  }
  if (spatialInflight) return                 // 背压：上一发没回就跳过本拍

  const frame = s.spatialFrame()
  if (!frame || !Array.isArray(frame.sources)) return
  const now = Date.now()
  const jobs = []

  const heartbeatDue = !!spatialLastL && now - spatialLastSentAt >= SP.heartbeat
  if ((listenerChanged(frame.listener) || heartbeatDue) && now - spatialLastSentAt >= SP.dt) {
    spatialLastSentAt = now
    spatialLastL = frame.listener
    jobs.push(window.hub.setListener(frame.listener))
  }

  const alive = new Set()
  for (const src of frame.sources) {
    alive.add(src.id)
    const prev = spatialLastSrc.get(src.id)
    if (prev && Math.abs(prev[0] - src.x) <= SP.epsSrc &&
        Math.abs(prev[1] - src.z) <= SP.epsSrc && prev[2] === src.enabled) continue
    spatialLastSrc.set(src.id, [src.x, src.z, src.enabled])
    jobs.push(window.hub.setSourceSpatial(src.id, src.x, src.z, src.enabled))
  }
  // 节点从图谱里消失（含"它被切成新 hub"）：撤掉它的摆放，避免内核里留幽灵位置
  for (const id of Array.from(spatialLastSrc.keys())) {
    if (alive.has(id)) continue
    const prev = spatialLastSrc.get(id)
    spatialLastSrc.delete(id)
    if (prev) jobs.push(window.hub.setSourceSpatial(id, 0, 0, false))
  }
  if (!jobs.length) return

  spatialInflight = true
  const safe = jobs.map((p) => Promise.resolve(p).catch(() => ({ ok: false, unsupported: true })))
  Promise.all(safe).then((results) => {
    spatialInflight = false
    let unsupported = false
    for (const r of results) if (r && r.unsupported) unsupported = true
    if (unsupported) {
      if (++spatialFails >= SP.maxFails) {
        spatialDisabled = true
        spatialRetryAt = Date.now() + 5000
      }
    } else {
      spatialFails = 0
    }
  }, () => {
    spatialInflight = false
    if (++spatialFails >= SP.maxFails) {
      spatialDisabled = true
      spatialRetryAt = Date.now() + 5000
    }
  })
}

function graphSignature (state, graph) {
  const nodeSig = graph.nodes.map((n) =>
    n.id + ':' + n.name + ':' + (n.online ? '1' : '0') + ':' +
    (n.isHub ? 'h' : '') + (n.isSelf ? 's' : '') + ':' + n.streamId
  ).join('|')
  return (graph.hasGraph ? 'g' + graph.version : 'legacy') +
    '|hub=' + graph.hubId + '|self=' + graph.selfId + '|' + nodeSig +
    '|src=' + (state && state.pcSource ? '1' : '0')
}

let graphBuiltSig = ''

function renderGraph (state, graph) {
  const s = ensureScene()
  if (!s) return
  const sig = graphSignature(state, graph)
  // 只把"结构"纳入签名。电平每 200ms 都在变，绝不能因此重排布局 ——
  // 一重排，用户刚按下的球就被换位置，点击直接丢失（表现为"点不到"）。
  if (sig !== graphBuiltSig) {
    graphBuiltSig = sig
    s.setGraph(graph)
  }
  s.setActive(true)
  s.setDynamic(sceneDynamic(state, graph))
}



// ============================================================ 图谱交互

function onNodeClick (nodeId) {
  const state = lastState
  if (!state || !nodeId) return
  const graph = collectGraph(state)
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return
  selected = { nodeId: node.id }
  refreshScene(state, graph)

  if (node.isHub) {
    // 点汇聚端：只显示信息，不提供切换
    showHubAction(node, graph)
    if (graph.isHubSelf) showVolumePanel(state, graph, node)
    else hideVolumePanel()
    return
  }

  if (graph.hasGraph) showSwitchAction(node, graph)
  else hideActionBar()

  const streamId = streamIdForNode(node, state)
  if (streamId || (node.isSelf && state.pcSource)) showVolumePanel(state, graph, node)
  else hideVolumePanel()
}

// 双击节点（scene 回调）：等同于"点一下 → 点操作条里的确认"。
// 非汇聚端且已有待切换操作条时直接切；否则退化为一次普通点选，不改变任何语义。
function onNodeActivate (nodeId) {
  const state = lastState
  if (!state || !nodeId) return
  const graph = collectGraph(state)
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node || node.isHub) return
  onNodeClick(node.id)
  if (action && action.mode === 'switch' && action.nodeId === node.id) doSwitch()
}

function showActionBar (parts, okText, cancelText) {
  const bar = $('graphAction')
  const textEl = $('actionText')
  const okBtn = $('actionSwitch')
  const cancelBtn = $('actionCancel')
  if (!bar || !textEl || !okBtn || !cancelBtn) return
  textEl.textContent = ''
  for (const p of parts) {
    if (typeof p === 'string' || typeof p === 'number') {
      textEl.appendChild(document.createTextNode(String(p)))
    } else if (p) {
      const span = document.createElement('span')
      span.className = p.cls || ''
      span.textContent = p.text
      textEl.appendChild(span)
    }
  }
  if (okText) {
    okBtn.style.display = ''
    okBtn.textContent = okText
  } else {
    okBtn.style.display = 'none'
  }
  cancelBtn.textContent = cancelText || '取消'
  bar.style.display = ''
}

function hideActionBar () {
  const bar = $('graphAction')
  if (bar) bar.style.display = 'none'
  const okBtn = $('actionSwitch')
  if (okBtn) okBtn.style.display = 'none'
  action = null
}

function showSwitchAction (node, graph) {
  action = { mode: 'switch', nodeId: node.id }
  const parts = []
  if (!node.online) parts.push({ text: '该设备当前离线。', cls: 'warn' })
  parts.push('把汇聚端切到 ' + node.name + '？')
  if (node.isSelf) parts.push('（本机将负责收流 / 混音 / 播放）')
  showActionBar(parts, '切换', '取消')
}

function showHubAction (node, graph) {
  action = { mode: 'hub', nodeId: node.id }
  const info = []
  if (node.ip) info.push(node.ip + (node.audioPort ? ':' + node.audioPort : ''))
  info.push(node.online ? '在线' : '离线')
  info.push('图谱 v' + (graph.version || 0))
  if (node.isSelf) info.push('本机')
  showActionBar(['当前汇聚端：' + node.name + ' · ' + info.join(' · ')], null, '关闭')
}

// 切换后的同步状态提示
function setSync (text, tone) {
  syncText = text || ''
  syncTone = tone || ''
  renderSyncLine()
}

function renderSyncLine () {
  const el = $('graphStatus')
  if (!el) return
  el.textContent = syncText
  el.className = 'graphstatus' + (syncTone ? ' ' + syncTone : '')
  el.style.display = syncText ? '' : 'none'
}

// 新图谱到达后确认同步：hubId 已指向目标且版本比切换前新 → "已同步（vN）"
function checkSwitchSync (graph) {
  if (!pendingSwitch || !graph || !graph.hasGraph) return
  if (graph.hubId !== pendingSwitch.nodeId) return
  if (graph.version > pendingSwitch.fromVersion) {
    if (pendingSwitch.timer) clearTimeout(pendingSwitch.timer)
    const version = graph.version
    pendingSwitch = null
    setSync('已同步（v' + version + '）', 'ok')
  }
}

async function doSwitch () {
  if (!action || action.mode !== 'switch' || !action.nodeId) return
  const nodeId = action.nodeId
  const before = lastState ? collectGraph(lastState) : null
  const fromVersion = before ? before.version : 0
  hideActionBar()
  pendingSwitch = { nodeId: nodeId, fromVersion: fromVersion, timer: null }
  setSync('已切换，正在同步…', '')
  // 兜底：6 秒还没等到新图谱就说明同步没成
  pendingSwitch.timer = setTimeout(() => {
    if (pendingSwitch && pendingSwitch.nodeId === nodeId) {
      pendingSwitch = null
      setSync('仍未收到新图谱，请检查网络或音频核心状态', 'warn')
    }
  }, 6000)

  let res = null
  try {
    res = await window.hub.switchHub(nodeId)
  } catch (err) {
    res = { ok: false, error: (err && err.message) || '调用失败' }
  }
  if (!res || !res.ok) {
    if (pendingSwitch && pendingSwitch.timer) clearTimeout(pendingSwitch.timer)
    pendingSwitch = null
    const reason = res && res.error ? res.error : '音频核心未支持图谱切换接口'
    setSync('切换失败：' + reason, 'warn')
    return
  }
  // 后端一般会立刻返回新图谱：先用它刷新一次，不必等 200ms 轮询
  if (res.graph && lastState && typeof res.graph === 'object') {
    render(Object.assign({}, lastState, { graph: res.graph }))
  }
}

// ============================================================ 音量面板

function hideVolumePanel () {
  const panel = $('volumePanel')
  if (panel) panel.style.display = 'none'
}

function showVolumePanel (state, graph, node) {
  const panel = $('volumePanel')
  const slider = $('volSlider')
  const nameEl = $('volName')
  const pctEl = $('volPct')
  const muteBtn = $('volMute')
  if (!panel || !slider || !nameEl || !pctEl) return
  const hubName = graph.hubNode && !graph.hubNode.ghost ? graph.hubNode.name : '汇聚端'

  // 汇聚端节点：本机是 hub 时调总音量；远端 hub 的音量由它自己控制
  if (node.isHub) {
    if (graph.isHubSelf) {
      nameEl.textContent = '汇聚端总音量'
      slider.disabled = false
      if (!volDragging) {
        slider.value = Math.round((typeof state.master === 'number' ? state.master : 1) * 100)
      }
      slider.oninput = () => {
        const g = slider.value / 100
        pctEl.textContent = pct(g)
        slider.style.setProperty('--p', (g * 100 / 1.5) + '%')
        window.hub.setMaster(g)
      }
    } else {
      nameEl.textContent = '当前汇聚端：' + node.name + ' · 音量由该设备控制'
      slider.disabled = true
    }
    if (muteBtn) muteBtn.style.display = 'none'
    pctEl.textContent = slider.disabled ? '—' : pct(slider.value / 100)
    if (!slider.disabled) slider.style.setProperty('--p', (slider.value / 1.5) + '%')
    panel.style.display = ''
    return
  }

  // 本机是音源：音量由远端汇聚端调，本机只显示状态
  if (node.isSelf && state.pcSource) {
    nameEl.textContent = '本机是音源 · 音量由 ' + hubName + ' 调节'
    slider.disabled = true
    slider.value = 100
    pctEl.textContent = '—'
    if (muteBtn) muteBtn.style.display = 'none'
    panel.style.display = ''
    return
  }

  const streamId = streamIdForNode(node, state)
  if (!streamId) {
    hideVolumePanel()
    return
  }
  nameEl.textContent = node.name || ('设备 ' + streamId)
  slider.disabled = false
  // 正在拖的时候不回写，否则滑杆会被状态推回去，手感像"抓不住"
  if (!volDragging) slider.value = Math.round(node.gain * 100)
  slider.oninput = () => {
    const g = slider.value / 100
    pctEl.textContent = pct(g)
    slider.style.setProperty('--p', (g * 100 / 1.5) + '%')
    window.hub.setSource(streamId, { gain: g })
  }
  pctEl.textContent = pct(slider.value / 100)
  slider.style.setProperty('--p', (slider.value / 1.5) + '%')
  if (muteBtn) {
    muteBtn.style.display = ''
    muteBtn.textContent = node.muted ? '已静音' : '静音'
    muteBtn.classList.toggle('on', !!node.muted)
  }
  panel.style.display = ''
}

// 每次状态刷新时同步面板内容（选中节点可能已失效）
function refreshVolumePanel (state, graph) {
  if (!selected) return
  const node = graph.nodes.find((n) => n.id === selected.nodeId)
  if (!node) {
    hideVolumePanel()
    return
  }
  const streamId = streamIdForNode(node, state)
  if (node.isHub) {
    if (graph.isHubSelf) showVolumePanel(state, graph, node)
    else hideVolumePanel()
    return
  }
  if (streamId || (node.isSelf && state.pcSource)) showVolumePanel(state, graph, node)
  else hideVolumePanel()
}

// 拖动状态：按下到松开之间不回写滑杆
$('volSlider').addEventListener('pointerdown', () => { volDragging = true })
window.addEventListener('pointerup', () => { volDragging = false })

$('volMute').addEventListener('click', () => {
  if (!lastState || !selected) return
  const graph = collectGraph(lastState)
  const node = graph.nodes.find((n) => n.id === selected.nodeId)
  if (!node || node.isHub) return
  const streamId = streamIdForNode(node, lastState)
  if (!streamId) return
  window.hub.setSource(streamId, { muted: !node.muted })
})

$('actionSwitch').addEventListener('click', () => { doSwitch() })
$('actionCancel').addEventListener('click', () => {
  hideActionBar()
})

// ============================================================ 汇聚端信息条

function renderHubBar (state, graph) {
  const nowEl = $('hubNow')
  const noteEl = $('hubNote')
  if (nowEl) {
    const hub = graph.hubNode
    if (hub && !hub.ghost) {
      nowEl.textContent = hub.name + (hub.ip ? '（' + hub.ip + '）' : '') +
        (hub.online ? '' : ' 离线')
      nowEl.classList.toggle('off', !hub.online)
    } else {
      nowEl.textContent = graph.hubId ? '汇聚端未知（' + graph.hubId + '）' : '汇聚端未知'
      nowEl.classList.add('off')
    }
  }
  if (noteEl) {
    if (graph.isHubSelf) {
      noteEl.textContent = '本机承担收流 / 混音 / 播放'
    } else if (state.pcSource) {
      const hubName = graph.hubNode && !graph.hubNode.ghost ? graph.hubNode.name : '远端'
      noteEl.textContent = '本机声音正送往 ' + hubName
    } else {
      noteEl.textContent = '本机空闲（未作为音源发送）'
    }
  }
}

// ============================================================ 连接界面

let connectSig = ''
let ctrlSig = ''

// 请求栏：对端发来的邀请 + 待确认的连接请求，合成一条，避免互相清空
let reqBarSig = ''

function renderRequestBar (container, state) {
  if (!container) return false
  const invites = Array.isArray(state.invites) ? state.invites : []
  const pending = Array.isArray(state.pending) ? state.pending : []
  const sig = JSON.stringify({
    i: invites.map((x) => x.ip + ':' + x.name),
    p: pending.map((x) => x.streamId + ':' + x.name)
  })
  if (sig === reqBarSig) return invites.length + pending.length > 0
  reqBarSig = sig

  container.innerHTML = ''

  // 邀请：对方希望本机把声音送过去
  for (const inv of invites) {
    const el = document.createElement('div')
    el.className = 'req'
    el.innerHTML =
      '<span class="who"></span>' +
      '<span class="ip"></span>' +
      '<span class="ask">邀请你把本机声音送过去</span>' +
      '<button class="btn ok">同意</button>' +
      '<button class="btn no">拒绝</button>'
    el.querySelector('.who').textContent = inv.name || inv.ip
    el.querySelector('.ip').textContent = inv.ip || ''
    el.querySelector('.ok').addEventListener('click', () => window.hub.acceptInvite(inv.ip))
    el.querySelector('.no').addEventListener('click', () => window.hub.rejectInvite(inv.ip))
    container.appendChild(el)
  }

  // 待确认：对方要把声音送到本机
  for (const p of pending) {
    const el = document.createElement('div')
    el.className = 'req'
    el.innerHTML =
      '<span class="who"></span>' +
      '<span class="ip"></span>' +
      '<span class="ask">请求把声音送到这里</span>' +
      '<button class="btn ok">同意</button>' +
      '<button class="btn no">拒绝</button>'
    el.querySelector('.who').textContent = p.name || ('设备 ' + p.streamId)
    el.querySelector('.ip').textContent = p.ip || ''
    el.querySelector('.ok').addEventListener('click', () => window.hub.accept(p.streamId))
    el.querySelector('.no').addEventListener('click', () => window.hub.reject(p.streamId))
    container.appendChild(el)
  }
  return invites.length + pending.length > 0
}

// 设备卡片列表
function renderPeers (container, state) {
  if (!container) return
  container.innerHTML = ''
  const peers = Array.isArray(state.peers) ? state.peers : []
  for (const p of peers) {
    if (!p) continue
    const el = document.createElement('div')
    el.className = 'card peer'
    const role = (p.canReceive && p.canSend) ? '音源 / 汇聚' : (p.canReceive ? '汇聚端' : '音源')
    el.innerHTML =
      '<div class="top"><div class="name"><span class="dot dim"></span></div>' +
      '<div class="db"></div></div>' +
      '<div class="row"><span class="ask"></span></div>' +
      '<div class="row" style="margin-top:10px">' +
        '<button class="btn go">邀请它把声音送过来</button>' +
      '</div>'
    el.querySelector('.name').appendChild(document.createTextNode(p.name || ''))
    el.querySelector('.db').textContent = role
    el.querySelector('.ask').textContent = p.ip + ':' + p.audioPort
    el.querySelector('.go').addEventListener('click', () =>
      window.hub.invitePeer({ ip: p.ip, audioPort: p.audioPort }))
    container.appendChild(el)
  }
}

function renderConnect (state) {
  // 200ms 轮询会把整片 DOM 重建一次，用户正在点的那个按钮就被换掉了。
  // 只有"设备集合/待确认集合"真的变了才重建。
  const sig = JSON.stringify({
    r: (Array.isArray(state.pending) ? state.pending : [])
      .map((x) => x.streamId + ':' + x.name + ':' + x.ip),
    p: (Array.isArray(state.peers) ? state.peers : [])
      .map((x) => x.name + '@' + x.ip + ':' + x.audioPort)
  })
  if (sig === connectSig) return
  connectSig = sig

  const hasReq = renderRequestBar($('reqGrid'), state)
  const sectReq = $('sectReq')
  if (sectReq) sectReq.style.display = hasReq ? '' : 'none'
  renderPeers($('peerGrid'), state)
  const sectPeer = $('sectPeer')
  if (sectPeer) sectPeer.style.display = (state.peers || []).length ? '' : 'none'
}

// 控制界面里的"新设备"区：会话进行中也能接人
function renderControlDevices (state) {
  const sig = JSON.stringify({
    r: (Array.isArray(state.pending) ? state.pending : []).map((x) => x.streamId + ':' + x.name),
    i: (Array.isArray(state.invites) ? state.invites : []).map((x) => x.ip + ':' + x.name),
    p: (Array.isArray(state.peers) ? state.peers : [])
      .map((x) => x.name + '@' + x.ip + ':' + x.audioPort)
  })
  if (sig === ctrlSig) return
  ctrlSig = sig
  renderRequestBar($('ctrlReq'), state)
  renderPeers($('ctrlPeers'), state)
}

// ============================================================ 瞬时会话通知
//
// 之前"新设备接入"只是默默多一个球，用户根本感知不到，
// 于是经常以为"手机切换了但电脑没反应"。这里给它一条明确的提示。
let knownSourceIds = new Set()
let noticeText = ''
let noticeUntil = 0

function showNotice (text) {
  noticeText = text
  noticeUntil = Date.now() + 4000
}

function checkNewSources (state) {
  const ids = new Set()
  const sources = Array.isArray(state.sources) ? state.sources : []
  for (const s of sources) {
    if (!s || !s.confirmed) continue
    ids.add(s.id)
    if (!knownSourceIds.has(s.id)) {
      showNotice((s.peerName || ('设备 ' + s.id)) + ' 已接入')
    }
  }
  knownSourceIds = ids
}

// ============================================================ 主刷新

function render (state) {
  if (!state || typeof state !== 'object') return
  lastState = state
  checkNewSources(state)

  const graph = collectGraph(state)
  checkSwitchSync(graph)

  const b = $('banner')
  if (b) {
    if (state.error) {
      b.textContent = state.error
      b.classList.remove('hidden', 'ok')
    } else if (noticeText && Date.now() < noticeUntil) {
      b.textContent = noticeText
      b.classList.remove('hidden')
      b.classList.add('ok')
    } else {
      b.classList.add('hidden')
      b.classList.remove('ok')
    }
  }
  const addrEl = $('addr')
  if (addrEl) addrEl.textContent = state.ip || '—'

  const sources = Array.isArray(state.sources) ? state.sources : []
  const confirmedCount = sources.filter((s) => s && s.confirmed).length
  // 控制屏显示条件（协议 §9.2）：图谱节点数 >= 2（网络已形成）或本机有音频流
  const networkUp = graph.nodes.length >= 2
  const localFlow = confirmedCount > 0 || !!state.pcSource
  const anyConnection = networkUp || localFlow

  // 自动切换屏幕：有图谱网络 / 本机有流 → 控制；否则 → 连接
  const next = anyConnection ? 'control' : 'connect'
  if (next !== screen) {
    selected = null
    hideActionBar()
    hideVolumePanel()
    graphBuiltSig = ''
    showScreen(next)
    // 连接界面时停画（canvas 也 display:none），省掉后台 rAF 重绘
    if (scene) scene.setActive(next === 'control')
  }

  if (screen === 'connect') {
    renderConnect(state)
  } else {
    renderControlDevices(state)
    renderGraph(state, graph)
    renderHubBar(state, graph)
    refreshVolumePanel(state, graph)
  }

  // 回到连接屏 / 选中的节点已从图谱里消失 → 收起操作条与音量面板
  const selectionGone = !!selected && !graph.nodes.some((n) => n.id === selected.nodeId)
  if (screen === 'connect' || selectionGone) {
    selected = null
    hideActionBar()
    hideVolumePanel()
    if (scene) scene.setDynamic(sceneDynamic(state, graph))
  }

  renderSyncLine()

  // 回显自动同意状态（用户刚点过就别覆盖，避免和点击打架）
  const auto = $('autoAccept')
  if (auto && !autoTouched) auto.checked = !!state.autoAccept

  const statsEl = $('stats')
  if (statsEl) {
    statsEl.innerHTML =
      '<span>设备 <b>' + graph.nodes.length + '</b> 台</span>' +
      (graph.hasGraph ? '<span>图谱 <b>v' + graph.version + '</b></span>' : '') +
      '<span>总线 <b>' + dbText(state.busLevel) + '</b></span>' +
      '<span>码率 <b>' + fmtNumber(state.kbps, 0) + '</b> kbps</span>' +
      '<span>坏包 <b>' + (Number.isFinite(state.malformed) ? state.malformed : 0) + '</b></span>' +
      '<span>渲染欠载 <b>' + (Number.isFinite(state.renderUnderruns) ? state.renderUnderruns : 0) + '</b></span>'
  }
}

// ============================================================ 事件

$('copyBtn').addEventListener('click', async (e) => {
  const text = $('addr').textContent
  try {
    await navigator.clipboard.writeText(text)
    e.target.textContent = '已复制'
    setTimeout(() => { e.target.textContent = '复制' }, 1200)
  } catch (err) { /* 剪贴板不可用时静默失败 */ }
})

// ---- 主动扫描 ----
$('scanBtn').addEventListener('click', async () => {
  const btn = $('scanBtn')
  const hint = $('scanHint')
  btn.disabled = true
  btn.classList.add('busy')
  btn.textContent = '扫描中…'
  hint.className = 'hint'
  hint.textContent = '正在向局域网广播探测…'

  const res = await window.hub.scan()

  btn.disabled = false
  btn.classList.remove('busy')
  btn.textContent = '扫描'

  if (!res || !res.ok) {
    hint.className = 'hint warn'
    hint.textContent = '扫描失败：音频核心没有响应'
    return
  }
  const n = (res.peers || []).length
  if (n === 0) {
    hint.className = 'hint warn'
    hint.textContent = '没有发现设备。请确认：① 手机已打开 App；② 手机和电脑在同一 WiFi / 网段'
  } else {
    hint.className = 'hint ok'
    hint.textContent = '扫描完成，发现 ' + n + ' 台设备'
  }
  // 立即用扫描结果刷新一次列表，不等下一次 200ms 轮询
  render(lastState)
})

// ---- 按 IP 直接邀请（广播被拦时的兜底） ----
$('manualInviteBtn').addEventListener('click', async () => {
  const raw = $('manualIp').value.trim()
  const hint = $('scanHint')
  if (!raw) {
    hint.className = 'hint warn'
    hint.textContent = '请先填对方的地址'
    return
  }
  let ip = raw
  let port = 47000
  const colon = raw.lastIndexOf(':')
  if (colon > 0) {
    ip = raw.slice(0, colon)
    port = parseInt(raw.slice(colon + 1), 10) || 47000
  }
  const res = await window.hub.invitePeer({ ip, audioPort: port })
  if (res && res.ok) {
    hint.className = 'hint ok'
    hint.textContent = '已向 ' + ip + ':' + port + ' 发出邀请，等对方确认'
  } else {
    hint.className = 'hint warn'
    hint.textContent = '邀请发送失败：地址格式不对或音频核心没响应'
  }
})

// 自动同意：手机点一下就连上，不必在电脑上再点一次（只影响非成员连接请求）
$('autoAccept').addEventListener('change', async (e) => {
  autoTouched = true
  await window.hub.setAutoAccept(e.target.checked)
})

$('ctrlAddBtn').addEventListener('click', () => {
  const panel = $('ctrlAddPanel')
  panel.style.display = panel.style.display === 'none' ? '' : 'none'
})

$('ctrlInviteBtn').addEventListener('click', async () => {
  const raw = $('ctrlIp').value.trim()
  if (!raw) return
  let ip = raw
  let port = 47000
  const colon = raw.lastIndexOf(':')
  if (colon > 0) {
    ip = raw.slice(0, colon)
    port = parseInt(raw.slice(colon + 1), 10) || 47000
  }
  await window.hub.invitePeer({ ip, audioPort: port })
})

$('leaveBtn').addEventListener('click', async () => {
  const s = await window.hub.getState()
  const sources = s && Array.isArray(s.sources) ? s.sources : []
  for (const src of sources) {
    if (src && src.confirmed) window.hub.disconnect(src.id)
  }
  // 用户主动"断开所有"：连关系表一起清掉；后端同时把图谱重置为只有本机
  await window.hub.clearSession()
  selected = null
  hideActionBar()
  hideVolumePanel()
  setSync('', '')
})

// ============================================================ 订阅

// 调试快照：排查"界面没切换"时直接读它，比看日志快
window.__hubDebug = () => {
  const graph = lastState ? collectGraph(lastState) : null
  const sceneDebug = scene ? scene.debug() : null
  return {
    screen: screen,
    hasGraph: !!(graph && graph.hasGraph),
    version: graph ? graph.version : null,
    hubId: graph ? graph.hubId : '',
    selfId: graph ? graph.selfId : '',
    nodes: graph ? graph.nodes.length : 0,
    hubOnline: !!(graph && graph.hubNode && graph.hubNode.online),
    selected: selected ? selected.nodeId : null,
    pendingSwitch: pendingSwitch ? pendingSwitch.nodeId : null,
    pcSource: !!(lastState && lastState.pcSource),
    controlShown: !!$('screenControl') && $('screenControl').style.display !== 'none',
    // 3D 场景（e2e 用：nodeScreen 坐标点击、positions 校验、azDeg 方位校验）
    scene: sceneDebug,
    spatial: {
      supported: spatialSupported(),
      disabled: spatialDisabled,
      fails: spatialFails,
      sources: spatialLastSrc.size,
      listener: spatialLastL
    }
  }
}

const unsubscribe = window.hub.onState(render)
window.addEventListener('beforeunload', unsubscribe)
startSpatialPump()          // 旧内核/旧 preload 下 spatialSupported()=false，泵不启动
window.hub.getState().then(render)