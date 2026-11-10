#include "audiohub/hub_core.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <thread>
#include <utility>
#include <vector>

#include "audiohub/asrc.hpp"
#include "audiohub/audio_format.hpp"
#include "audiohub/jitter_buffer.hpp"
#include "audiohub/meter.hpp"
#include "audiohub/mixer.hpp"
#include "audiohub/packet.hpp"
#include "audiohub/pi_controller.hpp"
#include "audiohub/spsc_ring.hpp"
#include "audiohub/udp_socket.hpp"
#include "wasapi_audio.hpp"

namespace audiohub {
namespace {

// 外环（PI）每 40 个 tick 调一次 = 200 ms。
constexpr int kAdjustEveryTicks = 40;
constexpr int kCatchUpThresholdMs = 40;
constexpr int kCatchUpWindows = 3;

// ---- 抖动自适应缓冲（v1.7）----
//
// 手机走 WiFi 时数据不是匀着到的：省电、信道竞争、系统调度都会把几十毫秒的
// 音频攒成一批送过来。目标延迟固定 60 ms 时，这种突发会周期性抽干抖动缓冲，
// 输出只能补零 —— 听感就是"偶尔卡一下，而且挺频繁"。
//
// 这套逻辑不猜网络模型，只看结果：
//   · 出现欠载      → 物理扩缓冲（插一小段静音把读指针往后放）+ 抬高目标延迟；
//   · 水位长期高于目标 → 数据被网络推后了，抬目标吸收，不做硬丢帧（硬丢就是可听的跳字）；
//   · 只有水位逼近容量（400 ms）才一次性丢回 200 ms，当作最后保险。
// 上限 240 ms：再高对音乐已没意义，反而拖慢"手机上按暂停"的反馈速度。
constexpr int kAdaptiveTargetMaxMs = 240;
constexpr int kAdaptiveGrowMs = 20;     // 欠载时至少多留这么多余量
constexpr int kAdaptiveGrowMaxMs = 60;  // 单次最多扩这么多（丢得越多补得越多）
constexpr int kRaiseThresholdMs = 40;   // 水位高于目标多少算"被网络推后"
constexpr int kRaiseWindows = 3;        // 连续几个调整窗口才抬目标
constexpr int kEmergencyDropMs = 320;   // 水位硬上限：超过就丢
constexpr int kEmergencyKeepMs = 200;   // 保命丢帧后保留的水位
// 会话关系表的存活时间：手机 App 退出后不会再广播 GRAPH / 发音频，
// 这么久没再见到就从"会话设备"里摘掉，界面不会一直显示在线。
constexpr int kSessionPeerTtlMs = 30000;
// 超过这么久没有包的流判定为掉线，从列表里移除。
constexpr int kSourceTimeoutSec = 5;
// ASRC 内部保留的输入帧数。
constexpr int kAsrcReserveFrames = 8;
// 源端采集重启的判定阈值：sample_pos 回退超过 2 秒。
//
// 手机端 CaptureService 重启（切前后台、重新授权录屏、系统杀服务后自动拉起）
// 时，stream_id 不变、但 sample_pos 从 0 重新计数；本端若还挂着旧读指针，
// 之后每个包都判"迟到"整包丢弃，缓冲水位恒为 0、电平 -120，包计数却继续涨，
// 只能重启汇聚端才恢复。正常乱序最多回退一两百毫秒，2 秒足够区分。
constexpr int kRestartJumpFrames = 2 * kSampleRate;
// 连续这么久没有一个包成功进入抖动缓冲 = 读指针已经跑过源端的写指针，
// 再怎么等也等不回来（源端采集重启、暂停后恢复、网络断点之后都会这样）。
// 这时清空重来，让下一个包按"首包"逻辑重新锚定读指针。
// 1 秒是权衡：太短会把正常断流抖动误判成错位，太长则用户听到的静音更久。
constexpr int kPushStallResetMs = 1000;
// 控制口 = 音频口 + 2（+1 是 PING/PONG 口）。握手和发现都走文本帧。
constexpr uint16_t kControlPortOffset = 2;

// v0.30：来源 IP 去重集合的上限。手机上只有一两个来源，但公网/校园网里
// 扫端口的机器可能成千上万 —— 无上限会让这个集合无限增长（内存泄漏）。
// 超上限后不再记新 IP，只更新 rx_last_ip，够诊断用。
constexpr size_t kRxMaxSenders = 64;

// ---- 图谱（docs/GRAPH-PROTOCOL.md）----
// 周期保活 3 秒；本地变化连发 2 次、间隔 60 ms；同一设备 1 秒最多收 5 次，
// 超出合并成延迟一次；同一来源的 GRAPH-REQ 200 ms 内只回一次。
constexpr int kGraphPeriodicMs = 3000;
constexpr int kGraphRepeatMs = 60;
constexpr int kGraphRequestReplyMs = 200;
constexpr int kGraphRateWindowMs = 1000;
constexpr int kGraphRateMaxSends = 5;
// 源地址错位（NAT/中继）时，按数据报源地址回 GRAPH 的最小间隔。
// 对端 3 秒一帧，回得比它密没有意义；但必须远小于它 10 秒的在线超时。
constexpr int kGraphSourceReplyMs = 1000;
// "全部断开"之后还要不要理会对端的 GRAPH（v0.42）。
//
// 背景：会话清空会置 graph_detached=true，本意是"别再让对端的旧图谱把我刚清掉的
// 节点灌回来"。但 HandleGraphFrame 首行对 detached 是无条件 return，而解除
// detached 的只有 GraphAddPeerLocked / SwitchGraphHub —— 都要先收到 CONN-REQ
// 或者用户手动切汇聚端。手机侧恰恰是"只广播 GRAPH、不发 CONN-REQ"，
// 于是两边卡死：PC 永远丢弃手机图谱（nodes 只剩自己，界面上没有任何设备可选），
// 手机也永远收不到 PC 回帧（学不到 hubId，界面显示"没有汇聚端"）。
// 实测（真机）：hsPackets=40、hsLastCmd 恒为 GRAPH、hsLastIp 是真手机 IP，
// 但 graph.nodes 只有 1 个 —— 帧被这一行吃掉了。
//
// 改法：detached 只保护"刚断开后的这一小段时间"。过了宽限期还收到对端的
// 合法 GRAPH，说明对端确实还在线、还想跟我们组网，就重新入网。
// 宽限期取 8 秒：够覆盖对端 3 秒一次的周期广播（2~3 轮），
// 又短到不会让用户觉得"电脑一直看不到设备"。
constexpr int kGraphDetachGraceMs = 8000;

// ---- 房间摆位（POS，docs/GRAPH-PROTOCOL.md §13）----
// 摆位是低频状态（只有拖拽时变），按键时连发 2 次 + 状态变化合并转发，就够了；
// 定时保活挂在 GRAPH 同一拍（3 s）上，补偿丢包。限速与 GRAPH 分开记，
// 否则这两类帧会互相抢那一秒 5 次的配额。
constexpr int kPlacementPeriodicMs = 3000;
constexpr int kPlacementRepeatMs = 60;
constexpr int kPlacementRequestReplyMs = 200;
constexpr int kPlacementRateWindowMs = 1000;
constexpr int kPlacementRateMaxSends = 5;

using Clock = std::chrono::steady_clock;

// 本机 IP 的测试钩子：AUDIOHUB_LOCAL_IP 非空时优先。
// 为什么要它：tools/graph-smoke.ps1 在同一台机器上跑两个 hub，自动枚举
// 只会得到同一个局域网 IP，两个进程的自节点会互相指错。显式覆盖最省事。
std::string EnvLocalIpOverride() {
  const char* v = std::getenv("AUDIOHUB_LOCAL_IP");
  if (v == nullptr) return {};
  std::string s(v);
  while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) s.pop_back();
  const size_t start = s.find_first_not_of(" \t");
  return start == std::string::npos ? std::string() : s.substr(start);
}

// 候选地址列表（"a, b, 127.0.0.1"）里取第一个，作为"本机 IP"。
std::string FirstIp(const std::string& list) {
  std::string s = list;
  const size_t comma = s.find(',');
  if (comma != std::string::npos) s = s.substr(0, comma);
  while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) s.pop_back();
  const size_t start = s.find_first_not_of(" \t");
  return start == std::string::npos ? std::string() : s.substr(start);
}

// 节点 id 是 "<ip>:<audioPort>"。取 ip 部分（找不到 ':' 返回空串）。
std::string NodeIpOf(const std::string& id) {
  const size_t colon = id.rfind(':');
  return colon == std::string::npos ? std::string() : id.substr(0, colon);
}

struct Stream {
  explicit Stream(uint16_t stream_id, int latency_ms)
      : id(stream_id),
        jitter(kChannels, kSampleRate, MsToFrames(400)),
        asrc(kChannels),
        pi(0.00002, 0.000002, -0.002, 0.002) {
    base_target_ms = std::clamp(latency_ms, 20, kAdaptiveTargetMaxMs);
    jitter.SetTargetDelayMs(base_target_ms);
  }

  uint16_t id;
  JitterBuffer jitter;
  Asrc asrc;
  PiController pi;
  Meter meter;
  std::vector<float> pull_buffer;
  std::vector<float> block_buffer;
  int tick_counter = 0;
  int excess_windows = 0;
  int catchup_count = 0;
  // 自适应缓冲（v1.7）：base_target_ms 是 --latency 给的原始目标，
  // target 只能往上抬到 kAdaptiveTargetMaxMs，调 SetLatency 时两者一起复位。
  int base_target_ms = 60;
  uint64_t last_underrun_frames = 0;  // 上一次调整窗口看到的欠载帧数（用于算增量）
  int high_windows = 0;               // 水位连续高于目标的窗口数
  int low_windows = 0;                // 水位连续低于目标的窗口数（用于慢慢把缓冲还回去）
  Clock::time_point last_catchup{};
  Clock::time_point last_packet{};
  float gain = 1.0f;
  bool muted = false;
  // 3D 空间化状态：只在混音线程使用（MixerLoop 持 mutex 调用 Process）。
  // ITD 历史环挂在这里，随流生命周期创建/销毁，不需要全局缓冲。
  SpatialState spatial;
  // 空间化结果独立缓冲：Process 是原地处理，拷一份再处理，MixBlock 负责求和。
  std::vector<float> spatial_buffer;
  uint64_t packets = 0;
  // 上一个包的流内绝对采样序号（-1 = 还没收到过）。
  // 用来识别"源端采集重启但 stream_id 没变"：序号大幅回退时必须清空抖动缓冲重新对齐，
  // 否则读指针永远跑在写指针前面，这条流就永久静音了。
  int64_t last_sample_pos = -1;
  // 最近一次"包成功写进抖动缓冲"的时刻，以及"已清空、等下一个包重新锚定"标记。
  // 两者配合识别"包在涨但一个也进不了缓冲"的永久静音：接收线程成功 Push 时刷新
  // last_good_push，混音线程发现它 1 秒没动就清空重来（见 RebaseStreamLocked）。
  Clock::time_point last_good_push{};
  bool awaiting_reanchor = false;
  // 握手：未经接收方确认的流，混音时按静音处理。
  // 这样"请求-确认"机制是真的生效的，同时不影响直接发包的调试场景
  // （那种场景把 --no-handshake 关掉即可）。
  bool confirmed = true;
  std::string peer_name;
  // 回 BYE 需要的对端地址（从 CONN-REQ 的来址拿到）
  std::string peer_ip_for_bye;
  uint16_t peer_port_for_bye = 0;
};

}  // namespace

struct HubCore::Impl {
  uint16_t port = 47000;
  uint16_t http_port_for_discovery = 0;  // 给发现协议用，0 表示没有 HTTP 界面
  int latency_ms = 60;
  int render_latency_ms = 10;

  std::atomic<bool> running{false};
  std::atomic<bool> stop_requested{false};
  std::atomic<float> master_gain{1.0f};

  mutable std::mutex mutex;
  std::map<uint16_t, std::unique_ptr<Stream>> streams;
  Meter bus_meter;

  // ---- 渲染探针（v0.27）：设备前 vs 设备上的两段证据 ----
  //
  // 为什么不能用 bus_meter 判断"汇聚端有没有真的出声"：Meter::Take() 是
  // 取走即清零，而 UI 每 200 ms 轮询一次 /api/state。诊断脚本再 curl 一下，
  // 谁后到谁拿到 0 —— 一个正在出声的汇聚端会被读成 -120 dBFS。
  //
  // 所以这里用"只增不减"的计数器，直接夹在送给设备的那一帧上：
  //   render_frames_total  渲染回调被调用的帧数（设备真的在跑）
  //   render_ring_missed   回调要数据但环形缓冲是空的（"没混进去"）
  //   render_audible_frames 至少有 1 个非零样本的帧数（"混进去了且非静音"）
  //   render_peak_abs      设备前见过的最大绝对值（单调）
  // 四个量单调递增，任意多个读者反复读都一样，不会互相抢。
  std::atomic<uint64_t> render_frames_total{0};
  std::atomic<uint64_t> render_ring_missed{0};
  std::atomic<uint64_t> render_audible_frames{0};
  std::atomic<double> render_peak_abs{0.0};
  // 归因探针（v0.28）：见 HubStats 里的同名注释。
  std::atomic<bool> rendering_as_hub{false};
  std::atomic<uint64_t> mixed_sources{0};
  std::atomic<uint64_t> mixed_frames{0};
  std::atomic<uint64_t> mixed_audible_frames{0};

  // 可选的 WAV 落盘：把"实际送给设备"的样本写出去，供用户直接播放验证。
  // 只有开了 --dump-render 才有开销（一次 fwrite），默认零影响。
  // mutable：Stats() 是 const 方法，但"当前落盘了多少帧"得能读到。
  mutable std::mutex dump_mutex;
  // 实时线程只读这个原子指针；指针的打开/关闭都在非实时线程里做。
  std::atomic<FILE*> dump_file{nullptr};
  // "正在写"的握手计数：关闭文件前必须等到 0，否则就是 use-after-close。
  std::atomic<int> dump_busy{0};
  mutable uint64_t dump_frames = 0;
  uint32_t dump_data_bytes = 0;
  std::string dump_path;

  // 渲染回调里逐帧判"这一帧有没有波形"。必须无锁无分配（实时线程）。
  void ProbeRenderBlock(const float* out, size_t got_samples, size_t need_samples) {
    const uint64_t frames = static_cast<uint64_t>(need_samples / kChannels);
    render_frames_total.fetch_add(frames, std::memory_order_relaxed);
    if (got_samples < need_samples) {
      const uint64_t missed =
          static_cast<uint64_t>((need_samples - got_samples) / kChannels);
      render_ring_missed.fetch_add(missed, std::memory_order_relaxed);
    }
    // 逐帧统计交给 ScanFrames（与单元测试共用同一份实现，避免"测试测的
    // 和线上跑的不是同一段代码"这种假绿）。
    const FrameStats fs = ScanFrames(out, got_samples, kChannels);
    if (fs.audible) render_audible_frames.fetch_add(fs.audible, std::memory_order_relaxed);
    if (fs.peak > 0.0) {
      double prev = render_peak_abs.load(std::memory_order_relaxed);
      while (fs.peak > prev &&
             !render_peak_abs.compare_exchange_weak(prev, fs.peak, std::memory_order_relaxed)) {
      }
    }
    if (dump_file.load(std::memory_order_acquire) != nullptr) {
      dump_busy.fetch_add(1, std::memory_order_acq_rel);
      FILE* f = dump_file.load(std::memory_order_acquire);
      // 写"完整一帧"（need_samples），而不是只写从环形缓冲读到的 got_samples：
      // 广播/静音期间 ring 常常是空的，只写 got 会让 WAV 的时间轴被压缩，
      // 回放时听起来比真实快好几倍，反而误导判断。
      if (f != nullptr) DumpRenderBlock(f, out, need_samples);
      dump_busy.fetch_sub(1, std::memory_order_acq_rel);
    }
  }

  // 写 WAV（16-bit PCM）：只写 data 段，收尾时补头。实时线程里只做一次 fwrite。
  //
  // 并发约定：dump_file 只在"设备已停"之后才被关闭（见 Stop()：render.Stop()
  // 先于 CloseDumpFile()），所以实时线程永远不会撞上 fclose。
  // 实时线程里因此不加锁——加锁会在锁被 HTTP 线程持有时直接卡住回调。
  void DumpRenderBlock(FILE* f, const float* out, size_t samples) {
    if (samples == 0) return;
    static thread_local std::vector<int16_t> pcm;
    pcm.resize(samples);
    for (size_t i = 0; i < samples; ++i) {
      float v = out[i];
      if (v > 1.0f) v = 1.0f;
      if (v < -1.0f) v = -1.0f;
      pcm[i] = static_cast<int16_t>(v * 32767.0f);
    }
    std::fwrite(pcm.data(), sizeof(int16_t), samples, f);
    dump_data_bytes += static_cast<uint32_t>(samples * sizeof(int16_t));
    dump_frames += samples / kChannels;
  }

  // 事件回调必须用独立的锁：Emit() 会在"已经持有 streams mutex"的接收线程里
  // 被调用（新流接入、流掉线），复用同一把 std::mutex 就是死锁——
  // 而且症状极具迷惑性：banner 打完之后整个程序静默，没有任何报错。
  mutable std::mutex cb_mutex;
  std::function<void(const std::string&)> event_cb;

