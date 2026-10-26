#include "audiohub/hub_core.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <thread>
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

using Clock = std::chrono::steady_clock;

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
    int64_t requested_at_ms = 0;
  };
  std::map<uint16_t, Pending> pending;
  // 已确认过的 stream_id。握手模式下发起方收到 ACK 才开始发音频，
  // 所以 Accept 时那个流往往还不存在——必须记住，等它的第一个包来时对号入座。
  std::set<uint16_t> accepted_ids;

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
      // 信标里带本机真实 IP：广播可能走虚拟网卡，来址不可信
      const std::string all = LocalIPv4List();
      local_ip_for_invite = all.substr(0, all.find(','));
    }
    // 两端能力完全一致：都能采音也都能放音。"发送/接收"只是指
    // 建立连接时谁发起，不是角色——汇聚端是用户在界面里选的。
    if (opts.advertise) {
      discovery->StartAdvertise(display_name, local_ip_for_invite, port,
                                http_port_for_discovery, true, true);
    } else {
      discovery->StartBrowse();
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
      const int n = data_socket.ReceiveFrom(buffer.data(), buffer.size(), nullptr, nullptr);
      if (n <= 0) {
        std::this_thread::sleep_for(std::chrono::microseconds(200));
        continue;
      }

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
      if (n <= 0) continue;
      buf[static_cast<size_t>(n)] = 0;

      std::string line(reinterpret_cast<char*>(buf.data()));
      while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();

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

        if (auto_accept_.load() || !require_handshake) {
          // 自动同意：直接放行
          std::lock_guard<std::mutex> lock(mutex);
          auto it = streams.find(stream_id);
          if (it != streams.end()) {
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
        } else {
          // 要人确认：进待确认列表，等界面点同意
          std::lock_guard<std::mutex> lock(mutex);
          Pending p;
          p.peer_name = peer_name;
          p.peer_ip = from_ip;
          p.peer_port = from_port;
          p.requested_at_ms = static_cast<int64_t>(
              std::chrono::duration_cast<std::chrono::milliseconds>(
                  Clock::now().time_since_epoch()).count());
          pending[stream_id] = p;
          Emit("[?] 连接请求: " + peer_name + " (流 " + std::to_string(stream_id) + ")，等待确认");
        }
      } else if (cmd == "CONN-ACK" && parts.size() >= 3) {
        // 我们是发起方，对方同意了
        const uint16_t stream_id = static_cast<uint16_t>(std::stoi(parts[2]));
        std::lock_guard<std::mutex> lock(mutex);
        auto it = streams.find(stream_id);
        if (it != streams.end()) it->second->confirmed = true;
        Emit("[+] 对方已同意: " + parts[1] + " (流 " + std::to_string(stream_id) + ")");
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
          if (idle > kSourceTimeoutSec) {
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
    {
      std::lock_guard<std::mutex> lock(mutex);
      auto pit = pending.find(stream_id);
      if (pit == pending.end()) return;
      ack_ip = pit->second.peer_ip;
      ack_port = pit->second.peer_port;
      accepted_ids.insert(stream_id);
      auto sit = streams.find(stream_id);
      if (sit != streams.end()) {
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
      Emit("[>] 已发出邀请: " + peer_ip + ":" + std::to_string(hs_port));
    }
    return ok;
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
bool HubCore::InvitePeer(const Peer& peer) { return impl_->InvitePeer(peer.ip, peer.audio_port); }

std::string HubCore::local_ip() const { return LocalIPv4List(); }
void HubCore::SetEventCallback(std::function<void(const std::string&)> cb) {
  std::lock_guard<std::mutex> lock(impl_->cb_mutex);
  impl_->event_cb = std::move(cb);
}

}  // namespace audiohub
