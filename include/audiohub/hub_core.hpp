// 汇聚端核心：接收多路音频、混音、输出。
//
// 抽成独立库的原因：命令行版和图形界面版必须共用同一套逻辑，
// 否则"UI 上调了音量但 CLI 测试时行为不一致"这类问题会反复出现。
//
// 线程模型（和之前一致，没有变）：
//   [接收线程]  UDP -> 解包 -> 每源 JitterBuffer
//   [混音线程]  每 5 ms：JB -> ASRC -> 求和(带每源增益) -> 软限幅 -> 输出环
//   [渲染线程]  WASAPI 实时回调：只从输出环 memcpy
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace audiohub {

struct SourceInfo {
  uint16_t id = 0;
  int occupancy_ms = 0;
  int target_ms = 0;
  double level_dbfs = -120.0;
  float gain = 1.0f;
  bool muted = false;
  uint64_t packets = 0;
  double ratio = 1.0;
  uint64_t underrun_frames = 0;
  uint64_t overflow_packets = 0;
};

struct HubStats {
  uint64_t packets_per_sec = 0;
  double kbps = 0.0;
  double bus_level_dbfs = -120.0;
  uint64_t render_underruns = 0;
  uint64_t malformed_packets = 0;
  size_t source_count = 0;
  int latency_ms = 60;
  int device_latency_ms = 0;
};

class HubCore {
 public:
  HubCore();
  ~HubCore();

  HubCore(const HubCore&) = delete;
  HubCore& operator=(const HubCore&) = delete;

  // port 为数据端口，控制口自动用 port+1。失败时写 error 并返回 false。
  bool Start(uint16_t port, int latency_ms, int render_latency_ms = 10,
             std::string* error = nullptr);
  void Stop();
  bool running() const;

  // 快照。UI 线程每 200 ms 取一次，不要持有迭代器跨调用。
  std::vector<SourceInfo> Sources() const;
  HubStats Stats() const;

  void SetSourceGain(uint16_t id, float gain);
  void SetSourceMuted(uint16_t id, bool muted);
  void SetMasterGain(float gain);
  float master_gain() const;

  // 改目标延迟：对所有已存在的流立即生效，新流也用这个值。
  void SetLatency(int ms);
  int latency() const;

  // 本机 IPv4 列表，UI 用来提示"手机上填这个地址"。
  std::string local_ip() const;

  // 状态变化回调（新流接入、设备失效等），在内部线程触发。
  void SetEventCallback(std::function<void(const std::string&)> cb);

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace audiohub
