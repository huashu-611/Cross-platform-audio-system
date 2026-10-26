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

function renderGraph (state) {
  const svg = $('graph')
  const sources = state.sources || []
  const nodes = layoutGraph(sources, '本机 · 汇聚')
  const hub = nodes[0]

  const parts = []
  // 连线
  for (let i = 1; i < nodes.length; ++i) {
    const n = nodes[i]
    const on = selected && selected.id === n.id
    parts.push('<line x1="' + hub.x + '" y1="' + hub.y + '" x2="' + n.x + '" y2="' + n.y +
      '" stroke="' + (on ? 'var(--accent-hi)' : 'var(--line)') + '" stroke-width="' +
      (on ? 3 : 2) + '" opacity="' + (on ? 1 : 0.75) + '"/>')
  }
  // 球
  for (const n of nodes) {
    const isSel = selected && selected.id === n.id
    const fill = n.kind === 'hub' ? 'var(--accent)' : 'oklch(0.4 0.06 255)'
    const label = n.kind === 'hub' ? '汇聚' : String(n.id)
    parts.push('<g class="node" data-id="' + n.id + '" style="cursor:pointer">' +
      (n.kind === 'hub'
        ? '<circle cx="' + n.x + '" cy="' + n.y + '" r="' + (n.r + 9) +
          '" fill="var(--accent-dim)"/>'
        : '') +
      '<circle cx="' + n.x + '" cy="' + n.y + '" r="' + n.r + '" fill="' + fill +
      '" stroke="' + (isSel ? 'var(--text)' : 'oklch(0.55 0.1 255)') +
      '" stroke-width="' + (isSel ? 3 : 2) + '"/>' +
      '<text x="' + n.x + '" y="' + (n.y + 5) + '" text-anchor="middle" fill="var(--text)"' +
      ' font-size="' + (n.kind === 'hub' ? 15 : 13) + '" font-weight="600">' + label + '</text>' +
      '<text x="' + n.x + '" y="' + (n.y + n.r + 20) + '" text-anchor="middle"' +
      ' fill="var(--dim)" font-size="12.5">' + n.name + '</text>' +
      '<text x="' + n.x + '" y="' + (n.y + n.r + 37) + '" text-anchor="middle"' +
      ' fill="var(--faint)" font-size="11.5">' +
      dbText(n.kind === 'hub' ? state.busLevel : n.level) + '</text>' +
      '</g>')
  }
  svg.innerHTML = parts.join('')

  svg.querySelectorAll('.node').forEach((g) => {
    g.addEventListener('click', () => {
      const id = g.dataset.id
      if (id === 'hub') {
        selected = { kind: 'hub', id: 'hub' }
      } else {
        const s = sources.find((x) => String(x.id) === String(id))
        if (!s) return
        selected = { kind: 'source', id: s.id }
      }
      renderGraph(state)
      showVolumePanel(state)
    })
  })
}

function showVolumePanel (state) {
  const panel = $('volumePanel')
  const slider = $('volSlider')
  const nameEl = $('volName')

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
    slider.value = Math.round((s.gain ?? 1) * 100)
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

// ============================================================ 连接界面

function renderConnect (state) {
  const reqGrid = $('reqGrid')
  reqGrid.innerHTML = ''
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
    reqGrid.appendChild(el)
  }
  $('sectReq').style.display = (state.pending || []).length ? '' : 'none'

  const peerGrid = $('peerGrid')
  peerGrid.innerHTML = ''
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
    peerGrid.appendChild(el)
  }
  $('sectPeer').style.display = (state.peers || []).length ? '' : 'none'
}

// ============================================================ 主刷新

function render (state) {
  if (!state) return
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
  const anyConnection = confirmedCount > 0

  // 自动切换屏幕：有连接 → 控制；没有 → 连接
  const next = anyConnection ? 'control' : 'connect'
  if (next !== screen) {
    selected = null
    showScreen(next)
  }

  if (screen === 'connect') {
    renderConnect(state)
  } else {
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

$('leaveBtn').addEventListener('click', async () => {
  const s = await window.hub.getState()
  for (const src of s.sources || []) {
    if (src.confirmed) window.hub.disconnect(src.id)
  }
})

// ============================================================ 订阅

const unsubscribe = window.hub.onState(render)
window.addEventListener('beforeunload', unsubscribe)
window.hub.getState().then(render)
