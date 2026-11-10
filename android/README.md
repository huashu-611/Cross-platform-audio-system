# AudioHub 源端（Android）

把手机正在播放的声音采集下来，通过 UDP 送到汇聚端（PC 上的 `audiohub-hub.exe`，或另一台
当汇聚端的手机）。这是**跨端音频系统**里“N 个源设备 → 1 个汇聚端”的手机侧实现：
同一套代码既能当音源，也能当接收端/汇聚端。

## 装

APK 已经打好了，在 `android/build/audiohub-source.apk`（v0.3）。

> 从 v0.1 升级：v0.1 的签名 key 是每次构建临时生成的，签名对不上，需要先卸载再安装
> （`adb uninstall com.audiohub.source`）。v0.2 起签名 key 固定在
> `android/keystore/audiohub-debug.keystore`，v0.2 → v0.3 可以直接覆盖升级。

```bash
# USB 连接 + 开发者模式
adb install -r android/build/audiohub-source.apk

# 或者把文件拷到手机上，用文件管理器点开安装（需要允许“未知来源”）
```

## 用

1. 先起汇聚端（PC）：

   ```bash
   build/audiohub-hub.exe --port 47000 --latency 60
   ```

   启动时会打印本机 IP，比如 `192.168.1.20`。**手机和 PC 必须在同一个局域网。**

2. 手机上打开「AudioHub 源端」。连接页会自动扫描局域网里的设备；
   广播被拦的网络（办公/校园 WiFi 常见）可以点「手动添加」直接填 IP。

3. 在「设备列表」里点「连入」。进入控制页后点「送入」，系统会弹出「屏幕录制」授权——
   **Android 的音频采集是挂在 MediaProjection 上的，不授权就没法抓，但它真的不会录屏。**
   你会在通知栏看到一个「AudioHub 正在采集」的常驻通知。

4. 手机放歌。汇聚端的耳机/音箱里应当听到声音，界面上会显示这一路的水位和电平。

控制页底部会实时显示：已发送包数、电平（dBFS）、码率。
**电平 −120 就是静音**——说明没抓到任何东西，先去看手机是不是真的在放。

5. 开始采集后**不会改动手机的媒体音量**（v0.25 起）。旧版会把它静音到 0，想让声音只从
   汇聚端出；真机实测部分 ROM 在音量为 0 时会连 `AudioPlaybackCapture` 一起掐成静音，
   现象就是「手机在放、桌面端电平条不动、完全没声音」，而静音自愈的 `isMusicActive()`
   闸门在这些 ROM 上恒 false、永远不触发。现在手机照常出声、汇聚端也收得到，互不干扰。

## 图谱：星空图（俯视投影，v1.7 起星空化）

控制页的图谱是**从上方俯瞰的只读星图**（不再是 3D 透视）：汇聚端画成中心的暖金色恒星，
其他设备画成静止围绕的行星（不公转、不自转），光束表示音频流；整页深空背景由
`StarSkyView` 绘制。投影/坐标/交互与 v1.5~v1.6 完全一致：

- 正交映射，1 米 = `ppu` 像素，等比居中；房间半径 2.4 m、内环 1 m，方位字「前/右/后/左」。
- 听者在原点朝 -Z，**屏幕上方 = 正前方 = -Z**，屏幕右 = +X。
- **距离即音量**：节点越远声音越小（振幅按 1/d 线性反比），没有远场截断；近场 +6 dB 封顶。
- 手机端**不能拖动、不能旋转、不能捏合缩放**；位置由汇聚端统一决定：
  桌面端拖动节点后，汇聚端会广播 `POS` 帧，手机 3 秒内跟到新位置。
- 没有人工摆位的节点用确定性自动槽位（按 nodeId 排序后依次取槽），所有设备对同一
  节点集算出的布局一致。
- 点击球：选中高亮 + 展开音量面板；点非汇聚端节点会弹「切换汇聚端」确认；
  双击节点直接切换汇聚端。
- **视野自适应（v1.6 起）**：节点都在房间附近时保持 2.6 m 的近景观感；有节点在远处时
  （距离即音量之后坐标可达 ±320 m）自动缩小 `ppu`，最远节点「连球半径和名字标签」
  都留在画布约 12% 边距以内，不会被挤到屏幕外。环始终按真实比例画；视野太大时
  环、方位字、刻度会省略（1 px 的环没有意义），左下角改为显示「视野 xx m」。
- 合规：屏幕坐标始终是 `sx = cx + wx*ppu`、`sy = cy + wz*ppu`（sy 向下 = +Z），
  屏幕上方 = 正前方 = -Z；视野缩放不改变方位关系。

## 位置同步协议（POS，与 C++ 端逐条一致）

走握手口（`audioPort+2`，与 GRAPH 同一 UDP socket）：

```
POS|<version>|<origin>|<nodeId>,<xCm>,<zCm>;<nodeId>,<xCm>,<zCm>;...\n
POS-REQ\n
```

- `<version>` 十进制 u64（Lamport）；`<origin>` 是发送方节点 id（`ip:audioPort`，可为空）。
- 坐标是厘米整数，范围 ±32000（±320 m）；`x=右(+)、z=后(+)`，与房间坐标一致。
- 整帧含 `\n` ≤ 1000 字节、摆位项 ≤ 32 项；items 段为空表示“整张表为空”。
- 合并是**整表 LWW**：`version` 大者胜；相等时 `origin` 字典序大者胜；采纳后整表替换，
  并再向所有已知节点中继一次（限速）。坏帧整帧丢弃，不能崩。
- 手机在连上/`requestSync` 时发 `POS-REQ`；收到 `POS-REQ` 回一帧完整 `POS`。
- 手机端只接收/中继，不产生摆位（禁止拖动），即使手机当汇聚端也没有拖动入口。

实现见 `PlacementStore.java`（严格编解码 + 合并）与 `InviteListener.java`（收帧/回包/中继）。

## 界面自适应（v1.5 起）

- 两屏（连接页 / 控制页）都是 `match_parent` 的 FrameLayout 子视图，靠 VISIBLE/GONE 切换；
  **没有 ScrollView**，内容不滚动即完整展示。
- 控制页的图谱用 `LayoutParams(MATCH_PARENT, 0, 1f)` 吃满剩余高度（最小 200dp），
  其余控件紧凑排列；音量面板默认收起，点节点才展开。
- 设备列表、待确认接入请求都放进对话框（主界面只留一行摘要），小屏也不溢出。
- 窗口底色显式设成深色背景，内容短于屏幕时不会露出系统白底。

## 退出与断开（v1.7 起）

- 停止采集、采集异常、服务销毁、退出 App（返回键 / 从最近任务划掉）都会在关 socket 前
  best-effort 连发 3 次 `CONN-BYE|<streamId>` 到汇聚端握手口（`audioPort+2`，间隔 50 ms）。
  汇聚端收到立刻摘掉这一路流和会话（旧版本要等 5 秒音频超时才掉流，看起来像"手机退了桌面还在"）。
- 退出 App：`MainActivity.onDestroy` 停掉采集服务（转屏重建不打断）；`CaptureService.onTaskRemoved`
  与 manifest 的 `android:stopWithTask="true"` 是划掉任务时的兜底。退出后不再推流、不再广播在线。
- 汇聚端退出：桌面端收到 `CONN-BYE`（或音频超时）后十来秒内把本机标为离线（PC 端同版本实现）；
  之后桌面端再邀请本机，手机端重新打开 App 即可响应。
- 邀请的可用性：只要 App 进程还活着（App 在前台，或按 Home 退到后台、Activity 未销毁），
  `InviteListener` 就一直在收 `CONN-INVITE`；但"同意邀请"要用户在前台点确认，后台收到只会
  置待确认标记。Activity 被销毁（进程已死，或退出 App 后由系统回收）时邀请会丢，重开 App 后再邀请即可。
  这也是为什么没有引入通知权限（后台弹邀请通知需要额外权限与通知渠道）。

## 多台手机

每台手机是图谱里的独立节点（id = `ip:audioPort`），汇聚端按节点分流并混音。
所有设备通过 `GRAPH` 帧同步同一份图谱；把汇聚端切到任意一台设备都可以。

## 自己构建

需要 Android SDK（platform-35 + build-tools-35）和 JDK 17。

### Android Studio

直接 `File → Open` 选本目录，然后 Run。项目零第三方依赖（不引 AndroidX），
所以同步和构建都不需要联网。

### 命令行（不依赖 Gradle）

在仓库根目录：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build-apk.ps1
```

产物：`android/build/audiohub-source.apk`（脚本会打印路径和大小）。

### 自检脚本（纯 Java 8，不需要 Android SDK）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android\verify\run-spatial-check.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File android\verify\run-graphfit-check.ps1
```

`run-spatial-check.ps1` 把 `Spatial.java` 与 C++ `tests/test_spatial.cpp` 的期望值逐条对齐
（空间化公式、ITD、延时逐样本过渡的接缝残差……）；`run-graphfit-check.ps1` 验证俯视图
视野自适应的数值约束（近/远节点、±320 m、随机布局都装得下、方位角不变、环按真比例）。
两个脚本都在临时目录编译，不写工作区；退出码 0 = 全部通过。

这个脚本做了两件不太常见的事，都是被逼的：

- **构建时给 manifest 注入 `package` 属性**：aapt2 命令行要求它，而 AGP 8
  已经不允许在源码 manifest 里写 `package` 了（要用 `namespace`）。所以源文件保持
  Gradle 兼容，打包时才临时注入。
- **不用 lambda 和方法引用**：`android.jar` 里没有 `java.lang.invoke.LambdaMetafactory`
  （Android 靠运行时 desugaring 支持 lambda，但 javac 编译时需要它在 classpath 上）。
  全部改成匿名内部类，反而在任何 Java 版本下都能编。

## 采集授权与 Android 14（v0.2 修复）

Android 14 起，屏幕/音频采集的 MediaProjection 授权是**一次性令牌**：一次授权只能用来
启动一次采集。系统会在 `startForeground(type=mediaProjection)` 时校验进程是否还持有有效
授权，所以「停掉旧采集、再用保存的旧授权重启」会被拒绝并抛 `SecurityException`，
整个进程直接闪退（v0.1 在「手机断开 → 桌面重新邀请 → 把电脑设为汇聚端」时会触发）。

v0.2 的改法：

- **切换汇聚端不重启采集**：`CaptureService` 收到目标变化时只换 UDP 发送目标（新 socket +
  新 IP/端口/streamId），复用当前的 `AudioRecord` + `MediaProjection` 会话，先给旧汇聚端发
  `CONN-BYE` 让它立刻摘流。`MainActivity` 不再保存授权去重启。
- **授权只活一次**：拿到的授权只用于当次启动，启动后立即丢弃（`clearProjectionAuth()`）；
  停止采集、断开连接、启动失败都会清掉。没有新授权时只提示「本机已设为音源，开始采集后
  生效」，不会拿旧令牌去试。
- **失败兜底**：`startForeground` 包了 try/catch，失败时把原因（`sLastError`）交给界面
  提示并停服务，不再把进程带走；界面每 400ms 轮询取走错误、清授权并 toast。

## 会话状态与邀请（v0.3 修复）

v0.2 有两个「断开之后」的状态问题：

- **断开后再次邀请不弹窗**：`inviteDialogShowing` 在第一次弹窗后置 true，却从不复位，
  之后的邀请都被 `!inviteDialogShowing` 挡掉。现在对话框加 `setOnDismissListener` 复位，
  每次邀请都会正常询问。
- **清后台重开进「没有汇聚端的星图」**：`userLeft`（用户已在连接页）只是内存字段，进程
  重启后归零；远端广播的残留图谱（≥2 个节点）把界面直接拉进控制屏，而实际没有会话，
  此时把任一端设成汇聚端也不会传声。现在：

  - `userLeft` 落盘到 SharedPreferences `audiohub` 的 `user_left`（默认 true），断开后
    清后台重开仍停在连接页；
  - 控制页条件加「汇聚端已知」（`hubId` 非空）：只有半张图时留在连接页；
  - 已断开时 `reconcile()` 直接停掉两个服务并返回，不会被残留图谱重新拉起；
  - 只有明确发起新连接（点「送入」、同意邀请、设备列表 / 手动 IP 连入、把本机设为
    汇聚端）才把 `user_left` 置 false 并进控制页。

界面顶部的版本标记现在显示 `v0.5 · 星图`：装上后先看这一行确认版本，再排查其他问题。

## 重连、衰减与稳定性（v0.4）

第四轮反馈里「重新邀请后进控制屏一下就退出 / 桌面端星图里还留着手机 / 只有两端同时退出才能
重连」这三件事，根因其实在 **PC 汇聚端**，不在手机：

- 汇聚端的 `accepted_ids`（已同意的流号）在流掉线、收到 `CONN-BYE`、「全部断开」时都没有
  清干净。手机重连固定用流 1，于是命中「这个流我同意过了」的分支——汇聚端回 ACK 但**不重建
  混音流**：手机进了控制屏、一包音频也不混，PC 星图里还留着那台手机，只有把 PC 重启（清
  `accepted_ids`）才恢复。现在 `accepted_ids` 跟着流一起清理，「全部断开」也清空
  `streams / accepted_ids / pending`，重连即重建流（见根目录 `README.md` 的 v0.4 说明）。
- 手机端本次补的是**兜底**：`projection.registerCallback`、`record.startRecording()`、
  采集线程主循环三处都包了 `try/catch(Throwable)`，异常写进 `sLastError` 由界面 toast 出来，
  不再把整个进程带走。若还闪退，界面上的「上次崩溃」对话框可以直接复制堆栈发回来。

另外两处调整：

- **距离衰减更陡**：增益从 `1/d` 改成 `(1/d)^1.6`（与 PC、C++ 完全一致），2 m ≈ -9.6 dB、
  4 m ≈ -19.3 dB，把行星拖远后听感变化明显了。
- **邀请只需填 IP**：「手动添加设备」的提示改成「电脑的 IP，例如 192.168.1.10（端口可省）」，
  端口默认 47000，逻辑没变，只是提示不再暗示必须带端口。

## 进控制屏闪退的兜底（v0.5）

v0.4 交付后仍有「同意邀请、进入控制屏的那一瞬间 App 退出」的报告。代码里没有任何
`finish()` / `System.exit`，所以那是**未捕获异常把进程带走**：`onStartCommand`、`onDraw`、
`refresh()` 都跑在主线程，里面漏一个异常就是闪退。v0.5 在这三处都加了总保险：

| 位置 | 兜底 | 出问题时的表现 |
|---|---|---|
| `CaptureService.onStartCommand` | 整体 `try/catch(Throwable)`，原实现改名 `handleStartCommand` | 采集起不来 → Toast「采集启动失败（XxxException），请重新点「送入」」，进程不退出 |
| `HubGraphView.onDraw` | `try/catch(Throwable)` 后调 `drawScene` | 最多这一帧不画，下一帧接着画 |
| `MainActivity.refresh` | `try/catch(Throwable)` 后调 `refreshInner` | 这一轮状态没刷新，400 ms 后自动重试 |

再加已有的 `projection.registerCallback`、`record.startRecording()`、采集线程主循环三处兜底，
现在任何一处出问题都只会「没声音 + 一条提示」，不会再闪退。真闪退了也没关系：
`crash.txt` 会在下次启动时弹出来，点「复制并清除」把堆栈发回来即可。

## 汇聚端被清空 / 重连后进不了控制屏（v0.6 / v0.7）

v0.5 交付后仍能复现「手机端断开、PC 再邀请、同意后进不了控制屏」，这次定位到**手机端的图谱
汇聚端（hubId）学不到**，并且反过来把 PC 的汇聚端清空：

- 手机点「断开所有连接」时 `GraphStore.resetSession()` 把本地 `version` 抬到
  `max(version, seenVersion) + 1`，同时把 `hubId` 清空。之后汇聚端发来的 GRAPH 版本号比这个
  抬高后的值小，`mergeFromFrame` 判「版本更低」整帧丢弃 → **hubId 永远是空**；
  `hubNode()` 返回 null → `reconcile()` 认为「汇聚端未知」把刚起的采集停掉（日志里那对
  「已静音媒体音量 → 已发送 CONN-BYE → 已恢复媒体音量」就是它）→ 界面退回连接页。
- 更糟的是空 hubId 会**顺着 LWW 反噬**：手机以高版本把「汇聚端为空」广播出去，PC 端
  `MergeGraph` 照单全收，于是 PC 的汇聚端也被清空（`[GRAPH] 合并 v2 … hub=(空)`），
  表现为「桌面端星图里还有手机、但谁当汇聚端都不出声」「必须两端同时重启才能重连」。

v0.7 修法（两端算法必须逐条一致，改完对 C++ 与 Java 都跑了断言）：

| 位置 | 改法 |
|---|---|
| `GraphStore.resetSession()` | `version / seenVersion / origin / hubId` 全部归零，只留本机节点。空白会话不该有版本优势 |
| `GraphStore.mergeFromFrame()` | ① 空 hubId 不等于一次真实赋值：`remoteHasHub || hubId.isEmpty()` 才写 hubId，**绝不**用空值覆盖已知汇聚端；② 本机 hubId 为空时，任何带 hubId 的帧都直接采纳（不受版本门槛限制），把汇聚端钉住 |
| `src/graph.cpp` `MergeGraph()` | 与上面两条逐条对齐（PC 端同样不能接受空 hubId 的覆盖，空白会话同样直接采纳） |
| `MainActivity.reconcile()` | 汇聚端一时找不到时不再立刻停采集：给 6 s 宽限期（`HUB_MISSING_GRACE_MS`），期间用上次记住的 `hubIp / hubPort`（`rememberedHub()`）兜底续跑，超时才停 |

`tests/test_graph.cpp` 新增两个回归用例：`Graph_MergeKeepsLocalHubWhenRemoteHubEmpty`、
`Graph_MergeAdoptsRemoteHubWhenLocalHubEmpty`（后者同时钉住「version 只涨不降」）。

## 图标（v0.7 已修好）

图标和桌面端统一为「深空底 + 暖金恒星 + 两颗行星」，由 `tools/gen-icon.py` 一套母版派生。

v0.5 及以前启动器里只剩「一团白光晕 + 四角星、行星看不见」，一度以为是系统「主题图标」去色，
**实际根因是自适应图标前景的密度桶**：前景 PNG 是 432×432（108dp × 4），却放在
`mipmap-anydpi-v26/`（`anydpi` 没有密度限定）里，系统按 mdpi 解释成 432dp 塞进 108dp 的图层，
于是只露出图像中心 `108/432 = 25%` —— 正中间就是恒星光晕，两颗行星在边缘、整片被裁掉。

