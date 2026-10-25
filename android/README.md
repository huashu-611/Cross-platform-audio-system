# AudioHub 源端（Android）

把手机正在播放的声音采集下来，通过 UDP 送到 PC 上的汇聚端（`audiohub-hub.exe`）。

这是**跨端音频系统**里唯一还缺的一块：没有它，“多台设备 → 一台输出”就不成立。
你只有一台 PC，所以手机是唯一能当第二台设备的东西。

## 装

APK 已经打好了，在 `build/audiohub-source.apk`（29 KB）。

```bash
# USB 连接 + 开发者模式
adb install -r build/audiohub-source.apk

# 或者把文件拷到手机上，用文件管理器点开安装（需要允许“未知来源”）
```

## 用

1. PC 上先起汇聚端：

   ```bash
   build/audiohub-hub.exe --port 47000 --latency 60
   ```

   启动时会打印本机 IP，比如 `192.168.1.20`。**手机和 PC 必须在同一个局域网。**

2. 手机上打开「AudioHub 源端」，填入那个 IP 和端口 `47000`，流编号默认 `1`。

3. 点「开始采集」。系统会弹出「屏幕录制」授权——
   **Android 的音频采集是挂在 MediaProjection 上的，不授权就没法抓，但它真的不会录屏。**
   你会在通知栏看到一个「AudioHub 正在采集」的常驻通知。

4. 手机放歌。PC 的耳机/音箱里应当听到声音，PC 界面上会显示这一路的水位和电平。

界面底部会实时显示：已发送包数、电平（dBFS）、码率。
**电平 −120 就是静音**——说明没抓到任何东西，先去看手机是不是真的在放。

## 多台手机

每台手机填**不同的流编号**（1、2、3…），Hub 会自动分流并混音。

## 自己构建

需要 Android SDK（platform-35 + build-tools-35）和 JDK 17。

### Android Studio

直接 `File → Open` 选本目录，然后 Run。项目零第三方依赖（不引 AndroidX），
所以同步和构建都不需要联网。

### 命令行（不依赖 Gradle）

```bash
export ANDROID_SDK_ROOT=/path/to/sdk
export JAVA_HOME=/path/to/jdk-17
./build-apk.sh
```

### 为什么有两种构建方式

Gradle 是常规路径，但它要先下载 AGP 和一串依赖。而本模块零第三方依赖，
所以 `build-apk.sh` 用 `aapt2 + javac + d8 + apksigner` 四步就够，5 秒内完成且不需要联网。

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
- 没有「自动发现」，需要手填 IP。下一步是加 mDNS。
- 前台服务 + WifiLock + WakeLock 都加了，但**还没在不同厂商的省电策略下验证过**。

## 源码

| 文件 | 职责 |
|---|---|
| `PacketProtocol.java` | 16 字节线格式，必须和 PC 端 `packet.hpp` 逐字节一致 |
| `CaptureService.java` | 前台服务、MediaProjection、AudioRecord、节拍发送、WifiLock |
| `MainActivity.java` | 极简界面（纯代码搭，没有 XML 布局） |
