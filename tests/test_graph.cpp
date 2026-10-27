#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "audiohub/graph.hpp"
#include "test_framework.hpp"

namespace {

audiohub::GraphNode MakeNode(const std::string& id, const std::string& name,
                             const std::string& kind, uint16_t audio_port,
                             uint16_t http_port) {
  audiohub::GraphNode n;
  n.id = id;
  n.name = name;
  n.kind = kind;
  n.audio_port = audio_port;
  n.http_port = http_port;
  return n;
}

audiohub::GraphState MakeGraph(uint64_t version, const std::string& origin,
                               const std::string& hub_id,
                               const std::vector<audiohub::GraphNode>& nodes) {
  audiohub::GraphState g;
  g.version = version;
  g.origin = origin;
  g.hub_id = hub_id;
  g.nodes = nodes;
  return g;
}

// 手工拼线格式：测试非法帧时需要绕过 EncodeGraphFrame 的清洗。
std::string NodeText(const audiohub::GraphNode& n) {
  return n.id + "," + n.name + "," + n.kind + "," + std::to_string(n.audio_port) + "," +
         std::to_string(n.http_port);
}

std::string MakeFrame(uint64_t version, const std::string& origin, const std::string& hub_id,
                      const std::vector<audiohub::GraphNode>& nodes) {
  std::string frame = "GRAPH|" + std::to_string(version) + "|" + origin + "|" + hub_id + "|";
  for (size_t i = 0; i < nodes.size(); ++i) {
    if (i > 0) frame += ';';
    frame += NodeText(nodes[i]);
  }
  return frame + "\n";
}

// 直接拼节点段（不经过 GraphNode），用来构造字段缺失之类的脏帧。
std::string MakeRawFrame(uint64_t version, const std::string& origin, const std::string& hub_id,
                         const std::vector<std::string>& node_texts) {
  std::string frame = "GRAPH|" + std::to_string(version) + "|" + origin + "|" + hub_id + "|";
  for (size_t i = 0; i < node_texts.size(); ++i) {
    if (i > 0) frame += ';';
    frame += node_texts[i];
  }
  return frame + "\n";
}

bool NodesSorted(const audiohub::GraphState& g) {
  for (size_t i = 1; i < g.nodes.size(); ++i) {
    if (!(g.nodes[i - 1].id < g.nodes[i].id)) return false;
  }
  return true;
}

const char* kSelfA = "192.168.1.20:47000";
const char* kNodeB = "192.168.1.31:47000";
const char* kNodeC = "192.168.1.32:47000";

}  // namespace

TEST(Graph_RoundTripKeepsAllFields) {
  std::vector<audiohub::GraphNode> nodes;
  nodes.push_back(MakeNode(kSelfA, "客厅PC", "pc", 47000, 4780));
  nodes.push_back(MakeNode(kNodeB, "Redmi K60", "android", 47000, 0));
  const auto g = MakeGraph(7, kSelfA, kSelfA, nodes);

  const std::string frame = audiohub::EncodeGraphFrame(g);
  CHECK_TRUE(!frame.empty());
  CHECK_EQ(frame.back(), '\n');
  CHECK_TRUE(frame.size() <= audiohub::kMaxGraphFrameBytes);

  audiohub::GraphState out;
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame, &out));
  CHECK_EQ(out.version, g.version);
  CHECK_TRUE(out.origin == g.origin);
  CHECK_TRUE(out.hub_id == g.hub_id);
  CHECK_EQ(out.nodes.size(), nodes.size());
  if (out.nodes.size() == 2) {
    CHECK_TRUE(out.nodes[0].id == kSelfA);
    CHECK_TRUE(out.nodes[0].name == "客厅PC");  // UTF-8 中文原样往返
    CHECK_TRUE(out.nodes[0].kind == "pc");
    CHECK_EQ(out.nodes[0].audio_port, 47000);
    CHECK_EQ(out.nodes[0].http_port, 4780);
    CHECK_TRUE(out.nodes[1].id == kNodeB);
    CHECK_EQ(out.nodes[1].http_port, 0);  // Android 没有 HTTP 口
  }
}

TEST(Graph_DecodeEmptyNodes) {
  const auto g = MakeGraph(3, kSelfA, kSelfA, {});
  const std::string frame = audiohub::EncodeGraphFrame(g);
  CHECK_TRUE(frame == std::string("GRAPH|3|") + kSelfA + "|" + kSelfA + "|\n");

  audiohub::GraphState out;
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame, &out));
  CHECK_TRUE(out.nodes.empty());
  CHECK_EQ(out.version, 3);
}

TEST(Graph_DecodeAcceptsCrLfAndEmptyHub) {
  const std::string frame = MakeFrame(1, kSelfA, std::string(""), {});
  audiohub::GraphState out;
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame, &out));
  CHECK_TRUE(out.hub_id.empty());  // Android 还没入网时 hubId 为空
  CHECK_TRUE(out.origin == kSelfA);
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame.substr(0, frame.size() - 1) + "\r\n", &out));
}