修法：`tools/gen-icon.py` 现在**按密度各出一份前景**
（`mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` = 108/162/216/324/432，
`safe=0.62` 把主体收进安全区），`mipmap-anydpi-v26/` 里那份 432 PNG 已删除。
Android 13+ 的 `<monochrome>` 单色图层保留（`res/drawable/ic_launcher_monochrome.xml`），
系统开「主题图标」时按它重绘——那时**本来**就是单色，属预期行为。

如果装 v0.7 后图标仍不对：先看界面顶部版本行是不是 `v0.7 · 星图`（不是就装到旧包了），
再换一下桌面布局或重启桌面清缓存。

## 手机当汇聚端时闪退（v0.8）

v0.7 交付后用户报「手机断开、桌面再邀请、把电脑设为汇聚端时手机闪退」，堆栈是：

```
java.lang.RuntimeException: Unable to start service com.audiohub.source.CaptureService ...
  Caused by: java.lang.SecurityException: Starting FGS with type mediaProjection ...
```

这里其实串着两个不同的崩溃，都已修掉。

**① 采集授权被复用（Android 14 只给一次）**。堆栈落在 `CaptureService.startForegroundNotification()`。
旧逻辑在「汇聚端变了」时先 `stopCapture()` 停掉旧采集，再拿之前存下的 `resultData` 重新起服务；
但 Android 14 起 `MediaProjection` 授权是**一次性令牌**，服务一停就作废，复用会在这里被系统拒绝，
异常没人接 → 进程闪退。改法：

| 位置 | 改法 |
|---|---|
| `CaptureService.handleStartCommand()` | 已经在采集时**只换 UDP 发送目标**（新 socket + 新 IP/端口/通道号），复用当前 `AudioRecord` / `MediaProjection` 会话，并给旧汇聚端补发 `CONN-BYE`；不再 stop+restart |
| `CaptureService.workerLoop()` | 每个包按当前 `address / port` 重建 `DatagramPacket`（原先是构造一次），否则改目标后还发旧地址 |
| `MainActivity` | 授权只用一次：启动成功后立刻 `clearProjectionAuth()`；断开 / 停止 / 启动失败也清。以后再需要采集只能重新点「送入」 |
| `CaptureService.startForegroundNotification()` | 整段兜 `Throwable`，失败写 `sLastError` 后 `stopSelf()`，不再让异常冒到主线程 |

**② 前台服务义务没人履行**（这条才是「手机当汇聚端」那条路径的直接原因）。`setLocalHub()` 先起了一次
`ReceiverService`，紧接着 `refresh() → reconcile()` 发现 `g.isHub() && !hubRunning` 又起一次；
第二次命中「已经在本端口收流 → 直接 return」的幂等分支，**早退发生在 `startForeground()` 之前**。
`startForegroundService()` 一旦发出，系统就开始 10 s 倒计时，没人履行义务就抛
`ForegroundServiceDidNotStartInTimeException` 杀进程。改法：`ReceiverService.handleStartCommand()`
把 `startForegroundNotification()` 提到**所有早退分支之前**（`ACTION_STOP` 除外，它是 `startService()`
下发的、没有前台义务），另外整个 `onStartCommand` 外面再加一层 `Throwable` 保险。

定向验证（模拟器）：连续两次 `am start-foreground-service ... ReceiverService -a ...START` 后
`pidof com.audiohub.source` 仍返回进程号、`logcat -b crash` 为空、
`dumpsys notification` 里有 `id=43 / channel=audiohub_receiver / title=AudioHub 正在接收`。

## 手机当汇聚端收不到声音（v0.9 修复）

用户报「切换到手机为汇聚端时，桌面端的声音没传过来，手机显示已接收 0 路」。

根因**不在手机侧**，而在电脑的音源进程（`apps/source_main.cpp`）的握手方式：

```cpp
hs.SetPeer(opt.hub_ip, opt.hub_port + 2);   // 内部 ::connect()
hs.SendToPeer(req...);
hs.ReceiveFrom(...);                        // ← 永远超时
```

UDP 的 `connect()` 不是"连上"，只是让内核**按对端 ip:port 过滤收到的包**。
手机 `InviteListener` 回 `CONN-ACK` 时用的是绑定在 `音频口+2` 的那**同一个**套接字，
经过 NAT / AP 中继 / 多网卡时源端口会被改写，于是回包源地址和电脑记录的
`ip:port` 对不上 → 内核**静默丢弃** → `recvfrom` 一直超时 → 打印「对方未在 10 秒内
确认连接」并退出。发送侧看起来一切正常（包都发出去了），所以这个 bug 极难从界面
上看出来，只能看到手机永远「已接收 0 路」。

实测对照（Python，同一条链路上就是一个通一个不通）：

| 套接字 | 结果 |
| --- | --- |
| 未 `connect`，`sendto` + `recvfrom` | 1 秒内收到 `CONN-ACK\|…\|1` |
| `connect(('127.0.0.1',47002))` + `send` | 6 次全部超时 |

修法：握手改成 `SendToAddr` + `ReceiveFrom`，用回包**内容**（`CONN-ACK` 前缀）判断成功，
不再绑定来源地址。同样的道理也适用于 hub 侧（它本来就用 `SendToAddr`）。

配套修的一处：`build.sh` 以前只把 `audiohub-hub.exe` 同步到 `ui/bin/`，**没有同步
`audiohub-source.exe`**。于是改完 `apps/source_main.cpp` 之后，桌面端仍在 spawn
几周前的旧副本 —— 修了也等于没修。现在两个都同步。

## 汇聚端接受新音源（v0.9）

`InviteListener` 处理 `CONN-REQ` 的放行条件是「图谱成员」或「`ROLE|hub` 的 60 秒兼容窗口」。
这两条都依赖"来源 IP 已经在图谱里"，而这件事本身不稳：DHCP 换了地址、过了 NAT、
对方刚重连还没同步上图谱，`GraphStore.hasNodeIp(fromIp)` 都会返回 false —— 于是请求被丢进
待确认列表，声音一个都进不来（汇聚端最核心的职责就是收各家音源）。

现在多一条放行：**本机是汇聚端时无条件 ACK**。这与 PC 侧一致（hub 默认 `auto_accept = true`）。

## 握手日志（v0.9）

`InviteListener` 以前**一行日志都没有**，排查"为什么手机不回 ACK"只能靠猜。
现在关键分支各打一条（只在握手/邀请时触发，不是每包）：

```
adb logcat -s AudioHubInvite
D AudioHubInvite: CONN-REQ 来自 10.0.2.2:63168 名字=PC-SRC stream=7 成员=false 自动同意=false 本机是汇聚端=true
D AudioHubInvite: 已回 CONN-ACK 给 10.0.2.2:63168
```

## 退出图谱共识 / 断开不再清空图谱（v0.10）

三条用户反馈其实是同一个根因：「全部断开」之后本机仍在参与图谱共识。

`resetSession()`（断开所有连接时调用）会把图谱重置成只剩自己、`hubId` 清空、`version` 归零，
协议 §4.6 只要求「不主动广播」。但**只做到不发是不够的**：对端还在每 3 秒周期广播它自己的旧图谱，
本机一合并就把刚清掉的节点全捞回来（节点是纯并集，从不删除）。于是：

| 现象 | 机制 |
| --- | --- |
| 一方断开就清空图谱 | 本机清空 + 对端灌回，画面反复 |
| 退出后又被拉回控制界面，只显示自己的汇聚端 | `MainActivity` 的 `g.nodes.size() >= 2` 被灌回来的图谱满足 |
| 反复切换汇聚端后没声音 | 幽灵帧抢走 hubId，谁也不发流 |

修法（两端对称）：

1. **显式退网状态**：`GraphStore.detached`，`resetSession()` 置 true；
   `mergeFromFrame()` 开头 `if (detached) return false;` —— 会话结束期间不合并任何远端帧。
2. **重新入网才恢复**：`GraphStore.reattach()` 在四处显式入网动作里调用 ——
   `setLocalHub()`（设自己为汇聚端）、`pickRemoteHub()`（手动连入 / 同意邀请）、
   `acceptPending()`（同意待确认请求）、切换汇聚端对话框。
3. **广播也拦住**：`InviteListener.sendBroadcastNow()` 在 `GraphStore.isDetached()` 时直接返回，
   避免把「只剩自己」的图谱推给对端（对端会把汇聚端认成一台北经离开的机器）。
4. **控制屏判定改看在线节点**：`showControl` 原用 `g.nodes.size() >= 2`，改为
   `onlinePeers >= 1 && hasHub`（`onlinePeers` 用 `GraphStore.isOnline()` 逐个判，
   排除本机）。残留的离线节点不再把界面拉进控制屏。

PC 端是同一套（`src/hub_core.cpp` 的 `graph_detached` + `GraphReattachLocked()`，
`ui/renderer.js` 的 `onlinePeers` / `hubAlive`，`ui/main.js` 的汇聚端未知宽限期
`HUB_UNKNOWN_GRACE_MS` 与新目标稳定期 `PC_SOURCE_TARGET_STABLE_MS`）。

验证：模拟器上「手机连入 → 点断开所有连接」，hub 的 `/api/state` 图谱
`v`、`hubId`、`nodes` 三项前后完全不变（不再被清空也不被灌回）；`AUDIOHUB_E2E_PORT=19337 node tools/ui-e2e.js`
ALL CHECKS PASSED。

## 反复切换汇聚端后没声音（v0.11：采集会话暂停/恢复）

用户反馈：「还是仅有在首次连接时有声音」。根因不在混音、也不在图谱，而在**采集会话的
生命周期**：`reconcile()` 发现本机被设成汇聚端时调 `stopSource()`，而 `stopSource()` 会
`clearProjectionAuth()` —— 把 Android 14 **一次性**的投屏授权销毁。等用户再切回「本机当音源」，
`hasProjectionAuth()` 已经为 false，`reconcile()` 只打一句「本机已设为音源，开始采集后生效」，
`CaptureService` 永远不会启动，于是**只有第一次链接时有声音**。

`CaptureService` 现在把「停发送」和「销毁投屏会话」拆成两件事：

| 动作 | 用在哪 | projection | AudioRecord |
| --- | --- | --- | --- |
| `ACTION_STOP` → `stopCapture()` | 用户点停止 / 服务销毁 / 失败收尾 | `unregisterCallback` + `stop()` + 置空 | stop + release |
| `ACTION_PAUSE` → `stopCaptureKeepProjection()` | 本机暂时被设为汇聚端 | **保留** | **保留**（线程继续读，只是不发包） |

暂停的实现是**一个开关**，不是拆掉采集链路：

- `CaptureService` 里新增 `private volatile boolean sending;`。`workerLoop` 每凑满一包先看它：
  `if (!sending) { fill = 0; … continue; }` —— 采集线程继续跑（保持 AudioRecord 的内部节奏），
  但一个包都不发出去。`running` 在暂停期间**保持 true**，所以线程不会退出、服务不会被销毁。
- `stopCaptureKeepProjection()` 只做：`sending = false` →（若之前在发）连发 `CONN-BYE` →
  恢复媒体音量 → 关 socket、清目标 → `sRunning = false`、`sStatus = "已暂停（投屏会话保留，切回音源即恢复）"`。
- 切回音源时走的是 **retarget 分支**（不是"重建会话"）：换 socket/地址后补 `sending = true; sRunning = true;`，
  下一包立刻恢复发送。全程不弹授权框、不重建 `AudioRecord`、不重启进程。

**为什么不是"暂停时停掉 AudioRecord、恢复时新建一个"**（最初就是这么写的，模拟器上必崩）：

```
Fatal signal 6 (SIGABRT) in tid (audiohub-captur)
Abort message: 'releaseBuffer: mUnreleased out of range,
  !(stepCount:240 <= mUnreleased:112 <= mFrameCount:3072), BufferSizeInFrames:3072'
  at android.media.AudioRecord.read(AudioRecord.java:1711)
  at com.audiohub.source.CaptureService.workerLoop(CaptureService.java:482)
```

只要还有线程阻塞在 `read()` 里，`record.stop()` / `record.release()` 都会踩到 AudioRecord 内部
「已借出未归还的缓冲」记账；`join(800)` 超时就是这个竞态窗口。先后试过三种写法都仍 abort：
① `workerLoop` 改成参数化避免读被覆盖的字段；② resume 前先 `join(800)` 再建新 record；
③ 两个收尾方法改成「先 `stop()` → 再 `join()` → 最后 `release()`」。
**结论：一个会话只用一个 `AudioRecord`、一条采集线程，是唯一稳定的用法**——这也是停发送开关方案的由来。

代价与边界：
- 暂停期间 `sStatus = "已暂停（投屏会话保留，切回音源即恢复）"`，通知栏仍显示「AudioHub 正在采集」——
  这是保活投屏会话必需的，系统不允许没有前台通知的 MediaProjection；
  同时 `pausing` 标志让 `workerLoop` 收尾时**不** `stopSelf()`，否则 `onDestroy` 会把 projection 一并销毁。
- 用户主动「停止」或「断开所有连接」仍走 `ACTION_STOP`，投屏会话正常销毁。
- 若系统在此期间把投屏会话收回（用户在下拉栏点了停止录制），`sHasProjection` 变 false，
  `reconcile()` 会退回「本机已设为音源，开始采集后生效」并按需重新弹授权。
- `reconcile()` 的 `isHub` 分支**不再**把 `sourceIntent` 置 false：那是「用户想让本机送声」的意愿，
  不是「此刻正在送」的状态。置 false 会导致切回音源后再也不会自动起采集（本轮的第二个真 bug）。


## 不断音切换：音源进程的 RETARGET（v0.11）

桌面端对应的一半在 `apps/source_main.cpp`。旧实现把目标 `connect()` 死在启动时，切换汇聚端
只能 kill 整个进程重新 spawn；而不重启就换目标，是"不断音"的结构性前提。

现在音源进程多了一个**本地控制口**（`--control-port`，Electron 侧固定
`ui/main.js: SOURCE_CONTROL_PORT = 47990`），一行一条命令：

| 命令 | 作用 |
| --- | --- |
| `RETARGET <ip>:<port> <streamId>` | 先给旧目标发 `CONN-BYE`，再改发送目标，`generation++` 触发重新握手 |
| `STATUS` | 回 `STATUS <ip>:<port> <streamId> <sent_packets>` |
| `STOP` | 回 `OK stopping` 并让进程自己走退出路径（恢复静音） |

握手也从"启动时阻塞 15 次，失败就 `return` 退出进程"改成**非阻塞重试**：发送线程发现
`generation` 变了就复位确认状态，每 2 秒补发一次 `CONN-REQ`，进程永不因为对方没起来而退出。
这正是修掉「切到一台还没准备好的汇聚端就再也回不来」的关键。

## 手机播放有杂音 / 电流音（v0.13 修好）

**现象**：电脑送来的声音在手机上播放时有明显杂音、持续"电流音"。

**先排除的**（都不是原因）：

- AudioTrack 本身：`dumpsys media.audio_flinger` 显示 `Underruns = 0`，缓冲 3272/4360 稳定；
- 采样格式：48000 Hz / 2ch / 16bit，与线格式一致，`mix` 归一化与软限幅公式与 C++ 端逐行对齐；
- 网络：模拟器内 ping 20 包 0 丢包（1.2~6.5 ms）；
- 硬削顶：软限幅用的是与 C++ 相同的 `threshold + headroom*tanh(...)`，阈值处连续。

**真因：抖动缓冲的漂移补偿在"整帧硬切"**

临时诊断日志（每 500 ms 打一次）实测水位：

```
诊断 路数=1 水位ms=183 欠载帧=2880 总线dBFS=-15.7
诊断 路数=1 水位ms=192 欠载帧=2880 ...
```

`PREFILL_MS = 60`、死区 ±40 ms → 目标区间是 **[20, 100] ms**，而实际水位稳定在 **147~192 ms**。
也就是说偏差一直远超死区，于是每次冷却（12 块 = 60 ms）就触发一次 `step = 241`；
而 `step > frames` 的老实现是"读 241 帧、只输出前 240 帧"——**每 60 ms 硬切掉 1 帧**，
一秒 16~17 次。这种波形上的台阶就是听感里的"咔哒/电流音"。

水位为什么偏高：`PREFILL_MS=60` 是延迟优先的偏小值，而 Android 的播放线程调度粒度
比 5 ms 粗，实际摆幅常达 ±40~60 ms，60±40 的死区根本框不住它。

**修法**（`JitterBuffer.java`）：

1. 读位置改成**分数游标 `pos_`**（`double`），每块按速率前进；
2. 取样做**线性插值**（用当前帧与下一帧按小数部分混合），彻底没有"整帧丢弃"；
3. 死区放大到 ±80 ms（配合 250 ms 容量），常见调度抖动直接落在死区内、完全不调速；
4. 速率上限 ±0.5%（48 kHz 下 240 帧/秒），比真实晶振偏差（±20~100 ppm）大一个数量级；
5. 尾部兜底改为补静音，不再"重复上一帧"（重复一个采样同样会留台阶）；
6. 顺带修掉一处双倍推进：推进 `readPos`/`valid` 已按 `pos_` 整数部分做过，
   尾部不能再 `readPos += n`（那样会每块丢半块音频）。

**验证**（模拟器 + 真实 App + `--tone 440` 正弦源）：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 缓冲水位 | 147~192 ms | **85~100 ms**（落在 60±80 死区内） |
| 欠载帧 | 持续增长 | **锁定 2880 不涨**（启动瞬间的历史值） |
| AudioTrack underruns | 0 | **0** |
| 总线电平 | -15.7 dBFS | -15.7 dBFS（一致，说明没改音量） |

回归：`node tools/audioflow-test.js --rounds 4` → 15/15 PASS；
`ui-e2e` ALL CHECKS PASSED；`bash build.sh` 断言 10970 / 失败 0 / 88 用例。

**边界**：以上是**模拟器**上的量化验证。真机的播放线程调度与音频 HAL 不同，
建议在真机上再听一次；若仍有残留杂音，下一个怀疑对象是手机端的
`aaudio` 低延迟路径与采样率转换，而不是这个缓冲。


## 切换汇聚端到手机后收不到声音（v0.12）

