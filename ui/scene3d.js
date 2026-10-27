// 3D 场景层（纯 Canvas 2D 手写透视投影，零新依赖）。
//
// 为什么不用 WebGL / Three.js（docs/3D-界面与空间音频-可行性与实现方案.md §2.1.1）：
//   - main.js:404 app.disableHardwareAcceleration()，WebGL 会退到软件光栅（SwiftShader），
//     帧率不可控；e2e 还额外 --disable-gpu（tools/ui-e2e.js:165-166）；
//   - ui/ 没有打包器，index.html 只允许 <script src>，引 UMD 包会破坏"纯 script"结构；
//   - ui/package.json:24-31 是打包白名单，node_modules 里的依赖不保证进 asar；
//   - 节点上限 32（include/audiohub/graph.hpp:40），常态 2–6，Canvas 2D 完全够用。
//
// 坐标契约（与 C++ 内核逐字一致，报告 §2.3.1）：
//   右手系，+Y 向上，1 unit ≈ 1 m；听者/汇聚端在原点，yaw=0 面朝 -Z。
//   +X = 听者右耳；屏幕上方 = 远处 = 听者正前；屏幕右 = +X = 右耳；屏幕下方 = 身后。
//
// v1.3 物理空间模型（用户定案，2026-10-26）：
//   - 听者固定在房间中心 (0,0,0)、朝向固定 -Z（yaw=0）。相机 orbit 只改"从哪看"，
//     不转听者、不转声场 —— 左右/前后只由节点在房间地面的位置决定；
//   - 节点不再等距排圆环：默认落在预计算的 32 个散点上（0.85–2.2 m，前方 ≥1.35 m、
//     后方 ≤1.5 m），按设备 id 持久化（store.slots）；拖动可在 0.45–2.3 m 地面自由摆位；
//   - 摆位跨设备同步（v1.4，GRAPH-PROTOCOL §13）：拖完的位置写进内核图谱，用 POS 帧
//     广播给所有电脑/手机 —— 谁拖都算数，位置在全网只有一份（本机 store 只留相机与槽位）；
//   - 球的屏幕大小 = 透视 × 距离缩放（近大远小），与音量衰减同向；名称行显示 "x.xx m"。
//
// v1.5 交互（用户定案，2026-10-27）：
//   左键只负责拖动 —— 节点上拖=摆位，空白处拖=轨道旋转，单击不选中、不弹条；
//   右键节点弹菜单（切换汇聚端 / 音量与静音 / 位置复位，菜单 UI 在 renderer.js）；
//   双击不再触发任何动作（旧"双击切汇聚端"随操作条一起废除）。
//
// 相机：绕原点球坐标 (φ, dist, pitch)，默认 φ=0、dist=3.4、pitch=40°，
// 看向原点；fovY=47.5° → 焦距 focalPx=(460/2)/tan(23.75°)=522.71（与旧 920x460 视口对齐）。
// 汇聚端永远在原点，因此投影恒为 (460,208)——这正是旧 2D 布局的中心（renderer.js 旧 GRAPH_CX/CY）。
//
// 默认 pitch 修订（D1）：初版沿用原型"相机高度固定 0.72 → asin(0.72/2.6)=16.07°"，
// 复核发现默认视角下 θ=-90° 侧附近的前排节点球会落进 hub 球/光晕内（gap≈-27px），
// 且"屏幕方向 vs 声像方位"的透视偏差过大。改为默认 40°：6 节点环与 hub 无遮挡（gap≈+31px），
// 纵深感更强，屏幕方向更接近真实方位。camH/camDist 仅保留给 camera() 的显式反解工具。
// v1.3 相机距离 2.6→3.4：2.6 的视野只够看 1.0 m 环，房间要看到 1–2 m 环与后方节点。
//
// 本文件对外只暴露 window.Scene3D = { CFG, create, camera, project, unproject, hitTest,
// roomLayout, SLOTS, listenerOf, azOf }；纯函数部分可在 node 下 require 做数值自检
// （build/_plan/ui_scene_check.js 就是这么跑的），不依赖 DOM。