TEST(Graph_DecodeRejectsMalformedFrames) {
  audiohub::GraphState out;
  const std::string valid_node = NodeText(MakeNode(kNodeB, "手机", "android", 47000, 0));

  // 前缀 / 段数不对
  CHECK_FALSE(audiohub::DecodeGraphFrame("", &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame("GRAPH\n", &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame("HELLO|1|a|a|\n", &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame("GRAPH|1|a|a\n", &out));          // 少一段
  CHECK_FALSE(audiohub::DecodeGraphFrame(std::string("GRAPH|1|a|a|") + valid_node + "|x\n", &out));

  // version / 端口数字非法
  CHECK_FALSE(audiohub::DecodeGraphFrame("GRAPH|x|a|a|\n", &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame("GRAPH|-1|a|a|\n", &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame("GRAPH|1.5|a|a|\n", &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame("GRAPH|99999999999999999999|a|a|\n", &out));  // 溢出
  {
    auto bad = MakeNode(kNodeB, "手机", "android", 47000, 0);
    bad.audio_port = 0;
    CHECK_FALSE(audiohub::DecodeGraphFrame(MakeFrame(1, kSelfA, kSelfA, {bad}), &out));
    bad.audio_port = 47000;
    bad.http_port = 0;
    bad.id = "192.168.1.31";  // id 不含 ':'
    CHECK_FALSE(audiohub::DecodeGraphFrame(MakeFrame(1, kSelfA, kSelfA, {bad}), &out));
    bad.id = kNodeB;
    CHECK_FALSE(audiohub::DecodeGraphFrame(
        MakeRawFrame(1, kSelfA, kSelfA, {valid_node, "10.0.0.1:x,名,pc,47000,0"}), &out));
    CHECK_FALSE(audiohub::DecodeGraphFrame(MakeRawFrame(1, kSelfA, kSelfA, {"a;b"}), &out));
  }

  // 节点字段数不对 / 空节点段
  {
    std::string frame = std::string("GRAPH|1|a|a|") + "192.168.1.31:47000,手机,android,47000\n";
    CHECK_FALSE(audiohub::DecodeGraphFrame(frame, &out));
    frame = std::string("GRAPH|1|a|a|") + kNodeB + ",手机,android,47000,0;;" + kNodeC +
            ",平板,android,47000,0\n";
    CHECK_FALSE(audiohub::DecodeGraphFrame(frame, &out));
  }
}

// origin/hubId 会原样写进 /api/state 的 JSON：控制字符/引号/反斜杠的帧整帧丢弃，
// 否则远端一帧脏数据就能让本地 HTTP 接口返回非法 JSON。
TEST(Graph_DecodeRejectsUnsafeOriginOrHub) {
  audiohub::GraphState out;
  CHECK_TRUE(audiohub::DecodeGraphFrame(MakeRawFrame(1, kSelfA, kSelfA, {}), &out));

  const std::string quote_origin = std::string(kSelfA) + "\"x";
  const std::string backslash_hub = std::string(kSelfA) + "\\x";
  std::string ctrl_origin = kSelfA;
  ctrl_origin.push_back('\x01');

  CHECK_FALSE(audiohub::DecodeGraphFrame(MakeRawFrame(1, quote_origin, kSelfA, {}), &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame(MakeRawFrame(1, kSelfA, backslash_hub, {}), &out));
  CHECK_FALSE(audiohub::DecodeGraphFrame(MakeRawFrame(1, ctrl_origin, kSelfA, {}), &out));
  // 空 hubId 仍然合法（Android 未入网时的默认状态）
  CHECK_TRUE(audiohub::DecodeGraphFrame(MakeRawFrame(1, kSelfA, "", {}), &out));
}

TEST(Graph_DecodeRejectsTooManyNodesAndOversizeFrame) {
  std::vector<audiohub::GraphNode> many;
  for (int i = 0; i < 33; ++i) {
    many.push_back(MakeNode("10.0.0." + std::to_string(i + 1) + ":47000", "n", "pc", 47000, 0));
  }
  audiohub::GraphState out;
  CHECK_FALSE(audiohub::DecodeGraphFrame(MakeFrame(1, kSelfA, kSelfA, many), &out));

  // 编码端防御：33 个节点只发出前 32 个，对端能收
  const auto g = MakeGraph(1, kSelfA, kSelfA, many);
  const std::string frame = audiohub::EncodeGraphFrame(g);
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame, &out));
  CHECK_EQ(out.nodes.size(), audiohub::kMaxGraphNodes);

  // 帧长 > 1000 直接丢
  CHECK_FALSE(audiohub::DecodeGraphFrame("GRAPH|1|" + std::string(1010, 'a') + "|x|\n", &out));
}

TEST(Graph_EncodeSortsNodesById) {
  std::vector<audiohub::GraphNode> nodes;
  nodes.push_back(MakeNode(kNodeC, "C", "pc", 47000, 0));
  nodes.push_back(MakeNode(kSelfA, "A", "pc", 47000, 0));
  nodes.push_back(MakeNode(kNodeB, "B", "pc", 47000, 0));
  const std::string frame = audiohub::EncodeGraphFrame(MakeGraph(1, kSelfA, kSelfA, nodes));

  // 线格式里的顺序必须按 id 升序，两端才能逐字节比较
  const size_t pos_a = frame.find(kSelfA);
  const size_t pos_b = frame.find(kNodeB);
  const size_t pos_c = frame.find(kNodeC);
  CHECK_TRUE(pos_a != std::string::npos);
  CHECK_TRUE(pos_a < pos_b);
  CHECK_TRUE(pos_b < pos_c);
}

TEST(Graph_EncodeTrimsTailNodesWhenTooLong) {
  // 20 个节点 × 每个约 80 字节 → 超过 1000 字节，按 id 升序从尾部裁掉一些。
  std::vector<audiohub::GraphNode> nodes;
  for (int i = 1; i <= 20; ++i) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "192.168.100.%d:47000", i);
    nodes.push_back(MakeNode(buf, "设备名称设备名称设备名称设备名称", "android", 47000, 0));
  }
  const std::string frame = audiohub::EncodeGraphFrame(MakeGraph(5, kSelfA, kSelfA, nodes));
  CHECK_TRUE(frame.size() <= audiohub::kMaxGraphFrameBytes);

  audiohub::GraphState out;
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame, &out));
  CHECK_TRUE(out.nodes.size() < nodes.size());
  CHECK_TRUE(!out.nodes.empty());
  // 保留的必须是按 id 升序的前缀（确定性裁剪）
  audiohub::GraphState sorted_state;
  sorted_state.nodes = nodes;
  audiohub::SortNodes(&sorted_state);
  for (size_t i = 0; i < out.nodes.size(); ++i) {
    CHECK_TRUE(out.nodes[i].id == sorted_state.nodes[i].id);
  }
}

TEST(Graph_SanitizeNameReplacesSeparatorsAndControls) {
  CHECK_TRUE(audiohub::SanitizeName("a|b,c;d\ne\rf") == "a_b_c_d_e_f");
  CHECK_TRUE(audiohub::SanitizeName(std::string("x\x01y")) == "x_y");
  CHECK_TRUE(audiohub::SanitizeName("客厅PC") == "客厅PC");
  CHECK_TRUE(audiohub::SanitizeName("").empty());
}

TEST(Graph_SanitizeNameTruncatesOnUtf8Boundary) {
  // 每个汉字 3 字节：14 个 42 字节，必须截到 13 个（39 字节），不能切出半个字
  const std::string chinese = "客厅电脑客厅电脑客厅电脑客厅电脑";
  const std::string out = audiohub::SanitizeName(chinese);
  CHECK_EQ(out.size(), 39);
  CHECK_TRUE(out == chinese.substr(0, 39));
  // 输出本身必须是合法 UTF-8（用最粗暴的续字节检查兜底）
  for (size_t i = 0; i < out.size(); ++i) {
    CHECK_TRUE((static_cast<unsigned char>(out[i]) & 0xC0) != 0x80 || i > 0);
  }

  // 40 字节以内：原样保留
  const std::string fits = "125.1.31:47000";  // ASCII 场景
  CHECK_TRUE(audiohub::SanitizeName(fits) == fits);
}

TEST(Graph_SanitizeNameReplacesInvalidUtf8) {
  // 截断的 UTF-8 序列 / 非法起始字节 → '_'（否则 /api/state 的 JSON 非法）
  // 逐字节替换：残缺的 3 字节序列两个坏字节各换一个 '_'
  CHECK_TRUE(audiohub::SanitizeName(std::string("\xE4\xB8", 2)) == "__");
  CHECK_TRUE(audiohub::SanitizeName(std::string("\xFF", 1)) == "_");
  CHECK_TRUE(audiohub::SanitizeName("a\xE4\xB8\xAD") == "a中");
  CHECK_TRUE(audiohub::SanitizeName(std::string("a\xC3", 2)) == "a_");
}

TEST(Graph_EncodeSanitizesNameAndKind) {
  auto node = MakeNode(kNodeB, "a|b,c;d", "phone", 47000, 0);
  const std::string frame = audiohub::EncodeGraphFrame(MakeGraph(1, kSelfA, kSelfA, {node}));
  CHECK_TRUE(frame.find("a_b_c_d") != std::string::npos);
  CHECK_TRUE(frame.find(",other,") != std::string::npos);  // 不认识的 kind 归一成 other
}

TEST(Graph_MergeUnionKeepsLocalAndSorts) {
  auto local = MakeGraph(2, kSelfA, kSelfA, {MakeNode(kSelfA, "PC", "pc", 47000, 4780)});
  const auto remote =
      MakeGraph(1, kNodeB, kNodeB, {MakeNode(kNodeB, "手机", "android", 47000, 0),
                                    MakeNode(kSelfA, "别人的名字", "pc", 47000, 4780)});
  const auto r = audiohub::MergeGraph(&local, remote);
  CHECK_TRUE(r.changed);
  CHECK_TRUE(r.nodes_changed);
  CHECK_FALSE(r.hub_changed);
  CHECK_EQ(local.nodes.size(), 2);
  CHECK_TRUE(NodesSorted(local));
  CHECK_EQ(local.version, 2);            // 对方版本更低：hub 信息不采纳
  CHECK_TRUE(local.hub_id == kSelfA);    // 但节点照样取并集
  const auto* self = audiohub::FindNode(local, kSelfA);
  CHECK_TRUE(self != nullptr);
  if (self != nullptr) CHECK_TRUE(self->name == "PC");  // 同 id 冲突保留 local
}

TEST(Graph_MergeAdoptsHigherVersion) {
  auto local = MakeGraph(2, kSelfA, kSelfA, {MakeNode(kSelfA, "PC", "pc", 47000, 4780)});
  const auto remote = MakeGraph(5, kNodeB, kNodeB, {MakeNode(kNodeB, "手机", "android", 47000, 0)});
  const auto r = audiohub::MergeGraph(&local, remote);
  CHECK_TRUE(r.changed);
  CHECK_TRUE(r.nodes_changed);
  CHECK_TRUE(r.hub_changed);
  CHECK_EQ(local.version, 5);
  CHECK_TRUE(local.hub_id == kNodeB);
  CHECK_TRUE(local.origin == kNodeB);
}

TEST(Graph_MergeSameVersionOriginTieBreak) {
  // origin 字典序更大的一方胜
  auto local = MakeGraph(5, kSelfA, kSelfA, {MakeNode(kSelfA, "PC", "pc", 47000, 4780),
                                              MakeNode(kNodeB, "手机", "android", 47000, 0)});
  const auto remote = MakeGraph(5, kNodeB, kNodeB, {MakeNode(kNodeB, "手机", "android", 47000, 0)});
  auto r = audiohub::MergeGraph(&local, remote);
  CHECK_TRUE(r.changed);
  CHECK_TRUE(r.hub_changed);
  CHECK_TRUE(local.hub_id == kNodeB);
  CHECK_TRUE(local.origin == kNodeB);
  CHECK_EQ(local.version, 5);

  // 反过来（remote.origin 更小）保持本地
  auto local2 = MakeGraph(5, kNodeC, kNodeC, {MakeNode(kNodeC, "C", "pc", 47000, 0)});
  const auto remote2 = MakeGraph(5, kSelfA, kSelfA, {});
  r = audiohub::MergeGraph(&local2, remote2);
  CHECK_FALSE(r.changed);
  CHECK_FALSE(r.hub_changed);
  CHECK_TRUE(local2.hub_id == kNodeC);
  CHECK_TRUE(local2.origin == kNodeC);
}

TEST(Graph_MergeSameVersionSameOriginIsNoop) {
  auto local = MakeGraph(5, kSelfA, kSelfA, {MakeNode(kSelfA, "PC", "pc", 47000, 4780),
                                              MakeNode(kNodeB, "手机", "android", 47000, 0)});
  const auto before = local;
  const auto remote = MakeGraph(5, kSelfA, kNodeB, {MakeNode(kNodeB, "手机", "android", 47000, 0)});
  const auto r = audiohub::MergeGraph(&local, remote);
  CHECK_FALSE(r.changed);
  CHECK_FALSE(r.nodes_changed);
  CHECK_FALSE(r.hub_changed);
  CHECK_TRUE(local.hub_id == before.hub_id);
  CHECK_TRUE(local.origin == before.origin);
  CHECK_EQ(local.nodes.size(), before.nodes.size());
}

TEST(Graph_MergeIgnoresInvalidRemoteNodeIds) {
  auto local = MakeGraph(1, kSelfA, kSelfA, {});
  audiohub::GraphState remote;
  remote.version = 1;
  remote.origin = kSelfA;
  remote.nodes.push_back(MakeNode("no-port", "x", "pc", 47000, 0));
  const auto r = audiohub::MergeGraph(&local, remote);
  CHECK_FALSE(r.changed);
  CHECK_TRUE(local.nodes.empty());
}

TEST(Graph_UpsertNode) {
  audiohub::GraphState g;
  CHECK_TRUE(audiohub::UpsertNode(&g, MakeNode(kNodeB, "手机", "android", 47000, 0)));
  CHECK_EQ(g.nodes.size(), 1);
  CHECK_FALSE(audiohub::UpsertNode(&g, MakeNode(kNodeB, "新名字", "pc", 47000, 0)));  // 已存在
  CHECK_TRUE(g.nodes[0].name == "手机");
  CHECK_FALSE(audiohub::UpsertNode(&g, MakeNode("bad-id", "x", "pc", 47000, 0)));
  CHECK_FALSE(audiohub::UpsertNode(&g, MakeNode("", "x", "pc", 47000, 0)));

  // 端口以 id 为准，避免两个字段打架
  auto node = MakeNode("10.1.1.1:48000", "别名", "pc", 0, 0);
  CHECK_TRUE(audiohub::UpsertNode(&g, node));
  const auto* added = audiohub::FindNode(g, "10.1.1.1:48000");
  CHECK_TRUE(added != nullptr);
  if (added != nullptr) CHECK_EQ(added->audio_port, 48000);
  CHECK_TRUE(NodesSorted(g));

  // 节点数上限
  for (int i = 0; i < 40; ++i) {
    audiohub::UpsertNode(&g, MakeNode("10.9.0." + std::to_string(i) + ":47000", "x", "pc", 47000, 0));
  }
  CHECK_EQ(g.nodes.size(), audiohub::kMaxGraphNodes);
}

TEST(Graph_SwitchHub) {
  auto g = MakeGraph(4, kSelfA, kSelfA, {MakeNode(kSelfA, "PC", "pc", 47000, 4780),
                                          MakeNode(kNodeB, "手机", "android", 47000, 0)});
  CHECK_TRUE(audiohub::SwitchHub(&g, kSelfA, kNodeB));
  CHECK_EQ(g.version, 5);  // 本地改动 = 版本 +1
  CHECK_TRUE(g.origin == kSelfA);
  CHECK_TRUE(g.hub_id == kNodeB);
  CHECK_FALSE(audiohub::IsHub(g, kSelfA));
  CHECK_TRUE(audiohub::IsHub(g, kNodeB));

  // 未知节点 / 空参数拒绝，且不改状态
  const auto before = g;
  CHECK_FALSE(audiohub::SwitchHub(&g, kSelfA, "192.168.1.99:47000"));
  CHECK_FALSE(audiohub::SwitchHub(&g, kSelfA, ""));
  CHECK_FALSE(audiohub::SwitchHub(&g, "", kNodeB));
  CHECK_EQ(g.version, before.version);
  CHECK_TRUE(g.hub_id == before.hub_id);
}

TEST(Graph_FindNodeAndIp) {
  const auto g = MakeGraph(1, kSelfA, kSelfA, {MakeNode(kSelfA, "PC", "pc", 47000, 4780),
                                                MakeNode(kNodeB, "手机", "android", 47000, 0)});
  CHECK_TRUE(audiohub::FindNode(g, kNodeB) != nullptr);
  CHECK_TRUE(audiohub::FindNode(g, "null") == nullptr);
  const auto* by_ip = audiohub::FindNodeByIp(g, "192.168.1.31");
  CHECK_TRUE(by_ip != nullptr);
  if (by_ip != nullptr) CHECK_TRUE(by_ip->id == kNodeB);
  CHECK_TRUE(audiohub::FindNodeByIp(g, "192.168.1.99") == nullptr);
  CHECK_TRUE(audiohub::FindNodeByIp(g, "") == nullptr);
}

TEST(Graph_StreamIdForRank) {
  audiohub::GraphState g;
  g.nodes = {MakeNode(kNodeC, "C", "pc", 47000, 0), MakeNode(kSelfA, "A", "pc", 47000, 0),
             MakeNode(kNodeB, "B", "pc", 47000, 0)};  // 故意乱序：rank 按 id 算，不依赖顺序
  CHECK_EQ(audiohub::StreamIdFor(g, kSelfA), 1);   // A 是 id 最小的
  CHECK_EQ(audiohub::StreamIdFor(g, kNodeB), 2);
  CHECK_EQ(audiohub::StreamIdFor(g, kNodeC), 3);
  CHECK_EQ(audiohub::StreamIdFor(g, "10.0.0.1:47000"), 0);  // 不在图谱里
}

TEST(Graph_IsGraphRequestFrame) {
  CHECK_TRUE(audiohub::IsGraphRequestFrame("GRAPH-REQ\n"));
  CHECK_TRUE(audiohub::IsGraphRequestFrame("GRAPH-REQ\r\n"));
  CHECK_TRUE(audiohub::IsGraphRequestFrame("GRAPH-REQ"));
  CHECK_FALSE(audiohub::IsGraphRequestFrame("GRAPH-RE"));
  CHECK_FALSE(audiohub::IsGraphRequestFrame("GRAPH-REQ extra"));
  CHECK_FALSE(audiohub::IsGraphRequestFrame("GRAPH|1|||\n"));
}

TEST(Graph_DecodeKeepsFirstDuplicateId) {
  const std::string frame = MakeFrame(1, kSelfA, kSelfA, {MakeNode(kNodeB, "第一个", "android", 47000, 0),
                                                           MakeNode(kNodeB, "第二个", "pc", 47000, 0)});
  audiohub::GraphState out;
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame, &out));
  CHECK_EQ(out.nodes.size(), 1);
  CHECK_TRUE(out.nodes[0].name == "第一个");
}

