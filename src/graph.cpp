#include "audiohub/graph.hpp"

#include <algorithm>
#include <cstddef>
#include <string>
#include <vector>

namespace audiohub {
namespace {

// 按分隔符切分。保留空段：第 5 段为空串代表 0 个节点，
// "a;;b" 这种空节点必须能被识别成非法帧而不是悄悄跳过。
std::vector<std::string> Split(const std::string& s, char sep) {
  std::vector<std::string> out;
  size_t start = 0;
  while (true) {
    const size_t pos = s.find(sep, start);
    if (pos == std::string::npos) {
      out.push_back(s.substr(start));
      break;
    }
    out.push_back(s.substr(start, pos - start));
    start = pos + 1;
  }
  return out;
}

// 去掉结尾的 '\r' '\n' 和空白。握手线程读到的行可能带 CRLF，
// GRAPH-REQ 的判定允许结尾空白（§2.2）。
std::string TrimTrailing(const std::string& s) {
  size_t end = s.size();
  while (end > 0 && (s[end - 1] == '\n' || s[end - 1] == '\r' ||
                     s[end - 1] == ' ' || s[end - 1] == '\t')) {
    --end;
  }
  return s.substr(0, end);
}

// origin/hubId 会原样写进 /api/state 的 JSON 和调试日志：空串合法（hubId 未定），
// 但控制字符、双引号、反斜杠会把下游 JSON 弄坏，这种帧整帧丢弃（lead 加固要求）。
bool ValidIdText(const std::string& s) {
  for (const char c : s) {
    const unsigned char u = static_cast<unsigned char>(c);
    if (u < 0x20 || u == 0x7F || c == '"' || c == '\\') return false;
  }
  return true;
}

// kind 只允许三个值；不认识的按 "other" 处理（解析宽松，但绝不让脏值上线）。
std::string NormalizeKind(const std::string& kind) {
  if (kind == "pc" || kind == "android" || kind == "other") return kind;
  return "other";
}

// 严格十进制无符号解析（version）。拒绝空串、符号、空白、溢出。
bool ParseU64(const std::string& s, uint64_t* out) {
  if (s.empty() || s.size() > 20) return false;
  uint64_t v = 0;
  for (char c : s) {
    if (c < '0' || c > '9') return false;
    const uint64_t digit = static_cast<uint64_t>(c - '0');
    if (v > (UINT64_MAX - digit) / 10) return false;
    v = v * 10 + digit;
  }
  *out = v;
  return true;
}

// 端口解析：1..65535（allow_zero 时允许 0，httpPort 用来表示"没有 HTTP 口"）。
bool ParsePort(const std::string& s, bool allow_zero, uint16_t* out) {
  if (s.empty() || s.size() > 5) return false;
  uint32_t v = 0;
  for (char c : s) {
    if (c < '0' || c > '9') return false;
    v = v * 10 + static_cast<uint32_t>(c - '0');
    if (v > 65535) return false;
  }
  if (!allow_zero && v == 0) return false;
  *out = static_cast<uint16_t>(v);
  return true;
}

// 从 id 里拆 ip。id 约定 "<ip>:<audioPort>"，局域网 IPv4 不含 ':'，取最后一个即可。
std::string IdIp(const std::string& id) {
  const size_t colon = id.rfind(':');
  return colon == std::string::npos ? std::string() : id.substr(0, colon);
}

// id 是否形如 "<ip>:<port>"，并顺带取出端口。id 是唯一标识，
// 节点其它字段都从它派生，所以这里校验得比别的字段严。
bool ValidId(const std::string& id, uint16_t* port) {
  const size_t colon = id.rfind(':');
  if (colon == std::string::npos || colon == 0 || colon + 1 >= id.size()) return false;
  return ParsePort(id.substr(colon + 1), false, port);
}

// 拼帧体（不含结尾 '\n'）。id 非法的节点直接跳过：宁可少一个节点，
// 也不能发出一个连自己都解析不了的帧。
std::string BuildFrameBody(const GraphState& g) {
  std::string frame = "GRAPH|";
  frame += std::to_string(g.version);
  frame += '|';
  frame += g.origin;
  frame += '|';
  frame += g.hub_id;
  frame += '|';
  bool first = true;
  for (const auto& n : g.nodes) {
    uint16_t id_port = 0;
    if (!ValidId(n.id, &id_port)) continue;
    if (!first) frame += ';';
    first = false;
    frame += n.id;
    frame += ',';
    frame += n.name;
    frame += ',';
    frame += n.kind;
    frame += ',';
    frame += std::to_string(n.audio_port);
    frame += ',';
    frame += std::to_string(n.http_port);
  }
  return frame;
}

// 拼 POS 帧体（不含结尾 '\n'）。id 非法的项直接跳过，理由同 BuildFrameBody。
std::string BuildPlacementBody(const PlacementState& p) {
  std::string frame = "POS|";
  frame += std::to_string(p.version);
  frame += '|';
  frame += p.origin;
  frame += '|';
  bool first = true;
  for (const auto& it : p.items) {
    uint16_t id_port = 0;
    if (!ValidId(it.node_id, &id_port)) continue;
    if (!first) frame += ';';
    first = false;
    frame += it.node_id;
    frame += ',';
    frame += std::to_string(it.x_cm);
    frame += ',';
    frame += std::to_string(it.z_cm);
  }
  return frame;
}

// 严格解析有符号厘米坐标：可选 '-'，十进制，绝对值不超过 ±kPlacementCoordLimitCm。
bool ParseSignedCm(const std::string& s, int16_t* out) {
  if (s.empty() || s.size() > 5) return false;  // 最长 "-2000"
  size_t i = 0;
  bool neg = false;
  if (s[0] == '-') {
    neg = true;
    i = 1;
    if (i >= s.size()) return false;
  }
  int32_t v = 0;
  for (; i < s.size(); ++i) {
    const char c = s[i];
    if (c < '0' || c > '9') return false;
    v = v * 10 + static_cast<int32_t>(c - '0');
    if (v > kPlacementCoordLimitCm) return false;
  }
  *out = static_cast<int16_t>(neg ? -v : v);
  return true;
}

bool SamePlacementItems(const std::vector<PlacementItem>& a,
                        const std::vector<PlacementItem>& b) {
  if (a.size() != b.size()) return false;
  for (size_t i = 0; i < a.size(); ++i) {
    if (a[i].node_id != b[i].node_id || a[i].x_cm != b[i].x_cm || a[i].z_cm != b[i].z_cm) {
      return false;
    }
  }
  return true;
}

}  // namespace

std::string SanitizeName(const std::string& in) {
  std::string out;
  out.reserve(std::min<size_t>(in.size(), kMaxGraphNameBytes));
  size_t i = 0;
  while (i < in.size() && out.size() < kMaxGraphNameBytes) {
    const unsigned char c = static_cast<unsigned char>(in[i]);
    if (c < 0x80) {
      // 分隔符和控制字符会把线格式 / JSON 弄坏，统一换成 '_'。
      if (c < 0x20 || c == '|' || c == ',' || c == ';') {
        out.push_back('_');
      } else {
        out.push_back(static_cast<char>(c));
      }
      ++i;
      continue;
    }
    // 多字节 UTF-8：整字符放进，放不下（会截半个汉字）就直接停。
    size_t len = 0;
    if ((c & 0xE0) == 0xC0) {
      len = 2;
    } else if ((c & 0xF0) == 0xE0) {
      len = 3;
    } else if ((c & 0xF8) == 0xF0) {
      len = 4;
    }
    bool valid = len != 0 && i + len <= in.size();
    for (size_t k = 1; valid && k < len; ++k) {
      valid = (static_cast<unsigned char>(in[i + k]) & 0xC0) == 0x80;
    }
    if (!valid) {
      // 非法起始字节或非法续字节：替换成 '_' 并只前进 1 字节，
      // 保证输出一定是合法 UTF-8（否则 HTTP JSON 会坏）。
      out.push_back('_');
      ++i;
      continue;
    }
    if (out.size() + len > kMaxGraphNameBytes) break;
    out.append(in, i, len);
    i += len;
  }
  return out;
}

void SortNodes(GraphState* g) {
  if (g == nullptr) return;
  std::sort(g->nodes.begin(), g->nodes.end(),
            [](const GraphNode& a, const GraphNode& b) { return a.id < b.id; });
}

std::string EncodeGraphFrame(const GraphState& g) {
  GraphState sorted = g;
  SortNodes(&sorted);
  // 节点数上限在编码端也挡一次：超过 32 的帧对端一定整帧丢弃，
  // 与其发出去不如按 id 升序保留前 32 个（防御性，正常流程到不了这里）。
  if (sorted.nodes.size() > kMaxGraphNodes) sorted.nodes.resize(kMaxGraphNodes);
  // 名字和 kind 在编码前再清洗一遍：调用方可能塞进分隔符，直接拼会毁掉整帧。
  for (auto& n : sorted.nodes) {
    n.name = SanitizeName(n.name);
    n.kind = NormalizeKind(n.kind);
  }
  // 超过 1000 字节：按 id 升序从尾部裁节点，直到装得下（§2.1 确定性规则）。
  while (true) {
    const std::string frame = BuildFrameBody(sorted) + "\n";
    if (frame.size() <= kMaxGraphFrameBytes || sorted.nodes.empty()) return frame;
    sorted.nodes.pop_back();
  }
}

bool DecodeGraphFrame(const std::string& frame, GraphState* out) {
  if (out == nullptr) return false;
  const std::string text = TrimTrailing(frame);
  if (text.empty() || text.size() > kMaxGraphFrameBytes) return false;
  const std::vector<std::string> parts = Split(text, '|');
  if (parts.size() != 5 || parts[0] != "GRAPH") return false;

  GraphState g;
  if (!ParseU64(parts[1], &g.version)) return false;
  g.origin = parts[2];
  g.hub_id = parts[3];
  if (!ValidIdText(g.origin) || !ValidIdText(g.hub_id)) return false;
  if (!parts[4].empty()) {
    const std::vector<std::string> items = Split(parts[4], ';');
    if (items.size() > kMaxGraphNodes) return false;  // 节点过多：整帧丢弃
    for (const auto& item : items) {
      if (item.empty()) return false;  // "a;;b" 之类：格式坏了
      const std::vector<std::string> f = Split(item, ',');
      if (f.size() != 5) return false;
      GraphNode n;
      n.id = f[0];
      uint16_t id_port = 0;
      if (!ValidId(n.id, &id_port)) return false;  // id 不含 ':' / 端口非法
      n.name = SanitizeName(f[1]);
      n.kind = NormalizeKind(f[2]);
      if (!ParsePort(f[3], false, &n.audio_port)) return false;
      if (!ParsePort(f[4], true, &n.http_port)) return false;
      // id 是唯一标识：id 与 audioPort 打架时以 id 为准，避免本地状态自相矛盾。
      n.audio_port = id_port;
      if (FindNode(g, n.id) == nullptr) g.nodes.push_back(n);  // 重复 id 保留第一个
    }
  }
  SortNodes(&g);
  *out = g;
  return true;
}

bool IsGraphRequestFrame(const std::string& frame) {
  return TrimTrailing(frame) == "GRAPH-REQ";
}

GraphMergeResult MergeGraph(GraphState* local, const GraphState& remote) {
  GraphMergeResult r;
  if (local == nullptr) return r;

  // 1) 节点并集：同 id 保留 local（节点字段在会话内不变）。
  for (const auto& n : remote.nodes) {
    uint16_t id_port = 0;
    if (!ValidId(n.id, &id_port)) continue;  // 远端脏数据不往图谱里放
    if (FindNode(*local, n.id) != nullptr) continue;
    if (local->nodes.size() >= kMaxGraphNodes) break;  // 上限：多出来的只能不接
    local->nodes.push_back(n);
    r.nodes_changed = true;
  }
  if (r.nodes_changed) SortNodes(local);

  // 2) 汇聚端 LWW（§3）。version 是 Lamport 时钟，本地改动由 SwitchHub 递增；
  //    merge 时"见过最大 version"就等于采纳后的 version，因为更大的值一定被采纳。
  if (remote.version > local->version) {
    local->hub_id = remote.hub_id;
    local->origin = remote.origin;
    local->version = remote.version;
    r.hub_changed = true;  // version 变了也算"汇聚端信息有变化"
  } else if (remote.version == local->version && remote.origin != local->origin &&
             remote.origin > local->origin) {
    // 同版本冲突按 origin 的逐字节字典序打破（两端算法必须一致）。
    local->hub_id = remote.hub_id;
    local->origin = remote.origin;
    r.hub_changed = true;
  }
  r.changed = r.nodes_changed || r.hub_changed;
  return r;
}

bool UpsertNode(GraphState* g, const GraphNode& n) {
  if (g == nullptr) return false;
  uint16_t id_port = 0;
  if (!ValidId(n.id, &id_port)) return false;
  if (FindNode(*g, n.id) != nullptr) return false;  // 字段会话内不变：只增不改
  if (g->nodes.size() >= kMaxGraphNodes) return false;
  GraphNode node = n;
  node.name = SanitizeName(node.name);
  node.kind = NormalizeKind(node.kind);
  node.audio_port = id_port;  // 端口以 id 为准
  g->nodes.push_back(node);
  SortNodes(g);
  return true;
}

bool SwitchHub(GraphState* g, const std::string& self_id, const std::string& hub_id) {
  if (g == nullptr || self_id.empty() || hub_id.empty()) return false;
  if (FindNode(*g, hub_id) == nullptr) return false;  // §4.5：目标必须是已知节点
  // 本地改动：version = 见过的最大 version + 1；merge 已保证 local.version 是最大值。
  ++g->version;
  g->origin = self_id;
  g->hub_id = hub_id;
  return true;
}

bool IsHub(const GraphState& g, const std::string& self_id) {
  return !self_id.empty() && g.hub_id == self_id;
}

const GraphNode* FindNode(const GraphState& g, const std::string& id) {
  for (const auto& n : g.nodes) {
    if (n.id == id) return &n;
  }
  return nullptr;
}

const GraphNode* FindNodeByIp(const GraphState& g, const std::string& ip) {
  if (ip.empty()) return nullptr;
  for (const auto& n : g.nodes) {
    if (IdIp(n.id) == ip) return &n;
  }
  return nullptr;
}

uint16_t StreamIdFor(const GraphState& g, const std::string& self_id) {
  // 排名按 id 升序，与编码顺序一致；self 还没入网时返回 0。
  if (FindNode(g, self_id) == nullptr) return 0;
  uint16_t rank = 0;
  for (const auto& n : g.nodes) {
    if (n.id < self_id) ++rank;
  }
  return static_cast<uint16_t>(rank + 1);
}

// ------------------------------------------------------------------ 房间摆位（POS）

void SortPlacementItems(PlacementState* p) {
  if (p == nullptr) return;
  std::sort(p->items.begin(), p->items.end(),
            [](const PlacementItem& a, const PlacementItem& b) { return a.node_id < b.node_id; });
}

const PlacementItem* FindPlacement(const PlacementState& p, const std::string& node_id) {
  for (const auto& it : p.items) {
    if (it.node_id == node_id) return &it;
  }
  return nullptr;
}

std::string EncodePlacementFrame(const PlacementState& p) {
  PlacementState sorted = p;
  SortPlacementItems(&sorted);
  // 超过上限：按 node_id 升序从尾部裁项（与 GRAPH 同一套确定性规则）。两个上限都要守：
  // 帧长 1000 字节保证 UDP 一包发得下；项数 32 与节点上限一致，否则对端会整帧丢弃。
  while (true) {
    const std::string frame = BuildPlacementBody(sorted) + "\n";
    const bool too_many = sorted.items.size() > kMaxGraphNodes;
    if ((!too_many && frame.size() <= kMaxPlacementFrameBytes) || sorted.items.empty()) {
      return frame;
    }
    sorted.items.pop_back();
  }
}

bool DecodePlacementFrame(const std::string& frame, PlacementState* out) {
  if (out == nullptr) return false;
  const std::string text = TrimTrailing(frame);
  if (text.empty() || text.size() > kMaxPlacementFrameBytes) return false;
  const std::vector<std::string> parts = Split(text, '|');
  if (parts.size() != 4 || parts[0] != "POS") return false;

  PlacementState p;
  if (!ParseU64(parts[1], &p.version)) return false;
  p.origin = parts[2];
  if (!ValidIdText(p.origin)) return false;
  if (!parts[3].empty()) {
    const std::vector<std::string> items = Split(parts[3], ';');
    if (items.size() > kMaxGraphNodes) return false;  // 摆位项上限与节点上限一致
    for (const auto& item : items) {
      if (item.empty()) return false;  // "a;;b" 之类：格式坏了
      const std::vector<std::string> f = Split(item, ',');
      if (f.size() != 3) return false;
      PlacementItem it;
      it.node_id = f[0];
      uint16_t id_port = 0;
      if (!ValidId(it.node_id, &id_port)) return false;
      if (!ParseSignedCm(f[1], &it.x_cm)) return false;
      if (!ParseSignedCm(f[2], &it.z_cm)) return false;
      if (FindPlacement(p, it.node_id) == nullptr) p.items.push_back(it);  // 重复 id 保留第一个
    }
  }
  SortPlacementItems(&p);
  *out = p;
  return true;
}

bool IsPlacementRequestFrame(const std::string& frame) {
  return TrimTrailing(frame) == "POS-REQ";
}

PlacementMergeResult MergePlacement(PlacementState* local, const PlacementState& remote) {
  PlacementMergeResult r;
  if (local == nullptr) return r;
  // 整表 LWW（§13.2）：version 大者胜；同版本按 origin 字典序打破平局（与 MergeGraph 一致）。
  const bool newer = remote.version > local->version;
  const bool tie_break = remote.version == local->version && remote.origin > local->origin;
  if (!newer && !tie_break) return r;

  PlacementState next = remote;
  SortPlacementItems(&next);
  r.items_changed = !SamePlacementItems(next.items, local->items);
  r.changed = r.items_changed || next.version != local->version;
  if (!r.changed) return r;  // 防御：版本不同但表内容一致也视作已采纳过，不必回发
  *local = next;
  r.adopted = true;
  return r;
}

bool SetPlacementItem(PlacementState* p, const std::string& node_id, int x_cm, int z_cm,
                      const std::string& origin_id) {
  if (p == nullptr || origin_id.empty()) return false;
  uint16_t id_port = 0;
  if (!ValidId(node_id, &id_port)) return false;
  if (x_cm < -kPlacementCoordLimitCm || x_cm > kPlacementCoordLimitCm) return false;
  if (z_cm < -kPlacementCoordLimitCm || z_cm > kPlacementCoordLimitCm) return false;

  for (auto& it : p->items) {
    if (it.node_id != node_id) continue;
    // 同一位置重复提交（GUI 轮询 / 重试）不该制造新版本，否则两端会互相回发。
    if (it.x_cm == x_cm && it.z_cm == z_cm) return false;
    it.x_cm = static_cast<int16_t>(x_cm);
    it.z_cm = static_cast<int16_t>(z_cm);
    ++p->version;  // 本地改动：version = 见过的最大 version + 1（调用方负责先抬高）
    p->origin = origin_id;
    return true;
  }

  PlacementItem item;
  item.node_id = node_id;
  item.x_cm = static_cast<int16_t>(x_cm);
  item.z_cm = static_cast<int16_t>(z_cm);
  p->items.push_back(item);
  SortPlacementItems(p);
  ++p->version;
  p->origin = origin_id;
  return true;
}

bool RemovePlacementItem(PlacementState* p, const std::string& node_id,
                         const std::string& origin_id) {
  if (p == nullptr || origin_id.empty()) return false;
  for (auto it = p->items.begin(); it != p->items.end(); ++it) {
    if (it->node_id != node_id) continue;
    p->items.erase(it);
    ++p->version;
    p->origin = origin_id;
    return true;
  }
  return false;
}

}  // namespace audiohub