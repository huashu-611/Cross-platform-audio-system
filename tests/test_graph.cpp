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