TEST(Graph_DecodeNormalizesKindAndPortFromId) {
  // 线格式里 kind 未知 → other；audioPort 与 id 打架 → 以 id 为准
  const std::string frame = MakeFrame(1, kSelfA, kSelfA, {MakeNode(kNodeB, "手机", "phone", 12345, 0)});
  audiohub::GraphState out;
  CHECK_TRUE(audiohub::DecodeGraphFrame(frame, &out));
  CHECK_EQ(out.nodes.size(), 1);
  CHECK_TRUE(out.nodes[0].kind == "other");
  CHECK_EQ(out.nodes[0].audio_port, 47000);  // id 里的端口
}

// ------------------------------------------------------------------ 房间摆位（POS）

namespace {

audiohub::PlacementItem MakePlacement(const std::string& id, int x_cm, int z_cm) {
  audiohub::PlacementItem it;
  it.node_id = id;
  it.x_cm = static_cast<int16_t>(x_cm);
  it.z_cm = static_cast<int16_t>(z_cm);
  return it;
}

audiohub::PlacementState MakePlacementState(uint64_t version, const std::string& origin,
                                            const std::vector<audiohub::PlacementItem>& items) {
  audiohub::PlacementState p;
  p.version = version;
  p.origin = origin;
  p.items = items;
  return p;
}

bool PlacementItemsSorted(const audiohub::PlacementState& p) {
  for (size_t i = 1; i < p.items.size(); ++i) {
    if (!(p.items[i - 1].node_id < p.items[i].node_id)) return false;
  }
  return true;
}

}  // namespace

