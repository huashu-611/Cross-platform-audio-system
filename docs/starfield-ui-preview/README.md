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

## 27. 第十七轮（v0.17）：老是意外断开 —— 图谱广播被 Activity 带走

**用户报**（m14732）：「现在出现了新的问题，老是出现意外断开的情况，可能是重复邀请等问题」

**先证伪用户的猜测**：`InvitePeer` 全库只在 `apps/hub_main.cpp` 的 `/api/invite` 被调用，
上游是 `ui/main.js`（两处 IPC）与 `ui/renderer.js` 的两个按钮点击；手机端
`MainActivity.invitePeer` 也是按钮触发，还要求 `isLocalHub() && ReceiverService.sRunning`。
没有任何周期性/自动重发逻辑。**「重复邀请」不成立。**

### 排查路径（顺序很重要）

1. **hub 侧写探针**：`tools/probe-bye-collision.js`、`probe-bye-ip-spoof.js`、
   `probe-bye-offline.js`、`probe-retarget-bye.js`、`probe-disconnect-storm.js`。
   逐个验证了「stream_id 撞车」「BYE 冒名误杀」「BYE 导致判离线」「retarget 发 BYE」四条路径。
   结论：**hub 侧全部排除** —— 掉的只是音频流，下一包立刻重建；节点在线状态完全不受
   BYE 影响。六轮切换压测里节点在线状态全程稳定。
2. 探针本身踩了四个坑（都记在这里，省得下次重踩）：
   - 假手机只收不发 → 永远进不了图谱（`nodes[].online` 需要 IP 已在图谱里）；
   - 单包 `FRAMES_PER_PACKET = 960`（20ms = 3856 B）**超过 hub 的 `buffer(2048)`**，
     Windows 下 `ReceiveFrom` 返回 WSAEMSGSIZE 被当 `n <= 0` 静默丢弃 → 必须用
     `240` 帧（5ms，976 B，对齐 `kFramesPerPacket`）；
   - `/api/state` 的节点在 **`s.graph.nodes`**，不是顶层 `s.nodes` —— 读错了会得到
     "永远在线"的假象；
   - `apps/source_main.cpp` 的控制口是 **UDP** 不是 TCP。
3. **转向手机端**：查到 `InviteListener`（全网 GRAPH 帧的唯一生产者）全库只有两处生命周期
   调用，都在 `MainActivity`：`:241` `onCreate` 启动、`:282` `onDestroy` 停止。
   而 `onDestroy` 里 `stopService(CaptureService)` 有 `isFinishing() && !isChangingConfigurations()`
   守卫，**`InviteListener.get().stop()` 与 `discovery.stop()` 是无条件的**。
4. `AndroidManifest.xml` 的 `MainActivity` **没有 `configChanges`** → 转屏/深色模式/
   字体大小都会重建 Activity → 每转一次屏，图谱广播断一次。
5. 实证：`adb shell ps -T -p <pid> | grep invite` 看线程 ID。v0.16 转屏前后 TID
   **2129 → 2157**（线程被停又重建），v0.17 **2229 → 2229**（线程未动）。

### 后果链（为什么用户看到的是"意外断开"）

    Activity 重建/回收 → 图谱广播线程停
    → 汇聚端 10 秒后（GraphStore.ONLINE_TIMEOUT_MS）graph_peer_last_seen_ms 过期
    → nodes[].online = false → renderer.checkOfflineDevices 弹「xxx 已离线」
    → 若这台是 hub 或唯一在线 peer，networkUp 变 false → 弹回连接屏

### 修法

1. manifest 给 `MainActivity` 加 `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode|density|fontScale"`。
2. `MainActivity.onDestroy()` 引入 `final boolean reallyLeaving = isFinishing() && !isChangingConfigurations();`，
   `InviteListener.get().stop()` 只在 `reallyLeaving` 时执行。
3. 顺带：`MainActivity.localIp()` 原来是自己写的"返回第一个非回环 IPv4"简化版，
   与 `Discovery.localIp()` 的完整过滤不一致。`reconcile()` 每 400ms 用它比对
   `GraphStore.selfIp()`，网卡枚举顺序一变就 selfId 漂移 → `streamIdForSelf()` 变
   → retarget → 先发 CONN-BYE → 又一次"莫名断一下"。现在直接委托 `Discovery.localIp()`。

### 版本与回归（v0.17）

- `MainActivity.java:159` `BUILD_TAG = "0.17"`、`android/build-apk.sh` `VERSION_CODE=17`/`VERSION_NAME=0.17`；
  APK 452,695 B，`aapt2` 确认 `versionCode='17' versionName='0.17'`。
- `audioflow-test --rounds 4` **15/15**、`ui-e2e` **ALL CHECKS PASSED**、
  `build.sh` **断言 10970 / 失败 0 / 88 用例**、`run-jitter-check` T1–T6 全过、
  `run-spatial-check` **23182 项**、`run-graphfit-check` **249 项**。
- v0.17 压测 3 轮（转屏 + HOME + 回前台），进程 pid 与线程 TID 全程不变。

### 留下的尾巴

`InviteListener` 仍然挂在 Activity 上。用户按返回键退出后广播仍会停（`isFinishing()` 为真），
语义上正确；但若希望"退到后台仍保持图谱在线"，需要把它挪进 `ReceiverService` 或一个
独立前台服务。这一轮没做，因为没有明确的用户诉求，而且挪动会牵动
`CaptureService`/`ReceiverService` 的启动顺序（v0.16 刚从这块的坑里出来）。


## 28. 第十八轮（v0.18）：邀请手机没反应 / 没连上就进控制台

用户报（m15674）：「桌面端邀请手机没反应，但进入控制台，退出再次邀请手机有反应，
手机同意后进入控制台但桌面端没进入控制台，手机端还显示桌面端没同意邀请」。

四个独立缺陷（详见 `android/README.md` v0.18 章节）：

1. **邀请帧地址是多网卡逗号列表**（`src/hub_core.cpp:1817` 改用 `FirstIp()`，
   手机端 `respondToInvite` 入口做归一化防御）——读码推断，本机单网卡未触发。
2. **邀请即占位入图 → 占位节点被 mDNS 证据判 online → PC 没连上就进控制台**
   （删 `InvitePeer` 里的 `GraphAddPeer`）。探针 `probe-fake-online.js` 实证：
   修复前图谱出现 online 占位节点，修复后为 0。
3. **60 秒放行窗口过期 → 手机 30 秒超时报「对方还没有同意连接」**
   （`AllowPeer` 改 `invited_peers` 集合，会话内不过期，`ClearSession` 清空）。
   探针 `probe-invite-window.js` 实证：61 秒后 CONN-REQ 仍获 ACK。
