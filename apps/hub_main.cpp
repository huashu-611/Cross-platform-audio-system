// audiohub-hub：汇聚端命令行版。
//
// 真正的逻辑都在 src/hub_core.cpp 里，这里只是薄壳——
// 命令行版和图形界面版必须共用同一套实现，否则"UI 上调了音量但
// 脚本测试时行为不一样"这类问题会反复出现。
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>

#include "audiohub/hub_core.hpp"
#include "audiohub/udp_socket.hpp"

namespace {

struct Options {
  uint16_t port = 47000;
  int latency_ms = 60;
  int render_latency_ms = 10;
  std::string log_path;
};

void PrintUsage() {
  std::printf(
      "用法: audiohub-hub [选项]\n"
      "\n"
      "  --port <n>        监听端口，默认 47000（控制口自动用 port+1）\n"
      "  --latency <ms>    目标端到端延迟，默认 60。可选 40/60/120\n"
      "  --render-ms <n>   WASAPI 输出缓冲毫秒数，默认 10\n"
      "  --log <file>      把输出写到文件（后台运行时看统计用）\n"
      "\n"
      "需要调每路音量/静音的话，用图形界面版 audiohub-hub-gui.exe\n");
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
      (void)next("--block");  // 兼容旧脚本，已无效
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

}  // namespace

int main(int argc, char** argv) {
  Options opt;
  if (!ParseArgs(argc, argv, &opt)) return 1;

  if (!opt.log_path.empty()) {
    if (std::freopen(opt.log_path.c_str(), "w", stdout) == nullptr) {
      std::printf("无法打开日志文件: %s\n", opt.log_path.c_str());
      return 1;
    }
  }
  // 行缓冲：进程被强杀时不会丢掉最后几 KB 输出
  std::setvbuf(stdout, nullptr, _IOLBF, 0);

  audiohub::HubCore hub;
  hub.SetEventCallback([](const std::string& text) { std::printf("%s\n", text.c_str()); });

  std::string error;
  if (!hub.Start(opt.port, opt.latency_ms, opt.render_latency_ms, &error)) {
    std::printf("%s\n", error.c_str());
    return 2;
  }

  std::printf("\n");
  std::printf("  audiohub-hub 已启动\n");
  std::printf("  监听       : 0.0.0.0:%u（控制口 %u）\n", opt.port, opt.port + 1);
  std::printf("  目标延迟   : %d ms\n", opt.latency_ms);
  std::printf("  本机地址   : %s\n", hub.local_ip().c_str());
  std::printf("  Ctrl+C 停止\n\n");

  const auto t0 = std::chrono::steady_clock::now();

  while (hub.running()) {
    std::this_thread::sleep_for(std::chrono::seconds(1));

    const auto stats = hub.Stats();
    const auto sources = hub.Sources();
    const auto elapsed =
        std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - t0)
            .count();

    std::printf("[%lld s] 源 %zu 路 | 总线 %6.1f dBFS | 渲染欠载 %llu | 坏包 %llu\n",
                static_cast<long long>(elapsed), sources.size(), stats.bus_level_dbfs,
                static_cast<unsigned long long>(stats.render_underruns),
                static_cast<unsigned long long>(stats.malformed_packets));

    for (const auto& s : sources) {
      std::printf("   stream %-3u 水位 %4d ms (目标 %d) | 比率 %.5f | 电平 %6.1f dBFS | 溢出 %llu 欠载 %llu\n",
                  s.id, s.occupancy_ms, s.target_ms, s.ratio, s.level_dbfs,
                  (unsigned long long)s.overflow_packets, (unsigned long long)s.underrun_frames);
    }
    std::fflush(stdout);
  }

  hub.Stop();
  return 0;
}