TEST(Placement_RoundTripKeepsItemsAndSorts) {
  const auto p = MakePlacementState(7, kSelfA,
                                    {MakePlacement(kNodeC, -123, 45),
                                     MakePlacement(kNodeB, 200, -150)});
  const std::string frame = audiohub::EncodePlacementFrame(p);
  CHECK_TRUE(frame.compare(0, 8, "POS|7|19") == 0 || frame.compare(0, 4, "POS|") == 0);
  CHECK_TRUE(frame.back() == '\n');

  audiohub::PlacementState out;
  CHECK_TRUE(audiohub::DecodePlacementFrame(frame, &out));
  CHECK_EQ(out.version, 7);
  CHECK_TRUE(out.origin == kSelfA);
  CHECK_EQ(out.items.size(), 2);
  CHECK_TRUE(PlacementItemsSorted(out));
  CHECK_TRUE(out.items[0].node_id == kNodeB);  // 按 id 升序
  CHECK_EQ(out.items[0].x_cm, 200);
  CHECK_EQ(out.items[0].z_cm, -150);
  CHECK_EQ(out.items[1].x_cm, -123);
  CHECK_EQ(out.items[1].z_cm, 45);
}

TEST(Placement_EmptyTableIsLegal) {
  // 用户清空所有摆位后：version>0 + 空表，必须能编码/解析出来。
  const auto p = MakePlacementState(9, kSelfA, {});
  const std::string frame = audiohub::EncodePlacementFrame(p);
  CHECK_TRUE(frame == "POS|9|" + std::string(kSelfA) + "|\n");
  audiohub::PlacementState out;
  CHECK_TRUE(audiohub::DecodePlacementFrame(frame, &out));
  CHECK_EQ(out.version, 9);
  CHECK_EQ(out.items.size(), 0);
}