  std::atomic<uint64_t> received_packets{0};
  std::atomic<uint64_t> received_bytes{0};
  std::atomic<uint64_t> malformed{0};

  // v0.30：接收侧证据。received_packets/received_bytes 以前只加不读，等于死计数器；
  // 现在一并接进 /api/state，让"包有没有到我这儿"变成可观测事实。
  std::atomic<uint64_t> rx_first_at_ms{0};
  std::atomic<uint64_t> rx_bad_packets{0};
  mutable std::mutex rx_mutex;                  // 保护下面几个非原子字段
  std::set<std::string> rx_senders;             // 见过的来源 IP（上限 kRxMaxSenders）
  std::string rx_last_ip;
  uint16_t rx_last_port = 0;
  std::string rx_last_reject;
  std::atomic<uint64_t> render_underruns{0};

  // v0.32：接收侧证据必须覆盖**所有**入口，不然"没收到包"会骗人。
  //
  // v0.30 的 rx_* 只记了音频口（47000）。但握手（CONN-REQ/ACK/INVITE）、
  // 图谱帧（GRAPH/POS）走的是 audioPort+2（47002），发现信标走 47300。
  // 于是出现一种极坏的情形：握手口被防火墙单独挡住、音频口也没包进来，
  // 界面只显示"未收到包"，看起来像"什么都没到"，实际上可能是
  // "握手包到了、但被别的规则拦在解码前"或"发现包到了、握手包没到"。
  // 用户的真实反馈正是这样：双方在图谱里互相可见（说明发现口是通的），
  // 却都说"对方没回应"（说明握手口不通）。必须能分辨这三个口。
  std::atomic<uint64_t> hs_packets{0};
  std::atomic<uint64_t> hs_recent{0};       // 最近 10 秒内的握手包数（粗略，按拍衰减）
  std::atomic<uint64_t> hs_first_at_ms{0};
  std::atomic<uint64_t> hs_discarded{0};    // 收到了但没匹配任何命令的行
  mutable std::mutex hs_mutex;
  std::string hs_last_ip;
  uint16_t hs_last_port = 0;
  std::string hs_last_cmd;                  // 最近一条命令的首段（CONN-REQ / GRAPH / POS …）
  std::string hs_last_line;                 // 最近一条原文（截断，便于肉眼看）

  int raw_dump_budget = 0;

  UdpSocket data_socket;
  UdpSocket ctrl_socket;
  UdpSocket handshake_socket;   // 音频口 +2，连接握手
  WasapiRender render;

  std::unique_ptr<Discovery> discovery;
  std::string display_name = "AudioHub";
  bool require_handshake = true;
  std::atomic<bool> auto_accept_{false};

  // 待确认的连接请求
  struct Pending {
    std::string peer_name;
    std::string peer_ip;
    uint16_t peer_port = 0;
    uint16_t peer_audio = 47000;   // 对端音频口（CONN-REQ 第 4 段，老版本缺省 47000）
    int64_t requested_at_ms = 0;
  };
  std::map<uint16_t, Pending> pending;
  // 已确认过的 stream_id。握手模式下发起方收到 ACK 才开始发音频，
  // 所以 Accept 时那个流往往还不存在——必须记住，等它的第一个包来时对号入座。
  std::set<uint16_t> accepted_ids;

  // ---- 图谱状态（docs/GRAPH-PROTOCOL.md）----
  // 谁当汇聚端、局域网里有哪些设备，全靠这几个字段。
  // 锁沿用 mutex：图谱由握手线程收、HTTP 线程读/切，数据量很小（<=32 节点），
  // 单独再开一把锁只会多出一种死锁可能。
  GraphState graph_;
  std::string graph_self_ip;        // 本机 IP（AUDIOHUB_LOCAL_IP 可覆盖）
  std::string self_node_id;         // 本机节点 id = <IP>:<音频口>
  // v0.41：本机**所有**网卡地址（含 self_node_id 里那个）。
  //
  // 为什么要记全：IsHub() 一直是 `graph_.hub_id == self_node_id` 的字符串比较，
  // 而别的设备在它自己那侧看到的"本机地址"未必等于我们选中的那张网卡 ——
  // 多网卡（WLAN + 以太网 + VMware）、多出口 NAT（手机看到的 10.0.2.2 其实是
  // 宿主）、以及 hostname 解析顺序都可能不一样。对端把这个地址写进 hubId 广播
  // 回来，本机就认不出"那是我自己"，于是：
  //   1) /api/state 的 isHub=false（界面把汇聚端标到一条陌生节点上）；
  //   2) MixerLoop 里 rendering_as_hub=false → 混音输出被整块清空 → 不出声。
  // 现象就是「手机上有特效、电脑上没声音」，而且怎么重连都没用。
  std::vector<std::string> self_ips;  // 本机全部 IPv4（启动时枚举一次）
  uint16_t graph_http_port = 0;     // 自节点要广播的 HTTP 口
  uint64_t graph_seen_version = 0;  // 见过的最大 version（本地改动取 max+1）
  std::map<std::string, Clock::time_point> graph_last_seen;   // ip -> 最近收到数据报
  std::map<std::string, Clock::time_point> graph_req_replied; // ip -> 上次回 GRAPH-REQ
  // 源地址错位时按数据报源地址回帧的限速（与 graph_req_replied 分开记：
  // 两者一个由 GRAPH-REQ 触发、一个由普通 GRAPH 触发，共用会互相把对方
  // 的窗口占掉，结果两边都回不出来）。
  std::map<std::string, Clock::time_point> graph_src_replied;  // ip -> 上次按源回帧
  std::map<std::string, std::vector<Clock::time_point>> graph_sends;  // ip -> 发送时刻
  std::map<std::string, Clock::time_point> graph_deferred;    // ip -> 被限速合并后的补发
  std::vector<std::pair<std::string, uint16_t>> graph_probes; // 待发的 GRAPH-REQ
  int graph_pending_repeats = 0;         // 本地变化还差几次广播（连发 2 次）
  Clock::time_point graph_next_repeat{};    // 下一次补发时刻
  Clock::time_point graph_next_periodic{};  // 下一次周期广播

  // 会话已结束（用户点了「全部断开」），本机暂时退出图谱共识。
  //
  // 协议 §4.6 说"重置后不广播"，但只做到"不主动广播"是不够的：本机仍然在收——
  // 对端（尤其是还没退出的那台）会周期广播它的旧图谱，本机一合并就把刚清掉的节点
  // 全捞回来，而且带着"version 更高、hub=本机"的身份发回去，对端于是把汇聚端
  // 认成这台已经离开的机器。用户看到的就是：
  //   * 「一方断开就清空图谱」—— 图谱清空又被对端灌回来；
  //   * 「退出后反复回到控制台，但只显示它自己的汇聚端」—— 捞回来的图谱让
  //     nodes>=2 成立，界面自己跳回控制屏；
  //   * 「反复切换时汇聚端没声音」—— 对端被这条幽灵帧抢走汇聚端，谁也不发流。
  // 所以这里需要一个显式状态：会话结束后既不合并、也不广播，直到用户重新
  // 邀请 / 接受邀请 / 主动切汇聚端（这些动作都会重新入网）才解除。
  bool graph_detached = false;
  // detached 的起始时刻（v0.42）：只有"刚断开"这段时间才真正拦对端 GRAPH，
  // 见 kGraphDetachGraceMs 的注释。
  Clock::time_point graph_detached_at = Clock::now();

  // ---- 房间摆位（POS）----
  // 与 graph_ 共用 mutex：数据量更小（<=32 项），读写都在 HTTP / 握手 / 混音线程。
  PlacementState placement_;
  uint64_t placement_seen_version = 0;
  std::map<std::string, Clock::time_point> placement_req_replied;      // ip -> 上次回 POS-REQ
  std::map<std::string, std::vector<Clock::time_point>> placement_sends;  // ip -> 发送时刻
  std::map<std::string, Clock::time_point> placement_deferred;         // ip -> 被限速后的补发
  std::vector<std::pair<std::string, uint16_t>> placement_probes;      // 待发的 POS-REQ
  int placement_pending_repeats = 0;             // 本地变化还差几次广播（连发 2 次）
  Clock::time_point placement_next_repeat{};     // 下一次补发时刻
  Clock::time_point placement_next_periodic{};   // 下一次周期广播

  std::thread receiver;
  std::thread control;
  std::thread handshake;
  std::thread mixer;

  SpscRing<float> output_ring{static_cast<size_t>(MsToFrames(100)) * kChannels};

  // ---- 3D 空间化（P0：Windows 汇聚端；坐标不进图谱协议）----
  // 读写都在上面那把 mutex 下：HTTP 线程写，混音线程每块读一次快照；
  // 数据量很小（<=32 个设备），与 graph_ 同策略，不另开锁。
  ListenerPose listener_pose;
  bool listener_valid = false;
  SpatialParams spatial_params;
  struct SpatialPlacement {
    Vec3 position;
    bool enabled = true;  // false = 该源旁路空间化（PC 环回音乐，保持立体声）
  };
  std::map<std::string, SpatialPlacement> spatial_placements;  // key = 设备 IP

  ~Impl() { Stop(); }

  void Emit(const std::string& text) {
    std::function<void(const std::string&)> cb;
    {
      std::lock_guard<std::mutex> lock(cb_mutex);
      cb = event_cb;
    }
    if (cb) cb(text);
  }

  // 清空一条流的抖动缓冲/重采样器，让下一个包重新锚定读指针。
  // 调用方必须已持有 mutex（接收线程和混音线程都会调用）。
  void RebaseStreamLocked(Stream& s, const std::string& why) {
    s.jitter.Reset();
    s.asrc.Reset();
    s.asrc.SetRatio(1.0);
    s.pi.Reset();
    s.excess_windows = 0;
    s.tick_counter = 0;
    s.last_good_push = Clock::now();
    s.awaiting_reanchor = true;
    Emit("[~] 流 " + std::to_string(s.id) + " " + why + "，重新对齐");
  }

  bool Start(uint16_t p, int latency, int render_ms, const StartOptions& opts,
             std::string* error) {
    if (running.load()) return false;
    port = p;
    latency_ms = latency;
    render_latency_ms = render_ms;
    display_name = opts.display_name.empty() ? "AudioHub" : opts.display_name;
    require_handshake = opts.require_handshake;
    auto_accept_.store(opts.auto_accept);
    http_port_for_discovery = opts.http_port;
    stop_requested.store(false);

    // 固定端口必须独占绑定（reuse_addr = false）。
    //
    // 2026-11-03（v0.20）：Windows 上 UDP 允许两个 socket 绑同一端口，
    // 内核把入包随机分给其中一个 —— 之前默认 reuse_addr = true，于是
    // 上一个没退干净的 audiohub-hub.exe 还能继续抢 47000/47002 的包，
    // 新进程看起来"起来了"却只收到一半数据。真实现象就是用户报的
    // "电脑连接手机后一直显示无反应，多次退出重进后有遗留"。
    // 独占绑定后，第二个进程会直接在 bind() 失败并退出，而不是静默分走数据。
    //
    // 但 reuse_addr = false 只管住了"两个都绑通配地址"的情况：Windows 允许
    // "具体 IP 的绑定"（如 127.0.0.1:47002）与"通配绑定"（0.0.0.0:47002）共存，
    // 后者 bind 照样成功，而发往 127.0.0.1 的包全进前者。这就是本机实测到的
    // 场景：hub 日志一切正常、界面正常，唯独收不到手机的任何握手帧。
    // 因此绑之前先问系统这个端口上还有谁。
    {
      const uint16_t check_ports[3] = {port, static_cast<uint16_t>(port + 1),
                                       static_cast<uint16_t>(port + kControlPortOffset)};
      for (const uint16_t p : check_ports) {
        const auto owners = ProbeUdpPortOwners(p);
        if (!owners.empty()) {
          std::string who;
          for (size_t i = 0; i < owners.size(); ++i) {
            if (i) who += ", ";
            who += owners[i];
          }
          if (error) {
            *error = "端口 " + std::to_string(p) + " 已被占用（" + who +
                     "）。多半是上一个 AudioHub 进程没退干净：请先在任务管理器里" +
                     "结束 audiohub-hub.exe / audiohub-source.exe 再重启。";
          }
          return false;
        }
      }
    }
    if (!data_socket.Open(port, false)) {
      if (error) *error = "绑定端口 " + std::to_string(port) + " 失败: " + data_socket.last_error();
      return false;
    }
    if (!ctrl_socket.Open(static_cast<uint16_t>(port + 1), false)) {
      if (error) *error = "绑定控制口 " + std::to_string(port + 1) + " 失败";
      return false;
    }
    ctrl_socket.SetReceiveTimeoutMs(30);

    // 握手口同理独占：UDP 没有 TIME_WAIT，上一进程真的退出后端口立刻可用；
    // 绑不上就说明还有别的实例活着，必须报错而不是悄悄分掉一半邀请帧。
    if (!handshake_socket.Open(static_cast<uint16_t>(port + kControlPortOffset), false)) {
      if (error) *error = "绑定握手口 " + std::to_string(port + kControlPortOffset) + " 失败";
      return false;
    }
    handshake_socket.SetReceiveTimeoutMs(50);

    if (!render.Start([this](float* out, int frames) {
          const size_t need = static_cast<size_t>(frames) * kChannels;
          const size_t got = output_ring.Read(out, need);
          if (got < need) {
            std::memset(out + got, 0, (need - got) * sizeof(float));
            render_underruns++;
          }
          // v0.27：把"实际送给设备的那一帧"记进单调探针。
          // 放在 Read/补零之后，量到的就是设备真正会播的东西。
          ProbeRenderBlock(out, got, need);
        }, render_latency_ms)) {
      if (error) *error = "启动输出设备失败: " + render.last_error();
      return false;
    }

    // 发现：广播自己 + 监听别人。广播失败（端口被占）不该让程序起不来，
    // 只是"自动发现"不可用，还能手填 IP。
    discovery.reset(new Discovery());
    {
      // 信标里带全部候选 IP（逗号分隔，第一个最可能可用）。
      // 一台机器可能同时有 WiFi 和网线，对端自己挑同网段的那个最稳。
      const std::string env_ip = EnvLocalIpOverride();
      if (!env_ip.empty()) {
        // 测试钩子：本机双进程冒烟测试时强制宣告同一个回环地址
        local_ip_for_invite = env_ip;
      } else {
        const std::string all = LocalIPv4List();
        local_ip_for_invite = all;   // 去掉尾部 ", 127.0.0.1"
        const size_t loop = local_ip_for_invite.find("127.0.0.1");
        if (loop != std::string::npos) {
          local_ip_for_invite = local_ip_for_invite.substr(0, loop);
          while (!local_ip_for_invite.empty() &&
                 (local_ip_for_invite.back() == ' ' || local_ip_for_invite.back() == ',')) {
            local_ip_for_invite.pop_back();
          }
        }
        if (local_ip_for_invite.empty()) local_ip_for_invite = "127.0.0.1";
      }
    }
    // 两端能力完全一致：都能采音也都能放音。"发送/接收"只是指
    // 建立连接时谁发起，不是角色——汇聚端是用户在界面里选的。
    const bool discovery_ok =
        opts.advertise
            ? discovery->StartAdvertise(display_name, local_ip_for_invite, port,
                                        http_port_for_discovery, true, true)
            : discovery->StartBrowse();
    if (!discovery_ok) {
      // 不能静默：发现不可用意味着"扫不到设备"，必须让用户知道原因
      Emit("[!] 自动发现不可用：端口 47300 被占用（可能有另一个 AudioHub 实例在跑）。"
           "可以手动填写对方 IP 继续使用。");
    }

    // 图谱：自节点 id = <本机 IP>:<音频口>，kind 固定 "pc"，name 用显示名。
    // PC 默认当汇聚端（协议 §1），用户之后可以在图谱上把汇聚端切到别的节点。
    {
      graph_self_ip = FirstIp(local_ip_for_invite);
      if (graph_self_ip.empty()) graph_self_ip = "127.0.0.1";
      graph_http_port = opts.http_port;
      // v0.41：把本机所有地址都记下来，供 IsSelfIdLocked() 判断"这个 id 是我自己"。
      // 见 self_ips 的成员注释：对端写回来的 hubId 可能是本机的另一张网卡，
      // 只比 self_node_id 会把它当成别人，导致本机让出汇聚端并静音。
      {
        // 用 LocalIPv4Addresses()（含虚拟网卡），不是 LocalIPv4List()：
        // 后者回答"该对外通告哪个地址"，会剔掉 VMware/WSL；而手机看到的电脑
        // 地址完全可能就是 192.168.x。判断"是不是我"必须用全集。
        self_ips = LocalIPv4Addresses();
        bool has_self = false;
        for (const auto& e : self_ips) {
          if (e == graph_self_ip) { has_self = true; break; }
        }
        if (!has_self) self_ips.push_back(graph_self_ip);
      }
      std::lock_guard<std::mutex> lock(mutex);
      GraphInitLocked();
    }

    running.store(true);
    receiver = std::thread(&Impl::ReceiverLoop, this);
    control = std::thread(&Impl::ControlLoop, this);
    handshake = std::thread(&Impl::HandshakeLoop, this);
    mixer = std::thread(&Impl::MixerLoop, this);
    return true;
  }