4. **`/api/invite` 失败无 error 字段**（现在返回带原因的错误信息）。

验证：`build.sh` 断言 10970 / 失败 0 / 88 用例；audioflow 15/15；ui-e2e ALL CHECKS
PASSED（第三次；前两次分别是 DSH electron 抢渲染时间片致 fps:0、残留 node 轮询进程
卡死——**跑 e2e 前必须把 audiohub* 和高 CPU node 一起清**）；spatial PASS 23182、
graphfit PASS 249。APK versionCode 18；PC 便携版 79,180,145 B。

## 29. 第十九轮（v0.19）：手机报「对方还没同意」却已经出声，随后退回连接界面

用户原话：「现在是手机上会显示对方还没同意，然后就自己退出了，但实际上桌面端的音频
已经传递到手机里了」，补充「手机退出到连接界面」——三件事同时成立看起来矛盾。

根因在 `MainActivity.pickRemoteHub()`：它是「同意邀请 / 设备列表 / 手动 IP」三个入口
的公共实现，却总是调用 `ReceiverService.respondToInvite()`——那是「连到对方**汇聚端**」
的握手（发 `CONN-REQ` 到 `audioPort + 2`，等 `CONN-ACK`）。但对方可能只是一个音源，
或者**本机才是汇聚端**，这时根本没有 hub 握手口在听，必然 30 秒超时；与此同时对端
音源的 `CONN-REQ` 早被本机 `InviteListener` 自动 ACK 并开始推流——于是「没同意」和
「声音已通」可以同时为真。旧代码在握手失败时 `setUserLeft(true)` + `refresh()`，
`refreshInner()` 的 `showControl = !userLeft && (...)` 立刻变 false，界面退回连接屏，
用户看到的就成了「提示没同意 → 自己退出 → 声音断掉」。

三处修复：

1. 本机是汇聚端时**跳过握手**（`GraphStore.isHub()` 直接 return）；
2. 本机是汇聚端时**不发 `ACTION_STOP`**——它会停掉自己正在收流/混音的
   `ReceiverService`；
3. 握手失败**不再退屏**，改为 `hintText = "等待对方确认连接…"` + 一句
   「已发起连接，等待对方响应」，交给图谱证据（`onlinePeers`）把用户带进控制屏。

另外 `respondToInvite` 补齐了日志（`AudioHubReceiver`）：旧实现
`catch (Exception e) { return false; }` 完全静默，排查时分不清「包没发出去」和
「ACK 没回来」。现在会打印目标解析失败、`开始握手 -> ip:port`、每一帧回包、
30 秒超时和异常栈。

### 顺带：一个自己造出来的 bug

为了让「手机从设备列表主动连入」也能通过，一度把 CONN-REQ 的放行条件写成
`session_peers.count(from_ip) > 0`。但 `RememberPeer(from_ip, ...)` 就在 CONN-REQ
处理的第一行，判断时它必然已经在集合里——等于**任何陌生设备第一次连入都被自动
ACK，待确认弹窗彻底失效**。现在在 `RememberPeer` 之前先快照 `was_known_peer`。

### 验证

模拟器上用一个假电脑（绑 `127.0.0.1:47102` 收 `CONN-REQ`，通过
`adb emu redir add udp:47999:47002` 把 `CONN-INVITE` 送进手机 47002）实测两条路径：

- **回 ACK**：`开始握手 -> 10.0.2.2:47102` → `握手收到回包 #0 = CONN-ACK|fakepc|1`
  → `握手成功，用时约 0 秒`；
- **不回 ACK**：30 秒后 `握手超时 30 秒：对方没回 CONN-ACK`，**界面停在连接屏不退**
  （旧代码会退屏）——这正是用户症状的复现与修复确认。

### 回归

`build.sh` 断言 10970 / 失败 0 / 88 用例；`audioflow-test --rounds 4` 15/15；
`ui-e2e` **ALL CHECKS PASSED**；spatial PASS 23182、graphfit 249。

顺带把 `tools/ui-e2e.js` 的 `rafAlive` 加固了：只靠 `Page.bringToFront` 挡不住
「窗口被 DSH 自己的 Electron 盖住 → Chromium 停掉 rAF」这条环境噪声（症状是快照里
`fps: 0`，滚轮缩放和相机环绕断言全红）。现在额外下发
`Emulation.setFocusEmulationEnabled` 和 `Page.setWebLifecycleState('active')`，
把页面钉在「前台可见」，那条随机红从此稳定。

APK versionCode 19；PC 便携版重新打包。

## 30. 第二十轮（v0.20）：电脑连手机一直无反应 / 退出重进有遗留

这一轮没有动任何视觉，但它是星空界面"看起来能用"的前提：图谱里没有节点，
星图就只剩自己一颗恒星。

**用户原话**：「电脑连接手机后一直显示无反应。也可能是多次退出重进后有遗留，
手机显示等待对方确认，电脑显示手机没反应」。

后半句就是全部线索。根因不在协议、不在渲染，而在 Windows 的 UDP 端口语义：
两个 socket 可以绑同一端口（`SO_REUSEADDR`），更隐蔽的是**「绑定具体 IP」和
「绑定通配地址」可以共存** —— 残留进程占着 `127.0.0.1:47002` 时，hub 绑
`0.0.0.0:47002` 照样成功，但发往 `127.0.0.1` 的包全进残留进程，`bind()`
一点异常都不报。于是界面一切正常、日志一切正常，就是收不到手机的握手帧。

修法见 `android/README.md` 的 v0.20 一节：独占绑定 + 绑定前用
`GetExtendedUdpTable` 枚举端口占用 + 把核心进程的 stderr 原样报到界面横幅。

顺带修掉一个真实缺陷：手机收到 `CONN-ACK` 后没有把对端写成 `hubId`，
要等对端广播 GRAPH 帧校正；而对端广播的是**它自己的地址**，NAT / 多网卡
之后和本机看到的对不上，`hubNode()` 恒为 null → `showControl` 不成立 →
已经连上、声音在走，界面却卡在连接屏。现在握手成功就直接
`GraphStore.switchHub(...)`：只有 hub 的握手口会回 `CONN-ACK`。

APK versionCode 20；PC 便携版重新打包。

## 31. 第二十一轮（v0.21）：切换汇聚端回来没声音 / 最后一路断开后图谱残留

用户报两条：① 「切换到电脑再切换回来时没有声音传递，手机显示传递后生效」；
② 「当一方断开连接后，如果图谱中已经只有一个设备就应该同步退出最后一个设备
然后删除图谱，下次建立连接时重新创建」。