**现象**：把汇聚端切到手机后，电脑一直在发，手机一路也收不到（「已接收 0 路」）。

**根因**：切换汇聚端时只改了图谱，没有通知被选中的那台设备。手机端的 ReceiverService
不是"图谱里我是 hub 就自己起来"，而是**收到 `ROLE|hub` 才启动**；`SwitchGraphHub()`
以前只 `GraphScheduleDoubleLocked()` 广播图谱、从不发 ROLE。

**修法**（`src/hub_core.cpp`）：

- 把发 ROLE 的逻辑收敛到 `Impl::SendRoleTo()`（原 `HubCore::SendRoleCommand` 改为委托它），
  这样锁内的 `SwitchGraphHub()` 也能用同一份实现。
- `SwitchGraphHub()` 在解锁后：
  - 目标是对端 → `SendRoleTo(hub_ip, hub_audio_port, true, "", 0)`，即 `ROLE|hub`；
  - 目标是本机 → 对所有其它节点 `SendRoleTo(ip, port, false, hub_ip, hub_audio_port)`，
    即 `ROLE|source|ip:port`，让原来当音源的手机改回送流。
- 注意发送必须在锁外：`UdpSocket` 不能持锁调用。

**验证**（模拟器 + 真实 App）：切到手机后
手机 `ss -lunp` 出现 `UNCONN *:47000`（ReceiverService 起来了），
从宿主跑 `audiohub-source.exe --hub 127.0.0.1:47000 --tone 440` →
`通道 7 · 已确认`、`发送 201 pkt/s`，手机 UI 变「已接收 1 路」。

## 邀请地址解析（v0.12）

**现象**：「不加 `:47000` 后缀，手机上接不到申请」。

**结论**：PC 侧对「只填 IP」和「IP:47000」本来就等价（内核 `/api/invite` 缺 `audioPort`
时回落 47000，实测两种写法产生完全相同的请求）。真正能让人踩到的是**输入形式**：

- 中文输入法打出的全角冒号 `：`（`192.168.1.44：47000`）—— 老代码 `lastIndexOf(':')`
  找不到半角冒号，整串当成 IP 发出去；
- 从别处复制的空格、尾随 `/`、`http://` 前缀。

**修法**（`ui/renderer.js`）：新增 `parsePeerAddress(text)`，统一归一化全角字符、
去空白、剥 `scheme://` 与尾斜杠，解析失败回落 47000；两处邀请入口（连接页 + 控制页）
都改用它，成功提示里带上最终解析出的 `ip:port`，失败提示显示内核返回的原因。

**验证**：真实 Electron 里对 6 种写法（无端口 / 带端口 / 前后空格 / 全角冒号 /
`http://.../` / 端口写错）调用 `parsePeerAddress`，全部得到 `{ip:"192.168.1.44",port:47000}`；
真实 App + 模拟器上只填 IP 点邀请 → 界面「已向 127.0.0.1:47000 发出邀请」，
手机 logcat `收到 CONN-INVITE`、弹出「连接请求」。

## 测试方法：`tools/audioflow-test.js`（v0.11）

用户要求「设计一套测试方法，要求不管怎么切换，都要保证声音流通」。这是一套**不依赖 UI、
不依赖声卡、可重复、可直接进 CI** 的黑盒验收：

```bash
node tools/audioflow-test.js              # 默认 5 轮切换
node tools/audioflow-test.js --rounds 10  # 更多轮
node tools/audioflow-test.js --keep       # 保留日志
```

- **环境**：两个真实 hub 实例（A: `--port 48510 --http-port 4795`、B: `48520 / 4796`）
  + 一个 `--tone 440` 音源进程（内置正弦，不碰声卡，信号完全可重复）。
- **观测量**：只读 `/api/state` 的 `busLevel`。**刻意不看 `kbps`** —— 包在涨但总线仍是
  -120 dBFS 的情况（抖动缓冲没蓄满）恰恰是用户说的「没声音」，用码率判断会漏掉。
  判据是「连续两次采样都 > -60 dBFS」才算出声。
- **用例**：

| 用例 | 断言 |
| --- | --- |
| T1 | 首次连接后目标 hub 出声 |
| T2 | 切换后**旧** hub 3 秒内静音（不会两头同时响） |
| T3 | 反复来回切 N 轮，**每一轮都恢复出声**（对应用户报的现象） |
| T4 | 全程音源进程 **pid 不变、exitCode=null**（不断音的结构性前提） |
| T5 | 改投到不可达端口后进程不退出，恢复可达目标后自动出声 |
| T6 | 停掉音源 → 汇聚端静音；重连后恢复出声 |

实测（`--rounds 6`）：**19/19 通过**，切换出声耗时 230–490 ms，旧 hub 静音 7–143 ms，
音源进程 pid 全程不变。

## 模拟器环境限制（影响验证方法，不影响 App）

本机（WHPX + Android 14 x86_64 镜像）在早期几轮里，qemu 会在启动音频采集的瞬间段错误退出
（Windows 应用日志 `出错应用程序名称： qemu-system-x86_64.exe / 异常代码： 0xc0000005`，
`adb logcat -b crash` 里没有 App 的 `FATAL EXCEPTION`，`/data/data/com.audiohub.source/files/`
也没有 `crash.txt` —— 是宿主 qemu 崩，不是 App）。

v0.11 这一轮改用**带窗口 + `-gpu angle_indirect`** 后，采集链路在模拟器上是可用的：
4 轮「切到手机 → 切回 PC」里 `sources[].packets` 每 2 秒增长 261–286 包（约 130–143 pkt/s），
App pid 全程不变，说明**采集、发送、暂停、恢复都在真实运行**，不再是"只能验界面"。

仍需真机复验的部分：真实音频路由（模拟器的 `AudioPlaybackCapture` 走 `REMOTE_SUBMIX`，
没有媒体播放时抓到的是静音）、厂商 ROM 的省电策略与后台限制。

另：`-no-window`（headless）在本机**更不稳定**，会在几次 `adb shell input` 后崩；调试时用带窗口的
`-gpu angle_indirect` 最稳。

### 跑测试前必须清场

`tools/audioflow-test.js` 和手工联调都会被残留状态污染，表现为「明明修好了却全红」：

```bash
adb shell am force-stop com.audiohub.source     # 手机 App 会把 GRAPH 帧灌进测试 hub，抢走 hubId
tasklist | grep -i audiohub                      # 残留的 hub/source 进程占着测试端口并继续广播旧图谱
# 逐个 taskkill /F /PID <pid>
```

另外 `aapt2` 要用 `android-15/aapt2.exe`（android-35 那份会报 `failed to open APK: I/O error`）；
dex 里的中文常量在 GBK 控制台下用 `strings | grep` 会假阴性，判定要以运行期 logcat 为准。


## 启动杂音 / 没连接就进控制台（v0.14）

用户报（2026-11-02）：「现在启动会有杂音」「而且没有连接直接就进控制台了」。

### 启动杂音：抖动缓冲的"预填"是假的

`JitterBuffer.push()` 首包锚定曾经写成：

```java
readPos = pos - PREFILL_MS * 48L;   // 往回拨 60ms，"预填 60ms 再开播"
pos_ = readPos;
```

问题在于往回拨出来的那 60 ms 里**一帧数据都没有**（`valid[]` 全是 false），
而 `occupancyFrames()` 算的是 `highestWritten - readPos` —— 它把这 2880 帧
不存在的预填算成了有效数据。于是 `ReceiverService.playLoop()` 的开播条件

```java
if (jb.occupancyFrames() < frames) continue;
```

在只收到**一个包**时就被满足，播放线程立刻开拉，前 60 ms 全部走
`underrunFrames++` 补静音的台阶 —— 听感上就是起播时"嗒"一下的杂音。

这个账在 v0.13 之前一直存在，但被另一处 bug 掩盖了：`pull()` 尾部有一句
`readPos += n` 与按 `pos_` 的推进重复（双倍推进，"每块丢半块音频"）。
v0.13 删掉那句是对的，代价是这个记账错误暴露了出来。

修法（两处，缺一不可）：

1. `push()` 首包只摆好游标，**不动 readPos**：`readPos = pos; pos_ = pos;`
   —— 让 `occupancyFrames()` 数的就是真数据。
2. 开播条件改成新的 `JitterBuffer.prefilled()`：
   `return highestWritten - readPos >= PREFILL_MS * 48L;`
   —— "预填多少"只有一个真源（`PREFILL_MS`），`ReceiverService` 不再自己
   用一块 `frames` 去猜。

### 没连接就进控制台

`showControl` 的判据是
`!userLeft && (hubRunning || srcRunning || (onlinePeers >= 1 && hasHub))`。
`GraphStore.hubId` 不持久化（重启后为空 → `hasHub` 为 false），
`userLeft` 默认读 `prefs.getBoolean("user_left", true)`，理论上都该让启动落在连接屏。
若仍出现，优先查 `userLeft` 是不是被上一条 `ROLE|source` 的失败路径写成了 false
（见下一节 —— 那条路径以前会 `setUserLeft(true)`，方向相反，但同样是
"把自动化指令当用户操作"造成的状态污染）。

### 来回切换时踢出控制台 + 切到手机彻底没声音

两个现象同一个根因：**`ROLE|source` 走了用户手动连接的代码路径**。

`MainActivity` 收到 `ROLE|source` 时原本调 `pickRemoteHub(d)` —— 那是"用户主动
连一台设备"的实现，它做两件在这里有害的事：

1. 先 `startService(ACTION_STOP)` 把 `ReceiverService` 停掉
   （用户以为只是换目标，实际把接收端拆了；对端再把汇聚端切回本机时，
   手机要重新起服务，中间那一拍就是"彻底没声音"）；
2. `respondToInvite()` 是一个 30 次 × 1 秒的 UDP 握手，**任何一次 ACK 抖动
   都会走到 `setUserLeft(true)` + Toast「对方还没有同意连接」+ 退回连接屏**
   （用户看到的「手机退出控制台，说桌面端没同意连接」）。

ROLE 是汇聚端那边的决定，不是用户操作；反复切换时对端会连发好几次。

修法：新增 `followRemoteRole(Discovery.Device d)`，只做三件事 ——
记住目标（`hubIp/hubPort/hubName` + `addManualPeer`）、
`setUserLeft(false)` + `GraphStore.reattach()` + `sourceIntent = true`
（会话语义成立，等 `reconcile` 按图谱决定起/停采集）、
**不动 `ReceiverService`**（对端随时可能切回来，接收端要保持活着）。
`pickRemoteHub` 仍只给"用户手动连接 / 同意邀请"用。

## 切换后先有声带杂音、然后彻底没声（v0.15）

用户报（2026-11-02）：「切换后，开始有点声音并伴随着杂音，然后就彻底没声了」。
这是 **v0.14 修复引入的二次效应**，不是新问题。

### 根因：抖动缓冲停在旧时间轴上

`JitterBuffer.push()` 有两条"正常丢包"规则：

```java
final long end = pos + frames;
if (end <= readPos) return false;                    // 已经全部过期
if (pos >= readPos + capacityFrames) return false;   // 超出容量窗口
```

切换汇聚端后，对端可能换了一条音频时间轴 —— `apps/source_main.cpp` 的
`sample_pos` 在音源**进程重启**后会从 0 重新开始（`RETARGET` 不重置它，但
重启会）。此时本缓冲的 `readPos` 还停在上一条时间轴的大序号上，于是
**每一个包**都命中上面两条之一被静默丢弃：`highestWritten` 永不前进 →
`prefilled()` 永远 false → `playLoop` 一直 `continue` → 彻底没声。

"前面有点声音并伴随杂音"是另一件事：旧时间轴残留在 `valid[]` 里的数据
被读指针按 `floorMod` 绕回来放了出去。

### 为什么 v0.14 之后才出现

以前 `ROLE|source` 走 `pickRemoteHub` → `ACTION_STOP` → `stopAll()` →
`streams.clear()`，**顺手把旧缓冲清了**，这个 bug 被掩盖。v0.14 让 ROLE 不再
停接收服务（对端切回来要能立刻收流，"不断音"必须这么做），这个巧合就没了。
换句话说：v0.14 的修法是对的，但它拆掉了一层意外的保护。

### 修法

1. `push()` 首包分支同时初始化 `readPos / pos_ / highestWritten = pos`
   （配合 v0.14 的"不倒退"语义）。
2. 在两条丢包规则**之前**加时间轴断档检测，命中则重锚：

   ```java
   if (end <= readPos) {
       // 小幅乱序（网络重排几帧）正常，整整落后半个窗口才是换了时间轴
       if (readPos - end > capacityFrames / 2L) { reanchorLocked(pos); }
       else { return false; }
   } else if (pos >= readPos + capacityFrames) {
       reanchorLocked(pos);
   }
   ```

   判据取容量窗口一半（容量 `48000/4` = 12000 帧 = 250 ms，半个窗口 6000 帧）。
   正常推流时 `readPos` 永远跟在 `pos` 后面 ~60–140 ms，两种越界在正常流里
   根本不会出现，因此不会误伤。
3. 新增 `reanchorLocked(long pos)`，其中 **必须 `java.util.Arrays.fill(valid, false)`**。
   不清的话读指针在新时间轴上按 `floorMod` 绕回旧槽位，会把上一段会话的音频
   当成本次样本放出来 —— 听感正是"先有一小段带杂音的声音，然后才是静音"。
   然后设 `readPos / pos_ / highestWritten = pos`、`starveTicks = 0`、`started = true`。

### 回归测试

新增 `android/verify/JitterCheck.java` + `run-jitter-check.ps1`
（纯 Java，不依赖 android.*，用本机 JDK 8 直接跑）：

```
powershell -ExecutionPolicy Bypass -File android\verify\run-jitter-check.ps1
```

| 用例 | 断言 |
| --- | --- |
| T1 | 切换前正常收流（`prefilled()` 为真） |
| T2 | 时间轴重置后仍能攒够预填 ← **本 bug 的直接回归** |
| T3 | 重锚后能取出有效音频（10/10 块非静音） |
| T4 | 正常推流 2000 包无误判（`rejected == 0`） |
| T5 | 小幅乱序仍被丢弃，不触发重锚 |
| T6 | 重锚后不残留旧时间轴数据 |

实测 `ALL PASS`。注意两个坑：`JitterBuffer` 是 package-private，测试类必须在
`com.audiohub.source` 包内；`javac` 必须带 `-encoding UTF-8`（否则中文注释报
GBK 映射错误）。另外 `.ps1` **必须带 UTF-8 BOM**，否则 Windows PowerShell 5.1
按 GBK 解析中文，引号配对会失败并报 `The string is missing the terminator`。

## 反复切换时崩溃：前台服务 10 秒义务（v0.16）

崩溃栈（用户 2026-11-02 贴出，`main` 线程）：

```
android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
Context.startForegroundService() did not then call Service.startForeground():
ServiceRecord{f9ff5f8 u0 com.audiohub.source/.ReceiverService}
```

`Context.startForegroundService()` 一发出，系统立刻开始 10 秒倒计时；到点没看到
`Service.startForeground()` 就抛这个异常并**把整个进程杀掉**。

### 这不是 v0.7 的修复失效，是它的漏网路径

v0.7 只解决了「同一个 START 被下发两次、第二次命中幂等分支提前 return」这一条：
当时把 `startForegroundNotification()` 提到了 `ACTION_START` 分支的幂等早退之前。
但仍然留着三个洞，任何一个都足以让义务没人履行：

1. `intent == null` —— 进程被系统回收后补投的 start，首行就 `return START_NOT_STICKY`。
2. 未知 action —— 同样早退。
3. **`ACTION_STOP` 分支的裸 `stopSelf()`** ← 本次的真凶

第 3 条最隐蔽：`ACTION_STOP` 本身是 `startService()` 下发的，**确实没有前台义务**，
所以在那里不调 `startForeground()` 看起来完全正确。但 `stopSelf()` 的语义是
「丢弃所有尚未处理的 start 请求」——包括那条**已经由 `startForegroundService()`
发出、系统已经在倒计时**的 START。请求被丢掉，义务就永远没人履行，10 秒后照样被杀。

反复切换汇聚端时正是这个组合：`MainActivity.reconcile()` 的 `isHub` 分支刚下发
START（`safeStartForegroundService`），紧接着 hub 角色变化又下发 STOP，
STOP 里的裸 `stopSelf()` 把 START 连人带义务一起抹掉。

### 修法

两个 Service（`ReceiverService` / `CaptureService`）都改成同一套结构：

- `onStartCommand` **首行**记 `pendingStartId = startId;`，然后**无条件**先履行义务
  （`try { startForegroundNotification(); } catch`），把结果放进 `boolean fg`；
- 再调 `handleStartCommand(intent, startId, fg)` 走各分支；`if (!fg)` 就收尾返回，
  不留一个没有前台的线程等着被系统回收；
- `intent == null` 时**只 return，不再 stopSelf** —— 服务可能本来就在正常收流；
- 所有裸 `stopSelf()` 改成 `stopSelfResult(startId)`：只有当这条就是最新一次请求时
  才真的停；**有更新的请求排队时服务继续活着**，等它的 `onStartCommand` 去履行义务。

`CaptureService` 与 `ReceiverService` 的唯一差别：采集允许前台起不来
（Android 14 复用过期授权时 `startForeground` 会抛 `SecurityException`），
所以它的顶部 catch 只写 `sLastError`/`sStatus` 把原因交给界面，不直接返回。

顺带修了 `ReceiverService.stopAll()` 的顺序：原来是先 `join(400)` 再关 socket /
释放 `AudioTrack`，但收流线程正阻塞在 `dataSocket.receive()`、播放线程可能阻塞在
`track.write()`，两者都要靠超时才能醒 —— 最坏在**主线程**上耗 800ms。
而主线程正是履行那 10 秒义务的地方。改成先 `close()`/`stop()+release()` 掐断阻塞点，
再 `join(200)`。

### 验证

- 全部 Android 源对着 `android-35/android.jar` 编译通过（`javac -encoding UTF-8`）。
- 模拟器冷启动正常显示，无崩溃。
- 用 `curl -X POST -d '{"ip":"127.0.0.1","audioPort":47000,"hub":1}' …/api/role`
  把手机设为汇聚端，logcat 出现
  `Background started FGS: Allowed [... act=com.audiohub.source.receiver.START ...]`，
  未再出现 `ForegroundServiceDidNotStartInTimeException`。
- 本轮模拟器可用（前几轮反复 qemu `0xc0000005`），但也只走到「起前台 + 不崩」，
  完整的反复切换压力仍需真机复验。