  void Stop() {
    if (!running.load() && !receiver.joinable() && !mixer.joinable()) return;
    stop_requested.store(true);
    if (receiver.joinable()) receiver.join();
    if (control.joinable()) control.join();
    if (handshake.joinable()) handshake.join();
    if (mixer.joinable()) mixer.join();
    render.Stop();   // 先停设备：回调彻底不再跑，之后关文件才安全
    CloseDumpFile();
    running.store(false);
    if (discovery) discovery->Stop();
    std::lock_guard<std::mutex> lock(mutex);
    streams.clear();
    pending.clear();
  }

  // ------------------------------------------------------------ 接收线程

  // v0.30：接收侧记账。名字里的 Locked 是历史习惯（rx_mutex 很短，只保护
  // 一个 set 和几个小字段），绝不与 streams 的 mutex 嵌套 —— 否则接收线程
  // 会在持锁路径里再拿一把锁，出问题时极难看出。
  void NoteRxPacketLocked(const std::string& ip, uint16_t port, size_t bytes) {
    const uint64_t now_ms = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
    if (rx_first_at_ms.load(std::memory_order_relaxed) == 0) {
      rx_first_at_ms.store(now_ms, std::memory_order_relaxed);
    }
    received_packets.fetch_add(1, std::memory_order_relaxed);
    received_bytes.fetch_add(bytes, std::memory_order_relaxed);
    {
      std::lock_guard<std::mutex> lock(rx_mutex);
      rx_last_ip = ip;
      rx_last_port = port;
      if (rx_senders.size() < kRxMaxSenders) rx_senders.insert(ip);
    }
  }

  void NoteRxReject(const std::string& why) {
    std::lock_guard<std::mutex> lock(rx_mutex);
    rx_last_reject = why;
  }

  // v0.32：握手口记账。和解码无关 —— 只要 datagram 到了就记，
  // 这样"握手口到底有没有包进来"不再依赖它是否是一条合法命令。
  void NoteHandshakePacket(const std::string& ip, uint16_t port) {
    const uint64_t now_ms = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
    if (hs_first_at_ms.load(std::memory_order_relaxed) == 0) {
      hs_first_at_ms.store(now_ms, std::memory_order_relaxed);
    }
    hs_packets.fetch_add(1, std::memory_order_relaxed);
    hs_recent.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(hs_mutex);
    hs_last_ip = ip;
    hs_last_port = port;
  }

  void NoteHandshakeCmd(const std::string& cmd, const std::string& line) {
    std::lock_guard<std::mutex> lock(hs_mutex);
    hs_last_cmd = cmd;
    // 只留前 120 字节：够看清是哪种帧，又不会把日志/JSON 撑爆
    hs_last_line = line.size() > 120 ? line.substr(0, 120) : line;
  }

  // 每拍衰减一次「最近 10 秒」的近似速率：GraphTick 是 50ms 一拍，
  // 200 拍 = 10 秒。这里用整数衰减，不追求精确，只要能回答
  // "握手口现在还活着吗"这一个问题。
  void DecayHandshakeRecent() {
    const uint64_t v = hs_recent.load(std::memory_order_relaxed);
    if (v == 0) return;
    // 每 200 拍清零一次（10 秒无包 -> 归零）
    if (++hs_decay_tick >= 200) {
      hs_decay_tick = 0;
      hs_recent.store(0, std::memory_order_relaxed);
    }
  }
  int hs_decay_tick = 0;

  void ReceiverLoop() {
    std::vector<uint8_t> buffer(2048);
    std::vector<float> scratch;

    while (!stop_requested.load()) {
      std::string from_ip;
      uint16_t from_port = 0;
      const int n = data_socket.ReceiveFrom(buffer.data(), buffer.size(), &from_ip, &from_port);
      if (n <= 0) {
        std::this_thread::sleep_for(std::chrono::microseconds(200));
        continue;
      }
      // v0.30：**在这行之后的任何判断都不影响这四个量**。
      // 先记账再解码：只有这样，"一个包都没收到"和"收到了但全解析失败"
      // 才能被区分开。解码失败时 rx_bad_packets 涨、rx_packets 也涨；
      // 防火墙挡住时两个都不涨。
      NoteRxPacketLocked(from_ip, from_port, static_cast<size_t>(n));

      // 音频包也算活标志（协议 §8）：只对图谱里已有的 IP 生效
      TouchGraphIp(from_ip);

      // 协议调试：前若干个包的原始字节。跨平台联调时直接看字节比推理可靠。
      if (raw_dump_budget > 0) {
        raw_dump_budget--;
        char hex[64] = {0};
        const int shown = n < 16 ? n : 16;
        for (int i = 0; i < shown; ++i) {
          std::snprintf(hex + i * 3, sizeof(hex) - i * 3, "%02X ", buffer[i]);
        }
        Emit(std::string("[RAW] len=") + std::to_string(n) + "  " + hex);
      }

      PacketHeader header;
      if (!DecodeHeader(buffer.data(), static_cast<size_t>(n), &header)) {
        malformed++;
        rx_bad_packets.fetch_add(1, std::memory_order_relaxed);
        NoteRxReject("头部解码失败（长度 " + std::to_string(n) + "）");
        continue;
      }
      const size_t payload_bytes = static_cast<size_t>(n) - kHeaderSize;
      if (payload_bytes == 0 ||
          payload_bytes % (kChannels * sizeof(int16_t)) != 0) {
        malformed++;
        rx_bad_packets.fetch_add(1, std::memory_order_relaxed);
        NoteRxReject("载荷长度非法（" + std::to_string(payload_bytes) + " 字节）");
        continue;
      }
      const int frames =
          static_cast<int>(payload_bytes / (kChannels * sizeof(int16_t)));
      const size_t samples = static_cast<size_t>(frames) * kChannels;

      if (scratch.size() < samples) scratch.resize(samples);
      const int16_t* src =
          reinterpret_cast<const int16_t*>(buffer.data() + kHeaderSize);
      for (size_t i = 0; i < samples; ++i) {
        scratch[i] = static_cast<float>(src[i]) * (1.0f / 32768.0f);
      }

      std::lock_guard<std::mutex> lock(mutex);
      auto it = streams.find(header.stream_id);
      if (it == streams.end()) {
        auto created = std::make_unique<Stream>(header.stream_id, latency_ms);
        created->peer_ip_for_bye = from_ip;
        // 四种情况视为已确认：没启用握手 / 自动同意新连接 / 之前握手通过过 / 之前 Accept 过
        // 自动同意（auto_accept）必须覆盖"直接发音频、没走 CONN-REQ 的源"：
        // 手机端 CaptureService 与直连测试源只发包不握手，而 GUI 汇聚端默认
        // auto_accept=true 却仍按"未确认"整条流静音，现象就是【包在涨、耳机全静音】。
        // auto_accept 关闭（用户在界面上关掉"自动同意"）时保持严格：数据流继续待确认。
        created->confirmed = !require_handshake || auto_accept_.load() ||
                             accepted_ids.count(header.stream_id) > 0;
        if (accepted_ids.count(header.stream_id) > 0) {
          created->peer_name = "已确认设备";
        }
        it = streams.emplace(header.stream_id, std::move(created)).first;
        Emit("[+] 新流接入: id=" + std::to_string(header.stream_id));
        // v0.26：只发音频、不发 CONN-REQ 的源（手机 CaptureService 就是这条路）
        // 也必须能把本机从"已退出共识"里拉回来。
        //
        // 以前这里只建流、不解除 graph_detached，于是一旦收摊过（对端 CONN-BYE
        // 退出 / 流超时掉线 / 用户点「全部断开」），后续纯数据包重连永远不 reattach：
        // HandleGraphFrame 首行的 `if (graph_detached) return;` 把对端所有 GRAPH
        // 帧丢掉，GraphBroadcastLocked 也不发 —— 两端各自守着自己那份图谱，
        // 各自都以为汇聚端是自己，于是都不送音，现象就是【两端都听不到对方】。
        //
        // 判据是"收到真实音频"= 对端确实在线，等价于握手成功，所以复用
        // GraphAddPeerLocked：它首行就是 GraphReattachLocked()，还会把该节点
        // 补进图谱并立刻互发一次 GRAPH，让共识在下一次合并里自愈。
        // audio_port 传 0 让它回落默认 47000（与 Android GraphStore.AUDIO_PORT、
        // 手机自节点 id 的约定一致）；非默认端口时会被后续 GRAPH 帧纠正。
        // 只在 detached 时做，正常收流路径的行为一个字节都不动。
        if (graph_detached && !from_ip.empty()) {
          GraphAddPeerLocked(from_ip, "", 0, nullptr);
        }
      }
      // 源端采集重启：同一个 stream_id 的 sample_pos 从 0 重新开始。
      // 这里必须先把抖动缓冲/重采样器清空——旧的读指针在几十亿帧的位置上，
      // 新包的序号永远追不上，会整包判"迟到"，表现为"包在涨、耳机永久静音"。
      // 用 int32 差值，避免 2^32 帧（约 24.8 小时）处序号回绕被误判成重启。
      // 旧流只活了几百毫秒就重启时回退幅度可能不足 2 秒、这里判不出来，
      // 由混音线程"kPushStallResetMs 没有有效包进入缓冲"的安全网兜底。
      Stream& stream = *it->second;
      if (stream.last_sample_pos >= 0) {
        const int32_t jump = static_cast<int32_t>(
            header.sample_pos - static_cast<uint32_t>(stream.last_sample_pos));
        if (jump < -kRestartJumpFrames) {
          RebaseStreamLocked(stream, "采样序号回退 " + std::to_string(-jump) + " 帧");
        }
      }
      stream.last_sample_pos = static_cast<int64_t>(header.sample_pos);
      if (stream.jitter.Push(header.sample_pos, scratch.data(), frames)) {
        stream.last_good_push = Clock::now();
        stream.awaiting_reanchor = false;
      }
      stream.packets++;
      stream.last_packet = Clock::now();

      received_packets++;
      received_bytes += static_cast<uint64_t>(n);
    }
  }

  // ------------------------------------------------------------ 握手线程

  // 文本帧，\n 结尾，走音频口 +2：
  //   CONN-REQ|<发起方名>|<stream_id>     请求建立连接
  //   CONN-ACK|<接收方名>|<stream_id>     同意
  //   CONN-NAK|<原因>|<stream_id>         拒绝
  //   CONN-BYE|<stream_id>                主动断开
  //
  // 回复直接发到来址（from_ip:from_port）：发起方用同一个 socket 监听应答，
  // 这样双方都不需要预先约定端口。
  void HandshakeLoop() {
    std::vector<uint8_t> buf(1024);
    while (!stop_requested.load()) {
      std::string from_ip;
      uint16_t from_port = 0;
      const int n = handshake_socket.ReceiveFrom(buf.data(), buf.size(), &from_ip, &from_port);
      // 图谱的周期广播/补发/回探全挂在这条线程上：本来就是 50 ms 一拍的循环，
      // 再开一条定时线程只会多一份竞态。
      GraphTick();
      DecayHandshakeRecent();
      if (n <= 0) continue;
      // v0.32：先记账再解析。这一行之前没有任何判断，
      // 所以"握手口收到几个包"是可信的，"界面说没回应"才有对照。
      NoteHandshakePacket(from_ip, from_port);
      // 任何一条数据报都算"这个 IP 还活着"（协议 §4.2/§8 的活标志）
      TouchGraphIp(from_ip);

      std::string line(reinterpret_cast<const char*>(buf.data()), static_cast<size_t>(n));
      while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
      {
        const size_t bar = line.find('|');
        NoteHandshakeCmd(bar == std::string::npos ? line.substr(0, 24) : line.substr(0, bar), line);
      }

      // 图谱帧与握手命令共用 audioPort+2 这个口，必须先判断
      if (IsGraphRequestFrame(line)) {
        ReplyGraphRequest(from_ip, from_port);
        continue;
      }
      if (line.compare(0, 6, "GRAPH|") == 0) {
        HandleGraphFrame(from_ip, from_port, line);
        continue;
      }
      // 摆位帧（§13）与 GRAPH 共用一个口；先判请求帧再判数据帧。
      if (IsPlacementRequestFrame(line)) {
        ReplyPlacementRequest(from_ip, from_port);
        continue;
      }
      if (line.compare(0, 4, "POS|") == 0) {
        HandlePlacementFrame(from_ip, line);
        continue;
      }

      std::vector<std::string> parts;
      size_t start = 0;
      while (true) {
        const size_t pos = line.find('|', start);
        if (pos == std::string::npos) { parts.push_back(line.substr(start)); break; }
        parts.push_back(line.substr(start, pos - start));
        start = pos + 1;
      }
      if (parts.empty()) continue;
      const std::string& cmd = parts[0];

      if (cmd == "CONN-REQ" && parts.size() >= 3) {
        // CONN-REQ|<名字>|<streamId>[|<对端音频口>]
        // 第 4 段是后来加的：电脑要能切回手机当汇聚端，
        // 就必须知道手机的音频口。老版本不带，回落到 47000。
        uint16_t peer_audio = 47000;
        if (parts.size() >= 4) {
          const int v = std::atoi(parts[3].c_str());
          if (v > 0 && v < 65536) peer_audio = static_cast<uint16_t>(v);
        }
        // v0.19：判断"是不是老朋友"必须在 RememberPeer 之前 ——
        // RememberPeer 就在下一行，写完 session_peers 里必然有它，
        // 放行判断若查 session_peers 就变成"任何陌生设备第一次连入都自动 ACK"，
        // 待确认弹窗彻底失效（实测 S1 陌生 IP 直接被 ACK）。
        bool was_known_peer = false;
        {
          std::lock_guard<std::mutex> lock(mutex);
          was_known_peer = session_peers.count(from_ip) > 0;
          RememberPeer(from_ip, parts[1], peer_audio);
        }
        const std::string peer_name = parts[1];
        const uint16_t stream_id = static_cast<uint16_t>(std::stoi(parts[2]));
        // 无论自动接受还是等确认，都先把对端地址记下来，
        // 否则以后想主动发 BYE 时不知道往哪发
        {
          std::lock_guard<std::mutex> lock(mutex);
          auto it = streams.find(stream_id);
          if (it != streams.end()) {
            it->second->peer_ip_for_bye = from_ip;
            it->second->peer_port_for_bye = from_port;
            if (it->second->peer_name.empty()) it->second->peer_name = peer_name;
          }
        }

        // 已经同意过的流再次收到 CONN-REQ（对端没收到 ACK 会重试）：
        // 直接重发 ACK，绝不能再进待确认 —— 否则界面上那个"同意"按钮
        // 会一直挂在那里，点了也没用（流已经在 accepted_ids 里了）。
        // v0.4：accepted_ids 里还留着这个 id、但 streams 里已经没有它 —— 说明上一轮
        // 会话的流被超时清理（或桌面端点了"全部断开"）时忘了摘 accepted_ids。
        // 这时若仍按"重发 ACK"处理，对端会以为连上了并开始推流，但这边没有一路流
        // 能收包/混音：现象就是"重新邀请、进了控制屏却没声音"，而且不重启桌面端
        // 永远好不了。这里把残留记录摘掉，让它走下面的正常建流逻辑。
        bool retry_acked = false;
        {
          std::lock_guard<std::mutex> lock(mutex);
          if (accepted_ids.count(stream_id) > 0) {
            if (streams.count(stream_id) > 0) {
              retry_acked = true;
            } else {
              accepted_ids.erase(stream_id);
              Emit("[~] 清理旧会话残留: 流 " + std::to_string(stream_id) +
                   "（将按新连接重建）");
            }
          }
        }
        if (retry_acked) {
          const std::string ack =
              "CONN-ACK|" + display_name + "|" + std::to_string(stream_id) + "\n";
          handshake_socket.SendToAddr(from_ip, from_port, ack.data(), ack.size());
          // 对端没收到 ACK 会重发 CONN-REQ，重发同样算握手成功：
          // 补一次节点注册 + 互发图谱（协议 §5）。这里以前误写成 return，
          // 会把整条握手线程结束掉——之后所有握手/图谱帧都收不到了。
          GraphAddPeer(from_ip, peer_name, peer_audio);
          continue;
        }

        // 协议 §5：图谱成员 IP 的连接请求，任何角色都自动 ACK（不弹窗）；
        // 非成员沿用 auto_accept / 待确认弹窗。非 hub 也必须能放新设备入网，
        // 否则新设备只能靠发现 hub，而 hub 可能已经切到手机上。
        //
        // v0.19：session_peers（历史连过、还没被 TTL 清掉的设备）也算成员。
        // 手机在"设备列表 / 手动 IP"主动连入时，PC 端图谱里没有它的节点、
        // invited_peers 里也没有它（那两条只在"PC 发邀请"时写入）——
        // CONN-REQ 会掉进待确认弹窗，手机 respondToInvite 30 秒拿不到
        // CONN-ACK 就报「对方还没有同意连接」退回连接屏，可与此同时
        // 音源进程已经在"边握手边推流"，手机其实已经出声了（用户实测：
        // 「显示对方还没同意，但桌面端的音频已经传递到手机里了」）。
        // 连过一次的设备是用户亲自建立的信任关系，再连不应再弹窗。
        bool graph_member = false;
        {
          std::lock_guard<std::mutex> lock(mutex);
          graph_member = FindNodeByIp(graph_, from_ip) != nullptr || was_known_peer;
        }
        if (auto_accept_.load() || !require_handshake || PeerAllowedNow(from_ip) ||
            graph_member) {
          // 自动同意：直接放行
          std::lock_guard<std::mutex> lock(mutex);
          auto it = streams.find(stream_id);
          if (it == streams.end()) {
            // 先建占位：连接已经成立了，即使对方还没开始发音频，
            // 控制界面的网络图上也应该立刻出现这台设备。
            // 否则会出现"连上了但界面上什么都没有"的诡异状态。
            auto created = std::make_unique<Stream>(stream_id, latency_ms);
            created->confirmed = true;
            created->peer_name = peer_name;
            created->peer_ip_for_bye = from_ip;
            created->peer_port_for_bye = from_port;
            // 必须记下时间：默认 last_packet 是 epoch，
            // 会被下面的"掉线清理"当成超时立刻删掉 —— 占位就白建了。
            created->last_packet = Clock::now();
            it = streams.emplace(stream_id, std::move(created)).first;
          } else {
            it->second->confirmed = true;
            it->second->peer_name = peer_name;
            it->second->peer_ip_for_bye = from_ip;
            it->second->peer_port_for_bye = from_port;
            it->second->jitter.Reset();
            it->second->asrc.Reset();
          }
          accepted_ids.insert(stream_id);
          const std::string ack = "CONN-ACK|" + display_name + "|" + std::to_string(stream_id) + "\n";
          handshake_socket.SendToAddr(from_ip, from_port, ack.data(), ack.size());
          Emit("[+] 已接受连接: " + peer_name + " (流 " + std::to_string(stream_id) + ")");
          // 握手成功：对端入图谱，并立刻和它互发一次当前图谱（协议 §4.1/§5）
          GraphAddPeerLocked(from_ip, peer_name, peer_audio, nullptr);
        } else {
          // 要人确认：进待确认列表，等界面点同意
          std::lock_guard<std::mutex> lock(mutex);
          Pending p;
          p.peer_name = peer_name;
          p.peer_ip = from_ip;
          p.peer_port = from_port;
          p.peer_audio = peer_audio;
          p.requested_at_ms = static_cast<int64_t>(
              std::chrono::duration_cast<std::chrono::milliseconds>(
                  Clock::now().time_since_epoch()).count());
          pending[stream_id] = p;
          Emit("[?] 连接请求: " + peer_name + " (流 " + std::to_string(stream_id) + ")，等待确认");
        }
      } else if (cmd == "CONN-INVITE") {
        // CONN-INVITE|<对端名>|<它自己的 ip:音频口>|<序号>
        //
        // 以前这里什么都没有 —— 手机发来的邀请到了就被丢掉，
        // 界面上自然"没反应"。现在入队，由界面决定要不要接受。
        std::string invite_ip = from_ip;
        uint16_t invite_port = 0;
        if (parts.size() >= 3) {
          const size_t colon = parts[2].rfind(':');
          if (colon != std::string::npos) {
            invite_ip = parts[2].substr(0, colon);
            invite_port = static_cast<uint16_t>(std::atoi(parts[2].c_str() + colon + 1));
          }
        }
        {
          std::lock_guard<std::mutex> lock(mutex);
          invite_list[invite_ip] = (parts.size() >= 2 ? parts[1] : invite_ip);
        }
        Emit("[<] 收到邀请: " + invite_ip + ":" + std::to_string(invite_port) +
             (parts.size() >= 2 ? ("  来自 " + parts[1]) : ""));
      } else if (cmd == "CONN-ACK" && parts.size() >= 3) {
        // 我们是发起方，对方同意了
        const uint16_t stream_id = static_cast<uint16_t>(std::stoi(parts[2]));
        std::lock_guard<std::mutex> lock(mutex);
        auto it = streams.find(stream_id);
        if (it != streams.end()) it->second->confirmed = true;
        Emit("[+] 对方已同意: " + parts[1] + " (流 " + std::to_string(stream_id) + ")");
        // 握手成功（我们是发起方）：对端入图谱 + 立刻互发当前图谱（协议 §5）
        GraphAddPeerLocked(from_ip, parts[1], 47000, nullptr);
      } else if (cmd == "CONN-NAK" && parts.size() >= 3) {
        Emit("[-] 对方拒绝连接: " + parts[1]);
      } else if (cmd == "CONN-BYE" && parts.size() >= 2) {
        const uint16_t stream_id = static_cast<uint16_t>(std::stoi(parts[1]));
        std::lock_guard<std::mutex> lock(mutex);
        auto it = streams.find(stream_id);
        if (it != streams.end()) {
          const std::string who = it->second->peer_name.empty() ? ("流 " + std::to_string(stream_id))
                                                                : it->second->peer_name;
          // 对端主动退出：会话关系也一并摘掉。
          // 不摘的话 session_peers 会一直留着它，界面把 30 秒内的"曾经在线"当成现在在线，
          // 用户看到的就是"手机退了、桌面还显示它在"。
          const std::string bye_ip = it->second->peer_ip_for_bye;
          if (!bye_ip.empty()) {
            session_peers.erase(bye_ip);
            // 判活时间戳也摘掉：否则 BYE 之后界面还要再等满 10 秒判活窗口
            // 才变离线，看起来就像"手机都退了，桌面还显示在线"。
            graph_last_seen.erase(bye_ip);
          }
          streams.erase(it);
          Emit("[-] 已断开: " + who);
        }
        // v0.4：会话结束把"已同意"记录也摘掉，否则同一 stream id 重连只会回 ACK 不建流。
        accepted_ids.erase(stream_id);
        pending.erase(stream_id);
        // v0.21：对端是最后一路 → 本机也收摊，图谱删干净（下次连上重建）。
        EndSessionIfAloneLocked();
      }
    }
  }

