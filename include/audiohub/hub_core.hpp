// 汇聚端核心：接收多路音频、混音、输出；外加设备发现与连接握手。
//
// 线程模型：
//   [广播线程]    每秒广播一次自己的存在（发现协议）
//   [发现接收线程] 收集别人的广播，维护对端列表
//   [控制线程]    连接握手（CONN-REQ/ACK/NAK/BYE）+ PING/PONG
//   [接收线程]    UDP 音频 -> 解包 -> 每源 JitterBuffer
//   [混音线程]    每 5 ms：JB -> ASRC -> 求和(带每源增益) -> 软限幅 -> 输出环
//   [渲染线程]    WASAPI 实时回调：只从输出环 memcpy
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "audiohub/discovery.hpp"

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
  bool confirmed = true;      // false = 握手还没通过，混音时按静音处理
  std::string peer_name;      // 发现协议拿到的设备名，比"流 1"可读
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

// 一条待确认的连接请求
struct PendingConnection {
  uint16_t stream_id = 0;
  std::string peer_name;
  std::string peer_ip;
  uint16_t peer_port = 0;
  int64_t requested_at_ms = 0;
};

struct StartOptions {
  bool advertise = true;           // 广播自己的存在
  bool require_handshake = true;   // true = 未经确认的流按静音处理
  bool auto_accept = false;        // 自动同意新连接请求
  std::string display_name;        // 界面上显示的本机名
};

class HubCore {
 public:
  HubCore();
  ~HubCore();

  HubCore(const HubCore&) = delete;
  HubCore& operator=(const HubCore&) = delete;

  bool Start(uint16_t port, int latency_ms, int render_latency_ms, const StartOptions& opts,
             std::string* error = nullptr);
  void Stop();
  bool running() const;

  std::vector<SourceInfo> Sources() const;
  HubStats Stats() const;

  void SetSourceGain(uint16_t id, float gain);
  void SetSourceMuted(uint16_t id, bool muted);
  void SetMasterGain(float gain);
  float master_gain() const;

  void SetLatency(int ms);
  int latency() const;

  // ---- 发现 ----
  std::vector<Peer> DiscoveredPeers() const;
  void RefreshDiscovery();  // 立即发一轮探测，不用等下一个广播周期

  // ---- 连接握手 ----
  std::vector<PendingConnection> PendingConnections() const;
  void AcceptConnection(uint16_t stream_id);
  void RejectConnection(uint16_t stream_id);
  void Disconnect(uint16_t stream_id);
  void SetAutoAccept(bool on);
  bool auto_accept() const;

  std::string local_ip() const;
  void SetEventCallback(std::function<void(const std::string&)> cb);

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace audiohub
