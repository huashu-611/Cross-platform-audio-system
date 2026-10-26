// 渲染层：两个屏幕。
//
//   连接界面 —— 建立连接（设备列表 / 待确认请求）
//   控制界面 —— 可视化网络图：球 = 设备，线 = 连接；汇聚端的球更大。
//               点任意球调它的音量，实时生效。
//
// 切换逻辑：出现第一条已确认的连接 → 自动进控制界面；
// 连接全部断开 → 回连接界面。汇聚端默认是本机（电脑）。

let screen = 'connect'          // 'connect' | 'control'
let selected = null             // 被点选的球 {kind:'hub'|'source', id}

// 角色：连接之后才确定。默认 PC 为汇聚端（本机）。
let hubSelf = true
let hubLabel = '本机'
let hubSelectSig = ''           // 选择器选项的签名，避免每 tick 重建

const $ = (id) => document.getElementById(id)

// ============================================================ 工具

const pct = (v) => Math.round(v * 100) + '%'
const dbText = (v) => (v <= -119 ? '静音' : v.toFixed(1) + ' dBFS')
const levelPct = (level) => Math.max(0, Math.min(100, (level + 60) / 0.6))

function post (path, obj) {
  fetch(path, { method: 'POST', body: JSON.stringify(obj) }).catch(() => {})
}

// ============================================================ 屏幕切换

function showScreen (next) {
  screen = next
  $('screenConnect').style.display = next === 'connect' ? '' : 'none'
  $('screenControl').style.display = next === 'control' ? '' : 'none'
  document.querySelector('h1 span').textContent =
    next === 'control' ? '控制台' : '连接设备'
}

// ============================================================ 网络图

// 布局：汇聚端在中间的大球，其它设备围着它排一圈
function layoutGraph (sources, hubLabel) {
  const cx = 460, cy = 200
  const nodes = [{ id: 'hub', kind: 'hub', name: hubLabel, x: cx, y: cy, r: 46 }]
  const n = Math.max(sources.length, 1)
  const radius = 185
  sources.forEach((s, i) => {
    const angle = -Math.PI / 2 + (i * 2 * Math.PI) / n
    nodes.push({
      id: String(s.id), kind: 'source',
      name: s.peerName || ('设备 ' + s.id),
      x: cx + radius * Math.cos(angle),
      y: cy + radius * Math.sin(angle) * 0.75,   // 稍扁，适合宽屏
      r: 30, level: s.level
    })
  })
  return nodes
}

let graphSig = ''

function renderGraph (state) {
  const sources = state.sources || []
  // 只把"节点集合"纳入签名。电平每秒都在变，绝不能因此重建 SVG——
  // 一重建，用户刚按下的球就被换掉，点击直接丢失（表现为"点不到"）。
  const sig = sources.map((x) => x.id + ':' + (x.peerName || '')).join('|') +
              '|hub=' + hubSelf + ':' + hubLabel
  if (sig !== graphSig) {
    graphSig = sig
    buildGraphSvg(state)
  }
  paintGraphDynamic(state)
}