  // ------------------------------------------------------------ 图谱
  //
  // 契约见 docs/GRAPH-PROTOCOL.md。这里只做"收发 + 什么时候广播"，
  // 合并/编解码这些纯逻辑全在 graph.hpp（可单测，两端逐字节一致）。
  // 所有图谱状态都受 mutex 保护；GRAPH 帧走握手口（audioPort+2）。

  // 节点 id 里的音频口 -> 握手口。越界返回 false（UDP 端口最多 65535）。
  static bool HsPortFor(uint16_t audio_port, uint16_t* out) {
    if (audio_port == 0 || audio_port > 65535 - kControlPortOffset) return false;
    *out = static_cast<uint16_t>(audio_port + kControlPortOffset);
    return true;
  }

  GraphNode MakeSelfNodeLocked() const {
    GraphNode me;
    me.id = self_node_id;
    me.name = SanitizeName(display_name.empty() ? std::string("AudioHub") : display_name);
    me.kind = "pc";   // 本仓库的 HubCore 只跑在 PC 上；Android 端自己报 android
    me.audio_port = port;
    me.http_port = graph_http_port;
    return me;
  }

  // v0.41：判断某个节点 id 是否指向本机。
  //
  // 不能只比 self_node_id（见 self_ips 的注释）：只要 id 里的 IP 是本机任意一张
  // 网卡的地址、端口是本机的音频口，就应该算"本机"。这样手机把它看到的
  // 宿主地址写进 hubId 时，本机仍然认得出自己是汇聚端，不会静音。
  bool IsSelfIdLocked(const std::string& id) const {
    if (id.empty()) return false;
    if (id == self_node_id) return true;
    const size_t colon = id.rfind(':');
    if (colon == std::string::npos) return false;
    const std::string ip = id.substr(0, colon);
    const std::string port_text = id.substr(colon + 1);
    if (port_text != std::to_string(static_cast<unsigned>(port))) return false;
    if (ip == "127.0.0.1" || ip == "localhost") return true;
    for (const auto& own : self_ips) {
      if (own == ip) return true;
    }
    return false;
  }

  // 启动时初始化：PC 默认自己当汇聚端（协议 §1）。
  void GraphInitLocked() {
    graph_ = GraphState();
    self_node_id = graph_self_ip + ":" + std::to_string(port);
    graph_.version = 1;
    graph_.origin = self_node_id;
    graph_.hub_id = self_node_id;
    graph_.nodes.push_back(MakeSelfNodeLocked());
    graph_seen_version = 1;
    graph_last_seen.clear();
    graph_req_replied.clear();
    graph_src_replied.clear();
    graph_sends.clear();
    graph_deferred.clear();
    graph_probes.clear();
    graph_pending_repeats = 0;
    graph_next_periodic = Clock::now() + std::chrono::milliseconds(kGraphPeriodicMs);
    graph_detached = false;   // 新进程：正常参与图谱共识
    graph_detached_at = Clock::now();
    // 摆位随进程启动从空开始：房间布局由桌面端拖拽后广播，不需要持久化在本进程里。
    placement_ = PlacementState();
    placement_seen_version = 0;
    placement_req_replied.clear();
    placement_sends.clear();
    placement_deferred.clear();
    placement_probes.clear();
    placement_pending_repeats = 0;
    placement_next_periodic = Clock::now() + std::chrono::milliseconds(kPlacementPeriodicMs);
  }

  // 会话清空（"全部断开"，协议 §4.6）：版本 +1、origin/hubId 回到自己、只留自节点。
  // 不广播——对端此刻已经断开/不可达。
  void GraphResetLocked() {
    const uint64_t next_version = std::max(graph_.version, graph_seen_version) + 1;
    graph_ = GraphState();
    graph_.version = next_version;
    graph_.origin = self_node_id;
    graph_.hub_id = self_node_id;
    graph_.nodes.push_back(MakeSelfNodeLocked());
    graph_seen_version = next_version;
    graph_last_seen.clear();
    graph_req_replied.clear();
    graph_src_replied.clear();
    graph_sends.clear();
    graph_deferred.clear();
    graph_probes.clear();
    graph_pending_repeats = 0;
    // 关键：把本机标记成"已退出图谱共识"（见 graph_detached 注释）。
    // 只"不主动广播"不够 —— 对端还在周期广播，本机会把它的旧图谱合并回来。
    graph_detached = true;
    graph_detached_at = Clock::now();
    // 摆位是"房间"的状态，不随会话清空；只清收发记账。
    placement_req_replied.clear();
    placement_sends.clear();
    placement_deferred.clear();
    placement_probes.clear();
    placement_pending_repeats = 0;
  }

  // 重新入网：邀请 / 接受邀请 / 主动切汇聚端时调用，解除 graph_detached。
  // 必须在持有 mutex 时调用（与 graph_ 的其它写入同锁）。
  void GraphReattachLocked() {
    graph_detached = false;
    graph_next_periodic = Clock::now() + std::chrono::milliseconds(kGraphPeriodicMs);
  }

  // 「全部断开」的加锁版本：清流 / 清放行 / 重置图谱，必须在持有 mutex 时调用。
  void ClearSessionLocked() {
    session_peers.clear();
    streams.clear();
    accepted_ids.clear();
    pending.clear();
    invited_peers.clear();
    GraphResetLocked();
  }

  // v0.21：最后一路也断了 → 本机跟着收摊，把图谱删干净。
  //
  // 用户要的语义是「对端一走，图谱里就只剩我一个，那我自己也不该还挂着这张
  // 半空的图；下次连上重新建」。以前只有用户手动点「断开所有连接」才会清，
  // 于是对端正常退出（CONN-BYE）或超时掉线后，本机留着一幅只有自节点的图，
  // hub 也还指着自己 —— 界面停在控制屏、节点残留，下次连接进的是"旧房间"。
  //
  // **不能靠"图谱里还有没有别的节点"判断**：图谱协议是只并集、从不删节点
  // （见 graph.cpp UpsertNode 的"字段会话内不变：只增不改"），对端走了它的
  // 节点仍然留在表里。所以判据看的是"还有没有一个活着的关系"：
  //   ① 没有活跃流（streams 空）；② 没有待确认的接入请求（那是"正要连上"）；
  //   ③ 没有会话关系（session_peers 空 —— 对端进过会话就被记在这，比图谱可靠）。
  // 三者同时成立才算"没人了"，随后 GraphResetLocked() 会把图谱重建为只含自节点。
  // 必须在持有 mutex 时调用。
  bool EndSessionIfAloneLocked() {
    if (graph_detached) return false;          // 已经收摊过，别重复
    if (!streams.empty() || !pending.empty()) return false;
    if (!session_peers.empty()) return false;
    Emit("[~] 图谱只剩本机：结束本轮会话，下次连接时重建");
    ClearSessionLocked();
    return true;
  }

  void TouchGraphIp(const std::string& ip) {
    std::lock_guard<std::mutex> lock(mutex);
    TouchGraphIpLocked(ip);
  }

  void TouchGraphIpLocked(const std::string& ip) {
    if (ip.empty()) return;
    if (FindNodeByIp(graph_, ip) == nullptr) return;
    graph_last_seen[ip] = Clock::now();
  }

  // 本机变化：立刻发 2 次、间隔 60 ms（协议 §4.4）。
  void GraphScheduleDoubleLocked() {
    graph_pending_repeats = 2;
    graph_next_repeat = Clock::now();
  }

  // 合并导致的变化 / 学会新节点：立刻发 1 次。
  void GraphScheduleOnceLocked() {
    if (graph_pending_repeats < 1) graph_pending_repeats = 1;
    graph_next_repeat = Clock::now();
  }

  // 限速发送：同一设备 1 秒最多 5 次，超出合并成一次延迟发送（协议 §4.4）。
  bool GraphTrySendLocked(const std::string& ip, uint16_t port, const std::string& frame) {
    if (ip.empty() || port == 0) return false;
    const auto now = Clock::now();
    auto& hist = graph_sends[ip];
    hist.erase(std::remove_if(hist.begin(), hist.end(),
                              [&](const Clock::time_point& t) {
                                return now - t >= std::chrono::milliseconds(kGraphRateWindowMs);
                              }),
               hist.end());
    if (static_cast<int>(hist.size()) >= kGraphRateMaxSends) {
      const auto due = hist.front() + std::chrono::milliseconds(kGraphRateWindowMs);
      auto it = graph_deferred.find(ip);
      if (it == graph_deferred.end() || due < it->second) graph_deferred[ip] = due;
      return false;
    }
    if (!handshake_socket.SendToAddr(ip, port, frame.data(), frame.size())) return false;
    hist.push_back(now);
    return true;
  }

  void GraphQueueProbeLocked(const std::string& ip, uint16_t port) {
    if (ip.empty() || port == 0) return;
    for (const auto& p : graph_probes) {
      if (p.first == ip && p.second == port) return;
    }
    graph_probes.emplace_back(ip, port);
  }

  // 向 nodes 里除自己外的每个节点单播当前图谱，返回成功发出几个。
  // 会话已结束（detached）时一律不发：见 graph_detached 的注释。
  int GraphBroadcastLocked() {
    if (graph_detached) return 0;
    const std::string frame = EncodeGraphFrame(graph_);
    int sent = 0;
    for (const auto& n : graph_.nodes) {
      if (n.id == self_node_id) continue;
      uint16_t hs = 0;
      if (!HsPortFor(n.audio_port, &hs)) continue;
      if (GraphTrySendLocked(NodeIpOf(n.id), hs, frame)) ++sent;
    }
    return sent;
  }

  void GraphSendRequestsLocked() {
    const std::string req = "GRAPH-REQ\n";
    for (const auto& p : graph_probes) {
      handshake_socket.SendToAddr(p.first, p.second, req.data(), req.size());
    }
    graph_probes.clear();
  }

  // 收到 GRAPH-REQ：回当前图谱，同一来源 200 ms 内只回一次（协议 §4.3）。
  void ReplyGraphRequest(const std::string& ip, uint16_t port) {
    std::lock_guard<std::mutex> lock(mutex);
    const auto now = Clock::now();
    auto it = graph_req_replied.find(ip);
    if (it != graph_req_replied.end() &&
        now - it->second < std::chrono::milliseconds(kGraphRequestReplyMs)) {
      return;
    }
    graph_req_replied[ip] = now;
    const std::string frame = EncodeGraphFrame(graph_);
    handshake_socket.SendToAddr(ip, port, frame.data(), frame.size());
  }