(function (root) {
  'use strict'

  const DEG = Math.PI / 180
  const WORLD_UP = [0, 1, 0]

  const VW = 920
  const VH = 460
  const CX = 460
  const CY = 208
  const FOV_Y = 47.5
  const FOCAL = (VH / 2) / Math.tan((FOV_Y / 2) * DEG)   // 522.71 px

  const CFG = {
    // —— 房间 / 听者（v1.3 物理空间模型）——
    listenerYawDeg: 0,        // 听者固定朝 -Z（屏幕上方=正前）；相机绕看不改变声场
    roomR: 2.4,               // 房间参考半径（地面方位刻度用，米）
    ringR: 1.0,               // 内圈参考半径（地面 R=1 环）
    camDist: 3.4,             // 默认相机距离：要同时看下 1 m / 2 m 环与后方节点（原 2.6 只够 1 m 环）
    // —— 默认摆放：32 个互不重叠的预计算槽位（SLOTS）——
    slotRMin: 0.85,
    slotRMax: 2.2,
    slotFrontMin: 1.35,       // 前方（-Z）节点投影会贴近 hub，太近会叠球
    slotRearMax: 1.5,         // 后方（+Z，靠近相机）再远投影会掉出屏幕下沿
    slotGap: 0.42,            // 槽位间最小平面距离（米），保证球不叠
    // —— 拖动 / 相机 ——
    dragRMin: 0.45,           // 拖节点离听者太近会"穿过头"
    dragRMax: 2.3,            // 房间边界（地面 R=2 环外侧留一点余量）
    // —— 球体尺寸：屏幕半径 = 透视投影 × 距离缩放（近大远小，与音量衰减同向）——
    hubBallR: 0.23,           // 旧 HUB_R=46px 反解：46*2.6/522.71≈0.229
    nodeBallR: 0.145,         // 旧 NODE_R=29px 反解：29*2.6/522.71≈0.144
    sizeRef: 1.0,             // 距离 1 m 时缩放 = 1
    sizePow: 0.55,            // scale = (sizeRef/dist)^sizePow
    sizeMin: 0.55,
    sizeMax: 1.5,
    fovYDeg: FOV_Y,
    camH: 0.72,               // 仅 camera() 省略 pitch 时反解用（原型口径 / 反解工具），交互不使用
    pitchDefault: 40,         // 默认俯角（D1 修订：16.07° → 40°，避免前排节点被 hub 球遮挡）
    pitchMin: 6,              // 俯仰下限，避免视线与地面平行（unproject 退化）
    pitchMax: 74,
    distMin: 1.2,             // 滚轮缩放下限
    distMax: 6.0,
    VW: VW, VH: VH, CX: CX, CY: CY,
    near: 0.05,
    focalPx: FOCAL,
    hubRMin: 26, hubRMax: 64, // 球半径像素夹紧：远处可读、近处不压邻居
    nodeRMin: 12, nodeRMax: 40,
    dprMax: 2,
    labelLOD: 8,              // 非 hub 节点 >8 时只给选中/悬停画名称（报告 §2.1.3/R3）
    animMs: 250               // 切 hub / 节点增删的位置插值时长
  }

  // 颜色与 styles.css :12-33 的令牌一致（canvas 不能直接吃 CSS 变量，这里抄一份字面值；
  // --text/--dim/--faint/--accent/--accent-hi 由 create() 在运行时从 getComputedStyle 读取覆盖）。
  const COLOR = {
    line: 'oklch(0.35 0.03 255 / .6)',
    flow: 'oklch(0.72 0.15 255 / .9)',
    text: 'oklch(0.94 0.01 255)',
    dim: 'oklch(0.68 0.02 255)',
    faint: 'oklch(0.52 0.02 255)',
    accent: 'oklch(0.62 0.17 255)',
    accentHi: 'oklch(0.72 0.15 255)',
    stroke: 'oklch(0.55 0.1 255)',
    strokeOff: 'oklch(0.42 0.02 255)',
    fillNode: 'oklch(0.4 0.06 255)',
    fillOff: 'oklch(0.3 0.03 255)',
    fillGhost: 'oklch(0.26 0.02 255)',
    ground: 'oklch(0.42 0.03 255 / .42)',
    groundDim: 'oklch(0.42 0.03 255 / .22)'
  }

  const STORE_KEY = 'audiohub.scene3d.v1'

  // 摆位坐标上限（米）：与内核 POS 帧的 ±20 m 上限一致（拖拽交互另受 CFG.dragRMax 约束）。
  // 位置真相在内核图谱里（跨电脑/手机同步），本机只存相机与自动槽位。
  const ROOM_COORD_MAX = 20
  const STORE_VERSION = 3

  // ============================================================ 向量与数值工具

  const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
  const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
  const mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s]
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
  const cross = (a, b) => [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
  ]
  const len = (a) => Math.hypot(a[0], a[1], a[2])
  const norm = (a) => {
    const l = len(a)
    return l > 1e-12 ? [a[0] / l, a[1] / l, a[2] / l] : [0, 0, 0]
  }
  const clamp = (v, lo, hi) => (v < lo ? lo : (v > hi ? hi : v))
  const wrap360 = (d) => ((d % 360) + 360) % 360
  const wrap180 = (d) => {
    const a = wrap360(d)
    return a > 180 ? a - 360 : a
  }
  const r3 = (v) => Math.round(v * 1000) / 1000

  // ============================================================ 相机与投影（纯函数）

  // 相机绕原点轨道：φ 绕 +Y（0 = 站在 +Z 处看向 -Z），pitch 为俯角。
  // pitchDeg 省略时按"相机高度 camH 固定"反解 —— 现在只作为显式反解工具保留；
  // create() 初始化一律显式传 CFG.pitchDefault（40°），不受 camH 影响。
  function camera (phiDeg, dist, pitchDeg) {
    const d = Number.isFinite(dist) ? clamp(dist, CFG.distMin, CFG.distMax) : CFG.camDist
    const phi = (Number.isFinite(phiDeg) ? phiDeg : 0) * DEG
    const p = Number.isFinite(pitchDeg)
      ? clamp(pitchDeg, CFG.pitchMin, CFG.pitchMax) * DEG
      : Math.asin(Math.min(CFG.camH / d, 0.999))
    const eye = [
      d * Math.cos(p) * Math.sin(phi),
      d * Math.sin(p),
      d * Math.cos(p) * Math.cos(phi)
    ]
    const fwd = norm(sub([0, 0, 0], eye))
    const right = norm(cross(fwd, WORLD_UP))   // φ=0 时 = +X（右耳）
    const up = cross(right, fwd)
    return {
      eye: eye,
      fwd: fwd,
      right: right,
      up: up,
      phiDeg: wrap180(phi / DEG),
      dist: d,
      pitchDeg: p / DEG,
      focal: CFG.focalPx
    }
  }

  // 世界点 → 虚拟 920x460 屏幕坐标（与旧 SVG viewBox 同坐标，hub 恒为 (460,208)）
  function project (view, p) {
    const rel = sub(p, view.eye)
    const depth = dot(rel, view.fwd)
    if (depth <= CFG.near) return { visible: false, depth: depth, ppu: 0 }
    const k = view.focal / depth
    return {
      visible: true,
      sx: CFG.CX + k * dot(rel, view.right),
      sy: CFG.CY - k * dot(rel, view.up),
      depth: depth,
      ppu: k
    }
  }

  // 屏幕（虚拟 920x460）→ y=0 水平面。拾取与拖动共用；pitch>=6° 时不会退化（报告 §3.1）。
  function unproject (view, sx, sy, planeY) {
    const dx = (sx - CFG.CX) / view.focal
    const dy = -(sy - CFG.CY) / view.focal
    const dir = norm(add(add(mul(view.right, dx), mul(view.up, dy)), view.fwd))
    if (Math.abs(dir[1]) < 1e-6) return null
    const t = ((planeY == null ? 0 : planeY) - view.eye[1]) / dir[1]
    return t <= 0 ? null : add(view.eye, mul(dir, t))
  }

  // 命中测试：投影球半径 + 容差；同距取更近深度；相机后方的 visible=false 自然不可点。
  function hitTest (items, sx, sy, slop) {
    let best = null
    for (let i = 0; i < items.length; i++) {
      const n = items[i]
      if (!n.visible) continue
      const rPx = n.rPx + (slop == null ? 6 : slop)
      const d2 = (n.sx - sx) * (n.sx - sx) + (n.sy - sy) * (n.sy - sy)
      if (d2 > rPx * rPx) continue
      if (!best || d2 < best.d2 || (d2 === best.d2 && n.depth < best.n.depth)) best = { n: n, d2: d2 }
    }
    return best ? best.n : null
  }

  // ============================================================ 布局：物理房间里的位置（v1.3）
  //
  // 用户定案（2026-10-26）：不要"等距圆环 + 固定前后左右"，要物理房间——声源在地面上
  // 有真实位置，离听者越远声音越小、球也越小。默认位置用预计算的 32 个散点槽位：
  // 距离 0.85–2.2 m（前方 ≥1.35 m 防叠 hub 球，后方 ≤1.5 m 防投到屏幕外），
  // 相邻槽位平面距离 ≥0.42 m；按设备 id 持久化（store.slots），增删其他设备不重排。
  // 拖动 = 地面自由摆位（0.45–2.3 m），落点同步进内核图谱（全设备一致）；
  // 位置复位 = 右键菜单里的一项，清掉全网摆位后退回本机槽位。
  const SLOT_COUNT = 32

  // 候选点是否与已占槽位保持 slotGap（米）
  function slotFree (placed, x, z) {
    for (let k = 0; k < placed.length; k++) {
      const dx = x - placed[k].pos[0]
      const dz = z - placed[k].pos[2]
      if (dx * dx + dz * dz < CFG.slotGap * CFG.slotGap) return false
    }
    return true
  }

  function buildSlots () {
    const out = []
    const GA = 137.507764               // 黄金角：方向铺得快，相邻槽位不扎堆（度）
    const radii = [
      2.00, 1.55, 1.20, 1.85, 0.95, 2.10, 1.30, 2.20,
      1.05, 1.70, 1.45, 0.85, 1.90, 1.15, 2.05, 1.60,
      1.00, 1.75, 0.90, 2.15, 1.35, 1.50, 1.80, 1.10,
      1.65, 1.25, 1.95, 0.85, 1.40, 1.85, 1.05, 1.55
    ]
    for (let i = 0; i < SLOT_COUNT; i++) {
      let theta = (-90 + i * GA) * DEG
      let r = radii[i % radii.length]
      // 前方（z<0）不能太近、后方（z>0，靠近相机）不能太远 —— 二者都会投影压球/出屏。
      r = Math.sin(theta) > 0 ? Math.min(r, CFG.slotRearMax) : Math.max(r, CFG.slotFrontMin)
      let x = 0
      let z = 0
      let accepted = false
      for (let tries = 0; tries < 24; tries++) {
        x = r * Math.cos(theta)
        z = r * Math.sin(theta)
        if (slotFree(out, x, z)) { accepted = true; break }
        theta += 13.5 * DEG
        if (Math.sin(theta) > 0) r = Math.min(r + 0.05, CFG.slotRearMax)
        else r = Math.min(r + 0.08, CFG.slotRMax)
        if (r < CFG.slotRMin) r = CFG.slotRMin
      }
      if (!accepted) {
        // 重试 24 次仍可能撞位（例如 slot17 与 slot29 只差 0.05m，球会几乎重叠）。
        // 兜底：在前后/半径规则内全周扫一遍，选与已占槽位“最小间距最大”的点；
        // 确定性（角度 0.1°、半径 0.01m 网格、固定次序），不依赖运行环境。
        let best = null
        let bestLegal = null
        for (let a = 0; a < 3600; a++) {
          const th = a * 0.1 * DEG
          const rear = Math.sin(th) > 0
          const lo = rear ? CFG.slotRMin : CFG.slotFrontMin
          const hi = rear ? CFG.slotRearMax : CFG.slotRMax
          for (let rr = lo; rr <= hi + 1e-9; rr += 0.01) {
            const px = rr * Math.cos(th)
            const pz = rr * Math.sin(th)
            let gap = Infinity
            for (let k = 0; k < out.length; k++) {
              const dx = px - out[k].pos[0]
              const dz = pz - out[k].pos[2]
              const d = Math.sqrt(dx * dx + dz * dz)
              if (d < gap) gap = d
            }
            const cost = Math.abs(th - theta) + Math.abs(rr - r)
            if (!best || gap > best.gap + 1e-9 ||
                (Math.abs(gap - best.gap) <= 1e-9 && cost < best.cost)) {
              best = { x: px, z: pz, gap: gap, cost: cost }
            }
            // 合法（≥slotGap）里取位移最小者：保持原本的布局方位不乱跳
            if (gap >= CFG.slotGap - 1e-9 && (!bestLegal || cost < bestLegal.cost)) {
              bestLegal = { x: px, z: pz, gap: gap, cost: cost }
            }
          }
        }
        const pick = bestLegal || best
        x = pick.x
        z = pick.z
      }
      out.push({ pos: [x, 0, z], azDeg: wrap360(Math.atan2(x, -z) / DEG) })
    }
    return out
  }

  const SLOTS = buildSlots()

  function slotAt (idx) {
    return SLOTS[((idx % SLOT_COUNT) + SLOT_COUNT) % SLOT_COUNT]
  }

  // nodes: 非 hub 节点数组（已按 id 升序，项可带 posSet/x/z）；slots: { id: idx }。
  // 返回 Map(id → {pos, slot, pinned, azDeg})。位置来源优先级：
  //   1) 图谱里的显式摆位（内核 POS 帧同步，全设备一致 / 可跨电脑）
  //   2) 本机持久化的自动槽位（只影响没摆过的设备，避免位置跳变）
  function roomLayout (nodes, slots) {
    const out = new Map()
    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i]
      const id = n.id
      if (n.posSet === true && Number.isFinite(n.x) && Number.isFinite(n.z)) {
        const x = clamp(n.x, -ROOM_COORD_MAX, ROOM_COORD_MAX)
        const z = clamp(n.z, -ROOM_COORD_MAX, ROOM_COORD_MAX)
        out.set(id, { pos: [x, 0, z], slot: -1, pinned: true, azDeg: wrap360(Math.atan2(x, -z) / DEG) })
        continue
      }
      const idx = slots && Number.isInteger(slots[id]) ? slots[id] : -1
      const s = slotAt(idx >= 0 ? idx : i)
      out.set(id, { pos: s.pos.slice(), slot: idx, pinned: false, azDeg: s.azDeg })
    }
    return out
  }

  // ============================================================ 听者位姿（v1.3：物理房间，听者固定）
  //
  // 听者坐在房间中心 (0,0,0)，面朝 -Z（默认相机 φ=0 时的屏幕上方）。相机 orbit 只改
  // “从哪看”，不改听者朝向：转动视角不会让声场旋转，左/右只由节点在房间里的位置决定。
  // 报给 C++ 的位姿恒为 pos=(0,0,0)、yaw=0、pitch=0。
  function listenerOf (view) {
    const a = CFG.listenerYawDeg * DEG
    const fwdH = [-Math.sin(a), 0, -Math.cos(a)]
    const rightH = norm(cross(fwdH, WORLD_UP))
    return { pos: [0, 0, 0], yawDeg: CFG.listenerYawDeg, pitchDeg: 0, fwdH: fwdH, rightH: rightH }
  }

  // 相对听者方位：az 0=正前、+90=右耳、-90=左耳、±180=身后；dist 为欧氏距离（音量衰减用）
  function azOf (listener, p) {
    const rel = [p[0] - listener.pos[0], 0, p[2] - listener.pos[2]]
    return {
      azDeg: Math.atan2(dot(rel, listener.rightH), dot(rel, listener.fwdH)) / DEG,
      dist: Math.hypot(p[0], p[1], p[2])
    }
  }

  // ============================================================ 场景实例

  function makeNode (raw, isHub) {
    return {
      id: String(raw.id),
      name: typeof raw.name === 'string' && raw.name ? raw.name : String(raw.id),
      isHub: !!isHub,
      isSelf: raw.isSelf === true,
      online: raw.online !== false,
      ghost: false,
      kind: typeof raw.kind === 'string' ? raw.kind : 'other',
      streamId: Number.isInteger(raw.streamId) ? raw.streamId : 0,
      // 显式摆位（内核 POS 帧同步过来的房间坐标，米）；posSet=false 时用本机自动槽位。
      posSet: raw.posSet === true && Number.isFinite(raw.x) && Number.isFinite(raw.z),
      x: Number.isFinite(raw.x) ? raw.x : 0,
      z: Number.isFinite(raw.z) ? raw.z : 0,
      level: null,
      flow: false,
      pinned: false
    }
  }

  function ghostHub () {
    return {
      id: '__hub_unknown__',
      name: '汇聚端未知',
      isHub: true,
      isSelf: false,
      online: false,
      ghost: true,
      kind: 'ghost',
      streamId: 0,
      posSet: false,
      x: 0,
      z: 0,
      level: null,
      flow: false,
      pinned: false
    }
  }

  function loadStore () {
    // v3：摆位移到内核图谱（POS 帧同步），本机只留相机与自动槽位；v1/v2 的 pins 直接丢弃
    // （旧的不跨设备摆位不再回放，避免同一个节点在两台电脑上位置不一致）。
    const empty = { v: STORE_VERSION, cam: { yawDeg: 0, pitchDeg: CFG.pitchDefault, dist: CFG.camDist }, slots: {} }
    try {
      const ls = root.localStorage
      if (!ls) return empty
      const parsed = JSON.parse(ls.getItem(STORE_KEY) || 'null')
      if (!parsed || (parsed.v !== 1 && parsed.v !== 2 && parsed.v !== STORE_VERSION)) return empty
      const cam = parsed.cam && typeof parsed.cam === 'object' ? parsed.cam : {}
      const slots = {}
      if (parsed.v >= 2 && parsed.slots && typeof parsed.slots === 'object') {
        for (const id of Object.keys(parsed.slots)) {
          const idx = parsed.slots[id]
          if (Number.isInteger(idx) && idx >= 0 && idx < SLOT_COUNT) slots[id] = idx
        }
      }
      return {
        v: STORE_VERSION,
        cam: {
          yawDeg: Number.isFinite(cam.yawDeg) ? wrap180(cam.yawDeg) : 0,
          pitchDeg: Number.isFinite(cam.pitchDeg) ? clamp(cam.pitchDeg, CFG.pitchMin, CFG.pitchMax) : CFG.pitchDefault,
          // v1→v2：房间比旧圆环大一圈，旧默认距离 2.6 会切掉 R=2 环，迁移时统一回默认 3.4。
          dist: parsed.v >= 2 && Number.isFinite(cam.dist) ? clamp(cam.dist, CFG.distMin, CFG.distMax) : CFG.camDist
        },
        slots: slots
      }
    } catch (e) {
      return empty
    }
  }

  function create (opts) {
    opts = opts || {}
    const canvas = opts.canvas
    if (!canvas || typeof canvas.getContext !== 'function') throw new Error('Scene3D.create: canvas required')
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('Scene3D.create: 2d context unavailable')

    // 交互分工（v1.5）：左键只负责拖动（节点摆位 / 空白处轨道旋转），
    // 右键在节点上弹菜单（切换汇聚端 / 音量与静音 / 位置复位），菜单由渲染层实现。
    const onMenu = typeof opts.onMenu === 'function' ? opts.onMenu : function () {}
    const onDragEnd = typeof opts.onDragEnd === 'function' ? opts.onDragEnd : function () {}

    const store = loadStore()
    let view = camera(store.cam.yawDeg, store.cam.dist,
      store.cam.pitchDeg == null ? CFG.pitchDefault : store.cam.pitchDeg)
    let nodes = []
    let byId = new Map()
    let worldPos = new Map()
    let targetPos = new Map()
    let lastGraph = null
    let selectedId = null
    let hoverId = null
    let pcSource = false
    let active = true
    let dirty = true
    let rafId = 0
    let lastItems = []
    let lastFpsAt = 0
    let lastFpsFrames = 0
    let fps = 0
    let anim = { active: false, from: new Map(), t0: 0, dur: CFG.animMs }
    let drag = null
    let spriteCache = new Map()
    let spriteScaleQ = 0
    let theme = null

    // 画布尺寸：内部像素按 DPR 放大，绘制坐标恒为 920x460（旧常量/e2e 坐标继续成立）
    let cw = VW
    let ch = VH
    const canvasSize = { cssW: 0, cssH: 0, dpr: 1 }

    function readTheme () {
      const pick = (name, fallback) => {
        try {
          const v = root.getComputedStyle
            ? root.getComputedStyle(root.document.documentElement).getPropertyValue(name).trim()
            : ''
          return v || fallback
        } catch (e) {
          return fallback
        }
      }
      theme = {
        text: pick('--text', COLOR.text),
        dim: pick('--dim', COLOR.dim),
        faint: pick('--faint', COLOR.faint),
        accent: pick('--accent', COLOR.accent),
        accentHi: pick('--accent-hi', COLOR.accentHi),
        warn: pick('--warn', 'oklch(0.80 0.15 85)')
      }
    }

    function saveStore () {
      try {
        const ls = root.localStorage
        if (!ls) return
        ls.setItem(STORE_KEY, JSON.stringify({
          v: STORE_VERSION,
          cam: { yawDeg: view.phiDeg, pitchDeg: view.pitchDeg, dist: view.dist },
          slots: store.slots
        }))
      } catch (e) { /* localStorage 不可用（隐私模式/文件协议）时静默 */ }
    }

    // ---------------------------------------------------------- 结构：图谱 → 3D 位置

    function byIdAsc (a, b) {
      return a.id < b.id ? -1 : (a.id > b.id ? 1 : 0)
    }

    // graph = renderer.collectGraph(state) 的返回；结构变化（节点集合/hub/在线/名字）时才调用。
    function setGraph (graph) {
      const prevWorld = new Map(worldPos)
      const g = graph && typeof graph === 'object' ? graph : null
      lastGraph = g
      const hubRaw = g && g.hubNode && !g.hubNode.ghost ? g.hubNode : null
      const hub = hubRaw ? makeNode(hubRaw, true) : ghostHub()
      hub.pinned = false   // hub 恒在房间中心（听者位），不参与摆位

      const othersRaw = []
      const rawList = g && Array.isArray(g.nodes) ? g.nodes : []
      for (let i = 0; i < rawList.length; i++) {
        const n = rawList[i]
        if (!n || typeof n.id !== 'string' || !n.id) continue
        if (n.id === hub.id) continue
        othersRaw.push(n)
      }
      othersRaw.sort(byIdAsc)

      nodes = [hub]
      byId = new Map()
      byId.set(hub.id, hub)

      // v1.3：房间槽位（store.slots 持久化，只服务“没摆过”的设备）。先剪掉已不在图中的
      // 设备避免 32 槽位耗尽；新设备取最小空闲槽，按 id 升序分配 → 结果确定。
      // 显式摆位（图谱 POS 帧）优先于槽位；菜单里"位置复位"后回落到自己的槽位。
      const present = new Set()
      for (let i = 0; i < othersRaw.length; i++) present.add(othersRaw[i].id)
      let slotsDirty = false
      for (const id of Object.keys(store.slots)) {
        if (!present.has(id)) { delete store.slots[id]; slotsDirty = true }
      }
      const used = new Set()
      for (const id of Object.keys(store.slots)) {
        const v = store.slots[id]
        if (Number.isInteger(v) && v >= 0 && v < SLOT_COUNT) used.add(v)
        else { delete store.slots[id]; slotsDirty = true }
      }
      const allocate = () => {
        for (let k = 0; k < SLOT_COUNT; k++) {
          if (!used.has(k)) { used.add(k); slotsDirty = true; return k }
        }
        return -1
      }
      for (let i = 0; i < othersRaw.length; i++) {
        const id = othersRaw[i].id
        if (!Number.isInteger(store.slots[id])) store.slots[id] = allocate()
      }
      const lay = roomLayout(othersRaw, store.slots)
      if (slotsDirty) saveStore()
      for (let i = 0; i < othersRaw.length; i++) {
        const raw = othersRaw[i]
        const rec = makeNode(raw, false)
        const l = lay.get(rec.id)
        rec.pinned = l.pinned
        rec.azDeg = l.azDeg
        rec.slot = l.slot
        nodes.push(rec)
        byId.set(rec.id, rec)
      }

      targetPos = new Map()
      targetPos.set(hub.id, [0, 0, 0])
      for (let i = 0; i < othersRaw.length; i++) {
        const id = othersRaw[i].id
        targetPos.set(id, lay.get(id).pos)
      }

      applyTargets(prevWorld)
      dirty = true
      return nodes.length
    }

    // 位置插值：切 hub 时旧 hub 回环位、新 hub 移到原点，都从当前位置平滑过去（250ms）。
    function applyTargets (prevWorld) {
      const from = new Map()
      let needAnim = false
      for (const id of Array.from(worldPos.keys())) {
        if (!targetPos.has(id)) worldPos.delete(id)
      }
      for (const entry of targetPos) {
        const id = entry[0]
        const to = entry[1]
        const cur = worldPos.get(id) || prevWorld.get(id)
        if (cur && active) {
          from.set(id, [cur[0], cur[1], cur[2]])
          worldPos.set(id, [cur[0], cur[1], cur[2]])
          if (Math.abs(cur[0] - to[0]) > 1e-6 || Math.abs(cur[2] - to[2]) > 1e-6) needAnim = true
        } else {
          worldPos.set(id, [to[0], to[1], to[2]])
        }
      }
      if (needAnim) {
        anim.active = true
        anim.from = from
        anim.t0 = (root.performance && root.performance.now ? root.performance.now() : Date.now())
        anim.dur = CFG.animMs
      } else {
        anim.active = false
        anim.from = new Map()
        // 首帧无动画：直接落位
        for (const entry of targetPos) worldPos.set(entry[0], entry[1].slice())
      }
    }

    // ---------------------------------------------------------- 动态：电平 / 流强 / 选中

    // dyn = { levels: {id: dB|null}, flows: {id: bool}, selectedId, pcSource }
    // 每 200ms 轮询调用一次，只更新动态字段，不重排布局（否则点击会丢、pin 会重置）。
    function setDynamic (dyn) {
      if (!dyn || typeof dyn !== 'object') return
      const levels = dyn.levels || {}
      const flows = dyn.flows || {}
      for (let i = 0; i < nodes.length; i++) {
        const rec = nodes[i]
        const lv = levels[rec.id]
        rec.level = (typeof lv === 'number' && Number.isFinite(lv)) ? lv : null
        rec.flow = flows[rec.id] === true
      }
      selectedId = typeof dyn.selectedId === 'string' && dyn.selectedId ? dyn.selectedId : null
      pcSource = dyn.pcSource === true
      dirty = true
    }

    // ---------------------------------------------------------- 场景 → 声场（上报用）

    function spatialFrame () {
      const listener = listenerOf(view)
      const sources = []
      for (let i = 0; i < nodes.length; i++) {
        const rec = nodes[i]
        if (rec.isHub || rec.ghost) continue
        const p = worldPos.get(rec.id) || [0, 0, 0]
        // D3：PC 环回音乐源（本机作为音源发送时）旁路空间化，否则立体声会被单声道化。
        const enabled = !(rec.isSelf && pcSource)
        sources.push({ id: rec.id, x: r3(p[0]), y: 0, z: r3(p[2]), enabled: enabled })
      }
      return {
        listener: {
          x: 0, y: 0, z: 0,
          yaw: Math.round(wrap180(listener.yawDeg) * 100) / 100,
          pitch: Math.round(listener.pitchDeg * 100) / 100
        },
        sources: sources
      }
    }

    // ---------------------------------------------------------- 调试快照（__hubDebug.scene）

    function debug () {
      const items = projectAll()
      const outNodes = []
      const nodeScreen = {}
      const positions = {}
      for (let i = 0; i < items.length; i++) {
        const it = items[i]
        const c = toClient(it.sx, it.sy)
        const rec = it.rec
        outNodes.push({
          id: rec.id,
          sx: Math.round(it.sx * 1000) / 1000,
          sy: Math.round(it.sy * 1000) / 1000,
          clientX: Math.round(c.clientX * 100) / 100,
          clientY: Math.round(c.clientY * 100) / 100,
          rPx: Math.round(it.rPx * 100) / 100,
          depth: Math.round(it.depth * 1000) / 1000,
          azDeg: Math.round(it.azDeg * 100) / 100,
          dist: Math.round(it.dist * 1000) / 1000,
          visible: it.visible,
          isHub: !!rec.isHub,
          online: !!rec.online,
          pinned: !!rec.pinned,
          selected: rec.id === selectedId,
          level: rec.level,
          slot: Number.isInteger(rec.slot) ? rec.slot : -1,
          sizeScale: Math.round(it.sizeScale * 100) / 100,
          enabled: !(rec.isSelf && pcSource)
        })
        nodeScreen[rec.id] = { sx: outNodes[i].sx, sy: outNodes[i].sy, clientX: outNodes[i].clientX, clientY: outNodes[i].clientY }
        const p = worldPos.get(rec.id)
        positions[rec.id] = p ? [r3(p[0]), r3(p[1]), r3(p[2])] : [0, 0, 0]
      }
      const hubItem = items.find((it) => it.rec.isHub) || null
      return {
        view: {
          phiDeg: Math.round(view.phiDeg * 100) / 100,
          pitchDeg: Math.round(view.pitchDeg * 100) / 100,
          dist: Math.round(view.dist * 1000) / 1000,
          focal: Math.round(view.focal * 100) / 100,
          cx: CX, cy: CY
        },
        hubScreen: hubItem ? nodeScreen[hubItem.rec.id] : null,
        nodes: outNodes,
        nodeScreen: nodeScreen,
        positions: positions,
        listener: spatialFrame().listener,
        fps: fps,
        placed: nodes.filter((n) => n.posSet).map((n) => n.id),
        slots: Object.assign({}, store.slots),
        room: {
          radius: CFG.roomR,
          rings: [1, 2],
          dragRMin: CFG.dragRMin,
          dragRMax: CFG.dragRMax,
          sizePow: CFG.sizePow
        },
        pcSource: pcSource,
        selected: selectedId,
        dragging: drag && drag.moved ? drag.id : null,
        canvas: {
          width: canvas.width,
          height: canvas.height,
          cssW: canvasSize.cssW,
          cssH: canvasSize.cssH,
          dpr: canvasSize.dpr
        }
      }
    }

    // ---------------------------------------------------------- 屏幕坐标换算

    function rect () {
      try {
        return canvas.getBoundingClientRect()
      } catch (e) {
        return { left: 0, top: 0, width: VW, height: VH }
      }
    }

    function toLogical (clientX, clientY) {
      const r = rect()
      const fit = fitInfo()
      return { sx: (clientX - r.left - fit.ox) / fit.s, sy: (clientY - r.top - fit.oy) / fit.s }
    }

    function toClient (sx, sy) {
      const r = rect()
      const fit = fitInfo()
      return { clientX: r.left + fit.ox + sx * fit.s, clientY: r.top + fit.oy + sy * fit.s }
    }

    // 等比适配：逻辑视口恒 920x460，画布按容器大小等比缩放后在容器里居中；
    // 容器宽高比与视口不一致时多出的部分留边（不拉伸、也不裁掉任何节点）。
    function fitInfo () {
      const r = rect()
      const cssW = Math.max(1, r.width || VW)
      const cssH = Math.max(1, r.height || (cssW * VH / VW))
      const s = Math.min(cssW / VW, cssH / VH)
      return { cssW: cssW, cssH: cssH, s: s, ox: (cssW - VW * s) / 2, oy: (cssH - VH * s) / 2 }
    }

    // ---------------------------------------------------------- 投影与绘制

    function projectAll () {
      const items = []
      const listener = listenerOf(view)
      for (let i = 0; i < nodes.length; i++) {
        const rec = nodes[i]
        const pos = worldPos.get(rec.id) || [0, 0, 0]
        const pr = project(view, pos)
        const a = azOf(listener, pos)
        let rPx
        let sizeScale = 1
        if (rec.isHub) {
          rPx = clamp(CFG.hubBallR * pr.ppu, CFG.hubRMin, CFG.hubRMax)
        } else {
          // 近大远小：距离 1 m 时缩放 = 1，越远越小 —— 与音量衰减同向（用户"用大小模拟远近"）。
          sizeScale = clamp(Math.pow(CFG.sizeRef / Math.max(a.dist, 0.2), CFG.sizePow), CFG.sizeMin, CFG.sizeMax)
          rPx = clamp(CFG.nodeBallR * pr.ppu * sizeScale, CFG.nodeRMin, CFG.nodeRMax)
        }
        items.push({
          rec: rec,
          pos: pos,
          sx: pr.sx,
          sy: pr.sy,
          depth: pr.depth,
          ppu: pr.ppu,
          visible: pr.visible,
          rPx: rPx,
          azDeg: wrap180(a.azDeg),
          dist: a.dist,
          sizeScale: sizeScale
        })
      }
      items.sort((a, b) => b.depth - a.depth)     // 远 → 近
      return items
    }

    function ensureSize () {
      const fit = fitInfo()
      const dpr = Math.min(root.devicePixelRatio || 1, CFG.dprMax)
      const w = Math.max(1, Math.round(fit.cssW * dpr))
      const h = Math.max(1, Math.round(fit.cssH * dpr))
      if (canvas.width !== w) canvas.width = w
      if (canvas.height !== h) canvas.height = h
      canvasSize.cssW = fit.cssW
      canvasSize.cssH = fit.cssH
      canvasSize.dpr = dpr
      return { s: fit.s * dpr, ox: fit.ox * dpr, oy: fit.oy * dpr, dpr: dpr }
    }

    function ballColor (rec) {
      if (rec.ghost) return COLOR.fillGhost
      if (rec.isHub) return theme ? theme.accent : COLOR.accent
      return rec.online ? COLOR.fillNode : COLOR.fillOff
    }

    function strokeColor (rec, selected) {
      if (selected) return theme ? theme.text : COLOR.text
      return rec.online ? COLOR.stroke : COLOR.strokeOff
    }

    // 预渲染球 sprite（每帧 createRadialGradient 在软件光栅下太贵，报告 §2.1.4）
    function getSprite (rec, rPx, selected, effScale) {
      const fill = ballColor(rec)
      const stroke = strokeColor(rec, selected)
      const offline = !rec.online && !rec.ghost
      const key = [fill, stroke, Math.round(rPx * 2) / 2, selected ? 1 : 0, offline ? 1 : 0, rec.ghost ? 1 : 0, spriteScaleQ].join('|')
      const cached = spriteCache.get(key)
      if (cached) return cached
      if (!root.document || !root.document.createElement) return { img: null, size: 0 }
      const size = rPx * 2 + 4
      const px = Math.max(4, Math.round(size * effScale))
      const cv = root.document.createElement('canvas')
      cv.width = px
      cv.height = px
      const g = cv.getContext('2d')
      if (!g) return { img: null, size: 0 }
      const s = px / size
      g.setTransform(s, 0, 0, s, 0, 0)
      g.translate(size / 2, size / 2)
      // 底球
      g.beginPath()
      g.arc(0, 0, rPx, 0, Math.PI * 2)
      g.fillStyle = fill
      g.fill()
      // 立体高光：左上偏亮、边缘压暗（纯白/纯黑叠层，不依赖具体色值）
      const grad = g.createRadialGradient(-rPx * 0.34, -rPx * 0.38, rPx * 0.12, 0, 0, rPx * 1.06)
      grad.addColorStop(0, 'rgba(255,255,255,0.40)')
      grad.addColorStop(0.42, 'rgba(255,255,255,0.10)')
      grad.addColorStop(0.82, 'rgba(0,0,0,0.06)')
      grad.addColorStop(1, 'rgba(0,0,0,0.34)')
      g.fillStyle = grad
      g.fill()
      // 描边（离线用虚线，与旧 CSS/旧 SVG 一致）
      g.beginPath()
      g.arc(0, 0, rPx, 0, Math.PI * 2)
      g.lineWidth = selected ? 3 : 2
      g.strokeStyle = stroke
      if (offline) g.setLineDash([5, 5])
      g.stroke()
      g.setLineDash([])
      const spr = { img: cv, size: size }
      spriteCache.set(key, spr)
      if (spriteCache.size > 200) spriteCache.clear()
      return spr
    }

    function levelPct (level) {
      return clamp((level + 60) / 0.6, 0, 100)
    }

    function shortName (name, max) {
      const chars = Array.from(String(name == null ? '' : name))
      if (chars.length <= max) return chars.join('')
      return chars.slice(0, max).join('') + '…'
    }

    function badgeOf (rec) {
      if (!rec || rec.ghost) return ''
      const parts = []
      if (rec.isSelf) parts.push('本机')
      if (rec.isHub) parts.push('汇聚端')
      if (!rec.online) parts.push('离线')
      return parts.join(' · ')
    }

    function drawGround (scale) {
      // 地面距离参考环：R=1 m / R=2 m（透视下是椭圆）。远近与音量衰减都靠它判读。
      const rings = [{ r: 1.0, dash: null }, { r: 2.0, dash: [4, 6] }]
      for (let ri = 0; ri < rings.length; ri++) {
        const rr = rings[ri].r
        ctx.beginPath()
        let started = false
        for (let i = 0; i <= 96; i++) {
          const a = (i / 96) * Math.PI * 2
          const p = project(view, [rr * Math.sin(a), 0, -rr * Math.cos(a)])
          if (!p.visible) { started = false; continue }
          if (!started) { ctx.moveTo(p.sx, p.sy); started = true } else ctx.lineTo(p.sx, p.sy)
        }
        ctx.strokeStyle = ri === 0 ? COLOR.ground : COLOR.groundDim
        ctx.lineWidth = 1
        if (rings[ri].dash) ctx.setLineDash(rings[ri].dash)
        ctx.stroke()
        ctx.setLineDash([])
      }

      // 方位刻度：前/右/后/左（世界固定方向；相机绕看不改变它，与声像一致）
      const marks = [
        { az: 0, label: '前' }, { az: 90, label: '右' },
        { az: 180, label: '后' }, { az: 270, label: '左' }
      ]
      ctx.font = '10px "Bahnschrift", "Segoe UI", sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillStyle = theme ? theme.faint : COLOR.faint
      ctx.globalAlpha = 0.85
      for (let i = 0; i < marks.length; i++) {
        const a = marks[i].az * DEG
        const rr = 1.75
        const p = project(view, [rr * Math.sin(a), 0, -rr * Math.cos(a)])
        if (!p.visible) continue
        ctx.fillText(marks[i].label, p.sx, p.sy)
      }
      ctx.globalAlpha = 1

      // 距离刻度：R=1/2 环上 45° 方向标 "1 m / 2 m"
      ctx.font = '9.5px "Bahnschrift", "Segoe UI", sans-serif'
      ctx.textAlign = 'left'
      for (let ri = 0; ri < rings.length; ri++) {
        const rr = rings[ri].r
        const p = project(view, [rr * 0.707, 0, -rr * 0.707])
        if (!p.visible) continue
        ctx.fillStyle = theme ? theme.faint : COLOR.faint
        ctx.globalAlpha = 0.7
        ctx.fillText(rr + ' m', p.sx + 4, p.sy)
      }
      ctx.globalAlpha = 1
      // 中心点 = 听者（hub 球盖在上面）
      const c = project(view, [0, 0, 0])
      if (c.visible) {
        ctx.beginPath()
        ctx.arc(c.sx, c.sy, 1.6, 0, Math.PI * 2)
        ctx.fillStyle = COLOR.groundDim
        ctx.fill()
      }
    }

    function drawLinks (items) {
      let hubItem = null
      for (let i = 0; i < items.length; i++) if (items[i].rec.isHub) { hubItem = items[i]; break }
      if (!hubItem || !hubItem.visible) return
      for (let i = 0; i < items.length; i++) {
        const it = items[i]
        if (it.rec.isHub || it.rec.ghost || !it.visible) continue
        const rec = it.rec
        const selected = rec.id === selectedId
        const boost = rec.flow && typeof rec.level === 'number' ? levelPct(rec.level) / 26 : 0
        const width = selected ? Math.max(3, 2 + boost) : (rec.flow ? 2 + boost : 2)
        ctx.beginPath()
        ctx.moveTo(hubItem.sx, hubItem.sy)
        ctx.lineTo(it.sx, it.sy)
        ctx.strokeStyle = selected ? (theme ? theme.accentHi : COLOR.accentHi)
          : (rec.flow ? COLOR.flow : COLOR.line)
        ctx.lineWidth = Math.round(width * 10) / 10
        ctx.globalAlpha = selected ? 1 : (rec.flow ? 0.95 : 0.7)
        ctx.stroke()
        ctx.globalAlpha = 1
      }
    }

    function drawNode (it, showLabels) {
      const rec = it.rec
      if (!it.visible) return
      const selected = rec.id === selectedId
      const hovered = rec.id === hoverId
      const rPx = it.rPx
      const alpha = rec.ghost ? 0.9 : (!rec.online ? 0.55 : (it.depth > 3.4 ? 0.78 : 1))

      ctx.save()
      ctx.globalAlpha = alpha

      // 汇聚端光晕（沿用旧版两层半透明圆：r+15 / r+7）
      if (rec.isHub && !rec.ghost) {
        const accent = theme ? theme.accent : COLOR.accent
        ctx.beginPath()
        ctx.arc(it.sx, it.sy, rPx * (1 + 15 / 46), 0, Math.PI * 2)
        ctx.fillStyle = accent
        ctx.globalAlpha = alpha * 0.10
        ctx.fill()
        ctx.beginPath()
        ctx.arc(it.sx, it.sy, rPx * (1 + 7 / 46), 0, Math.PI * 2)
        ctx.globalAlpha = alpha * 0.14
        ctx.fill()
        ctx.globalAlpha = alpha
      }

      // 球体
      const spr = getSprite(rec, rPx, selected, Math.max(canvasSize.dpr, 1))
      if (spr.img) {
        const s = spr.size
        ctx.drawImage(spr.img, it.sx - s / 2, it.sy - s / 2, s, s)
      } else {
        ctx.beginPath()
        ctx.arc(it.sx, it.sy, rPx, 0, Math.PI * 2)
        ctx.fillStyle = ballColor(rec)
        ctx.fill()
      }

      // 电平弧（球外一圈，二级编码；旧版是一行文字，这里两者都保留）
      if (typeof rec.level === 'number' && !rec.ghost) {
        const pct = levelPct(rec.level) / 100
        ctx.beginPath()
        ctx.arc(it.sx, it.sy, rPx + 5, -Math.PI / 2, -Math.PI / 2 + pct * Math.PI * 2)
        ctx.strokeStyle = theme ? theme.accentHi : COLOR.accentHi
        ctx.globalAlpha = alpha * 0.85
        ctx.lineWidth = 2
        ctx.stroke()
        ctx.globalAlpha = alpha
      }

      // 球内文字：本机 / 短名 / ?
      const insideFont = clamp(rPx * 0.34, 11, 15)
      ctx.font = '600 ' + insideFont + 'px "Bahnschrift", "Segoe UI", sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillStyle = theme ? theme.text : COLOR.text
      const inside = rec.ghost ? '?' : (rec.isSelf ? '本机' : shortName(rec.name, rec.isHub ? 6 : 4))
      ctx.fillText(inside, it.sx, it.sy + 0.5)

      // 角标：本机 · 汇聚端 · 离线（旧版在球正上方，这里贴球右上；靠近右边界时翻到左侧）
      const badge = badgeOf(rec)
      if (badge) {
        ctx.font = '10.5px "Bahnschrift", "Segoe UI", sans-serif'
        try { ctx.letterSpacing = '1px' } catch (e) { /* 老内核没有 letterSpacing */ }
        const w = ctx.measureText(badge).width
        let bx = it.sx + rPx + 6
        if (bx + w > VW - 4) bx = it.sx - rPx - 6 - w
        if (bx < 4) bx = 4
        const by = clamp(it.sy - rPx + 2, 10, VH - 6)
        ctx.textAlign = 'left'
        ctx.fillStyle = theme ? theme.accentHi : COLOR.accentHi
        ctx.fillText(badge, bx, by)
        try { ctx.letterSpacing = '0px' } catch (e) { /* noop */ }
      }

      // 名称 / 电平：按节点在 hub 屏幕中心的上/下半区放球上或球下（与旧版信息位一致）
      const isAbove = it.sy < CY
      const showName = showLabels || selected || hovered
      const nameFont = rec.isHub ? 13 : 12.5
      ctx.textAlign = 'center'
      if (rec.isHub) {
        ctx.font = '600 ' + nameFont + 'px "Bahnschrift", "Segoe UI", sans-serif'
        ctx.fillStyle = theme ? theme.dim : COLOR.dim
        const hubName = rec.ghost ? '等待图谱…' : rec.name
        ctx.fillText(clampText(hubName, 22), it.sx, it.sy + rPx + 18)
        if (typeof rec.level === 'number') {
          ctx.font = '11.5px "Bahnschrift", "Segoe UI", sans-serif'
          ctx.fillStyle = theme ? theme.faint : COLOR.faint
          ctx.fillText(dbText(rec.level), it.sx, it.sy + rPx + 33)
        }
      } else if (showName) {
        const nameY = isAbove ? it.sy - rPx - 9 : it.sy + rPx + 18
        const lvlY = isAbove ? nameY - 14 : nameY + 14
        ctx.font = nameFont + 'px "Bahnschrift", "Segoe UI", sans-serif'
        ctx.fillStyle = theme ? theme.dim : COLOR.dim
        ctx.fillText(clampText(rec.name, 18), it.sx, clamp(nameY, 10, VH - 6))
        // 距离直接写在球下：球大小 + 数字双编码，别让用户猜远近
        const info = (typeof rec.level === 'number' ? dbText(rec.level) + ' · ' : '') + it.dist.toFixed(2) + ' m'
        ctx.font = '11.5px "Bahnschrift", "Segoe UI", sans-serif'
        ctx.fillStyle = theme ? theme.faint : COLOR.faint
        ctx.fillText(info, it.sx, clamp(lvlY, 10, VH - 6))
      }

      // 选中态：球外再套一圈（旧版是加粗描边，canvas 里 sprite 已处理，这里补一圈光）
      if (selected) {
        ctx.beginPath()
        ctx.arc(it.sx, it.sy, rPx + 3, 0, Math.PI * 2)
        ctx.strokeStyle = theme ? theme.text : COLOR.text
        ctx.globalAlpha = alpha * 0.45
        ctx.lineWidth = 1.5
        ctx.stroke()
        ctx.globalAlpha = alpha
      }

      ctx.restore()
    }

    function clampText (text, max) {
      const chars = Array.from(String(text == null ? '' : text))
      if (chars.length <= max) return chars.join('')
      return chars.slice(0, max).join('') + '…'
    }

    function dbText (v) {
      if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
      if (v <= -119) return '静音'
      return v.toFixed(1) + ' dBFS'
    }

    function draw (now) {
      const r = rect()
      if (!r.width || !r.height) return
      const size = ensureSize()
      ctx.setTransform(1, 0, 0, 1, 0, 0)
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      ctx.setTransform(size.s, 0, 0, size.s, size.ox, size.oy)
      if (!theme) readTheme()

      const items = projectAll()
      lastItems = items
      drawGround(size)
      drawLinks(items)
      const others = nodes.length - 1
      const showLabels = others <= CFG.labelLOD || nodes.length < 2
      for (let i = 0; i < items.length; i++) drawNode(items[i], showLabels)

      // FPS（R4 自检用；低端机关 GPU 时看它是否掉到 30 以下）
      if (!lastFpsAt) { lastFpsAt = now; lastFpsFrames = 0 }
      lastFpsFrames++
      if (now - lastFpsAt >= 500) {
        fps = Math.round(lastFpsFrames * 1000 / (now - lastFpsAt))
        lastFpsAt = now
        lastFpsFrames = 0
      }
    }

    // ---------------------------------------------------------- 动画循环（脏标记驱动）

    function tick (now) {
      rafId = root.requestAnimationFrame ? root.requestAnimationFrame(tick) : 0
      if (anim.active) {
        const k = anim.dur > 0 ? Math.min(1, (now - anim.t0) / anim.dur) : 1
        const e = 1 - Math.pow(1 - k, 3)       // ease-out-cubic
        for (const entry of anim.from) {
          const id = entry[0]
          const from = entry[1]
          const to = targetPos.get(id)
          if (!to) continue
          worldPos.set(id, [
            from[0] + (to[0] - from[0]) * e,
            from[1] + (to[1] - from[1]) * e,
            from[2] + (to[2] - from[2]) * e
          ])
        }
        dirty = true
        if (k >= 1) { anim.active = false; anim.from = new Map() }
      }
      if (!active || !dirty) return
      dirty = false
      draw(now)
    }

    // ---------------------------------------------------------- 交互：拾取 / 拖动 / 轨道相机

    function pickAt (clientX, clientY) {
      const p = toLogical(clientX, clientY)
      const items = projectAll()
      return hitTest(items, p.sx, p.sy, 6)
    }

    function onDown (ev) {
      if (ev.button !== 0) return
      const hit = pickAt(ev.clientX, ev.clientY)
      const rec = hit ? hit.rec : null
      drag = {
        mode: rec && !rec.isHub && !rec.ghost ? 'node' : 'orbit',
        id: rec ? rec.id : null,
        rec: rec,
        x0: ev.clientX,
        y0: ev.clientY,
        lx: ev.clientX,
        ly: ev.clientY,
        moved: false,
        startPos: rec && !rec.isHub ? (worldPos.get(rec.id) || [0, 0, 0]).slice() : null
      }
      try { canvas.setPointerCapture && ev.pointerId != null && canvas.setPointerCapture(ev.pointerId) } catch (e) { /* noop */ }
      ev.preventDefault()
      if (root.document && root.document.body) root.document.body.style.userSelect = 'none'
    }

    function onMove (ev) {
      if (!drag) {
        // hover：只改光标与 hover 高亮，不重建任何东西
        const hit = pickAt(ev.clientX, ev.clientY)
        const id = hit ? hit.rec.id : null
        if (id !== hoverId) {
          hoverId = id
          dirty = true
        }
        canvas.style.cursor = id ? 'pointer' : 'grab'
        return
      }
      const dx = ev.clientX - drag.lx
      const dy = ev.clientY - drag.ly
      drag.lx = ev.clientX
      drag.ly = ev.clientY
      const total = Math.abs(ev.clientX - drag.x0) + Math.abs(ev.clientY - drag.y0)
      if (!drag.moved && total > 4) drag.moved = true

      if (drag.mode === 'orbit') {
        if (!drag.moved) return
        const phi = view.phiDeg - dx * 0.35
        const pitch = clamp(view.pitchDeg + dy * 0.25, CFG.pitchMin, CFG.pitchMax)
        view = camera(phi, view.dist, pitch)
        dirty = true
        return
      }

      // 拖节点：屏幕点 → y=0 平面 → 半径夹紧（拖到听者左/右侧即改变声像）
      if (!drag.moved) return
      const p = toLogical(ev.clientX, ev.clientY)
      const world = unproject(view, p.sx, p.sy, 0)
      if (!world) return
      const maxR = CFG.dragRMax    // 房间边界固定：相机拉近时也不该缩成半米活动范围
      let x = world[0]
      let z = world[2]
      const rr = Math.hypot(x, z)
      if (rr < 1e-6) { x = 0; z = -CFG.dragRMin } else if (rr > maxR) { x *= maxR / rr; z *= maxR / rr } else if (rr < CFG.dragRMin) { x *= CFG.dragRMin / rr; z *= CFG.dragRMin / rr }
      const pos = [x, 0, z]
      worldPos.set(drag.id, pos)
      targetPos.set(drag.id, pos)
      const rec = byId.get(drag.id)
      if (rec) rec.pinned = true
      dirty = true
    }

    function onUp (ev) {
      if (root.document && root.document.body) root.document.body.style.userSelect = ''
      if (!drag) return
      const d = drag
      drag = null
      if (d.mode === 'node' && d.id) {
        if (d.moved) {
          // 落点写回内核图谱（POS 帧 → 其他电脑 / 手机）。本机先定住位置，
          // 等 200ms 轮询把内核回声带回来，避免先弹回槽位再跳过去。
          const pos = worldPos.get(d.id) || [0, 0, 0]
          const to = [r3(pos[0]), 0, r3(pos[2])]
          worldPos.set(d.id, to)
          targetPos.set(d.id, to)
          const rec = byId.get(d.id)
          if (rec) rec.pinned = true
          dirty = true
          onDragEnd(d.id, to)
        }
        // 左键不选中：没拖动就是空操作。切换汇聚端 / 音量 / 复位都走右键菜单（见 onMenu）。
        return
      }
      // orbit：没拖动 = 空操作；只有真的转过视角才需要落盘相机。
      if (d.moved) saveStore()
    }

    function onWheel (ev) {
      ev.preventDefault()
      // deltaMode：0=像素、1=行（≈33px）、2=页（≈一屏）。归一化后再换算，触控板/行滚轮手感一致。
      const unit = ev.deltaMode === 1 ? 33 : (ev.deltaMode === 2 ? VH : 1)
      const delta = (Number.isFinite(ev.deltaY) ? ev.deltaY : 0) * unit
      // 方向：滚轮向上（deltaY<0）= 凑近放大，向下 = 拉远看全景。
      const dist = clamp(view.dist * Math.exp(delta * 0.0012), CFG.distMin, CFG.distMax)
      view = camera(view.phiDeg, dist, view.pitchDeg)
      saveStore()
      dirty = true
    }

    function onContextMenu (ev) {
      ev.preventDefault()
      if (drag) { drag = null; if (root.document && root.document.body) root.document.body.style.userSelect = '' }
      const hit = pickAt(ev.clientX, ev.clientY)
      // 右键 = 节点菜单；点空白只吞掉浏览器默认菜单（不弹自己的）。
      onMenu(hit ? hit.rec.id : null, ev.clientX, ev.clientY)
    }

    function onKeyDown (ev) {
      if (!drag) return
      if (ev.key === 'Escape' && drag.mode === 'node' && drag.id && drag.startPos) {
        worldPos.set(drag.id, drag.startPos.slice())
        targetPos.set(drag.id, drag.startPos.slice())
        const rec = byId.get(drag.id)
        if (rec) rec.pinned = rec.posSet === true
        drag.moved = false
        dirty = true
      }
    }

    function setActive (on) {
      active = !!on
      if (active) dirty = true
    }

    // ---------------------------------------------------------- 生命周期

    // 容器/窗口尺寸变化：逻辑视口恒定，重算等比缩放即可（Flex 布局下容器常变，用 RO 更及时）
    function onResize () {
      ensureSize()
      dirty = true
    }

    canvas.addEventListener('mousedown', onDown)
    canvas.addEventListener('contextmenu', onContextMenu)
    canvas.addEventListener('wheel', onWheel, { passive: false })
    root.addEventListener('mousemove', onMove)
    root.addEventListener('mouseup', onUp)
    root.addEventListener('keydown', onKeyDown)
    root.addEventListener('resize', onResize)
    let ro = null
    if (root.ResizeObserver) {
      try {
        ro = new root.ResizeObserver(onResize)
        ro.observe(canvas)
      } catch (e) { ro = null }
    }
    try { readTheme() } catch (e) { /* noop */ }
    if (root.requestAnimationFrame) rafId = root.requestAnimationFrame(tick)

    return {
      canvas: canvas,
      setGraph: setGraph,
      setDynamic: setDynamic,
      setActive: setActive,
      spatialFrame: spatialFrame,
      debug: debug,
      listNodes: function () { return nodes.slice() },
      pickClient: pickAt,
      reset: function () {
        nodes = []
        byId = new Map()
        worldPos = new Map()
        targetPos = new Map()
        selectedId = null
        hoverId = null
        dirty = true
      },
      dispose: function () {
        if (rafId && root.cancelAnimationFrame) root.cancelAnimationFrame(rafId)
        rafId = 0
        canvas.removeEventListener('mousedown', onDown)
        canvas.removeEventListener('contextmenu', onContextMenu)
        canvas.removeEventListener('wheel', onWheel)
        root.removeEventListener('mousemove', onMove)
        root.removeEventListener('mouseup', onUp)
        root.removeEventListener('keydown', onKeyDown)
        root.removeEventListener('resize', onResize)
        if (ro) { try { ro.disconnect() } catch (e) { /* noop */ } ro = null }
      },
      _view: function () { return view }
    }
  }

  const API = {
    CFG: CFG,
    COLOR: COLOR,
    camera: camera,
    project: project,
    unproject: unproject,
    hitTest: hitTest,
    SLOTS: SLOTS,
    SLOT_COUNT: SLOT_COUNT,
    slotAt: slotAt,
    roomLayout: roomLayout,
    listenerOf: listenerOf,
    azOf: azOf,
    create: create
  }

  if (typeof module !== 'undefined' && module.exports) module.exports = API
  root.Scene3D = API
})(typeof globalThis !== 'undefined' ? globalThis : this)