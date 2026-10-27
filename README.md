# Cross-platform-audio-system

把多台设备各自正在播放的声音，同时送到**同一台设备**的扬声器或耳机上播出来。

- 拓扑：**N 个源 → 1 个输出端**（不是多房间同步播放，那个方向是反的）
- 输出端：Windows PC（已实现）、Android（规划中）
- 首要指标：低延迟、音画同步

> 完整的方案调研、竞品对比、延迟预算与取舍分析见
> [`docs/跨端音频汇聚系统-技术调研与架构设计.html`](docs/跨端音频汇聚系统-技术调研与架构设计.html)

---

## 现状

| 能力 | 状态 | 说明 |
|---|---|---|
| UDP 传输协议（16 字节包头） | ✅ | `include/audiohub/packet.hpp` |
| 无锁 SPSC 环形缓冲 | ✅ | 设备回调只 memcpy |
| 每源独立抖动缓冲 | ✅ | 按绝对采样序号索引，支持部分迟到裁剪 |
| 时钟同步（ping/pong + 中位数） | ✅ | 实测回环 RTT 0.3~0.6 ms |
| ASRC 时钟漂移补偿 | ✅ | 4 点 Catmull-Rom，±0.2% |
| 外环：PI 锁水位 + 追帧 | ✅ | 把实际延迟锁到 `--latency` |
| N 路混音 + 软限幅 | ✅ | float32 累加，阈值 0.85 |
| WASAPI 系统音频采集 | ✅ | loopback + AUTOCONVERTPCM |
| 进程回环 `--include/--exclude` | ✅ | Win10 2004+，只抓/排除指定进程树 |
| Android 源端（采集） | ✅ 已真机验证 | `android/`，APK ≈ 334 KB |
| Android 接收端（播放/混音） | ✅ 已实现 | `ReceiverService`：AudioTrack 混音输出；图谱改为**俯视平面图**（只读，不能拖） |
| Windows 图形界面 | ✅ | `ui/` Electron 壳，**3D 房间视图**（俯仰/轨道/滚轮缩放）+ 每路音量/静音 + 总音量 |
| PC 自己当音源 | ✅ | 非汇聚端时界面自动拉起 `audiohub-source`，音量/静音联动 |
| 本地 HTTP 控制接口 | ✅ | `--http-port 4780`；含 `/api/state.graph`、`/api/graph/switch`、`/api/graph/placement` |
| 共享网络图谱（双端） | ✅ | 设备图谱 + 图上右键菜单切换汇聚端（左键只拖动），全网同步（[协议](docs/GRAPH-PROTOCOL.md)） |
| 房间摆位全网同步 | ✅ | 桌面拖完的落点经 POS 帧广播给所有电脑/手机；手机俯视图只读显示（[§13](docs/GRAPH-PROTOCOL.md)） |
| WASAPI 低延迟输出 | ✅ | 事件驱动，默认 10 ms 缓冲 |
| 内置信号源 `--tone` | ✅ | 无音频设备也能测；两路正弦叠加可数值验证混音 |
| 抖动注入 `--jitter` | ✅ | 模拟 WiFi，验抖动缓冲 |
| 电平表 | ✅ | 每路 + 混音总线 dBFS |
| 单元测试 | ✅ | **85 个用例**（含图谱协议 23 个、房间摆位 7 个），无需声卡 |
| Opus 编码 | ⬜ | 接口已预留，P0 用 int16 PCM |
| Android 低延迟输出 | ⬜ | 现用 AudioTrack，未上 AAudio |
| 设备发现 / 配对 | ✅ | UDP 广播发现 + 手工邀请 + 自动同意；未上 mDNS |
| 蓝牙输出拦截 | ⬜ | 官方警告：蓝牙会 +150~250 ms |

**这不是完成品，是可运行的 P0 骨架。** 传输、缓冲、混音、时钟这几块核心逻辑是完整的，
平台适配和体验层还没做。

---

## 架构

