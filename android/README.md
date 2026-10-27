# AudioHub 源端（Android）

把手机正在播放的声音采集下来，通过 UDP 送到汇聚端（PC 上的 `audiohub-hub.exe`，或另一台
当汇聚端的手机）。这是**跨端音频系统**里“N 个源设备 → 1 个汇聚端”的手机侧实现：
同一套代码既能当音源，也能当接收端/汇聚端。

## 装

APK 已经打好了，在 `android/build/audiohub-source.apk`。

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

## 图谱：俯视平面图（v1.5 起）

控制页的图谱是**从上方俯瞰的只读平面图**（不再是 3D 透视）：

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

## 已知限制

- **没有实机验证过**。本机没有连着 Android 设备，所以只验证到「APK 能打包、
  badging 正确、签名有效、manifest 里 service 类型和权限齐全」，没有跑起来看过。
  真机上第一次跑大概率会遇到需要调的东西。
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