  // 收到 GRAPH：merge → 更新活标志 → 只有 changed 才广播（防 ping-pong，协议 §4.2）。
  // 未变化绝对不回发，否则两端会互相触发一轮广播风暴。
  void HandleGraphFrame(const std::string& from_ip, uint16_t from_port,
                        const std::string& line) {
    GraphState remote;
    if (!DecodeGraphFrame(line, &remote)) return;   // 非法帧整帧丢弃

    bool changed = false;
    size_t node_count = 0;
    std::string hub_id;
    {
      std::lock_guard<std::mutex> lock(mutex);
      // 会话已结束：本机不再参与共识，对端的图谱一律不合并。
      // 否则对端周期广播的旧图谱会把刚清掉的节点（和它认的汇聚端）全灌回来，
      // 界面就出现「图谱又满了 / 又跳回控制台 / 汇聚端变成别人」。
      //
      // v0.42：但这道闸门必须有寿命。以前它是无条件的，而解除 detached 的
      // 只有 GraphAddPeerLocked（要 CONN-REQ）和 SwitchGraphHub（要用户点），
      // 手机侧只广播 GRAPH、不发 CONN-REQ —— 于是 PC 永久处于 detached，
      // 把手机关在门外：图谱里永远只有自己，界面上连可选设备都没有。
      // 现在只拦"刚断开后的 kGraphDetachGraceMs"，过了就重新入网。
      if (graph_detached) {
        const auto since = std::chrono::duration_cast<std::chrono::milliseconds>(
            Clock::now() - graph_detached_at).count();
        if (since < kGraphDetachGraceMs) return;
        if (remote.nodes.empty()) return;   // 空图谱不值得一提
        GraphReattachLocked();
        Emit("[GRAPH] 收摊已过 " + std::to_string(since / 1000) +
             " 秒，收到对端图谱，重新入网");
      }
      if (remote.version > graph_seen_version) graph_seen_version = remote.version;

      // 先记下这次新学到的节点：合并后要给它们各补一次 GRAPH-REQ（协议 §4.4）
      std::vector<std::pair<std::string, uint16_t>> probes;
      for (const auto& rn : remote.nodes) {
        if (rn.id == self_node_id) continue;
        if (FindNode(graph_, rn.id) != nullptr) continue;
        uint16_t hs = 0;
        if (HsPortFor(rn.audio_port, &hs)) probes.emplace_back(NodeIpOf(rn.id), hs);
      }

      const GraphMergeResult result = MergeGraph(&graph_, remote);
      changed = result.changed;
      // v0.41：对端报回来的 hubId 可能就是"本机的另一个地址"。
      //
      // 场景：手机在它那侧看到的电脑地址是 10.0.2.2（QEMU NAT 网关）或
      // 192.168.x（另一张网卡），于是它把 hubId 写成 `<那个地址>:47000`。
      // 本机 self_node_id 却是 10.135.129.59:47000 —— 字符串不等，
      // IsHub() 返回 false，MixerLoop 把混音整块清空 → 电脑没声音。
      //
      // 这里把它归一到 self_node_id：语义上"汇聚端是我"，只是名字不同。
      // 只在确实指向本机时才改，且要递增 version，让这个更正能传播出去
      // （否则对端会一直以为自己那侧的地址才是汇聚端）。
      if (IsSelfIdLocked(graph_.hub_id) && graph_.hub_id != self_node_id) {
        const std::string before = graph_.hub_id;
        graph_.hub_id = self_node_id;
        graph_.origin = self_node_id;
        if (graph_.version <= graph_seen_version) graph_.version = graph_seen_version + 1;
        graph_seen_version = graph_.version;
        changed = true;
        Emit("[GRAPH] 汇聚端地址归一: " + before + " -> " + self_node_id + "（都是本机）");
      }
      // 判活要放在合并之后：第一次收到某台设备的 GRAPH 时，它的节点就是
      // 这一帧才学到的，合并前 TouchGraphIpLocked 找不到节点会直接返回，
      // 结果"刚广播过"的设备反而显示离线（真机上要等它 3 秒后再发一帧才好）。
      TouchGraphIpLocked(from_ip);
      // v0.38：源地址不可信时的判活兜底。
      //
      // 数据报的源地址不一定等于发送方自己的节点 IP：QEMU 用户态 NAT（仿真机
      // 10.0.2.15 到宿主这边源地址变成 127.0.0.1）、AP 中继、多出口都会造成
      // 这种错位。一旦错位，FindNodeByIp(from_ip) 要么落空、要么命中一条
      // "以源地址命名"的伪节点，真正那台设备永远 online=false —— 界面据此
      // 判成"网络不在"，一直停在连接屏，而对方其实每 3 秒都在广播 GRAPH。
      //
      // 判据（v0.39）：数据报源地址**不在帧内那张节点表里**，就说明源地址
      // 不是发送方自己的节点 IP，此时只有"帧内的节点表"是可用证据 —— 把表里
      // 所有非自节点都记一次活跃。
      //
      // 为什么不用帧里的 origin：本机从未学到过汇聚端时 origin 本来就是空串
      // （手机刚启动、或对端还没收到过我们的帧），此时 origin 判据永远不成立，
      // 兜底形同虚设（v0.38 实测：帧是 `GRAPH|0|||...`，origin 为空，节点仍然
      // 全部 online=false）。
      //
      // 副作用是：一台真掉线的机器只要还被别人列在帧里就会显示在线（手机端
      // v0.22 出于同样理由也是这么做的），可接受 —— 界面"在线"本来就只是
      // 提示，真正判活还有音频流与 discovery 两条独立证据。
      {
        bool from_matches_node = false;
        for (const auto& rn : remote.nodes) {
          if (NodeIpOf(rn.id) == from_ip) { from_matches_node = true; break; }
        }
        if (!from_matches_node) {
          for (const auto& rn : remote.nodes) {
            if (rn.id == self_node_id) continue;
            TouchGraphIpLocked(NodeIpOf(rn.id));
          }
          // v0.39：还要**按数据报源地址回一帧**。
          //
          // 对端发来的帧里如果没写我们（它在自己的图谱里还不认识本机），它就
          // 永远学不到 hubId —— 表现就是「手机界面没有汇聚端」：本机这边早就是
          // 汇聚端了，但对端一个能用的 GRAPH 都没收到过。
          // 正常情况下 GraphBroadcastLocked() 会按图谱里的节点 IP 单播回去，
          // 而源地址错位时（NAT/中继）那张表里根本没有这个源地址，广播打不到它。
          // 这里直接按 from_ip:from_port 回一帧，走的是真实收包路径，一定到得了。
          const auto replied = graph_src_replied.find(from_ip);
          const bool due = replied == graph_src_replied.end() ||
                           Clock::now() - replied->second >=
                               std::chrono::milliseconds(kGraphSourceReplyMs);
          if (due) {
            graph_src_replied[from_ip] = Clock::now();
            const std::string back = EncodeGraphFrame(graph_);
            handshake_socket.SendToAddr(from_ip, from_port, back.data(), back.size());
          }
        }
      }
      if (changed) {
        if (graph_.version > graph_seen_version) graph_seen_version = graph_.version;
        GraphScheduleOnceLocked();   // 合并变化：立即 1 次
        for (const auto& p : probes) GraphQueueProbeLocked(p.first, p.second);
      }
      node_count = graph_.nodes.size();
      hub_id = graph_.hub_id;
    }

    if (changed) {
      Emit("[GRAPH] 合并 v" + std::to_string(remote.version) + " <- " + from_ip + "，节点 " +
           std::to_string(node_count) + " 个，hub=" + (hub_id.empty() ? "(空)" : hub_id));
    }
  }

  // 握手成功 / 主动邀请：对端入图谱，并立刻互发一次当前图谱（协议 §4.1/§5）。
  // 调用者必须已持锁。返回 true = 新节点。
  bool GraphAddPeerLocked(const std::string& ip, const std::string& name, uint16_t audio_port,
                          std::string* node_id) {
    if (ip.empty()) return false;
    // 有新设备入网 = 会话重新开始：解除"已退出共识"状态。
    // 否则点了「全部断开」之后再邀请/被邀请，图谱永远停在只剩自己。
    GraphReattachLocked();
    bool added = false;
    std::string new_id;
    const GraphNode* existing = FindNodeByIp(graph_, ip);
    if (existing == nullptr) {
      const uint16_t audio = audio_port ? audio_port : 47000;
      GraphNode n;
      n.id = ip + ":" + std::to_string(audio);
      n.name = SanitizeName(name.empty() ? ip : name);
      // 握手帧里没有 kind。对端后发的 GRAPH 也不会覆盖这个字段
      // （协议 §3：同 id 冲突保留 local，节点字段会话内不变），
      // 所以手工邀请/握手学到的节点可能显示 other——已知限制，UI 不依赖 kind 渲染。
      n.kind = "other";
      n.audio_port = audio;
      n.http_port = 0;
      if (!UpsertNode(&graph_, n)) return false;
      new_id = n.id;
      added = true;
      GraphScheduleDoubleLocked();   // 节点入网属于本地变化：连发 2 次
    }

    const GraphNode* node = FindNodeByIp(graph_, ip);
    if (node == nullptr) return added;
    uint16_t hs = 0;
    if (HsPortFor(node->audio_port, &hs)) {
      // §5 的"立刻互发一次当前 GRAPH"：不能等 3 秒定时器，
      // 否则新入网的设备要空等一轮才知道真正的 hub 是谁。
      GraphTrySendLocked(ip, hs, EncodeGraphFrame(graph_));
      GraphQueueProbeLocked(ip, hs);
      // 摆位同理：新设备入网立刻拿一份当前布局，并回探它知道的布局（§13.3）。
      if (placement_.version > 0) PlacementTrySendLocked(ip, hs, EncodePlacementFrame(placement_));
      GraphQueuePlacementProbeLocked(ip, hs);
    }
    if (node_id != nullptr && added) *node_id = new_id;
    return added;
  }

  // 同上，但调用者不持锁（用于 HTTP/握手线程的回调点）。
  void GraphAddPeer(const std::string& ip, const std::string& name, uint16_t audio_port) {
    std::string node_id;
    bool added = false;
    {
      std::lock_guard<std::mutex> lock(mutex);
      added = GraphAddPeerLocked(ip, name, audio_port, &node_id);
    }
    if (added) Emit("[GRAPH] 节点加入: " + node_id);
  }

  // 由握手线程每轮调用一次（50 ms 一拍）：周期广播、补发、延迟发送、GRAPH-REQ。
  // 不额外开线程：图谱的定时需求（3 s / 60 ms / 200 ms）都远粗于 50 ms 的收包超时。
  void GraphTick() {
    std::vector<std::string> deferred_ips;
    {
      std::lock_guard<std::mutex> lock(mutex);
      const auto now = Clock::now();

      // 周期保活：每 3 秒一次，补偿丢包（协议 §4.4）
      if (now >= graph_next_periodic) {
        graph_next_periodic = now + std::chrono::milliseconds(kGraphPeriodicMs);
        GraphBroadcastLocked();
      }

      // 本地变化的第 2 次广播（间隔 60 ms）
      if (graph_pending_repeats > 0 && now >= graph_next_repeat) {
        graph_pending_repeats--;
        graph_next_repeat = now + std::chrono::milliseconds(kGraphRepeatMs);
        GraphBroadcastLocked();
      }

      // 被限速合并掉的目标：窗口滑过之后补发一次
      for (auto it = graph_deferred.begin(); it != graph_deferred.end();) {
        if (now >= it->second) {
          deferred_ips.push_back(it->first);
          it = graph_deferred.erase(it);
        } else {
          ++it;
        }
      }
      for (const auto& ip : deferred_ips) {
        const GraphNode* node = FindNodeByIp(graph_, ip);
        if (node == nullptr || node->id == self_node_id) continue;
        uint16_t hs = 0;
        if (!HsPortFor(node->audio_port, &hs)) continue;
        GraphTrySendLocked(ip, hs, EncodeGraphFrame(graph_));
      }

      GraphSendRequestsLocked();

      // ---- 摆位：周期保活 / 本地改动补发 / 限速补发 / POS-REQ ----
      if (now >= placement_next_periodic) {
        placement_next_periodic = now + std::chrono::milliseconds(kPlacementPeriodicMs);
        PlacementBroadcastLocked();
      }
      if (placement_pending_repeats > 0 && now >= placement_next_repeat) {
        placement_pending_repeats--;
        placement_next_repeat = now + std::chrono::milliseconds(kPlacementRepeatMs);
        PlacementBroadcastLocked();
      }
      for (auto it = placement_deferred.begin(); it != placement_deferred.end();) {
        if (now < it->second) {
          ++it;
          continue;
        }
        const std::string ip = it->first;
        it = placement_deferred.erase(it);
        const GraphNode* node = FindNodeByIp(graph_, ip);
        if (node == nullptr || node->id == self_node_id) continue;
        uint16_t hs = 0;
        if (!HsPortFor(node->audio_port, &hs)) continue;
        PlacementTrySendLocked(ip, hs, EncodePlacementFrame(placement_));
      }
      PlacementSendRequestsLocked();

      // ---- 会话关系保活：把已经退出的设备从"会话设备"里摘掉（v1.7）----
      SessionPeersTickLocked(now);
    }
  }

  // 本地切换汇聚端（协议 §4.5）。节点不存在返回 false（HTTP 回 unknown node）。
  bool SwitchGraphHub(const std::string& node_id) {
    bool is_hub_now = false;
    std::string hub_ip;
    uint16_t hub_audio_port = 0;
    // 其它节点的 ip:音频口（锁内快照，锁外发 ROLE —— 发送不能持锁）。
    std::vector<std::pair<std::string, uint16_t>> others;
    {
      std::lock_guard<std::mutex> lock(mutex);
      const GraphNode* target = FindNode(graph_, node_id);
      if (target == nullptr) return false;
      // 用户主动指定汇聚端 = 会话重新开始：解除"已退出共识"状态。
      GraphReattachLocked();
      // version = max(version, seen) + 1：SwitchHub 内部只做 +1，
      // 这里先把本地 version 抬到"见过的最大值"，避免比远端回退。
      if (graph_seen_version > graph_.version) graph_.version = graph_seen_version;
      if (!SwitchHub(&graph_, self_node_id, node_id)) return false;
      graph_seen_version = graph_.version;
      hub_ip = NodeIpOf(target->id);
      hub_audio_port = target->audio_port;
      for (const auto& n : graph_.nodes) {
        if (n.id == self_node_id) continue;
        const std::string ip = NodeIpOf(n.id);
        if (!ip.empty() && n.audio_port != 0) others.emplace_back(ip, n.audio_port);
      }
      GraphScheduleDoubleLocked();   // 协议 §4.5：连发 2 次
      is_hub_now = IsHub(graph_, self_node_id);
    }
    Emit(std::string("[GRAPH] 汇聚端切换 -> ") + node_id +
         (is_hub_now ? "（本机是汇聚端）" : "（本机不是汇聚端，输出静音）"));

    // 通知被选中的那台设备"你现在是汇聚端了"。
    //
    // 为什么必须发：GRAPH 帧只告诉所有人"谁是 hub"，但那台设备的接收服务
    // 不会因为图谱变了就自己起来 —— 手机端的 ReceiverService 只有收到
    // ROLE|hub 才会启动。以前这里只切图谱不发 ROLE，用户把汇聚端切到手机后
    // 看到的就是"电脑一直在发，手机一路也收不到"（2026-10-29 报的 bug）。
    // 反过来切回自己（本机当汇聚端）时也要告诉对端改回当音源。
    if (!hub_ip.empty() && hub_audio_port != 0) {
      if (hub_ip != NodeIpOf(self_node_id)) {
        // 对端当汇聚端：让它起来收流。
        SendRoleTo(hub_ip, hub_audio_port, true, "", 0);
      } else {
        // 本机当汇聚端：通知原来那台当音源的设备改回"往我这里送"。
        // 只有对端是手机时才需要 —— 它的采集要靠这条指令驱动，桌面端
        // 音源由界面自己按图谱 reconcile，不依赖这条。
        for (const auto& n : others) {
          SendRoleTo(n.first, n.second, false, hub_ip, hub_audio_port);
        }
      }
    }
    return true;
  }

  // HTTP /api/graph/sync：立刻广播一次 + 给每个节点发一次 GRAPH-REQ（协议 §7.3）。
  void SyncGraphNow() {
    std::lock_guard<std::mutex> lock(mutex);
    GraphBroadcastLocked();
    for (const auto& n : graph_.nodes) {
      if (n.id == self_node_id) continue;
      uint16_t hs = 0;
      if (!HsPortFor(n.audio_port, &hs)) continue;
      GraphQueueProbeLocked(NodeIpOf(n.id), hs);
    }
    GraphSendRequestsLocked();
  }

