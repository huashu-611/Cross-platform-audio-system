// 网络图谱（GRAPH）：纯数据与纯算法，不依赖 socket / 音频后端。
//
// 契约见 docs/GRAPH-PROTOCOL.md §2/§3。刻意做成无平台依赖的纯函数集合：
// 两端（PC C++ / Android Java）必须算出逐字节一致的结果，只有能单测才敢改。
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace audiohub {

// 节点。id = "<ip>:<audioPort>"，是节点在会话内的唯一标识；
// audio_port 与 id 里的端口重复，但线格式和 HTTP 都要求独立字段，所以保留。
struct GraphNode {
  std::string id;
  std::string name;
  std::string kind;  // "pc" | "android" | "other"
  uint16_t audio_port = 0;
  uint16_t http_port = 0;
};

// 图谱快照。nodes 始终按 id 升序（SortNodes 保证），跨端比较时可以直接逐项对齐。
struct GraphState {
  uint64_t version = 0;  // Lamport 版本号，只增
  std::string origin;    // 最近一次改动发起者的 node id
  std::string hub_id;    // 当前汇聚端；空 = 还没确定（Android 首次入网前）
  std::vector<GraphNode> nodes;
};

// 合并结果：区分"节点变了"和"汇聚端变了"，方便调用方决定是否广播 / 写日志。
struct GraphMergeResult {
  bool changed = false;
  bool nodes_changed = false;
  bool hub_changed = false;
};

// 规模上限（§2.1，两端必须一致）：节点最多 32 个；帧长超过 1000 字节不合法。
constexpr size_t kMaxGraphNodes = 32;
constexpr size_t kMaxGraphNameBytes = 40;
constexpr size_t kMaxGraphFrameBytes = 1000;

// 名字清洗：分隔符 / 控制字符换成 '_'，非法 UTF-8 字节序列也换成 '_'
// （否则 /api/state 的 JSON 会变成非法编码，界面直接解析失败），
// 最后按 UTF-8 字符边界截到 40 字节，不允许截出半个汉字。
std::string SanitizeName(const std::string& in);

// 编码成线格式（末尾带 '\n'）。节点先按 id 升序；
// 编码后超过 1000 字节时按 id 升序从尾部裁节点直到装得下（§2.1 确定性规则）。
std::string EncodeGraphFrame(const GraphState& g);

// 解析线格式。false = 整帧非法（格式不符 / 数字非法 / id 不含 ':' /
// 节点数 > 32 / 帧长 > 1000），调用方应当整帧丢弃。
bool DecodeGraphFrame(const std::string& frame, GraphState* out);

// 是否是同步请求帧 "GRAPH-REQ"（允许结尾空白，收包时可能带着 '\r\n'）。
bool IsGraphRequestFrame(const std::string& frame);

// 合并远端图谱（§3）。local 原地修改；返回值说明变化类型。
GraphMergeResult MergeGraph(GraphState* local, const GraphState& remote);

// 追加节点（§4.1 入网，不 bump version）。已存在 / id 非法 / 超上限返回 false。
bool UpsertNode(GraphState* g, const GraphNode& n);

// 本地切换汇聚端（§4.5）。hub_id 必须是已存在节点，否则拒绝。
bool SwitchHub(GraphState* g, const std::string& self_id, const std::string& hub_id);

bool IsHub(const GraphState& g, const std::string& self_id);

const GraphNode* FindNode(const GraphState& g, const std::string& id);
const GraphNode* FindNodeByIp(const GraphState& g, const std::string& ip);

// streamId = 按 id 升序的排名 + 1；self 不在图谱里（还没入网）返回 0。
uint16_t StreamIdFor(const GraphState& g, const std::string& self_id);

// 按 id 升序排序（原地）。编码 / 合并后都要调用，保证跨端顺序一致。
void SortNodes(GraphState* g);

// ------------------------------------------------------------------ 房间摆位（POS）
//
// 图谱回答"局域网里有哪些设备"，摆位回答"它们摆在房间哪个位置"。
// 位置由桌面端拖拽产生，必须让所有设备（多台电脑、手机）看到同一套布局，
// 所以单独用一个 POS 帧同步：GRAPH 的节点字段是严格 5 段、对端会整帧丢弃
// 不认识的帧，往节点里塞字段会直接破坏兼容性（docs/GRAPH-PROTOCOL.md §13）。
//
// 合并策略是"整表 LWW"而不是逐节点 LWW：拖拽是人对整个房间的编辑，
// 整表替换能让两端收敛到同一份快照，也不需要为每个节点再记版本号。
struct PlacementItem {
  std::string node_id;  // "<ip>:<port>"，与 GraphNode::id 同格式
  int16_t x_cm = 0;     // 地面坐标，屏幕右为 +X（厘米）
  int16_t z_cm = 0;     // 地面坐标，屏幕下方 / 靠近观察者为 +Z（厘米）
};

// 摆位快照。items 始终按 node_id 升序（SortPlacementItems 保证）。
struct PlacementState {
  uint64_t version = 0;  // Lamport 版本号，与 GraphState 各自独立编号
  std::string origin;    // 最近一次改动发起者的 node id
  std::vector<PlacementItem> items;
};

struct PlacementMergeResult {
  bool changed = false;       // 本地摆位是否被这条帧改变
  bool adopted = false;       // 是否整表采纳了远端
  bool items_changed = false; // 具体坐标表是否有变化（用于日志/测试）
};

// 坐标上限 ±320 m（=±32000 cm）：距离就是音量（用户定稿），要能把声源甩得很远；
// 仍是 int16 厘米能表示的范围（最大 ±327 m），同时挡住脏数。
constexpr int kPlacementCoordLimitCm = 32000;
// 与 GRAPH 帧同一量级的上限；超长帧两端都不合法。
constexpr size_t kMaxPlacementFrameBytes = 1000;

// 编码成线格式 "POS|<version>|<origin>|<nodeId>,<xCm>,<zCm>;..."（末尾带 '\n'）。
// items 先按 node_id 升序；超过 1000 字节时按升序从尾部裁项。
std::string EncodePlacementFrame(const PlacementState& p);

// 解析线格式。false = 整帧非法（前缀 / 段数 / 数字 / id / 坐标范围不对）。
bool DecodePlacementFrame(const std::string& frame, PlacementState* out);

// 是否是同步请求帧 "POS-REQ"（允许结尾空白）。
bool IsPlacementRequestFrame(const std::string& frame);

// 整表 LWW：version 大者胜；同版本按 origin 逐字节字典序大者胜（与 MergeGraph 一致）。
PlacementMergeResult MergePlacement(PlacementState* local, const PlacementState& remote);

const PlacementItem* FindPlacement(const PlacementState& p, const std::string& node_id);

void SortPlacementItems(PlacementState* p);

// 本地改动（协议 §13.2）：改动即 version+1、origin=self。
// 坐标没变 / 越界 / id 非法 / origin 为空都返回 false 且不改状态。
bool SetPlacementItem(PlacementState* p, const std::string& node_id, int x_cm, int z_cm,
                      const std::string& origin_id);
// 删除某个节点的摆位（回到自动布局）。不存在返回 false。
bool RemovePlacementItem(PlacementState* p, const std::string& node_id,
                         const std::string& origin_id);

}  // namespace audiohub