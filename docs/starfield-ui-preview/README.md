# AudioHub 星空主题界面预览（待确认稿）

> 状态：**仅预览，未改动任何正式代码**。`ui/`、`android/`、协议与服务层全部保持原样，
> 仓库里只新增了 `docs/starfield-ui-preview/` 这一个目录。
> 目的：在动手改两端实现之前，先把「星空 + 恒星/行星图谱」的视觉方案画出来给产品确认。

## 1. 看什么

| 端 | 页面 | 预览图 |
| --- | --- | --- |
| Windows PC | 连接界面 | [pc-connect.png](pc-connect.png) |
| Windows PC | 控制界面（星图） | [pc-control.png](pc-control.png) |
| Android | 连接界面 | [android-connect.png](android-connect.png) |
| Android | 控制界面（星图） | [android-control.png](android-control.png) |

截图均为 2x（PC 2560×1600，手机 1120×2080）。也可以直接用 Chrome 打开同名 `.html`：

```bash
chrome docs/starfield-ui-preview/pc-control.html
```

预览页共用的视觉层是 [shared.css](shared.css)（设计令牌 + 组件样式）和
[starfield.js](starfield.js)（星野背景 + 恒星/行星/光束绘制，纯 Canvas 2D、零依赖）。

## 2. 设计总纲：深空观星台

- **背景**：不是一块画布，而是整页深空——深空渐变 + 星云 + 银河带 + 三层星点 + 亮星十字星芒 + 流星。
  图谱画布内再叠一层局部星尘与中心金色星云，让「恒星」像星系核心。
- **面板**：半透明玻璃 HUD，数字用等宽数字（`tabular-nums`），保证仪表感的可读性。
- **配色**：星云青 `#6fe0ff`、星芒金 `#ffd27a`、星紫 `#b7a4ff`、星野底 `#04060f`；
  状态色沿用原语义：绿=同步、琥珀=提醒、红=断开/危险。
- **信息密度**：不新增业务，只把原本就有的信息（dBFS、距离、同步版本、码率、欠载…）重新排布。

## 3. 图谱（重点）：恒星 + 静止行星

| 语义 | 视觉 | 说明 |
| --- | --- | --- |
| 汇聚端 | **恒星**：暖金核心 + 日冕 + 四向星芒 + 日珥 + 色球层 | 恒在中心；外圈淡弧是汇聚总线电平 |
| 其他设备 | **行星**：朝恒星受光的球体 + 大气辉光 + 表面条纹 + 背光面 | 位置=空间摆位，距离=音量（越远越小、越轻） |
| 音频流 | 恒星↔行星的渐变光束，出声时更亮、光带上有星尘 | 离线=灰暗虚线 |
| 离线设备 | 灰蓝暗星 + 虚线外圈（个别设备带行星环） | 角标「离线」 |
| 本机 | 金色角标「本机」，汇聚端额外标「汇聚端」 | 沿用原角标语义 |
| 距离参考 | 1 m / 2 m 轨道环 + 前/右/后/左方位字 | 与原空间音频方位约定一致 |

**关于「不需要转动」**：行星不做公转、不做自转动画，星体相对位置始终由图谱协议里的坐标决定。
但以下**原有交互全部保留**，因为它们承载的是功能而不是装饰：

- PC：左键拖动行星摆位（落点同步全网，距离即音量）、空白拖动旋转观察视角、滚轮缩放、右键星球菜单；
- Android：只读俯视图，点选行星展开音量面板、双击行星切换汇聚端。

也就是说：**星体不动，视角可以动**。这样既满足"不要转"，也不丢"所有功能保留"。

两端语义一致，差别只在投影：PC 是 3D 透视（可摆位），Android 是俯视正交（只读、省电）。

## 4. 功能保留对照

### PC · 连接界面

| 原功能 | 预览中的位置 |
| --- | --- |
| 本机地址 + 复制 | 右上地址胶囊 +「复制」 |
| 待确认请求 同意/拒绝 | 「待确认接入」琥珀警示条 |
| 扫描 / 重新扫描 | 「发现的设备」行右侧「重新扫描」 |
| 扫描结果提示 | 「扫描完成，发现 2 台设备」 |
| 发现的设备卡片 + 邀请 | 双列 peer 卡（名字/角色/IP/「邀请它」） |
| 手动按 IP 邀请 | 「按 IP 直接邀请」输入框 +「邀请」 |
| 顶部状态提示 | 顶部 banner（邀请中/已接入/失败） |
| 底部遥测 | 底部 stats（设备/已确认/待确认/总线/码率/坏包/欠载/阶段） |

### PC · 控制界面

| 原功能 | 预览中的位置 |
| --- | --- |
| 星图 | 全屏星图（恒星+行星+光束+环+方位） |
| 摆位/距离即音量 | 行星位置与大小、dBFS 文字；拖动逻辑不变 |
| 视角旋转/滚轮缩放 | 保留在原画布交互（预览图是静态帧） |
| 右键节点菜单 | 书房手机旁的菜单：切换汇聚端到此设备 / 位置复位 |
| 恒星右键菜单 | 原有第三项「所有设备位置复位」仍挂在汇聚端上（截图未展开） |
| 汇聚端信息 + 自动同意 | 底部 hub 条：客厅PC · 本机 ·「自动同意新设备」勾选 |
| 同步状态 | 星图下方「已同步（v7）…」 |
| 新设备添加 | 底部「＋ 添加设备」 |
| 全部断开 | 右上「全部断开」 |
| 底部遥测 | 设备/图谱版本/总线/码率/坏包/渲染欠载/流欠载/迟到溢出 |
| 图例与操作提示 | 新增图内 legend + 底部操作提示（仅可读性，不改功能） |

### Android · 连接界面

| 原功能 | 预览中的位置 |
| --- | --- |
| 标题 / 副标 / 版本标记 | 顶部 AudioHub +「星图 · v1.7-bye」 |
| 待确认摘要 | 「1 个连接请求待处理 ›」 |
| 连接引导提示 | 「先建立连接，再在控制界面里指定谁当汇聚端」 |
| 本机声音送入/停止 | 「本机声音」卡 +「送入」 |
| 扫描 / 手动添加 / 设备列表 | 设备卡右侧三个入口 |
| 扫描结果与设备摘要 | 「已发现 2 台设备」+ 设备名列表提示 |
| 背景星空 | 整屏星野 + 底部星图说明 |

### Android · 控制界面

| 原功能 | 预览中的位置 |
| --- | --- |
| 只读星图 | 顶部星图区（恒星+行星+光束） |
| 点选行星 → 音量面板 | 「卧室平板」音量面板（dBFS/100%/滑杆/静音） |
| 双击/菜单切换汇聚端 | 提示「点星球可切换汇聚端」，确认对话框逻辑不变 |
| 同步状态 | 图内左下「✦ 已同步（v7）」 |
| 新设备 + 添加设备 | 「新设备」卡 +「添加设备」 |
| 汇聚端信息卡 | 「客厅PC · 图谱 v7 · 3 台设备 · 已接收 2 路 · 已发送 1 路」 |
| 断开所有连接 | 底部按钮 |
| 待确认请求摘要 | 保留在原位置，有请求时才出现（本截图无） |

## 5. 落地实现映射（确认后才动）

### PC（Electron / 原生 Canvas）

| 文件 | 改动 |
| --- | --- |
| `ui/styles.css` | 用本预览的令牌与玻璃组件替换现有深蓝主题；结构选择器与布局尺寸保持不变 |
| `ui/starfield.js`（新增） | 从预览 `starfield.js` 抽出星野背景绘制，供控制屏画布与整页背景复用 |
| `ui/scene3d.js` | **投影/相机/命中/槽位/拖拽/右键逻辑全部保留**，只替换材质：地面环→星环与方位、连线→光束、球形节点→恒星/行星 |
| `ui/index.html` | 增加星图 legend/操作提示两个覆盖层、连接页底部星座说明；不删任何功能节点 |
| `ui/renderer.js` | 只补绘制字段与 HUD 文案；POS/空间音频上报、图谱结构签名逻辑不动 |
| `ui/desktop.js` / 主进程 | 不改（窗口尺寸/无边框等维持现状） |

### Android（原生 Java）

| 文件 | 改动 |
| --- | --- |
| `HubGraphView.java` | 保留映射/命中/选中/双击；新增 `drawSky/drawStar/drawPlanet/drawBeam`，替换球体与背景绘制 |
| `MainActivity.java` | 颜色常量换成星空令牌；卡片/按钮圆角与描边；标题「俯视平面图」→「星图」；SeekBar 用 tint 上色 |
| `Scene3D.java`/`PlacementStore`/`GraphStore`/服务层 | **不改**（协议、摆放同步、BYE 全部不动） |

### 验收

- PC：`npm test`（现有 86 例）全过；手动回归：扫描/邀请/同意/拒绝/切汇聚端/位置复位/拖拽距离即音量/视角旋转/滚轮/离线角标/全部断开。
- Android：`gradlew assembleDebug` 通过；真机回归：扫描/手动添加/设备列表/本机送入/点选音量/双击切 hub/断开/退出 BYE。
- 两端星图信息必须对齐：同样的设备数、同样的汇聚端、同样的电平与距离。

