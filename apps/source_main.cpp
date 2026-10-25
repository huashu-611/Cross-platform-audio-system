// audiohub-source：把本机正在播放的声音（系统混音）发到汇聚端。
//
// 线程模型：
//   [WASAPI 采集线程] --float->int16--> [SPSC 环] --> [发送线程] --UDP--> Hub
//
// 为什么采集线程只做转换和入环、发送交给另一个线程：
// send() 在某些情况下会阻塞（ARP 未解析、路由变化、网卡忙），
// 一旦阻塞在采集回调里，WASAPI 的缓冲立刻溢出，声音就断了。
// 这也是 docs 里"设备回调里只做 memcpy"那条规矩的延伸。

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "audiohub/audio_format.hpp"
#include "audiohub/clock_sync.hpp"
#include "audiohub/meter.hpp"
#include "audiohub/packet.hpp"
#include "audiohub/spsc_ring.hpp"
#include "audiohub/udp_socket.hpp"
#include "wasapi_audio.hpp"

namespace {

using Clock = std::chrono::steady_clock;

uint32_t NowMs(Clock::time_point t0) {
  return static_cast<uint32_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count());
}

inline int16_t FloatToS16(float v) {
  v = std::clamp(v, -1.0f, 1.0f);
  return static_cast<int16_t>(std::lrintf(v * 32767.0f));
}

struct Options {
  std::string hub_ip = "127.0.0.1";
  uint16_t hub_port = 47000;
  int capture_buffer_ms = 20;
  int stream_id = 1;
  int tone_hz = 0;        // >0 时用内置正弦代替采集
  int jitter_ms = 0;      // 模拟网络抖动（零均值）
  std::string include_name;  // 进程回环：只抓这个进程树
  std::string exclude_name;  // 进程回环：排除这个进程树
  std::string log_path;   // 非空时把输出重定向到文件
  bool verbose = false;
};

