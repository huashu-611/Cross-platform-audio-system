// 星空材质层（v1.8 星空主题）：整页星野 + 星图里的恒星 / 行星 / 音频光束。
//
// 为什么单独一层：
//   - 整页背景（#sky）与图谱（#graph, scene3d.js）是两张 canvas：背景只在尺寸变化时
//     重画一次，图谱按脏标记重画 —— 星野不跟着相机旋转，也不吃每帧预算；
//   - scene3d.js 只保留投影 / 相机 / 命中 / 交互 / 空间音频这些"功能"，材质统一放这里，
//     以后调色只改这一个文件；
//   - 纯 Canvas 2D、零依赖、无 <script type=module>，符合 index.html 的纯 script 结构
//     与 ui/package.json 的打包白名单（build.files 已加入 starfield.js）。
//
// 语义（与预览稿 docs/starfield-ui-preview/ 一致，并写进 docs）：
//   恒星 = 汇聚端（中心，暖金，日冕 + 四向星芒 + 日珥 + 总线电平弧）
//   行星 = 设备（静止不公转/自转，朝恒星受光；距离越远屏幕越小 —— 与音量衰减同向）
//   光束 = 音频流（恒星 ↔ 行星，出声更亮并带星尘；离线为灰暗虚线）
//   灰暗 + 虚线外圈 = 离线；行星环只给离线 / 特殊设备（用户定案）
//
// 本文件只做绘制，不参与任何状态判断；场景数据由 scene3d.js 传入。