```
 源 A (Windows)          源 B (Windows)          源 C (macOS, 规划)
 WASAPI loopback         WASAPI loopback         Core Audio Tap
      │                       │                       │
      ├─ int16 分包 ──────────┼───────────────────────┤
      │  16B 头 + 960B 载荷    │                       │
      ▼                       ▼                       ▼
 ┌──────────────────────────────────────────────────────────┐
 │  HUB (Windows)                                           │
 │                                                          │
 │  [接收线程]  UDP → 解码 → 每源 JitterBuffer              │
 │                                    │                     │
 │  [混音线程]  每 5 ms: JB → ASRC → 求和 → 软限幅 → 输出环 │
 │                                    │                     │
 │  [渲染线程]  WASAPI 实时回调：只从输出环 memcpy          │
 │                                                          │
 │  [主线程]    统计 + 控制口（应答 PING）                   │
 └──────────────────────────────────────────────────────────┘
                          │
                扬声器 / 3.5 mm / USB-C DAC
                （蓝牙耳机 = +150~250 ms，不要用）
```

线程划分的唯一原则：**硬实时只留给渲染回调**。它只做 `memcpy`，
不加锁、不分配、不打日志。其余所有工作都在普通线程上，晚了就晚了，
由输出环吸收。

---

## 构建

### Windows（MSYS2 / MinGW-w64，推荐）

```bash
# 安装工具链（只需一次）
pacman -S mingw-w64-ucrt-x86_64-gcc

# 没有 cmake 时用自带脚本（等价）
./build.sh

# 有 cmake 的话
cmake -S . -B build -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build -j
```

产物在 `build/bin/`：

- `audiohub-source.exe` — 采集端
- `audiohub-hub.exe` — 汇聚端
- `build/audiohub_tests.exe` — 测试

### 测试

```bash
ctest --test-dir build --output-on-failure
# 或直接跑
./build/audiohub_tests.exe
```

测试只覆盖平台无关的核心层，**不需要声卡**，可以在 CI 里跑。

### Windows 一键脚本（本项目的实测环境，无需 cmake）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1        # 编译全部 + 跑单测
powershell -NoProfile -ExecutionPolicy Bypass -File tools\graph-smoke.ps1  # 图谱冒烟：起两个 hub 进程互发 GRAPH，结束自动清理
node tools\ui-e2e.js                                                       # Electron 界面端到端（开发态；打包态设 AUDIOHUB_UI_EXE=ui\dist\AudioHub-便携版.exe）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-apk.ps1    # 重新打包 Android APK
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe-rx.ps1 -Secs 12  # 直连源端 UDP 量时间轴空洞/丢包（排查音频卡顿、电流音）
javac -d build\mixcheck-out tools\mixcheck\MixCheck.java; java -cp build\mixcheck-out MixCheck  # 混音标度数值对照（不需要 Android SDK）
```

> 脚本内部会先把 MSYS2 的 `bin` 加进 `PATH`、并统一用相对路径编译——
> MinGW 的 `ld` 处理带中文的绝对路径会直接失败（而且报错信息帮不上忙）。
> `build-apk.ps1` 走 aapt2 + javac(JDK17) + d8 + apksigner，不依赖 Gradle。
> `probe-rx.ps1` 不启动汇聚端，直接收 `audiohub-source` 的包：它区分"源端时间轴空洞
> （seq 连号但 sample_pos 跳 480）"和"网络丢包（两者一起跳）"，是排查噪声/卡顿的第一站。

### Visual Studio

```powershell
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config Release
```

---

## 运行

### 图形界面版（推荐日常用）

PC 端的正式交付形态是**一个 exe**：`ui/dist/AudioHub-便携版.exe`（单文件，内含 Electron
运行时 + C++ 音频核心 + 界面，双击即用，不需要装 Node/npm）。它只是个"果壳"：
窗口里跑的是本仓库的网页界面，音频仍然是同一套 C++ 核心（打包在 exe 内，启动时释放到
临时目录运行）。

```bash
# 拿到 exe（开发者，先 cd ui && npm install）
cd ui && npm run dist        # 产物：ui/dist/AudioHub-便携版.exe

