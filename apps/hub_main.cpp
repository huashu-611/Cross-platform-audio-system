// audiohub-hub：汇聚端命令行版。
//
// 真正的逻辑都在 src/hub_core.cpp 里，这里只是薄壳——
// 命令行版和图形界面版必须共用同一套实现，否则"UI 上调了音量但
// 脚本测试时行为不一样"这类问题会反复出现。
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <sstream>
#include <string>
#include <vector>
#include <windows.h>
#include <io.h>
#include <shellapi.h>
#include <thread>

#include "audiohub/hub_core.hpp"
#include "audiohub/http_api.hpp"
#include "audiohub/udp_socket.hpp"

namespace {

// --log 的路径可能是 UTF-8 中文。Windows 的 CRT 会用 ANSI 代码页解释窄路径，
// 中文路径直接打不开；这里统一按 UTF-8 解码成宽字符再 _wfopen，然后 _dup2
// 到 stdout / stderr，行为与 freopen 等价。
bool RedirectStdioToFile(const std::string& utf8_path) {
  const int wide_len =
      MultiByteToWideChar(CP_UTF8, 0, utf8_path.c_str(), -1, nullptr, 0);
  if (wide_len <= 0) return false;
  std::wstring wide_path(static_cast<size_t>(wide_len), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, utf8_path.c_str(), -1, wide_path.data(), wide_len);
  FILE* file = _wfopen(wide_path.c_str(), L"w");
  if (file == nullptr) return false;
  const int fd = _fileno(file);
  const bool ok = _dup2(fd, _fileno(stdout)) == 0;
  if (ok) _dup2(fd, _fileno(stderr));
  std::fclose(file);  // dup 出来的描述符不受影响，stdout / stderr 继续有效
  return ok;
}

struct Options {
  uint16_t port = 47000;
  int latency_ms = 60;
  int render_latency_ms = 10;
  uint16_t http_port = 0;  // 0 = 不开 HTTP 接口
  bool no_discovery = false;
  bool no_handshake = false;
  bool auto_accept = true;
  std::string name = "AudioHub";
  std::string log_path;
};

void PrintUsage() {
  std::printf(
      "用法: audiohub-hub [选项]\n"
      "\n"
      "  --port <n>        监听端口，默认 47000（控制口自动用 port+1）\n"
      "  --latency <ms>    目标端到端延迟，默认 60。可选 40/60/120\n"
      "  --render-ms <n>   WASAPI 输出缓冲毫秒数，默认 10\n"
      "  --http-port <n>   打开本地 HTTP 接口（给图形界面用），默认关闭\n"
      "  --name <s>        本机显示名，默认 AudioHub\n"
      "  --no-discovery    关闭自动发现（只用手填 IP）\n"
      "  --no-handshake    关闭连接确认，任何包都直接收\n"
      "  --auto-accept     自动同意所有连接请求\n"
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
    } else if (a == "--name") {
      opt->name = next("--name");
    } else if (a == "--no-discovery") {
      opt->no_discovery = true;
    } else if (a == "--no-handshake") {
      opt->no_handshake = true;
    } else if (a == "--auto-accept") {
      opt->auto_accept = true;
    } else if (a == "--http-port") {
      opt->http_port = static_cast<uint16_t>(std::stoi(next("--http-port")));
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

// 图谱 JSON（docs/GRAPH-PROTOCOL.md §7.1）：节点上的"流字段"按 source.peer_ip
// 与节点 IP 匹配，没有流的节点给默认值，界面就不用判断 null 了。
// 只放在 hub_main 里：HubCore 保持平台无关，不依赖 JSON。
std::string GraphStateJson(const audiohub::HubCore& hub,
                           const std::vector<audiohub::SourceInfo>& sources,
                           const std::vector<audiohub::Peer>& discovered) {
  const audiohub::GraphState g = hub.Graph();
  const audiohub::PlacementState placement = hub.Placements();
  const std::string self_id = hub.graph_self_id();

  std::ostringstream o;
  o << "{\"version\":" << g.version << ",\"origin\":\"" << audiohub::JsonEscape(g.origin)
    << "\",\"hubId\":\"" << audiohub::JsonEscape(g.hub_id) << "\",\"selfId\":\""
    << audiohub::JsonEscape(self_id) << "\",\"isHub\":"
    << (hub.graph_is_hub() ? "true" : "false")
    << ",\"placementVersion\":" << placement.version << ",\"nodes\":[";
  for (size_t i = 0; i < g.nodes.size(); ++i) {
    const audiohub::GraphNode& n = g.nodes[i];
    // 节点 id 一定是 "ip:端口"；取最后一个冒号前的部分当 IP
    const size_t colon = n.id.rfind(':');
    const std::string ip = colon == std::string::npos ? n.id : n.id.substr(0, colon);

    // 活标志：本机恒在线；否则看音频流/发现列表/10s 内的 GRAPH（协议 §8）。
    //
    // v1.7 起不再看 session_peers：那是"连过"的历史表，以前只进不出，
    // 手机 App 都退了它还在，界面就永远显示在线，右键邀请过去也没人接。
    // 判活只认三类实时证据，任何一类断了就如实变离线。
    bool online = (n.id == self_id);
    if (!online) {
      for (const auto& s : sources) {
        if (s.peer_ip == ip) {
          online = true;
          break;
        }
      }
    }
    if (!online) {
      for (const auto& p : discovered) {
        if (p.ip == ip) {
          online = true;
          break;
        }
      }
    }
    if (!online) {
      const int64_t since_ms = hub.graph_peer_last_seen_ms(ip);
      if (since_ms >= 0 && since_ms < 10000) online = true;
    }

    // 承载的流 id：汇聚端不送流（0），其余节点按 id 升序的 rank+1（协议 §6）
    const uint16_t stream_id = (n.id == g.hub_id) ? 0 : audiohub::StreamIdFor(g, n.id);

    const audiohub::SourceInfo* src = nullptr;
    for (const auto& s : sources) {
      if (s.peer_ip == ip) {
        src = &s;
        break;
      }
    }

    if (i) o << ",";
    // 摆位（§13）：posSet=false 时界面用确定性自动槽位排布；true 时用 x/z（米）。
    const audiohub::PlacementItem* pos = audiohub::FindPlacement(placement, n.id);
    o << "{\"id\":\"" << audiohub::JsonEscape(n.id) << "\""
      << ",\"name\":\"" << audiohub::JsonEscape(n.name) << "\""
      << ",\"kind\":\"" << audiohub::JsonEscape(n.kind) << "\""
      << ",\"ip\":\"" << audiohub::JsonEscape(ip) << "\""
      << ",\"audioPort\":" << n.audio_port << ",\"httpPort\":" << n.http_port
      << ",\"online\":" << (online ? "true" : "false")
      << ",\"isSelf\":" << (n.id == self_id ? "true" : "false")
      << ",\"isHub\":" << (n.id == g.hub_id ? "true" : "false")
      << ",\"streamId\":" << stream_id
      << ",\"posSet\":" << (pos != nullptr ? "true" : "false");
    if (pos != nullptr) {
      o << ",\"x\":" << (static_cast<double>(pos->x_cm) / 100.0)
        << ",\"z\":" << (static_cast<double>(pos->z_cm) / 100.0);
    }
    o << ",\"level\":" << (src != nullptr ? src->level_dbfs : -120.0)
      << ",\"occupancy\":" << (src != nullptr ? src->occupancy_ms : 0)
      << ",\"gain\":" << (src != nullptr ? src->gain : 1.0)
      << ",\"muted\":" << ((src != nullptr && src->muted) ? "true" : "false") << "}";
  }
  o << "]}";
  return o.str();
}

}  // namespace

int main(int argc, char** argv) {
#ifdef _WIN32
  // Windows 的 argv 是 ANSI 码页（中文系统是 GBK），写进 JSON 就不是合法 UTF-8。
  // 统一转成 UTF-8，否则 --name 里带中文会让 /api/state 解析失败。
  {
    int w_argc = 0;
    LPWSTR* w_argv = CommandLineToArgvW(GetCommandLineW(), &w_argc);
    if (w_argv != nullptr && w_argc > 0) {
      static std::vector<std::string> utf8_args;
      static std::vector<char*> utf8_argp;
      // 先 reserve：机造成 vector 扩容时，之前 push 进 utf8_argp 的 data() 指针
      // 会全部悬空，argv 里随即出现乱码/“未知参数”（起两个进程时尤其容易撞上）。
      utf8_args.reserve(static_cast<size_t>(w_argc));
      utf8_argp.reserve(static_cast<size_t>(w_argc) + 1);
      for (int i = 0; i < w_argc; ++i) {
        const int n = WideCharToMultiByte(CP_UTF8, 0, w_argv[i], -1, nullptr, 0, nullptr, nullptr);
        utf8_args.emplace_back(n > 0 ? static_cast<size_t>(n - 1) : 0, '\0');
        if (n > 0) {
          WideCharToMultiByte(CP_UTF8, 0, w_argv[i], -1, utf8_args.back().data(), n, nullptr,
                              nullptr);
        }
        utf8_argp.push_back(utf8_args.back().data());
      }
      utf8_argp.push_back(nullptr);
      LocalFree(w_argv);
      argc = w_argc;
      argv = utf8_argp.data();
    }
  }
#endif

  Options opt;
  if (!ParseArgs(argc, argv, &opt)) return 1;

  if (!opt.log_path.empty()) {
    if (!RedirectStdioToFile(opt.log_path)) {
      std::printf("无法打开日志文件: %s\n", opt.log_path.c_str());
      return 1;
    }
  }
  // 行缓冲：进程被强杀时不会丢掉最后几 KB 输出
  std::setvbuf(stdout, nullptr, _IOLBF, 0);

  audiohub::HubCore hub;
  hub.SetEventCallback([](const std::string& text) { std::printf("%s\n", text.c_str()); });

  audiohub::StartOptions opts;
  opts.advertise = !opt.no_discovery;
  opts.require_handshake = !opt.no_handshake;
  opts.auto_accept = opt.auto_accept;
  opts.display_name = opt.name;
  opts.http_port = opt.http_port;  // 图谱里要暴露给对端的 HTTP 端口（Android 用不上时为 0）

  std::string error;
  if (!hub.Start(opt.port, opt.latency_ms, opt.render_latency_ms, opts, &error)) {
    std::printf("%s\n", error.c_str());
    return 2;
  }

  // 供 HTTP 接口里的 /api/state 用：只取第一个 IP（不带 "127.0.0.1" 那个）
  std::string ip_only;

  // ---- 本地 HTTP 接口（图形界面走这条）----
  // 只绑 127.0.0.1：这是本机控制通道，不该暴露到局域网。
  std::unique_ptr<audiohub::HttpApi> http;
  if (opt.http_port != 0) {
    http = std::make_unique<audiohub::HttpApi>();
    std::string http_error;
    if (!http->Start(opt.http_port,
                     [&](const std::string& method, const std::string& path,
                         const std::string& body) -> std::string {
                       if (path == "/api/state") {
                         const auto stats = hub.Stats();
                         const auto sources = hub.Sources();
                         // 图谱渲染也要用这两份，顺手只取一次（Sources() 会带走电平快照）
                         const auto discovered = hub.DiscoveredPeers();
                         const auto session_peers = hub.SessionPeers();

                         // 收包速率：接口被轮询的频率不固定，用增量/时间算
                         static uint64_t last_total = 0;
                         static double smoothed_kbps = 0;
                         static auto last_tick = std::chrono::steady_clock::now();
                         const auto now = std::chrono::steady_clock::now();
                         const double dt =
                             std::chrono::duration<double>(now - last_tick).count();
                         uint64_t total = 0;
                         for (const auto& s : sources) total += s.packets;
                         if (dt > 0.05) {
                           const double instant =
                               static_cast<double>(total - last_total) * 976.0 * 8.0 / 1000.0 / dt;
                           smoothed_kbps = smoothed_kbps * 0.7 + instant * 0.3;
                           last_total = total;
                           last_tick = now;
                         }

                         std::ostringstream o;
                         o << "{\"ip\":\"" << audiohub::JsonEscape(ip_only) << ":"
                           << opt.port << "\",\"latency\":" << stats.latency_ms
                           << ",\"master\":" << hub.master_gain()
                           << ",\"busLevel\":" << stats.bus_level_dbfs
                           << ",\"kbps\":" << smoothed_kbps
                           << ",\"malformed\":" << stats.malformed_packets
                           << ",\"renderUnderruns\":" << stats.render_underruns
                           << ",\"autoAccept\":" << (hub.auto_accept() ? "true" : "false")
                           << ",\"peers\":[";
                         {
                           const auto peers = hub.DiscoveredPeers();
                           for (size_t i = 0; i < peers.size(); ++i) {
                             const auto& p = peers[i];
                             if (i) o << ",";
                             o << "{\"name\":\"" << audiohub::JsonEscape(p.name) << "\","
                               << "\"ip\":\"" << p.ip << "\",\"audioPort\":" << p.audio_port
                               << ",\"canReceive\":" << (p.can_receive ? "true" : "false")
                               << ",\"canSend\":" << (p.can_send ? "true" : "false") << "}";
                           }
                         }
                         o << "],\"pending\":[";
                         {
                           const auto pending = hub.PendingConnections();
                           for (size_t i = 0; i < pending.size(); ++i) {
                             const auto& p = pending[i];
                             if (i) o << ",";
                             o << "{\"streamId\":" << p.stream_id
                               << ",\"name\":\"" << audiohub::JsonEscape(p.peer_name) << "\","
                               << "\"ip\":\"" << p.peer_ip << "\"}";
                           }
                         }
                         o << "],\"sessionPeers\":[";
                         {
                           const auto& sp = session_peers;
                           for (size_t i = 0; i < sp.size(); ++i) {
                             if (i) o << ",";
                             o << "{\"name\":\"" << audiohub::JsonEscape(sp[i].name) << "\","
                               << "\"ip\":\"" << sp[i].ip << "\","
                               << "\"audioPort\":" << sp[i].audio_port << "}";
                           }
                         }
                         o << "],\"invites\":[";
                         {
                           const auto invites = hub.Invites();
                           for (size_t i = 0; i < invites.size(); ++i) {
                             if (i) o << ",";
                             o << "{\"name\":\"" << audiohub::JsonEscape(invites[i].name) << "\","
                               << "\"ip\":\"" << invites[i].ip << "\"}";
                           }
                         }
                         o << "],\"sources\":[";
                         for (size_t i = 0; i < sources.size(); ++i) {
                           const auto& s = sources[i];
                           if (i) o << ",";
                           o << "{\"id\":" << s.id << ",\"level\":" << s.level_dbfs
                             << ",\"gain\":" << s.gain
                             << ",\"muted\":" << (s.muted ? "true" : "false")
                             << ",\"confirmed\":" << (s.confirmed ? "true" : "false")
                             << ",\"peerName\":\"" << audiohub::JsonEscape(s.peer_name) << "\""
                             << ",\"peerIp\":\"" << audiohub::JsonEscape(s.peer_ip) << "\""
                             << ",\"occupancy\":" << s.occupancy_ms
                             << ",\"target\":" << s.target_ms
                              << ",\"catchup\":" << s.catchup_count
                              << ",\"late\":" << s.late_packets
                              << ",\"lost\":" << s.lost_packets
                              << ",\"packets\":" << s.packets
                             << ",\"underrun\":" << s.underrun_frames
                             << ",\"overflow\":" << s.overflow_packets << "}";
                         }
                         o << "],\"graph\":"
                           << GraphStateJson(hub, sources, discovered) << "}";
                         return o.str();
                       }

                       if (method == "POST" && path == "/api/source") {
                         double v = 0;
                         bool b = false;
                         uint16_t id = 0;
                         if (audiohub::JsonFindNumber(body, "id", &v)) {
                           id = static_cast<uint16_t>(v);
                         }
                         if (audiohub::JsonFindNumber(body, "gain", &v)) {
                           hub.SetSourceGain(id, static_cast<float>(v));
                         }
                         if (audiohub::JsonFindBool(body, "muted", &b)) {
                           hub.SetSourceMuted(id, b);
                         }
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/master") {
                         double v = 0;
                         if (audiohub::JsonFindNumber(body, "gain", &v)) {
                           hub.SetMasterGain(static_cast<float>(v));
                         }
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/accept") {
                         double v = 0;
                         if (audiohub::JsonFindNumber(body, "streamId", &v)) {
                           hub.AcceptConnection(static_cast<uint16_t>(v));
                         }
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/reject") {
                         double v = 0;
                         if (audiohub::JsonFindNumber(body, "streamId", &v)) {
                           hub.RejectConnection(static_cast<uint16_t>(v));
                         }
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/disconnect") {
                         double v = 0;
                         if (audiohub::JsonFindNumber(body, "streamId", &v)) {
                           hub.Disconnect(static_cast<uint16_t>(v));
                         }
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/clear-session") {
                         hub.ClearSession();
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/auto-accept") {
                         bool b = false;
                         if (audiohub::JsonFindBool(body, "on", &b)) hub.SetAutoAccept(b);
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/accept-invite") {
                         std::string ip;
                         if (!audiohub::JsonGetString(body, "ip", &ip) || ip.empty()) {
                           return "{\"ok\":false,\"error\":\"ip missing\"}";
                         }
                         hub.AcceptInvite(ip);
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/reject-invite") {
                         std::string ip;
                         if (!audiohub::JsonGetString(body, "ip", &ip) || ip.empty()) {
                           return "{\"ok\":false,\"error\":\"ip missing\"}";
                         }
                         hub.RejectInvite(ip);
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/role") {
                         std::string ip;
                         std::string hub_ip;
                         double audio_port = 0;
                         double hub_port = 0;
                         double is_hub = 1;
                         if (!audiohub::JsonGetString(body, "ip", &ip) ||
                             !audiohub::JsonFindNumber(body, "audioPort", &audio_port)) {
                           return "{\"ok\":false,\"error\":\"ip/audioPort missing\"}";
                         }
                         audiohub::JsonGetString(body, "hubIp", &hub_ip);
                         audiohub::JsonFindNumber(body, "hubPort", &hub_port);
                         if (!audiohub::JsonFindNumber(body, "hub", &is_hub)) is_hub = 1;
                         const bool ok = hub.SendRoleCommand(
                             ip, static_cast<uint16_t>(audio_port), is_hub != 0, hub_ip,
                             static_cast<uint16_t>(hub_port));
                         return ok ? "{\"ok\":true}" : "{\"ok\":false}";
                       }
                       if (method == "POST" && path == "/api/refresh") {
                         hub.RefreshDiscovery();
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/invite") {
                         std::string sip;
                         double v = 0;
                         if (audiohub::JsonGetString(body, "ip", &sip) &&
                             audiohub::JsonFindNumber(body, "audioPort", &v)) {
                           audiohub::Peer peer;
                           peer.ip = sip;
                           peer.audio_port = static_cast<uint16_t>(v);
                           return hub.InvitePeer(peer) ? "{\"ok\":true}" : "{\"ok\":false}";
                         }
                         return "{\"ok\":false,\"error\":\"ip/audioPort missing\"}";
                       }
                       if (method == "POST" && path == "/api/latency") {
                         double v = 0;
                         if (audiohub::JsonFindNumber(body, "ms", &v)) {
                           hub.SetLatency(static_cast<int>(v));
                         }
                         return "{\"ok\":true}";
                       }
                       if (method == "POST" && path == "/api/graph/switch") {
                          std::string node_id;
                          if (!audiohub::JsonGetString(body, "nodeId", &node_id) ||
                              node_id.empty()) {
                            return "{\"ok\":false,\"error\":\"unknown node\"}";
                          }
                          // 节点不存在时协议要求明确的 unknown node（§7.2）
                          if (!hub.SwitchGraphHub(node_id)) {
                            return "{\"ok\":false,\"error\":\"unknown node\"}";
                          }
                          std::ostringstream o;
                          o << "{\"ok\":true,\"graph\":"
                            << GraphStateJson(hub, hub.Sources(), hub.DiscoveredPeers())
                            << "}";
                          return o.str();
                        }
                        if (method == "POST" && path == "/api/graph/sync") {
                          hub.SyncGraphNow();
                          const auto g = hub.Graph();
                          // sent = 实际发出的广播份数（不含自己）
                          const size_t sent = g.nodes.empty() ? 0 : g.nodes.size() - 1;
                          std::ostringstream o;
                          o << "{\"ok\":true,\"sent\":" << sent << "}";
                          return o.str();
                        }
                        if (method == "POST" && path == "/api/graph/placement") {
                          // 房间摆位（协议 §13）：桌面端拖拽落点 / 清除，由本机内核
                          // version+1 后广播给图谱里所有节点（手机端只读显示）。
                          std::string node_id;
                          if (!audiohub::JsonGetString(body, "nodeId", &node_id) ||
                              node_id.empty()) {
                            return "{\"ok\":false,\"error\":\"nodeId missing\"}";
                          }
                          bool known = false;
                          for (const auto& n : hub.Graph().nodes) {
                            if (n.id == node_id) { known = true; break; }
                          }
                          if (!known) return "{\"ok\":false,\"error\":\"node not found\"}";
                          bool clear = false;
                          audiohub::JsonFindBool(body, "clear", &clear);
                          if (clear) {
                            // 没摆过 / 已经是自动槽位：当成功（幂等，GUI 重试不会报错）
                            hub.ClearNodePlacement(node_id);
                          } else {
                            double x = 0, z = 0;
                            if (!audiohub::JsonFindNumber(body, "x", &x) ||
                                !audiohub::JsonFindNumber(body, "z", &z)) {
                              return "{\"ok\":false,\"error\":\"need x/z\"}";
                            }
                            if (!(std::isfinite(x) && std::isfinite(z)) ||
                                !(x >= -audiohub::kSpatialCoordLimit &&
                                  x <= audiohub::kSpatialCoordLimit) ||
                                !(z >= -audiohub::kSpatialCoordLimit &&
                                  z <= audiohub::kSpatialCoordLimit)) {
                              return "{\"ok\":false,\"error\":\"coord out of range\"}";
                            }
                            // 坐标与现有值相同 → 内核不造新版本（避免两端互相回发）
                            hub.SetNodePlacement(node_id, static_cast<float>(x),
                                                 static_cast<float>(z));
                          }
                          std::ostringstream o;
                          o << "{\"ok\":true,\"placementVersion\":" << hub.placement_version()
                            << "}";
                          return o.str();
                        }
                        if (method == "POST" && path == "/api/session/clear") {
                          // 与旧 /api/clear-session 同义；ClearSession 里额外重置图谱（§4.6）
                          hub.ClearSession();
                          return "{\"ok\":true}";
                        }
                        if (method == "POST" && path == "/api/listener") {
                          double x = 0, y = 0, z = 0, yaw = 0, pitch = 0;
                          bool has_pos = audiohub::JsonFindNumber(body, "x", &x);
                          has_pos = audiohub::JsonFindNumber(body, "y", &y) || has_pos;
                          has_pos = audiohub::JsonFindNumber(body, "z", &z) || has_pos;
                          audiohub::JsonFindNumber(body, "yaw", &yaw);
                          audiohub::JsonFindNumber(body, "pitch", &pitch);
                          if (!has_pos) return "{\"ok\":false,\"error\":\"need x/y/z\"}";
                          audiohub::ListenerPose pose;
                          pose.position = audiohub::Vec3{
                              static_cast<float>(std::clamp(x, -static_cast<double>(audiohub::kSpatialCoordLimit), static_cast<double>(audiohub::kSpatialCoordLimit))),
                              static_cast<float>(std::clamp(y, -static_cast<double>(audiohub::kSpatialCoordLimit), static_cast<double>(audiohub::kSpatialCoordLimit))),
                              static_cast<float>(std::clamp(z, -static_cast<double>(audiohub::kSpatialCoordLimit), static_cast<double>(audiohub::kSpatialCoordLimit)))};
                          pose.yaw_deg = static_cast<float>(std::clamp(yaw, -180.0, 180.0));
                          pose.pitch_deg = static_cast<float>(std::clamp(pitch, -90.0, 90.0));
                          hub.SetListenerPose(pose, true);
                          return "{\"ok\":true}";
                        }
                        if (method == "POST" && path == "/api/source/spatial") {
                          std::string node_id;
                          if (!audiohub::JsonGetString(body, "nodeId", &node_id) ||
                              node_id.empty()) {
                            return "{\"ok\":false,\"error\":\"nodeId missing\"}";
                          }
                          double x = 0, y = 0, z = 0;
                          if (!audiohub::JsonFindNumber(body, "x", &x) ||
                              !audiohub::JsonFindNumber(body, "y", &y) ||
                              !audiohub::JsonFindNumber(body, "z", &z)) {
                            return "{\"ok\":false,\"error\":\"need x/y/z\"}";
                          }
                          bool enabled = true;
                          audiohub::JsonFindBool(body, "enabled", &enabled);
                          // 范围与内核/协议一致：每个分量 ∈[-kSpatialCoordLimit, kSpatialCoordLimit]。
                          // 用 !(x>=lo && x<=hi) 让 NaN 也走拒绝分支。
                          const double lim = audiohub::kSpatialCoordLimit;
                          if (!(x >= -lim && x <= lim) || !(y >= -lim && y <= lim) ||
                              !(z >= -lim && z <= lim)) {
                            return "{\"ok\":false,\"error\":\"coord out of range\"}";
                          }
                          // 未知设备：内核拒绝且不保存（界面静默降级）。
                          if (!hub.SetSourceSpatial(node_id, static_cast<float>(x),
                                                    static_cast<float>(y),
                                                    static_cast<float>(z), enabled)) {
                            return "{\"ok\":false,\"error\":\"unknown node\"}";
                          }
                          return "{\"ok\":true}";
                        }
                        return "{\"ok\":false}";
                     },
                     &http_error)) {
      std::printf("%s\n", http_error.c_str());
      return 4;
    }
  }

  std::printf("\n");
  std::printf("  audiohub-hub 已启动\n");
  std::printf("  监听       : 0.0.0.0:%u（控制口 %u）\n", opt.port, opt.port + 1);
  std::printf("  目标延迟   : %d ms\n", opt.latency_ms);
  {
    const std::string all = hub.local_ip();
    ip_only = all.substr(0, all.find(','));
    std::printf("  本机地址   : %s\n", all.c_str());
  }
  if (http) std::printf("  HTTP 接口  : http://127.0.0.1:%u\n", opt.http_port);
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
      // 自适应缓冲的调节过程也打出来：用户报"卡顿"时先看这行——
      // 是目标延迟在涨（网络在抖）、欠载在增加（真的断流），还是发生了保命丢帧。
      std::printf("   %-14s 水位 %4d ms | 目标 %4d ms | 欠载 %llu 帧 | 迟到 %llu | "
                  "溢出 %llu | 保命丢帧 %d 次 | 电平 %6.1f dBFS\n",
                  s.peer_name.empty() ? ("流 " + std::to_string(s.id)).c_str() : s.peer_name.c_str(),
                  s.occupancy_ms, s.target_ms,
                  static_cast<unsigned long long>(s.underrun_frames),
                  static_cast<unsigned long long>(s.late_packets),
                  static_cast<unsigned long long>(s.lost_packets), s.catchup_count, s.level_dbfs);
    }

    const auto pending = hub.PendingConnections();
    for (const auto& p : pending) {
      std::printf("   [等待确认] %s (%s) 流 %u\n", p.peer_name.c_str(), p.peer_ip.c_str(),
                  p.stream_id);
    }

    const auto peers = hub.DiscoveredPeers();
    if (!peers.empty()) {
      std::printf("   发现 %zu 台设备:", peers.size());
      for (const auto& p : peers) std::printf(" %s(%s)", p.name.c_str(), p.ip.c_str());
      std::printf("\n");
    }
    std::fflush(stdout);
  }

  hub.Stop();
  return 0;
}
