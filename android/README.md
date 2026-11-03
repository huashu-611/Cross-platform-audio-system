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

5. 开始采集后手机会**自动把媒体音量静音**，保证只有汇聚端的耳机出声；停止采集/断开/
   服务销毁或异常退出时恢复原音量。若个别机型上「静音」会连带掐掉采集
   （自愈：静音后采集电平持续 ≥3 秒静音、而系统确实在放声音却抓不到信号），
   服务会自动取消静音并打一次警告日志。

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