TEST(Placement_DecodeRejectsBadFrames) {
  audiohub::PlacementState out;
  const std::string id = kNodeB;
  auto frame = [&id](const std::string& items) { return "POS|1||" + items + "\n"; };
  // 前缀 / 段数
  CHECK_FALSE(audiohub::DecodePlacementFrame("", &out));
  CHECK_FALSE(audiohub::DecodePlacementFrame("GRAPH|1|||\n", &out));
  CHECK_FALSE(audiohub::DecodePlacementFrame("POS|1|\n", &out));            // 只有 3 段
  CHECK_FALSE(audiohub::DecodePlacementFrame("POS|1|||\n", &out));          // 5 段
  CHECK_FALSE(audiohub::DecodePlacementFrame("POS|x||\n", &out));           // 版本非法
  CHECK_FALSE(audiohub::DecodePlacementFrame("POS|1|a\tb|\n", &out));       // origin 含控制字符
  // 空表 + 空 origin（汇聚端还没定 id）是合法的：解析后没有摆位项
  CHECK_TRUE(audiohub::DecodePlacementFrame("POS|1||\n", &out));
  CHECK_EQ(out.items.size(), 0);
  // 节点项：字段数 / id / 坐标
  CHECK_FALSE(audiohub::DecodePlacementFrame(frame(id + ",1"), &out));
  CHECK_FALSE(audiohub::DecodePlacementFrame(frame("no-colon,1,2"), &out));
  CHECK_FALSE(audiohub::DecodePlacementFrame(frame(id + ",,2"), &out));
  CHECK_FALSE(audiohub::DecodePlacementFrame(frame(id + ",1,2;git"), &out));
  CHECK_FALSE(audiohub::DecodePlacementFrame(frame(id + ",2001,0"), &out));   // 超 ±20m
  CHECK_FALSE(audiohub::DecodePlacementFrame(frame(id + ",-2001,0"), &out));
  CHECK_FALSE(audiohub::DecodePlacementFrame(frame(id + ",1.5,0"), &out));    // 只收厘米整数
  // 正常值边界
  CHECK_TRUE(audiohub::DecodePlacementFrame(frame(id + ",2000,-2000"), &out));
  CHECK_EQ(out.items.size(), 1);
  if (out.items.size() == 1) {
    CHECK_EQ(out.items[0].x_cm, 2000);
    CHECK_EQ(out.items[0].z_cm, -2000);
  }
  // 重复 id 保留第一个
  CHECK_TRUE(audiohub::DecodePlacementFrame(frame(id + ",1,2;" + id + ",3,4"), &out));
  CHECK_EQ(out.items.size(), 1);
  if (out.items.size() == 1) CHECK_EQ(out.items[0].x_cm, 1);
  // 超长帧（>1000 字节）
  std::string huge = "POS|1||";
  for (int i = 0; i < 80; ++i) {
    if (i) huge += ';';
    huge += id + "," + std::to_string(i) + "," + std::to_string(i);
  }
  huge += "\n";
  CHECK_TRUE(huge.size() > audiohub::kMaxPlacementFrameBytes);
  CHECK_FALSE(audiohub::DecodePlacementFrame(huge, &out));
  // 项数超上限（但帧长没超）：也必须整帧丢弃
  std::string many = "POS|1||";
  for (size_t i = 0; i < audiohub::kMaxGraphNodes + 1; ++i) {
    if (i) many += ';';
    many += "10.0.0." + std::to_string(i + 1) + ":47000,1,1";
  }
  many += "\n";
  CHECK_TRUE(many.size() <= audiohub::kMaxPlacementFrameBytes);
  CHECK_FALSE(audiohub::DecodePlacementFrame(many, &out));
}