## 6. 需要你确认的点

1. **恒星配色**：当前是暖金色（与蓝紫行星区分度最高）。要不要改成青白冷星？
   （建议保留金色，金色同时表示"电源/总线在中心"）
2. **行星环**：当前只给个别设备（示例里的离线备用手机）加环做区分。所有行星都加环，还是保持"少而特殊"？
   （建议保持少而特殊，否则星图噪声大）
3. **视角交互**：保留 PC 的"空白拖动旋转视角 / 滚轮缩放"吗？星体本身不转，但相机可转。
   （建议保留——这是原有功能）
4. **连接页装饰**：PC/Android 连接页底部的星座 + 一句星图说明，保留还是去掉？
   （建议保留，让空状态也有星空叙事）
5. **落地顺序**：先改 PC（可立刻截图对比）再改 Android，还是两端一起？
   （建议先 PC 定稿视觉，再按同一套令牌移植 Android）

确认后我会按第 5 节的映射直接改正式代码，并保持功能清单不变。

## 7. 重新生成截图

```bash
CHROME="/c/Program Files/Google/Chrome/Application/chrome.exe"
BASE="file:///D:/Users/25516/Desktop/%E8%B7%A8%E7%AB%AF%E9%9F%B3%E9%A2%91%E7%B3%BB%E7%BB%9F/docs/starfield-ui-preview"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
  --window-size=1280,800 --virtual-time-budget=5000 \
  --screenshot=docs/starfield-ui-preview/pc-control.png "$BASE/pc-control.html"
# 手机页用 --window-size=560,1040
```

## 8. 文件清单

```
docs/starfield-ui-preview/
├── shared.css            设计令牌 + 两端组件样式（预览用）
├── starfield.js          星野背景 + PC 3D 星图 + Android 俯视星图绘制
├── pc-connect.html/.png
├── pc-control.html/.png
├── android-connect.html/.png
├── android-control.html/.png
├── pc-control-implemented.png   落地后 PC 控制界面实拍（生产文件渲染）
├── pc-connect-implemented.png   落地后 PC 连接界面实拍
├── pc-control-720.png           最小窗口 720×560 下的控制界面
├── android-graph-v2.png         第二轮 Android 星图 v2 预览（412×915）
├── app-icon.png                 双端统一图标对照（16~/256 与自适应前景）
└── README.md             本文件
```

## 9. 落地状态（2026-10-27 已实现，两端同时）

预览稿按第 6 节建议全部采纳（恒星暖金 / 行星环只给离线与特殊设备 / 保留相机旋转与滚轮 /
保留连接页星座装饰 / 两端同时落地），并已改正式代码：

- **PC**：`ui/styles.css` 重写为星空令牌与玻璃 HUD；新增 `ui/starfield.js`（整页星野 +
  恒星/行星/光束绘制）；`ui/scene3d.js` 只换材质，投影/相机/命中/拖拽/右键菜单/空间音频
  上报全部沿用；`ui/index.html` 加 `#sky` 背景层、图例与操作提示、连接页星座装饰；
  `ui/renderer.js` 只加星野初始化与 resize 重绘；`ui/package.json` 打包清单加 starfield.js。
- **Android**：`HubGraphView` 换星空绘制，新增 `StarSkyView` 星空背景；`MainActivity`
  换色与圆角、标题/文案改为星图语义；`Scene3D` 数学、`PlacementStore`/`GraphStore`、
  协议与服务层不变。
- **「不需要转动」的实现口径**：行星不做公转/自转动画；但 PC 的相机轨道旋转、滚轮缩放、
  拖拽摆位（距离即音量）与 Android 的点选展开音量、双击切换汇聚端全部保留 —— 这些是既有
  功能，不属于"行星自转"。
- **验证**：`node tools/ui-e2e.js` 全部通过（含拖拽摆位落点写回、相机旋转不改声场、
  右键菜单切汇聚端/位置复位、全部断开回到连接界面等 30 项检查）；四个页面 Chrome
  headless 无 JS 报错；1280×800 / 1080×760 / 720×560 三种窗口截图确认不溢出。

第 7 节的复现命令仍适用于预览页；落地版本的实拍见 `pc-*-implemented.png`。

## 10. 第二轮修改（2026-10-27 深夜，用户 4 条反馈）

用户反馈：① 断开后总是弹回控制界面（两端）；② 桌面端要能无级缩放；③ 手机端星图难看；
④ 重做图标，双端统一，且桌面图标有显示问题。处理如下：

- **① 断开后回弹**（根因：远端残留图谱 1~10s 内仍有 2 个节点，被"图谱节点≥2 就进控制台"
  的规则当成已连接）：
  - PC：`ui/renderer.js` 增加 `leftLock/leftAt`。点「全部断开」立刻上锁，只有出现"新会话"
    证据才解锁——连续 0.8s 存在的 confirmed 收流来源，或本机重新供声（pcSource）超过 1.5s。
    刻意**不**把"图谱变空"当作解锁条件，否则远端图谱写回旧节点时又会弹回。
  - Android：`MainActivity.refresh()` 删掉 `if (userLeft && !hubRunning && !srcRunning) userLeft = false;`；
    `userLeft` 只在用户明确发起新连接时清除（点「送入」/ 接受待确认 / 授权采集 / 设备列表或手动 IP）。
  - 验证：用假 `window.hub` 注入状态序列（连接 → 断开 → 远端图谱写回 v7 两节点 → v8 只剩本机 →
    新 confirmed 来源）跑 headless 断言，结果为 `control → connect → connect → connect → control`，
    即断开后不再回弹、重新连上仍会自动进控制台。
- **② 桌面端无级缩放**：`ui/scene3d.js` 滚轮改为"目标距离 + 每帧缓动"（约 250ms 视觉收敛），
  滚动范围从 1.2~6.0m 放宽到 0.85~12.0m，单格步长 12%→11%；`view.dist` 仍是渲染真值，
  命中/投影/调试读数保持一致，e2e 的滚轮断言（3.40 ↔ 3.88m）继续通过。
- **③ 手机端星图 v2**（`HubGraphView.java` 重写，API 面未变）：星芒收敛为 4 长（2.75r/2.65r）
  + 4 短（1.55r）的锥形细针，三层日冕 + 轻日珥 + 白热内核；行星基径 23dp（15~28dp 按距离），
  强化受光渐变、大气辉光、受光边缘、离线环与虚线外圈；光束改 1.35~1.9dp 渐变芯线 + 外发光 +
  流动星尘；星图区中心径向暗化 + 四角暗角；环/方位字更淡更细，距离标注移到右上，文字加
  halo 与视口夹取，同步状态改胶囊 chip。预览见 `android-graph-v2.png`。
