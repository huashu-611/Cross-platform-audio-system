// 星空主题预览的绘制层（纯 Canvas 2D，零依赖）。
//
// 本文件只服务 docs/starfield-ui-preview 的设计确认，不进入正式构建。
// 正式落地方案：同一套绘制逻辑替换 ui/scene3d.js 的配色与星体绘制，
// 并把背景星野抽成 ui/starfield.js；Android 侧对应 HubGraphView 的配色与绘制。
//
// 视觉契约（与预览说明 README 一致）：
//   · 汇聚端 = 恒星：位于图谱中心，暖金核心 + 日冕 + 星芒，球体半径仍由投影距离决定；
//   · 其他设备 = 行星：静止摆放在恒星周围（不公转、不自转动画），屏幕大小仍随距离缩放
//     （距离即音量：越远越小、越轻）；在线=行星本体 + 大气光晕，离线=灰蓝暗星 + 虚线轨道；
//   · 连接 = 光束：恒星到行星的渐变光带，有音频流时更亮并在光带上带星尘；
//   · 背景 = 星野：全局深空 + 星云 + 多层星点；图谱内再加一层局部星尘与金色星云。
//
// 保留的既有信息编码（功能不变，只换视觉）：
//   距离参考环 1 m / 2 m、方位字 前/右/后/左（PC）、电平弧、dBFS、距离 m、离线/本机角标。