void PrintUsage() {
  std::printf(
      "用法: audiohub-source --hub <ip>[:port] [选项]\n"
      "\n"
      "  --hub <ip[:port]>   汇聚端地址，默认端口 %u（控制口自动用 port+1）\n"
      "  --stream-id <n>     流编号，一台设备可跑多路，默认 1\n"
      "  --cap-ms <n>        采集缓冲毫秒数，默认 20。越小延迟越低、越容易断\n"
      "  --tone <hz>         不发采集，改发内置正弦波。用于单机测多源混音\n"
      "  --jitter <ms>       给发送时刻加零均值随机抖动，模拟 WiFi\n"
      "  --include <exe>     只抓这个进程的声音（Win10 2004+）\n"
      "  --exclude <exe>     排除这个进程的声音，其它照抓（Win10 2004+）\n"
      "  --log <file>        把输出写到文件（后台运行时看统计用）\n"
      "  --verbose           每包打印一次（调试用，会明显影响性能）\n",
      static_cast<unsigned>(47000));
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
    if (a == "--hub") {
      const std::string v = next("--hub");
      if (v.empty()) return false;
      const size_t colon = v.find(':');
      if (colon == std::string::npos) {
        opt->hub_ip = v;
      } else {
        opt->hub_ip = v.substr(0, colon);
        opt->hub_port = static_cast<uint16_t>(std::stoi(v.substr(colon + 1)));
      }
    } else if (a == "--stream-id") {
      opt->stream_id = std::stoi(next("--stream-id"));
    } else if (a == "--cap-ms") {
      opt->capture_buffer_ms = std::stoi(next("--cap-ms"));
    } else if (a == "--tone") {
      opt->tone_hz = std::stoi(next("--tone"));
    } else if (a == "--jitter") {
      opt->jitter_ms = std::stoi(next("--jitter"));
    } else if (a == "--include") {
      opt->include_name = next("--include");
    } else if (a == "--exclude") {
      opt->exclude_name = next("--exclude");
    } else if (a == "--log") {
      opt->log_path = next("--log");
    } else if (a == "--verbose") {
      opt->verbose = true;
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
  if (!data_socket.Open(0) || !data_socket.SetPeer(opt.hub_ip, opt.hub_port)) {
    std::printf("无法连接汇聚端 %s:%u -- %s\n", opt.hub_ip.c_str(), opt.hub_port,
                data_socket.last_error().c_str());
    return 2;
  }

  audiohub::UdpSocket ctrl_socket;
  if (!ctrl_socket.Open(0) ||
      !ctrl_socket.SetPeer(opt.hub_ip, static_cast<uint16_t>(opt.hub_port + 1))) {
    std::printf("无法连接控制口 %s:%u -- %s\n", opt.hub_ip.c_str(), opt.hub_port + 1,
                ctrl_socket.last_error().c_str());
    return 2;
  }
  // 控制口用"阻塞读 + 30 ms 超时"，而不是非阻塞轮询：
  // 轮询的粒度受系统定时器精度限制，会把 RTT 抬到十几毫秒。
  ctrl_socket.SetReceiveTimeoutMs(30);

  // 1 秒的环足够吸收发送线程被调度走的抖动。
  audiohub::SpscRing<int16_t> ring(static_cast<size_t>(audiohub::kSampleRate) *
                                   audiohub::kChannels);
  std::atomic<bool> running{true};
  std::atomic<uint64_t> captured_frames{0};
  std::atomic<uint64_t> sent_packets{0};
  std::atomic<uint64_t> dropped_samples{0};

  std::vector<int16_t> scratch;
  // 采集侧电平：链路出问题时第一个要看的东西——
  // 到底"没抓到声音"还是"抓到但没送到"。
  audiohub::Meter capture_meter;

  // --tone 模式完全不碰声卡：没有音频设备也能跑，而且信号可重复。
  // 这是验证"多源混音是否正确"最直接的手段——两个不同频率的正弦叠加后，
  // 总功率应当恰好比单路高 3 dB（不相关信号功率相加）。
  audiohub::WasapiCapture capture;
  const bool tone_mode = opt.tone_hz > 0;

  if (!tone_mode) {
    const audiohub::CaptureCallback on_capture = [&](const float* data, int frames) {
      const size_t samples = static_cast<size_t>(frames) * audiohub::kChannels;
      if (scratch.size() < samples) scratch.resize(samples);
      capture_meter.Add(data, samples);
      for (size_t i = 0; i < samples; ++i) scratch[i] = FloatToS16(data[i]);
      const size_t written = ring.Write(scratch.data(), samples);
      if (written < samples) dropped_samples += (samples - written);
      captured_frames += static_cast<uint64_t>(frames);
    };

    // 进程回环：单机上跑 source+hub 时，整机回环会抓到 hub 自己的输出，
    // 形成"采集→播放→再采集"的反馈回路。--exclude 掉 hub 就能解开。
    uint32_t target_pid = 0;
    bool exclude_target = true;
    std::string target_name;
    if (!opt.exclude_name.empty() || !opt.include_name.empty()) {
      exclude_target = !opt.exclude_name.empty();
      target_name = exclude_target ? opt.exclude_name : opt.include_name;
      // 等一下目标进程出现（最多 5 秒）。
      // 否则用户先起 source 再起 hub 就会直接报错，而这两个程序的启动顺序
      // 本来是没有意义的——报错只应该发生在"确实没有这个进程"的时候。
      for (int i = 0; i < 25 && target_pid == 0; ++i) {
        target_pid = audiohub::FindProcessIdByName(target_name);
        if (target_pid == 0) std::this_thread::sleep_for(std::chrono::milliseconds(200));
      }
      if (target_pid == 0) {
        std::printf("找不到进程 %s —— 它必须正在运行，且名字要带 .exe\n", target_name.c_str());
        return 4;
      }
      std::printf("进程回环   : %s %s (pid=%u)\n", exclude_target ? "排除" : "只抓",
                  target_name.c_str(), target_pid);
    }

    const bool ok = target_pid != 0
                        ? capture.StartProcessLoopback(on_capture, target_pid, exclude_target,
                                                       opt.capture_buffer_ms)
                        : capture.Start(on_capture, opt.capture_buffer_ms);

    if (!ok) {
      std::printf("启动采集失败: %s\n", capture.last_error().c_str());
      return 3;
    }
  }

  std::printf("\n");
  std::printf("  audiohub-source 已启动\n");
  std::printf("  目标       : %s:%u\n", opt.hub_ip.c_str(), opt.hub_port);
  std::printf("  流编号     : %d\n", opt.stream_id);
  if (tone_mode) {
    std::printf("  信号源     : 内置正弦 %d Hz\n", opt.tone_hz);
  } else {
    std::printf("  采集缓冲   : %d ms\n", opt.capture_buffer_ms);
  }
  if (opt.jitter_ms > 0) std::printf("  抖动注入   : ±%d ms\n", opt.jitter_ms);
  std::printf("  格式       : %d Hz / %d ch / int16 on wire\n", audiohub::kSampleRate,
              audiohub::kChannels);
  std::printf("  本机地址   : %s\n", audiohub::LocalIPv4List().c_str());
  std::printf("  Ctrl+C 停止\n\n");

  // ---- 发送线程 ----
  const size_t packet_samples =
      static_cast<size_t>(audiohub::kFramesPerPacket) * audiohub::kChannels;
  std::vector<int16_t> payload(packet_samples);
  std::vector<uint8_t> datagram(audiohub::kHeaderSize + audiohub::kMaxPayloadBytes);

  audiohub::PacketHeader header;
  header.stream_id = static_cast<uint16_t>(opt.stream_id);

  std::thread sender([&] {
    uint32_t sample_pos = 0;
    uint32_t send_errors = 0;
    constexpr double kTwoPi = 6.283185307179586;
    const double phase_step =
        tone_mode ? kTwoPi * opt.tone_hz / audiohub::kSampleRate : 0.0;
    double phase = 0.0;

    // 按 5 ms 节拍发，而不是"有多少发多少"。
    //
    // WASAPI 的回调是每 ~20 ms 交一批数据，如果照单全收，发送线程会在几微秒内
    // 连发 4 个包然后干等 20 ms。网络上看平均码率是对的，但对端的抖动缓冲
    // 会看到 20 ms 一簇的到达图案，水位随之上下摆动十几毫秒。
    // 均匀发送能把这段抖动直接从链路上消掉。
    std::mt19937 rng{std::random_device{}()};
    auto next = Clock::now();
    const auto tick = std::chrono::microseconds(audiohub::kFramesPerPacket * 1000000 /
                                                audiohub::kSampleRate);

    while (running.load()) {
      next += tick;

      // 关键：即使这一拍没数据，sample_pos 也要推进。
      // 否则源端时间轴会落后于真实时间，对端看到的"采样率"低于 48 kHz，
      // 水位会被一路抽干。跳过的这一拍在对端就是一次真实的丢包。
      bool have_data = true;
      if (tone_mode) {
        for (int i = 0; i < audiohub::kFramesPerPacket; ++i) {
          const int16_t v = FloatToS16(static_cast<float>(0.5 * std::sin(phase)));
          phase += phase_step;
          if (phase > kTwoPi) phase -= kTwoPi;
          payload[static_cast<size_t>(i) * audiohub::kChannels + 0] = v;
          payload[static_cast<size_t>(i) * audiohub::kChannels + 1] = v;
        }
        captured_frames += audiohub::kFramesPerPacket;
      } else if (ring.Available() >= packet_samples) {
        ring.Read(payload.data(), packet_samples);
      } else {
        have_data = false;
        dropped_samples += packet_samples;
      }

      if (have_data) {
        header.seq++;
        header.sample_pos = sample_pos;
        header.capture_ts_ms = NowMs(t0);

        const size_t n = audiohub::EncodeHeader(header, datagram.data());
        std::memcpy(datagram.data() + n, payload.data(), packet_samples * sizeof(int16_t));
        if (!data_socket.SendToPeer(datagram.data(), n + packet_samples * sizeof(int16_t))) {
          if (send_errors++ < 5) {
            std::printf("发送失败: %s\n", data_socket.last_error().c_str());
          }
        } else {
          sent_packets++;
        }
      }
      sample_pos += audiohub::kFramesPerPacket;

      // 零均值抖动：只扰动到达时刻，不改变平均发包率。
      // 单边抖动会让源端时间轴变慢，接收端水位被抽干——那是 bug，不是抖动。
      std::chrono::steady_clock::time_point wake = next;
      if (opt.jitter_ms > 0) {
        std::uniform_real_distribution<double> dist(-opt.jitter_ms, opt.jitter_ms);
        wake += std::chrono::microseconds(
            static_cast<long long>(dist(rng) * 1000.0));
      }
      std::this_thread::sleep_until(wake);
      // 被系统挂起太久就别"补课"了，直接从当前时刻重新起拍。
      if (Clock::now() - next > std::chrono::milliseconds(100)) next = Clock::now();
    }
  });

  // ---- 时钟同步线程 ----
  audiohub::ClockSync clock_sync;
  std::atomic<double> measured_offset_ms{0.0};
  std::atomic<double> measured_rtt_ms{0.0};
  std::atomic<bool> clock_ready{false};

  std::thread clock_thread([&] {
    uint8_t ping[5];
    uint8_t pong[64];
    ping[0] = 1;  // type=1(PING)
    while (running.load()) {
      const uint32_t t1 = NowMs(t0);
      std::memcpy(ping + 1, &t1, sizeof(t1));
      ctrl_socket.SendToPeer(ping, sizeof(ping));

      // 发完必须等一小会儿再收。
      // 之前是"发完立刻非阻塞读，读不到就直接睡 500 ms"，
      // 于是 PONG 要等到下一轮才被读到，RTT 恒等于 500 ms——
      // 测出来的不是网络往返，是自己的轮询周期。
      const int n = ctrl_socket.ReceiveFrom(pong, sizeof(pong), nullptr, nullptr);
      if (n >= 13 && pong[0] == 2) {
        uint32_t pt1 = 0, t2 = 0, t3 = 0;
        std::memcpy(&pt1, pong + 1, 4);
        std::memcpy(&t2, pong + 5, 4);
        std::memcpy(&t3, pong + 9, 4);
        clock_sync.AddSample(pt1, t2, t3, NowMs(t0));
      }

      double off = 0.0;
      if (clock_sync.OffsetMs(&off)) {
        measured_offset_ms.store(off);
        measured_rtt_ms.store(clock_sync.RttMs());
        clock_ready.store(clock_sync.Ready());
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(470));
    }
  });

  // ---- 统计 ----
  uint64_t last_frames = 0;
  uint64_t last_packets = 0;
  while (running.load()) {
    std::this_thread::sleep_for(std::chrono::seconds(1));
    const uint64_t f = captured_frames.load();
    const uint64_t p = sent_packets.load();
    const double rtt = measured_rtt_ms.load();

    double capture_level = -120.0;
    const bool has_level = capture_meter.Take(&capture_level);
    std::printf(
        "采集 %5.1f kfps 电平 %6.1f dBFS | 发送 %4llu pkt/s | 约 %6.1f kbps | RTT %5.1f ms "
        "| 单程 %5.1f ms | 丢 %llu\n",
        (f - last_frames) / 1000.0, has_level ? capture_level : -120.0,
        static_cast<unsigned long long>(p - last_packets),
                (p - last_packets) * (packet_samples * 2 + audiohub::kHeaderSize) * 8.0 / 1000.0,
                rtt, rtt * 0.5, static_cast<unsigned long long>(dropped_samples.load()));
    last_frames = f;
    last_packets = p;
    std::fflush(stdout);  // 理由同 hub：--log 时保证每秒落盘

    if (!tone_mode && !capture.running()) {
      std::printf("采集已停止: %s\n", capture.last_error().c_str());
      running.store(false);
      break;
    }
  }

  running.store(false);
  capture.Stop();
  if (sender.joinable()) sender.join();
  if (clock_thread.joinable()) clock_thread.join();
  return 0;
}