- **④ 双端统一图标**：新增 `tools/gen-icon.py`（Pillow，单一母题：深空圆角方 + 暖金恒星 +
  青/紫两颗行星 + 稀疏星点），一次生成 PC 与 Android 全部尺寸：
  `ui/icon.png`(512)、`ui/icon.ico`(16/20/24/32/40/48/64/128/256，逐档独立渲染再手写 ICO，
  小尺寸不再糊)、Android `mipmap-*/ic_launcher(.round).png`、自适应前景 432px、
  `values/ic_launcher_background.xml`(#070B1B)。`ui/main.js` 的 BrowserWindow 补 `icon:`，
  解决开发态/任务栏退回 Electron 默认图标的问题。对照图见 `app-icon.png`。
- **第二轮的验证**：`node --check` 全部通过；`node tools/ui-e2e.js` 再次 ALL CHECKS PASSED；
  PC 便携版/安装包重建（含新图标与新逻辑，`win-unpacked/AudioHub.exe` 内 icon.ico 已替换，
  抽出的 32px 图标可辨认）；Android `build-apk.sh` 重建成功，
  `aapt2` 显示应用图标走 `mipmap-anydpi-v26/ic_launcher.xml`（自适应图标），APK 已重新签名。
- **仍未验证**：本机无 adb 设备/模拟器，Android 端只到"构建成功 + 星图预览图 + 逻辑推演"；
  Windows 可能缓存旧图标，若桌面/任务栏仍显示旧图标，注销或清理图标缓存后即可看到新图标。


## 11. 第三轮修复（2026-10-28，Android 闪退 + 主题图标）

用户反馈：① 手机断开后桌面重新邀请、把电脑设为汇聚端时手机闪退；② 手机图标变成灰底白星。

- **① 闪退（Android 14 采集授权是一次性令牌）**：旧逻辑在采集目标变化时先 `stopCapture()`
  再用保存的 MediaProjection 授权重建采集，系统在 `startForeground(type=mediaProjection)`
  处校验授权已失效 → 抛 `SecurityException`，进程直接崩。修复：
  `CaptureService` 目标变化改为 **retarget**（换 socket + 目标地址，复用当前
  `AudioRecord`/`MediaProjection`，并先给旧汇聚端补发 `CONN-BYE`）；`startForeground` 加
  try/catch 兜底；`MainActivity` 的授权只用于当次启动，启动/停止/失败后一律
  `clearProjectionAuth()`，失败原因通过 `CaptureService.consumeError()` 在 400ms 轮询里
  提示用户重新授权。详见 `android/README.md` 的「采集授权与 Android 14」。
- **② 灰底白星图标**：系统「主题图标」会把自适应图标自动去色重绘；未提供 `<monochrome>`
  时就会得到一张不受控的灰底白色剪影。新增 `res/drawable/ic_launcher_monochrome.xml`
  （设计过的单色图层：中央四角星 + 两颗行星 + 星点）与 `mipmap-anydpi-v33/` 下的自适应图标
  声明（`<background>/<foreground>/<monochrome>`）。对照见 `icon-themed.png`。
- **顺带修掉安装坑**：`build-apk.sh` 的签名 keystore 原来在 `build/` 里，而脚本每次
  `rm -rf build` → 每次构建换一把 key，已装版本无法覆盖安装。现在固定在
  `android/keystore/audiohub-debug.keystore`（已 gitignore），签名稳定；APK 版本号升到
  `0.2 / versionCode 2`。
- **验证**：`build-apk.sh` 构建成功（374,504 B）；`aapt2 dump xmltree` 确认 v33 自适应图标
  含 `monochrome` 节点；连续两次构建 `apksigner` 证书 SHA-256 一致；
  `run-spatial-check.ps1`、`run-graphfit-check.ps1` 全通过。本机仍无 adb 设备，未真机手测。

## 12. 第四轮修复（2026-10-28 下午，Android 会话状态）

用户反馈两点：断开后再次邀请手机没弹窗；清后台重开进入「没有汇聚端的双星界面」，
此时把任一端设成汇聚端也不传声。

- **① 邀请弹窗只弹一次**：`MainActivity` 的 `inviteDialogShowing` 在首次弹窗时置 true，
  却没有任何地方复位——第一次邀请之后的所有邀请都被 `!inviteDialogShowing` 挡掉。
  修复：对话框加 `setOnDismissListener`，关闭时复位，之后每次邀请都会询问。
- **② 重开进「无 hub 星图」**：`userLeft`（已断开/在连接页）只是内存字段，进程被杀后
  归零；`InviteListener` 收到的远端残留 `GRAPH`（≥2 节点）把 `showControl` 判成 true，
  直接显示控制屏，但实际没有会话、`CaptureService` 也没在跑，所以设汇聚端不传声。
  修复：
  - `userLeft` 落盘到 SharedPreferences `audiohub` 的 `user_left`（默认 true），断开后
    清后台重开仍在连接页；只有明确发起新连接（送入 / 同意邀请 / 设备列表或手动 IP /
    把本机设为汇聚端）才置 false；
  - 控制页条件加「汇聚端已知」（`hubId` 非空），只有半张图时留在连接页；
  - 已断开时 `reconcile()` 直接停服务并返回，不会被残留图谱重新拉起；
  - 版本标记改为 `v0.3 · 星图`（`BUILD_TAG=0.3`，APK `0.3 / versionCode 3`），
    便于一眼确认装的是哪一版。
- **图标仍是灰底白星**：v0.3 已带 `<monochrome>`；若装后仍灰，先看顶部版本行是否为
  `v0.3 · 星图`（不是则装的还是旧包，v0.1 临时签名无法覆盖安装，必须先卸载），版本正确
  仍灰则是桌面「主题图标 / 图标样式」开关所致，需在系统桌面设置里关闭。详见
  `android/README.md` 的「图标（v0.2 / v0.3）」。
- **验证**：`build-apk.sh` 构建成功（374,504 B，versionCode 3 / versionName 0.3）；
  dex 内含 `user_left`、`v0.3 · 星图` 等新字符串；`apksigner` 证书与 v0.2 相同
  （SHA-256 `5dbe36c1…`）；`run-spatial-check.ps1` / `run-graphfit-check.ps1` 全通过。
  仍无 adb 设备，未真机手测。

## 13. 第五轮修复（2026-10-28 傍晚，重连卡死 / 衰减 / v0.4 兜底）

用户 m01576 + m01592 + m01581 四条反馈：同意邀请后进控制屏瞬间退出、桌面端星图仍留着手机、
只有两端同时退出才能重连、距离远了声音下降不明显、邀请能不能不带端口。

**根因在 PC 汇聚端（`src/hub_core.cpp`）的会话残留**：

- CONN-REQ 重试分支看到 `accepted_ids` 里有这个流号就只重发 ACK、不检查 `streams` 是否还在；
- 流掉线（`MixerLoop`）、收到 `CONN-BYE`、「全部断开」（`ClearSession`）都不清 `accepted_ids`。

手机重连固定用流 1，于是命中「我同意过了」的分支：ACK 到了、混音流没建——手机进了控制屏却
一包音频都不混，PC 星图里还留着那台手机；只有重启 PC 才恢复。这同时解释了「设任一端为汇聚端
都不传声」和「必须两端一起退出才能重连」。

修法（4 处）：重试分支发现 `streams` 缺失就 `accepted_ids.erase` 并落到正常建流路径（带
`[~] 清理旧会话残留` 日志）；掉线清理、`CONN-BYE` 各加一次 `accepted_ids.erase`；`ClearSession`
补 `streams / accepted_ids / pending` 三个 clear。

其余三条：

- **距离衰减**：`DistanceGain` 从 `min(1/d, 2)` 改为 `min((1/d)^1.6, 2)`
  （`include/audiohub/spatial.hpp` 新增 `kDistanceExponent`），C++ 单测、`Spatial.java`、
  `android/verify/SpatialCheck.java` 同步；2 m ≈ -9.6 dB、4 m ≈ -19.3 dB。
- **Android 兜底**：`projection.registerCallback`、`record.startRecording()`、采集线程主循环
  三处 `try/catch(Throwable)`，异常进 `sLastError` 由界面 toast，不再带走进程。
- **邀请免端口**：两端输入提示改为「只填 IP 就行，端口默认 47000」，解析逻辑本来就支持。

**验证**：`bash build.sh`（MSYS2 g++ 16.2）10960 条断言全过、86 个用例 0 失败，并同步
`ui/bin/audiohub-hub.exe`；APK v0.4（`versionCode 4 / versionName 0.4`，374,504 B）构建成功，
dex 含「0.4 · 星图」「注册采集回调失败」「采集线程异常退出」「端口可省」；`run-spatial-check`
PASS 23182 项、`run-graphfit-check` PASS 249 项。PC 重新打包
`ui/dist/AudioHub-便携版.exe`（79,170,369 B）与 `ui/dist/AudioHub Setup 0.1.0.exe`
（79,463,539 B）。仍无 adb 设备，未真机手测；图标若仍灰请先确认版本行是 `v0.4 · 星图`。

## 14. 第六轮修复（2026-10-28 夜，Android 崩溃兜底 + v0.5）

v0.4 之后仍收到「同意邀请、进控制屏那一瞬间 App 退出」的报告。代码里没有任何
`finish()` / `System.exit` / `moveTaskToBack`，所以是**未捕获异常把进程带走**——
`onStartCommand`、`onDraw`、`refresh()` 都跑在主线程，漏一个异常就等于闪退。

v0.5 在三处加总保险（原实现各改名一层，行为不变）：

| 位置 | 兜底 | 出问题时的表现 |
|---|---|---|
| `CaptureService.onStartCommand` | `try/catch(Throwable)` → `handleStartCommand` | Toast「采集启动失败（XxxException），请重新点「送入」」，进程不退出 |
| `HubGraphView.onDraw` | `try/catch(Throwable)` → `drawScene` | 最多这一帧不画，下一帧继续 |
| `MainActivity.refresh` | `try/catch(Throwable)` → `refreshInner` | 这一轮状态没刷新，400 ms 后自动重试 |

加上已有的 `projection.registerCallback`、`record.startRecording()`、采集线程主循环三处，
现在任何一处的异常都只会「没声音 + 一条提示」。真闪退了也没关系：`crash.txt` 会在下次
启动时弹窗，点「复制并清除」即可把堆栈发回来。

**图标仍是灰底白星**：APK 里的图标资源已实测是彩色的（从 `mipmap-xxxhdpi-v4/ic_launcher.png`
解出来目视正常），所以灰的是系统「主题图标」用 `<monochrome>` 层重绘的结果。排查顺序：
① 看界面顶部版本行是不是 `v0.5 · 星图`（不是则装的是旧包，需先卸载再装）；
② 版本对但仍灰 → 桌面设置里关「主题图标 / 图标样式」，或换一下布局/重启桌面清缓存。
应用内无法关闭这个系统行为。对照见 `icon-themed.png`。

**产物**：`android/build/audiohub-source.apk`（374,504 B，`versionCode 5 / versionName 0.5`，
compileSdk 35，签名 keystore 与 v0.2 起一致可覆盖升级）；PC
`ui/dist/AudioHub-便携版.exe`（79,170,369 B）与 `ui/dist/AudioHub Setup 0.1.0.exe`
（79,463,539 B）沿用 16:20 那次构建，已确认内含新 `audiohub-hub.exe`
（SHA-256 前缀 `a11a4be8d839ceba`）与「只填 IP 就行」文案。

**验证**：`run-spatial-check.ps1` PASS 23182 项、`run-graphfit-check.ps1` PASS 249 项；
`aapt2 dump badging` 版本号正确，APK 内 16 项 mipmap 资源齐全。本机仍无 adb 设备，
未真机手测——这一轮的兜底逻辑只在构建层面上验证过。

## 15. 第七轮修复（2026-10-28 深夜，模拟器实测：图标真因 + 汇聚端被清空 + v0.7）

这一轮的起点是用户提出的「能不能在电脑上装安卓模拟器来模拟手机测试」。模拟器装好之后，
前面几轮靠静态检查判断不了的问题全部在模拟器上复现并被定位：

**① 图标「只剩一团白光晕」的真因（§14 的结论被修正）**

§14 当时判断为「系统主题图标用 `<monochrome>` 重绘」。模拟器上一层层探针做完发现不是：
把前景 PNG 换成纯红/四象限色块，图标就完整变色；换回真实前景又只剩中央光晕；删掉
`mipmap-anydpi-v26` 只留 legacy 位图，图标立刻正常。真正原因是**密度桶**——
`mipmap-anydpi-v26/ic_launcher_foreground.png` 是 432×432（108dp × 4），但 `anydpi` 无限定
密度，系统按 mdpi 解释成 432dp 塞进 108dp 图层，只露中心 `108/432 = 25%`，恰好是恒星光晕。
修法：`tools/gen-icon.py` 按密度各出一份前景（108/162/216/324/432，`safe=0.62`），
`mipmap-anydpi-v26` 里那份 432 PNG 删除。模拟器实测抽屉与应用详情页大图标均恢复正常，
截图归档 `android-icon-fixed.png`（图标放大）与 `android-launcher-fixed.png`（抽屉实拍）。

**② 「断开后重连进不了控制屏 / PC 星图还留着手机 / 必须两端同时重启」**

根因在**汇聚端（hubId）的 LWW 规则**，不是采集授权：

- 手机点「断开所有连接」→ `GraphStore.resetSession()` 把 `version` 抬到
  `max(version, seenVersion) + 1` 并清空 `hubId`；
- 之后汇聚端发来的 GRAPH 版本更低 → `mergeFromFrame` 整帧丢弃 → **hubId 永远是空**；
- `hubNode()` 为 null → `reconcile()` 按「汇聚端未知」停掉刚启动的采集（日志里
  「已静音媒体音量 → 已发送 CONN-BYE → 已恢复媒体音量」三步就是它）→ 退回连接页，
  看起来像「进控制屏一瞬间就退出」；
- 反过来，手机以高版本广播「hubId 为空」，PC 的 `MergeGraph` 照单全收 → PC 的汇聚端也被
  清空（`[GRAPH] 合并 v2 … hub=(空)`），于是「谁当汇聚端都不出声」。

修法（C++ 与 Java 逐条对齐）：

| 位置 | 改法 |
|---|---|
| `GraphStore.resetSession()` | `version / seenVersion / origin / hubId` 归零，只留本机节点 |
| `GraphStore.mergeFromFrame()` + `src/graph.cpp MergeGraph()` | ① 空 hubId 不是真实赋值，`remoteHasHub \|\| hubId.isEmpty()` 才写，绝不用空值覆盖已知汇聚端；② 本机 hubId 为空时直接采纳任何带 hubId 的帧（不受版本门槛限制），version 仍只涨不降 |
| `MainActivity.reconcile()` | 汇聚端一时找不到不再立刻停采集：6 s 宽限期（`HUB_MISSING_GRACE_MS`）+ `rememberedHub()` 用记住的 `hubIp/hubPort` 兜底续跑 |

`tests/test_graph.cpp` 新增 `Graph_MergeKeepsLocalHubWhenRemoteHubEmpty` 与
`Graph_MergeAdoptsRemoteHubWhenLocalHubEmpty` 两个回归用例；`bash build.sh` 断言
**10970 条、失败 0、用例 0/88 失败**。

**③ 模拟器实测（Android 14 x86_64，WHPX，窗口模式）**

一条完整链路跑通并留了截图（`D:/tmp/ahemu/shots/`）：

| 步骤 | 结果 |
|---|---|
| 启动 → 控制屏 | 暖金恒星 + 3 颗行星 + 1.0/2.4 m 环 + 方位字 + 「视野 2.6 m」，星空 UI 与真机一致 |
| 断开所有连接 | 进程存活，正确回连接屏（`v0.7 · 星图`） |
| PC `/api/invite` → 手机 | 弹「连接请求 / AudioHub（10.0.2.2）希望本机把声音送过去」 |
| 同意 → 授予采集 | 进控制屏，`/api/state` 里 `sources[0].packets` 持续增长（3626 → 后续 2808+），hub 卡显示「发送 N 包」 |
| 再次断开 → 再邀请 → 再同意 | 第二次同样弹窗正常、同样进控制屏并持续发送（修复前这一步必定卡住） |

**模拟器踩坑（供下次复用）**：`-no-window`（headless）在本机 WHPX 下会段错误
（`Get-WinEvent` 里 `qemu-system-x86_64-headless.exe` / `0xc0000005`），改窗口模式
（`-gpu angle_indirect`、`-WindowStyle Minimized`）后稳定；每次重启模拟器都要重做
`adb root`、`wm size 1080x2400`、`wm density 420`、`pm grant … RECORD_AUDIO`、
`adb emu redir add udp:47001:47001` / `udp:47002:47002`、`install -r`（否则报 Activity 不存在）。

**模拟器截屏归档**：`android-v07-control.png`（控制屏：恒星+行星+「发送 N 包」）、
`android-v07-invite.png`（PC 邀请弹窗）。

**产物**：`android/build/audiohub-source.apk` 448,599 B（`versionCode 7 / versionName 0.7`，
compileSdk 35，签名与 v0.2 起同一 keystore，可覆盖升级）；PC 侧本轮只改
`src/graph.cpp` + `ui/bin/audiohub-hub.exe`（`build.sh` 自动同步），未重打 exe。

**验证**：`bash build.sh` 断言 10970 条 / 0 失败 / 88 用例全过；
`run-spatial-check.ps1` PASS 23182 项、`run-graphfit-check.ps1` PASS 249 项；
模拟器走通连接-邀请-采集-断开-重连全链路。真机（厂商 ROM 省电策略、真实音频路由）仍未复验。

## 17. 第八轮修复：手机当汇聚端闪退（v0.8）

v0.7 交付后用户反馈「手机断开、桌面重新邀请、在还没有汇聚端的情况下把电脑设为汇聚端 → 手机闪退」，
附了完整堆栈。查下来是**两个独立崩溃**串在一条路径上。

### ① 采集授权被复用（`CaptureService` / `SecurityException`）

堆栈落在 `CaptureService.startForegroundNotification(CaptureService.java:601)`，
`java.lang.SecurityException: Starting FGS with type mediaProjection ...`。
不是缺 manifest 权限（`FOREGROUND_SERVICE_MEDIA_PROJECTION` 一直在），而是**拿作废的授权去起前台服务**：
旧代码在「汇聚端变了」时先 `stopCapture()` 再用存下的 `resultData` 重启，而 Android 14 起
`MediaProjection` 授权是一次性令牌，服务一停就作废。

改法：切换汇聚端时**只换发送目标**（新 socket + 新 IP/端口/通道号），复用正在跑的
`AudioRecord` / `MediaProjection`；`workerLoop` 每包按当前目标重建 `DatagramPacket`；
授权「用后即焚」（启动成功 / 断开 / 停止 / 失败都清）；`startForeground` 整段兜 `Throwable`。

### ② 前台服务义务没人履行（`ReceiverService`）

`setLocalHub()` 起一次 `ReceiverService`，紧接着 `refresh() → reconcile()` 又起一次；
第二次命中幂等早退，而早退发生在 `startForeground()` 之前 → 系统 10 s 后
抛 `ForegroundServiceDidNotStartInTimeException` 杀进程。把 `startForeground()` 提到所有早退
分支之前（`ACTION_STOP` 除外）后不再崩。

### 验证与边界

- 定向验证（模拟器）：连续两次 `am start-foreground-service ... ReceiverService -a ...START`
  → 进程存活（`pidof` 有值）、`logcat -b crash` 空、通知栏有 `id=43 / audiohub_receiver / AudioHub 正在接收`。
- **完整「手机当音源持续发流」这条没能在模拟器上复验**：本机 qemu 一启动音频采集就
  段错误退出（`qemu-system-x86_64.exe / 0xc0000005`，试了 6 次、换过 GPU 后端与 `-no-audio`），
  App 侧没有 `FATAL EXCEPTION` 也没有 `crash.txt`。真机仍需复验。
- `bash build.sh` 断言 10970 / 0 失败 / 88 用例；`run-spatial-check.ps1` PASS 23182 项；
  `run-graphfit-check.ps1` PASS 249 项。

---

## 18. 第九轮修复（2026-10-29 晚，用户反馈：手机当汇聚端收不到声音 / 不填端口发不出邀请）

用户报两条：

1. **切换到手机为汇聚端时，桌面端的声音没传过来**，手机显示「已接收 0 路」；
2. **电脑不填手机端口就无法发送邀请**，或发送后没反应。

### ① 「0 路」的真因：UDP `connect()` 把回包过滤掉了

问题不在手机上，在电脑的音源进程 `apps/source_main.cpp` 的握手：

```cpp
hs.SetPeer(opt.hub_ip, opt.hub_port + 2);   // 内部 ::connect()
hs.SendToPeer(req...);
hs.ReceiveFrom(...);                        // ← 永远超时
```

UDP 的 `connect()` 不是"建立连接"，只是让内核**按对端 ip:port 过滤入包**。手机回
`CONN-ACK` 用的是绑定在「音频口 + 2」上的**同一个**套接字，经过 NAT / AP 中继 / 多网卡时
源端口会被改写，回包的源地址于是和电脑记录的 `ip:port` 对不上，内核**静默丢弃** →
`recvfrom` 一直超时 → 打印「对方未在 10 秒内确认连接」然后退出。发送侧一切正常
（包确实都发出去了），所以界面上的症状只有「已接收 0 路」。

同一条链路上的一对对照实验（Python）：

| 套接字 | 结果 |
| --- | --- |
| 未 `connect`，`sendto` + `recvfrom` | 1 秒内收到 `CONN-ACK\|Android SDK built for x86_64\|1` |
| `connect(('127.0.0.1',47002))` + `send` | 6 次全部超时 |

另外单独证明接收链路本身没问题：直接往手机 `47000` 灌 300 个合规音频包
（16 字节头 `(1<<4)|0` + 小端 stream_id/seq/sample_pos/capture_ts_ms + 240 帧小端
int16 立体声），手机界面立刻从「已接收 0 路」变成「**已接收 1 路**」。

修法：握手改成 `SendToAddr` + `ReceiveFrom`，用回包**内容**（`CONN-ACK` 前缀）判断成败，
不再绑定来源地址。

配套修的一处隐藏坑：`build.sh` 以前只同步 `ui/bin/audiohub-hub.exe`，**没有同步
`audiohub-source.exe`**。所以改完 `apps/source_main.cpp` 之后桌面端仍在 spawn 几周前的
旧副本 —— 修了等于没修。现在两个都同步。

### ② 「不填端口发不出邀请」的真因

`/api/invite` 要求 `audioPort` 必须是 JSON **数字**，缺了就返回
`{"ok":false,"error":"ip/audioPort missing"}`；`ui/main.js` 的 IPC 守卫
`!Number.isInteger(p.audioPort)` 同样会直接 `{ok:false}`。可界面上写的就是「端口可省」。

三处一起放宽：`apps/hub_main.cpp`（缺省/字符串/越界一律回落 47000）、`ui/main.js`
（`Number.parseInt` 后兜底）、`ui/renderer.js`（失败时把内核给的原因显示出来，
不再一律说"地址格式不对"）。邀请等待时间也从 6 s 放宽到 20 s —— 对方要在手机上
看到弹窗再点「同意」，6 秒经常不够，用户先看到"无响应"两秒后手机才连上。

### ③ 汇聚端接受新音源（`InviteListener`）

放行条件原本是「图谱成员 / `ROLE|hub` 60 秒窗口」，两者都依赖"来源 IP 已在图谱里"。
可这件事本身不稳：DHCP 换地址、过 NAT、对方刚重连还没同步，`hasNodeIp(fromIp)` 都会是
false → 请求被丢进待确认列表 → 汇聚端一个音源都进不来。现在多一条：**本机是汇聚端时
无条件 ACK**（与 PC 侧 hub 默认 `auto_accept = true` 对齐）。

### ④ 握手日志

`InviteListener` 原来**一行日志都没有**，这是本次排查最大的盲点。现在关键分支各打一条
（只在握手/邀请时触发）：

```
adb logcat -s AudioHubInvite
D AudioHubInvite: CONN-REQ 来自 10.0.2.2:63168 名字=PC-SRC stream=7 成员=false 自动同意=false 本机是汇聚端=true
D AudioHubInvite: 已回 CONN-ACK 给 10.0.2.2:63168
```

### ⑤ 另一处开发态才暴露的问题：`sourceExePath()`

`ui/main.js` 用 `process.resourcesPath` 判断"是不是打包态"，但这个值在 `electron .` 开发运行时
**也存在**（指向 `node_modules/electron/dist/resources`），于是开发态永远解析到
`app.asar.unpacked/bin/...` 那个只有打包后才有的路径，spawn 直接 ENOENT：

```
[graph] 音源进程启动失败: spawn ...\app.asar.unpacked\bin\audiohub-source.exe ENOENT
```

改用 `app.isPackaged` 判断，开发态走仓库里的 `ui/bin/`，打包态走 asarUnpack。

### 验证

- PC：`node tools/ui-e2e.js` → **ALL CHECKS PASSED**（约 45 项）。顺带修了脚本的端口占用：
  固定 9333 会被开发机上的 Edge 占住，现在支持 `AUDIOHUB_E2E_PORT=19337` 覆盖。
- C++：`bash build.sh` → 断言 10970 / 失败 0 / 88 用例。
- Android：`run-spatial-check.ps1` PASS 23182 项、`run-graphfit-check.ps1` PASS 249 项。
- 模拟器实测（打包后的 `ui/dist/win-unpacked/AudioHub.exe` + v0.8 APK）：
  1. 只填 IP 发邀请 → `{"ok":true}`，手机弹出「连接请求」；
  2. 点「同意」→ 进控制屏；
  3. 把汇聚端切到手机 → 电脑自动 spawn 音源进程，日志 `对方已确认连接`、
     `发送 201 pkt/s | 约 1569 kbps`；
  4. 手机界面 → **「已接收 1 路」**（修复前恒为「已接收 0 路」）。
- 未提交 git。

---

## 19. 第十轮修复（v0.10）：退出会话后不再抢图谱 / 不再被拉回控制台

用户反馈三条，实测确认是同一个根因：**「全部断开」之后本机仍在参与图谱共识**。

- 「当场上设备数等于 2 时，一方断开就清空图谱」
- 「桌面端退出时会反复回到控制台，但只显示他自己的汇聚端」
- 「反复切换时，还是会出现汇源端没声音」

机制：`ClearSession()` → `GraphResetLocked()` 把本机图谱重置成只剩自己
（协议 §4.6 只要求"不广播"），但对端还在每 3 秒周期广播自己的旧图谱，本机一合并
就把刚清掉的节点全捞回来；捞回来后本机 version 更高、`hub=self`，又发回对端 →
对端 LWW 把汇聚端认成一台北经离开的机器，于是谁也不发流。

### 改动

| 端 | 文件 | 改动 |
| --- | --- | --- |
| C++ | `src/hub_core.cpp` | 新增 `graph_detached`；`GraphResetLocked()` 置 true；`GraphBroadcastLocked()` / `HandleGraphFrame()` 首行拦截；`GraphReattachLocked()` 在 `GraphAddPeerLocked()` / `SwitchGraphHub()` 里解除 |
| PC UI | `ui/renderer.js` | `networkUp` 从 `nodes.length >= 2` 改为「在线其他设备 ≥ 1 且汇聚端在线」（`onlinePeers` / `hubAlive`） |
| PC UI | `ui/main.js` | 汇聚端在切换瞬间不存在时给 `HUB_UNKNOWN_GRACE_MS = 4000` 宽限期再停音源；新目标需稳定 `PC_SOURCE_TARGET_STABLE_MS = 900` 才真正切换（避免 streamId 排号抖动把音源进程反复杀掉） |
| Java | `GraphStore.java` | 新增 `detached` / `isDetached()` / `reattach()`；`mergeFromFrame()` 首行拦截；`resetSession()` 置 true |
| Java | `InviteListener.java` | `sendBroadcastNow()` 在退网状态下直接返回 |
| Java | `MainActivity.java` | 四处显式入网动作调 `GraphStore.reattach()`；`showControl` 改用在线其他设备数 |

### 验证

- 独立 hub + Python 冒充对端：`POST /api/clear-session` 后连发 6 帧旧图谱，
  `version / nodes / hubId` 三项完全不变（修复前会被灌回到 2 个节点）。
- 模拟器实测：手机连入 → 点「断开所有连接」→ hub `/api/state` 图谱前后一致；
  手机界面正确回到连接屏且不再被弹回。
- `AUDIOHUB_E2E_PORT=19337 node tools/ui-e2e.js` → **ALL CHECKS PASSED**。
- `bash build.sh` → 断言 10970 / 失败 0 / 88 用例；
  `run-spatial-check.ps1` PASS 23182、`run-graphfit-check.ps1` PASS 249。
- 打包：PC `ui/dist/AudioHub-便携版.exe` / `AudioHub Setup 0.1.0.exe`（20:09），
  APK `android/build/audiohub-source.apk` 448,599 B；三处二进制
  （`build/`、`ui/bin/`、asarUnpack）SHA-256 一致。

## 20. 第十一轮（v0.11）：不断音切换 —— 重新设计音频流转 + 新增验收套件

用户反馈：「还是仅有在首次链接时有声音，重新设计一套流转方法，然后设计一套测试方法，
要求不管怎么切换，都要保证声音流通」。

### 20.1 三条根因（都不是混音，也不是星空 UI）

1. **音源进程把目标焊死在启动参数上**：旧 `apps/source_main.cpp` 用
   `data_socket.SetPeer(hub_ip, hub_port)`（= UDP `connect()`），换目标只能 kill 进程重建；
   而重建要走 10 秒阻塞握手 + 抖动缓冲重新蓄水 + 采集重新起步 → 每次切换都掉音。
2. **握手失败就自杀**：旧握手 15 次拿不到 `CONN-ACK` 直接 `return`（进程退出），
   Electron 3 秒后重 spawn，新目标常常还没准备好 → 反复撞墙，永远没声音。
3. **Android 切汇聚端时销毁了采集授权**：`reconcile()` 的 `g.isHub()` 分支调
   `stopSource()`，里面 `clearProjectionAuth()` 把 Android 14 一次性的投屏授权清空；
   再切回音源时 `hasProjectionAuth()` 为 false，`CaptureService` 永远不启动
   → 「只有第一次链接时有声音」。

### 20.2 新的流转设计

- **桌面端**：音源进程新增本地控制口 `--control-port`，支持
  `RETARGET ip:port streamId` / `STATUS` / `STOP`。切换汇聚端时 Electron 只发一条
  `RETARGET`（`ui/main.js` 的 `retargetPcSource`），**进程不重启**；握手改成非阻塞重试
  （每 2 秒补 `CONN-REQ`），进程永不因为对方没起来而退出；切换前先给旧目标发
  `CONN-BYE`，避免两头同时响。
- **手机端**：`CaptureService` 把「停发送」和「销毁投屏会话」拆开 —— 新增
  `ACTION_PAUSE` → `stopCaptureKeepProjection()`：保留 MediaProjection，并**保留 AudioRecord
  与采集线程**，只把 `sending` 开关置 false（`workerLoop` 凑满一包时看到它就不发包）。
  切回音源走的是 **retarget 分支**（换 socket/地址后补 `sending = true`），下一包立刻恢复发送，
  **全程不弹授权框、不重建 AudioRecord、不重启进程**。
- 两者结合：任意来回切换都满足「进程不重启 + 会话不销毁 + 采集链路不拆装」，即结构性保证不断音。

#### 为什么不能「暂停时停 record、恢复时新建」

最初就是那么写的，模拟器上必崩（三次迭代都没绕过去）：

```
Fatal signal 6 (SIGABRT) in tid (audiohub-captur)
Abort message: 'releaseBuffer: mUnreleased out of range,
  !(stepCount:240 <= mUnreleased:112 <= mFrameCount:3072), BufferSizeInFrames:3072'
  at android.media.AudioRecord.read(AudioRecord.java:1711)
```

只要还有线程阻塞在 `read()` 里，`stop()` / `release()` 都会踩到 AudioRecord 内部
「已借出未归还的缓冲」记账；`join(800)` 超时就是这个竞态窗口。试过 ① `workerLoop` 参数化、
② resume 前先 `join(800)`、③ 改成「先 `stop()` → 再 `join()` → 最后 `release()`」，
**全部仍 abort**。结论：一个会话只用一个 `AudioRecord`、一条采集线程，是唯一稳定的用法。

另外两个连带 bug（同轮修掉）：
- `reconcile()` 的 `isHub` 分支曾把 `sourceIntent = false`，导致切回音源后 `reconcile`
  根本不会起采集 —— `sourceIntent` 是「用户意愿」，不是「当前状态」。
- 暂停后 `workerLoop` 收尾会无条件 `stopSelf()`，`onDestroy` 把 projection 一并销毁；
  改用 `pausing` 标志守住。


### 20.3 验收套件 `tools/audioflow-test.js`

不依赖 UI、不依赖声卡（`--tone 440` 内置正弦）、可重复、退出码进 CI。
两个真实 hub + 一个音源，只读 `/api/state` 的 `busLevel` 判「有没有声音」
（**刻意不用 kbps** —— 包在涨但总线 -120 dBFS 正是用户说的"没声音"）。
用例 T1 首次出声 / T2 旧 hub 静音 / T3 反复切 N 轮每轮恢复 / T4 音源 pid 不变 /
T5 目标不可达不退出且恢复后出声 / T6 断开重连恢复。

实测 `--rounds 6`：**19/19 通过**，切换出声 230–490 ms，旧 hub 静音 7–143 ms。

### 20.4 本轮验证与产物

- **桌面端**：`node tools/audioflow-test.js --rounds 6` → 19/19 PASS。
- **手机端（模拟器实测）**：`/api/graph/switch` 在 PC 与手机之间来回切 4 轮，每轮切回 PC 后
  hub 的 `sources[].packets` 在 2 秒内增长 280 / 286 / 282 / 261 包（约 130–143 pkt/s），
  App **pid 5026 全程不变**（不再是三个原生 abort 版本里的崩溃）。反向也一样：把手机设为
  汇聚端后，PC 音源 `发送 201 pkt/s · 已确认`，手机 UI 从「已接收 0 路」变「已接收 1 路」。
- `AUDIOHUB_E2E_PORT=19337 node tools/ui-e2e.js` → ALL CHECKS PASSED（首跑有
  wheel/轨道 3 项偶发失败，重跑全绿；属既有 flaky，不是本轮回归）。
- `bash build.sh` → 断言 10970 / 失败 0 / 88 用例；`run-spatial-check.ps1` PASS 23182、
  `run-graphfit-check.ps1` PASS 249。
- 产物：PC `ui/dist/AudioHub-便携版.exe` 79,178,311 B、
  `AudioHub Setup 0.1.0.exe` 79,471,481 B（21:31）；APK
  `android/build/audiohub-source.apk` 452,695 B（versionCode 11 / versionName 0.11）。
  三处二进制 SHA-256 一致（hub `ecb84604f5b2f396`、source `d1d7763335777aa4`）。

## 21. 桌面端「插耳机仍从扬声器出声」（v0.11.1）

### 根因

`platform/win/wasapi_audio.cpp` 的 `WasapiRender::ThreadMain()` 只在 `Start()` 时
`GetDefaultAudioEndpoint(eRender, eConsole, &device)` 解析一次，随后
`device->Activate(kIID_IAudioClient, ...)`。**`IAudioClient` 一旦 `Initialize`
就与那台设备焊死**：不监听 `IMMNotificationClient`，也不会因为默认设备变了重开。

hub 是长驻进程，于是「启动时是扬声器」这件事会一直生效 —— 用户中途插耳机、
切蓝牙、在设置里把耳机设为默认播放设备，声音照旧从扬声器出来。

本机实测（`probe_render.exe`）：`eConsole`/`eMultimedia`/`eCommunications`
三个 role 当时都指向同一台 `扬声器 (Realtek(R) Audio)`，所以「取错 role」
在**这台机器上**不是原因；真因是长驻进程握着启动时的设备不放手。

### 修法（`platform/win/wasapi_audio.{cpp,hpp}`，+90/−8）

1. 新增 `GetDefaultRenderDevice(en, out)`：优先 `eMultimedia`（媒体播放），
   失败再退 `eConsole`。四处默认设备解析（系统回环采集、`SetDefaultRenderMute`、
   `ListRenderDevices`、渲染端）统一走它 —— 这样「静音」和「放音」永远指向同一台设备。
2. 新增 `DefaultRenderDeviceId()`：取当前默认播放设备的 id 字符串，可任意线程调用。
3. `WasapiRender::ThreadMain()` 拆成**外层重建循环 + 内层 `ThreadLoop()`**：
   - `ThreadLoop()` 就是原来的实现，但每约 1 秒比对一次 `DefaultRenderDeviceId()`，
     变了就 `device_switched_ = true; break;`（日志 `[render] 默认播放设备已切换，重建输出流`）；
   - `ThreadMain()` 循环调用 `ThreadLoop()`，`device_switched_` 为真就 sleep 120ms 再来一遍。
   - `cleanup()` 里删掉了 `running_.store(false)` —— 换设备重建时 `running_` 必须一直是 true，
     否则 `Start()` 那个 50ms 探测会误判"没跑起来"。
4. 启动与每次重建时打一行 `[render] 输出设备: <id>`，日志里能直接看出跟到了哪台。

### 踩坑（这个 bug 的正确写法）

**设备检查必须放在 `WaitForSingleObject` 之前、且不依赖它的返回值**：
渲染事件大约每 10ms 就来一次，`WaitForSingleObject(event, 200)` 几乎总是立刻返回
`WAIT_OBJECT_0`，把检查塞进「超时分支」等于永远不执行。第一版就是这么写的，
验证时发现日志里根本不出 `已切换`。

### 验证

- 临时加 `AUDIOHUB_FORCE_DEV_MISMATCH=1` 环境变量把 `bound_device_id_` 伪造掉，
  确认机制生效：日志连续输出 `默认播放设备已切换，重建输出流` + 重新绑定设备，
  **进程不退出**、音频服务不中断（验证后该临时开关已删除，`grep` 确认为 0）。
- `bash build.sh` → 断言 10970 / 失败 0 / 88 用例；`node tools/audioflow-test.js --rounds 4`
  → **15/15 PASS**（改的是渲染端，音频流连续性未受影响）。
- PC 重打包：`ui/dist/AudioHub-便携版.exe` 79,182,432 B、
  `AudioHub Setup 0.1.0.exe` 79,475,681 B（22:52）。
  三处 hub 二进制 SHA-256 一致（`c76697aa85665801`）。

### 还没验证的

真机上「插耳机后声音自动转到耳机」这一步**没有在真实硬件上实测过**：
本机探测工具无法切换系统默认设备（`IPolicyConfig` 的 `CoCreateInstance` 在本机
返回失败，三个已知 IID 都试过），所以只用伪造设备 id 验证了「检测到变化 → 重建输出流」
这条分支。真机验收请插拔耳机观察日志里的 `输出设备:` 行是否变化。

## 22. 第十二轮（v0.12）：切到手机当汇聚端收不到声音 + 邀请地址解析

用户三条反馈（2026-11-02）：

> 1、现在不加：47000的后缀，手机上接不到申请
> 2、切换汇源端后，电脑依旧没法传递声音到手机
> 3、但来回切换后，手机还是可以传递声音到电脑

### 22.1 问题 2 的根因：切汇聚端只改图谱，没通知那台设备

`GRAPH` 帧只告诉所有人"谁是 hub"，但**手机不会因为图谱变了就自己起来收流** ——
`ReceiverService` 是被 `ROLE|hub` 这条指令唤起的。而 `SwitchGraphHub()`
以前只 `GraphScheduleDoubleLocked()` 广播图谱，从不发 ROLE：

```
[GRAPH] 汇聚端切换 -> 127.0.0.1:47000（本机不是汇聚端，输出静音）
[graph] 启动音源进程 -> 10.0.2.15:47000（stream 2）
   ← 手机这边 ss -lunp 里 47000 根本没有监听，包全丢
```

修法：

- 把 ROLE 发送收敛成一个实现 `Impl::SendRoleTo()`；`HubCore::SendRoleCommand()`
  改为委托它（切换汇聚端发生在 `Impl` 的锁内流程里，只有 Impl 成员可用）。
- `SwitchGraphHub()` 在**解锁之后**按目标分两种：
  - 目标是对端 → `ROLE|hub`，让它起来收流；
  - 目标是本机 → 对所有其它节点发 `ROLE|source|ip:port`，让原音源改回送流。
- 发送必须放在锁外（`UdpSocket` 不要持锁调用）。

实测（模拟器 + 真实 App）：切到手机后 `ss -lunp` 出现 `UNCONN *:47000`，
从宿主跑 `audiohub-source.exe --hub 127.0.0.1:47000 --tone 440` →
`通道 7 · 已确认`、`发送 201 pkt/s`，手机 UI 变「已接收 1 路」。

### 22.2 问题 1：PC 侧两种写法本来就等价，坑在输入形式

隔离实测证明「只填 IP」与「IP:47000」产生**完全相同**的请求
（`[invite] 请求 ip=... raw_port=47000 -> audioPort=47000`），
而且只填 IP 的邀请在真实 App 上确实能弹到手机。真正会踩的是输入形式：
全角冒号 `：`、复制带进来的空格、尾随 `/`、`http://` 前缀。

修法：`ui/renderer.js` 新增 `parsePeerAddress()` 统一归一化，两处邀请入口都用它，
成功提示带上最终解析出的 `ip:port`，失败提示显示内核原因。

实测：6 种写法全部解析成 `{ip:"192.168.1.44",port:47000}`；真实 App 里只填 IP 点邀请
→ 界面「已向 127.0.0.1:47000 发出邀请」，手机 `收到 CONN-INVITE` 并弹「连接请求」。

### 22.3 回归与产物

- `node tools/audioflow-test.js --rounds 4` → **15/15 PASS**
- `AUDIOHUB_E2E_PORT=19337 node tools/ui-e2e.js` → **ALL CHECKS PASSED**
  （首跑因模拟器还挂着 `udp:47000/47002` 端口转发而失败，`adb emu redir del` 后重跑全绿 ——
  这条要记进测试前置条件）
- `bash build.sh` → 断言 10970 / 失败 0 / 88 用例
- 产物：`ui/dist/AudioHub-便携版.exe` 79,185,196 B、
  `AudioHub Setup 0.1.0.exe` 79,478,367 B；APK 452,695 B（versionCode 12 / versionName 0.12）

## 23. 第十三轮（v0.13）：手机播放有杂音 / 电流音

**现象**：电脑送到手机的声音有明显杂音、持续电流音。

**排除过程**（都做了，都不是原因）：AudioTrack 自身 `Underruns = 0`；采样格式
48000/2ch/16bit 与线格式一致；`mix` 归一化与软限幅公式与 C++ 端逐行对齐；
模拟器内 20 包 ping 0 丢包。

**真因**：抖动缓冲的漂移补偿在整帧硬切。

加了 500 ms 一次的临时诊断（`诊断 路数= 水位ms= 欠载帧= 总线dBFS=`）后一眼看出水位
稳定在 **147~192 ms**，而 `PREFILL_MS=60` + 死区 ±40 的目标区间是 [20,100] ms。
偏差长期超出死区 → 每 60 ms 冷却到期就 `step=241`，而 `step>frames` 的老实现是
"读 241 帧只输出前 240 帧" = **每 60 ms 硬切 1 帧**，一秒 16~17 次。
波形上的台阶就是听感里的咔哒/电流音。

**修法**（`android/.../JitterBuffer.java`）：分数游标 `pos_` + 线性插值取代整帧丢弃；
死区 40→**80 ms**；速率上限 ±0.5%；尾部兜底从"重复上一帧"改成补静音；
顺带修掉 `readPos` 双倍推进（尾部那次 `readPos += n` 与按 `pos_` 的推进重复）。

**量化结果**：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 缓冲水位 | 147~192 ms | 85~100 ms |
| 欠载帧 | 持续增长 | 锁定 2880（启动历史值）不涨 |
| AudioTrack underruns | 0 | 0 |
| 总线电平 | -15.7 dBFS | -15.7 dBFS（未改动音量） |

回归全绿：`audioflow-test` 15/15、`ui-e2e` ALL CHECKS PASSED、
`build.sh` 10970 断言 0 失败。APK v0.13（versionCode 13）。

**边界**：量化验证在模拟器上完成。真机的播放线程调度与音频 HAL 不同，建议真机再听一次。

---

## 24. 第十四轮（v0.14）：启动杂音 + 来回切换踢出控制台

用户报（2026-11-02）：「现在启动会有杂音」「没有连接直接就进控制台了」
「这次切换到手机为汇源端就彻底没声音了」「来回切换时还会出现手机退出控制台，
说桌面端没同意连接」。

这三条其实只有两个根因，都出在 **v0.11–v0.13 那几轮"不断音切换"改动自己身上**。

### 24.1 启动杂音：预填是假的

`JitterBuffer.push()` 首包把 `readPos` 往回拨 60 ms（"预填"），但那段区间里
`valid[]` 全是 false。`occupancyFrames()` = `highestWritten - readPos` 却把它
算成有效数据 → `ReceiverService.playLoop()` 的
`if (jb.occupancyFrames() < frames) continue;` 在只收到一个包时就放行 →
前 60 ms 全是 `underrunFrames++` 补出来的静音台阶。

这个账 v0.13 之前一直有，但被 `pull()` 尾部那句重复推进的 `readPos += n` 掩盖
（双倍推进，"每块丢半块音频"）。v0.13 删掉那句是对的，代价是把记账错误暴露了。

修法：
- `push()` 首包 `readPos = pos; pos_ = pos;`（不倒退），让 occupancy 数真数据；
- 新增 `JitterBuffer.prefilled()`：`highestWritten - readPos >= PREFILL_MS * 48L`，
  `playLoop` 改用它 —— **"预填多少"只有一个真源**，服务层不再拿一块 `frames` 去猜。

### 24.2 来回切换踢出控制台 / 切到手机彻底没声音

`MainActivity` 收到 `ROLE|source` 时原本调 `pickRemoteHub(d)`。那是"用户主动连
一台设备"的路径，做两件在这里有害的事：

1. 先 `ACTION_STOP` 停掉 `ReceiverService` —— 对端把汇聚端切回本机时要重新起服务，
   中间那一拍就是"彻底没声音"；
2. `respondToInvite()` 是 30 次 × 1 秒的 UDP 握手，任何一次 ACK 抖动都会走到
   `setUserLeft(true)` + 「对方还没有同意连接」+ 退回连接屏 —— 就是用户看到的
   「手机退出控制台」。

ROLE 是汇聚端那边的决定，不是用户操作，反复切换时对端会连发好几次。

修法：新增 `followRemoteRole()`，只记目标 + `setUserLeft(false)` +
`reattach()` + `sourceIntent = true`，**不动 `ReceiverService`**。
`pickRemoteHub` 只留给"用户手动连接 / 同意邀请"。

### 24.3 验证与产物

- `audioflow-test --rounds 4` → **15/15 PASS**
- `ui-e2e` → **ALL CHECKS PASSED**
- `build.sh` → 断言 10970 / 失败 0 / 88 用例
- APK `android/build/audiohub-source.apk` 452,695 B（versionCode 14 / versionName 0.14）

**边界**：模拟器在本轮多次 `qemu` 崩溃（`0xc0000005`，已知环境问题），
「启动杂音消失」与「启动落在连接屏」这两条需要在真机上再确认一次。

## 25. 第十五轮（v0.15）：切换后先有声带杂音、然后彻底没声

用户报（2026-11-02，v0.14 交付后 11 分钟）：「切换后，开始有点声音并伴随着杂音，
然后就彻底没声了」。

**这是第 24 节那个修复的二次效应**，不是新问题。记在这儿是因为它示范了一类
很容易踩的因果：修好 A 的时候，A 的旧行为正在掩盖 B。

### 25.1 根因

`JitterBuffer.push()` 有两条"正常丢包"规则：

```java
final long end = pos + frames;
if (end <= readPos) return false;                    // 已经全部过期
if (pos >= readPos + capacityFrames) return false;   // 超出容量窗口
```

切换汇聚端后对端可能换了一条音频时间轴（`apps/source_main.cpp` 的 `sample_pos`
在音源进程重启后从 0 重新开始）。本缓冲的 `readPos` 还停在上一条时间轴的大序号上，
于是每个包都命中这两条被静默丢弃 → `highestWritten` 永不前进 → `prefilled()`
永远 false → `playLoop` 一直 `continue` → **彻底没声**。

"前面有点声音并伴随杂音"是旧时间轴残留在 `valid[]` 里的数据被读指针按 `floorMod`
绕回来放了出去 —— 所以这个 bug 的听感是"先响一下（还是错的声音），然后死寂"，
而不是"直接没声"。

### 25.2 为什么 v0.14 之后才出现

以前 `ROLE|source` 走 `pickRemoteHub` → `ACTION_STOP` → `stopAll()` →
`streams.clear()`，**顺手把旧缓冲清了**。v0.14 为了让"对端切回来能立刻收流"
而不停接收服务，这层意外的保护就没了。

结论：v0.14 的修法仍然是对的，它只是把一个原本被掩盖的记账错误暴露出来。
**不能为了消除这个症状回退 v0.14** —— 回退会把"切换后彻底没声音"换回来。

### 25.3 修法

1. 首包分支同时初始化 `readPos / pos_ / highestWritten = pos`。
2. 两条丢包规则**之前**加断档检测，命中则 `reanchorLocked(pos)`：

   ```java
   if (end <= readPos) {
       if (readPos - end > capacityFrames / 2L) { reanchorLocked(pos); }
       else { return false; }   // 小幅乱序，照旧丢弃
   } else if (pos >= readPos + capacityFrames) {
       reanchorLocked(pos);
   }
   ```

   判据取半个容量窗口（容量 250 ms → 6000 帧）。正常推流 `readPos` 跟在
   `pos` 后面 60–140 ms，两种越界在正常流里不会出现 ⇒ 不误伤。
3. `reanchorLocked()` 里 **必须 `Arrays.fill(valid, false)`** —— 不清就会把上一段
   会话的样本当成本次的放出来（就是那声"杂音"）。

### 25.4 验收

新增 `android/verify/JitterCheck.java` + `run-jitter-check.ps1`（纯 Java 8，
不依赖 android.*），6 条断言全 PASS：

| 用例 | 断言 |
| --- | --- |
| T1 | 切换前正常收流 |
| T2 | 时间轴重置后仍能攒够预填 ← **本 bug 的直接回归** |
| T3 | 重锚后能取出有效音频（10/10 块非静音） |
| T4 | 正常推流 2000 包无误判（`rejected == 0`） |
| T5 | 小幅乱序仍被丢弃，不触发重锚 |
| T6 | 重锚后不残留旧时间轴数据 |

回归三件套：`audioflow-test --rounds 4` **15/15 PASS**、`ui-e2e` **ALL CHECKS
PASSED**、`build.sh` **断言 10970 / 失败 0 / 88 用例**。

产物：APK `android/build/audiohub-source.apk` 452,695 B（versionCode 15 /
versionName 0.15）、PC `ui/dist/AudioHub-便携版.exe` 79,183,929 B。

**仍未验证**：模拟器本轮反复 `qemu` 崩溃（`0xc0000005`），这条只能在真机上确认。
修法本身在单测层面已被 T2/T3/T6 钉住 —— 那三条正是"时间轴跳变 → 还能出声 →
且不残留旧数据"，与用户描述的现象一一对应。

## 26. 第十六轮（v0.16）：反复切换时崩溃 —— 前台服务 10 秒义务

用户贴出崩溃栈，没有文字说明：

```
android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
Context.startForegroundService() did not then call Service.startForeground():
ServiceRecord{f9ff5f8 u0 com.audiohub.source/.ReceiverService}
```

`startForegroundService()` 一发出系统就开始 10 秒倒计时，到点没 `startForeground()`
就杀进程。v0.7 修过同一个异常类，但只堵了「同一个 START 下发两次、第二次命中幂等
分支」那一条路径；这次走的是另一条。

**真凶是 `ACTION_STOP` 分支里的裸 `stopSelf()`。** 它最隐蔽的地方在于：
STOP 是 `startService()` 下发的、确实没有前台义务，在那里不调 `startForeground()`
看起来完全正确。但 `stopSelf()` 会丢弃所有尚未处理的 start 请求 —— 包括那条
已经被 `startForegroundService()` 发出、系统已经在倒计时的 START。请求被丢掉，
义务没人履行，10 秒后照样被杀。

反复切换汇聚端正是这个组合：`reconcile()` 的 `isHub` 分支刚发 START，
hub 角色一变又发 STOP，STOP 把 START 连人带义务一起抹掉。

修法（两个 Service 同款）：`onStartCommand` 首行记 `pendingStartId`，**无条件**先
履行义务并置 `boolean fg`，再进 `handleStartCommand(intent, startId, fg)`；
`intent == null` 只 return 不再 `stopSelf`；所有裸 `stopSelf()` → `stopSelfResult(startId)`
（有更新的请求排队时服务继续活着）。

顺带修 `stopAll()` 顺序：原来先 `join(400)` 再关 socket/`AudioTrack`，线程却正阻塞在
`receive()`/`track.write()` 上，最坏在主线程耗 800ms —— 而主线程正是履行那 10 秒义务
的地方。改成先掐断阻塞点再 `join(200)`。

### 这一轮顺带修掉的两个测试缺陷

这两个都不是产品问题，而是**测试自己会随机变红**，值得记下来：

1. **`「全部断开」清除会话并回到连接界面` 有采样竞态。** 原来 `waitDebug` 的谓词只有
   `s.screen === 'connect'`，一满足就返回快照 —— 但此刻图谱清空可能还没落地，
   `nodes` 仍是切换前的旧值，断言随机失败（实测 1/2 次）。改成谓词同时等
   `s.screen === 'connect' && s.nodes === 1`，并把实际值打进断言文案。
2. **缩放/环绕依赖 `requestAnimationFrame`，窗口被遮挡就全红。** 那两个断言靠
   「每帧朝目标追 28%」的缓动，rAF 一被 Chromium 暂停，`dist` 和 `phi` 就完全不动。
   实测同一条链路一次全过、一次三项全红，快照里 `fps: 0` 是唯一线索。
   新增 `rafAlive(tag)`：断言前轮询 `scene.fps`，为 0 就 `Page.bringToFront()`
   再推一次 `mouseMoved`（部分 Chromium 版本要收到输入事件才恢复合成器），
   最多重试 12 次。

两处都改成「先把前置条件等出来，再断言」，没有放宽任何阈值。

### 版本与回归

- `MainActivity.java` `BUILD_TAG = "0.16"`；`android/build-apk.sh`
  `VERSION_CODE=16` / `VERSION_NAME=0.16`。
- 产物：APK 452,695 B（versionCode 16 / versionName 0.16）；
  PC `ui/dist/AudioHub-便携版.exe` 79,184,162 B。
- 三处二进制 SHA-256 一致：hub `d98d97eda1111b5d`、source `0ad62638c88a193b`。
- 回归：`audioflow-test --rounds 4` **15/15 PASS**、`ui-e2e` **ALL CHECKS PASSED**、
  `build.sh` **断言 10970 / 失败 0 / 88 用例**。
- dex 里确认 `stopSelfResult` / `pendingStartId` 两个符号已进包。

**仍未验证**：只走到「起前台 + 不崩」，完整的反复切换压力仍需真机复验。
另外「没连接就进控制台」已被用户确认是**遗留进程**导致，不再是缺陷，
本条可以关闭。