function buildGraphSvg (state) {
  const svg = $('graph')
  const sources = state.sources || []
  // 本机不是汇聚端时，大球是对面的汇聚端，小球是本机（方案 4.2）
  const nodes = hubSelf
      ? layoutGraph(sources, hubLabel + ' · 汇聚')
      : layoutGraph([{ id: 0, peerName: '本机', level: state.busLevel }],
                    hubLabel + ' · 汇聚')
  const hub = nodes[0]

  const parts = []
  for (let i = 1; i < nodes.length; ++i) {
    const n = nodes[i]
    parts.push('<line data-line="' + n.id + '" x1="' + hub.x + '" y1="' + hub.y +
      '" x2="' + n.x + '" y2="' + n.y + '" stroke="var(--line)" stroke-width="2" opacity="0.75"/>')
  }
  for (const n of nodes) {
    const label = n.kind === 'hub' ? '汇聚' : String(n.id)
    parts.push('<g class="node" data-id="' + n.id + '" style="cursor:pointer">' +
      (n.kind === 'hub'
        ? '<circle cx="' + n.x + '" cy="' + n.y + '" r="' + (n.r + 9) + '" fill="var(--accent-dim)"/>'
        : '') +
      '<circle data-ball="' + n.id + '" cx="' + n.x + '" cy="' + n.y + '" r="' + n.r +
      '" fill="' + (n.kind === 'hub' ? 'var(--accent)' : 'oklch(0.4 0.06 255)') +
      '" stroke="oklch(0.55 0.1 255)" stroke-width="2"/>' +
      '<text x="' + n.x + '" y="' + (n.y + 5) + '" text-anchor="middle" fill="var(--text)"' +
      ' font-size="' + (n.kind === 'hub' ? 15 : 13) + '" font-weight="600">' + label + '</text>' +
      '<text x="' + n.x + '" y="' + (n.y + n.r + 20) + '" text-anchor="middle"' +
      ' fill="var(--dim)" font-size="12.5">' + n.name + '</text>' +
      '<text data-lvl="' + n.id + '" x="' + n.x + '" y="' + (n.y + n.r + 37) +
      '" text-anchor="middle" fill="var(--faint)" font-size="11.5">—</text>' +
      '</g>')
  }
  svg.innerHTML = parts.join('')

  svg.querySelectorAll('.node').forEach((g) => {
    g.addEventListener('click', () => {
      const id = g.dataset.id
      if (id === 'hub') {
        selected = { kind: 'hub', id: 'hub' }
      } else {
        const src = sources.find((x) => String(x.id) === String(id))
        if (!src) return
        selected = { kind: 'source', id: src.id }
      }
      paintGraphDynamic(lastState)
      showVolumePanel(lastState)
    })
  })
}

// 只改文本和描边，不重建节点——这样点击永远不会被打断
function paintGraphDynamic (state) {
  if (!state) return
  const svg = $('graph')
  const sources = state.sources || []
  const setLvl = (id, text) => {
    const el = svg.querySelector('[data-lvl="' + id + '"]')
    if (el) el.textContent = text
  }
  setLvl('hub', dbText(state.busLevel))
  for (const s of sources) setLvl(s.id, dbText(s.level))

  // 选中态：描边高亮 + 连线加粗
  svg.querySelectorAll('[data-ball]').forEach((c) => {
    const on = selected && String(selected.id) === c.getAttribute('data-ball')
    c.setAttribute('stroke', on ? 'var(--text)' : 'oklch(0.55 0.1 255)')
    c.setAttribute('stroke-width', on ? '3' : '2')
  })
  svg.querySelectorAll('[data-line]').forEach((l) => {
    const on = selected && String(selected.id) === l.getAttribute('data-line')
    l.setAttribute('stroke', on ? 'var(--accent-hi)' : 'var(--line)')
    l.setAttribute('stroke-width', on ? '3' : '2')
    l.setAttribute('opacity', on ? '1' : '0.75')
  })
}

let volDragging = false

function showVolumePanel (state) {
  const panel = $('volumePanel')
  const slider = $('volSlider')
  const nameEl = $('volName')
  if (!hubSelf) {
    // 本机只是音源，音量由对面的汇聚端调
    nameEl.textContent = '本机是音源 · 音量由 ' + hubLabel + ' 调节'
    slider.value = 100
    slider.disabled = true
    $('volPct').textContent = '—'
    panel.style.display = ''
    return
  }
  slider.disabled = false

  if (selected.kind === 'hub') {
    nameEl.textContent = '汇聚端总音量'
    slider.value = Math.round((state.master || 1) * 100)
    slider.oninput = () => {
      const g = slider.value / 100
      $('volPct').textContent = pct(g)
      slider.style.setProperty('--p', (g * 100 / 1.5) + '%')
      window.hub.setMaster(g)
    }
  } else {
    const s = (state.sources || []).find((x) => String(x.id) === String(selected.id))
    if (!s) { panel.style.display = 'none'; return }
    nameEl.textContent = s.peerName || ('设备 ' + s.id)
    // 正在拖的时候不回写，否则滑杆会被状态推回去，手感像"抓不住"
    if (!volDragging) slider.value = Math.round((s.gain ?? 1) * 100)
    slider.oninput = () => {
      const g = slider.value / 100
      $('volPct').textContent = pct(g)
      slider.style.setProperty('--p', (g * 100 / 1.5) + '%')
      window.hub.setSource(s.id, { gain: g })
    }
  }
  $('volPct').textContent = pct(slider.value / 100)
  slider.style.setProperty('--p', (slider.value / 1.5) + '%')
  panel.style.display = ''
}