# 开发态调试界面（不打包，改完代码刷新即可）
cd ui && npm start
```

> 虚拟机 / 无 GPU 环境双击便携版若起不来，给 exe 加参数
> `--no-sandbox --disable-gpu` 再启动（正常 PC 不需要）。

> **两个环境坑（都会以莫名其妙的错误呈现）**
>
> 1. 某些 Electron 宿主环境会给子进程设 `ELECTRON_RUN_AS_NODE=1`，
>    Electron 就退化成纯 Node，`require('electron')` 返回的是二进制路径而不是 API。
>    表现是 `Cannot read properties of undefined (reading 'whenReady')`。
>    先 `unset ELECTRON_RUN_AS_NODE` 再启动。main.js 里已加针对性报错。
> 2. 虚拟机 / 无 GPU 环境里 Chromium 的 GPU 进程会崩溃，整个应用起不来。
>    开发态用 `npm run start:sandbox-off`（即 `electron . --no-sandbox`）；
>    打包好的便携版 exe 则加参数 `--no-sandbox --disable-gpu` 启动。
>    正式打包产物在正常 PC 上不需要这个参数。

双击后自动做三件事：启动 C++ 音频核心、起本地 HTTP 控制口、打开界面窗口。
每一路源有独立音量滑杆 + 静音 + 实时电平，底部有总音量和目标延迟
（40 / 60 / 120 ms）。掉线的流自动从列表消失，音量会被记住。

界面中央是**全网共享的设备图谱**：网络里每台设备（PC / 手机）都是图上的一个球，
汇聚端是大球。**左键只负责拖动**——拖小球 = 在地面摆位，拖空白处 = 旋转视角；
**右键点小球**弹出节点菜单：「切换汇聚端到此设备」「音量与静音」「位置复位」。
切换后立即生效并带同步状态，同时把新图谱广播给所有设备；手机和另一台 PC 的界面
会在 1~2 秒内自动跟着变，不用每台设备各点一遍。协议与合并规则见
[docs/GRAPH-PROTOCOL.md](docs/GRAPH-PROTOCOL.md)。

图谱是**物理房间**的俯视投影（1 格 = 1 m）：听者固定在原点，球在地面上自由摆位，
球的屏幕大小随距离缩放（近大远小，与音量衰减同向）；左键拖动摆位、右键菜单里
「位置复位」回到自动槽位。**拖完的位置是全网一份**：写进本机内核后由 POS 帧同步给所有
电脑和手机（[协议 §13](docs/GRAPH-PROTOCOL.md)）；手机端用同一个坐标系画俯视平面图，
但只读。滚轮向上=凑近放大、向下=拉远；整个界面随窗口自适应，不出现滚动条。

窗口顶部直接显示**手机上该填的 IP**，点「复制」即可。

### 命令行版（脚本/测试用）

先起汇聚端（出声的那台机器）：

```bash
audiohub-hub.exe --port 47000 --latency 60
# 启动后会打印本机 IP，例如 192.168.1.20
```

再在源端机器上：

```bash
audiohub-source.exe --hub 192.168.1.20:47000
```

多台源设备同时跑第二条命令即可，Hub 会自动按 `stream_id` 分流混音。

**只有一台 PC 也能测多源**——开多个 source 实例，用不同 `--stream-id`：

```bash
# 终端 1
build/audiohub-hub.exe --port 47000 --latency 60
# 终端 2
build/audiohub-source.exe --hub 127.0.0.1:47000 --tone 440 --stream-id 1
# 终端 3
build/audiohub-source.exe --hub 127.0.0.1:47000 --tone 660 --stream-id 2
```

耳机里会同时听到 440 + 660。Hub 应当显示：单路 −9.0 dBFS、总线 −6.0 dBFS
（**差值 3.01 dB 就是混音正确的硬指标**）。

完整的判定标准和排错见表见 [docs/测试指南.md](docs/测试指南.md)。

### 参数

`audiohub-hub`

| 参数 | 默认 | 说明 |
|---|---|---|
| `--port` | 47000 | 数据口；控制口自动用 `port+1` |
| `--latency` | 60 | 目标端到端延迟（ms），可选 40 / 60 / 120 |
| `--render-ms` | 10 | WASAPI 输出缓冲，越小延迟越低、越容易断 |
| `--http-port` | 关 | 本地 HTTP 接口，图形界面靠它控制 |
| `--block` | 240 | 混音块大小（帧），240 = 5 ms |

`audiohub-source`

| 参数 | 默认 | 说明 |
|---|---|---|
| `--hub` | 127.0.0.1 | 支持 `ip:port` |
| `--stream-id` | 1 | 一台设备跑多路时区分 |
| `--cap-ms` | 20 | 采集缓冲，越小越激进 |
| `--tone <hz>` | 关 | 不发采集，改发内置正弦波 |
| `--jitter <ms>` | 0 | 零均值发送抖动，模拟 WiFi |
| `--include <exe>` | 关 | 只抓这个进程的声音 |
| `--exclude <exe>` | 关 | 排除这个进程，其它照抓 |
| `--verbose` | 关 | 每包打印，仅调试 |

### 实测（同机回环，`--latency 60`）

```
收 202 pkt/s (1577 kbps) | 坏包 0 | 源 1 路
stream 1  水位 56 ms (目标 60) | 比率 1.00018 | 迟到 0 溢出 0 | 欠载 2880 追帧 1
```

| 指标 | 实测值 | 说明 |
|---|---|---|
| 发包速率 | 202 pkt/s | 理论 200，多出的 1% 是 1 秒窗口的量化误差 |
| 码率 | 1577 kbps | 976 B × 200/s × 8 |
| 丢包 / 迟到 / 溢出 | 0 / 0 / 0 | 回环网络 |
| 时钟同步 RTT | 0.3~0.6 ms | 回环应得的量级 |
| 水位 | 51~66 ms，均值 ≈ 58 | 目标 60；±8 ms 是混音线程节拍抖动 |
| 追帧次数 | 1 | 只在启动突发时发生一次 |
| 欠载 | 2880 帧 | 就是启动时的 60 ms 初始静音，之后不再增加 |

**还没在真实两台机器 / WiFi 上测过**，上面的数字只证明链路逻辑正确，
不代表无线环境的表现。

### 验收标准（待测）

| 场景 | 目标 | 看哪里 |
|---|---|---|
| 有线 LAN | < 40 ms | source 的单程 RTT/2 + Hub 水位 |
| 5 GHz WiFi | < 80 ms | 同上，注意看「迟到/溢出」是否增长 |
| 连续 30 min | 无溢出、无持续欠载 | 两个计数应当接近 0 |

---

## 协议

固定 **16 字节包头 + 定长载荷**，全部小端。

```
 0                   1                   2                   3