(function (root) {
  'use strict'

  const TAU = Math.PI * 2
  const DEG = Math.PI / 180

  const clamp = (v, lo, hi) => (v < lo ? lo : (v > hi ? hi : v))

  function rgba (rgb, a) {
    return 'rgba(' + rgb + ',' + a + ')'
  }

  // 固定种子伪随机：同一块屏幕每次刷新星点位置一致（不闪、不随机跳）。
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

  // 画布按 CSS 尺寸 × DPR 放大，返回逻辑坐标（CSS px）与 2d ctx。
  function fitCanvas (canvas, alpha) {
    const dpr = Math.min(root.devicePixelRatio || 1, 2)
    const rect = canvas.getBoundingClientRect ? canvas.getBoundingClientRect() : null
    const w = Math.max(1, Math.round(rect && rect.width ? rect.width : (canvas.clientWidth || 1)))
    const h = Math.max(1, Math.round(rect && rect.height ? rect.height : (canvas.clientHeight || 1)))
    const pw = Math.max(1, Math.round(w * dpr))
    const ph = Math.max(1, Math.round(h * dpr))
    if (canvas.width !== pw) canvas.width = pw
    if (canvas.height !== ph) canvas.height = ph
    const ctx = canvas.getContext('2d', alpha === false ? undefined : { alpha: true })
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    return { ctx: ctx, w: w, h: h, dpr: dpr }
  }

  // 柔光：三层由内向外衰减的径向渐变（软件光栅下比 shadowBlur 便宜且更好看）
  function softGlow (ctx, x, y, r, rgb, peak, mid) {
    if (!(r > 0)) return
    const g = ctx.createRadialGradient(x, y, 0, x, y, r)
    g.addColorStop(0, rgba(rgb, peak))
    g.addColorStop(0.45, rgba(rgb, mid))
    g.addColorStop(1, rgba(rgb, 0))
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(x, y, r, 0, TAU)
    ctx.fill()
  }

  function starDot (ctx, x, y, r, a, tint) {
    ctx.globalAlpha = a
    ctx.fillStyle = 'rgb(' + tint + ')'
    ctx.beginPath()
    ctx.arc(x, y, r, 0, TAU)
    ctx.fill()
    ctx.globalAlpha = 1
  }

  // 十字星芒：亮星才有，给静态星野一点"镜头感"
  function crossStar (ctx, x, y, r, a, tint) {
    ctx.save()
    ctx.strokeStyle = rgba(tint, a)
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(x - r * 3.2, y)
    ctx.lineTo(x + r * 3.2, y)
    ctx.moveTo(x, y - r * 3.2)
    ctx.lineTo(x, y + r * 3.2)
    ctx.stroke()
    ctx.globalAlpha = a * 0.55
    softGlow(ctx, x, y, r * 4.5, tint, 0.5, 0.12)
    ctx.restore()
    ctx.globalAlpha = 1
  }

  // ============================================================ 整页星野背景 (#sky)

  const SKY = {
    nebula: [
      { x: 0.16, y: 0.16, r: 0.55, inner: '78,104,196', peak: 0.20, mid: 0.06 },
      { x: 0.82, y: 0.10, r: 0.46, inner: '126,92,214', peak: 0.16, mid: 0.05 },
      { x: 0.74, y: 0.74, r: 0.52, inner: '44,128,168', peak: 0.13, mid: 0.04 },
      { x: 0.30, y: 0.84, r: 0.42, inner: '150,74,132', peak: 0.09, mid: 0.03 }
    ]
  }

  function paintSky (canvas, opts) {
    if (!canvas || typeof canvas.getContext !== 'function') return
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
      softGlow(ctx, 0, 0, n.r * base, n.inner, n.peak, n.mid)
      ctx.restore()
    }

    // 银河带：一条倾斜的密集星带
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

    // 少数带星芒的亮星 + 一条极淡的星座连线（纯装饰）
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

  // ============================================================ 星体调色板

  // 设备行星的配色（按节点 id 稳定分配，不随刷新改变）。
  const PALETTES = {
    cyan: { hi: '#e2fbff', base: '#59c6ea', dark: '#0a2a42', glow: '111,224,255' },
    violet: { hi: '#efe9ff', base: '#9b86ff', dark: '#241650', glow: '170,150,255' },
    rose: { hi: '#ffe6f1', base: '#e486b5', dark: '#4a1734', glow: '255,156,198' },
    amber: { hi: '#fff4d6', base: '#e8b45e', dark: '#4a2d0c', glow: '255,200,110' },
    ice: { hi: '#eef5ff', base: '#a8bad4', dark: '#2a3648', glow: '160,185,215' },
    off: { hi: '#c7d0e0', base: '#5a6579', dark: '#1a202c', glow: '140,155,180' }
  }
  const PALETTE_ORDER = ['cyan', 'violet', 'rose', 'amber', 'ice']

  function paletteForId (id) {
    let h = 2166136261
    const s = String(id == null ? '' : id)
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i)
      h = Math.imul(h, 16777619)
    }
    return PALETTE_ORDER[Math.abs(h) % PALETTE_ORDER.length]
  }

  // ============================================================ 恒星（汇聚端）

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

  // 恒星：日冕 + 四向星芒 + 色球层 + 日珥 + 白热核心 + 总线电平弧
  function drawStar (ctx, x, y, r, opt) {
    opt = opt || {}
    const level = opt.level
    const pct = typeof level === 'number' ? clamp((level + 60) / 60, 0, 1) : 0.82
    const dim = opt.dim === true ? 0.55 : 1
    ctx.save()
    ctx.globalAlpha = dim

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
    ctx.globalAlpha = dim * 0.75
    ctx.beginPath()
    ctx.arc(x, y, r * 1.07, 0.52, 1.42)
    ctx.stroke()
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
    ctx.restore()
  }

  // ============================================================ 行星（设备）

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

  // 行星：朝向恒星的被照亮面 + 大气光晕 + 受光边缘 + 电平弧 + 选中/离线标识。
  //   opt = { starX, starY, online, streaming, palette, level, selected, rings, ghost }
  function drawPlanet (ctx, x, y, r, opt) {
    opt = opt || {}
    const online = opt.online !== false && opt.ghost !== true
    const streaming = opt.streaming === true && online
    const pal = PALETTES[online ? (opt.palette || 'cyan') : 'off']
    const tint = pal.glow
    const light = Math.atan2(Number(opt.starY) - y, Number(opt.starX) - x)

    ctx.save()
    ctx.globalAlpha = online ? 1 : 0.86

    // 大气光晕
    if (online) softGlow(ctx, x, y, r * 2.35, tint, streaming ? 0.22 : 0.12, 0.05)

    // 行星环（后半）
    if (opt.rings) drawPlanetRing(ctx, x, y, r, tint, online)

    // 星球本体（按朝恒星方向受光）
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
      ctx.globalAlpha = online ? 1 : 0.86
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
    if (opt.rings) drawPlanetRingFront(ctx, x, y, r, tint, online)

    // 电平弧（在线且正在出声才有）
    if (streaming && typeof opt.level === 'number') {
      const pct = clamp((opt.level + 60) / 60, 0, 1)
      ctx.save()
      ctx.beginPath()
      ctx.arc(x, y, r + Math.max(4, r * 0.16), -Math.PI / 2, -Math.PI / 2 + pct * TAU)
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

    // 选中态：一圈星环
    if (opt.selected) {
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
  }

  // ============================================================ 音频光束（恒星 ↔ 行星）

  //   star = {x, y, r}，node = {x, y, r, online, streaming, selected, level}
  function drawBeam (ctx, star, node) {
    const online = node.online !== false
    const streaming = node.streaming === true && online
    const tint = online ? (node.selected ? '196,244,255' : '111,224,255') : '150,165,190'
    const dx = node.x - star.x
    const dy = node.y - star.y
    const len = Math.hypot(dx, dy)
    if (!isFinite(len) || len < 1) return
    const ux = dx / len
    const uy = dy / len
    const x1 = star.x + ux * (star.r * 1.25)
    const y1 = star.y + uy * (star.r * 1.25)
    const x2 = node.x - ux * (node.r + 3)
    const y2 = node.y - uy * (node.r + 3)
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

  // ============================================================ 图谱内局部星野（让中心像星系核心）

  const DUST = (function () {
    const rnd = mulberry32(91)
    const out = []
    for (let i = 0; i < 130; i++) {
      out.push({
        x: rnd() * 920,
        y: rnd() * 460,
        r: 0.4 + rnd() * 1.0,
        a: 0.12 + rnd() * 0.42,
        warm: rnd() < 0.25
      })
    }
    return out
  })()

  //   hub = {x, y}（逻辑视口坐标，hub 投影恒为 460,208）
  function drawGraphBackdrop (ctx, hub) {
    const hx = hub && isFinite(hub.x) ? hub.x : 460
    const hy = hub && isFinite(hub.y) ? hub.y : 208
    ctx.save()
    // 恒星周围的金色星云 + 一撮紫色冷光（中心像星系核心）
    softGlow(ctx, hx, hy, 236, '255,186,96', 0.055, 0.02)
    softGlow(ctx, hx + 150, hy - 90, 150, '140,130,255', 0.045, 0.015)
    ctx.restore()
    for (let i = 0; i < DUST.length; i++) {
      const d = DUST[i]
      ctx.globalAlpha = d.a
      ctx.fillStyle = d.warm ? 'rgb(255,230,190)' : 'rgb(214,228,255)'
      ctx.beginPath()
      ctx.arc(d.x, d.y, d.r, 0, TAU)
      ctx.fill()
    }
    ctx.globalAlpha = 1
  }

  root.Starfield = {
    paintSky: paintSky,
    drawStar: drawStar,
    drawPlanet: drawPlanet,
    drawBeam: drawBeam,
    drawGraphBackdrop: drawGraphBackdrop,
    paletteForId: paletteForId,
    PALETTES: PALETTES,
    _dust: DUST
  }
})(typeof window !== 'undefined' ? window : this)