TEST(Placement_EncodeTrimsOverlongFrame) {
  // 项数上限 32（帧长上限 1000）：超限时按 id 升序从尾部裁，裁完仍必须能解析。
  std::vector<audiohub::PlacementItem> items;
  for (size_t i = 0; i < audiohub::kMaxGraphNodes + 8; ++i) {
    items.push_back(MakePlacement("192.168.100." + std::to_string(i + 1) + ":47000", i, i));
  }
  const auto p = MakePlacementState(3, kSelfA, items);
  const std::string frame = audiohub::EncodePlacementFrame(p);
  CHECK_TRUE(frame.size() <= audiohub::kMaxPlacementFrameBytes);
  audiohub::PlacementState out;
  CHECK_TRUE(audiohub::DecodePlacementFrame(frame, &out));
  CHECK_TRUE(PlacementItemsSorted(out));
  CHECK_EQ(out.items.size(), audiohub::kMaxGraphNodes);  // 裁到上限整好装下

  // 恰好等于上限时不该被裁
  std::vector<audiohub::PlacementItem> exact(items.begin(),
                                             items.begin() + audiohub::kMaxGraphNodes);
  const std::string frame2 = audiohub::EncodePlacementFrame(MakePlacementState(4, kSelfA, exact));
  audiohub::PlacementState out2;
  CHECK_TRUE(audiohub::DecodePlacementFrame(frame2, &out2));
  CHECK_EQ(out2.items.size(), audiohub::kMaxGraphNodes);
}