  int64_t GraphPeerLastSeenMs(const std::string& ip) const {
    std::lock_guard<std::mutex> lock(mutex);
    const auto it = graph_last_seen.find(ip);
    if (it == graph_last_seen.end()) return -1;
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - it->second).count();
  }

  // ------------------------------------------------------------ 房间摆位（POS）
  //
  // 契约见 docs/GRAPH-PROTOCOL.md §13。骨架照抄 GRAPH（同一条握手线程、同一个
  // audioPort+2 的口），但版本、限速、周期节拍全部独立：拖设备不该 bump 图谱版本，
  // 切换汇聚端也不该覆盖房间布局。

  void PlacementScheduleDoubleLocked() {
    placement_pending_repeats = 2;
    placement_next_repeat = Clock::now();
  }

  void PlacementScheduleOnceLocked() {
    if (placement_pending_repeats < 1) placement_pending_repeats = 1;
    placement_next_repeat = Clock::now();
  }

  bool PlacementTrySendLocked(const std::string& ip, uint16_t port, const std::string& frame) {
    if (ip.empty() || port == 0) return false;
    const auto now = Clock::now();
    auto& hist = placement_sends[ip];
    hist.erase(std::remove_if(hist.begin(), hist.end(),
                              [&](const Clock::time_point& t) {
                                return now - t >= std::chrono::milliseconds(kPlacementRateWindowMs);
                              }),
               hist.end());
    if (static_cast<int>(hist.size()) >= kPlacementRateMaxSends) {
      const auto due = hist.front() + std::chrono::milliseconds(kPlacementRateWindowMs);
      auto it = placement_deferred.find(ip);
      if (it == placement_deferred.end() || due < it->second) placement_deferred[ip] = due;
      return false;
    }
    if (!handshake_socket.SendToAddr(ip, port, frame.data(), frame.size())) return false;
    hist.push_back(now);
    return true;
  }

  // 空表 + version 0 = 从没摆过，没必要发；用户清空后是"空表 + version>0"，
  // 必须发出去，否则对端会一直留着旧布局。
  int PlacementBroadcastLocked() {
    if (placement_.version == 0 && placement_.items.empty()) return 0;
    const std::string frame = EncodePlacementFrame(placement_);
    int sent = 0;
    for (const auto& n : graph_.nodes) {
      if (n.id == self_node_id) continue;
      uint16_t hs = 0;
      if (!HsPortFor(n.audio_port, &hs)) continue;
      if (PlacementTrySendLocked(NodeIpOf(n.id), hs, frame)) ++sent;
    }
    return sent;
  }

  void GraphQueuePlacementProbeLocked(const std::string& ip, uint16_t port) {
    if (ip.empty() || port == 0) return;
    for (const auto& p : placement_probes) {
      if (p.first == ip && p.second == port) return;
    }
    placement_probes.emplace_back(ip, port);
  }

  void PlacementSendRequestsLocked() {
    const std::string req = "POS-REQ\n";
    for (const auto& p : placement_probes) {
      handshake_socket.SendToAddr(p.first, p.second, req.data(), req.size());
    }
    placement_probes.clear();
  }

  // 收到 POS-REQ：回当前摆位，同一来源 200 ms 内只回一次（与 GRAPH-REQ 同策略）。
  void ReplyPlacementRequest(const std::string& ip, uint16_t port) {
    std::lock_guard<std::mutex> lock(mutex);
    const auto now = Clock::now();
    auto it = placement_req_replied.find(ip);
    if (it != placement_req_replied.end() &&
        now - it->second < std::chrono::milliseconds(kPlacementRequestReplyMs)) {
      return;
    }
    placement_req_replied[ip] = now;
    if (placement_.version == 0 && placement_.items.empty()) return;  // 没摆过就不回空帧
    const std::string frame = EncodePlacementFrame(placement_);
    handshake_socket.SendToAddr(ip, port, frame.data(), frame.size());
  }

  // 收到 POS：整表 LWW 合并；只有 changed 才转发一次（防 ping-pong，与 GRAPH 同规则）。
  void HandlePlacementFrame(const std::string& from_ip, const std::string& line) {
    PlacementState remote;
    if (!DecodePlacementFrame(line, &remote)) return;  // 非法帧整帧丢弃

    bool changed = false;
    uint64_t version = 0;
    size_t count = 0;
    {
      std::lock_guard<std::mutex> lock(mutex);
      TouchGraphIpLocked(from_ip);
      if (remote.version > placement_seen_version) placement_seen_version = remote.version;
      const PlacementMergeResult result = MergePlacement(&placement_, remote);
      changed = result.changed;
      if (changed) {
        if (placement_.version > placement_seen_version) placement_seen_version = placement_.version;
        PlacementScheduleOnceLocked();
      }
      version = placement_.version;
      count = placement_.items.size();
    }
    if (changed) {
      Emit("[GRAPH] 合并摆位 v" + std::to_string(version) + " <- " + from_ip + "，" +
           std::to_string(count) + " 项");
    }
  }

  // 本地摆位改动（拖拽落点 / 清除）：先抬到"见过的最大版本"再 +1，然后连发 2 次。
  bool SetNodePlacementLocked(const std::string& node_id, int x_cm, int z_cm) {
    if (placement_seen_version > placement_.version) placement_.version = placement_seen_version;
    if (IsPlacementCoordValid(x_cm) == false || IsPlacementCoordValid(z_cm) == false) return false;
    // 幂等：GUI 拖完可能重复提交同一个厘米坐标，不应该制造新版本。
    const PlacementItem* cur = FindPlacement(placement_, node_id);
    if (cur != nullptr && cur->x_cm == x_cm && cur->z_cm == z_cm) return true;
    if (!SetPlacementItem(&placement_, node_id, x_cm, z_cm, self_node_id)) return false;
    placement_seen_version = placement_.version;
    PlacementScheduleDoubleLocked();
    return true;
  }

  bool ClearNodePlacementLocked(const std::string& node_id) {
    if (placement_seen_version > placement_.version) placement_.version = placement_seen_version;
    if (!RemovePlacementItem(&placement_, node_id, self_node_id)) return false;
    placement_seen_version = placement_.version;
    PlacementScheduleDoubleLocked();
    return true;
  }

  static bool IsPlacementCoordValid(int cm) {
    return cm >= -kPlacementCoordLimitCm && cm <= kPlacementCoordLimitCm;
  }

  // ------------------------------------------------------------ 控制线程

  // PING/PONG 必须单独一条线程。
  //
  // 曾经把它放在数据接收循环里"顺手处理"，结果控制 socket 是 30 ms 超时的
  // 阻塞读，每个数据包都要白等最多 30 ms——200 包/秒的源端直接被处理成
  // 30 包/秒，抖动缓冲被抽干，表现是"第一秒有声音然后永久静音"。
  // 低 RTT 和不阻塞数据通路，只能靠独立线程同时满足。
  void ControlLoop() {
    uint8_t ctrl[64];
    while (!stop_requested.load()) {
      std::string from_ip;
      uint16_t from_port = 0;
      const int n = ctrl_socket.ReceiveFrom(ctrl, sizeof(ctrl), &from_ip, &from_port);
      if (n < 5 || ctrl[0] != 1 || from_port == 0) continue;

      uint8_t pong[13];
      pong[0] = 2;
      std::memcpy(pong + 1, ctrl + 1, 4);  // t1 回显
      const uint32_t t2 = NowMs();
      std::memcpy(pong + 5, &t2, 4);       // t2：Hub 收到时刻
      const uint32_t t3 = NowMs();
      std::memcpy(pong + 9, &t3, 4);       // t3：Hub 回复时刻
      ctrl_socket.SendToAddr(from_ip, from_port, pong, sizeof(pong));
    }
  }

  // ------------------------------------------------------- 渲染落盘（诊断）

  // 把"实际送给输出设备"的样本写成 16-bit PCM WAV。
  //
  // 为什么值得做：用户需要的是"我这边到底有没有出声"的可验证证据。电平数字
  // 已经能回答"有没有信号"，但只有一段能直接播放的音频能排除"是软件静音还是
  // 耳朵听不见"。文件用标准 44 字节头，双击就能播。
  bool StartDumpFile(const std::string& path, std::string* error) {
    std::lock_guard<std::mutex> lock(dump_mutex);
    CloseDumpFileLocked();  // 已在录就先收尾（换文件不该丢前一段）
    FILE* f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) {
      if (error) *error = "打不开输出文件: " + path;
      return false;
    }
    // 先写 44 字节占位头，收尾时回填长度。WAV 头必须知道总长度，
    // 而我们是边录边写，所以只能先占位。
    unsigned char header[44] = {0};
    std::fwrite(header, 1, sizeof(header), f);
    std::fflush(f);
    dump_frames = 0;
    dump_data_bytes = 0;
    dump_path = path;
    dump_file.store(f, std::memory_order_release);  // 最后才发布给实时线程
    return true;
  }

  void CloseDumpFile() {
    std::lock_guard<std::mutex> lock(dump_mutex);
    CloseDumpFileLocked();
  }

  void CloseDumpFileLocked() {
    FILE* f = dump_file.exchange(nullptr, std::memory_order_acq_rel);
    if (f == nullptr) return;
    // 摘掉指针后，实时线程最多只有一个"已经取到指针、正在 fwrite"的窗口。
    // 等它离开再 fclose，否则就是经典的 use-after-close（会直接崩）。
    while (dump_busy.load(std::memory_order_acquire) != 0) {
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    // 回填 WAV 头：RIFF/WAVE + fmt(16) + data
    const uint32_t byte_rate = kSampleRate * kChannels * 2;
    const uint32_t riff_size = 36 + dump_data_bytes;
    unsigned char h[44];
    std::memcpy(h + 0, "RIFF", 4);
    std::memcpy(h + 4, &riff_size, 4);
    std::memcpy(h + 8, "WAVE", 4);
    std::memcpy(h + 12, "fmt ", 4);
    const uint32_t fmt_size = 16;
    std::memcpy(h + 16, &fmt_size, 4);
    const uint16_t audio_format = 1;  // PCM
    std::memcpy(h + 20, &audio_format, 2);
    const uint16_t channels = kChannels;
    std::memcpy(h + 22, &channels, 2);
    const uint32_t sample_rate = kSampleRate;
    std::memcpy(h + 24, &sample_rate, 4);
    std::memcpy(h + 28, &byte_rate, 4);
    const uint16_t block_align = kChannels * 2;
    std::memcpy(h + 32, &block_align, 2);
    const uint16_t bits = 16;
    std::memcpy(h + 34, &bits, 2);
    std::memcpy(h + 36, "data", 4);
    std::memcpy(h + 40, &dump_data_bytes, 4);
    std::fseek(f, 0, SEEK_SET);
    std::fwrite(h, 1, sizeof(h), f);
    std::fflush(f);
    std::fclose(f);
    Emit("[DUMP] 渲染输出已保存: " + dump_path + "（" + std::to_string(dump_frames) +
         " 帧 / " + std::to_string(dump_frames * 100 / kSampleRate / 100.0) + " 秒）");
  }

  // ------------------------------------------------------------ 混音线程

  void MixerLoop() {
    const int block = kFramesPerPacket;
    const size_t block_samples = static_cast<size_t>(block) * kChannels;

    std::vector<float> mix_buffer(block_samples);
    std::vector<MixInput> mix_inputs;

    auto next = Clock::now();
    const auto tick = std::chrono::microseconds(block * 1000000 / kSampleRate);

    while (!stop_requested.load()) {
      next += tick;

      {
        std::lock_guard<std::mutex> lock(mutex);

        // 每块取一次监听位姿快照：HTTP 线程可能正在改；整块用同一份，
        // 避免块内不同源用不同位姿导致声像互相矛盾。
        const ListenerPose listener = listener_pose;
        const bool listener_ok = listener_valid;

        // 掉线的流及时清掉，否则手机上按了停止，这边还留着一个死行
        const auto now = Clock::now();
        bool dropped_any = false;
        for (auto it = streams.begin(); it != streams.end();) {
          const auto idle =
              std::chrono::duration_cast<std::chrono::seconds>(now - it->second->last_packet)
                  .count();
          // 已确认但一包没来的流（刚同意、对方还在授权采集）给 60 秒宽限，
          // 否则节点会在界面上闪一下就没。真正在发流的仍然按短超时清理。
          const int limit = (it->second->packets == 0) ? 60 : kSourceTimeoutSec;
          if (idle > limit) {
            Emit("[-] 流掉线: id=" + std::to_string(it->first));
            // v0.4：掉线清理必须同步摘掉 accepted_ids。只删 streams 会留下"幽灵已同意"，
            // 对端（手机固定用流 1）重连时命中 CONN-REQ 的"重发 ACK"分支，
            // 于是 ACK 发出去了、流却没建 —— 界面显示连上、实际一包都不混。
            accepted_ids.erase(it->first);
            it = streams.erase(it);
            dropped_any = true;
          } else {
            ++it;
          }
        }
        // v0.21：超时掉线也可能让图谱只剩自己（对端崩溃、没发 BYE 就断电），
        // 与正常断开一样收摊。放在循环外，避免边遍历边改 streams。
        if (dropped_any) EndSessionIfAloneLocked();

        mix_inputs.clear();
        for (auto& kv : streams) {
          Stream& s = *kv.second;

          // 安全网：读指针已经跑到源端写指针前面时，之后每个包都被判"迟到/超容量"，
          // 水位恒 0、电平 -120，包计数却照涨——现象就是"正常在发包但耳机没声"，
          // 不重启汇聚端永远回不来。连续 1 秒没有一个包成功入缓冲就清空重来，
          // 让下一个包按首包逻辑重新锚定读指针。采样序号大幅回退的情况在上面
          // 收包线程里已经立刻处理了，这里兜住小幅回退/序号连续但时间轴错开的情形。
          if (s.packets > 0 && !s.awaiting_reanchor && s.jitter.started() &&
              (now - s.last_good_push) > std::chrono::milliseconds(kPushStallResetMs)) {
            RebaseStreamLocked(s, "连续 " + std::to_string(kPushStallResetMs) +
                                      " 毫秒没有有效包进入缓冲");
          }

          // ---- 外环：自适应缓冲 + PI 微调 ----
          //
          // v1.7：高水位不再硬追帧。以前 FastForward 一次丢掉几十毫秒，
          // 听感就是"隔一会儿跳一下"；WiFi 突发越频繁跳得越勤。
          // 现在网络把数据推后多少，就把目标延迟抬多少吸收掉，只留 ASRC 的
          // ±0.2% 比率慢慢回正；水位逼近容量（400 ms）才一次性丢回 200 ms 保命。
          if (++s.tick_counter >= kAdjustEveryTicks) {
            s.tick_counter = 0;
            const int occupancy = s.jitter.occupancy_ms();
            const int target = s.jitter.target_delay_ms();
            const int excess = occupancy - target;
            const auto js = s.jitter.stats();

            // 1) 出现欠载 = 缓冲不够大。按这次丢掉的量补余量：插静音只是在读指针
            //    后面加一段空位，声音不断，只是整体后移；立即生效，不靠慢速 PI。
            if (js.frames_underrun > s.last_underrun_frames) {
              const uint64_t lost = js.frames_underrun - s.last_underrun_frames;
              s.last_underrun_frames = js.frames_underrun;
              const int lost_ms = static_cast<int>(lost * 1000 / kSampleRate);
              // 只有"成块地没数据"（≥5 ms）才算真实网络停顿。采样率微差造成的
              // 一两帧欠载交给 PI 比例微调去追，不能因此插 20 ms 静音——那样
              // 每来一次小欠载就多一个可闻的空洞，反而制造卡顿。
              if (lost_ms >= 5) {
                const int grow =
                    std::clamp(lost_ms + 10, kAdaptiveGrowMs, kAdaptiveGrowMaxMs);
                const int next_target = std::min(target + grow, kAdaptiveTargetMaxMs);
                if (next_target > target) {
                  s.jitter.SetTargetDelayMs(next_target);
                  s.jitter.InsertSilence(MsToFrames(next_target - target));
                  Emit("[~] 流 " + std::to_string(s.id) + " 抖动欠载 " +
                       std::to_string(lost_ms) + " ms，缓冲 " + std::to_string(target) +
                       " -> " + std::to_string(next_target) + " ms");
                }
              }
            }

            // 2) 水位长期高于目标：数据被网络推后了。抬目标吸收，不做硬丢帧。
            if (excess > kRaiseThresholdMs) {
              s.high_windows++;
            } else {
              s.high_windows = 0;
            }
            if (s.high_windows >= kRaiseWindows && occupancy > target) {
              const int next_target = std::min(occupancy, kAdaptiveTargetMaxMs);
              if (next_target > target) {
                s.jitter.SetTargetDelayMs(next_target);
                Emit("[~] 流 " + std::to_string(s.id) + " 网络抖动，目标延迟 " +
                     std::to_string(target) + " -> " + std::to_string(next_target) +
                     " ms");
              }
              s.high_windows = 0;
            }

            // 3) 水位长期低于目标（网络变好）：慢慢把多留的缓冲还回去，
            //    避免延迟只升不降。只改目标，不动数据，所以不会有跳字。
            if (excess < -kRaiseThresholdMs) {
              s.low_windows++;
            } else {
              s.low_windows = 0;
            }
            if (s.low_windows >= 50 && target > s.base_target_ms) {
              s.jitter.SetTargetDelayMs(std::max(s.base_target_ms, target - 10));
              s.low_windows = 0;
            }

            // 4) 最后保险：水位逼近容量才硬丢，丟到 200 ms。
            if (occupancy > kEmergencyDropMs) {
              s.jitter.FastForward(MsToFrames(occupancy - kEmergencyKeepMs));
              s.last_catchup = Clock::now();
              s.catchup_count++;
              s.pi.Reset();
              s.asrc.SetRatio(1.0);
              Emit("[~] 流 " + std::to_string(s.id) + " 水位 " +
                   std::to_string(occupancy) + " ms 过高，丢弃到 " +
                   std::to_string(kEmergencyKeepMs) + " ms");
            } else {
              const double ratio = 1.0 + s.pi.Update(static_cast<double>(excess), 0.2);
              s.asrc.SetRatio(ratio);
            }
          }

          // 未经确认的流完全不取水。
          // 如果照常取水，缓冲会被抽干、read_pos_ 跑到 highest_written_ 前面，
          // 确认之后每个包都判"迟到"，永远回不来。缓冲满了会自然溢出丢包，
          // 代价只是"确认前的音频被丢"——那本来就该丢。
          if (!s.confirmed) continue;

          const int want =
              static_cast<int>(std::ceil(block * s.asrc.ratio())) + kAsrcReserveFrames;
          const int have = s.asrc.AvailableInputFrames();
          // 上限是 block + 预留 + 2，绝不是 block * 4。
          //
          // 抖动缓冲的供给速率就是 240 帧/tick（200 包/秒 × 240 帧）。
          // 每 tick 取得比这多，缓冲就会被慢慢抽干；一旦 read_pos_ 跑到
          // highest_written_ 前面，之后每个包都判成"迟到"直接丢弃，
          // 缓冲区再也回不来——表现是"第一秒还有声音，然后永久静音"。
          //
          // 但上限也不能压到 block：ASRC 的 4 点插值要回看 idx-1、前看 idx+2，
          // 至少需要 4 帧余量。压到 block+2 时第一 tick 会差一帧，Pull 失败、
          // 补一个 5 ms 的空洞。留出 kAsrcReserveFrames 让内部预留建起来，
          // 稳态收敛到 have=8、need=240，恰好平衡。
          const int need = std::clamp(want - have, 0,
                                      block + kAsrcReserveFrames + 2);

          if (need > 0) {
            const size_t need_samples = static_cast<size_t>(need) * kChannels;
            if (s.pull_buffer.size() < need_samples) s.pull_buffer.resize(need_samples);
            s.jitter.Pull(s.pull_buffer.data(), need);
            s.asrc.Push(s.pull_buffer.data(), need);
          }

          if (s.block_buffer.size() < block_samples) s.block_buffer.resize(block_samples);
          if (!s.asrc.Pull(s.block_buffer.data(), block)) {
            std::memset(s.block_buffer.data(), 0, block_samples * sizeof(float));
          }

          s.meter.Add(s.block_buffer.data(), block_samples);

          MixInput in;
          in.data = s.block_buffer.data();
          // 未经确认的流不参与混音——"请求-确认"机制必须真的挡住声音，
          // 否则只是界面上演戏。
          in.active = !s.muted && s.confirmed;

          // 3D 空间化：位置按设备 IP 存（流重连不丢）。没有位置 / 每源旁路 /
          // 监听位姿还没上报 → 完全走旧路径，保证 2D 行为逐样本不变。
          const auto sp = listener_ok
                              ? spatial_placements.find(s.peer_ip_for_bye)
                              : spatial_placements.end();
          if (sp != spatial_placements.end() && sp->second.enabled && in.active) {
            s.spatial.SetTargetPosition(sp->second.position);
            // Process 原地处理：先拷到每流独立 buffer，MixBlock 再统一求和。
            // user_gain 已折进每耳总增益，in.gain 必须置 1，否则音量乘两次。
            if (s.spatial_buffer.size() < block_samples) s.spatial_buffer.resize(block_samples);
            std::memcpy(s.spatial_buffer.data(), s.block_buffer.data(),
                        block_samples * sizeof(float));
            s.spatial.Process(s.spatial_buffer.data(), block, listener, spatial_params, s.gain);
            in.data = s.spatial_buffer.data();
            in.gain = 1.0f;
          } else {
            in.gain = s.gain;
          }
          mix_inputs.push_back(in);
        }

        // 协议 §6.1：本机不是汇聚端时混音输出静音。
        // 上面的取水循环照常跑——不取水的话切回汇聚端的瞬间缓冲已被抽干，
        // 会有几百毫秒的杂音/欠载。
        //
        // v0.41：加一条"收到音频就出声"的兜底。
        //
        // 为什么需要：`IsHub()` 是 `graph_.hub_id == self_node_id` 的字符串比较，
        // 而 hub_id 是别的设备按**它自己那侧看到的地址**写进图谱的。多网卡
        // （WLAN + 以太网 + VMware）、多出口 NAT、hostname 解析顺序都会让这个
        // 字符串和我们选的网卡对不上。一旦对不上，本机就"认不出自己是汇聚端"，
        // 把好不容易送到手边的音频整块清掉 —— 现象正是「手机上有特效、
        // 电脑上一点声音都没有」，而且重连多少次都一样。
        //
        // 判据：**只要有一路已确认的流在给我送音频**，就说明对方把我当汇聚端
        // （它是 switchHub(我) 之后才开始推流的，否则根本不会有 confirmed 流）。
        // 这时"我是不是 hub"已经不是问题——音频都在手上了，不播才是错的。
        // 这不会让非汇聚端乱出声：非汇聚端收到的是未确认流，不会进 confirmed_streams。
        bool hub_now = IsHub(graph_, self_node_id);
        if (!hub_now) {
          for (const auto& kv : streams) {
            if (kv.second && kv.second->confirmed) { hub_now = true; break; }
          }
        }
        rendering_as_hub.store(hub_now, std::memory_order_relaxed);
        if (!hub_now) mix_inputs.clear();
      }

      // 归因探针（v0.28）：把"这一拍实际混入几路"记下来。
      // mix_inputs 里可能还有 active=false 的条目（未确认 / 已静音），真正出声的
      // 只有 active 的，所以只数 active 的，否则"混入 1 路"会骗人。
      {
        size_t active_inputs = 0;
        for (const auto& in : mix_inputs) {
          if (in.active && in.data != nullptr && in.gain != 0.0f) ++active_inputs;
        }
        mixed_sources.store(static_cast<uint64_t>(active_inputs), std::memory_order_relaxed);
        if (active_inputs > 0) {
          mixed_frames.fetch_add(static_cast<uint64_t>(block), std::memory_order_relaxed);
        }
      }

      MixBlock(mix_buffer.data(), mix_inputs.data(), static_cast<int>(mix_inputs.size()),
               block, kChannels);

      // 总音量必须放在 MixBlock 之后——MixBlock 第一件事就是把 out 清零，
      // 提前乘上去会被直接抹掉。（这个错法不报错，只是"调总音量没反应"。）
      const float master = master_gain.load();
      if (master != 1.0f) {
        for (size_t i = 0; i < block_samples; ++i) mix_buffer[i] *= master;
      }

      SoftLimit(mix_buffer.data(), static_cast<int>(block_samples), 0.85f);
      {
        // 有流参与混音的这一拍，混出来的到底有没有声音。
        const FrameStats fs = ScanFrames(mix_buffer.data(), block_samples, kChannels);
        if (fs.audible > 0) {
          mixed_audible_frames.fetch_add(static_cast<uint64_t>(block), std::memory_order_relaxed);
        }
      }
      bus_meter.Add(mix_buffer.data(), block_samples);
      output_ring.Write(mix_buffer.data(), block_samples);

      std::this_thread::sleep_until(next);
      if (Clock::now() - next > std::chrono::milliseconds(50)) next = Clock::now();
    }
  }

  // ------------------------------------------------------------ 快照

  uint32_t NowMs() const {
    return static_cast<uint32_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch())
            .count() &
        0xFFFFFFFFu);
  }

  std::vector<SourceInfo> Sources() const {
    std::lock_guard<std::mutex> lock(mutex);
    std::vector<SourceInfo> out;
    out.reserve(streams.size());
    for (const auto& kv : streams) {
      const Stream& s = *kv.second;
      SourceInfo info;
      info.id = s.id;
      info.occupancy_ms = s.jitter.occupancy_ms();
      info.target_ms = s.jitter.target_delay_ms();
      info.catchup_count = s.catchup_count;
      info.gain = s.gain;
      info.muted = s.muted;
      info.packets = s.packets;
      info.ratio = s.asrc.ratio();
      // 每路电平：Take 是"取走并清零"，所以这里每次调用拿到的是上一段的均值。
      // UI 每 200 ms 取一次，正好一秒被切成 5 段，显示的是滚动均值。
      double level = -120.0;
      if (s.meter.Take(&level)) info.level_dbfs = level;
      const auto st = s.jitter.stats();
      info.underrun_frames = st.frames_underrun;
      info.overflow_packets = st.packets_overflow;
      info.late_packets = st.packets_late;
      info.lost_packets = st.packets_overflow;
      info.confirmed = s.confirmed;
      info.peer_ip = s.peer_ip_for_bye;
      info.peer_name = s.peer_name;
      out.push_back(info);
    }
    return out;
  }

  HubStats Stats() const {
    HubStats st;
    std::lock_guard<std::mutex> lock(mutex);
    st.source_count = streams.size();
    st.latency_ms = latency_ms;
    st.malformed_packets = malformed.load();
    st.render_underruns = render_underruns.load();
    double level = -120.0;
    if (bus_meter.Take(&level)) st.bus_level_dbfs = level;
    // 渲染探针：只读，不影响上面 Take 的节奏，也不会被任何轮询取走。
    st.render_frames_total = render_frames_total.load(std::memory_order_relaxed);
    st.render_ring_missed = render_ring_missed.load(std::memory_order_relaxed);
    st.render_audible_frames = render_audible_frames.load(std::memory_order_relaxed);
    const double peak = render_peak_abs.load(std::memory_order_relaxed);
    st.render_peak_dbfs = 20.0 * std::log10(peak + 1e-12);
    // 归因探针（v0.28）：这三个量回答"audible 为什么是 0"。
    st.rendering_as_hub = rendering_as_hub.load(std::memory_order_relaxed);

    // v0.30：接收侧证据（锁 rx_mutex，与 streams 的锁不嵌套）
    st.rx_packets = received_packets.load(std::memory_order_relaxed);
    st.rx_bytes = received_bytes.load(std::memory_order_relaxed);
    st.rx_bad_packets = rx_bad_packets.load(std::memory_order_relaxed);
    st.rx_first_at_ms = rx_first_at_ms.load(std::memory_order_relaxed);
    {
      size_t unconfirmed = 0;
      for (const auto& kv : streams) {
        if (!kv.second->confirmed) ++unconfirmed;
      }
      st.rx_pending_streams = unconfirmed;
    }
    {
      std::lock_guard<std::mutex> rx_lock(rx_mutex);
      st.rx_distinct_senders = static_cast<uint32_t>(rx_senders.size());
      st.rx_last_ip = rx_last_ip;
      st.rx_last_port = rx_last_port;
      st.rx_last_reject = rx_last_reject;
    }
    // v0.32：握手口证据（与音频口分开，见 HubStats 里的注释）
    st.hs_packets = hs_packets.load(std::memory_order_relaxed);
    st.hs_recent = hs_recent.load(std::memory_order_relaxed);
    st.hs_first_at_ms = hs_first_at_ms.load(std::memory_order_relaxed);
    {
      std::lock_guard<std::mutex> hs_lock(hs_mutex);
      st.hs_last_ip = hs_last_ip;
      st.hs_last_port = hs_last_port;
      st.hs_last_cmd = hs_last_cmd;
      st.hs_last_line = hs_last_line;
    }
    // 发现口证据：能给"图谱里为什么看得见对方"一个直接答案。
    st.discovery_peers = static_cast<uint64_t>(DiscoveredPeers().size());
    st.mixed_sources = static_cast<size_t>(mixed_sources.load(std::memory_order_relaxed));
    st.mixed_frames = mixed_frames.load(std::memory_order_relaxed);
    st.mixed_audible_frames = mixed_audible_frames.load(std::memory_order_relaxed);
    {
      std::lock_guard<std::mutex> dlock(dump_mutex);
      st.render_dump_frames = dump_frames;
    }
    return st;
  }

  void SetSourceGain(uint16_t id, float gain) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = streams.find(id);
    if (it != streams.end()) it->second->gain = std::clamp(gain, 0.0f, 4.0f);
  }

  void SetSourceMuted(uint16_t id, bool muted) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = streams.find(id);
    if (it != streams.end()) it->second->muted = muted;
  }

  void SetMasterGain(float gain) { master_gain.store(std::clamp(gain, 0.0f, 4.0f)); }

  void SetLatency(int ms) {
    std::lock_guard<std::mutex> lock(mutex);
    latency_ms = std::clamp(ms, 20, 250);
    for (auto& kv : streams) {
      Stream& s = *kv.second;
      // 重新给了目标延迟，就把它当成新的基线：自适应缓冲从零开始，
      // 否则之前为抗抖动抬上去的目标会赖着不走（v1.7）。
      s.base_target_ms = latency_ms;
      s.jitter.SetTargetDelayMs(latency_ms);
      s.last_underrun_frames = s.jitter.stats().frames_underrun;
      s.high_windows = 0;
      s.low_windows = 0;
      s.excess_windows = 0;
    }
  }

  // ---- 3D 空间化 ----

  // 设备是否"已知"：有流正在从它来，或它还在图谱里（离线也算——图谱保留节点，
  // 切换汇聚端/重连后界面要沿用同一份布局）。
  bool KnownDeviceIpLocked(const std::string& ip) const {
    for (const auto& kv : streams) {
      if (kv.second->peer_ip_for_bye == ip) return true;
    }
    for (const auto& n : graph_.nodes) {
      if (NodeIpOf(n.id) == ip) return true;
    }
    return false;
  }

  void SetListenerPose(const ListenerPose& pose, bool valid) {
    std::lock_guard<std::mutex> lock(mutex);
    const auto clamp_f = [](float v, float lo, float hi) {
      return std::isfinite(v) ? std::clamp(v, lo, hi) : 0.0f;
    };
    ListenerPose p = pose;
    p.position.x = clamp_f(p.position.x, -kSpatialCoordLimit, kSpatialCoordLimit);
    p.position.y = clamp_f(p.position.y, -kSpatialCoordLimit, kSpatialCoordLimit);
    p.position.z = clamp_f(p.position.z, -kSpatialCoordLimit, kSpatialCoordLimit);
    p.yaw_deg = clamp_f(p.yaw_deg, -180.0f, 180.0f);
    p.pitch_deg = clamp_f(p.pitch_deg, -90.0f, 90.0f);
    listener_pose = p;
    listener_valid = valid;
  }

  bool SetSourceSpatial(const std::string& node_id, float x, float y, float z, bool enabled) {
    const std::string ip = NodeIpOf(node_id);
    if (ip.empty()) return false;
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) return false;
    if (std::fabs(x) > kSpatialCoordLimit || std::fabs(y) > kSpatialCoordLimit ||
        std::fabs(z) > kSpatialCoordLimit) {
      return false;
    }
    std::lock_guard<std::mutex> lock(mutex);
    // 旁路是安全操作（PC 环回声源可能不在图谱里，也要能关掉空间化）；
    // 真正的空间化位置只给已知设备存，未知 nodeId 返回 false 让界面别重试。
    if (enabled && !KnownDeviceIpLocked(ip)) return false;
    SpatialPlacement p;
    p.position = Vec3{x, y, z};
    p.enabled = enabled;
    spatial_placements[ip] = p;
    return true;
  }

  bool ClearSourceSpatial(const std::string& node_id) {
    const std::string ip = NodeIpOf(node_id);
    if (ip.empty()) return false;
    std::lock_guard<std::mutex> lock(mutex);
    return spatial_placements.erase(ip) > 0;
  }

  // ---- 发现 ----
  std::vector<Peer> DiscoveredPeers() const {
    return discovery ? discovery->Peers() : std::vector<Peer>{};
  }

  void RefreshDiscovery() {
    if (discovery) discovery->Probe();
  }

  // ---- 握手 ----
  std::vector<PendingConnection> PendingConnections() const {
    std::lock_guard<std::mutex> lock(mutex);
    std::vector<PendingConnection> out;
    out.reserve(pending.size());
    for (const auto& kv : pending) {
      PendingConnection c;
      c.stream_id = kv.first;
      c.peer_name = kv.second.peer_name;
      c.peer_ip = kv.second.peer_ip;
      c.peer_port = kv.second.peer_port;
      c.requested_at_ms = kv.second.requested_at_ms;
      out.push_back(c);
    }
    std::sort(out.begin(), out.end(), [](const PendingConnection& a, const PendingConnection& b) {
      return a.stream_id < b.stream_id;
    });
    return out;
  }

  void AcceptConnection(uint16_t stream_id) {
    std::string ack_ip;
    uint16_t ack_port = 0;
    uint16_t peer_audio = 47000;
    std::string peer_name;
    {
      std::lock_guard<std::mutex> lock(mutex);
      auto pit = pending.find(stream_id);
      if (pit == pending.end()) return;
      ack_ip = pit->second.peer_ip;
      ack_port = pit->second.peer_port;
      peer_audio = pit->second.peer_audio;
      peer_name = pit->second.peer_name;
      accepted_ids.insert(stream_id);
      auto sit = streams.find(stream_id);
      if (sit == streams.end()) {
        auto created = std::make_unique<Stream>(stream_id, latency_ms);
        created->confirmed = true;
        created->peer_name = pit->second.peer_name;
        created->peer_ip_for_bye = pit->second.peer_ip;
        created->peer_port_for_bye = pit->second.peer_port;
        created->last_packet = Clock::now();   // 理由同上
        sit = streams.emplace(stream_id, std::move(created)).first;
      } else {
        sit->second->confirmed = true;
        sit->second->peer_name = pit->second.peer_name;
        // 未确认期间缓冲里攒的是旧数据，sample_pos 早就落在 read_pos_ 前面，
        // 继续用只会全部判成迟到。清空重来，从确认后的第一个包开始播。
        sit->second->jitter.Reset();
        sit->second->asrc.Reset();
      }
      pending.erase(pit);
    }
    const std::string ack = "CONN-ACK|" + display_name + "|" + std::to_string(stream_id) + "\n";
    handshake_socket.SendToAddr(ack_ip, ack_port, ack.data(), ack.size());
    Emit("[+] 已同意连接 (流 " + std::to_string(stream_id) + ")");
    // 界面点"同意"同样是握手成功：对端入图谱 + 立刻互发一次当前图谱（协议 §5）
    GraphAddPeer(ack_ip, peer_name, peer_audio);
  }

  void RejectConnection(uint16_t stream_id) {
    std::string nak_ip;
    uint16_t nak_port = 0;
    {
      std::lock_guard<std::mutex> lock(mutex);
      auto pit = pending.find(stream_id);
      if (pit == pending.end()) return;
      nak_ip = pit->second.peer_ip;
      nak_port = pit->second.peer_port;
      pending.erase(pit);
    }
    const std::string nak = "CONN-NAK|用户拒绝|" + std::to_string(stream_id) + "\n";
    handshake_socket.SendToAddr(nak_ip, nak_port, nak.data(), nak.size());
    Emit("[-] 已拒绝连接 (流 " + std::to_string(stream_id) + ")");
  }

  void Disconnect(uint16_t stream_id) {
    std::string bye_ip;
    uint16_t bye_port = 0;
    {
      std::lock_guard<std::mutex> lock(mutex);
      auto sit = streams.find(stream_id);
      if (sit == streams.end()) return;
      bye_ip = sit->second->peer_ip_for_bye;
      bye_port = sit->second->peer_port_for_bye;
      streams.erase(sit);
      pending.erase(stream_id);
      accepted_ids.erase(stream_id);
    }
    if (!bye_ip.empty()) {
      const std::string bye = "CONN-BYE|" + std::to_string(stream_id) + "\n";
      handshake_socket.SendToAddr(bye_ip, bye_port, bye.data(), bye.size());
    }
    Emit("[-] 已主动断开 (流 " + std::to_string(stream_id) + ")");
  }

  void SetAutoAccept(bool on) { auto_accept_.store(on); }
  bool auto_accept() const { return auto_accept_.load(); }

  // 邀请：发 CONN-INVITE 给对端的握手口（音频口+2）。
  // 对方确认后会主动发 CONN-REQ，走正常握手；音频流向不变（手机→PC）。
  bool InvitePeer(const std::string& peer_ip, uint16_t peer_audio_port) {
    if (peer_ip.empty() || peer_audio_port == 0) return false;
    // 第 3 段必须是**单个** ip:port。手机端 InviteListener 用 lastIndexOf(':')
    // 切开就直接交给 InetAddress.getByName —— 拿到 "a, b" 这种逗号列表会立刻
    // 抛异常，respondToInvite 的 catch 直接 return false，用户看到
    // 「对方还没有同意连接」。
    // mDNS 信标带全列表是**故意的**（对端自己挑同网段那个最稳，见下方注释），
    // 但握手帧不是信标：它是一份"请连我这里"的地址，只能有一个。
    // 之前这里直接用 local_ip_for_invite，双网卡机器（WiFi + 网线）必踩。
    const std::string invite_ip = FirstIp(local_ip_for_invite);
    const std::string invite = "CONN-INVITE|" + display_name + "|" +
                               (invite_ip.empty() ? std::string("127.0.0.1") : invite_ip) + ":" +
                               std::to_string(port) + "|" +
                               std::to_string(invite_seq_++) + "\n";
    const uint16_t hs_port = static_cast<uint16_t>(peer_audio_port + kControlPortOffset);
    const bool ok = handshake_socket.SendToAddr(peer_ip, hs_port, invite.data(), invite.size());
    if (ok) {
      AllowPeer(peer_ip);
      {
        std::lock_guard<std::mutex> lock(mutex);
        RememberPeer(peer_ip, "", peer_audio_port);
      }
      Emit("[>] 已发出邀请: " + peer_ip + ":" + std::to_string(hs_port));
      // v0.18：不再"邀请即占位入图"。占位节点会被 /api/state 的 mDNS 证据
      // 判成 online=true（手机只要在广播信标就算"在线"），renderer 的
      // networkUp 立刻成立 → PC 还没连上就跳进控制台（用户实测：
      // 「邀请手机没反应，但进入控制台」）。对端真正的节点等它发
      // CONN-REQ 时再入图（自动同意分支里有 GraphAddPeerLocked）。
      // 放行改由 AllowPeer 的 invited 集合保证，与占位节点无关。
    }
    return ok;
  }

  // 待确认的邀请：ip -> 对端名
  std::map<std::string, std::string> invite_list;

  // 会话关系表：连过的设备（跨角色切换保留）
  struct KnownPeer {
    std::string name;
    uint16_t audio_port = 47000;
  };
  std::map<std::string, KnownPeer> session_peers;

  void RememberPeer(const std::string& ip, const std::string& name, uint16_t audio_port) {
    if (ip.empty()) return;
    auto it = session_peers.find(ip);
    if (it == session_peers.end()) {
      KnownPeer kp;
      kp.name = name.empty() ? ip : name;
      kp.audio_port = audio_port ? audio_port : 47000;
      session_peers.emplace(ip, kp);
    } else {
      if (!name.empty()) it->second.name = name;
      if (audio_port) it->second.audio_port = audio_port;
    }
  }

  // 把"已经彻底没动静"的会话设备摘掉（调用者持锁）。
  //
  // v1.7：手机 App 退出后既不发音频也不再广播 GRAPH，但 session_peers 以前是只进不出，
  // 界面就一直把它显示成在线，邀请过去当然没人接。这里按 TTL 清理：
  //   有活着的流 → 保留；最近 TTL 内收到过它的 GRAPH → 保留；否则删除。
  void SessionPeersTickLocked(Clock::time_point now) {
    for (auto it = session_peers.begin(); it != session_peers.end();) {
      const std::string& ip = it->first;
      bool live = false;
      for (const auto& kv : streams) {
        if (kv.second->peer_ip_for_bye == ip) {
          live = true;
          break;
        }
      }
      if (!live) {
        const auto seen = graph_last_seen.find(ip);
        if (seen != graph_last_seen.end() &&
            now - seen->second <= std::chrono::milliseconds(kSessionPeerTtlMs)) {
          live = true;
        }
      }
      if (live) {
        ++it;
      } else {
        it = session_peers.erase(it);
      }
    }
  }

  // 本机主动邀请/任命过的对端：它随后的 CONN-REQ 直接放行。
  //
  // 为什么需要：PC 把汇聚端切到手机后，手机会立刻发 CONN-REQ 开始推流。
  // 如果这一刻还要用户在 PC 上再点一次"同意"，就变成"两头都要点"，
  // 而且 PC 端此时往往已经切到音源模式，待确认列表还被界面忽略了。
  // 对端既然是被本机点名的，放行它的连接是合理的。
  // v0.18：从"单 IP + 60 秒过期"改成"集合 + 会话内不过期"。
  // 旧窗口的两个坑：① 用户在手机上读弹窗超过 60 秒才点同意，CONN-REQ 到时
  // 窗口已过期，只能掉进 pending 等界面确认——没人点就 30 秒超时，手机端
  // 报「对方还没有同意连接」；② 连续邀请两台设备时后者会覆盖前者。
  // 集合只在 ClearSession（全部断开）时清空。
  std::set<std::string> invited_peers;

  void AllowPeer(const std::string& ip) {
    if (!ip.empty()) invited_peers.insert(ip);
  }

  /**
   * 给一台设备下发角色指令（ROLE|hub 或 ROLE|source|ip:port）。
   *
   * 放在 Impl 里而不是 HubCore，是因为切换汇聚端发生在 SwitchGraphHub 的锁内
   * 流程中，那里只有 Impl 成员可用；实际发送必须在锁外（UdpSocket 不要持锁调用）。
   */
  bool SendRoleTo(const std::string& peer_ip, uint16_t peer_audio_port, bool make_hub,
                  const std::string& hub_ip, uint16_t hub_port) {
    if (peer_ip.empty() || peer_audio_port == 0) return false;
    std::string frame;
    if (make_hub) {
      frame = "ROLE|hub\n";
    } else {
      frame = "ROLE|source|" + hub_ip + ":" + std::to_string(hub_port) + "\n";
    }
    const uint16_t hs_port = static_cast<uint16_t>(peer_audio_port + kControlPortOffset);
    const bool ok = handshake_socket.SendToAddr(peer_ip, hs_port, frame.data(), frame.size());
    AllowPeer(peer_ip);
    {
      std::lock_guard<std::mutex> lock(mutex);
      RememberPeer(peer_ip, "", peer_audio_port);
    }
    Emit("[>] 角色指令 -> " + peer_ip + ":" + std::to_string(hs_port) + "  " + frame);
    // 被点名的设备先占位入图（协议 §4.1）；它随后的 CONN-REQ 就会走成员自动 ACK。
    GraphAddPeer(peer_ip, "", peer_audio_port);
    return ok;
  }

  bool PeerAllowedNow(const std::string& ip) {
    return !ip.empty() && invited_peers.count(ip) > 0;
  }
  std::string local_ip_for_invite;
  uint32_t invite_seq_ = 1;
};