(function (global) {
  'use strict'

  const TAU = Math.PI * 2
  const DEG = Math.PI / 180
  const clamp = (v, lo, hi) => (v < lo ? lo : (v > hi ? hi : v))

  // 固定种子伪随机：截图可复现，也让正式实现能"每台设备看到的星野一样"
  function mulberry32 (seed) {
    let a = seed >>> 0
    return function () {
      a |= 0
      a = (a + 0x6D2B79F5) | 0
      let t = Math.imul(a ^ (a >>> 15), 1 | a)
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296
    }
  }

  function fitCanvas (canvas, alpha) {
    const dpr = Math.min(global.devicePixelRatio || 1, 2)
    const rect = canvas.getBoundingClientRect()
    const w = Math.max(1, rect.width)
    const h = Math.max(1, rect.height)
    canvas.width = Math.max(1, Math.round(w * dpr))
    canvas.height = Math.max(1, Math.round(h * dpr))
    const ctx = canvas.getContext('2d')
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, w, h)
    if (alpha) ctx.globalAlpha = alpha
    return { ctx: ctx, w: w, h: h, dpr: dpr }
  }

  // ============================================================ 通用绘制件

  function rgba (rgb, a) {
    return 'rgba(' + rgb + ',' + a + ')'
  }

  // 径向柔光圆（星云 / 光晕都用它）
  function softGlow (ctx, x, y, r, rgb, peak, mid) {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r)
    g.addColorStop(0, rgba(rgb, peak))
    g.addColorStop(0.42, rgba(rgb, mid))
    g.addColorStop(1, rgba(rgb, 0))
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(x, y, r, 0, TAU)
    ctx.fill()
  }

  function starDot (ctx, x, y, r, a, tint) {
    ctx.beginPath()
    ctx.arc(x, y, r, 0, TAU)
    ctx.fillStyle = 'rgba(' + (tint || '236,242,255') + ',' + a + ')'
    ctx.fill()
  }

  // 十字星芒（大星），角度 0 = 横向
  function crossStar (ctx, x, y, r, a, tint) {
    tint = tint || '226,237,255'
    softGlow(ctx, x, y, r * 3.4, tint, a * 0.30, a * 0.05)
    ctx.save()
    ctx.strokeStyle = rgba(tint, a)
    ctx.lineCap = 'round'
    ctx.lineWidth = Math.max(0.6, r * 0.28)
    ctx.beginPath()
    ctx.moveTo(x - r * 2.6, y); ctx.lineTo(x + r * 2.6, y)
    ctx.moveTo(x, y - r * 2.6); ctx.lineTo(x, y + r * 2.6)
    ctx.stroke()
    ctx.globalAlpha = a * 0.55
    ctx.lineWidth = Math.max(0.5, r * 0.18)
    ctx.beginPath()
    ctx.moveTo(x - r * 1.5, y - r * 1.5); ctx.lineTo(x + r * 1.5, y + r * 1.5)
    ctx.moveTo(x - r * 1.5, y + r * 1.5); ctx.lineTo(x + r * 1.5, y - r * 1.5)
    ctx.stroke()
    ctx.restore()
    ctx.beginPath()
    ctx.arc(x, y, Math.max(0.8, r * 0.85), 0, TAU)
    ctx.fillStyle = 'rgba(255,255,255,' + Math.min(1, a * 1.35) + ')'
    ctx.fill()
  }

  function text (ctx, str, x, y, opt) {
    opt = opt || {}
    ctx.save()
    ctx.font = opt.font || '11px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif'
    ctx.fillStyle = opt.color || '#a3b3d1'
    ctx.textAlign = opt.align || 'center'
    ctx.textBaseline = opt.baseline || 'middle'
    if (opt.halo !== false) {
      ctx.shadowColor = 'rgba(0,0,0,.9)'
      ctx.shadowBlur = opt.haloSize || 6
    }
    ctx.fillText(String(str == null ? '' : str), x, y)
    ctx.restore()
  }

  // ============================================================ 全局星野背景

  const SKY = {
    nebula: [
      { x: 0.16, y: 0.16, r: 0.55, inner: '78,104,196', peak: 0.20, mid: 0.06 },
      { x: 0.82, y: 0.10, r: 0.46, inner: '126,92,214', peak: 0.16, mid: 0.05 },
      { x: 0.74, y: 0.74, r: 0.52, inner: '44,128,168', peak: 0.13, mid: 0.04 },
      { x: 0.30, y: 0.84, r: 0.42, inner: '150,74,132', peak: 0.09, mid: 0.03 }
    ]
  }

  function paintSky (canvas, opts) {
    opts = opts || {}
    const f = fitCanvas(canvas)
    const ctx = f.ctx
    const w = f.w
    const h = f.h
    const rnd = mulberry32(opts.seed == null ? 7 : opts.seed)
    const base = Math.max(w, h)

    // 深空底色
    const bg = ctx.createLinearGradient(0, 0, w * 0.25, h)
    bg.addColorStop(0, '#070b1b')
    bg.addColorStop(0.45, '#05070f')
    bg.addColorStop(1, '#03040a')
    ctx.fillStyle = bg
    ctx.fillRect(0, 0, w, h)

    // 星云团
    for (let i = 0; i < SKY.nebula.length; i++) {
      const n = SKY.nebula[i]
      ctx.save()
      ctx.translate(n.x * w, n.y * h)
      const rx = n.r * base
      softGlow(ctx, 0, 0, rx, n.inner, n.peak, n.mid)
      ctx.restore()
    }

    // 银河带：一条倾斜的密集星带（局部旋转坐标系里画）
    ctx.save()
    ctx.translate(w * 0.52, h * 0.46)
    ctx.rotate(-0.44)
    const bw = Math.max(w, h) * 0.72
    const band = ctx.createLinearGradient(0, -bw * 0.5, 0, bw * 0.5)
    band.addColorStop(0, 'rgba(130,150,255,0)')
    band.addColorStop(0.36, 'rgba(150,170,255,.045)')
    band.addColorStop(0.5, 'rgba(176,190,255,.075)')
    band.addColorStop(0.64, 'rgba(150,170,255,.045)')
    band.addColorStop(1, 'rgba(130,150,255,0)')
    ctx.fillStyle = band
    ctx.fillRect(-bw, -bw * 0.5, bw * 2, bw)
    for (let i = 0; i < 620; i++) {
      const x = (rnd() - 0.5) * bw * 2
      const y = (rnd() + rnd() + rnd() - 1.5) * bw * 0.28
      starDot(ctx, x, y, rnd() < 0.88 ? 0.5 + rnd() * 0.6 : 0.9 + rnd() * 0.9,
        0.18 + rnd() * 0.5, rnd() < 0.7 ? '214,226,255' : '255,232,196')
    }
    ctx.restore()

    // 主星野：三层（细、中、亮）
    const bright = []
    for (let layer = 0; layer < 3; layer++) {
      const count = [340, 120, 34][layer]
      const rBase = [0.45, 0.8, 1.25][layer]
      const aBase = [0.45, 0.62, 0.85][layer]
      for (let i = 0; i < count; i++) {
        const x = rnd() * w
        const y = rnd() * h
        const r = rBase + rnd() * (layer === 2 ? 0.9 : 0.7)
        const a = aBase * (0.45 + rnd() * 0.55)
        const tint = rnd() < 0.72 ? '232,240,255' : (rnd() < 0.5 ? '255,228,186' : '190,215,255')
        starDot(ctx, x, y, r, a, tint)
        if (layer >= 1 && rnd() < 0.13) {
          ctx.save()
          ctx.globalAlpha = 0.10
          softGlow(ctx, x, y, r * 7, tint, 0.7, 0.18)
          ctx.restore()
        }
        if (layer === 2) bright.push({ x: x, y: y, r: r })
      }
    }

    // 少数带星芒的亮星 + 一条极淡的星座连线（只做装饰，不参与任何语义）
    for (let i = 0; i < bright.length && i < 12; i++) {
      const b = bright[i]
      crossStar(ctx, b.x, b.y, Math.max(0.7, b.r * 0.82), 0.5 + rnd() * 0.35,
        i % 3 === 0 ? '255,226,178' : '224,238,255')
    }
    if (bright.length >= 5) {
      ctx.save()
      ctx.strokeStyle = 'rgba(160,190,255,.09)'
      ctx.lineWidth = 0.8
      ctx.beginPath()
      for (let i = 0; i < 5; i++) {
        if (i === 0) ctx.moveTo(bright[i].x, bright[i].y)
        else ctx.lineTo(bright[i].x, bright[i].y)
      }
      ctx.stroke()
      ctx.restore()
    }

    // 一颗很淡的流星（静态画面里的方向感）
    ctx.save()
    ctx.translate(w * 0.84, h * 0.20)
    ctx.rotate(2.58)
    const streak = ctx.createLinearGradient(-130, 0, 130, 0)
    streak.addColorStop(0, 'rgba(190,220,255,0)')
    streak.addColorStop(0.45, 'rgba(190,220,255,.16)')
    streak.addColorStop(0.56, 'rgba(235,245,255,.38)')
    streak.addColorStop(0.72, 'rgba(190,220,255,.10)')
    streak.addColorStop(1, 'rgba(190,220,255,0)')
    ctx.fillStyle = streak
    ctx.fillRect(-130, -0.7, 260, 1.4)
    ctx.restore()

    // 极暗的尘埃颗粒（让大面积深空不"塑料"）
    for (let i = 0; i < 180; i++) {
      ctx.globalAlpha = 0.05 + rnd() * 0.12
      ctx.fillStyle = rnd() < 0.5 ? '#9db7ff' : '#ffd9a0'
      ctx.fillRect(rnd() * w, rnd() * h, 0.7, 0.7)
    }
    ctx.globalAlpha = 1
  }

  // ============================================================ 立体星图：投影（沿用 scene3d.js 契约）

  const VW = 920
  const VH = 460
  const CX = 460
  const CY = 208
  const FOV_Y = 47.5
  const FOCAL = (VH / 2) / Math.tan((FOV_Y / 2) * DEG)

  function camera (phiDeg, pitchDeg, dist) {
    const d = clamp(dist, 1.2, 6)
    const phi = phiDeg * DEG
    const p = clamp(pitchDeg, 6, 74) * DEG
    const eye = [d * Math.cos(p) * Math.sin(phi), d * Math.sin(p), d * Math.cos(p) * Math.cos(phi)]
    const fwd = norm([-eye[0], -eye[1], -eye[2]])
    const right = norm(cross(fwd, [0, 1, 0]))
    const up = cross(right, fwd)
    return { eye: eye, fwd: fwd, right: right, up: up, phiDeg: phiDeg, dist: d, pitchDeg: pitchDeg, focal: FOCAL }
  }

  const norm = (a) => {
    const l = Math.hypot(a[0], a[1], a[2])
    return l > 1e-9 ? [a[0] / l, a[1] / l, a[2] / l] : [0, 0, 0]
  }
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

  function project (view, p) {
    const rel = [p[0] - view.eye[0], p[1] - view.eye[1], p[2] - view.eye[2]]
    const depth = dot(rel, view.fwd)
    if (depth <= 0.05) return { visible: false, depth: depth, ppu: 0, sx: 0, sy: 0 }
    const k = view.focal / depth
    return {
      visible: true,
      sx: CX + k * dot(rel, view.right),
      sy: CY - k * dot(rel, view.up),
      depth: depth,
      ppu: k
    }
  }

  // ============================================================ 星体：恒星 / 行星

  const PALETTES = {
    cyan: { hi: '#e2fbff', base: '#59c6ea', dark: '#0a2a42', glow: '111,224,255' },
    violet: { hi: '#efe9ff', base: '#9b86ff', dark: '#241650', glow: '170,150,255' },
    rose: { hi: '#ffe6f1', base: '#e486b5', dark: '#4a1734', glow: '255,156,198' },
    amber: { hi: '#fff4d6', base: '#e8b45e', dark: '#4a2d0c', glow: '255,200,110' },
    ice: { hi: '#eef5ff', base: '#a8bad4', dark: '#2a3648', glow: '160,185,215' },
    off: { hi: '#c7d0e0', base: '#5a6579', dark: '#1a202c', glow: '140,155,180' }
  }

  function drawSpike (ctx, x, y, len, width, angle, tint, alpha) {
    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(angle)
    const g = ctx.createLinearGradient(0, 0, len, 0)
    g.addColorStop(0, rgba(tint, alpha))
    g.addColorStop(0.35, rgba(tint, alpha * 0.34))
    g.addColorStop(1, rgba(tint, 0))
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.moveTo(0, -width * 0.5)
    ctx.lineTo(len * 0.90, -width * 0.07)
    ctx.lineTo(len, 0)
    ctx.lineTo(len * 0.90, width * 0.07)
    ctx.lineTo(0, width * 0.5)
    ctx.closePath()
    ctx.fill()
    ctx.restore()
  }

  // 恒星（汇聚端）：日冕 + 四向星芒 + 色球层 + 日珥 + 白热核心
  function drawStar (ctx, x, y, r, opt) {
    opt = opt || {}
    const level = opt.level
    const pct = typeof level === 'number' ? clamp((level + 60) / 60, 0, 1) : 0.82

    // 外层日冕（大范围暖光）
    softGlow(ctx, x, y, r * 5.6, '255,190,96', 0.30, 0.10)
    softGlow(ctx, x, y, r * 3.1, '255,214,140', 0.34, 0.12)
    softGlow(ctx, x, y, r * 1.9, '255,236,190', 0.30, 0.10)

    // 星芒：横向 / 纵向更长，斜向短
    drawSpike(ctx, x, y, r * 4.6, r * 0.22, 0, '255,232,180', 0.72)
    drawSpike(ctx, x, y, r * 4.6, r * 0.22, Math.PI, '255,232,180', 0.72)
    drawSpike(ctx, x, y, r * 3.9, r * 0.19, Math.PI / 2, '255,236,196', 0.62)
    drawSpike(ctx, x, y, r * 3.9, r * 0.19, -Math.PI / 2, '255,236,196', 0.62)
    for (let i = 0; i < 8; i++) {
      const a = Math.PI / 4 + i * Math.PI / 4
      drawSpike(ctx, x, y, r * (i % 2 === 0 ? 2.0 : 2.5), r * 0.08, a, '255,220,150', 0.46)
    }

    // 日珥：两道贴着边缘的弧形等离子体
    ctx.save()
    ctx.strokeStyle = 'rgba(255,168,96,.45)'
    ctx.lineWidth = Math.max(1.4, r * 0.055)
    ctx.lineCap = 'round'
    ctx.shadowColor = 'rgba(255,150,70,.55)'
    ctx.shadowBlur = r * 0.35
    ctx.beginPath()
    ctx.arc(x, y, r * 1.10, -2.62, -1.62)
    ctx.stroke()
    ctx.globalAlpha = 0.75
    ctx.beginPath()
    ctx.arc(x, y, r * 1.07, 0.52, 1.42)
    ctx.stroke()
    ctx.globalAlpha = 1
    ctx.restore()

    // 色球层细亮圈
    ctx.save()
    ctx.beginPath()
    ctx.arc(x, y, r * 1.015, 0, TAU)
    ctx.strokeStyle = 'rgba(255,226,160,.8)'
    ctx.lineWidth = Math.max(1, r * 0.035)
    ctx.shadowColor = 'rgba(255,200,110,.9)'
    ctx.shadowBlur = r * 0.5
    ctx.stroke()
    ctx.restore()

    // 核心
    const g = ctx.createRadialGradient(x - r * 0.20, y - r * 0.24, r * 0.06, x, y, r * 1.02)
    g.addColorStop(0, '#ffffff')
    g.addColorStop(0.16, '#fff9e2')
    g.addColorStop(0.40, '#ffe8a8')
    g.addColorStop(0.68, '#ffd077')
    g.addColorStop(0.88, '#ffab4f')
    g.addColorStop(1, '#ff8a3c')
    ctx.beginPath()
    ctx.arc(x, y, r, 0, TAU)
    ctx.fillStyle = g
    ctx.fill()

    // 白热内核
    const core = ctx.createRadialGradient(x - r * 0.1, y - r * 0.12, 0, x, y, r * 0.58)
    core.addColorStop(0, 'rgba(255,255,255,.98)')
    core.addColorStop(0.55, 'rgba(255,252,238,.55)')
    core.addColorStop(1, 'rgba(255,245,215,0)')
    ctx.beginPath()
    ctx.arc(x, y, r * 0.58, 0, TAU)
    ctx.fillStyle = core
    ctx.fill()

    // 总线电平：恒星外圈一道很淡的能量弧（保持"电平可读"，不抢主体）
    if (typeof level === 'number') {
      ctx.save()
      ctx.beginPath()
      ctx.arc(x, y, r * 1.55, -Math.PI / 2, -Math.PI / 2 + pct * TAU)
      ctx.strokeStyle = 'rgba(255,214,140,.38)'
      ctx.lineWidth = Math.max(1.2, r * 0.045)
      ctx.lineCap = 'round'
      ctx.shadowColor = 'rgba(255,200,110,.55)'
      ctx.shadowBlur = r * 0.3
      ctx.stroke()
      ctx.restore()
    }
  }

  function drawPlanetRing (ctx, x, y, r, tint, online) {
    const rx = r * 2.0
    const ry = r * 0.62
    const rot = -0.36
    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(rot)
    // 后半环（在星体后面）
    ctx.beginPath()
    ctx.ellipse(0, 0, rx, ry, 0, Math.PI, TAU)
    ctx.strokeStyle = online ? rgba(tint, 0.38) : 'rgba(140,155,180,.22)'
    ctx.lineWidth = Math.max(1.4, r * 0.10)
    ctx.stroke()
    ctx.beginPath()
    ctx.ellipse(0, 0, rx * 1.10, ry * 1.10, 0, Math.PI, TAU)
    ctx.strokeStyle = online ? rgba(tint, 0.14) : 'rgba(140,155,180,.08)'
    ctx.lineWidth = Math.max(1.2, r * 0.06)
    ctx.stroke()
    ctx.restore()
  }

  function drawPlanetRingFront (ctx, x, y, r, tint, online) {
    const rx = r * 2.0
    const ry = r * 0.62
    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(-0.36)
    ctx.beginPath()
    ctx.ellipse(0, 0, rx, ry, 0, 0, Math.PI)
    ctx.strokeStyle = online ? rgba(tint, 0.52) : 'rgba(150,165,190,.30)'
    ctx.lineWidth = Math.max(1.5, r * 0.11)
    ctx.shadowColor = online ? rgba(tint, 0.6) : 'rgba(0,0,0,0)'
    ctx.shadowBlur = r * 0.5
    ctx.stroke()
    ctx.restore()
  }

  // 行星（设备）：朝向恒星的被照亮面 + 大气光晕 + 电平弧 + 名称/距离
  function drawPlanet (ctx, node) {
    const x = node.sx
    const y = node.sy
    const r = node.rPx
    const online = node.online !== false
    const streaming = node.streaming === true && online
    const pal = PALETTES[online ? (node.palette || 'cyan') : 'off']
    const tint = pal.glow
    const light = Math.atan2(node.starY - y, node.starX - x)

    ctx.save()
    ctx.globalAlpha = online ? 1 : 0.82

    // 大气光晕
    if (online) {
      softGlow(ctx, x, y, r * 2.35, tint, streaming ? 0.22 : 0.12, 0.05)
    }

    // 行星环（后半）
    if (node.rings) drawPlanetRing(ctx, x, y, r, tint, online)

    // 星球本体
    ctx.save()
    ctx.beginPath()
    ctx.arc(x, y, r, 0, TAU)
    ctx.clip()

    const lx = x + Math.cos(light) * r * 0.45
    const ly = y + Math.sin(light) * r * 0.45
    const body = ctx.createRadialGradient(lx, ly, r * 0.08, x, y, r * 1.08)
    body.addColorStop(0, pal.hi)
    body.addColorStop(0.34, pal.base)
    body.addColorStop(0.78, pal.dark)
    body.addColorStop(1, '#05070f')
    ctx.fillStyle = body
    ctx.fillRect(x - r, y - r, r * 2, r * 2)

    // 表面条纹（细微，不喧宾夺主）
    if (r > 12) {
      ctx.globalAlpha = online ? 0.16 : 0.10
      ctx.strokeStyle = 'rgba(255,255,255,.7)'
      ctx.lineWidth = Math.max(1, r * 0.09)
      for (let i = 0; i < 3; i++) {
        const yy = y - r * 0.46 + i * r * 0.42
        ctx.beginPath()
        ctx.ellipse(x, yy, r * 0.92, r * 0.16, 0.10, 0.15, Math.PI - 0.15)
        ctx.stroke()
      }
      ctx.globalAlpha = online ? 1 : 0.82
    }

    // 背光面压暗（朝向恒星那侧保持亮）
    const shadeX = x - Math.cos(light) * r * 1.25
    const shadeY = y - Math.sin(light) * r * 1.25
    const shade = ctx.createRadialGradient(shadeX, shadeY, r * 0.1, shadeX, shadeY, r * 1.9)
    shade.addColorStop(0, 'rgba(2,4,12,.72)')
    shade.addColorStop(0.55, 'rgba(2,4,12,.28)')
    shade.addColorStop(1, 'rgba(2,4,12,0)')
    ctx.fillStyle = shade
    ctx.fillRect(x - r, y - r, r * 2, r * 2)
    ctx.restore()

    // 恒星照亮的边缘弧
    ctx.save()
    ctx.beginPath()
    ctx.arc(x, y, r * 0.985, light - 1.15, light + 1.15)
    ctx.strokeStyle = online ? 'rgba(255,246,220,.5)' : 'rgba(190,205,230,.22)'
    ctx.lineWidth = Math.max(1, r * 0.055)
    ctx.shadowColor = online ? 'rgba(255,225,170,.55)' : 'rgba(0,0,0,0)'
    ctx.shadowBlur = r * 0.4
    ctx.stroke()
    ctx.restore()

    // 行星环（前半，覆盖球体下缘）
    if (node.rings) drawPlanetRingFront(ctx, x, y, r, tint, online)

    // 电平弧（在线且正在出声才有）
    if (streaming && typeof node.level === 'number') {
      const pct = clamp((node.level + 60) / 60, 0, 1)
      ctx.save()
      ctx.beginPath()
      ctx.arc(x, y, r + Math.max(4, r * 0.16), -Math.PI / 2,
        -Math.PI / 2 + pct * TAU)
      ctx.strokeStyle = pct > 0.8 ? '#ffd166' : rgba(tint, 0.92)
      ctx.lineWidth = Math.max(1.4, r * 0.075)
      ctx.lineCap = 'round'
      ctx.shadowColor = pct > 0.8 ? 'rgba(255,209,102,.8)' : rgba(tint, 0.85)
      ctx.shadowBlur = r * 0.45
      ctx.stroke()
      ctx.restore()
    }

    // 离线：虚线外圈
    if (!online) {
      ctx.save()
      ctx.beginPath()
      ctx.arc(x, y, r + Math.max(4, r * 0.2), 0, TAU)
      ctx.strokeStyle = 'rgba(150,165,190,.42)'
      ctx.lineWidth = 1.2
      ctx.setLineDash([3.5, 5])
      ctx.stroke()
      ctx.restore()
    }

    // 选中态（手机端点选/悬停）：一圈星环
    if (node.selected) {
      ctx.save()
      ctx.beginPath()
      ctx.arc(x, y, r + Math.max(7, r * 0.34), 0, TAU)
      ctx.strokeStyle = 'rgba(196,244,255,.85)'
      ctx.lineWidth = 1.6
      ctx.setLineDash([2, 6])
      ctx.shadowColor = 'rgba(140,230,255,.85)'
      ctx.shadowBlur = 14
      ctx.stroke()
      ctx.restore()
      softGlow(ctx, x, y, r * 2.9, '160,235,255', 0.18, 0.05)
    }

    ctx.restore()

    // ---- 文字：名称 / dBFS / 距离 ----
    const centerY = node.hubY == null ? CY : node.hubY
    const vh = node.vh || VH
    const above = y < centerY
    const nameFont = '600 ' + (node.big ? 13 : 12.5) + 'px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif'
    const infoFont = '11px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif'
    const nameY = above ? y - r - 11 : y + r + 18
    const infoY = above ? nameY - 14 : nameY + 14
    const info = (typeof node.level === 'number' && online ? dbText(node.level) + ' · ' : '') +
      node.dist.toFixed(2) + ' m'

    text(ctx, truncate(node.name, node.big ? 18 : 16), x, clamp(nameY, 12, vh - 12), {
      font: nameFont, color: online ? '#dfe9fb' : '#9aa7bd'
    })
    text(ctx, info, x, clamp(infoY, 12, vh - 8), {
      font: infoFont, color: !online ? '#7d879c' : (streaming ? '#9fdcf4' : '#8e9db8')
    })

    // 角标：本机 / 离线（贴着星球右上，靠右边界时翻到左侧）
    if (node.badge) {
      const font = '10px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif'
      ctx.save()
      ctx.font = font
      const wpx = ctx.measureText(node.badge).width
      const vw = node.vw || VW
      let bx = x + r + 7
      let align = 'left'
      if (node.badgeSide === 'left' || bx + wpx > vw - 6) { bx = x - r - 7; align = 'right' }
      const by = clamp(y - r + 4, 10, vh - 10)
      text(ctx, node.badge, bx, by, {
        font: font, align: align, color: node.badgeColor || 'rgba(255,210,122,.92)'
      })
      ctx.restore()
    }
  }

  function dbText (v) {
    if (typeof v !== 'number' || !isFinite(v) || v <= -119) return '静音'
    return (v < 0 ? '−' : '') + Math.abs(v).toFixed(1) + ' dBFS'
  }

  function truncate (s, n) {
    const chars = Array.from(String(s == null ? '' : s))
    return chars.length <= n ? chars.join('') : chars.slice(0, n).join('') + '…'
  }

  // ============================================================ 光束（恒星 ↔ 行星）

  function drawBeam (ctx, node, star) {
    const nx = node.sx != null ? node.sx : node.x
    const ny = node.sy != null ? node.sy : node.y
    const online = node.online !== false
    const streaming = node.streaming === true && online
    const tint = online ? (node.selected ? '196,244,255' : '111,224,255') : '150,165,190'
    const dx = nx - star.x
    const dy = ny - star.y
    const len = Math.hypot(dx, dy)
    if (!isFinite(len) || len < 1) return
    const ux = dx / len
    const uy = dy / len
    const x1 = star.x + ux * (star.r * 1.25)
    const y1 = star.y + uy * (star.r * 1.25)
    const x2 = nx - ux * (node.rPx + 3)
    const y2 = ny - uy * (node.rPx + 3)
    if ((x2 - x1) * ux + (y2 - y1) * uy <= 0) return

    const g = ctx.createLinearGradient(x1, y1, x2, y2)
    g.addColorStop(0, rgba(tint, online ? 0.55 : 0.2))
    g.addColorStop(0.55, rgba(tint, online ? (streaming ? 0.30 : 0.18) : 0.09))
    g.addColorStop(1, rgba(tint, online ? 0.05 : 0.02))

    ctx.save()
    ctx.strokeStyle = g
    ctx.lineWidth = online ? (streaming ? 2.1 : 1.4) : 1.1
    ctx.shadowColor = rgba(tint, online ? (streaming ? 0.55 : 0.3) : 0.12)
    ctx.shadowBlur = online ? (streaming ? 10 : 5) : 3
    if (!online) ctx.setLineDash([3, 7])
    ctx.beginPath()
    ctx.moveTo(x1, y1)
    ctx.lineTo(x2, y2)
    ctx.stroke()
    ctx.restore()

    // 光带上的星尘（有音频流时更明显）
    if (streaming) {
      const dots = [0.34, 0.52, 0.70]
      for (let i = 0; i < dots.length; i++) {
        const t = dots[i]
        const px = x1 + (x2 - x1) * t
        const py = y1 + (y2 - y1) * t
        const a = 0.22 + 0.16 * Math.sin(i * 2.1)
        softGlow(ctx, px, py, 5.5, tint, a + 0.12, a * 0.3)
        ctx.beginPath()
        ctx.arc(px, py, 1.1, 0, TAU)
        ctx.fillStyle = 'rgba(255,255,255,.7)'
        ctx.fill()
      }
    }
  }

  // ============================================================ 距离参考环 + 方位字（保留原有信息层）

  function drawGroundRef (ctx, view) {
    const rings = [{ r: 1.0, dash: null, a: 0.20 }, { r: 2.0, dash: [4, 7], a: 0.12 }]
    for (let ri = 0; ri < rings.length; ri++) {
      const rr = rings[ri].r
      ctx.save()
      ctx.beginPath()
      let started = false
      for (let i = 0; i <= 110; i++) {
        const a = (i / 110) * TAU
        const p = project(view, [rr * Math.sin(a), 0, -rr * Math.cos(a)])
        if (!p.visible) { started = false; continue }
        if (!started) { ctx.moveTo(p.sx, p.sy); started = true } else ctx.lineTo(p.sx, p.sy)
      }
      ctx.strokeStyle = 'rgba(150,180,235,' + rings[ri].a + ')'
      ctx.lineWidth = 1
      if (rings[ri].dash) ctx.setLineDash(rings[ri].dash)
      ctx.stroke()
      ctx.restore()
    }

    // 距离刻度（1 m / 2 m）
    ctx.save()
    for (let i = 0; i < rings.length; i++) {
      const p = project(view, [rings[i].r * 0.707, 0, -rings[i].r * 0.707])
      if (!p.visible) continue
      text(ctx, rings[i].r + ' m', p.sx + 5, p.sy, {
        font: '9.5px "Bahnschrift", "Segoe UI", sans-serif',
        align: 'left', color: 'rgba(150,175,220,.55)', haloSize: 5
      })
    }
    ctx.restore()

    // 方位字：屏幕上方 = 正前 = -Z（与声场方向一致）
    const marks = [{ az: 0, label: '前' }, { az: 90, label: '右' }, { az: 180, label: '后' }, { az: 270, label: '左' }]
    for (let i = 0; i < marks.length; i++) {
      const a = marks[i].az * DEG
      const rr = 1.78
      const p = project(view, [rr * Math.sin(a), 0, -rr * Math.cos(a)])
      if (!p.visible) continue
      text(ctx, marks[i].label, p.sx, p.sy, {
        font: '10px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
        color: 'rgba(150,175,220,.58)', haloSize: 5
      })
    }
  }

  // ============================================================ 图谱内局部星野

  function localDust (ctx, seed, hub) {
    const rnd = mulberry32(seed)
    // 恒星周围的金色星云（让中心像"星系核心"）
    ctx.save()
    softGlow(ctx, hub.x, hub.y, 230, '255,186,96', 0.055, 0.02)
    softGlow(ctx, hub.x + 150, hub.y - 90, 150, '140,130,255', 0.045, 0.015)
    ctx.restore()
    for (let i = 0; i < 110; i++) {
      const x = rnd() * VW
      const y = rnd() * VH
      const r = 0.4 + rnd() * 1.0
      const a = 0.12 + rnd() * 0.42
      starDot(ctx, x, y, r, a, rnd() < 0.75 ? '214,228,255' : '255,230,190')
    }
  }

  // ============================================================ PC 星图

  function drawPcGraph (canvas, cfg) {
    cfg = cfg || {}
    const f = fitCanvas(canvas)
    const ctx = f.ctx
    const w = f.w
    const h = f.h
    const s = Math.min(w / VW, h / VH)
    const ox = (w - VW * s) / 2
    const oy = (h - VH * s) / 2

    ctx.save()
    ctx.translate(ox, oy)
    ctx.scale(s, s)

    const view = camera(cfg.phi == null ? 0 : cfg.phi, cfg.pitch == null ? 40 : cfg.pitch,
      cfg.dist == null ? 3.4 : cfg.dist)
    const hubRaw = cfg.hub || { name: '汇聚端未知', level: null }
    const star = {
      name: hubRaw.name || '汇聚端未知',
      level: typeof hubRaw.level === 'number' ? hubRaw.level : null,
      badge: hubRaw.badge || '',
      badgeColor: hubRaw.badgeColor || 'rgba(255,210,122,.92)',
      x: CX,
      y: CY,
      rPx: clamp(0.24 * (FOCAL / view.dist), 30, 66)
    }
    const starCss = { x: CX, y: CY, r: star.rPx }

    localDust(ctx, cfg.seed == null ? 13 : cfg.seed, { x: CX, y: CY })
    drawGroundRef(ctx, view)

    // 投影节点
    const nodes = []
    const rawNodes = Array.isArray(cfg.nodes) ? cfg.nodes : []
    for (let i = 0; i < rawNodes.length; i++) {
      const n = rawNodes[i]
      const pos = [n.x || 0, 0, n.z || 0]
      const pr = project(view, pos)
      if (!pr.visible) continue
      const dist = Math.hypot(pos[0], pos[1], pos[2])
      const sizeScale = clamp(Math.pow(1 / Math.max(dist, 0.2), 0.55), 0.55, 1.5)
      nodes.push({
        id: n.id,
        name: n.name || n.id || '设备',
        palette: n.palette || 'cyan',
        online: n.online !== false,
        streaming: n.streaming === true && n.online !== false,
        level: typeof n.level === 'number' ? n.level : null,
        rings: n.rings === true,
        badge: n.badge || '',
        badgeColor: n.badgeColor || 'rgba(255,210,122,.92)',
        badgeSide: n.badgeSide || '',
        vw: VW,
        vh: VH,
        selected: n.selected === true,
        x: pr.sx,
        y: pr.sy,
        sx: pr.sx,
        sy: pr.sy,
        depth: pr.depth,
        rPx: clamp(0.17 * pr.ppu * sizeScale, 18, 48),
        dist: dist,
        starX: CX,
        starY: CY,
        hubY: CY
      })
    }

    // 光束先画（在星体下方）
    for (let i = 0; i < nodes.length; i++) drawBeam(ctx, nodes[i], starCss)

    // 按深度排序：远 → 近（后面的行星可以挡住恒星边缘，保持三维遮挡关系）
    const items = nodes.slice()
    items.push({
      isStar: true, x: star.x, y: star.y, depth: view.dist, rPx: star.rPx,
      name: star.name, level: star.level, badge: star.badge, badgeColor: star.badgeColor
    })
    items.sort((a, b) => b.depth - a.depth)

    for (let i = 0; i < items.length; i++) {
      const it = items[i]
      if (it.isStar) {
        drawStar(ctx, it.x, it.y, it.rPx, { level: it.level })
        drawHubLabels(ctx, it)
      } else {
        drawPlanet(ctx, it)
      }
    }

    ctx.restore()

    // 返回 CSS 坐标，供预览页把右键菜单摆到节点旁
    const outNodes = nodes.map((n) => ({
      id: n.id,
      sx: ox + n.x * s,
      sy: oy + n.y * s,
      rPx: n.rPx * s
    }))
    return {
      w: w, h: h, s: s, ox: ox, oy: oy,
      hub: { sx: ox + CX * s, sy: oy + CY * s, rPx: star.rPx * s },
      nodes: outNodes
    }
  }

  // 恒星文字：角标在上，名称/总线电平在下
  function drawHubLabels (ctx, star) {
    if (star.badge) {
      text(ctx, star.badge, star.x, star.y - star.rPx - 26, {
        font: '10.5px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
        color: star.badgeColor || 'rgba(255,210,122,.95)', haloSize: 7
      })
    }
    text(ctx, truncate(star.name, 22), star.x, star.y + star.rPx + 22, {
      font: '600 13px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
      color: '#ffe8bd', haloSize: 7
    })
    if (typeof star.level === 'number') {
      text(ctx, '汇聚总线 ' + dbText(star.level), star.x, star.y + star.rPx + 38, {
        font: '11px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
        color: 'rgba(255,220,160,.72)'
      })
    }
  }

  // ============================================================ Android 星图（俯视只读）

  function drawPhoneGraph (canvas, cfg) {
    cfg = cfg || {}
    const f = fitCanvas(canvas)
    const ctx = f.ctx
    const w = f.w
    const h = f.h
    const cx = w / 2
    const cy = h * 0.50
    const VIEW_R = 2.6
    const ppu = Math.min(w, h) / (VIEW_R * 2) * 1.08
    const rnd = mulberry32(cfg.seed == null ? 21 : cfg.seed)

    // 局部星尘 + 中心金色星云
    ctx.save()
    softGlow(ctx, cx, cy, Math.max(w, h) * 0.42, '255,186,96', 0.06, 0.02)
    softGlow(ctx, cx + w * 0.22, cy - h * 0.16, w * 0.30, '140,130,255', 0.05, 0.015)
    ctx.restore()
    for (let i = 0; i < 120; i++) {
      starDot(ctx, rnd() * w, rnd() * h, 0.4 + rnd() * 0.9, 0.12 + rnd() * 0.4,
        rnd() < 0.76 ? '214,228,255' : '255,230,190')
    }

    // 俯视：sx = cx + wx*ppu，sy = cy + wz*ppu（屏幕上方 = 正前 = -Z）
    const maps = (wx, wz) => [cx + wx * ppu, cy + wz * ppu]

    // 轨道环（1 m / 2 m）与方位字
    const rings = [{ r: 1.0, dash: null, a: 0.20 }, { r: 2.0, dash: [4, 7], a: 0.12 }]
    for (let i = 0; i < rings.length; i++) {
      ctx.save()
      ctx.beginPath()
      ctx.arc(cx, cy, rings[i].r * ppu, 0, TAU)
      ctx.strokeStyle = 'rgba(150,180,235,' + rings[i].a + ')'
      ctx.lineWidth = 1
      if (rings[i].dash) ctx.setLineDash(rings[i].dash)
      ctx.stroke()
      ctx.restore()
      const lp = maps(rings[i].r * 0.707, -rings[i].r * 0.707)
      text(ctx, rings[i].r + ' m', lp[0] + 4, lp[1], {
        font: '9.5px "Bahnschrift", "Segoe UI", sans-serif',
        align: 'left', color: 'rgba(150,175,220,.55)', haloSize: 5
      })
    }
    const marks = [
      { x: 0, z: -2.38, label: '前' }, { x: 2.38, z: 0, label: '右' },
      { x: 0, z: 2.38, label: '后' }, { x: -2.38, z: 0, label: '左' }
    ]
    for (let i = 0; i < marks.length; i++) {
      const p = maps(marks[i].x, marks[i].z)
      text(ctx, marks[i].label, p[0], p[1], {
        font: '10px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
        color: 'rgba(150,175,220,.55)', haloSize: 5
      })
    }

    // 恒星（汇聚端）
    const hubRaw = cfg.hub || { name: '汇聚端未知', level: null }
    const starR = clamp(Math.min(w, h) * 0.072, 24, 34)
    const star = {
      name: hubRaw.name || '汇聚端未知',
      level: typeof hubRaw.level === 'number' ? hubRaw.level : null,
      badge: hubRaw.badge || '',
      x: cx,
      y: cy,
      rPx: starR
    }

    const rawNodes = Array.isArray(cfg.nodes) ? cfg.nodes : []
    const nodes = []
    for (let i = 0; i < rawNodes.length; i++) {
      const n = rawNodes[i]
      const px = maps(n.x || 0, n.z || 0)
      const dist = Math.hypot(n.x || 0, n.z || 0)
      const sizeScale = clamp(Math.pow(1 / Math.max(dist, 0.2), 0.55), 0.55, 1.5)
      nodes.push({
        id: n.id,
        name: n.name || n.id || '设备',
        palette: n.palette || 'cyan',
        online: n.online !== false,
        streaming: n.streaming === true && n.online !== false,
        level: typeof n.level === 'number' ? n.level : null,
        rings: n.rings === true,
        badge: n.badge || '',
        badgeColor: n.badgeColor || 'rgba(255,210,122,.92)',
        badgeSide: n.badgeSide || '',
        vw: w,
        vh: h,
        selected: n.selected === true,
        x: px[0],
        y: px[1],
        sx: px[0],
        sy: px[1],
        rPx: clamp(26 * sizeScale, 15, 30),
        dist: dist,
        big: false,
        starX: cx,
        starY: cy,
        hubY: cy
      })
    }

    // 光束
    for (let i = 0; i < nodes.length; i++) drawBeam(ctx, nodes[i], { x: cx, y: cy, r: starR })

    // 先行星、后恒星：恒星始终在最上层（俯视图中心不被遮挡）
    for (let i = 0; i < nodes.length; i++) drawPlanet(ctx, nodes[i])
    drawStar(ctx, cx, cy, starR, { level: star.level })

    // 恒星文字：名称在上方（避开下方音量面板），总线电平在名称上方
    text(ctx, truncate(star.name, 20), cx, cy + starR + 22, {
      font: '600 12.5px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
      color: '#ffe8bd', haloSize: 7
    })
    if (typeof star.level === 'number') {
      text(ctx, '汇聚总线 ' + dbText(star.level), cx, cy + starR + 38, {
        font: '10.5px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
        color: 'rgba(255,220,160,.7)'
      })
    }
    if (star.badge) {
      text(ctx, star.badge, cx, cy - starR - 24, {
        font: '10px "Bahnschrift", "Segoe UI", "Microsoft YaHei", sans-serif',
        color: 'rgba(255,210,122,.9)', haloSize: 6
      })
    }

    return {
      w: w, h: h, ppu: ppu, cx: cx, cy: cy,
      hub: { x: cx, y: cy, r: starR },
      nodes: nodes.map((n) => ({ id: n.id, x: n.x, y: n.y, r: n.rPx }))
    }
  }

  global.StarUI = {
    paintSky: paintSky,
    drawPcGraph: drawPcGraph,
    drawPhoneGraph: drawPhoneGraph,
    PALETTES: PALETTES,
    scale: function (n, a, b, c, d) {
      if (a === b) return c
      return c + (d - c) * (clamp(n, a, b) - a) / (b - a)
    }
  }

  // 自动铺满窗口（预览页只需要给 <canvas id="sky"> 一个尺寸）
  function autoSky () {
    const sky = global.document && global.document.getElementById('sky')
    if (!sky) return
    paintSky(sky, { seed: Number(sky.getAttribute('data-seed')) || 7 })
  }
  if (global.document) {
    if (global.document.readyState === 'complete') autoSky()
    else global.addEventListener('load', autoSky)
    global.addEventListener('resize', function () {
      const sky = global.document.getElementById('sky')
      if (sky) paintSky(sky, { seed: Number(sky.getAttribute('data-seed')) || 7 })
    })
  }
})(typeof window !== 'undefined' ? window : globalThis)