+-------+-------+---------------+---------------+---------------+
| ver 4 |flags 4|    reserved   |        stream_id (16)         |
+-------+-------+---------------+---------------+---------------+
|                          seq (32)                             |
+---------------------------------------------------------------+
|                       sample_pos (32)                         |
+---------------------------------------------------------------+
|                     capture_ts_ms (32)                        |
+---------------------------------------------------------------+
|                  int16 PCM payload (960 B)                    |
+---------------------------------------------------------------+
```

| 字段 | 作用 |
|---|---|
| `sample_pos` | 该帧首采样在流内的绝对序号（48 kHz）。**丢包后靠它算缺了多少采样**，靠 `seq` 反推在重排时会出错 |
| `capture_ts_ms` | 源端单调时钟，毫秒。用毫秒不是微秒：µs 版 71 分钟就回绕，ms 版能撑 49 天 |
| `flags` | 目前只有 `Discontinuity` 和 `Marker`，留给后续扩展 |

载荷是 **int16 交织 PCM**，每包 240 帧（5 ms）= 960 字节。
选 240 帧是为了让整个数据报（976 B）远小于 WiFi MTU，
**避免 IP 分片**——一个分片丢失等于整个包作废。

**为什么 P0 不用 Opus**：Opus 省带宽（128 kbps vs 1.5 Mbps），但引入编码器依赖、
增加 12.5 ms 算法延迟。局域网场景下 1.5 Mbps/源 完全不是瓶颈，
所以 P0 先把这个变量去掉，把精力放在缓冲和时钟上。
编码器是接口可插拔的，加 Opus 不影响其他模块。

---

## 关键设计决定（都有代价，写清楚）

1. **UDP 而不是 TCP**
   TCP 的队头阻塞在丢包时会把延迟**永久推高**——它不丢数据，你只能加大缓冲。
   对"延迟必须稳定在 60 ms"是灾难。Snapcast 用 TCP 是因为它默认缓冲 1 秒，
   多 30 ms 无所谓，我们不行。

2. **抖动缓冲按绝对采样序号索引，而不是队列**
   队列模型分不清"包还没到"和"包丢了"，也无法利用迟到包的后半段。
   绝对序号模型两种都能处理（见 `JitterBuffer::Push` 的部分迟到裁剪）。

3. **扩容 = 插一段静音**
   缓冲物理上就是"晚一点开始播"。运行中想增大延迟，只能插入一次静音，
   这声空档不可避免。`InsertSilence()` 就是干这个的。

4. **ASRC 用三次插值，不用 sample slip**
   插/删整帧会产生周期性相位跳变，有持续音时比插值更难听。
   代价是多两次乘法。

5. **混音全程 float32，末端软限幅**
   多源直接相加必然过 1.0。过程中夹紧会得到硬削顶的"糊"声，
   所以只在最后过一遍 tanh 软限幅（阈值 0.85，以下完全不染色）。

6. **采集线程不直接发网络**
   `send()` 可能阻塞（ARP 未解析、路由变化）。阻塞在采集回调里 = WASAPI 缓冲溢出 = 声音断掉。
   所以采集回调只做 float→int16 和入环，发送交给独立线程。

---

## 已知限制

- **单机跑真实采集必须加 `--exclude audiohub-hub.exe`**：否则 source 抓到的
  系统混音里包含 hub 自己的输出，形成环回反馈。加了这个参数就干净了
  （已实测：hub 正在播 440 Hz 时，排除后的源端抓到 −120 dBFS）。
- **图谱 / 握手 / ROLE 全部是明文可信局域网假设**：任何能发 UDP 的机器都能伪造
  GRAPH 帧改掉汇聚端；跨不可信网络需要配对密钥（还没做）。
- **发现靠 UDP 广播（47300）**：部分公司网络、跨网段会被拦，此时仍可手工填 IP 邀请。
- **没有 mDNS**：广播只在同一子网内有效。
- **Hub 不能同时采集本机音频**。如果要做，必须用 Windows 10 2004+ 的
  `AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK` + `EXCLUDE` 模式排除自身进程树，
  否则会自己抓自己，形成无限反馈。
- **未使用 `IAudioClient3`**。它能把共享模式周期压到 2.67 ms（需要驱动是 WaveRT），
  现在是传统 `IAudioClient`，约 10 ms。这是最好摘的一颗果子。
- **`device_latency_ms()` 报的是配置值，不是实测值**。
  真要精确，得读 `IAudioClock` 的 position 差。
- **水位有 ±8 ms 的残余抖动**。混音线程用 `sleep_until` 打 5 ms 节拍，
  Windows 默认定时器精度是 15.6 ms，偶尔会晚一拍再连补几拍。
  解决办法是 `timeBeginPeriod(1)` 或改用等待定时器，还没做。
- **蓝牙耳机不能用于音画同步**。A2DP 会一次性加 150~250 ms，任何优化都白做。
  代码里还没做检测拦截。
- **Android / iOS 未接入**。iOS 不能作为源——系统没有提供任何系统级音频采集 API。
- **无加密**。P0 假设在可信局域网内。

---

## 路线

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 ✅ | Windows→Windows 单源打通 + 测量工具 + 单机可测 | 见 `docs/测试指南.md` |
| P1 ✅ | **Android 两端**：采集端 + 接收端（APK 已打包，采集端真机验证） | < 100 ms，锁屏 10 min 不掉 |
| P2 ⬜ | 多源打磨：响度归一化、混音台、混音线程节拍精度 | 3 源，源间差 < 10 ms |
| P3 🔶 | 发现 / 共享图谱 ✅；剩 mDNS、蓝牙拦截、校准向导、Opus 编码 | 新设备 60 s 内出声 |

Android 端的用法和已知限制见 [android/README.md](android/README.md)。

---

## 参考

- ITU-R BT.1359-1 — 音画同步的可察觉阈值 +45 / −125 ms
- EBU R37 — 整链 +40 / −60 ms
- [Snapcast](https://github.com/badaix/snapcast) — 时钟同步与时间戳播出思路
- [Opus](https://opus-codec.org/) — 帧长 2.5~60 ms，CELT 算法延迟 = 帧长 + 2.5 ms
- [SonoBus](https://sonobus.net/) — 多对多低延迟混音的参考实现

## 许可

见 [LICENSE](LICENSE)。
