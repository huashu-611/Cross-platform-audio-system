// audiohub-hub：接收多路音频，混音后从本机扬声器/耳机播出。
//
// 线程模型（对应 docs §04 的四条线程）：
//   [接收线程]  UDP -> 解包 -> 每源 JitterBuffer
//   [混音线程]  每 5 ms：JitterBuffer -> ASRC -> 求和 -> 软限幅 -> 输出环
//   [渲染线程]  WASAPI 实时回调：只从输出环 memcpy
//   [主线程]    统计与控制口（响应 PING，用于对端测 RTT）
//
// 注意混音线程不是实时线程，它只需要在 5 ms 内完成，做不到也只是偶尔
// 少一个块，由输出环吸收；真正硬实时的只有渲染线程。

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
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

namespace {

using Clock = std::chrono::steady_clock;

// ASRC 内部保留的输入帧数。太小会因为插值保护帧不足而频繁 Pull 失败，
// 太大会变成额外的延迟。8 帧 = 0.17 ms，够用且几乎不可见。
constexpr int kAsrcReserveFrames = 8;

uint32_t NowMs(Clock::time_point t0) {
  return static_cast<uint32_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count());
}

inline float S16ToFloat(int16_t v) { return static_cast<float>(v) * (1.0f / 32768.0f); }

struct Options {
  uint16_t port = 47000;
  int latency_ms = 60;
  int block_frames = audiohub::kFramesPerPacket;
  int render_latency_ms = 10;
  std::string log_path;  // 非空时把输出重定向到文件
};

void PrintUsage() {
  std::printf(
      "用法: audiohub-hub [选项]\n"
      "\n"
      "  --port <n>        监听端口，默认 47000（控制口自动用 port+1）\n"
      "  --latency <ms>    目标端到端延迟，默认 60。可选 40/60/120\n"
      "  --render-ms <n>   WASAPI 输出缓冲毫秒数，默认 10\n"
      "  --block <frames>  混音块大小（帧），默认 %d（5 ms）\n"
      "  --log <file>      把输出写到文件（后台运行时看统计用）\n",
      audiohub::kFramesPerPacket);
}

bool ParseArgs(int argc, char** argv, Options* opt) {
  for (int i = 1; i < argc; ++i) {
    const std::string a = argv[i];
    auto next = [&](const char* name) -> std::string {
      if (i + 1 >= argc) {
        std::printf("参数 %s 缺少取值\n", name);
        return {};
      }
      return argv[++i];
    };
    if (a == "--port") {
      opt->port = static_cast<uint16_t>(std::stoi(next("--port")));
    } else if (a == "--latency") {
      opt->latency_ms = std::stoi(next("--latency"));
    } else if (a == "--render-ms") {
      opt->render_latency_ms = std::stoi(next("--render-ms"));
    } else if (a == "--block") {
      opt->block_frames = std::stoi(next("--block"));
    } else if (a == "--log") {
      opt->log_path = next("--log");
    } else if (a == "-h" || a == "--help") {
      PrintUsage();
      return false;
    } else {
      std::printf("未知参数: %s\n", a.c_str());
      PrintUsage();
      return false;
    }
  }
  return true;
}

// 外环（PI）每 40 个 tick 调一次 = 200 ms。
// 调得比这更勤会让比率抖动传成音高抖动；更慢则跟不上缓慢的时钟漂移。
constexpr int kAdjustEveryTicks = 40;

// 水位超过目标这么多就认为"启动突发还没消化完"，直接追帧而不是等 PI 慢慢拉。
//
// 为什么不能只靠 PI：比率被限制在 ±0.2%（再大就有音高漂移的风险），
// 折算下来每秒只能追回 96 个采样。追 70 ms 要 35 秒——这半分钟里
// 用户听到的延迟是目标值的两倍。
constexpr int kCatchUpThresholdMs = 40;

// 连续这么多个调整窗口都超标才动手，避免一次网络突发就触发跳帧。
constexpr int kCatchUpWindows = 3;

// 一路输入源的全部状态。
struct Stream {
  explicit Stream(uint16_t stream_id, int latency_ms)
      : id(stream_id),
        jitter(audiohub::kChannels, audiohub::kSampleRate,
               audiohub::MsToFrames(400)),  // 400 ms 容量，够吸收一次 GC/调度尖峰
        asrc(audiohub::kChannels),
        // kp/ki 的量纲：输入是毫秒误差，输出是重采样比率偏移（±0.002 = ±0.2%）。
        //
        // 增益不能再大：离散系统的单步增益 = kp × 48000 × T。
        // T = 0.2 s、kp = 8e-5 时单步增益 0.77，已经接近临界，
        // 实测会变成 50↔74 ms 的极限环（延迟一直在呼吸，比固定偏移更难听）。
        // 取 2e-5 后单步增益 0.19，稳定。
        //
        // 代价是 PI 追不动大偏差（0.2% 上限决定了最快也只能 96 采样/秒）。
        // 所以大偏差交给上面的追帧一步到位，PI 只负责几百 ppm 的缓慢漂移。
        pi(0.00002, 0.000002, -0.002, 0.002) {
    jitter.SetTargetDelayMs(latency_ms);
  }

