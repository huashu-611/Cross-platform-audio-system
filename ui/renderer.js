// 渲染进程：纯网页逻辑，不碰 Node。
// 所有对音频核心的操作都走 window.hub.* （preload 暴露的窄接口）。

const grid = document.getElementById('grid')
const peerGrid = document.getElementById('peerGrid')
const reqGrid = document.getElementById('reqGrid')
const banner = document.getElementById('banner')
const cards = new Map()   // streamId -> DOM 元素

// ------------------------------------------------------------------ 工具

const SEGMENTS = 14

function pct (v) { return Math.round(v * 100) + '%' }
function dbText (v) { return v <= -119 ? '静音' : v.toFixed(1) + ' dBFS' }

// 电平映射到分段：-60dBFS 起表，最后两段是热区、最后一段是削波
function meterPct (level) { return Math.max(0, Math.min(100, (level + 60) / 0.6)) }

function buildMeter (el) {
  el.innerHTML = ''
  for (let i = 0; i < SEGMENTS; ++i) el.appendChild(document.createElement('i'))
}

function paintMeter (el, level) {
  const lit = Math.round(meterPct(level) / 100 * SEGMENTS)
  const segs = el.children
  for (let i = 0; i < segs.length; ++i) {
    const on = i < lit
    const frac = i / SEGMENTS
    segs[i].className =
      on ? (frac > 0.92 ? 'on clip' : frac > 0.78 ? 'on hot' : 'on') : ''
  }
}

document.getElementById('copyBtn').addEventListener('click', async (e) => {
  const text = document.getElementById('addr').textContent
  try {
    await navigator.clipboard.writeText(text)
    const btn = e.target
    const old = btn.textContent
    btn.textContent = '已复制'
    setTimeout(() => { btn.textContent = old }, 1200)
  } catch (err) { /* 剪贴板不可用时静默失败，用户还能手抄 */ }
})

// ------------------------------------------------------------------ 建卡片

function createCard (s) {
  const el = document.createElement('div')
  el.className = 'card'
  el.innerHTML =
    '<div class="top">' +
      '<div class="name"><span class="dot"></span>流 ' + s.id + '</div>' +
      '<div class="db"></div>' +
    '</div>' +
    '<div class="meter"></div>' +
    '<div class="row">' +
      '<input type="range" min="0" max="150">' +
      '<span class="pct"></span>' +
      '<button class="mute">静音</button>' +
    '</div>' +
    '<div class="foot">' +
      '<span>水位</span><span>包数</span><span>欠载</span><span>溢出</span>' +
    '</div>'

  const slider = el.querySelector('input')
  const label = el.querySelector('.pct')
  const mute = el.querySelector('.mute')
  const paint = () => slider.style.setProperty('--p', (slider.value / 1.5) + '%')
  buildMeter(el.querySelector('.meter'))

  // 初值来自后端状态，不要写死 100
  slider.value = Math.round(s.gain * 100)
  label.textContent = pct(s.gain)
  mute.classList.toggle('on', s.muted)
  mute.textContent = s.muted ? '已静音' : '静音'
  paint()

  // 用户拖动时才上报，避免和 200ms 的状态推送互相覆盖
  let dragging = false
  slider.addEventListener('pointerdown', () => { dragging = true })
  slider.addEventListener('pointerup', () => { dragging = false })
  slider.addEventListener('input', () => {
    const g = slider.value / 100
    label.textContent = pct(g)
    paint()
    window.hub.setSource(s.id, { gain: g })
  })

  mute.addEventListener('click', () => {
    const next = !mute.classList.contains('on')
    mute.classList.toggle('on', next)
    mute.textContent = next ? '已静音' : '静音'
    el.querySelector('.dot').classList.toggle('off', next)
    window.hub.setSource(s.id, { muted: next })
  })

  el._dragging = () => dragging
  return el
}

// ------------------------------------------------------------------ 刷新