HubCore::HubCore() : impl_(new Impl()) {}
HubCore::~HubCore() = default;

bool HubCore::Start(uint16_t port, int latency_ms, int render_latency_ms, const StartOptions& opts,
                    std::string* error) {
  return impl_->Start(port, latency_ms, render_latency_ms, opts, error);
}
void HubCore::Stop() { impl_->Stop(); }
bool HubCore::running() const { return impl_->running.load(); }
std::vector<SourceInfo> HubCore::Sources() const { return impl_->Sources(); }
HubStats HubCore::Stats() const { return impl_->Stats(); }
void HubCore::SetSourceGain(uint16_t id, float gain) { impl_->SetSourceGain(id, gain); }
void HubCore::SetSourceMuted(uint16_t id, bool muted) { impl_->SetSourceMuted(id, muted); }
void HubCore::SetMasterGain(float gain) { impl_->SetMasterGain(gain); }
float HubCore::master_gain() const { return impl_->master_gain.load(); }

bool HubCore::StartRenderDump(const std::string& path, std::string* error) {
  return impl_->StartDumpFile(path, error);
}

void HubCore::StopRenderDump() { impl_->CloseDumpFile(); }

bool HubCore::render_dump_active() const {
  return impl_->dump_file.load(std::memory_order_acquire) != nullptr;
}
void HubCore::SetLatency(int ms) { impl_->SetLatency(ms); }
int HubCore::latency() const { return impl_->latency_ms; }