  uint16_t id;
  audiohub::JitterBuffer jitter;
  audiohub::Asrc asrc;
  audiohub::PiController pi;
  audiohub::Meter meter;
  std::vector<float> pull_buffer;
  std::vector<float> block_buffer;
  int tick_counter = 0;
  int excess_windows = 0;
  int catchup_count = 0;
  Clock::time_point last_catchup{};
  uint64_t packets = 0;
  uint64_t bytes = 0;
};

}  // namespace

int main(int argc, char** argv) {
  Options opt;
  if (!ParseArgs(argc, argv, &opt)) return 1;

  // --log：把输出写进文件。后台运行（无控制台）时只能靠这个看统计。
  if (!opt.log_path.empty()) {
    if (std::freopen(opt.log_path.c_str(), "w", stdout) == nullptr) {
      std::printf("无法打开日志文件: %s\n", opt.log_path.c_str());
      return 1;
    }
  }

  // 行缓冲：这两支程序经常被重定向到日志文件，默认的块缓冲会在进程被强杀时
  // 丢掉最后几 KB 输出——排查现场正是最需要那段输出的时候。
  // freopen 之后必须重新设置，否则又回到块缓冲。
  std::setvbuf(stdout, nullptr, _IOLBF, 0);

  const auto t0 = Clock::now();

  audiohub::UdpSocket data_socket;
  if (!data_socket.Open(opt.port)) {
    std::printf("绑定端口 %u 失败: %s\n", opt.port, data_socket.last_error().c_str());
    return 2;
  }
  audiohub::UdpSocket ctrl_socket;
  if (!ctrl_socket.Open(static_cast<uint16_t>(opt.port + 1))) {
    std::printf("绑定控制口 %u 失败: %s\n", opt.port + 1, ctrl_socket.last_error().c_str());
    return 2;
  }
  ctrl_socket.SetNonBlocking(true);

  std::mutex streams_mutex;
  std::map<uint16_t, std::unique_ptr<Stream>> streams;

  const int block = std::max(64, opt.block_frames);
  const size_t block_samples = static_cast<size_t>(block) * audiohub::kChannels;

  // 输出环：容纳 100 ms，交给渲染线程按设备周期取。
  audiohub::SpscRing<float> output_ring(
      static_cast<size_t>(audiohub::MsToFrames(100)) * audiohub::kChannels);

  std::atomic<bool> running{true};
  std::atomic<uint64_t> received_packets{0};
  std::atomic<uint64_t> received_bytes{0};
  std::atomic<uint64_t> malformed{0};

  // ---------------- 渲染 ----------------
  std::atomic<uint64_t> render_underruns{0};
  audiohub::WasapiRender render;
  const bool render_ok = render.Start(
      [&](float* out, int frames) {
        const size_t need = static_cast<size_t>(frames) * audiohub::kChannels;
        const size_t got = output_ring.Read(out, need);
        if (got < need) {
          std::memset(out + got, 0, (need - got) * sizeof(float));
          render_underruns++;
        }
      },
      opt.render_latency_ms);

  if (!render_ok) {
    std::printf("启动输出设备失败: %s\n", render.last_error().c_str());
    return 3;
  }

  std::printf("\n");
  std::printf("  audiohub-hub 已启动\n");
  std::printf("  监听       : 0.0.0.0:%u（控制口 %u）\n", opt.port, opt.port + 1);
  std::printf("  目标延迟   : %d ms\n", opt.latency_ms);
  std::printf("  输出设备   : 默认设备，缓冲 %d ms\n", opt.render_latency_ms);
  std::printf("  本机地址   : %s\n", audiohub::LocalIPv4List().c_str());
  std::printf("  Ctrl+C 停止\n\n");

  // ---------------- 接收线程 ----------------
  std::thread receiver([&] {
    std::vector<uint8_t> buffer(2048);
    std::vector<float> scratch;
    while (running.load()) {
      const int n = data_socket.ReceiveFrom(buffer.data(), buffer.size(), nullptr, nullptr);
      if (n <= 0) {
        std::this_thread::sleep_for(std::chrono::microseconds(200));
        continue;
      }

      audiohub::PacketHeader header;
      if (!audiohub::DecodeHeader(buffer.data(), static_cast<size_t>(n), &header)) {
        malformed++;
        continue;
      }
      const size_t payload_bytes = static_cast<size_t>(n) - audiohub::kHeaderSize;
      if (payload_bytes == 0 || payload_bytes % (audiohub::kChannels * sizeof(int16_t)) != 0) {
        malformed++;
        continue;
      }
      const int frames =
          static_cast<int>(payload_bytes / (audiohub::kChannels * sizeof(int16_t)));
      const size_t samples = static_cast<size_t>(frames) * audiohub::kChannels;

      if (scratch.size() < samples) scratch.resize(samples);
      const int16_t* src = reinterpret_cast<const int16_t*>(buffer.data() + audiohub::kHeaderSize);
      for (size_t i = 0; i < samples; ++i) scratch[i] = S16ToFloat(src[i]);

      std::lock_guard<std::mutex> lock(streams_mutex);
      auto it = streams.find(header.stream_id);
      if (it == streams.end()) {
        auto created = std::make_unique<Stream>(header.stream_id, opt.latency_ms);
        it = streams.emplace(header.stream_id, std::move(created)).first;
        std::printf("[+] 新流接入: id=%u\n", header.stream_id);
      }
      it->second->jitter.Push(header.sample_pos, scratch.data(), frames);
      it->second->packets++;
      it->second->bytes += static_cast<uint64_t>(n);

      received_packets++;
      received_bytes += static_cast<uint64_t>(n);

      // PING 必须在这里应答，不能放到每秒一次的统计循环里——
      // 那样 RTT 会被量化到 1 秒，时钟同步直接废掉。
      // 接收线程每 5 ms 转一圈，应答延迟也就是几毫秒。
      uint8_t ctrl[64];
      for (int i = 0; i < 4; ++i) {
        std::string from_ip;
        uint16_t from_port = 0;
        const int cn = ctrl_socket.ReceiveFrom(ctrl, sizeof(ctrl), &from_ip, &from_port);
        if (cn < 5 || ctrl[0] != 1 || from_port == 0) break;

        uint8_t pong[13];
        pong[0] = 2;
        std::memcpy(pong + 1, ctrl + 1, 4);  // t1（回显给源端）
        const uint32_t t2 = NowMs(t0);
        std::memcpy(pong + 5, &t2, 4);       // t2：Hub 收到时刻
        const uint32_t t3 = NowMs(t0);
        std::memcpy(pong + 9, &t3, 4);       // t3：Hub 回复时刻
        ctrl_socket.SendToAddr(from_ip, from_port, pong, sizeof(pong));
      }
    }
  });

  // ---------------- 混音线程 ----------------
  std::vector<float> mix_buffer(block_samples);
  std::vector<audiohub::MixInput> mix_inputs;
  audiohub::Meter bus_meter;

  std::thread mixer([&] {
    auto next = Clock::now();
    const auto tick = std::chrono::microseconds(block * 1000000 / audiohub::kSampleRate);

    while (running.load()) {
      next += tick;

      {
        std::lock_guard<std::mutex> lock(streams_mutex);
        mix_inputs.clear();
        for (auto& kv : streams) {
          Stream& s = *kv.second;

          // ---- 外环：把水位锁到目标值 ----
          // 内环（抖动缓冲）只保证"不饿死"，它管不了"水位漂到哪"。
          // 没有这一段，实际延迟就等于启动瞬间的突发量，而不是 --latency。
          if (++s.tick_counter >= kAdjustEveryTicks) {
            s.tick_counter = 0;
            const int occupancy = s.jitter.occupancy_ms();
            const int target = s.jitter.target_delay_ms();
            const int excess = occupancy - target;
            const bool cooldown_ok =
                (Clock::now() - s.last_catchup) > std::chrono::seconds(5);

            // 单次突发不该触发跳帧，所以要求"连续多个窗口都超标"
            if (excess > kCatchUpThresholdMs) {
              s.excess_windows++;
            } else {
              s.excess_windows = 0;
            }

            if (s.excess_windows >= kCatchUpWindows && cooldown_ok) {
              // 启动突发留下的债：直接丢掉多出来的部分，一步到位。
              s.jitter.FastForward(audiohub::MsToFrames(excess - 2));
              s.last_catchup = Clock::now();
              s.catchup_count++;
              s.excess_windows = 0;
              s.pi.Reset();
              s.asrc.SetRatio(1.0);
            } else {
              // 正常情况：几百 ppm 的缓慢漂移，交给 PI 平推。
              // 误差为正（水位偏高）时输出正比率，读得更快，水位回落。
              const double ratio = 1.0 + s.pi.Update(static_cast<double>(excess), 0.2);
              s.asrc.SetRatio(ratio);
            }
          }

          // ASRC 需要多少输入，取决于它的输出/输入比率。
          // 用"目标储备 - 现有储备"来补水，而不是每帧都灌固定量，
          // 否则储备会随时间无限增长（相当于延迟慢慢变大）。
          const int want =
              static_cast<int>(std::ceil(block * s.asrc.ratio())) + kAsrcReserveFrames;
          const int have = s.asrc.AvailableInputFrames();
          const int need = std::clamp(want - have, 0, block * 4);

          if (need > 0) {
            const size_t need_samples = static_cast<size_t>(need) * audiohub::kChannels;
            if (s.pull_buffer.size() < need_samples) s.pull_buffer.resize(need_samples);
            s.jitter.Pull(s.pull_buffer.data(), need);
            s.asrc.Push(s.pull_buffer.data(), need);
          }

          if (s.block_buffer.size() < block_samples) s.block_buffer.resize(block_samples);
          if (!s.asrc.Pull(s.block_buffer.data(), block)) {
            // ASRC 输出不足（上游断流）：补零，让混音继续，
            // 不要在这里卡住——卡住会把其他源一起拖死。
            std::memset(s.block_buffer.data(), 0, block_samples * sizeof(float));
          }

          s.meter.Add(s.block_buffer.data(), block_samples);

          audiohub::MixInput in;
          in.data = s.block_buffer.data();
          in.gain = 1.0f;
          in.active = true;
          mix_inputs.push_back(in);
        }
      }

      audiohub::MixBlock(mix_buffer.data(), mix_inputs.data(),
                         static_cast<int>(mix_inputs.size()), block, audiohub::kChannels);
      // 多源直接相加会过 1.0，必须限幅，否则削顶的爆音比延迟更难听。
      audiohub::SoftLimit(mix_buffer.data(), static_cast<int>(block_samples), 0.85f);
      bus_meter.Add(mix_buffer.data(), block_samples);
      output_ring.Write(mix_buffer.data(), block_samples);

      std::this_thread::sleep_until(next);
      // 如果线程被挂起太久，重置时基，避免"补课"式地连跑几十个 tick。
      if (Clock::now() - next > std::chrono::milliseconds(50)) next = Clock::now();
    }
  });

  // ---------------- 统计 ----------------
  uint64_t last_packets = 0;
  const auto started_at = Clock::now();

  while (running.load()) {
    std::this_thread::sleep_for(std::chrono::seconds(1));

    if (!render.running()) {
      std::printf("输出设备已停止: %s\n", render.last_error().c_str());
      running.store(false);
      break;
    }

    const uint64_t p = received_packets.load();
    const auto elapsed_s = std::chrono::duration_cast<std::chrono::seconds>(Clock::now() - started_at).count();
    size_t stream_count = 0;
    {
      std::lock_guard<std::mutex> lock(streams_mutex);
      stream_count = streams.size();
    }

    double bus_level = -120.0;
    const bool has_bus = bus_meter.Take(&bus_level);
    std::printf("\n[%lld s] 收 %llu pkt/s (%.0f kbps) | 混音总线 %6.1f dBFS | 渲染欠载 %llu | 坏包 %llu | 源 %zu 路\n",
                static_cast<long long>(elapsed_s),
                static_cast<unsigned long long>(p - last_packets),
                (p - last_packets) * (960.0 + 16) * 8 / 1000.0,
                has_bus ? bus_level : -120.0,
                static_cast<unsigned long long>(render_underruns.load()),
                static_cast<unsigned long long>(malformed.load()),
                stream_count);

    {
      std::lock_guard<std::mutex> lock(streams_mutex);
      for (auto& kv : streams) {
        Stream& s = *kv.second;
        const auto st = s.jitter.stats();
        double level = -120.0;
        const bool has_level = s.meter.Take(&level);
        std::printf(
            "   stream %-3u 水位 %4d ms (目标 %d) | 比率 %.5f | 电平 %6.1f dBFS "
            "| 迟到 %llu 溢出 %llu | 欠载 %llu 追帧 %d\n",
            s.id, s.jitter.occupancy_ms(), s.jitter.target_delay_ms(), s.asrc.ratio(),
            has_level ? level : -120.0, static_cast<unsigned long long>(st.packets_late),
            static_cast<unsigned long long>(st.packets_overflow),
            static_cast<unsigned long long>(st.frames_underrun), s.catchup_count);
      }
    }
    last_packets = p;
    // 显式 flush：freopen 之后 setvbuf 未必生效，不 flush 的话
    // --log 的日志要攒满 4 KB 才落盘，后台跑的时候看着像"没输出"。
    std::fflush(stdout);
  }

  running.store(false);
  render.Stop();
  if (receiver.joinable()) receiver.join();
  if (mixer.joinable()) mixer.join();
  return 0;
}