function render (state) {
  if (!state) return

  if (state.error) {
    banner.textContent = state.error
    banner.classList.remove('hidden')
  } else {
    banner.classList.add('hidden')
  }

  document.getElementById('addr').textContent = state.ip || '—'

  // 总音量
  const master = document.getElementById('master')
  if (document.activeElement !== master) {
    master.value = Math.round((state.master || 1) * 100)
    master.style.setProperty('--p', (master.value / 1.5) + '%')
    document.getElementById('masterPct').textContent = pct(state.master || 1)
  }

  // 延迟档位
  document.querySelectorAll('#lat button').forEach((b) => {
    b.classList.toggle('on', Number(b.dataset.v) === state.latency)
  })

  // ---- 待确认的连接请求：内嵌警示条 ----
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

  // ---- 发现的设备 ----
  peerGrid.innerHTML = ''
  const connectedIds = new Set((state.sources || []).map(x => x.id))
  for (const p of state.peers || []) {
    if (connectedIds.has(p.audioPort)) continue
    const el = document.createElement('div')
    el.className = 'card peer'
    const role = (p.canReceive && p.canSend) ? '音源 / 汇聚' : (p.canReceive ? '汇聚端' : '音源')
    el.innerHTML =
      '<div class="top"><div class="name"><span class="dot dim"></span>' + p.name +
      '</div><div class="db">' + role + '</div></div>' +
      '<div class="row"><span class="ask">' + p.ip + ':' + p.audioPort + '</span></div>' +
      '<div class="row" style="margin-top:10px">' +
        '<button class="btn go">请求连接</button>' +
      '</div>'
    el.querySelector('.go').addEventListener('click', () => window.hub.invitePeer({ ip: p.ip, audioPort: p.audioPort }))
    peerGrid.appendChild(el)
  }
  if ((state.peers || []).length === 0) peerGrid.innerHTML = ''
  document.getElementById('sectPeer').style.display =
    (state.peers || []).length ? '' : 'none'

  // 源卡片
  const sources = state.sources || []
  if (sources.length === 0 && cards.size === 0) {
    grid.innerHTML =
      '<div class="empty"><b>等待设备接入</b>' +
      '在手机上打开 AudioHub 源端，填上面那个地址</div>'
  } else if (sources.length > 0) {
    const empty = grid.querySelector('.empty')
    if (empty) empty.remove()
  }

  const seen = new Set()
  for (const s of sources) {
    seen.add(s.id)
    let el = cards.get(s.id)
    if (!el || !el.isConnected) {
      el = createCard(s)
      cards.set(s.id, el)
      grid.appendChild(el)
    }
    el.querySelector('.db').textContent = dbText(s.level)
    paintMeter(el.querySelector('.meter'), s.level)
    el.querySelector('.dot').classList.toggle('off', s.muted)
    el.querySelector('.mute').classList.toggle('on', s.muted)
    el.querySelector('.mute').textContent = s.muted ? '已静音' : '静音'
    const foot = el.querySelector('.foot').children
    foot[0].textContent = '水位 ' + s.occupancy + ' ms'
    foot[1].textContent = s.packets + ' 包'
    foot[2].textContent = '欠载 ' + s.underrun
    foot[3].textContent = '溢出 ' + s.overflow
    // 用户正在拖的时候不回写滑杆，否则会被抢回去
    if (!el._dragging()) {
      const slider = el.querySelector('input')
      slider.value = Math.round(s.gain * 100)
      slider.style.setProperty('--p', (slider.value / 1.5) + '%')
      el.querySelector('.pct').textContent = pct(s.gain)
    }
  }
  for (const [id, el] of cards) {
    if (!seen.has(id)) { el.remove(); cards.delete(id) }
  }

  document.getElementById('stats').innerHTML =
    '<span>源 <b>' + sources.length + '</b> 路</span>' +
    '<span>总线 <b>' + dbText(state.busLevel) + '</b></span>' +
    '<span>码率 <b>' + (state.kbps || 0).toFixed(0) + '</b> kbps</span>' +
    '<span>坏包 <b>' + state.malformed + '</b></span>' +
    '<span>渲染欠载 <b>' + state.renderUnderruns + '</b></span>'
}

// ------------------------------------------------------------------ 事件

document.getElementById('master').addEventListener('input', (e) => {
  const g = e.target.value / 100
  document.getElementById('masterPct').textContent = pct(g)
  e.target.style.setProperty('--p', (g * 100 / 1.5) + '%')
  window.hub.setMaster(g)
})

document.querySelectorAll('#lat button').forEach((b) => {
  b.addEventListener('click', () => window.hub.setLatency(Number(b.dataset.v)))
})

// 订阅主进程推送；先主动拉一次，避免等 200ms 才看到内容
const unsubscribe = window.hub.onState(render)
window.addEventListener('beforeunload', unsubscribe)
window.hub.getState().then(render)