### 排查这类问题的顺序（下次直接照做）

1. 先看崩溃栈里点名的是**哪个** Service（`ReceiverService` / `CaptureService`）。
2. `grep "startForegroundService" MainActivity.java` 找出所有下发点，
   再 `grep "ACTION_START\|ACTION_STOP"` 列出所有 Intent 构造点。
3. 检查该 Service 的 `onStartCommand`：**有没有任何一条路径在
   `startForeground()` 之前 return**（含 `intent == null`、未知 action、幂等早退）。
4. 检查有没有裸 `stopSelf()`：它会丢掉排队中的 start 请求及其义务。
5. 顺带看这条路径上有没有主线程阻塞（`join` / `sleep` / 同步 IO）——
   它们会吃掉 10 秒额度，让边缘情况变成必现。

## 老是意外断开：图谱广播被 Activity 带走（v0.17）

**症状**：用一阵子就"断一下"——手机明明还在放，汇聚端却把本机判成离线，
界面弹「xxx 已离线」，严重时直接弹回连接屏。转个屏、切一次后台都可能触发。

**根因**：全网 GRAPH 帧的唯一生产者 `InviteListener` 是 Activity 的子组件，
它的生命周期被挂在 `MainActivity` 上：

| | 位置 | 问题 |
| --- | --- | --- |
| 启动 | `MainActivity.java:241` `onCreate` | 只有 Activity 起来才广播 |
| 停止 | `MainActivity.java:282` `onDestroy` | **无条件**停，连 `isChangingConfigurations()` 都不判 |

而 `onDestroy` 里的另外两行是有守卫的（`stopService(CaptureService)` 走
`isFinishing() && !isChangingConfigurations()`），只有 `InviteListener.stop()`
和 `discovery.stop()` 是无条件的。于是：

    Activity 重建/被回收 → 图谱广播线程停 → 汇聚端 10 秒后（GraphStore.ONLINE_TIMEOUT_MS）
    graph_peer_last_seen_ms 过期 → nodes[].online = false → renderer 弹「已离线」
    → 若这台是 hub 或唯一在线 peer，networkUp 变 false → 弹回连接屏

**为什么"重复邀请"的猜测不成立**：`InvitePeer`（C++）只在 `/api/invite` 被调用，
`ui/main.js:389`/`:701` 与 `ui/renderer.js:1060`/`:1088` 全部是按钮点击；
手机端 `MainActivity.invitePeer` 也是按钮触发。代码里没有任何周期性或自动重发。

**修法**（两处一起改才完整）：

1. `AndroidManifest.xml` 的 `MainActivity` 加
   `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode|density|fontScale"`
   —— 转屏/深色模式/字体大小不再重建 Activity。
2. `MainActivity.onDestroy()` 引入 `final boolean reallyLeaving = isFinishing() && !isChangingConfigurations();`，
   `InviteListener.get().stop()` 只在 `reallyLeaving` 时执行（原来无条件）。

**顺带修的第二条**：`MainActivity.localIp()` 原来是自己写的一份"返回第一个非回环 IPv4"的简化版，
与 `Discovery.localIp()` 的完整过滤（wlan/eth/ap/en 优先，跳过 tun/ppp/rmnet/clat、
169.254./198.18./198.19./100.64./100.65.）不一致。而 `reconcile()` 每 400ms 用它比对
`GraphStore.selfIp()` —— 网卡枚举顺序一变（插拔热点/VPN/蜂窝唤醒）selfId 就漂移
→ `streamIdForSelf()` 变 → retarget → 先发 CONN-BYE → 又是一次"莫名断一下"。
现在 `MainActivity.localIp()` 直接委托 `Discovery.localIp()`。

**实测证据**（模拟器 Pixel，`ps -T` 看线程）：

| | 转屏前 TID | 转屏后 TID | 判定 |
| --- | --- | --- | --- |
| v0.16 | 2129 | **2157** | 线程被停又重建 |
| v0.17 | 2229 | **2229** | 线程未动 |

v0.17 压测 3 轮（转屏 + 切后台 + 回前台），进程 pid 与线程 TID 全程不变。

**回归**：`audioflow-test --rounds 4` 15/15、`ui-e2e` ALL CHECKS PASSED、
`build.sh` 断言 10970/失败 0/88 用例、`run-jitter-check` T1–T6 全过、
`run-spatial-check` 23182 项、`run-graphfit-check` 249 项。

**仍未解决的同族问题**：`InviteListener` 仍然挂在 Activity 上。用户按返回键退出后
广播仍然会停（`isFinishing()` 为真），这在语义上是对的；但如果希望"App 退到后台
仍保持图谱在线"，需要把 `InviteListener` 挪进 `ReceiverService`/一个独立前台服务。
这一轮没做，因为还没有明确的用户诉求。

## 邀请手机没反应 / 没连上就进控制台（v0.18）

**症状**（用户原话）：「桌面端邀请手机没反应，但进入控制台，退出再次邀请手机有反应，
手机同意后进入控制台但桌面端没进入控制台，手机端还显示桌面端没同意邀请」。

四个独立缺陷叠加，全部在本轮修复：

### 缺陷 1：邀请帧的地址可能是逗号列表（多网卡）

PC 发的 `CONN-INVITE|名字|ip:port|序号` 里，ip 段用的是 `local_ip_for_invite`，
而它来自 `LocalIPv4List()`（`src/udp_socket.cpp:237`）——多网卡机器上这是一个
**逗号分隔的列表**（WiFi + 网线同时在线时必有多个）。手机端 `InviteListener.java`
用 `lastIndexOf(':')` 一刀切，拿到 `"192.168.x.x, 10.135.x.x"` 整段当 IP 交给
`InetAddress.getByName` → 抛 `UnknownHostException` → `respondToInvite` 的
`catch (Exception e) { return false; }` **立刻失败**，连 30 秒重试都不走。

对照证据：图谱自节点早就用 `graph_self_ip = FirstIp(local_ip_for_invite)`
（`src/hub_core.cpp:398`），唯独邀请帧没用。

**修法**（两端都改）：
- PC `src/hub_core.cpp` `InvitePeer`：帧里的地址改用 `FirstIp(local_ip_for_invite)`。
- 手机 `ReceiverService.respondToInvite`：入口对 `pcIp` 做 trim + 取第一个逗号前的
  部分（防御旧版 PC 仍在发列表）。

**注**：本机开发环境只有一张可用网卡（WLAN），此缺陷在本机未实际触发，属读码推断 +
两端防御；用户真机若是双网卡则必踩。

### 缺陷 2：邀请后 PC「没连上就进控制台」（探针实证）

`InvitePeer` 发完邀请就把对端**占位入图**（`GraphAddPeer(peer_ip, "", port)`，
协议 §4.1 注释「邀请即占位入图」）。而 `/api/state` 判节点 `online` 的证据之一是
**mDNS 发现列表**（`apps/hub_main.cpp` 的 `discovered[].ip == ip`）——手机只要在
正常广播信标（它一直在广播，这与连没连上无关），占位节点就被判 `online=true`。
渲染端 `networkUp = onlinePeers >= 1 && hubAlive` 立刻成立 → **PC 跳进控制台，
而手机那边可能根本还没看到邀请**。

**探针实证**（`tools/probe-fake-online.js`）：假手机只发一帧信标、完全不响应邀请 →
v0.17 图谱出现 `online=true` 的占位节点（复现成功）；v0.18 同场景图谱里
**没有任何非本机节点**（修复确认）。

**修法**：删掉 `InvitePeer` 里的占位入图。对端真正入图发生在它发 CONN-REQ 被
自动同意时（那条路径本来就有 `GraphAddPeerLocked`）。`SendRoleTo` 里的占位入图
**保留**——ROLE 指令本来就是发给已确认连接的设备的。

### 缺陷 3：60 秒放行窗口过期 → 「对方还没有同意连接」（探针实证）

邀请后 PC 只放行对端 60 秒（`AllowPeer`：单 IP + `auto_accept_until_ms = now+60s`）。
用户在手机上读弹窗超过 60 秒才点「同意」→ 手机这时才发 CONN-REQ → PC 端窗口已过期，
CONN-REQ 掉进 pending 等人工确认（真实应用没有 `--auto-accept`）→ 没人点 →
手机 `respondToInvite` 30 秒超时 → 报「对方还没有同意连接，请稍后再试」。
另外连续邀请两台设备时，第二台的 AllowPeer 会覆盖第一台。

**探针实证**（`tools/probe-invite-window.js`）：真实启动参数（无 `--auto-accept`）下
邀请后等 61 秒再发 CONN-REQ —— v0.17 无 ACK（窗口过期），v0.18 收到
`CONN-ACK|AudioHub|1`（修复确认）。

**修法**：`AllowPeer` 改为 `std::set<std::string> invited_peers`（会话内不过期），
`PeerAllowedNow` 查集合，`ClearSession`（全部断开）时清空。

### 缺陷 4：邀请失败时前端只能显示笼统错误

`/api/invite` 失败时返回裸 `{"ok":false}`，渲染端只能显示「音频核心没有响应」。
现在失败返回
`{"ok":false,"error":"邀请帧发送失败：请确认对方 IP/端口正确、对方 App 在前台且与本机在同一网络"}`。

### 症状对照

| 用户症状 | 缺陷 | 证据级别 |
| --- | --- | --- |
| 邀请手机没反应 | 缺陷 1（多网卡逗号列表，本机未触发）+ 手机未在前台等 | 读码推断 + 两端防御 |
| 但进入控制台 | 缺陷 2（占位节点被 mDNS 证据判在线） | **探针实证** |
| 手机端显示桌面端没同意邀请 | 缺陷 3（60 秒窗口过期） | **探针实证** |
| 桌面端没进入控制台 | 缺陷 2 的反面：手机确认后 PC 才看到真实节点入图，属正常时序；缺陷 1/3 修复后握手一次成功，不再出现「手机进了 PC 没进」的分裂状态 | 推断 |

## 手机报「对方还没同意」却已经出声 / 随后退回连接界面（v0.19）

### 现象

用户原话：「现在是手机上会显示对方还没同意，然后就自己退出了，但实际上桌面端的音频
已经传递到手机里了」，补充「手机退出到连接界面」。

三件事同时发生，看起来互相矛盾：提示没同意、声音却是通的、界面自己退回连接屏。

### 根因：`pickRemoteHub` 无条件做「连到对方 hub」的握手

`MainActivity.pickRemoteHub(Device)` 是**同意邀请 / 设备列表 / 手动 IP** 三个入口的
公共实现，它总会调 `ReceiverService.respondToInvite(ip, audioPort, ...)`——那是「连到
对方 **汇聚端**」的握手：往 `audioPort + 2` 发 `CONN-REQ`，等对方 hub 回 `CONN-ACK`。

但入口的语义比这宽：

- 对方可能只是一个**音源**，根本不是汇聚端；
- **本机就是汇聚端**时（手机上被别的设备选成 hub），对端更不会有 hub 握手口。

这两种情况 `respondToInvite` 必然 30 秒超时（`for (int i = 0; i < 30; ++i)` ×
`setSoTimeout(1000)`），而**与此同时**对端音源的 `CONN-REQ` 早已被本机
`InviteListener` 自动 ACK 并开始推流——所以「没同意」和「声音通了」能同时成立。

旧代码在握手失败时执行 `setUserLeft(true); refresh();` + Toast「对方还没有同意连接」，
`refreshInner()` 的 `showControl = !userLeft && (...)` 立刻变 false → **退回连接屏**，
顺带把刚建好的接收链路留在后台。用户看到的就是「提示没同意 → 自己退出 → 声音断掉」。

### 修复（三处）

1. **本机是汇聚端时跳过握手**：`pickRemoteHub` 的新线程开头判断
   `GraphStore.isHub()`，是则直接 `hintText = ""; refresh();` 收工，不发 `CONN-REQ`。
2. **本机是汇聚端时不停接收服务**：`startService(ACTION_STOP)` 原本无条件执行（那是
   「切换要连的汇聚端，先把旧接收服务清干净」），但本机是 hub 时它会停掉自己正在
   收流/混音的 `ReceiverService`，声音立刻断。改为 `if (!GraphStore.isHub())`。
3. **握手失败不再退屏**：失败分支改成
   `hintText = "等待对方确认连接…"; refresh();`
   + Toast「已发起连接，等待对方响应（若长时间无反应，请到电脑上查看连接请求）」。
   界面留在原地，等图谱/音频证据（`refreshInner` 里的 `onlinePeers`）自然把用户带进
   控制屏。

另外 `respondToInvite` 全程补了日志（`AudioHubReceiver` tag）：目标解析失败、
`开始握手 -> ip:port`、每一帧回包内容、30 秒超时、异常栈。旧实现里
`catch (Exception e) { return false; }` 是**完全静默**的，排查时看不出是包没发出去
还是 ACK 没回来。

### 验证（模拟器实测）

用 `/tmp/fake_pc.py` 冒充电脑：绑 `127.0.0.1:47102` 收 `CONN-REQ`，通过
`adb emu redir add udp:47999:47002` 把 `CONN-INVITE|桌面端|10.0.2.2:47100|1` 送进
手机 47002。

| 场景 | 期望 | 实测 |
| --- | --- | --- |
| 邀请弹窗 | 弹出「连接请求 / 桌面端（10.0.2.2）希望本机把声音送过去」 | ✅ |
| 点「同意」+ 对方回 ACK | `开始握手 -> 10.0.2.2:47102` → `握手收到回包 #0 = CONN-ACK\|fakepc\|1` → `握手成功，用时约 0 秒` | ✅ |
| 点「同意」+ 对方**不回** ACK | 30 秒后 `握手超时 30 秒：对方没回 CONN-ACK`，**界面停在连接屏不退** | ✅（旧代码会退屏） |

### 顺带修掉的一个自己造的 bug

v0.19 期间为了让「手机上从设备列表主动连入」也能通过，一度把 CONN-REQ 的放行条件写成
`session_peers.count(from_ip) > 0`——但 `RememberPeer(from_ip, ...)` 就在 CONN-REQ 处理的
**第一行**，判断时它必然已经写进集合，等于**任何陌生设备第一次连入都被自动 ACK，待确认
弹窗彻底失效**。现在在 `RememberPeer` 之前先快照 `was_known_peer`，用快照判放行。

## 电脑连手机一直无反应 / 退出重进有遗留（v0.20）

**现象**：电脑端点了「邀请」或者手机端点了设备，两边就互相干等 ——
手机显示「等待对方确认」，电脑端 20 秒后报「邀请 X 无响应」。
用户自己的描述最关键：**「多次退出重进后有遗留」**。

### 根因：残留进程占着 UDP 端口，新进程"启动成功"却收不到包

Windows 的 UDP 有两条容易被忽略的规则：

1. **两个 socket 可以绑同一个端口**（`SO_REUSEADDR`）。旧代码
   `UdpSocket::Open(port)` 的 `reuse_addr` 默认是 `true`，所以上一个没退干净的
   `audiohub-hub.exe` 还能和新的同时活着，内核把入包**随机分给其中一个**。
2. 就算改成独占绑定也没用：**「绑定具体 IP」和「绑定通配地址」是可以共存的**。
   一个程序占着 `127.0.0.1:47002`，hub 再去绑 `0.0.0.0:47002` 依然成功，
   但发往 `127.0.0.1` 的包**全部进前一个程序**。`bind()` 完全查不出来。

本机实测的完整证据链（`--port 47100` 做测试端口）：

| 场景 | hub 日志 | `CONN-REQ` 后 `sources` |
| --- | --- | --- |
| 端口干净 | 已启动 | `[('127.0.0.2', True)]` ✅ 建流、回 ACK |
| 先有进程占 `127.0.0.1:47102` | 照样打印「已启动」 | `[]` ❌ 连 ACK 都不是 hub 发的 |

第二种情况下手机端其实收到了一个 `CONN-ACK`（那个残留 socket 回的），
看起来像"连上了"，但 hub 侧一包数据都没有。

### 三处修复

1. **固定端口独占绑定**（`src/hub_core.cpp`）：`data_socket` / `ctrl_socket` /
   `handshake_socket` 三处 `Open(...)` 全部显式传 `reuse_addr = false`。
   第二个实例现在会直接 `绑定端口 47000 失败: bind() (wsa=10048)` 并退出。
2. **启动前枚举端口占用**（`src/udp_socket.cpp` 新增 `ProbeUdpPortOwners`）：
   用 `GetExtendedUdpTable(..., UDP_TABLE_OWNER_PID, ...)` 在绑定之前问系统
   "这个端口上还有谁"。这是唯一能查出第 2 条规则的办法。命中就拒绝启动并
   给出可操作的提示：
   > 端口 47002 已被占用（127.0.0.1）。多半是上一个 AudioHub 进程没退干净：
   > 请先在任务管理器里结束 audiohub-hub.exe / audiohub-source.exe 再重启。
3. **错误原因透传到界面**（`ui/main.js` + `apps/hub_main.cpp`）：核心进程的
   最后一行 stderr 会被记住，`hubProcess.on('exit')` 时原样推给渲染进程。
   以前界面只会显示「音频核心已退出（code=1）」，用户完全不知道该做什么。

### 顺带修的：手机连上后卡在连接屏

`pickRemoteHub` 握手成功后只 `upsertNode`，**没有把对端标记为汇聚端**，
要等对端广播 GRAPH 帧来校正 `hubId`。但广播里的 `hubId` 是**对端看到的地址**，
NAT / 多网卡之后可能和本机看到的地址不一致，于是 `hubNode()` 恒为 `null`，
`showControl` 条件不成立 —— 明明已经连上、声音也在走，界面却停在连接屏。
现在握手成功（收到 `CONN-ACK`）就直接 `GraphStore.switchHub(d.ip + ":" + audioPort)`：
只有 hub 的握手口会回这个包，回包本身就是"对端是汇聚端"的证明。

### 用户自查方法

以后再遇到"连不上、两端互相等"，先看电脑上有没有残留进程：

- 任务管理器 → 详细信息 → 结束所有 `audiohub-hub.exe` / `audiohub-source.exe`，重启 App；
- 或者直接看界面横幅：v0.20 起它会明确写出是哪个端口被谁占着。

### 验证

