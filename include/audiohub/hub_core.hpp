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
#include "audiohub/graph.hpp"
#include "audiohub/spatial.hpp"

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
  std::string peer_ip;        // 对端地址：界面切汇聚端时要用它
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
  uint16_t http_port = 0;          // HTTP 控制口（图谱节点要广播它），0 = 没有界面
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

  // ---- 3D 空间化（P0：Windows 汇聚端；坐标不进图谱协议）----
  // listener_valid=false 时所有源都走旧路径（2D 行为逐样本不变），因此
  // "UI 停止上报"就是整功能的回滚开关。
  void SetListenerPose(const ListenerPose& pose, bool valid);

  // node_id = "<ip>:<audioPort>"，与图谱节点 id 一致；位置按"设备 IP"归并，
  // 所以流重连/stream_id 变化不会丢位置。坐标单位米，|x|,|y|,|z| <= 20。
  // 返回 false = 未知设备（不在图谱也没有流）或坐标非法，此时不保存。
  bool SetSourceSpatial(const std::string& node_id, float x, float y, float z, bool enabled);

  // 节点离开图谱/取消摆放：删掉位置后该源自动回到旧路径。返回是否确实删掉了。
  bool ClearSourceSpatial(const std::string& node_id);

  // ---- 发现 ----
  std::vector<Peer> DiscoveredPeers() const;

  // 别人发来的邀请（对方希望把声音送到它那里）。
  // 收到 CONN-INVITE 时入队，界面确认后再由界面侧启动音源进程。
  std::vector<Peer> Invites() const;

  // 会话关系表：本次会话里"连过"的设备，跨角色切换保留。
  //
  // 为什么必须单独存一份：切换汇聚端之后本机不再收流，sources 会清空；
  // 手机端又不广播信标，peers 里也没有它 —— 于是下拉里只剩"本机"，
  // 用户切一次就再也切不回来了。
  std::vector<Peer> SessionPeers() const;

  // 主动断开所有：清空关系表（用户点"断开"时才调）
  void ClearSession();
  void AcceptInvite(const std::string& ip);
  void RejectInvite(const std::string& ip);
  void RefreshDiscovery();  // 立即发一轮探测，不用等下一个广播周期

  // ---- 连接握手 ----
  std::vector<PendingConnection> PendingConnections() const;
  void AcceptConnection(uint16_t stream_id);
  void RejectConnection(uint16_t stream_id);
  void Disconnect(uint16_t stream_id);
  void SetAutoAccept(bool on);
  bool auto_accept() const;

  // 邀请一台已发现的设备把声音送过来（发起方是 PC，但音频流向不变）
  bool InvitePeer(const Peer& peer);

  // 远程指挥对端切换角色（控制通道）
  //   make_hub=true  → 告诉对端"你来当汇聚端"
  //   make_hub=false → 告诉对端"把声音送到 hub_ip:hub_port"
  bool SendRoleCommand(const std::string& peer_ip, uint16_t peer_audio_port,
                       bool make_hub, const std::string& hub_ip, uint16_t hub_port);

  // ---- 图谱（docs/GRAPH-PROTOCOL.md）：节点集合 + 汇聚端指针 ----
  // 谁当汇聚端不再由本机单方面决定，而是图谱里的一个指针（LWW 合并）；
  // 每个设备都按图谱 reconcile 自己的职责。
  GraphState Graph() const;
  std::string graph_self_id() const;             // 本机节点 id = <IP>:<音频口>
  bool graph_is_hub() const;                     // graph.hubId == selfId
  bool SwitchGraphHub(const std::string& node_id);  // 本地切换汇聚端（节点不存在返回 false）
  void SyncGraphNow();                           // 立刻广播图谱 + 逐节点回探（/api/graph/sync）
  // 某 IP 最近一次收到数据报距今多少毫秒（从未见到 = -1）。界面用它判 online（§7.1）。
  int64_t graph_peer_last_seen_ms(const std::string& ip) const;

  // ---- 房间摆位（POS）：GRAPH 之外的独立同步帧（docs/GRAPH-PROTOCOL.md §13）----
  // 桌面端拖拽 -> 本机摆位 version+1 -> 广播给图谱里所有节点，多台电脑与手机看到同一份
  // 布局；手机端只读（画俯视图）。坐标单位米，X 向右、Z 向观察者（屏幕下方），|x|,|z| <= 20。
  // 节点 id 与图谱一致（"<ip>:<audioPort>"），节点不存在也允许存——布局可能先于设备入网，
  // 合并时按 LWW 收敛，不需要依赖当前图谱成员。
  PlacementState Placements() const;
  // 返回 false = 坐标越界 / node_id 非法；与现有位置相同算成功（幂等，不产生新版本）。
  bool SetNodePlacement(const std::string& node_id, float x_m, float z_m);
  // 删掉某节点的摆位（回到自动布局）。返回是否确实删除了。
  bool ClearNodePlacement(const std::string& node_id);
  uint64_t placement_version() const;  // 调试 / 测试

  std::string local_ip() const;
  void SetEventCallback(std::function<void(const std::string&)> cb);

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace audiohub