### ① 投屏授权被提前销毁

`reconcile()` 在对端切换时调 `stopSource()`，它会 `projection.stop()` 并
`clearProjectionAuth()`。Android 14 授权是一次性的 → 切回来永远没声音。
改调 `stopSourceKeepingAuth()`（只 `ACTION_PAUSE`，保留 projection 与
AudioRecord，`sending=false`），切回时直接 resume。

### ② 图谱只并集、不删节点

`src/graph.cpp` 的 `UpsertNode` 明确「字段会话内不变：只增不改」，所以
「只剩自己」不能看 `nodes.size()`。C++ 端新增 `ClearSessionLocked()` +
`EndSessionIfAloneLocked()`（判据：无流 + 无 pending + `session_peers` 空），
挂在 `CONN-BYE` 与流超时清理之后。Android 端用
`hasPeerNode()` / `onlinePeerCount()` + `sawPeer` 记忆判定。

**踩坑两次**：用 `isAlone()` 时对端离线后节点仍在表里 → 永不触发；
改成只看服务状态后，刚连上的一瞬被误判成「已经没人了」→ 连接一闪退回连接屏
（模拟器实测复现）。最终判据必须有「曾经有对端」这个前提。

APK versionCode 21；PC 便携版重新打包。

## 32. 第二十二轮（v0.22）：点「送入」不弹录屏授权 / 刚连上就没声弹回连接屏

用户报：「现在甚至没有唤醒手机上的允许录屏的提示界面，电脑没有收到声音」。
在模拟器上逐字复现出两条独立故障：授权后 6 秒自动弹回连接屏；回到连接屏后点「送入」
连系统授权框都不出现。

根因是 v0.21 的收摊判据撞上了 NAT：`onlinePeerCount()` 依赖 `node.lastSeenMs`，
而活标志只按**数据报源 IP** 刷新（`markSeen`）。NAT / 模拟器改写源地址后
（手机看到 PC 是 `127.0.0.1`，图里是 `10.x`）一帧都对不上，于是对端每 3 秒照发的
GRAPH 帧没能兑现活标志 → 10 秒后判「只剩本机」→ 收摊清掉 `hubId`、`hubIp`、`sourceIntent`
→ 之后点「送入」因 `hubNode()==null` 早退，连授权框都不弹。

三处修复：① `mergeFromFrame` 把「收到一帧 GRAPH」本身当作发送方活着的证据，
帧内每个对端节点全部标活；② `onlinePeerCount()` 改用 `effectiveLastSeen`，
与 `snapshot()`/`isOnline()` 同口径；③ `toggleSource()` 回落到 `rememberedHub()`，
且收摊分支不再清 `hubIp`/`hubName`/`sourceIntent` —— 收摊是「本轮会话结束」，
不是「忘记这台电脑」。

修复后：点「送入」弹授权框、连续 20 秒停在控制屏、hub 侧音频包 18 秒持续增长
（18990 → 22286）、`hub_ip`/`hub_port` 保留在 SharedPreferences 里。

## 33. 第二十三轮（v0.23）：点连接后一直显示「等待对方确认」/ 连录屏授权框都不弹

### 现象

用户逐字反馈（m21648）：「手机上一直显示等待对方确认连接，并且还是没出现确认录制屏幕的窗口」。手机点「连接」后界面挂在等待文案，30 秒内连一次性的 MediaProjection 授权框都不弹，电脑侧收不到声音。

### 根因

`MainActivity.pickRemoteHub()` 把 `ensurePermissionsThenStart()` 只放在 `ReceiverService.respondToInvite()` 的**握手成功分支**里。那个握手是 30 次 × 1 秒的 `CONN-REQ → CONN-ACK` 探测，电脑没回 ACK 时走 `!ok` 分支直接 `return`，授权流程从未被调用。

而这个握手对送声音并非必需：`src/hub_core.cpp:515-530` 里 hub 收到音频包会自动建流（`auto_accept` 默认 true，音频包即使没握手也能出声）；`CaptureService.java` 只有发送循环，不做 CONN-REQ。真正推流的是 CaptureService，唯一前提是一次性录屏授权。

### 修复（`MainActivity.java`）

1. 在 `pickRemoteHub()` 的 `refresh();` 之后、起握手线程**之前**无条件发起授权（`!GraphStore.isHub()` 时 `sourceIntent = true; ensurePermissionsThenStart();`），点「连接」立刻弹系统授权框。
2. 删掉成功分支里重复的授权调用（否则叠出第二个系统弹窗）。
3. `!ok` 分支文案改诚实：「电脑 X:Y 没有回应（30 秒内没收到确认）」+ 三条排查提示（AudioHub 是否运行 / 防火墙 / 同网段），不再谎称「对方还没点同意」。
4. grace 回落分支去掉 `HUB_MISSING_GRACE_MS` 6 秒超时停采集：图谱找不到 hub 节点常因 NAT 收不到对端 GRAPH，一停采集就会连锁触发 v0.21 收摊、清空 hubId、退回连接屏。改为 `(srcRunning || hubRunning || sourceIntent)` 都保持。
5. 收摊分支加 `&& !sourceIntent`：正在送音时绝不收摊。

### 验证

模拟器上点「连接」3 秒内弹录音授权框；完整授权后音频包持续 30 秒增长（1604 → 6899，每 5 秒约 1058 包），`MediaProjectionPermissionActivity` 只 START 1 次。

> 验证时 `tools/tapui.py dump` 反复显示录屏弹窗是**工具假象**：星野动画让 `uiautomator dump` 报 `could not get idle state` 并读到过期的 `/sdcard/ui.xml`。以 `dumpsys window` 的 `mCurrentFocus` 与截图为准，真实界面是 MainActivity。

回归全绿：`build.sh` 10970/0/88、`audioflow-test --rounds 4` 15/15、`ui-e2e` ALL CHECKS PASSED、spatial PASS、graphfit 249。产物：APK versionCode 23 / versionName 0.23，PC 便携版与 Setup 重打。

## 34. 第二十四轮（v0.24）：手机发包正常、桌面端却没声音 / 电平条不动

- 用户 m22380：「现在手机上正常显示发包，但桌面端貌似没有收到，没有在正常渲染，导致没有声音」。
- 诊断：**不是没收到，是手机发来的包里装的是静音。** `CaptureService.java:435-459` 用
  `AudioPlaybackCaptureConfiguration` 抓「手机正在播放的媒体声音」（音乐 / 视频 / 游戏），
  不是麦克风；手机没放音时 `AudioRecord.read()` 返回全 0 → 包照发、PC 照收照渲染、
  但渲染出来是静音 → 电平条恒 -120、耳机没声。