- `bash build.sh`：断言 10970 / 失败 0 / 88 用例。
- 独占绑定：第二个 hub → `绑定端口 47000 失败: bind() (wsa=10048)`，进程数 1。
- 端口探测：python 先绑 `127.0.0.1:47002` → hub 拒绝启动并打印上述提示，进程数 0。
- 干净环境端到端（模拟器 → PC）：`开始握手 -> 10.0.2.2:47002` →
  `握手收到回包 #0 = CONN-ACK|桌面端|1` → `握手成功`，hub 侧 `sources: [('127.0.0.1', True)]`。
- `tools/audioflow-test.js --rounds 4`：15/15。
- `tools/ui-e2e.js`：ALL CHECKS PASSED。
- `run-spatial-check.ps1` PASS、`run-graphfit-check.ps1` PASS。

## 切换汇聚端回来没声音 / 最后一路断开后图谱残留（v0.21）

两条用户报的问题，根因各在一端。

### ① 切到电脑再切回来，手机显示已生效却一直没有声音

`MainActivity.reconcile()` 原来在对端切换时调 `stopSource()`。它是"彻底结束"：

```
ACTION_STOP → CaptureService.stopCapture() → projection.stop()
            → sHasProjection = false → clearProjectionAuth()
```

而 Android 14 的投屏授权**一次性**：一旦销毁，下次再切回"本机当音源"就永远
拿不到授权 —— 界面提示「本机已设为音源，开始采集后生效」，却一直没有声音。

"汇聚端暂时未知"在切换过程中是常态（对端正在广播新图谱，或本机刚被设为 hub
又切回去），它不是"用户要停止送音"。修法：这里改调 `stopSourceKeepingAuth()`
—— 只发 `CaptureService.ACTION_PAUSE`，`stopCaptureKeepProjection()` 保留
`projection`、保留 `AudioRecord`、把 `sending` 置 false，采集线程继续跑只是不发；
切回来时走 resume 分支直接 `sending = true`，不再重新要授权。

### ② 一方断开后图谱只剩一个设备，应同步退出并删除图谱

图谱协议只做并集、从不删节点（`src/graph.cpp` 的 `UpsertNode`：
"字段会话内不变：只增不改"），所以「只剩自己」**不能**靠遍历 `graph_.nodes`
判断 —— 对端走了它的节点仍在表里。

C++ 端（`src/hub_core.cpp`）新增：

- `ClearSessionLocked()`：清 `session_peers` / `streams` / `accepted_ids` /
  `pending` / `invited_peers`，并 `GraphResetLocked()`。
- `EndSessionIfAloneLocked()`：判据是「没有活跃流 + 没有待确认请求 +
  `session_peers` 为空」——`session_peers` 记录的是"进过会话的对端"，比图谱可靠。
- 三处调用：`CONN-BYE` 分支末尾、流超时清理循环之后（只有真的删掉了流才判）。
- `HubCore::ClearSession()` 收敛成一句 `impl_->ClearSessionLocked();`。

Android 端（`GraphStore` / `MainActivity.reconcile()`）对应实现，判据是
「**曾经**有对端 + 现在**在线**对端为 0 + 本机不是 hub + 无采集/接收」：

- `GraphStore.hasPeerNode()`：这幅图里是否出现过别的节点。
- `GraphStore.onlinePeerCount()`：在线对端数（`now - lastSeenMs < 10000ms`）。
- `sawPeer` 字段记住"曾经有对端"，收摊后清零。

**两个坑（都是实测踩到的）**：

1. 一开始用 `GraphStore.isAlone()`（表里只剩自节点）作判据 —— 对端离线后节点
   仍留在表里，`isAlone()` 永远 false，自动收摊形同虚设。
2. 改成"只看服务状态 + isAlone()"后，**刚连上的一瞬**图谱还没同步下来、
   `isAlone()` 为 true、服务又没起 —— 会把"正要连上"判成"没人了"，
   连接一闪就退回连接界面。所以必须有 `sawPeer` 这个"曾经有对端"的前提。

### 验证

| 项 | 结果 |
|---|---|
| 探针 `CONN-REQ` → `CONN-BYE`（C++ 端） | 修复前节点数 2（残留）→ 修复后 1，`hubId` 回落自节点 ✅ |
| 模拟器：手机连 PC 后杀 `audiohub-hub` | 15 秒内自动退回连接屏，`user_left=true` 落盘、`hub_ip/hub_name` 被清 ✅ |
| `build.sh` | 断言 10970 / 失败 0 / 88 用例 ✅ |
| `audioflow-test --rounds 4` | 15/15 ✅ |
| `ui-e2e` | ALL CHECKS PASSED ✅ |
| `run-spatial-check.ps1` / `run-graphfit-check.ps1` | PASS ✅ |

APK versionCode 21；PC 便携版重新打包。

## 点「送入」不弹录屏授权 / 刚连上就没声弹回连接屏（v0.22）

**现象**（用户原话）：「现在甚至没有唤醒手机上的允许录屏的提示界面，电脑没有收到声音」。

模拟器上逐字复现出两条独立的故障：

1. 连上 PC、授权录屏之后，**6 秒左右自动弹回连接屏**，hub 侧 `sources` 变空、`[-] 流掉线`；
2. 回到连接屏后点「送入」，**任何系统弹窗都不出现**（`topResumedActivity` 始终是本 Activity，
   没有任何权限 Activity 被拉起），电脑当然收不到声音。

### 根因 A：收摊判据被 NAT 改写源 IP 误触发

v0.21 新增的「最后一路断开就删图谱」判据是
`onlinePeerCount() == 0`，而在线判据来自 `node.lastSeenMs`。`lastSeenMs` 的刷新只有三条路径：
握手成功（`upsertNode`）、合并新节点（`mergeFromFrame` 首次见到）、数据报到达（`markSeen(源 IP)`）。
第三条是**按数据报源 IP** 匹配节点的，而模拟器 / NAT / 代理会把源地址改写
（手机看到的 PC 是 `127.0.0.1`，图谱里的 PC 节点却是 `10.135.129.59:47000` / `10.0.2.2:47000`）
—— 匹配不上，于是对端每 3 秒照发的 GRAPH 帧一帧都没能刷新活标志。
10 秒后 `onlinePeerCount()` 归零 → 判定「图谱只剩本机」→ 收摊。

收摊分支原本会 `hubIp = null; hubName = null; saveHub(); sourceIntent = false;`，
把用户记住的汇聚端地址从 SharedPreferences 里**删掉**，于是：

- 手机回到连接屏（`setUserLeft(true)`）；
- 之后点「送入」时 `g.hubNode()` 返回 null（`hubId` 已被 `resetSession()` 清空，
  `hubIp` 也被删了）→ `toggleSource()` 直接 Toast「请先连接一台设备」就 return，
  **`ensurePermissionsThenStart()` 压根没被调用** → 用户看到的就是「连录屏授权框都不弹」。

### 根因 B：`toggleSource()` 在 hub 未知时直接放弃

即使只是「`hubId` 指向的节点暂时不在图里」（对端 GRAPH 还没到、节点 id 对不上），
`toggleSource()` 也一律早退，而不是回落到上次记住的 `hubIp`/`hubPort` ——
明明还连得上，却什么都不做。

### 修复（三处）

1. **`GraphStore.mergeFromFrame()`：收到一帧 GRAPH = 发送方还活着。**
   把帧里出现的每个对端节点都标活（并记进 `seenByIp`）。
   对端真走了就不会再发帧，节点自然过期，收摊逻辑照样按预期触发，不会把该收的摊挡住。
   注释里写清了两种源 IP 对不上的情况：① NAT / 模拟器改写源地址；② 帧是别的设备中继的。
2. **`GraphStore.onlinePeerCount()` 改用 `effectiveLastSeen(node, now)`**，
   与 `snapshot()` / `isOnline()` 同一口径，不再只看 `node.lastSeenMs`。
3. **`MainActivity.toggleSource()` 回落到 `rememberedHub()`**，
   并在进入采集前把 `hubIp`/`hubPort` 重新钉住（`saveHub()`）。
   点「送入」是用户明确的开始采集意愿，只要知道往哪儿送就必须把授权流程走下去。
   同时**收摊分支不再清 `hubIp`/`hubName`/`sourceIntent`** ——
   收摊的语义是「这轮会话结束、图谱重建」，不是「忘记这台电脑」；
   记住的地址是用户建立的连接关系，下次一键重连要用它。

### 验证

| 检查 | 修复前 | 修复后 |
|---|---|---|
| 点「送入」是否弹录音授权 | 不弹（Toast 早退） | **弹**（WHILE USING THE APP） |
| 授权后是否退回连接屏 | 6 秒内退回 | **连续 20 秒停在控制屏** |
| hub 侧音频包 | `sources []`（流掉线） | **持续增长 18990 → 22286，18 秒不断** |
| SharedPreferences | `hub_ip`/`hub_port` 被删 | **保留 `hub_ip=10.0.2.2`、`hub_port=47200`** |

回归：`build.sh` 断言 10970 / 失败 0 / 88 用例；`audioflow-test --rounds 4` 15/15；
`ui-e2e` ALL CHECKS PASSED；spatial PASS 23182；graphfit 249。
APK versionCode 22；PC 便携版 79,182,537 B。

> 环境提醒：模拟器联调前必须 `adb emu redir del udp:47000` / `udp:47002`，
> 否则 qemu 独占 127.0.0.1 上这两个端口，会让 audioflow / e2e 的测试 hub 绑不上端口
> （表现为大面积 FAIL，不是代码问题）。

## 点连接后一直显示「等待对方确认」 / 连录屏授权框都不弹（v0.23）

### 现象

用户逐字反馈（m21648）：「手机上一直显示等待对方确认连接，并且还是没出现确认录制屏幕的窗口」。
也就是：手机点「连接」/「同意邀请」之后，界面挂在「等待对方确认连接…」，30 秒里**连系统的一次性录屏授权框都没弹出来**，电脑侧自然也收不到任何声音。

### 根因：授权流程被押在一个不必要的「hub 式握手」后面

`MainActivity.pickRemoteHub()` 是「同意邀请 / 设备列表 / 手动 IP」三个入口的公共实现。v0.22 及以前，它把 `ensurePermissionsThenStart()` **只放在握手成功分支**里：

```java
boolean ok = ReceiverService.respondToInvite(d.ip, audioPort, Build.MODEL);
if (!ok) {
    hintText = "等待对方确认连接…";        // ← 用户看到的就是这句
    refresh();
    Toast(...「对方还没有同意连接」...);
    return;                               // ← 直接返回，授权流程根本没走到
}
...
if (!GraphStore.isHub()) { sourceIntent = true; ensurePermissionsThenStart(); }
```

`ReceiverService.respondToInvite()` 是一个 30 次 × 1 秒的 `CONN-REQ → CONN-ACK` 探测（发往 `audioPort + 2` 的握手口）。电脑没回 ACK 时，上面那个 `!ok` 分支就是唯一出口 —— 于是「一直等待对方确认」和「连录屏框都不弹」两个症状，是**同一条因果链**。

而这个握手对「送声音」并不是必需的：

- `src/hub_core.cpp:515-530`：hub 收到音频包会自动建流，`created->confirmed = !require_handshake || auto_accept_.load() || accepted_ids.count(header.stream_id) > 0;`。`auto_accept` 默认 true，**音频包即使没握手也能出声**。
- `android/.../CaptureService.java` 只有发送循环（`while (running)` / `if (!sending) continue;` / 每包重建 `DatagramPacket` / `sPackets++`），**不做 CONN-REQ 握手**；握手只存在于 `ReceiverService.respondToInvite`，是「连到对方汇聚端」这条路径用的。

一句话：送声音唯一必需的前提是**一次性的录屏授权**，真正推流的是 CaptureService；把授权埋在探测握手的成功分支里，等于让「电脑有没有回一个 ACK」决定「手机能不能弹授权框」。

### 三处修复（`android/app/src/main/java/com/audiohub/source/MainActivity.java`）

1. **授权与握手解耦**：在 `pickRemoteHub()` 的 `refresh();` 之后、起握手线程**之前**就发起授权：

```java
final boolean wantSendAudio = !GraphStore.isHub();
if (wantSendAudio) {
    sourceIntent = true;
    ensurePermissionsThenStart();
}
```

   本机是汇聚端时不送音，自然不弹授权框；否则点「连接」立刻弹系统授权框，不再等 30 秒。
2. **删掉成功分支里重复的** `ensurePermissionsThenStart()`（否则会叠出第二个系统弹窗），只留一句注释说明授权已在进线程前发起。
3. **`!ok` 分支文案改诚实**：`hintText = "电脑 <ip>:<port> 没有回应"`，Toast 改为「30 秒内没收到确认。请检查：① 电脑上的 AudioHub 是否在运行；② Windows 防火墙是否放行了 AudioHub；③ 两台设备是否连在同一个网络」。原因：30 秒一条 ACK 都没回来 = 电脑根本没收到包（端口残留 / 防火墙 / 不在一个网段），并不是「对方还没点同意」。

### 配套的两处会话保护（防止又退回连接屏）

4. **grace 回落分支**去掉 `HUB_MISSING_GRACE_MS` 超时停采集：原逻辑在图谱里找不到 hub 节点 6 秒后就停采集。但「找不到 hub 节点」经常只是 NAT / 防火墙让对端 GRAPH 帧到不了，不是汇聚端没了；一停采集 `srcRunning` 变 false，紧接着 v0.21 的收摊分支就会把 `hubId` 清空、退回连接屏。改为：

```java
if (remembered != null && (srcRunning || hubRunning || sourceIntent)) {
    if (srcRunning) { startCapture(remembered, g.streamIdForSelf()); }
    else if (sourceIntent && (hasProjectionAuth() || CaptureService.sHasProjection)) {
        hintText = ""; startCapture(remembered, g.streamIdForSelf());
    }
    return;
}
```

5. **收摊分支加 `&& !sourceIntent`**：正在送音时绝不收摊（收摊是「本轮会话真的结束了」，不是「图谱暂时没同步」）。

### 验证（模拟器 emulator-5554 + hub `--port 47000 --http-port 47900`）

| 检查 | 修复前 | 修复后 |
|---|---|---|
| 点「连接」后弹录音授权框 | 不弹（要等 30 秒握手，还不一定弹） | **3 秒内弹出** |
| 授权后音频包 | `sources []` | **持续 30 秒增长**：1604 → 2662 → 3724 → 4782 → 5841 → 6899（每 5 秒约 1058 包）；更长观察 19827 → 32964 |
| 反复出现的录屏弹窗 | — | `MediaProjectionPermissionActivity` 只 START 1 次；`topResumedActivity=MainActivity`，窗口数 0 |

> **注意（工具假象，不是产品缺陷）**：`tools/tapui.py dump` 在验证时反复显示「Start recording or casting…」弹窗，一度以为授权框弹了两次。真因是 `uiautomator dump` 报 `ERROR: could not get idle state`（星野动画让 UI 一直不 idle），dump 失败后脚本仍去 `cat` 上一次的 `/sdcard/ui.xml` → 读到过期内容。用 `adb shell dumpsys window` 查 `mCurrentFocus` 与窗口数、以及 `adb exec-out screencap -p` 截图才能确认真实界面。真机上星野动画不会导致 uiautomator 长期失败，且用户看到的是一次性系统弹窗。

### 回归

`bash build.sh` 断言 10970 / 失败 0 / 88 用例；`audioflow-test --rounds 4` 15/15；`AUDIOHUB_E2E_PORT=19340 node tools/ui-e2e.js` ALL CHECKS PASSED；`run-spatial-check.ps1` PASS；`run-graphfit-check.ps1` checks=249 fails=0。

> 跑 audioflow / e2e 前**必须先** `adb emu redir del udp:47000` 与 `udp:47002`：模拟器开着时 `qemu-system-x86_64` 会独占 `127.0.0.1` 上这两个端口，测试 hub 绑不上会大面积 FAIL。

## 手机发包正常、桌面端却没声音 / 电平条不动（v0.24）

### 现象
用户 m22380（逐字）：「现在手机上正常显示发包，但桌面端貌似没有收到，没有在正常渲染，导致没有声音」。
手机端面板显示「发送中 · N 包 · -120 dBFS」、`sPackets` 持续增长；桌面端星图电平条不动、
总线 `-120 dBFS`、耳机全静音，看起来像「没收到 / 没渲染」。

### 结论先行
**不是接收端坏了，也不是没收到——手机发来的包里装的是静音（payload 全 0）。**
本功能采集的是**手机正在播放的媒体声音（音乐 / 视频 / 游戏）**，不是麦克风：
`CaptureService.java` 的 `startRecording()` 用
`AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(USAGE_MEDIA/GAME/UNKNOWN)`
+ `AudioRecord.setAudioPlaybackCaptureConfig(config)`。手机没在放音时
`AudioRecord.read()` 返回全 0 → 包照发、PC 照收照渲染、但渲染出来是静音，
于是电平条恒 `-120`、听起来「没声音」。**用户选择「保持现状，加静音提示」**：不改采集语义，
只在两端加醒目的「当前没采集到声音」提示，避免被误判成「没收到」。

### 决定性实验（全部本机实测）
- 实验 1 `tools/probe-pcrecv.js`：假音源从宿主机发 440Hz 正弦 → PC `bus=-19.88/-22.52`、
  `isHub=true`、`confirmed=true` = 建流 / 确认 / 混音 / 渲染链路完好。
  `src/hub_core.cpp:524-525` `created->confirmed = !require_handshake || auto_accept_.load() || accepted_ids.count(header.stream_id) > 0;`
  使「只发包不握手」的源即 confirmed；`:1626-1629` `if (!IsHub(graph_, self_node_id)) mix_inputs.clear();` 未触发。
- 实验 2 手机端到端（清数据 → 手动添加 `10.0.2.2:47000` → 三级授权）：12 秒逐秒 `/api/state`
  → `sources=[id=1 conf=True pkt=563→3473]`、`occupancy≈target`、`late/lost/catchup/overflow=0`
  但 `bus=-120`、`level=-120`。**PC 收到全部包、缓冲健康、无丢包 → 「没收到 / 没渲染」不成立。**
- 实验 3 `tools/probe-payload.py`：杀 hub，裸 UDP 绑 `0.0.0.0:47000` 直接解手机包 →
  `200 包/秒`、`avgRMS=0.0 (-120.0 dBFS)`、`peak=0`、`badVer=0`、
  `seq` 连续 125597→126801、**包头合法但 payload 全 0**。
- 实验 4 `PacketProtocol.writePayload()`（`PacketProtocol.java:61-67`）复核：逐字节小端写
  `block[i]`，实现正确，排除「写错导致全 0」。
