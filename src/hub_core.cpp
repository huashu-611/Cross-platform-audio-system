#include "audiohub/hub_core.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>
#include <thread>

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
};

}  // namespace

struct HubCore::Impl {
  uint16_t port = 47000;
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
  WasapiRender render;

  std::thread receiver;
  std::thread control;
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

  bool Start(uint16_t p, int latency, int render_ms, std::string* error) {
    if (running.load()) return false;
    port = p;
    latency_ms = latency;
    render_latency_ms = render_ms;
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

    running.store(true);
    receiver = std::thread(&Impl::ReceiverLoop, this);
    control = std::thread(&Impl::ControlLoop, this);
    mixer = std::thread(&Impl::MixerLoop, this);
    return true;
  }

  void Stop() {
    if (!running.load() && !receiver.joinable() && !mixer.joinable()) return;
    stop_requested.store(true);
    if (receiver.joinable()) receiver.join();
    if (control.joinable()) control.join();
    if (mixer.joinable()) mixer.join();
    render.Stop();
    running.store(false);
    std::lock_guard<std::mutex> lock(mutex);
    streams.clear();
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
          in.active = !s.muted;
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
};

HubCore::HubCore() : impl_(new Impl()) {}
HubCore::~HubCore() = default;

bool HubCore::Start(uint16_t port, int latency_ms, int render_latency_ms, std::string* error) {
  return impl_->Start(port, latency_ms, render_latency_ms, error);
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
std::string HubCore::local_ip() const { return LocalIPv4List(); }
void HubCore::SetEventCallback(std::function<void(const std::string&)> cb) {
  std::lock_guard<std::mutex> lock(impl_->cb_mutex);
  impl_->event_cb = std::move(cb);
}

}  // namespace audiohub