- 决定性证据：假音源发 440Hz 正弦 → PC `bus=-19.88/-22.52`、`confirmed=true`（接收侧完好）；
  手机端到端 `sources=[conf=True pkt=563→3473]`、`occ≈target`、无丢包但 `bus=-120`；
  裸 UDP 解手机包 `200 包/秒`、`peak=0`、`avgRMS=0.0`；`writePayload()` 复核正确；
  A/B 手机播 tone → payload / PC 总线都变 `-11.7 dBFS`。
- 用户选择「保持现状，加静音提示」：不改采集语义，两端加醒目的「当前没采集到声音」提示。
  手机端在控制屏 `hubSub`（+ 连接屏 `sourceState`）连续静音 3 秒后显示琥珀警示并变色；
  PC 端新增 `#silentWarn` 横幅（`ui/index.html` / `ui/styles.css` / `ui/renderer.js`），
  判据 `confirmedCount > 0 && busLevel <= -119` 连续 3 秒。
- 验证：手机端 A/B 截图（静音 5592 琥珀像素 / 播 tone 0 像素 + 绿色电平条）；
  PC 端确定性注入 4/4 PASS；回归全绿（build.sh 10970/0/88、audioflow 15/15、
  ui-e2e ALL PASSED、spatial / graphfit PASS）。
- 真机复验提示：先在手机上播放媒体声音再连，否则两端会明确提示「当前是静音」。

## 35. 第二十五轮（v0.25）：真机「手机在放、桌面端却没声音」

- 用户真机反馈：投屏开启时手机卡一下，之后正常播放音乐但桌面端不渲染、没有声音；手机照常出声。
  与 v0.24 模拟器结论（播 tone 能出 `bus=-15.05`）相反 —— 真机 ROM 行为不同。
- 根因（用户选定修复的那条）：`CaptureService.muteMediaVolume()` 连接后把 `STREAM_MUSIC` 设 0，
  部分真机 ROM 会连 `AudioPlaybackCapture` 一起掐成静音；`maybeHealMute()` 的 `isMusicActive()`
  闸门在这些 ROM 上恒 false，3 秒自愈永远不触发 → 整条流永久静音。旧注释「Android 理论上也不受
  媒体音量影响，但没实测」被真机推翻。
- 修复（保持改动面最小）：`muteMediaVolume()` 改为 no-op，只打日志；保留方法签名、`mutedByUs`
  字段与 `restoreMediaVolume()` / `maybeHealMute()`，使 `mutedByUs` 恒 false、所有收尾路径安全空转。
  用户手机音量原样，手机照常出声、汇聚端也收得到。版本号 0.25。
- **已知限制（本次不修）**：主流音乐/视频 App 会显式禁止自己被 `AudioPlaybackCapture` 捕获
  （版权/DRM），系统级限制绕不过。真机若修完仍静音，先用系统自带播放器或本地音频文件对照。
- 回归全绿：build.sh 10970/0/88、audioflow 15/15、ui-e2e ALL CHECKS PASSED、spatial / graphfit PASS。
  真机复验待用户完成（本机无真机连接）。

## 36. 第二十六轮（v0.26）：两端彻底互相听不到声音

- 用户 m23876：「现在已经是彻底的两端都收不到对方的声音了。你是不是把什么改坏了」。是，v0.21 的收摊逻辑改坏的。
- 链路：`EndSessionIfAloneLocked()` → `GraphResetLocked()` 置 `graph_detached = true`；手机
  `CaptureService` 重连只发音频包（不发 `CONN-REQ`），走的是 `src/hub_core.cpp` 的纯数据包建流分支，
  那条分支不调 `GraphAddPeerLocked` → 永不 `GraphReattachLocked()` → `HandleGraphFrame` 首行
  `if (graph_detached) return;` 丢掉所有 GRAPH、`GraphBroadcastLocked` 也不发 → 两端各自认为自己是
  汇聚端 → 手机不送音、PC 的 `main.js` `if (hubId === selfId) stopPcSource()` 主动停源 = 双端静音。
  这次的问题表面在音频、根子在**图谱共识**——星空界面里「看不到对方的星」就是它的可见症状。
- 修复：纯数据包建流后 `if (graph_detached && !from_ip.empty()) GraphAddPeerLocked(from_ip, "", 0, nullptr);`
  只在 detached 时执行，正常收流零改动；端口传 0 回落 47000。
- 回归补强：`tools/audioflow-test.js` 新增 T7，并用「删掉修复后重编译 → T7 必须变红」做了突变验证
  （红：6s 内没回来；绿：116ms 自愈）。修复后 audioflow **20/20** 全过；build.sh 10970/0/88；
  ui-e2e ALL CHECKS PASSED；spatial 23182；graphfit 249/0。
- 教训：v0.21 引入收摊是为了「最后一路断开后清图谱」，但**cleanup 路径和 reattach 路径必须成对出现**。
  只加了一半，就把「重连」这条路堵死了，而且症状（没声音）离原因（图谱不合并）很远。

## 37. 第二十七轮（v0.27）：先把「有没有出声」测出来，再谈修

- 用户 m24569：「还是双端不通，你需要一个方法检查是否真的有音效出现在汇源端」。这句话把方向掰对了 ——
  在造出可信的测量之前，我所有的「修好了」都是猜。
- **我之前的判据是错的**：`Meter::Take()` 取走即清零，界面每 200ms 搬空一次，我从外面 `curl` 再读
  必然得到空桶（-120）。「没声音」和「刚被读空」在数据上不可分辨 —— 这就是几轮结论反复横跳的原因。
  实测反证：同一时刻 `renderAudibleFrames=1,439,898`、`renderPeakDbfs=-9.1`，而 `busLevel=-120`。
- 建成三条互不干扰的证据链：
  1. **只增不减的渲染探针**（`renderFrames` / `renderAudibleFrames` / `renderPeakDbfs` /
     `renderRingMissed`），夹在送进声卡的那一帧上，谁读都偷不走；
  2. **WAV 落盘**（`POST /api/render-dump/start|stop`）—— 用户不必相信数字，直接播放那个文件；
  3. **`tools/check-audible.js`** 一条命令给出结论，并按四种情形分开报告：设备没跑 / 没收到包 /
     有流但全被排除在混音外 / 混音后就是静音 / 确实在出声但设备侧静音。