- 实验 5 A/B（手机真的播一段 440Hz tone，用 MediaStore + `com.android.music/.AudioPreview`）：
  - 手机播 tone → payload `-11.7 dBFS`、PC `bus=-11.7`、`kbps≈1560`、`confirmed=true`；
  - 不播 → 全 `-120 dBFS`；
  - 手机媒体音量被 `muteMediaVolume()` 静音到 0 时**模拟器 ROM 仍能采到声音**
    （`CaptureService.java:686-688` 注释里「理论上不受媒体音量影响」当时据此判定）。
    **注意（v0.25 更正）：这个结论只在模拟器成立，真机被推翻** —— 见下一节 v0.25。
  - → 有没有声音只取决于**手机是否真的在播放音频**。

### 修复（v0.24）
手机端（`android/app/src/main/java/com/audiohub/source/MainActivity.java`）：
1. 新增字段 `private long silentSinceMs = 0;` 与常量 `SILENT_DBFS = -100`
   （复用 `CaptureService.MUTE_SILENT_DBFS`）、`SILENT_WARN_DELAY_MS = 3000L`。
2. `refreshInner()` 计算 `silentNow = srcRunning && sLevelDbfs <= SILENT_DBFS`，
   连续 3 秒才 `silentWarn = true`（防抖：刚接入 / 缓冲没填满时不闪假警）。
3. **主提示点在控制屏 `hubSub`**（关键结构：`sourceState` 在连接屏，用户发包时看的是控制屏）：
   `silentWarn` 时追加「⚠ 采集到的是静音：本功能送的是手机正在播放的声音（音乐/视频/游戏），
   不是麦克风，请先在手机上播放音频」并 `hubSub.setTextColor(COLOR_WARN)`；
   连接屏 `sourceState` 同步显示同款文案、同样改琥珀色。

PC 端（`ui/renderer.js` / `ui/index.html` / `ui/styles.css`）：
1. 新增 `let silentSince = 0`；`render(state)` 在 `anyConnection` 之后判
   `silentNow = confirmedCount > 0 && busLevel <= -119`，同样连续 3 秒才提示。
2. 文案：「已收到音源，但当前是静音：本功能送的是发送端「正在播放的声音」（音乐 / 视频 / 游戏），
   不是麦克风。请确认发送端正在播放媒体声音。」
3. `ui/index.html:103` 新增 `<div id="silentWarn" class="silent-warn hidden" role="status"></div>`；
   `ui/styles.css` 加 `.silent-warn`（琥珀底 + 左边框）与 `.silent-warn.hidden { display:none }`。
4. 判据用总线电平（就是汇聚端耳机里实际听到的），`confirmedCount > 0` 才提示。

### 验证
- 手机端 A/B 截图实测（星野动画下 uiautomator 拿不到文本，改 `screencap` + PIL 像素统计）：
  - 静音态等 4 秒 → hub `bus=-120`、`sources=[(1,True,797,-120)]`，控制屏可见琥珀警示文案，
    该区 **5592 个琥珀像素**；
  - 手机播 tone（重播 3 次）→ hub `bus=-11.737/-11.736/-11.743`、`sources[0].level≈-11.74`，
    全图琥珀像素 **0**，提示消失、星图显示 `-16.0 dBFS` 绿色电平条。
- PC 端 `tools/_verify_silent_warn.js` 确定性注入（同一个同步 `cdp.eval` 里直接调全局 `render()`，
  避免 `onState` 真实推流插队）：4/4 PASS —— 静音持续 4s 点亮 / 总线有声立即隐藏 /
  无已确认流不提示 / 静音不足 3s 防抖不闪。
- 回归全绿：`bash build.sh` 断言 10970 / 失败 0 / 88 用例；`audioflow-test --rounds 4` 15/15；
  `ui-e2e`（`AUDIOHUB_E2E_PORT=19341`）ALL CHECKS PASSED；spatial PASS；graphfit 249。
- 产物：APK `android/build/audiohub-source.apk` 456,791 B（versionCode 24 / versionName 0.24）；
  `ui/dist/AudioHub-便携版.exe` 79,184,369 B、`AudioHub Setup 0.1.0.exe` 79,477,545 B。
  SHA 一致（`build/` 与 `ui/bin/`）：hub `77c22c5f46ccbea9`、source `1ab8f695c6a9344a`。

### 使用提示（给真机复验）
- 本功能送的是**手机正在播放的声音**，不是麦克风。**连接之前放、还是连接之后才放，都可以**：
  `AudioPlaybackCapture` 抓的是实时音轨，采集会话建立后新开播的音乐会立刻被抓到。
  实测（v0.24 模拟器）：先连上、此时不放音乐 → 总线 `-120 dBFS`（两端提示静音）；
  然后才在手机上播放音频 → 约 1 秒后总线跳到 `-15.05 dBFS` 并持续到播放结束，
  静音提示随之消失。所以「先连后放音乐」完全正常，不需要提前放、也不需要重连。
- 手机一直没播放任何音频时，两端会明确提示「当前是静音」，这是预期行为——
  包在发、对端也在收，只是内容是静音。开始播放后提示会自动消失、无需任何操作。
- 跑回归前先 `adb emu redir del udp:47000` / `udp:47002`（qemu 会独占这两个端口，
  否则 `audioflow-test` 大面积 FAIL）。

## 真机「手机在放、桌面端却没声音 / 电平条不动」（v0.25）

### 现象（用户 2026-11-06 真机反馈，逐字）
> 还是不行，开启的时候手机会卡一下，然后正常播放的时候还是没有声音传递，
> 虽然手机在正常播放，桌面端还是没有渲染任何结果。

与 v0.24 的模拟器结论相反：模拟器里用系统自带播放器放 tone，连接后 `bus=-15.05` 一切正常；
真机上手机明明在放音乐，桌面端电平条不动、完全没声音。「开启时手机卡一下」是投屏授权 +
音频通路切换的新线索。用户确认：**手机照常出声**（不是手机自己没声）。

### 根因（选定修复的那一条）
`CaptureService.muteMediaVolume()` 在每次开始采集后把 `STREAM_MUSIC` 设成 0，
原意是「声音只从汇聚端出」。Windows 的 WASAPI 回环不受端点静音影响，据此推测 Android 也一样
（`CaptureService.java:686-688` 旧注释：「理论上也不受媒体音量影响，但没实测」）—— **真机证明
不成立**：部分 ROM 在媒体音量为 0 时会把播放器的音轨一并静音，`AudioPlaybackCapture` 拿到的
就是全 0，包照发、PC 照收照渲染，但渲染出来是静音。

`maybeHealMute()` 本该 3 秒后自愈（静音电平持续 3 秒 → `restoreMediaVolume()`），但它带
`final boolean musicActive = isMusicActive(); if (!musicActive) return;` 闸门；某些 ROM 上
`AudioManager.isMusicActive()` 恒 false（尤其是被我们静音之后），自愈永远不触发 → **整条流变成
永久静音**。模拟器 ROM 不掐采集也不误报 `isMusicActive`，所以从头到尾没暴露。

另一个**本次不修**的候选根因：主流音乐/视频 App（网易云、QQ 音乐、B 站、抖音、YouTube 等）
会在 `AudioAttributes` 里显式禁止自己被 `AudioPlaybackCapture` 捕获（版权/DRM），这是系统级限制，
换静音策略也绕不过。用户 2026-11-06 明确选择「只修静音掐断采集」，所以这一条保留为已知限制：
**若修完仍静音，请先用系统自带播放器 / 本地音频文件试一次**，能出声就说明是播放 App 的捕获策略。

### 修复（v0.25）
`android/app/src/main/java/com/audiohub/source/CaptureService.java`：
1. `muteMediaVolume()` 方法体改为 **no-op**，只打一行日志「v0.25：保持媒体音量不变」。
   方法签名、`mutedByUs` 字段、`restoreMediaVolume()` / `maybeHealMute()` 全部保留 ——
   于是 `mutedByUs` 恒为 false，所有收尾路径（workerLoop 退出、`stopCaptureKeepProjection()`、
   `stopCapture()`、自愈）的 `restoreMediaVolume()` 都安全空转，**不用改动任何调用点**。
2. 字段区、新会话启动处、`maybeHealMute()` 的文档注释同步更正，写清真机证据，避免以后又有人
   按「理论上不受影响」把静音加回来。
3. 用户手机音量保持原样：手机照常出声、汇聚端也收得到，两害相权先保证有声音。

### 验证
- `bash build.sh`：断言 10970 条 / 失败 0 / 88 用例（C++ 侧本轮零改动）。
- 模拟器端到端：连接后手机媒体音量**不再被改成 0**（`dumpsys audio` 的 `STREAM_MUSIC` 保持
  15/15），播放 tone 后 PC `bus=-15.05`、`confirmed=true`、`packets` 持续增长；静音提示不再误报。
- 回归：`audioflow-test --rounds 4` 15/15、`ui-e2e` ALL CHECKS PASSED、spatial / graphfit PASS。
- **真机复验仍待用户完成**（本机没有连接真机，拿不到真机 logcat）；重点看连接后手机音量是否
  原样、播放普通音乐时桌面端电平条是否起来。

### 使用提示
- 连接之前放音乐、还是连接之后才放，都可以（v0.24 已实测「先连后放」约 1 秒自动出声）。
- 手机没在放音时，两端会亮 v0.24 的静音提示 —— 这是正常的，播起来就会消失。
- 若真机上放的是禁止被捕获的 App，仍可能静音；请先用系统自带播放器或本地音频文件对照。

## 两端彻底互相听不到声音 / 图谱里看不到对方（v0.26）

- 用户反馈：「现在已经是彻底的两端都收不到对方的声音了。你是不是把什么改坏了」。是 —— 我 v0.21
  加的「最后一路断开后结束本轮会话」逻辑把它改坏了。
- 机制（逐条可查）：
  1. 对端退出（`CONN-BYE`）/ 流超时掉线 / 用户点「全部断开」，都会调
     `HubCore::Impl::EndSessionIfAloneLocked()`；那时已无流、无 pending、无 sessionPeers，
     于是走 `ClearSessionLocked()` → `GraphResetLocked()`，结尾 `graph_detached = true`。
  2. 手机 `CaptureService` 重新连上时**只发音频包、不发 CONN-REQ**（`auto_accept` 默认开，
     纯数据包也能建流）。PC 的 `ReceiverLoop` 建流成功，`sources` 有、`packets` 在涨、甚至
     `busLevel` 有声（因为本机恰好还占着 hub 位）。
  3. 但那条建流分支**不调 `GraphAddPeerLocked`** —— 而解除 `graph_detached` 只有
     `GraphAddPeerLocked`（首行 `GraphReattachLocked()`）和 `SwitchGraphHub` 两条路。
  4. 于是 `HandleGraphFrame` 首行 `if (graph_detached) return;` 把对端所有 GRAPH 帧丢掉，
     `GraphBroadcastLocked` 也首行早退不发 → **两端各自守着自己那份图谱，`hubId` 都指向自己**。
  5. 结果是两边都认为「我是汇聚端」：手机侧不送音、PC 侧 `ui/main.js` 的
     `if (hubId === selfId) { stopPcSource() }` 主动停源 → 彻底双端静音。
- 修复：在 `src/hub_core.cpp` 的纯数据包建流分支（`Emit("[+] 新流接入: …")` 之后）加
  ```cpp
  if (graph_detached && !from_ip.empty()) {
    GraphAddPeerLocked(from_ip, "", 0, nullptr);
  }
  ```
  理由：判据「收到真实音频」= 对端确实在线，等价于握手成功；复用 `GraphAddPeerLocked` 不新造机制，
  它自带 `GraphReattachLocked()` + 节点入图 + 立刻互发一次 GRAPH，共识在下一次合并里自愈。
  `audio_port` 传 0 回落到默认 47000（手机 `CaptureService` 用 `new DatagramSocket()`，源端口随机，
  `from_port` 不可靠）；非默认端口会被后续 GRAPH 帧纠正。**只在 `graph_detached` 时执行，正常收流
  路径一个字节都没改。**
- 复现与验证：协议级探针（起 hub → `CONN-REQ` 建会话 → `CONN-BYE` → 只发音频包）在修复前
  `nodes` 恒为 1、修复后回到 2（节点 id `127.0.0.2:47000`，端口回落符合设计）。
- 回归补强：`tools/audioflow-test.js` 新增 **T7**「收摊后只发音频的源必须把图谱拉回来」。
  做了突变验证 —— 把上面那段 `if` 删掉重编译，T7 报 `6s 内没回来` 变红；加回来后 116ms 自愈变绿。
  这条用例之前不存在，正是它缺位让 v0.21 的缺陷一路溜到用户手上。
- 回归全绿：`bash build.sh` 断言 10970 / 失败 0 / 88 用例；`audioflow-test --rounds 4` **20/20**；
  `ui-e2e` ALL CHECKS PASSED；spatial PASS 23182；graphfit checks=249 fails=0。
- 产物：APK 456,791 B（versionCode 26 / versionName 0.26）；PC 便携版 79,184,238 B、
  安装版 79,477,412 B；`build/` 与 `ui/bin/` 的 hub/source 哈希一致
  （hub `786a07386ae12820…`、source `114cd46fa2f1ca8f…`）。

## 汇聚端到底有没有真的出声？（v0.27）

- 用户要求：「还是双端不通，你需要一个方法检查是否真的有音效出现在汇源端」。这个要求是对的 ——
  我之前的判据（轮询 `/api/state` 看 `busLevel`）本身就是不可信的，它一直在骗我。
- **为什么 `busLevel` 不能当判据**：`include/audiohub/meter.hpp` 的 `Meter::Take()` 是
  **取走即清零**的采样器（`sum_sq_ = 0.0; count_ = 0;`）。界面自己每 200ms 取一次，正好把它搬空；
  我从外面再 `curl` 一次，读到的必然是空桶 → `-120 dBFS`。于是「没声音」和「我刚把数读空了」
  在数据上完全一样。这就是前面几轮结论反复横跳的根因。
- **实测反证（决定性）**：手机送音时同一时刻
  `renderAudibleFrames = 1,439,898`、`renderPeakDbfs = -9.1`，而 `busLevel = -120`。
  声音明明在响，`busLevel` 报静音。
- 改法：加**只增不减的渲染探针**，夹在「真正送进声卡的那一帧」上，任何读者反复读都读到同一份值。
  - `Meter` 新增 `Peek()`（只读不清零）与 `add` 时累计的 `peak_abs_` / `nonzero_` / `clips_`；
    新增自由函数 `ScanFrames()` 逐帧判「至少一个声道非零」（抽出来是为了能被单元测试直接覆盖）。
  - `src/hub_core.cpp` 渲染回调里 `ProbeRenderBlock()` 累加 `render_frames_total` /
    `render_audible_frames` / `render_peak_abs` / `render_ring_missed`。
  - 新增 **WAV 落盘**：`POST /api/render-dump/start` / `stop`，把真正送给 WASAPI 的样本写成
    可双击播放的文件 —— 用户不需要相信任何数字，直接听那个文件就知道汇聚端有没有出声。
    （写 `need_samples` 而不是 `got_samples`：只写 got 会把静音期压缩掉，WAV 时间轴变快数倍。）
  - `/api/state` 暴露 `renderFrames` / `renderAudibleFrames` / `renderPeakDbfs` /
    `renderRingMissed` / `renderDumpFrames` / `renderDumpActive`。
- **判读口诀**（已写进 `include/audiohub/hub_core.hpp` 的注释）：
  | 现象 | 结论 |
  |---|---|
  | `renderFrames` 不涨 | 输出设备没在跑（不是「没声音」） |
  | 涨了但 `renderAudibleFrames` 不涨 | 混音后就是静音，问题在上游，别去调音量 |
  | 两个都涨但听不见 | 数据到了设备，问题在系统音量 / 静音开关 / 默认输出设备 |
- 新增工具 `tools/check-audible.js`，一条命令给出结论并自动区分上面四种情形：
  ```bash
  node tools/check-audible.js --port 47900 --seconds 5     # 判定
  node tools/check-audible.js --dump                       # 顺便录 WAV 直接听
  ```
  退出码：`0` = 确实在出声；`1` = 没出声（并指出是哪一种）；`2` = 连不上。
- 界面：PC 状态栏新增「出声 N/M 帧 · 峰值 X dBFS」；静音警告现在能区分「混音后就是静音（上游问题）」
  与「发送端现在没在放声音」，不再一律怪音量。
- 手机侧对称实现：`ReceiverService` 在 `track.write()` **之后**统计 `sRenderFrames` /
  `sRenderAudible` / `sRenderPeakDbfs`（放在 write 之后：write 抛异常就不该算），
  本机当汇聚端时状态行直接显示「出声 N/M 帧 · 峰值 X dBFS」，全零时追加「（全是静音：混音后没声音）」。
- **测试也改了这个判据**：`tools/audioflow-test.js` 的 `waitForSound` / `waitForSilence` 原来只看
  `busLevel`，同样会被界面轮询抢空。现在以 `renderAudibleFrames` 的增量为准，`busLevel` 只作兜底。
  改了以后 `--rounds 4` 仍 **20/20**；突变验证（把增量判据退回纯 `busLevel`）会失败，证明它真的接线。
- 单元测试：新增 `tests/test_meter.cpp` 8 个用例（`Peek` 幂等、`Peek` 后 `Take` 仍拿全量、
  静音与「没数据」可区分、0.5 正弦 `peak_dbfs ≈ -6.02`、clip 计数、`ScanFrames` 只数非零帧）。
  **三次突变验证全部见红**（`Peek` 也清零 / `Add` 去掉非零过滤 / `ScanFrames` 整块只算一帧）。
  `bash build.sh`：断言 **10998** 条、失败 0、用例 **96/96**。
- 回归全绿：`audioflow-test --rounds 4` 20/20；`ui-e2e` ALL CHECKS PASSED；
  spatial PASS 23182；graphfit checks=249 fails=0。
- 产物：APK **456,791 B**（versionCode 27 / versionName 0.27，sha256 `14a1b67d…`）；
  PC 便携版 **79,192,510 B**、安装版 **79,485,682 B**。
