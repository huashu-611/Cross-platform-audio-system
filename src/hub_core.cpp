#include "audiohub/hub_core.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
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
// 超过这么久没有包的流判定为掉线，从列表里移除。
constexpr int kSourceTimeoutSec = 5;
// ASRC 内部保留的输入帧数。
constexpr int kAsrcReserveFrames = 8;
// 控制口 = 音频口 + 2（+1 是 PING/PONG 口）。握手和发现都走文本帧。
constexpr uint16_t kControlPortOffset = 2;

// ---- 图谱（docs/GRAPH-PROTOCOL.md）----
// 周期保活 3 秒；本地变化连发 2 次、间隔 60 ms；同一设备 1 秒最多收 5 次，
// 超出合并成延迟一次；同一来源的 GRAPH-REQ 200 ms 内只回一次。
constexpr int kGraphPeriodicMs = 3000;
constexpr int kGraphRepeatMs = 60;
constexpr int kGraphRequestReplyMs = 200;
constexpr int kGraphRateWindowMs = 1000;
constexpr int kGraphRateMaxSends = 5;

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
    jitter.SetTargetDelayMs(latency_ms);
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
  Clock::time_point last_catchup{};
  Clock::time_point last_packet{};
  float gain = 1.0f;
  bool muted = false;
  uint64_t packets = 0;
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

  // 事件回调必须用独立的锁：Emit() 会在"已经持有 streams mutex"的接收线程里
  // 被调用（新流接入、流掉线），复用同一把 std::mutex 就是死锁——
  // 而且症状极具迷惑性：banner 打完之后整个程序静默，没有任何报错。
  mutable std::mutex cb_mutex;
  std::function<void(const std::string&)> event_cb;

  std::atomic<uint64_t> received_packets{0};
  std::atomic<uint64_t> received_bytes{0};
  std::atomic<uint64_t> malformed{0};
  std::atomic<uint64_t> render_underruns{0};

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
  uint16_t graph_http_port = 0;     // 自节点要广播的 HTTP 口
  uint64_t graph_seen_version = 0;  // 见过的最大 version（本地改动取 max+1）
  std::map<std::string, Clock::time_point> graph_last_seen;   // ip -> 最近收到数据报
  std::map<std::string, Clock::time_point> graph_req_replied; // ip -> 上次回 GRAPH-REQ
  std::map<std::string, std::vector<Clock::time_point>> graph_sends;  // ip -> 发送时刻
  std::map<std::string, Clock::time_point> graph_deferred;    // ip -> 被限速合并后的补发
  std::vector<std::pair<std::string, uint16_t>> graph_probes; // 待发的 GRAPH-REQ
  int graph_pending_repeats = 0;         // 本地变化还差几次广播（连发 2 次）
  Clock::time_point graph_next_repeat{};    // 下一次补发时刻
  Clock::time_point graph_next_periodic{};  // 下一次周期广播

  std::thread receiver;
  std::thread control;
  std::thread handshake;
  std::thread mixer;

  SpscRing<float> output_ring{static_cast<size_t>(MsToFrames(100)) * kChannels};

  ~Impl() { Stop(); }

  void Emit(const std::string& text) {
    std::function<void(const std::string&)> cb;
    {
      std::lock_guard<std::mutex> lock(cb_mutex);
      cb = event_cb;
    }
    if (cb) cb(text);
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

    if (!data_socket.Open(port)) {
      if (error) *error = "绑定端口 " + std::to_string(port) + " 失败: " + data_socket.last_error();
      return false;
    }
    if (!ctrl_socket.Open(static_cast<uint16_t>(port + 1))) {
      if (error) *error = "绑定控制口 " + std::to_string(port + 1) + " 失败";
      return false;
    }
    ctrl_socket.SetReceiveTimeoutMs(30);

    if (!handshake_socket.Open(static_cast<uint16_t>(port + kControlPortOffset))) {
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
    render.Stop();
    running.store(false);
    if (discovery) discovery->Stop();
    std::lock_guard<std::mutex> lock(mutex);
    streams.clear();
    pending.clear();
  }

  // ------------------------------------------------------------ 接收线程

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
        continue;
      }
      const size_t payload_bytes = static_cast<size_t>(n) - kHeaderSize;
      if (payload_bytes == 0 ||
          payload_bytes % (kChannels * sizeof(int16_t)) != 0) {
        malformed++;
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
        // 三种情况视为已确认：没启用握手 / 之前握手通过过 / 之前 Accept 过
        created->confirmed =
            !require_handshake || accepted_ids.count(header.stream_id) > 0;
        if (accepted_ids.count(header.stream_id) > 0) {
          created->peer_name = "已确认设备";
        }
        it = streams.emplace(header.stream_id, std::move(created)).first;
        Emit("[+] 新流接入: id=" + std::to_string(header.stream_id));
      }
      it->second->jitter.Push(header.sample_pos, scratch.data(), frames);
      it->second->packets++;
      it->second->last_packet = Clock::now();

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
      if (n <= 0) continue;
      // 任何一条数据报都算"这个 IP 还活着"（协议 §4.2/§8 的活标志）
      TouchGraphIp(from_ip);

      std::string line(reinterpret_cast<const char*>(buf.data()), static_cast<size_t>(n));
      while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();

      // 图谱帧与握手命令共用 audioPort+2 这个口，必须先判断
      if (IsGraphRequestFrame(line)) {
        ReplyGraphRequest(from_ip, from_port);
        continue;
      }
      if (line.compare(0, 6, "GRAPH|") == 0) {
        HandleGraphFrame(from_ip, line);
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
        {
          std::lock_guard<std::mutex> lock(mutex);
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
        if (accepted_ids.count(stream_id) > 0) {
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
        bool graph_member = false;
        {
          std::lock_guard<std::mutex> lock(mutex);
          graph_member = FindNodeByIp(graph_, from_ip) != nullptr;
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
          streams.erase(it);
          Emit("[-] 已断开: " + who);
        }
        pending.erase(stream_id);
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
    graph_sends.clear();
    graph_deferred.clear();
    graph_probes.clear();
    graph_pending_repeats = 0;
    graph_next_periodic = Clock::now() + std::chrono::milliseconds(kGraphPeriodicMs);
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
    graph_sends.clear();
    graph_deferred.clear();
    graph_probes.clear();
    graph_pending_repeats = 0;
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
  int GraphBroadcastLocked() {
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
  void HandleGraphFrame(const std::string& from_ip, const std::string& line) {
    GraphState remote;
    if (!DecodeGraphFrame(line, &remote)) return;   // 非法帧整帧丢弃

    bool changed = false;
    size_t node_count = 0;
    std::string hub_id;
    {
      std::lock_guard<std::mutex> lock(mutex);
      TouchGraphIpLocked(from_ip);
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
    }
  }

  // 本地切换汇聚端（协议 §4.5）。节点不存在返回 false（HTTP 回 unknown node）。
  bool SwitchGraphHub(const std::string& node_id) {
    bool is_hub_now = false;
    {
      std::lock_guard<std::mutex> lock(mutex);
      if (FindNode(graph_, node_id) == nullptr) return false;
      // version = max(version, seen) + 1：SwitchHub 内部只做 +1，
      // 这里先把本地 version 抬到"见过的最大值"，避免比远端回退。
      if (graph_seen_version > graph_.version) graph_.version = graph_seen_version;
      if (!SwitchHub(&graph_, self_node_id, node_id)) return false;
      graph_seen_version = graph_.version;
      GraphScheduleDoubleLocked();   // 协议 §4.5：连发 2 次
      is_hub_now = IsHub(graph_, self_node_id);
    }
    Emit(std::string("[GRAPH] 汇聚端切换 -> ") + node_id +
         (is_hub_now ? "（本机是汇聚端）" : "（本机不是汇聚端，输出静音）"));
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

        // 掉线的流及时清掉，否则手机上按了停止，这边还留着一个死行
        const auto now = Clock::now();
        for (auto it = streams.begin(); it != streams.end();) {
          const auto idle =
              std::chrono::duration_cast<std::chrono::seconds>(now - it->second->last_packet)
                  .count();
          // 已确认但一包没来的流（刚同意、对方还在授权采集）给 60 秒宽限，
          // 否则节点会在界面上闪一下就没。真正在发流的仍然按短超时清理。
          const int limit = (it->second->packets == 0) ? 60 : kSourceTimeoutSec;
          if (idle > limit) {
            Emit("[-] 流掉线: id=" + std::to_string(it->first));
            it = streams.erase(it);
          } else {
            ++it;
          }
        }

        mix_inputs.clear();
        for (auto& kv : streams) {
          Stream& s = *kv.second;

          // ---- 外环：把水位锁到目标值 ----
          if (++s.tick_counter >= kAdjustEveryTicks) {
            s.tick_counter = 0;
            const int occupancy = s.jitter.occupancy_ms();
            const int target = s.jitter.target_delay_ms();
            const int excess = occupancy - target;
            const bool cooldown_ok =
                (Clock::now() - s.last_catchup) > std::chrono::seconds(5);

            if (excess > kCatchUpThresholdMs) {
              s.excess_windows++;
            } else {
              s.excess_windows = 0;
            }

            if (s.excess_windows >= kCatchUpWindows && cooldown_ok) {
              s.jitter.FastForward(MsToFrames(excess - 2));
              s.last_catchup = Clock::now();
              s.catchup_count++;
              s.excess_windows = 0;
              s.pi.Reset();
              s.asrc.SetRatio(1.0);
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
          in.gain = s.gain;
          // 未经确认的流不参与混音——"请求-确认"机制必须真的挡住声音，
          // 否则只是界面上演戏。
          in.active = !s.muted && s.confirmed;
          mix_inputs.push_back(in);
        }

        // 协议 §6.1：本机不是汇聚端时混音输出静音。
        // 上面的取水循环照常跑——不取水的话切回汇聚端的瞬间缓冲已被抽干，
        // 会有几百毫秒的杂音/欠载。
        if (!IsHub(graph_, self_node_id)) mix_inputs.clear();
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
    for (auto& kv : streams) kv.second->jitter.SetTargetDelayMs(latency_ms);
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
    const std::string invite = "CONN-INVITE|" + display_name + "|" + local_ip_for_invite + ":" +
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
      // 邀请即占位入图（协议 §4.1）：这样对端随后发 CONN-REQ 时
      // 已经算"图谱成员"，会走成员自动 ACK，不用用户再点一次。
      GraphAddPeer(peer_ip, "", peer_audio_port);
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

  // 本机主动邀请/任命过的对端：它随后的 CONN-REQ 直接放行。
  //
  // 为什么需要：PC 把汇聚端切到手机后，手机会立刻发 CONN-REQ 开始推流。
  // 如果这一刻还要用户在 PC 上再点一次"同意"，就变成"两头都要点"，
  // 而且 PC 端此时往往已经切到音源模式，待确认列表还被界面忽略了。
  // 对端既然是被本机点名的，放行它的连接是合理的。
  std::string auto_accept_ip;
  uint64_t auto_accept_until_ms = 0;

  void AllowPeer(const std::string& ip) {
    auto_accept_ip = ip;
    auto_accept_until_ms = NowMs() + 60'000;
  }
  bool PeerAllowedNow(const std::string& ip) {
    return !ip.empty() && ip == auto_accept_ip && NowMs() < auto_accept_until_ms;
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
void HubCore::SetLatency(int ms) { impl_->SetLatency(ms); }
int HubCore::latency() const { return impl_->latency_ms; }

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
  if (peer_ip.empty() || peer_audio_port == 0) return false;
  std::string frame;
  if (make_hub) {
    frame = "ROLE|hub\n";
  } else {
    frame = "ROLE|source|" + hub_ip + ":" + std::to_string(hub_port) + "\n";
  }
  const uint16_t hs_port = static_cast<uint16_t>(peer_audio_port + kControlPortOffset);
  const bool ok = impl_->handshake_socket.SendToAddr(peer_ip, hs_port, frame.data(), frame.size());
  impl_->AllowPeer(peer_ip);
  {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->RememberPeer(peer_ip, "", peer_audio_port);
  }
  impl_->Emit("[>] 角色指令 -> " + peer_ip + ":" + std::to_string(hs_port) + "  " + frame);
  // 被点名的设备先占位入图（协议 §4.1）；它随后的 CONN-REQ 就会走成员自动 ACK。
  impl_->GraphAddPeer(peer_ip, "", peer_audio_port);
  return ok;
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
  impl_->session_peers.clear();
  // "全部断开"同时重置图谱（协议 §4.6）：版本 +1、origin/hubId 回到自己、只留自节点。
  // 不广播：对端已经断开/不可达，广播只会在下次见面时造成一次无意义的合并。
  impl_->GraphResetLocked();
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
  return IsHub(impl_->graph_, impl_->self_node_id);
}

bool HubCore::SwitchGraphHub(const std::string& node_id) {
  return impl_->SwitchGraphHub(node_id);
}

void HubCore::SyncGraphNow() { impl_->SyncGraphNow(); }

int64_t HubCore::graph_peer_last_seen_ms(const std::string& ip) const {
  return impl_->GraphPeerLastSeenMs(ip);
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