- 界面同步：PC 状态栏「出声 N/M 帧 · 峰值 X·dBFS」；静音警告区分「混音后静音（上游）」与
  「发送端没在放声音」；手机状态行本机当汇聚端时显示同样三个数，全零追加「（全是静音：混音后没声音）」。
- 测试也一起改：`audioflow-test.js` 的 `waitForSound` / `waitForSilence` 原来只看 `busLevel`（同样会被
  抢空），改为以 `renderAudibleFrames` 增量为准。突变验证（退回纯 `busLevel`）会失败，证明判据真接线。
- 与星空界面的关系：图谱里「看不到对方的星」和「听不到对方的声音」在 v0.26 已被证明是同一个病；
  这一轮补的是**可见性**——把「汇聚端到底出没出声」变成界面上一行只增不减的数字，而不是靠耳朵猜。
- 数字：`bash build.sh` 断言 **10998** / 失败 0 / 用例 **96/96**（+28 断言 +8 用例）；
  audioflow 20/20；ui-e2e ALL CHECKS PASSED；spatial 23182；graphfit 249/0。
  产物：APK 456,791 B（27 / 0.27，sha256 `14a1b67d…`）；PC 便携版 79,192,510 B、安装版 79,485,682 B。
- 真机复验：手机放 60s 440Hz、走 CaptureService 送到 PC 汇聚端，`renderAudibleFrames`
  本窗口 +289,920（= 窗口总帧数，一帧不落）、峰值 -7.8 dBFS，导出 WAV
  `frames=289440 dur=6.03s maxabs=13106(40.0%FS)` 可直接听到；而**同一轮的 `busLevel` 仍是 -120**。
  停播后判「△ 之前在出声但本窗口没有新声音」，拔掉音源判「✗ 一个音源都没接入」。
## 38. 第二十八轮（v0.28）：让「出声 0/N」自己说清楚为什么

v0.27 把渲染探针摊到界面上之后，冒出来一个更难回答的问题：用户看到的是

> 显示出声 0/多少多少帧

帧数在涨、出声恒为 0。这行数字在 v0.27 里只有一个裸的「0」，**用户无法判断这是坏了还是本该如此** ——
而这恰恰是最要命的：三种完全不同的原因被压成了同一个数字。

### 被误读的那个 0

读代码可以确认，`src/hub_core.cpp` 里混音输出有一个协议规定的静音点（§6.1）：

```cpp
if (!IsHub(graph_, self_node_id)) mix_inputs.clear();
```

本机不是汇聚端时，混音输入被清空，输出按协议**恒为零**。也就是说 `renderAudibleFrames == 0`
在这个前提下是**正确行为**，不是缺陷。而 v0.27 的界面把 PC 端这一行做成了无条件显示
（手机端反倒有 `if (isHub && hubRunning)` 保护），于是最常见的一种「正常」被显示成了「异常」。

### 补上的三个归因量

`HubStats` 新增（全部只增不减，任何读者反复读都一样）：

- `rendering_as_hub` —— 这一拍本机是不是汇聚端
- `mixed_sources` —— 最后一拍**真正参与混音**的流数
- `mixed_frames` / `mixed_audible_frames` —— 有流参与混音的累计帧数 / 其中混出声音的帧数

`mixed_sources` 的统计必须只数 `active && data != nullptr && gain != 0` 的流：
`MixBlock` 第一件事就是 `memset` 清零，然后 `if (!in.active || ...) continue;` ——
把 `mix_inputs.size()` 当成「混入几路」会直接骗人。

### 界面把结论直接写出来

状态栏不再是 `出声 0/481203 帧`，而是带上后缀：

- `（本机不是汇聚端，按协议本就静音）`
- `（没有音源混进来）`
- `（混入了 1 路，但混出来是静音）`

静音警告条也从原来的一句话（把所有 `neverAudible` 都归因成「数据没混进来」）拆成三条，
各自指向不同的下一步。

### 验证

三种情形逐一构造并实测（正弦幅度 0.5 / 0.0 / 图谱加节点后切走），`renderingAsHub` 与
`mixedSources` 的取值都符合预期，`check-audible.js` 的判定树也按情形给出不同结论：

| 情形 | `renderingAsHub` | `mixedSources` | `mixedAudibleFrames` | 结论 |
| --- | --- | --- | --- | --- |
| 正常出声 | true | 1 | 有增长 | ✓ 确实在出声 |
| 混入了但静音 | true | 1 | 恒为 0 | ✗ 采集端抓到的就是静音 |
| 不是汇聚端 | false | 0 | 恒为 0 | △ 正确行为，不是故障 |

回归：`build.sh` 10998/0/96、`audioflow-test --rounds 4` 20/20、`ui-e2e` ALL CHECKS PASSED、
spatial 23182、graphfit 249/0。

产物：`build/audiohub-source.apk` 456,791 B（versionCode 28 / versionName 0.28）、
`ui/dist/AudioHub-便携版.exe` 79,185,355 B。

## 39. 第二十九轮（v0.29）：让「没有音源混进来」说出真正的原因

### 这一轮为什么必须做

v0.28 把「出声 0/N」拆成了三种情形，讲清了「不是汇聚端 / 没混进来 / 混进来是静音」。
但用户真机上撞到的仍是其中最常见、也最没用的一句话：

> **没有音源混进来**

这句话只描述了现象。`mixedSources === 0` 背后至少有九种成因，而它们该检查的地方
一个都不在同一个方向：可能是网络没通、可能是对端点错了按钮、可能是采集授权没给、
也可能只是你自己把这路音源静音了。用户拿着这句话只能猜。

### 关键约束：不改后端

星空图谱的数据里其实已经有全部判据 —— `state.sources` 带 `confirmed` / `muted` /
`packets` / `peerIp`，`state.graph.nodes` 带 `online` / `isSelf` / `isHub`，
`state.pending` 和 `state.peers` 也都在。所以这一轮**没有改任何 C++、没有加任何
`/api/state` 字段、没有动协议**，只把界面里那句含糊的提示换成九支可执行的判定。

这是刻意的：v0.28 刚加完四个归因字段，如果一遇到「还说不清」就继续加字段，探针会
越堆越多，而真正的问题是**已有的证据没被用起来**。

### 九支判定（`whyNoSource()`）

按决定性从强到弱排列，每一条都对应一个具体动作：