- **真机复验（模拟器 + 真手机音源，v0.27 实测）**：
  - 手机在放 60 秒 440 Hz 音频、走 `CaptureService` 送到 PC 汇聚端时：
    `renderAudibleFrames` 本窗口 **+289,920**（等于窗口总帧数，即一帧不落）、峰值 `-7.8 dBFS`；
    `check-audible.js` 判 **✓ 确实在出声**；同时导出的 WAV 为
    `ch=2 rate=48000 frames=289440 dur=6.03s maxabs=13106(40.0%FS) nonzero=289426`，
    直接用播放器打开能听到那段 440 Hz。
    这份录音已留档在 `build/汇聚端实测录音-手机60秒音源.wav`（1,157,804 B），可直接双击核对。
  - **同一轮的 `busLevel` 仍显示 `-120`**（因为它的采样桶被界面轮询搬空了）——
    这正是「不能拿 `busLevel` 当判据」的现场证据。
  - 反向（停掉播放器后立刻测）：判 **△ 之前在出声，但这 5 秒没有新声音**；
    彻底没有音源接入时判 **✗ 一个音源都没接入**。三种情形都按预期报出，没有一次误判。
  - 结论：`v0.27` 的探针能如实指出「汇聚端有没有真的出声」，并且能区分
    「混音后是静音」/「发送端没在放」/「设备侧静音」。它不修复双端不通，但从此
    任何一次「还是不通」都能被定位到具体一环，而不用再靠猜。

## 「出声 0/多少帧」是什么意思？（v0.28）

v0.27 把渲染探针摊在状态栏上之后，最常见的一个疑问就是：**帧数在涨，「出声」却一直是 0。**
这不一定是不通，而且它至少有三种完全不同的原因。v0.28 让这行数字自己把原因写出来。

### 判据从后端来，不靠猜

`/api/state` 新增三个只增不减的归因量（`HubStats` 里的 `rendering_as_hub` /
`mixed_sources` / `mixed_frames` / `mixed_audible_frames`）：

| 字段 | 含义 |
| --- | --- |
| `renderingAsHub` | 这一拍本机是不是汇聚端（来自 `IsHub(graph_, self_node_id)`） |
| `mixedSources` | 最后一拍**真正参与混音**的流数（只数 `active && data && gain != 0` 的，否则"混入 1 路"会骗人） |
| `mixedFrames` | 有流参与混音的累计帧数（单调） |
| `mixedAudibleFrames` | 其中混出声音的累计帧数（单调） |

### 三种情形，三种处理

| 界面显示 | 真实含义 | 该怎么办 |
| --- | --- | --- |
| `出声 0/N（本机不是汇聚端，按协议本就静音）` | 协议 §6.1 规定：不是汇聚端就不混音，输出恒为零。**这是正确行为** | 去真正承担汇聚端的那台设备上看。若两台都听不到，问题在角色分配，不在音频输出 |
| `出声 0/N（没有音源混进来）` | 是汇聚端，但一路都没混进去 | 查流是否已确认（`confirmed`）、发送端是否真的在发包、是否被静音或增益为 0 |
| `出声 0/N（混入了 N 路，但混出来是静音）` | 数据进来了、内容是静音 | 采集端抓到的就是无声。本功能送的是发送端「正在播放的声音」，确认它真的在放媒体声音、录屏授权已通过 |

v0.27 之前这三种全被当成「没混进去」，白白排查了好几轮 —— 所以这个区分不是锦上添花。

### 命令行同样能给出结论

`node tools/check-audible.js --port <http端口> --seconds 10` 的判定树也按这三种情形分开：

```
本机是汇聚端     : 否（按协议混音输出恒为静音）
实际混入路数     : 0
混入帧/有声帧    : 941040 / 0（本窗口 +0 / +0）
```

结论会是「△ 本机不是汇聚端 —— 混音输出按协议 §6.1 恒为静音，941040 帧全是零是正确的」
而不是误报成故障。

### 三种情形都实测过

| 情形 | 构造方式 | `renderingAsHub` | `mixedSources` | `mixedAudibleFrames` | 结论 |
| --- | --- | --- | --- | --- | --- |
| 正常出声 | 推 440Hz 正弦（幅度 0.5） | true | 1 | 有增长 | ✓ 确实在出声 |
| 混入了但静音 | 推 440Hz 正弦（幅度 0） | true | 1 | 恒为 0 | ✗ 采集端抓到的就是静音 |
| 不是汇聚端 | 图谱里加一个节点后切过去 | false | 0 | 恒为 0 | △ 正确行为，不是故障 |

第三方验证：`bash build.sh` 断言 10998 / 失败 0 / 用例 96/96；`audioflow-test --rounds 4` 20/20；
`ui-e2e` ALL CHECKS PASSED；spatial PASS 23182；graphfit checks=249 fails=0。

## 「没有音源混进来」到底是什么意思？（v0.29）

v0.28 把「出声 0/N」拆成了三种情形，但用户实际撞上的是其中最常见的一种：状态栏写着
**「没有音源混进来」**。这句话只描述了现象，没告诉人该做什么 —— 因为 `mixedSources === 0`
背后至少有九种完全不同的成因，从「对端压根没连上」到「你自己把它静音了」，需要检查的
地方一个都不在同一个方向。

v0.29 把这九种成因直接说出来。判据全部来自已有的 `/api/state`（`sources` / `pending` /
`peers` / `graph.nodes`），**没有新增任何后端字段**，也没有改协议。

### 九种成因与对应文案

| 现象（按优先级） | 界面 / 工具说的话 | 该去查什么 |
| --- | --- | --- |
| 图谱里只有自己 | 星空里只有你自己，对端还没连上 | 对端点「送入」、防火墙放行 UDP |
| 图谱里有对端但全离线 | 对端在图谱里但已离线 | 对端 App 被系统杀了，或换了 IP |
| 有 pending 待确认 | 对端已请求连接，等你点「同意」 | 界面点「同意」 |
| 图谱里有在线对端但无流 | 对端就在星空里，但它还没开始送音 | 对端是否已授权采集 |
| 发现列表有、图谱没有 | 已经发现对端，但它还没开始送音 | 同上，先把它拉进图谱 |
| 有流但全被静音 | 收到的 N 路音源全被你静音了 | 取消静音 |
| 有流但全未确认 | 流已收到，但都还没被确认（看「自动同意」） | 打开自动同意 / 走 CONN-REQ |
| 有流但包太少 | 流刚建立，还没收到足够的包 | 再等一秒 |
| 以上都不是 | 收到了 N 路流，但没有一路通过混音条件 | 增益为 0、流被排除 |

### 为什么这一条必须排在「一个音源都没接入」里

用户最常遇到的恰恰是「一个音源都没有」。如果 `check-audible.js` 的 `anySources === 0`
分支只报一句「没收到包」，那上面九支细分判定在最常见的场景下永远用不上。所以
v0.29 把 `whyNoSource()` 同时接进了 `anySources === 0` 与 `audible === 0 && mixedSources === 0`
两个分支。

### 正反对照（实测）

```
# 空 hub，没有任何音源
✗ 一个音源都没接入：星空里只有你自己，对端还没连上
  汇聚端一包音频都没收到，问题在网络/发送端，不在混音，也不在音量。
  先解决连通性：对端 App 里点「送入」，两端防火墙放行 UDP，图谱里应该能看到对方那颗星球。

# 推 440 Hz 正弦后（同一台 hub）
✓ 确实在出声：本窗口有 16880 帧真的带着声音送进了声卡（峰值 -5.7 dBFS）。
  如果你耳机里听不到，问题在汇聚端之后：系统音量 / 静音开关 / 默认输出设备选错了。
```

### 九支判定的自测

`whyNoSource()` 用 node 沙箱逐条喂九种 `state` 验证，九种输出**互不相同**
（`distinct 9 of 9`）：

```
A 只有自己      -> 星空里只有你自己，对端还没连上
B 请确认        -> 对端已请求连接，等你点「同意」
C 发现了没送     -> 已经发现对端，但它还没开始送音
D 对端离线      -> 对端在图谱里但已离线
E 对端在线没送音  -> 对端就在星空里，但它还没开始送音
F 全静音        -> 收到的 1 路音源全被你静音了
G 全未确认      -> 流已收到，但都还没被确认（看界面上的「自动同意」）
H 包太少        -> 流刚建立，还没收到足够的包
I 其他         -> 收到了 1 路流，但没有一路通过混音条件
```

### 回归与产物（v0.29）

- `bash build.sh` → 断言 `10998` 条 / 失败 `0` / 用例 `96/96`
- `node tools/audioflow-test.js --rounds 4` → **20/20**（T7 自愈 117ms）
- `AUDIOHUB_E2E_PORT=19346 node tools/ui-e2e.js` → `ALL CHECKS PASSED`
- `run-spatial-check.ps1` → `PASS: 23182 项检查全部通过`
- `run-graphfit-check.ps1` → `checks=249 fails=0`
- APK `build/audiohub-source.apk` = **456,791 B**，`versionCode='29' versionName='0.29'`，
  sha256 `f434dc8c3ed0d8190db48721a6c8a69436db759f5274b702ac69980e545f32e0`
- PC `ui/dist/AudioHub-便携版.exe` = **79,185,545 B**、
  `AudioHub Setup 0.1.0.exe` = **79,478,721 B**
- 行尾：`ui/renderer.js` 纯 CRLF（1310 行）、`tools/check-audible.js` 纯 LF（290 行）、
  本文件纯 LF

## 「我有发，你没收」到底是哪一头出了问题？（v0.31）

手机显示「发送中 · N 包」，电脑却报「星空里只有你自己，对端还没连上」——
这两句可以同时为真，而且一直为真也没人会发现，因为**它们说的是两件事**。

### 为什么「手机说在传音」不能当证据

`android/app/src/main/java/com/audiohub/source/CaptureService.java` 的发送循环是：

```java
target.send(datagram);
sPackets++;
```

`sPackets++` 只在 `send()` 没抛异常之后执行。而 UDP 的 `send()` 成功**只表示本机
把包交给了协议栈**，不表示包离开了本机，更不表示对端收到。手机界面上
「发送 N 包」的准确含义是「N 次 `send()` 没有报错」。它无法证明电脑收到过任何一个字节。

真正能判定的是**接收端**：电脑有没有真的收到 UDP 包。
v0.30 起 `HubStats` 有了 `rx_packets` / `rx_bad_packets` / `rx_distinct_senders` /
`rx_last_ip` / `rx_last_port` / `rx_last_reject`，v0.31 把它们显示出来。

### 三种情形，三种结论

| 电脑状态栏显示 | 含义 | 该查什么 |
| --- | --- | --- |
| `接收 未收到包` | 一个 UDP 包都没到达本机进程 | 网络层：对端没点「送入」／手机记着旧 IP／防火墙／不在同一网段 |
| `接收 N 包` 但图谱仍只有自己 | 包到了，但没建成音频流 | 协议/格式层：看括号里的「最近一次拒收原因」 |
| `接收 N 包` 且图谱里有对方 | 链路通了 | 接着看「出声 X/Y 帧」判断有没有混出声音 |

`tools/check-audible.js` 会把上面这张表直接打成一句话：

```
✗ 一个音源都没接入：星空里只有你自己，对端还没连上（本机一个包都没收到）
  先解决连通性：
    1. 对端 App 里点「送入」，并确认录屏授权弹窗点了 START NOW
    2. 手机可能记着上次的旧 IP —— 电脑换过网段后必须重新扫描配对
    3. 便携版每次解压到新的临时目录，防火墙规则按完整路径匹配，可能没有放行规则：
       New-NetFirewallRule -DisplayName "AudioHub 入站" -Direction Inbound `
         -Program "<本程序路径>" -Action Allow -Profile Public
```

包到了但没建成流时，则只补一句「问题在协议/格式，不在网络」+ 最近一次拒收原因。

### 手机端也把目标地址写出来了

只显示「发送 1000 包」时，用户永远发现不了「其实在往一个已经不存在的地址发」。
v0.31 起手机状态行是：

```
发送中 · 1024 包 → 10.135.129.59:47000 · -22.3 dBFS · 通道 1
```

箭头右边就是 `CaptureService.sHost:sPort`，也就是 `EXTRA_HOST` / `EXTRA_PORT`
实际拿到的东西。这个地址来自 `MainActivity` 保存的 `hub_ip` / `hub_port`
（`prefs.getString("hub_ip", null)` / `prefs.getInt("hub_port", 47000)`），
**是持久化的，换网段不会自动失效** —— 这正是「双方都以为自己在正常工作」的经典成因。

### 便携版与防火墙（已缓解）

`ui/package.json` 之前只有 `target: ["portable","nsis"]` + `asarUnpack: ["bin/**/*"]`，
便携版每次运行都解压到 `%TEMP%\<随机名>\`。Windows 防火墙按**程序完整路径**匹配规则，
所以每换一次目录，之前点过的「允许」就不再命中，而新目录通常没有任何规则
（入站默认 `NotConfigured` = 阻断）。

v0.31 给 `build.portable` 加了 `"unpackDirName": "AudioHub"`，解压目录固定为
`%TEMP%\AudioHub\`，点过一次「允许」之后规则能一直命中。

注意：本机历史上已经积累了约 120 条 `*audiohub*` 防火墙规则，其中 2 条是
Block（指向一个早已不存在的旧解压目录）。它们不影响当前路径，但说明
「被点过取消」这件事确实发生过。要清掉的话：

```powershell
Get-NetFirewallRule -DisplayName '*audiohub*' | Where-Object { $_.Action -eq 'Block' } | Remove-NetFirewallRule
```

### 关于「自动检查防火墙」的一个失败尝试

我曾想给界面加一个「测一下本机能不能被外部访问」的按钮（`ui/netcheck.js`），
实现是本机往自己的地址发包看能不能收到。**这个自测是无效的**：本机自寻址不经过
防火墙入站链，无论有没有规则都会「通」，只会给出假安全感。该模块已删除，
判据改为「接收端到底有没有收到包」这个可以直接观测的事实。

### 回归

- `bash build.sh`：断言 **10998** 条，失败 0 条，用例 **96/96**
- `node tools/audioflow-test.js --rounds 4`：**20/20**（T7 自愈 114ms）
- `AUDIOHUB_E2E_PORT=19351 node tools/ui-e2e.js`：`ALL CHECKS PASSED`
- `run-spatial-check.ps1`：`PASS: 23182`
- `run-graphfit-check.ps1`：`checks=249 fails=0`
- APK v0.31 `456,791 B`（`versionCode='31' versionName='0.31'`，
  sha256 `d7a9afb930e699cb6a39fd6d8b2b884e2109636c06e2df685346241c6473d3df`）
- PC 便携版 `79,196,636 B` / 安装版 `79,489,844 B`

## 两端在图谱里互相看得见，却说「没回应」（v0.32）

### 现场长什么样

- 电脑和手机的星空里，**都能看见对方那颗星球**
- 手机点「邀请」→ 提示「电脑 192.168.x.x:47000 没有回应」
- 电脑点「邀请」→ 提示「邀请 xxx 无响应」
- 电脑状态栏：`接收 未收到包`
- `node tools/check-audible.js` 报「一个音源都没接入」

这三条同时出现时，最容易被误判成「网络全断」。**其实不是。** 它有一个非常具体的指纹。

### 为什么：三个端口各管一件事

| 端口 | 用途 | 走什么 |
| --- | --- | --- |
| `audioPort`（默认 47000） | 媒体音频包 | 单向，量大，UDP |
| `audioPort + 1`（47001） | 控制 | 少量 |
| **`audioPort + 2`（47002）** | **握手 / 图谱 / 摆位** | `CONN-REQ`、`CONN-ACK`、`CONN-INVITE`、`GRAPH`、`POS` |
| 47300（固定） | 设备发现 | mDNS 式 UDP 广播 |

关键点：**「星空里能看见对方」靠的是 47300 的广播，完全不需要 47002。**
所以「看得见 + 说没回应」= **广播通了、47002 被挡住了**，这两个事实并不矛盾。

再往上，`rxPackets`（v0.30 加的接收侧计数）只统计 **47000**。
所以它显示 0 也只证明「媒体口没收到」，**不能证明握手口也没收到**。
v0.31 之前，我的诊断工具就卡在这个盲区里：把「只有 47002 不通」误报成「网络层完全不通」。

### v0.32 做了什么

给握手口加了独立计数（`HandshakeLoop()` 里，在解码之前记账，和解码失败与否无关）：

```
hs_packets       握手口收到的数据报总数（只增不减）
hs_recent        最近 10 秒的握手包数（近似，10 秒无包归零）
hs_last_ip/port  最近一个握手包的来源
hs_last_cmd      最近一条命令首段（CONN-REQ / GRAPH / POS / CONN-INVITE …）
hs_last_line     最近一条原文（截断 120 字节）
discovery_peers  当前发现列表里的设备数
```

`/api/state` 里对应 `hsPackets` / `hsRecent` / `hsLastIp` / `hsLastPort` / `hsLastCmd` / `hsLastLine` / `hsPort` / `discoveryPeers`。

界面上多了「握手」那一格，`node tools/check-audible.js` 也会打印：

```
握手口包数       : 一个都没收到，端口 47002
发现到的设备     : 2 台（广播 47300）
```

`whyNoSource()` 的判据也跟着拆了：

| 握手口 | 广播 | 结论 |
| --- | --- | --- |
| 0 包 | 看得见设备 | **47002 被挡**：广播能到、这个端口不能。查防火墙针对这个端口 |
| 0 包 | 也看不见设备 | 同一网络都没通，先查 Wi-Fi / 网段 |
| 有包、但没建成流 | — | 包到了、命令没匹配上：查协议版本，看 `hsLastCmd` |
| 有包、也建成流 | — | 握手口正常，问题在更上层 |

界面上会直接显示成：

> 对端在图谱里，但握手口 47002 一个包都没收到（广播能看见它、这个端口被挡了，所以两端互报「没回应」）

### 怎么修（如果确实是 47002 被挡）

Windows 这边最常见的原因是**便携版每次启动解压到 `%TEMP%\<随机名>\`**，
防火墙规则按程序完整路径匹配，所以上一次点过的「允许」对新目录无效。
v0.31 已经给 `electron-builder` 加了 `unpackDirName: "AudioHub"` 固定目录名，
但**已经存在的历史 Block 规则不会自己消失**，需要手动看一眼：

```powershell
# 1) 看看有没有指向 AudioHub 的 Block 规则
Get-NetFirewallRule -DisplayName "*audiohub*" | Where-Object { $_.Action -eq "Block" } |
  Select-Object DisplayName, Direction, Profile, Action