TEST(Placement_MergeWholeTableLww) {
  // 远端版本更大：整表采纳
  audiohub::PlacementState local =
      MakePlacementState(2, kNodeB, {MakePlacement(kNodeB, 100, 100)});
  const auto remote = MakePlacementState(3, kSelfA, {MakePlacement(kNodeC, -50, 60)});
  const auto r = audiohub::MergePlacement(&local, remote);
  CHECK_TRUE(r.changed);
  CHECK_TRUE(r.adopted);
  CHECK_TRUE(r.items_changed);
  CHECK_EQ(local.version, 3);
  CHECK_TRUE(local.origin == kSelfA);
  CHECK_EQ(local.items.size(), 1);
  CHECK_TRUE(local.items[0].node_id == kNodeC);
  CHECK_EQ(local.items[0].x_cm, -50);
  CHECK_EQ(local.items[0].z_cm, 60);

  // 旧版本：忽略
  const auto before = local;
  const auto r_old = audiohub::MergePlacement(&local, MakePlacementState(1, kSelfA, {}));
  CHECK_FALSE(r_old.changed);
  CHECK_TRUE(local.version == before.version);
  CHECK_EQ(local.items.size(), before.items.size());

  // 同版本：origin 字典序大者胜（与 MergeGraph 一致）
  auto a = MakePlacementState(5, "aaa:47000", {MakePlacement(kNodeB, 1, 1)});
  const auto b = MakePlacementState(5, "zzz:47000", {MakePlacement(kNodeB, 2, 2)});
  const auto r_tie = audiohub::MergePlacement(&a, b);
  CHECK_TRUE(r_tie.changed);
  CHECK_EQ(a.items[0].x_cm, 2);
  // 反过来（更小的 origin）不能覆盖
  const auto a2 = a;
  const auto r_smaller = audiohub::MergePlacement(&a, MakePlacementState(5, "aaa:47000",
                                                                         {MakePlacement(kNodeB, 9, 9)}));
  CHECK_FALSE(r_smaller.changed);
  CHECK_EQ(a.items[0].x_cm, a2.items[0].x_cm);

  // 同版本同 origin：忽略
  CHECK_FALSE(audiohub::MergePlacement(&a, a).changed);

  // 更高版本的空表 = 对方清空了所有摆位
  const auto r_clear = audiohub::MergePlacement(&a, MakePlacementState(6, kSelfA, {}));
  CHECK_TRUE(r_clear.changed);
  CHECK_EQ(a.items.size(), 0);
}