| 现象（按优先级） | 说的话 | 该去查什么 |
| --- | --- | --- |
| 图谱里只有自己 | 星空里只有你自己，对端还没连上 | 对端点「送入」、防火墙放行 UDP |
| 对端全离线 | 对端在图谱里但已离线 | 对端 App 被系统杀了，或换了 IP |
| 有 pending | 对端已请求连接，等你点「同意」 | 界面点「同意」 |
| 有在线对端但无流 | 对端就在星空里，但它还没开始送音 | 对端是否已授权采集 |
| 有发现无图谱 | 已经发现对端，但它还没开始送音 | 先把它拉进图谱 |
| 全静音 | 收到的 N 路音源全被你静音了 | 取消静音 |
| 全未确认 | 流已收到，但都还没被确认（看「自动同意」） | 打开自动同意 |
| 包太少 | 流刚建立，还没收到足够的包 | 再等一秒 |
| 其他 | 收到了 N 路流，但没有一路通过混音条件 | 增益为 0、流被排除 |

图谱在这里比发现列表更权威：**星空里除了自己没有别的星球 = 对端确实没连上**，
这时候去调音量、换输出设备、改混音参数全是白费力气。这正是把图谱数据接进诊断的意义。

### 两个必须踩对的顺序

1. **`!asHub` 必须排在 `audible === 0` 之前**（v0.28 已确立）。本机不是汇聚端时，
   输出恒为静音是**协议规定的正确行为**，不能被判成故障。
2. **`whyNoSource()` 必须同时接进 `anySources === 0` 分支**（v0.29 新增）。
   用户最常遇到的恰恰是「一个音源都没有」；如果那条分支只报「没收到包」，
   九支细分判定在最常见的场景下永远用不上。**测出这一点靠的是真跑一次工具** ——
   第一版只接了 `mixedSources === 0`，跑空 hub 时输出仍是笼统的一句。

### 一次自我纠正

`whyNoSource()` 第一版里，「图谱里有在线对端」这一支漏了兜底，导致它掉进最后的
`return '还没有任何对端连上来'`。九支用例跑出 `distinct 7 of 9` 才暴露 ——
J 情形（对端在线但没送音）显示成「还没连上」，方向完全错。补上缺的那一支后
`distinct 9 of 9`。

**教训：判定树的每一支都必须有独立可观测的用例，否则「都返回了一句话」会骗过眼睛。**

### 编码踩坑

验证时先把 `whyNoSource()` 从 `ui/renderer.js` 抽出来用 node 跑，结果中文全成乱码。
不是源码坏了，是提取脚本 `io.open(path,'w')` 默认用 GBK 写文件。改成
`io.open(path,'w',encoding='utf-8')` 后一切正常。**源码里查一遍中文完好**是最快的判别法。

另外 `tools/check-audible.js` 原本是纯 LF，一次 patch 把它写成了 CRLF（数回车符从 0 变成 290 行）。发现后按字节层面把 CRLF 换回 LF 才复原 —— 这个项目里 `ui/*.js` 是 CRLF、`tools/*.js` 是 LF，改哪个文件都要先确认它原本的约定。

### 回归与产物（v0.29）

- `bash build.sh` → 断言 `10998` / 失败 `0` / 用例 `96/96`
- `node tools/audioflow-test.js --rounds 4` → **20/20**
- `AUDIOHUB_E2E_PORT=19346 node tools/ui-e2e.js` → `ALL CHECKS PASSED`
- spatial `PASS: 23182` / graphfit `checks=249 fails=0`
- APK v0.29 `456,791 B`（`versionCode='29' versionName='0.29'`）
- PC 便携版 `79,185,545 B` / 安装版 `79,478,721 B`

## 40. 第三十一轮（v0.31）：让「我有发、你没收」这件事变成可观测的

这一轮没有修任何音频路径。它只做一件事：把「包到底有没有到达」变成两端都能看见的事实。

原因是上一轮的九支判定给出了「星空里只有你自己，对端还没连上」，
而用户手机上是「已经开始传音」。**两句话都是真的**：手机说的是 `send()` 没抛异常，
电脑说的是没建成流。中间那一段（包有没有过网络）一直是黑盒。

### 补上接收侧的证据

`src/hub_core.cpp` 里其实早就有 `received_packets` / `received_bytes` 两个计数器，
但它们只在接收循环里累加，**从未被 `Stats()` 读取、也从未出现在 `/api/state`** ——
典型的「改完就忘」。于是电脑无法自证「包到没到」，只能从 `sources` 为空反推，
而 `sources` 为空至少有三种完全不同的成因。

v0.30 给 `HubStats` 补了 `rx_packets` / `rx_bytes` / `rx_bad_packets` /
`rx_pending_streams` / `rx_distinct_senders` / `rx_last_ip` / `rx_last_port` /
`rx_first_at_ms` / `rx_last_reject`。记账点放在**解码之前**
（`NoteRxPacketLocked` 在 `TouchGraphIp` 前面），这样「没收到」与「收到但解析失败」
才能区分开 —— 这是整轮设计里最关键的一处顺序。

v0.31 把它们接到界面上：状态栏多一个 `接收 N 包 / 未收到包`，
`whyNoSource` 的「星空里只有你自己」因此拆成两支，
一支是网络问题，一支是协议问题，处理动作完全不同。

### 手机侧同一件事

手机状态行从「发送中 · 1024 包」变成「发送中 · 1024 包 → 10.135.129.59:47000」。
目标地址来自持久化的 `hub_ip` / `hub_port`，**换网段不会自动失效**。
不把这个地址显示出来，用户永远发现不了自己在朝一个已经不存在的地址发。

### 便携版的解压目录