# 2) 如果 Program 指向的是已经不存在的老目录，删掉它
Get-NetFirewallRule -DisplayName "*audiohub*" | Where-Object { $_.Action -eq "Block" } | Remove-NetFirewallRule

# 3) 给当前程序显式放行（管理员 PowerShell）
New-NetFirewallRule -DisplayName "AudioHub 入站" -Direction Inbound `
  -Program "<你的 AudioHub 路径>\resources\app.asar.unpacked\bin\audiohub-hub.exe" `
  -Action Allow -Profile Public
```

另外两个高频原因（v0.31 起界面上会直接显示发送目标）：

- **手机换过网段，但仍往旧 IP 发**：`prefs` 里存着上一次的 `hub_ip`。
  手机状态行现在会显示 `发送中 · N 包 → 192.168.x.x:47000`，看这个地址对不对。
- **只点了「设为音源」，没点「送入」**：采集服务没起，一个包都不会发。

### 验证方法

```bash
node tools/check-audible.js --seconds 10
```

输出里现在有两行是决定性的：

- `接收包数` —— 媒体口（47000）
- `握手口包数` —— 握手/图谱口（47002）

两者都是只增不减的计数，**怎么轮询都不会被读空**（这一点是 v0.27 才修好的：
之前的 `Meter::Take()` 是「取走即清零」，Electron 每 200 ms 轮询一次，
我再用 curl 读一次必然是空的，导致前几轮结论反复横跳）。

### 本轮的验证（全绿）

- `bash build.sh` → 断言 **10998** 条，失败 0，用例 **96/96**
- `node tools/audioflow-test.js --rounds 4` → **20/20**
- `AUDIOHUB_E2E_PORT=19352 node tools/ui-e2e.js` → `ALL CHECKS PASSED`
- `run-spatial-check.ps1` → `PASS: 23182 项检查全部通过`
- `run-graphfit-check.ps1` → `checks=249 fails=0`
- 握手口探针 `D:/tmp/_hs32_probe.js` 三情形全 ✓：
  ```
  ① 没发包            hsPackets=0                                  rxPackets=0 sources=0
  ② 发 5 个垃圾包      hsPackets=5  hsCmd="HELLO-WORLD-NOT-A-COMMAN"  rxPackets=0 sources=0
  ③ 发合法 CONN-REQ    hsPackets=8  hsCmd="CONN-REQ"  sources=1 acked=true  rxPackets=0
  ```
  第 ③ 项尤其重要：**握手通了、流建成了，而音频口仍是 0 包** —— 这正是当前现场的中间态，
  证明这两个口确实是分开记的。
- `tools/check-audible.js` 三情形端到端：
  - A 什么都没发 → `✗ …（本机一个包都没收到）` + 3 条排查
  - B 只发握手包 → `握手口包数 : 3`、`最近握手来源 : … 命令 CONN-REQ`，结论指向「采集端抓到的就是无声」
  - C 握手 + 音频都通 → `✓ 确实在出声：本窗口有 11549 帧真的带着声音送进了声卡（峰值 -5.7 dBFS）`
- 界面判定树突变换证：删掉「握手口 0 包 + 广播可见」这一支后，
  十种情形从 `distinct 10 of 10` 退化成 `9 of 10`（与「真离线」撞成同一句话），还原后恢复 10/10。
  **这一步是为了确认新判据真的接线，而不是只写了没生效。**

### 产物

- APK `build/audiohub-source.apk` = **456,791 B**，`versionCode='32' versionName='0.32'`
- PC `ui/dist/AudioHub-便携版.exe` = **79,199,958 B**、`AudioHub Setup 0.1.0.exe` = **79,493,169 B**

> 这一版仍然只是**让「哪个口不通」变成可读证据**，没有声称修好了双端不通本身。
> 装上之后请再看一眼状态栏：`接收` 和 `握手` 两格分别是 0 还是有数，就能定方向。

## 「两端都显示对方没响应」到底断在哪一环？（v0.34）

这一节能写出来，是因为 2026-11-08 那一轮**真的把整条链路跑通了**，而且跑通的方式
可以把责任一刀切开。所有数字都是实测，不是推断。

### 决定性实测：链路上每一环都是好的

环境：PC 当汇聚端（`audiohub-hub.exe --port 47000`），手机是 Android 14 x86_64 模拟器，
装的是本仓库当次构建的 APK，采集授权弹窗点过「START NOW」，`CaptureService` 在运行。

**手机没在放声音时**（这就是用户现场看到的「采集到的是静音」）：

```
rxPackets        = 242838         ← 包一直在到
sources          = 1 路 confirmed  ← 流建成了
mixedSources     = 1              ← 真的有一路进了混音器
mixedFrames      = 29140800       ← 混音器确实在混
mixedAudibleFrames = 0            ← 混出来的全是零
renderAudibleFrames = 0
renderPeakDbfs   = -240
```

**在手机上真的播放一段 440 Hz 音频之后**（同一台 hub，什么都没改）：

```
mixedAudibleFrames += 1261680  →  1372560  →  1484880
renderAudibleFrames = 1253845
renderPeakDbfs      = -7.95911 dBFS
```

**再把媒体音量调到 0、退出播放器**：

```
mixedAudibleFrames 冻结在 2921280 不再增长
renderAudibleFrames 冻结在 2920811
```

**结论**：PC 侧的握手、建流、混音、渲染、落盘**每一环都正常**。
`mixedAudibleFrames === 0` 只有一个成因 —— **手机端没在播放媒体声音**，
采到的样本本身就是一片零。它和网络、防火墙、混音参数、输出设备**全都没有关系**。

### 判据铁律（这一条能省掉好几轮排查）

| 观测 | 含义 | 该查什么 |
| --- | --- | --- |
| `mixedAudibleFrames > 0` | 汇聚端**真的在出声** | 听不见就查汇聚端的音量/静音开关/输出设备 |
| `= 0` 且 `mixedSources > 0` | 数据到了，内容是零 | **查采集端**：它在播媒体声音吗？授权过了吗？ |
| `= 0` 且 `mixedSources = 0` | 一路都没混进来 | 查连通性/确认状态/静音，见上一节九支判定 |

### 为什么前几轮一直查不出来

三个观测缺口，都在手机这边，v0.33/v0.34 才补上：

1. **静音提示会把关键数字顶掉**。`MainActivity` 的 `sourceState` 以前在静音时用
   `silentHint` **整行替换**掉 `发送 N 包 → ip:port` —— 用户报「采集到的是静音」
   的那一刻，屏幕上恰好**没有**「发了多少包、发到哪」。而这两个数正是判断
   「包有没有出去、地址错没错」的唯一依据。v0.33 起改为
   `silentHint + 换行 + sendFacts`，两个都在。
2. **读失败与正常读取无法区分**。`CaptureService.workerLoop` 里 `read() <= 0`
   的非致命分支以前只 `continue`，不计数。于是「一直读不到声音」和
   「好好地读着、只是没凑满一包」在界面上完全一样。v0.33 加了 `sReadZero` /
   `sReadTotal`，现在提示能直接说「采集一直读到 0（抓不到该应用的声音）」。
3. **发送异常不留痕**。`send()` 抛异常时只写一个会被下一帧覆盖的 `sStatus`。
   v0.33 加了 `sSendErrors` / `sLastSendError`，界面会显示「发送失败 N 次 · 异常类名」。

另外补了一个交叉校验：手机图谱里的汇聚端是 A、`CaptureService` 实际发往 B 时，
状态行会直接报警「⚠ 图谱里的汇聚端是 X，实际却发往 Y」。手机记着上次的旧 IP
（`hub_ip` 持久化在 SharedPreferences 里）这种错，以前永远是静默的。

### PC 侧的提示也改了（v0.34）

`ui/renderer.js` 的「出声 0/N」在 `mixedSources > 0` 时，以前只说
「（混入了 1 路，但混出来是静音）」。这句话是**现象**，不是**下一步**。
现在直接写：

> （音频数据到了 CPU、混音器也在混，但内容全是零 —— 问题在采集端：
> 手机没在播放媒体声音，或放的声音不属于媒体类型）

`AudioPlaybackCapture` 只抓得到 `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`
三类声音。通话、铃声、部分 App 自定义 usage 的声音**抓不到**，这是 Android 的
设计限制，不是本项目的缺陷。

### 这一版仍未解决什么

- **「两端互报没响应」没有在真机上复现过**。本轮所有协议层实验都跑在模拟器上，
  而 qemu 的 user-mode NAT 会**改写源 IP**（模拟器发包到达宿主时源 IP 变成宿主 IP），
  所以任何「两个节点」的协议验证在模拟器上都无效。这条只能靠真机。
- 便携版（`AudioHub-便携版.exe`）每次解压到 `%TEMP%\<随机目录>`，而 Windows 防火墙
  规则是按程序完整路径匹配的 —— 新目录没有规则时，入站会被默认阻断。
  v0.31 已加 `build.portable.unpackDirName = "AudioHub"` 固定目录名，但
  **这一条未经实机验证**（本轮 `Start-Process` 便携版没能真正启动起来）。
  如果真机上出现「一个包都收不到」，先用 `New-NetFirewallRule` 给实际路径放行。

## 为什么两端都显示对方没响应、音频口一个包都没有？（v0.36）

这一节记录一次真实的排查：现场是**手机和电脑连在同一个 WiFi**，两边的界面都写着
「对方没有回应」，手机显示自己在传音，电脑那台一个音频包都没收到，一个声音都没有。
最后发现**不是程序的问题，是电脑上装了代理软件**。

### 一句话结论

> 电脑上跑着 Clash 之类的代理工具，开着 **TUN / 增强模式**。它会在系统里建一张虚拟网卡
> （名字通常叫 `Meta` / `utun` / `Tun`），抢走默认路由。结果是：**凡是发往「和电脑不在同一网段」
> 的 IP，包都会被这张虚拟网卡接走，永远出不了电脑。**

而手机上拿到的地址经常就是别的网段（校园 WiFi 换 AP、手机热点是 `192.168.x` / `172.20.10.x`、
不同楼层不同网段……），于是：

- 电脑发给手机的**握手包**（`audioPort + 2`）→ 进了代理 → 手机永远收不到 → 手机显示「电脑没响应」
- 手机发给电脑的包**如果也走错了路**（对端地址不对 / 被隔离）→ 电脑同样收不到 → 电脑显示「手机没响应」
- 两边的「发现广播」（47300）是**子网内广播**，反而可能还通 → 所以**星空里能看见对方那颗星球**
- 但音频口、握手口一个包都没有 → 界面上就是「未收到包」「握手 未收到」

### 怎么一眼确认

v0.36 起，`/api/state` 多了两组字段，界面和命令行工具都会直接说出来：

| 字段 | 含义 |
|---|---|
| `localNics[]` | 本机**真实**网卡清单，形如 `10.135.129.59/16 gateway=no name=WLAN` |
| 每个节点的 `egressSrc` | 本机发往这个对端时，系统会用的**源地址** |
| 每个节点的 `egressOk` | 这个源地址是不是落在了本机的真实网卡上 |
| 每个节点的 `egressHijacked` | **`true` 就是这个对端的包会被代理 TUN 接走** |

命令行里跑一次就能看到：

```
  本机网卡         : 10.135.129.59/16 gateway=no name=WLAN
  ⚠ 出站被接管    : 192.168.43.77:47000 的包会被代理 TUN 接走（源地址 198.18.0.1），永远到不了对端。
    处理办法      : 关掉 Clash / 代理的 TUN（增强）模式，或让两端处于同一网段。
```

界面状态栏也会多出一个「网卡」格：正常显示「网卡 1 张」，被接管时变成「网卡 ⚠ 被代理接管」，
鼠标悬停有完整解释。而「没有音源混进来」那句话会直接变成：

> 本机发往 192.168.43.77:47000 的包被代理 TUN 接走了（源地址会变成 198.18.0.1，
> 对方的包也回不来） —— 请先关掉 Clash / 代理软件的 TUN（增强）模式

### 为什么这一层特别难查

因为**收和发走的是两套完全不同的逻辑**：

- 收：三个 socket 都绑 `0.0.0.0`（`src/udp_socket.cpp` 的 `UdpSocket::Open`），
  所以**任何网卡进来的包都能收到** —— 收包侧没有任何「绑错网卡」的问题
- 发：`sendto` 直接用系统路由表（`UdpSocket::SendToAddr`），**代码里没有任何固定出站网卡的逻辑**
- 于是「收得到、发不出去」这种半通状态在应用层完全看不出来，只有去查路由表才发现

### 为什么不干脆在代码里强制走 WLAN

试过，用 `IP_UNICAST_IF`（socket 选项 31）把出站钉到 WLAN，跨网段的源地址确实从
`198.18.0.1` 变回了 `10.135.129.59`。但**回环发送会直接失败**（`WinError 10049`），
而 `tools/audioflow-test.js` 这类回归全部依赖 `127.0.0.2` 同机双进程 —— 一改就全打死。
更重要的是：用户的另一类可能原因是**校园 WiFi 的 AP 客户端隔离**（见下），固定网卡救不了它，
却会引入新故障。所以最终选择**只做只读诊断，把事实摆到界面上**，不擅自改变出站行为。

### 另一类同样"看起来像程序坏了"的原因：AP 客户端隔离

如果两端确实在**同一个 WiFi、同一个网段**，但还是互不可达，那么查一下 ARP 表：

```powershell
Get-NetNeighbor -AddressFamily IPv4 | Where-Object { $_.IPAddress -like '10.135.*' }
```

如果发现**好几个不同 IP 的 MAC 地址全是网关那一个**（代理 ARP / 大二层），
再用 `ping` 打其中几个，全都不通 —— 那多半是**校园网 / 公共 WiFi 开了客户端隔离**，
同一 WiFi 下的设备之间禁止互相访问。这一类应用层绕不过去，可用办法：

1. **手机开热点，电脑连手机热点**（两端直连，没有 AP 隔离）
2. 用手机数据线做 USB 网络共享
3. 换一个允许设备互访的路由器 / WiFi

### 排查顺序（照着做就行）

1. 看界面状态栏有没有「⚠ 被代理接管」→ **有就关代理的 TUN 模式**，这一步最常见
2. 没有的话，看「网卡」格显示的 IP 是不是你以为的那张网卡
3. 两边都确认之后仍然不通 → 试「手机开热点、电脑连热点」
4. 以上都做了还不通 → 跑 `node tools/check-audible.js --port 4780`，
   它会把这个对端的 `egressSrc` / `egressHijacked`、握手口包数、发现到的设备全部打出来

### 这一轮改了什么

| 位置 | 改动 |
|---|---|
| `src/udp_socket.cpp` | 新增 `ProbeEgress()`（`connect()` + `getsockname()`，**不发任何包**）与 `RealNicSummary()`（真实网卡清单，复用 `LocalIPv4List` 的虚拟网卡剔除规则） |
| `apps/hub_main.cpp` | `/api/state` 顶层加 `localNics[]`；每个 graph 节点加 `egressSrc` / `egressOk` / `egressHijacked`；带 2 秒缓存（UI 每 200ms 轮询，不能每拍建 socket） |
| `ui/renderer.js` | `whyNoSource()` 最前面插「被代理 TUN 接走」判定；新增 `egressSpan()` 显示在状态栏 |
| `tools/check-audible.js` | 同样插入该判定；输出多「本机网卡」与「⚠ 出站被接管」两行 |

验证：`bash build.sh` 断言 **10998** 条、失败 0、用例 **96/96**；
`node tools/audioflow-test.js --rounds 4` **20/20**；
`AUDIOHUB_E2E_PORT=19356 node tools/ui-e2e.js` **ALL CHECKS PASSED**；
`run-spatial-check.ps1` **PASS: 23182**；`run-graphfit-check.ps1` **checks=249 fails=0**。
构造性验证：用图谱帧注入一个 `192.168.43.77` 节点后，
`egressSrc` 变成 `198.18.0.1`、`egressHijacked=true`；同网段的 `10.135.7.56` 仍是 `false`。

## 已知限制

- **真机仍未验证，只在模拟器上跑过**。v0.7 已在 Android 14 x86_64 模拟器（WHPX）上走通
  「连接 → 邀请/同意 → 授予采集 → 进控制屏持续发送 → 断开 → 重新邀请 → 再同意」全链路，
  图标、断连、采集授权都实测过；但厂商 ROM 的省电策略、后台限制、真实音频路由仍需真机复验。
- **v0.8 的两处修复里，「手机当汇聚端不闪退」只在模拟器上做了定向验证**（两次前台服务启动后
  进程存活），完整 UI 路径没跑通——本机模拟器 qemu 一启动音频采集就段错误（见上一节），
  「持续采集发流」这条仍需真机复验。
- **进程回环只在 Win10 2004+ 上支持**，这里指的是 Hub 端，Android 端不需要。
- **Android 14 起每次会话都要重新授权** MediaProjection，不能长期有效。
- **DRM 内容抓不到**（Netflix、部分音乐平台会主动拒绝被采集）。
- 自动发现依赖 UDP 广播；跨网段/广播被拦时仍需手动填 IP。
- 前台服务 + WifiLock + WakeLock 都加了，但**还没在不同厂商的省电策略下验证过**。

## 源码

| 文件 | 职责 |
|---|---|
| `PacketProtocol.java` | 16 字节音频线格式，必须和 PC 端 `packet.hpp` 逐字节一致 |
| `CaptureService.java` | 前台服务、MediaProjection、AudioRecord、节拍发送、WifiLock |
| `MainActivity.java` | 界面（纯代码搭，没有 XML 布局）：连接页 / 控制页、全自适应无滚动 |
| `HubGraphView.java` | 俯视只读平面图：正交映射、方位/环、球体/电平弧/选中高亮、点击/双击 |
| `GraphFit.java` | 俯视图视野自适应的纯数学：节点世界坐标/标签盒 → ppu 与视野半径 |
| `PlacementStore.java` | POS 帧严格编解码 + 整表 LWW 合并（与 `src/graph.cpp` 对齐） |
| `InviteListener.java` | 常驻握手口（audioPort+2）：CONN/ROLE/GRAPH/GRAPH-REQ/POS/POS-REQ |
| `GraphStore.java` | 图谱状态 + GRAPH 帧编解码 + 汇聚端切换 |
| `Scene3D.java` | 纯 Java 数学：确定性自动槽位、球半径/电平换算（与 PC `ui/scene3d.js` 对齐） |
| `ReceiverService.java` | 接收端：UDP 收流、抖动缓冲、空间化混音、本地播放 |