TEST(Placement_SetAndRemoveBumpsVersion) {
  audiohub::PlacementState p;
  CHECK_TRUE(audiohub::SetPlacementItem(&p, kNodeB, 100, -40, kSelfA));
  CHECK_EQ(p.version, 1);
  CHECK_TRUE(p.origin == kSelfA);
  CHECK_TRUE(audiohub::FindPlacement(p, kNodeB) != nullptr);

  // 同一坐标重复提交：不产生新版本（GUI 轮询/重试会重复发）
  CHECK_FALSE(audiohub::SetPlacementItem(&p, kNodeB, 100, -40, kSelfA));
  CHECK_EQ(p.version, 1);

  // 改坐标：版本 +1
  CHECK_TRUE(audiohub::SetPlacementItem(&p, kNodeB, 200, 80, kSelfA));
  CHECK_EQ(p.version, 2);
  CHECK_EQ(audiohub::FindPlacement(p, kNodeB)->x_cm, 200);

  // 越界 / 非法 id / 空 origin：拒绝且不改状态
  const auto before = p;
  CHECK_FALSE(audiohub::SetPlacementItem(&p, kNodeB, 2001, 0, kSelfA));
  CHECK_FALSE(audiohub::SetPlacementItem(&p, "no-colon", 0, 0, kSelfA));
  CHECK_FALSE(audiohub::SetPlacementItem(&p, kNodeB, 0, 0, ""));
  CHECK_EQ(p.version, before.version);

  // 删除：版本 +1；删不存在的返回 false
  CHECK_TRUE(audiohub::RemovePlacementItem(&p, kNodeB, kSelfA));
  CHECK_EQ(p.version, 3);
  CHECK_TRUE(audiohub::FindPlacement(p, kNodeB) == nullptr);
  CHECK_FALSE(audiohub::RemovePlacementItem(&p, kNodeB, kSelfA));
}

TEST(Placement_RequestFrameAndCrossTypeSafety) {
  CHECK_TRUE(audiohub::IsPlacementRequestFrame("POS-REQ\n"));
  CHECK_TRUE(audiohub::IsPlacementRequestFrame("POS-REQ\r\n"));
  CHECK_TRUE(audiohub::IsPlacementRequestFrame("POS-REQ"));
  CHECK_FALSE(audiohub::IsPlacementRequestFrame("POS-RE"));
  CHECK_FALSE(audiohub::IsPlacementRequestFrame("POS-REQ extra"));
  CHECK_FALSE(audiohub::IsPlacementRequestFrame("POS|1||\n"));
  // 两类帧不能互相冒充
  CHECK_FALSE(audiohub::IsGraphRequestFrame("POS-REQ"));
  CHECK_FALSE(audiohub::IsPlacementRequestFrame("GRAPH-REQ"));
  audiohub::GraphState g;
  CHECK_FALSE(audiohub::DecodeGraphFrame("POS|1||\n", &g));
  audiohub::PlacementState p;
  CHECK_FALSE(audiohub::DecodePlacementFrame(MakeFrame(1, kSelfA, kSelfA, {}), &p));
}