// 拖动状态：按下到松开之间不回写滑杆
$('volSlider').addEventListener('pointerdown', () => { volDragging = true })
window.addEventListener('pointerup', () => { volDragging = false })

// ============================================================ 连接界面


// 汇聚端选择器：连接建立后才显示（方案 7.3）
function renderHubSelect (state) {
  const sel = $('hubSelect')
  // 候选 = 本机 + 已连接设备 + 发现的设备
  //
  // 只列 peers 是不够的：手机端不广播信标（省电），
  // 所以 PC 的 peers 里往往没有手机——但手机明明已经连上了。
  // 已连接设备从 sources 取，一定在。
  const candidates = []
  const seen = new Set()
  for (const s of (state.sources || [])) {
    if (!s.peerIp) continue
    const key = s.peerIp + ':' + state.audioPort
    if (seen.has(key)) continue
    seen.add(key)
    candidates.push({ name: s.peerName || ('设备 ' + s.id), ip: s.peerIp, port: 47000 })
  }
  for (const p of (state.peers || [])) {
    const key = p.ip + ':' + p.audioPort
    if (seen.has(key)) continue
    seen.add(key)
    candidates.push({ name: p.name, ip: p.ip, port: p.audioPort })
  }

  const sig = JSON.stringify(candidates.map((p) => p.name + '@' + p.ip + ':' + p.port)) +
              '|' + hubSelf + '|' + hubLabel
  if (sig === hubSelectSig) return
  hubSelectSig = sig

  const prev = hubSelf ? 'self' : 'remote'
  sel.innerHTML = ''
  const optSelf = document.createElement('option')
  optSelf.value = 'self'
  optSelf.textContent = '本机（电脑）'
  sel.appendChild(optSelf)
  for (const p of candidates) {
    const o = document.createElement('option')
    o.value = p.ip + ':' + p.port
    o.textContent = p.name + '（' + p.ip + '）'
    sel.appendChild(o)
  }
  sel.value = prev
}

$('hubSelect').addEventListener('change', async (e) => {
  const v = e.target.value
  if (v === 'self') {
    hubSelf = true
    hubLabel = '本机'
    await window.hub.setRole({ self: true })
  } else {
    const [ip, portStr] = v.split(':')
    hubSelf = false
    hubLabel = ip
    await window.hub.setRole({ self: false, ip, audioPort: Number(portStr) })
  }
  hubSelectSig = ''
  graphSig = ''
  render(lastState)
})

let connectSig = ''
let ctrlSig = ''

// 待确认请求：连接界面和控制界面都要显示
function renderPending (container, state) {
  container.innerHTML = ''
  for (const p of state.pending || []) {
    const el = document.createElement('div')
    el.className = 'req'
    el.innerHTML =
      '<span class="who">' + p.name + '</span>' +
      '<span class="ip">' + p.ip + '</span>' +
      '<span class="ask">请求把声音送到这里</span>' +
      '<button class="btn ok">同意</button>' +
      '<button class="btn no">拒绝</button>'
    el.querySelector('.ok').addEventListener('click', () => window.hub.accept(p.streamId))
    el.querySelector('.no').addEventListener('click', () => window.hub.reject(p.streamId))
    container.appendChild(el)
  }
}

// 设备卡片列表
function renderPeers (container, state) {
  container.innerHTML = ''
  for (const p of state.peers || []) {
    const el = document.createElement('div')
    el.className = 'card peer'
    const role = (p.canReceive && p.canSend) ? '音源 / 汇聚' : (p.canReceive ? '汇聚端' : '音源')
    el.innerHTML =
      '<div class="top"><div class="name"><span class="dot dim"></span>' + p.name +
      '</div><div class="db">' + role + '</div></div>' +
      '<div class="row"><span class="ask">' + p.ip + ':' + p.audioPort + '</span></div>' +
      '<div class="row" style="margin-top:10px">' +
        '<button class="btn go">邀请它把声音送过来</button>' +
      '</div>'
    el.querySelector('.go').addEventListener('click', () =>
      window.hub.invitePeer({ ip: p.ip, audioPort: p.audioPort }))
    container.appendChild(el)
  }
}