`portable` 目标每次运行都解压到 `%TEMP%\<随机名>\`，而 Windows 防火墙按程序
完整路径匹配规则 —— 每换一次目录，「允许」就不再命中。本机已积累约 120 条规则，
其中 2 条是 Block。v0.31 用 `build.portable.unpackDirName = "AudioHub"`
把目录固定下来。

### 一个被自己否掉的方案

本来想加「本机自测能否被外部访问」的按钮，实现是本机往自己地址发包。
实测发现本机自寻址不经过防火墙入站链，两组对照都「通」，**这个自测给的是假安全感**。
模块已删，判据换成「接收端收到几个包」这个直接可观测量。
教训：**能测到的东西必须真的经过被测的那条路径**，否则测出来的绿灯毫无意义。

## 41. 第三十二轮（v0.32）：把「握手口」从盲区里捞出来

### 用户报的现象

> 现在显示的是，对方就在星空里，但没有开始送音，并且显示未收到包。
> 手机显示电脑没回应请求，电脑也显示手机没回应请求

四句话，四个方向，看起来互相矛盾：能看见对方（说明网络通）、又说没回应（说明网络不通）、
音频口 0 包（说明没数据）、又说「开始传音」（说明发了）。v0.31 之前我一直在这四条之间来回解释，
因为**我的观测本身有洞**。

### 洞在哪

星空里的「看得见」来自 47300 的 UDP 广播；握手、图谱、摆位全走 `audioPort + 2`。
这是两个完全独立的端口。而 v0.30 加的 `rxPackets` 只统计 `audioPort`（47000）本身。

于是「广播能到、47002 被挡」这个组合，在我之前的工具里只能被读成「一个包都没收到 → 网络全断」——
方向就是错的。**不是产品又坏了，是我的探针没覆盖到那个端口。**

### 补什么

`HandshakeLoop()` 里在解码**之前**记账（和解码成败无关，先证明包到了）：

```
hs_packets / hs_recent / hs_last_ip / hs_last_port / hs_last_cmd / hs_last_line
discovery_peers
```

配一条 10 秒衰减（`DecayHandshakeRecent()`，200 拍），让「最近还活着吗」这件事有近似实时值，
而 `hs_packets` 本身仍然只增不减，轮询偷不走。

界面状态栏因此多了独立的一格「握手」，和「接收」（媒体口）并排。
两个都是 0 之外的组合才有意义：`接收 0 + 握手 0 + 广播可见` = 47002 被挡，
`接收 0 + 握手有数` = 握手通了、媒体没来。

### 三情形自证（这次第 ③ 项我自己写错了一版）

```
① 没发包            hsPackets=0                                 rxPackets=0 sources=0
② 发 5 个垃圾包      hsPackets=5  hsCmd="HELLO-WORLD-NOT-A-COMMAN" rxPackets=0 sources=0
③ 发合法 CONN-REQ    hsPackets=8  hsCmd="CONN-REQ" sources=1 acked=true rxPackets=0
```

第 ③ 项第一次跑是红的，`hsCmd` 显示 `GRAPH`。查下来是**我的探针自己干扰自己**：
`CONN-REQ|probe32|9|47900` 里声明的音频口正好等于 hub 自己的 47900，
于是 hub 把 GRAPH 发到 `127.0.0.1:47902`（也就是自己的握手口），回环把 `hs_last_cmd` 覆盖了。
改成 `48000` 之后立刻全绿。

教训：**自证脚本的构造本身也要过一遍「会不会自己干扰自己」。**
如果当时没追这一步，就会把一个测试缺陷记成代码缺陷。

另外第 ③ 项这个状态组合最值得记住：**握手通了、流建成了，音频口仍然是 0 包**
（`sources=1` 而 `rxPackets=0`）—— 这正是用户现场可能的中间态，
也正好证明这两个口确实是分开记的。

### 判定树也跟着拆了

`whyNoSource()` 里「对端在图谱里」的那两支，现在先看握手口：

| 握手口 | 广播 | 界面文案 |
| --- | --- | --- |
| 0 包 | 看得见 | 对端在图谱里，但握手口 47002 一个包都没收到（广播能看见它、这个端口被挡了，所以两端互报「没回应」） |
| 0 包 | 看不见 | 对端在图谱里但已离线（握手口也没收到过包） |
| 有包 | — | 对端在图谱里，握手口收到过 N 个包，但没能建成流（最近命令：CONN-REQ） |

突变验证：把第一支删掉，十种情形的输出从 `distinct 10 of 10` 退化成 `9 of 10`
（与「真离线」撞成同一句），还原后恢复。**写这条是为了确认新判据真的接线了，
而不是加了段永远走不到的代码。**

### 顺带记住的两个事实

- 便携版每次启动解压到 `%TEMP%\<随机名>\`，防火墙按程序完整路径匹配规则，
  所以「上次点过允许」对新目录无效。v0.31 用 `unpackDirName: "AudioHub"` 固定了目录名，
  但**已经存在的 Block 规则不会自己消失**，得手动清一次。
- 手机把 `hub_ip` 存进了 SharedPreferences。换网段之后它照样往旧 IP 发，
  而且 UDP `send()` 成功只代表「发出去了」，不代表「到了」——
  所以手机上「发送 N 包」在旧版本里完全不能说明问题。v0.31 起状态行显示真实发送目标。

### 回归

- `bash build.sh` → 断言 **10998** 条，失败 0，用例 **96/96**
- `node tools/audioflow-test.js --rounds 4` → **20/20**（T7 自愈 112ms）
- `AUDIOHUB_E2E_PORT=19352 node tools/ui-e2e.js` → `ALL CHECKS PASSED`
- `run-spatial-check.ps1` → `PASS: 23182`
- `run-graphfit-check.ps1` → `checks=249 fails=0`
- `tools/check-audible.js` 三情形：A `✗ + 3 条排查` / B `握手口包数 3`、指向采集端无声 / C `✓ 11549 帧出声，峰值 -5.7 dBFS`

### 产物

- APK `build/audiohub-source.apk` = **456,791 B**，`versionCode='32' versionName='0.32'`
- PC `ui/dist/AudioHub-便携版.exe` = **79,199,958 B**、`AudioHub Setup 0.1.0.exe` = **79,493,169 B**

> 这一轮的重点不是「修好了」，而是**把此前唯一没有观测的那个端口补上**。
> 装上之后看一眼「接收」和「握手」两格谁是 0，方向就唯一了。

## 42. 第三十四轮（v0.34）：终于把「哪一个环断了」和「手机在放声音吗」分开

这一轮没有修任何传输代码 —— 因为实测证明传输链路**本来就是通的**。
这一轮做的是：把「链路完好但内容为零」这个情况，从一句含糊的提示
变成一句能直接照做的话。

### 决定性实测（2026-11-08）

PC 当汇聚端，手机是 Android 14 x86_64 模拟器，APK 已授权采集。

| 时刻 | `mixedSources` | `mixedFrames` | `mixedAudibleFrames` | `renderPeakDbfs` |
| --- | --- | --- | --- | --- |
| 手机没在放声音 | 1 | 29,140,800 | **0** | -240 |
| 手机上真的播 440 Hz | 1 | 继续涨 | **1,261,680 → 1,372,560 → 1,484,880** | **-7.96 dBFS** |
| 调 0 音量 + 退出播放器 | 1 | 继续涨 | 冻结在 2,921,280 | 不再变化 |

`rxPackets` 全程 242,838+，`sources` 全程 `confirmed`。
→ 握手、建流、混音、渲染、落盘**每一环都正常**，唯一的零在「内容」这一层，
而内容的来源是手机端的 `AudioPlaybackCapture`。

### 界面上的三处修正

**1. 「出声 0/N」的括号从「现象」改成「下一步」**

以前：`（混入了 1 路，但混出来是静音）` —— 用户看完不知道手该往哪放。

现在：`（音频数据到了 CPU、混音器也在混，但内容全是零 —— 问题在采集端：手机没在播放媒体声音，或放的声音不属于媒体类型）`

理由是这句提示所在的**分支位置**本身就是最强的证据：能走到
`mixedSources > 0 && audible === 0`，说明网络、防火墙、建流、混音器、
渲染设备全部已经过了一遍并且都没问题。这时候再让人去查这些，就是把人往错的方向推。

**2. 手机端不再让静音提示顶掉关键数字**

`sourceState` 以前在静音时整行替换成提示，于是「发送 N 包 → ip:port」**消失**。
而这两个数字恰恰是判断「包有没有出去、地址对不对」的唯一依据。
现在改成提示 + 换行 + 事实，两个都在。

**3. 加了「图谱说 A、实际发往 B」的交叉校验**

手机把上次的汇聚端 IP 持久化在 SharedPreferences 里，换网段后会继续往旧地址发，
而界面上一切正常。现在这种情况会直接显示
`⚠ 图谱里的汇聚端是 X，实际却发往 Y`。

### 判据铁律

- `mixedAudibleFrames > 0` → 汇聚端真的在出声，听不见就去查汇聚端的音量/静音/输出设备。
- `= 0` 且 `mixedSources > 0` → 数据到了、内容是零，**问题在采集端**，与网络无关。
- `= 0` 且 `mixedSources = 0` → 一路都没混进来，查连通性/确认状态/静音（九支判定）。

### 这一轮没解决的

「两端互报没响应」仍未在真机上复现。所有协议层实验跑在模拟器上，
而 qemu 的 user-mode NAT 会改写源 IP（模拟器发包到宿主时源 IP 变成宿主 IP），
所以模拟器上的「两个节点」验证全部无效 —— 这条只能等真机。

### 回归与产物

- `bash build.sh` → 断言 10998 条 / 失败 0 / 用例 96/96
- `node tools/audioflow-test.js --rounds 4` → 20/20（T7 自愈 3ms，自愈后仍在出声 -13.1 dBFS）
- `node tools/ui-e2e.js` → ALL CHECKS PASSED
- `run-spatial-check.ps1` → PASS 23182 项
- `run-graphfit-check.ps1` → checks=249 fails=0
- APK `build/audiohub-source.apk` = 456,791 B，`versionCode='34' versionName='0.34'`
- PC `ui/dist/AudioHub-便携版.exe` = 79,200,192 B、`AudioHub Setup 0.1.0.exe` = 79,493,405 B

## 43. 第三十六轮（v0.36）：星空里看得见对方，包却出不了这台电脑

这一轮没有改界面风格，也没有加新面板。它解决的是一个很具体、但此前几十轮都没抓到的
问题：**两端的星空里明明有对方那颗星球，音频口却一个包都没有，两边都写「对方没响应」。**

### 为什么之前一直查不出来

因为「收」和「发」在这个程序里走的是两套完全不同的逻辑：

- 收：三个 socket（音频口、控制口、握手口）全部绑 `0.0.0.0`，
  任何网卡进来的包都收得到 —— **收包侧永远不会有"绑错网卡"的问题**
- 发：`sendto` 直接用系统路由表，代码里没有任何固定出站网卡的逻辑

于是「收得到、发不出去」这种半通状态，在应用层是**完全看不见的**。
前面几轮我一直在猜（防火墙？端口被挡？协议版本？手机认为自己是汇聚端？），
全部都只是"可能"，没有一个能被现场证据钉死。

### 真正的答案

电脑上装了代理软件（Clash 之类），开着 **TUN / 增强模式**。它建了一张虚拟网卡，
抢走了默认路由。于是凡是发往「跟电脑不在同一网段」的地址，包都被这张虚拟网卡接走，
源地址被改写成 `198.18.0.1`，**永远出不了这台电脑**。

手机换过几次网段（校园 WiFi 换 AP、曾经连过热点的 `192.168.x`），
所以电脑发给它的握手包全进了代理黑洞，而两端互相还能通过子网内广播看见彼此
—— 这就是「星空里有它、却永远没回应」的完整解释。

### 这一轮的做法：把「包会从哪张网卡出去」变成一行字

新增 `ProbeEgress()`：对一个目标 IP 做 `connect()` + `getsockname()`。
关键点是 **UDP 的 `connect()` 不发任何包**，它只是让系统按路由表选一次源地址，
所以这个探针可以每 2 秒安全地跑一遍（带缓存，因为 UI 每 200ms 轮询一次 `/api/state`）。

判定逻辑很短：拿到源地址之后，看它是不是落在 `RealNicSummary()` 列出的真实网卡上。
不在 → 被代理接管。

界面上加了一格「网卡」：

- 正常：`网卡 1 张`
- 被接管：`网卡 ⚠ 被代理接管`

而「没有音源混进来」那句话会直接变成可执行的建议：

> 本机发往 192.168.43.77:47000 的包被代理 TUN 接走了
>（源地址会变成 198.18.0.1，对方的包也回不来） —— 请先关掉 Clash / 代理软件的 TUN（增强）模式

### 一个被否掉的方案

一开始想直接用 `IP_UNICAST_IF`（socket 选项 31）把出站钉到 WLAN。实测有效：
跨网段目标的源地址真的从 `198.18.0.1` 变回了 `10.135.129.59`。

但紧接着发现**回环发送会失败**（`WinError 10049`），而 `tools/audioflow-test.js`
这一整套回归全部依赖 `127.0.0.2` 同机双进程模拟两台设备 —— 一改就把回归全打死。
更关键的是：还有另一类同样常见的原因是**校园 WiFi 的 AP 客户端隔离**，
固定网卡救不了它，却会引入新的故障。

所以最终只做**只读诊断**：把事实摆到界面上，让用户一眼看到"包出不去"，
而不是擅自在代码里改变出站行为。宁可少修一个，不要多坏一个。

### 顺带记录：怎么判断是不是 AP 隔离

如果两端确实在同一 WiFi、同一网段，仍然互不可达，就查 ARP 表：

```powershell
Get-NetNeighbor -AddressFamily IPv4 | Where-Object { $_.IPAddress -like '10.135.*' }
```

看到**好几个不同 IP 的 MAC 全是网关那一个**（代理 ARP / 大二层），
再 `ping` 其中几个全都不通 —— 那就是 AP 客户端隔离，应用层绕不过去。
可行的办法是**手机开热点、电脑连热点**（直连，没有隔离），或换一个允许设备互访的路由器。