void HubCore::SetListenerPose(const ListenerPose& pose, bool valid) {
  impl_->SetListenerPose(pose, valid);
}

bool HubCore::SetSourceSpatial(const std::string& node_id, float x, float y, float z,
                               bool enabled) {
  return impl_->SetSourceSpatial(node_id, x, y, z, enabled);
}

bool HubCore::ClearSourceSpatial(const std::string& node_id) {
  return impl_->ClearSourceSpatial(node_id);
}

std::vector<Peer> HubCore::DiscoveredPeers() const { return impl_->DiscoveredPeers(); }
void HubCore::RefreshDiscovery() { impl_->RefreshDiscovery(); }

std::vector<PendingConnection> HubCore::PendingConnections() const {
  return impl_->PendingConnections();
}
void HubCore::AcceptConnection(uint16_t stream_id) { impl_->AcceptConnection(stream_id); }
void HubCore::RejectConnection(uint16_t stream_id) { impl_->RejectConnection(stream_id); }
void HubCore::Disconnect(uint16_t stream_id) { impl_->Disconnect(stream_id); }
void HubCore::SetAutoAccept(bool on) { impl_->SetAutoAccept(on); }
bool HubCore::auto_accept() const { return impl_->auto_accept(); }
bool HubCore::SendRoleCommand(const std::string& peer_ip, uint16_t peer_audio_port,
                              bool make_hub, const std::string& hub_ip,
                              uint16_t hub_port) {
  // 单一实现放在 Impl::SendRoleTo：切换汇聚端时也要用它（见 SwitchGraphHub）。
  return impl_->SendRoleTo(peer_ip, peer_audio_port, make_hub, hub_ip, hub_port);
}

std::vector<Peer> HubCore::SessionPeers() const {
  std::vector<Peer> out;
  std::lock_guard<std::mutex> lock(impl_->mutex);
  for (const auto& kv : impl_->session_peers) {
    Peer p;
    p.ip = kv.first;
    p.name = kv.second.name;
    p.audio_port = kv.second.audio_port;
    out.push_back(p);
  }
  return out;
}

void HubCore::ClearSession() {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  // v0.4：全部断开 = 真的把这一轮会话清干净。以前只清 session_peers + 图谱，
  // streams / accepted_ids / pending 留着，下一次用相同 stream id 重连就会命中
  // "已同意过 → 只回 ACK 不建流"的分支：界面连上了、声音永远不通，
  // 只能重启桌面端（用户报的"两端都退出才能重连"就是这个）。
  // v0.18：被邀请过的对端也一并解除放行——「全部断开」之后不该再有免确认的 CONN-REQ。
  // "全部断开"同时重置图谱（协议 §4.6）：版本 +1、origin/hubId 回到自己、只留自节点。
  // 不广播：对端已经断开/不可达，广播只会在下次见面时造成一次无意义的合并。
  // v0.21：逻辑收敛到 ClearSessionLocked()，自动收摊路径共用同一份实现。
  impl_->ClearSessionLocked();
}

std::vector<Peer> HubCore::Invites() const {
  std::vector<Peer> out;
  std::lock_guard<std::mutex> lock(impl_->mutex);
  for (const auto& kv : impl_->invite_list) {
    Peer p;
    p.ip = kv.first;
    p.name = kv.second;
    p.audio_port = impl_->port;
    out.push_back(p);
  }
  return out;
}

void HubCore::AcceptInvite(const std::string& ip) {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->invite_list.erase(ip);
}

void HubCore::RejectInvite(const std::string& ip) {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->invite_list.erase(ip);
}

bool HubCore::InvitePeer(const Peer& peer) { return impl_->InvitePeer(peer.ip, peer.audio_port); }

// ---- 图谱（docs/GRAPH-PROTOCOL.md）----

GraphState HubCore::Graph() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->graph_;
}

std::string HubCore::graph_self_id() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->self_node_id;
}

bool HubCore::graph_is_hub() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  // v0.41：先按"任意本机地址 + 本机端口"认一次，再回落到严格相等。
  // 对端写回来的 hubId 可能是本机的另一张网卡（或多出口 NAT 后的地址），
  // 只比 self_node_id 会让界面把汇聚端标成一台陌生设备。
  if (impl_->IsSelfIdLocked(impl_->graph_.hub_id)) return true;
  return IsHub(impl_->graph_, impl_->self_node_id);
}

bool HubCore::SwitchGraphHub(const std::string& node_id) {
  return impl_->SwitchGraphHub(node_id);
}

void HubCore::SyncGraphNow() { impl_->SyncGraphNow(); }

int64_t HubCore::graph_peer_last_seen_ms(const std::string& ip) const {
  return impl_->GraphPeerLastSeenMs(ip);
}

// ---- 房间摆位（POS）----

PlacementState HubCore::Placements() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->placement_;
}

uint64_t HubCore::placement_version() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->placement_.version;
}

bool HubCore::SetNodePlacement(const std::string& node_id, float x_m, float z_m) {
  if (!std::isfinite(x_m) || !std::isfinite(z_m)) return false;
  const int x_cm = static_cast<int>(std::lround(static_cast<double>(x_m) * 100.0));
  const int z_cm = static_cast<int>(std::lround(static_cast<double>(z_m) * 100.0));
  bool changed = false;
  bool ok = false;
  {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    const uint64_t before = impl_->placement_.version;
    ok = impl_->SetNodePlacementLocked(node_id, x_cm, z_cm);
    changed = ok && impl_->placement_.version != before;
  }
  if (changed) {
    impl_->Emit("[GRAPH] 摆位更新: " + node_id + " -> (" + std::to_string(x_cm) + ", " +
                std::to_string(z_cm) + ") cm");
  }
  return ok;
}

bool HubCore::ClearNodePlacement(const std::string& node_id) {
  bool changed = false;
  bool ok = false;
  {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    const uint64_t before = impl_->placement_.version;
    ok = impl_->ClearNodePlacementLocked(node_id);
    changed = ok && impl_->placement_.version != before;
  }
  if (changed) impl_->Emit("[GRAPH] 摆位清除: " + node_id);
  return ok;
}

// 测试钩子优先：tools/graph-smoke.ps1 在同一台机器上起两个 hub，
// 自动枚举会给出同一个局域网 IP，两个进程的自节点会互相指错。
std::string HubCore::local_ip() const {
  const std::string env = EnvLocalIpOverride();
  if (!env.empty()) return env;
  return LocalIPv4List();
}
void HubCore::SetEventCallback(std::function<void(const std::string&)> cb) {
  std::lock_guard<std::mutex> lock(impl_->cb_mutex);
  impl_->event_cb = std::move(cb);
}

}  // namespace audiohub