function renderConnect (state) {
  // 200ms 轮询会把整片 DOM 重建一次，用户正在点的那个按钮就被换掉了。
  // 只有"设备集合/待确认集合"真的变了才重建。
  const sig = JSON.stringify({
    r: (state.pending || []).map((x) => x.streamId + ':' + x.name + ':' + x.ip),
    p: (state.peers || []).map((x) => x.name + '@' + x.ip + ':' + x.audioPort)
  })
  if (sig === connectSig) return
  connectSig = sig

  renderPending($('reqGrid'), state)
  $('sectReq').style.display = (state.pending || []).length ? '' : 'none'
  renderPeers($('peerGrid'), state)
  $('sectPeer').style.display = (state.peers || []).length ? '' : 'none'
}

// 控制界面里的"新设备"区：会话进行中也能接人
function renderControlDevices (state) {
  const sig = JSON.stringify({
    r: (state.pending || []).map((x) => x.streamId + ':' + x.name),
    p: (state.peers || []).map((x) => x.name + '@' + x.ip + ':' + x.audioPort)
  })
  if (sig === ctrlSig) return
  ctrlSig = sig
  renderPending($('ctrlReq'), state)
  renderPeers($('ctrlPeers'), state)
}

// ============================================================ 主刷新

let lastState = null

function render (state) {
  if (!state) return
  lastState = state
  if (state.error) {
    const b = $('banner')
    b.textContent = state.error
    b.classList.remove('hidden')
  } else {
    $('banner').classList.add('hidden')
  }
  $('addr').textContent = state.ip || '—'

  const sources = state.sources || []
  const confirmedCount = sources.filter((s) => s.confirmed).length
  // 本机切走汇聚端后会变成"纯音源"：本地没有 sources，
  // 但会话明明还在。只看 sources 会把界面误跳回连接界面。
  const anyConnection = confirmedCount > 0 || !hubSelf

  // 自动切换屏幕：有连接 → 控制；没有 → 连接
  const next = anyConnection ? 'control' : 'connect'
  if (next !== screen) {
    selected = null
    showScreen(next)
  }

  if (screen === 'connect') {
    renderConnect(state)
  } else {
    renderControlDevices(state)
    renderHubSelect(state)
    renderGraph(state)
    if (selected) showVolumePanel(state)
  }

  $('stats').innerHTML =
    '<span>设备 <b>' + confirmedCount + '</b> 台</span>' +
    '<span>总线 <b>' + dbText(state.busLevel) + '</b></span>' +
    '<span>码率 <b>' + (state.kbps || 0).toFixed(0) + '</b> kbps</span>' +
    '<span>坏包 <b>' + state.malformed + '</b></span>' +
    '<span>渲染欠载 <b>' + state.renderUnderruns + '</b></span>'
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

$('volMute').addEventListener('click', () => {
  if (!selected || selected.kind !== 'source') return
  window.hub.setSource(selected.id, { muted: true })
  $('volMute').classList.add('on')
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
  const n = res.peers.length
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
  for (const src of s.sources || []) {
    if (src.confirmed) window.hub.disconnect(src.id)
  }
})

// ============================================================ 订阅

// 调试快照：排查"界面没切换"时直接读它，比看日志快
window.__hubDebug = () => ({
  screen,
  hubSelf,
  hubLabel,
  sources: (lastState && lastState.sources ? lastState.sources : []).length,
  confirmed: (lastState && lastState.sources ? lastState.sources : [])
      .filter((x) => x.confirmed).length,
  peers: (lastState && lastState.peers ? lastState.peers : []).length,
  controlShown: !!$('screenControl') && $('screenControl').style.display !== 'none'
})

const unsubscribe = window.hub.onState(render)
window.addEventListener('beforeunload', unsubscribe)
window.hub.getState().then(render)
