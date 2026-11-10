#include "audiohub/udp_socket.hpp"

#include <algorithm>
#include <cstring>
#include <sstream>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#ifdef _MSC_VER
#pragma comment(lib, "ws2_32.lib")  // MSVC 专用；MinGW 靠 CMake 的 -lws2_32
#endif
using socket_t = SOCKET;
static constexpr socket_t kInvalidSocket = INVALID_SOCKET;
#else
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
using socket_t = int;
static constexpr socket_t kInvalidSocket = -1;
#endif

namespace audiohub {
namespace {

#ifdef _WIN32
struct WinsockGuard {
  WinsockGuard() {
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
  }
  ~WinsockGuard() { WSACleanup(); }
};
void EnsureWinsock() { static WinsockGuard guard; }
#else
void EnsureWinsock() {}
#endif

}  // namespace

UdpSocket::UdpSocket() { EnsureWinsock(); }

UdpSocket::~UdpSocket() { Close(); }

bool UdpSocket::valid() const { return handle_ != kInvalidSocket; }

void UdpSocket::SetError(const char* what) {
#ifdef _WIN32
  last_error_ = std::string(what) + " (wsa=" + std::to_string(WSAGetLastError()) + ")";
#else
  last_error_ = std::string(what) + " (errno=" + std::to_string(errno) + ")";
#endif
}

bool UdpSocket::Open(uint16_t bind_port, bool reuse_addr) {
  Close();
  const socket_t s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s == kInvalidSocket) {
    SetError("socket()");
    return false;
  }
  handle_ = s;

  if (reuse_addr) {
    int one = 1;
    ::setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&one), sizeof(one));
  }

  // 默认给 1 MB 接收缓冲，理由见头文件注释。
  SetReceiveBufferBytes(1 << 20);

  if (bind_port != 0) {
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(bind_port);
    if (::bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
      SetError("bind()");
      Close();
      return false;
    }
  }
  return true;
}

void UdpSocket::Close() {
  if (!valid()) return;
#ifdef _WIN32
  ::closesocket(handle_);
  handle_ = kInvalidSocket;
#else
  ::close(handle_);
  handle_ = -1;
#endif
}

bool UdpSocket::SetNonBlocking(bool on) {
  if (!valid()) return false;
#ifdef _WIN32
  u_long mode = on ? 1 : 0;
  return ::ioctlsocket(handle_, FIONBIO, &mode) == 0;
#else
  const int flags = ::fcntl(handle_, F_GETFL, 0);
  return ::fcntl(handle_, F_SETFL, on ? (flags | O_NONBLOCK) : (flags & ~O_NONBLOCK)) == 0;
#endif
}

bool UdpSocket::SetReceiveTimeoutMs(int ms) {
  if (!valid()) return false;
#ifdef _WIN32
  DWORD timeout = static_cast<DWORD>(ms);
  return ::setsockopt(handle_, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout),
                      sizeof(timeout)) == 0;
#else
  timeval tv{};
  tv.tv_sec = ms / 1000;
  tv.tv_usec = (ms % 1000) * 1000;
  return ::setsockopt(handle_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) == 0;
#endif
}

bool UdpSocket::SetReceiveBufferBytes(int bytes) {
  if (!valid()) return false;
  return ::setsockopt(handle_, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&bytes),
                      sizeof(bytes)) == 0;
}

bool UdpSocket::SetSendBufferBytes(int bytes) {
  if (!valid()) return false;
  return ::setsockopt(handle_, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&bytes),
                      sizeof(bytes)) == 0;
}

bool UdpSocket::SetPeer(const std::string& ipv4, uint16_t port) {
  if (!valid()) return false;
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(port);
  if (::inet_pton(AF_INET, ipv4.c_str(), &addr.sin_addr) != 1) {
    SetError("inet_pton()");
    return false;
  }
  // 用 connect() 让内核记下对端：后续可以直接 send()，
  // 同时能收到 ICMP 端口不可达的错误，比 sendto 更早发现对端没在监听。
  if (::connect(handle_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
    SetError("connect()");
    return false;
  }
  return true;
}

bool UdpSocket::SendToPeer(const void* data, size_t size) {
  if (!valid()) return false;
  const int sent = static_cast<int>(::send(handle_, static_cast<const char*>(data),
                                           static_cast<int>(size), 0));
  if (sent < 0) {
    SetError("send()");
    return false;
  }
  return static_cast<size_t>(sent) == size;
}

bool UdpSocket::SendToAddr(const std::string& ipv4, uint16_t port, const void* data, size_t size) {
  if (!valid()) return false;
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(port);
  if (::inet_pton(AF_INET, ipv4.c_str(), &addr.sin_addr) != 1) {
    SetError("inet_pton()");
    return false;
  }
  const int sent =
      static_cast<int>(::sendto(handle_, static_cast<const char*>(data), static_cast<int>(size), 0,
                                reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)));
  if (sent < 0) {
    SetError("sendto()");
    return false;
  }
  return static_cast<size_t>(sent) == size;
}

int UdpSocket::ReceiveFrom(void* buffer, size_t capacity, std::string* from_ipv4,
                           uint16_t* from_port) {
  if (!valid()) return -1;
  sockaddr_in from{};
#ifdef _WIN32
  int from_len = sizeof(from);
#else
  socklen_t from_len = sizeof(from);
#endif
  const int n = static_cast<int>(::recvfrom(handle_, static_cast<char*>(buffer),
                                            static_cast<int>(capacity), 0,
                                            reinterpret_cast<sockaddr*>(&from), &from_len));
  if (n <= 0) return -1;

  if (from_ipv4 != nullptr) {
    char text[INET_ADDRSTRLEN] = {0};
    ::inet_ntop(AF_INET, &from.sin_addr, text, sizeof(text));
    *from_ipv4 = text;
  }
  if (from_port != nullptr) *from_port = ntohs(from.sin_port);
  return n;
}

namespace {

// 网卡名/描述小写化：VMware、vEthernet 这些都是 ASCII，逐字节转小写就够
std::string NarrowLower(const wchar_t* w) {
  std::string out;
  for (; w != nullptr && *w != L'\0'; ++w) {
    const wchar_t c = *w;
    out += static_cast<char>(c >= L'A' && c <= L'Z' ? c - L'A' + L'a' : c);
  }
  return out;
}

// 名字里带这些词的网卡基本都不是"能和手机通信的那张"
bool LooksVirtual(const std::string& name) {
  static const char* kBad[] = {"vmware", "virtualbox", "hyper-v", "vethernet", "wsl",
                               "tap-",    "tailscale",  "zerotier", "clash",    "wireguard",
                               "docker",  "loopback",   "npcap",    "vpn",      "meta"};
  for (const char* bad : kBad) {
    if (name.find(bad) != std::string::npos) return true;
  }
  return false;
}

}  // namespace

// WCHAR* → 窄字符串（UTF-8）。网卡友好名可能是中文，所以不能只做
// 逐字节窄化，要走一遍 WideCharToMultiByte。
std::string NarrowFromWide(const wchar_t* w) {
#ifdef _WIN32
  if (w == nullptr) return std::string();
  const int need = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
  if (need <= 0) return std::string();
  std::string out(static_cast<size_t>(need - 1), static_cast<char>(0));
  WideCharToMultiByte(CP_UTF8, 0, w, -1, out.data(), need, nullptr, nullptr);
  return out;
#else
  (void)w;
  return std::string();
#endif
}

std::string LocalIPv4List() {
  // 枚举所有网卡，剔除虚拟 / TUN / 回环 / 链路本地，按"像真实局域网"排序，
  // 返回逗号分隔的候选列表（第一个是最可能可用的）。
  //
  // 为什么不能再用"连 223.5.5.5 看来路"的老技巧：开了 Clash 等代理的
  // TUN 模式后，这条路由被 TUN 网卡接管，getsockname 拿到的是 198.18.0.1
  // 这种假地址——对端根本连不通，表现为"扫得到但连不上"。
  std::vector<std::pair<int, std::string>> entries;

#ifdef _WIN32
  ULONG size = 16384;
  std::vector<BYTE> buf(size);
  PIP_ADAPTER_ADDRESSES addrs = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data());
  if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                        GAA_FLAG_SKIP_DNS_SERVER, nullptr, addrs, &size) == NO_ERROR) {
    for (PIP_ADAPTER_ADDRESSES a = addrs; a != nullptr; a = a->Next) {
      if (a->OperStatus != IfOperStatusUp) continue;
      // 隧道 / 回环 / PPP 一律跳过
      if (a->IfType == IF_TYPE_TUNNEL || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK ||
          a->IfType == IF_TYPE_PPP) {
        continue;
      }
      // VMware / vEthernet / Clash 这类虚拟网卡也是"以太网"类型，
      // 光看 IfType 分不出来，必须按名字剔除——否则会挑到 192.168.x 或
      // 198.18.x 的假地址，对端连不通。
      const std::string friendly = NarrowLower(a->FriendlyName);
      const std::string desc = NarrowLower(a->Description);
      if (LooksVirtual(friendly) || LooksVirtual(desc)) continue;

      const bool has_gateway = a->FirstGatewayAddress != nullptr;
      // 以太网(6) 和 WiFi(71) 最优先
      int base = (a->IfType == IF_TYPE_ETHERNET_CSMACD ||
                  a->IfType == IF_TYPE_IEEE80211)
                     ? 100
                     : 0;
      // 路由度量：越小越"主"，真实默认网卡的度量通常远小于虚拟网卡
      if (a->Ipv4Metric < 200) base += (200 - static_cast<int>(a->Ipv4Metric)) / 4;

      for (PIP_ADAPTER_UNICAST_ADDRESS ua = a->FirstUnicastAddress; ua != nullptr;
           ua = ua->Next) {
        if (ua->Address.lpSockaddr == nullptr) continue;
        if (ua->Address.lpSockaddr->sa_family != AF_INET) continue;
        const auto* sa = reinterpret_cast<const sockaddr_in*>(ua->Address.lpSockaddr);
        const uint32_t ip = ntohl(sa->sin_addr.s_addr);

        if ((ip >> 24) == 127) continue;                        // 回环
        if ((ip & 0xFFFF0000u) == 0xA9FE0000u) continue;        // 169.254/16 链路本地
        if ((ip & 0xFFFE0000u) == 0xC6120000u) continue;        // 198.18/15 Clash TUN 假地址
        if ((ip & 0xFFC00000u) == 0x64400000u) continue;        // 100.64/10 CGNAT / Tailscale

        char text[INET_ADDRSTRLEN] = {0};
        in_addr tmp{};
        tmp.s_addr = htonl(ip);
        ::inet_ntop(AF_INET, &tmp, text, sizeof(text));

        int score = base;
        if (has_gateway) score += 50;                            // 有默认网关说明能出网
        if (ua->OnLinkPrefixLength == 24) score += 5;            // 家用/办公最常见
        entries.emplace_back(score, std::string(text));
      }
    }
  }
#endif

  // 兜底：Windows 上枚举失败、或非 Windows 平台，退回"连公网看来路"
  if (entries.empty()) {
    const socket_t s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s != kInvalidSocket) {
      sockaddr_in probe{};
      probe.sin_family = AF_INET;
      probe.sin_port = htons(53);
      ::inet_pton(AF_INET, "223.5.5.5", &probe.sin_addr);
      if (::connect(s, reinterpret_cast<sockaddr*>(&probe), sizeof(probe)) == 0) {
        sockaddr_in local{};
#ifdef _WIN32
        int len = sizeof(local);
#else
        socklen_t len = sizeof(local);
#endif
        if (::getsockname(s, reinterpret_cast<sockaddr*>(&local), &len) == 0) {
          char text[INET_ADDRSTRLEN] = {0};
          ::inet_ntop(AF_INET, &local.sin_addr, text, sizeof(text));
          entries.emplace_back(0, std::string(text));
        }
      }
#ifdef _WIN32
      ::closesocket(s);
#else
      ::close(s);
#endif
    }
  }

  std::stable_sort(entries.begin(), entries.end(),
                   [](const std::pair<int, std::string>& a,
                      const std::pair<int, std::string>& b) { return a.first > b.first; });

  std::ostringstream oss;
  bool first = true;
  for (const auto& e : entries) {
    if (!first) oss << ", ";
    oss << e.second;
    first = false;
  }
  if (!first) oss << ", ";
  oss << "127.0.0.1";
  return oss.str();
}

// 本机全部 IPv4（v0.41）。与 LocalIPv4List() 的差别见头文件注释：
// 这里**不剔除**虚拟网卡、不打分、不排序，因为判断"这个节点 id 是不是我"
// 需要的是事实集合，而不是"该对外用哪个地址"的偏好。
std::vector<std::string> LocalIPv4Addresses() {
  std::vector<std::string> out;
#ifdef _WIN32
  ULONG size = 16384;
  std::vector<BYTE> buf(size);
  PIP_ADAPTER_ADDRESSES addrs = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data());
  if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                        GAA_FLAG_SKIP_DNS_SERVER, nullptr, addrs, &size) == NO_ERROR) {
    for (PIP_ADAPTER_ADDRESSES a = addrs; a != nullptr; a = a->Next) {
      if (a->OperStatus != IfOperStatusUp) continue;
      for (PIP_ADAPTER_UNICAST_ADDRESS ua = a->FirstUnicastAddress; ua != nullptr;
           ua = ua->Next) {
        if (ua->Address.lpSockaddr == nullptr) continue;
        if (ua->Address.lpSockaddr->sa_family != AF_INET) continue;
        const auto* sa = reinterpret_cast<const sockaddr_in*>(ua->Address.lpSockaddr);
        char text[INET_ADDRSTRLEN] = {0};
        if (::inet_ntop(AF_INET, &sa->sin_addr, text, sizeof(text)) == nullptr) continue;
        const std::string ip(text);
        if (ip.empty()) continue;
        if (ip.compare(0, 4, "127.") == 0) continue;   // 回环单独补
        bool dup = false;
        for (const auto& e : out) {
          if (e == ip) { dup = true; break; }
        }
        if (!dup) out.push_back(ip);
      }
    }
  }
#endif
  out.push_back("127.0.0.1");
  return out;
}

//
// v0.36：出网路径判定。
//
// 只读探测：用 UDP connect() + getsockname() 问内核"你会用哪个源地址发这个包"。
// UDP 的 connect 不发任何包，只是把对端记在 PCB 上并做一次路由查询。
//
// 为什么要按"对端"分别问，而不是问一次就完事：路由是按目标前缀匹配的。
// 同一台机器上，发往同网段（10.135.x）的对端走 WLAN，发往跨网段
// （10.69.x / 192.168.43.x / 172.20.x）的对端会被 TUN 的 0.0.0.0/0 抢走。
// 本机实测就是这种"对一半、错一半"的状态。
EgressInfo ProbeEgress(const std::string& dst_ipv4) {
  EgressInfo out;
  if (dst_ipv4.empty()) return out;
  const socket_t s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s == kInvalidSocket) return out;

  sockaddr_in dst{};
  dst.sin_family = AF_INET;
  dst.sin_port = htons(1);   // 端口随便：我们只要路由判定，不发包
  if (::inet_pton(AF_INET, dst_ipv4.c_str(), &dst.sin_addr) != 1) {
#ifdef _WIN32
    ::closesocket(s);
#else
    ::close(s);
#endif
    return out;
  }
  if (::connect(s, reinterpret_cast<sockaddr*>(&dst), sizeof(dst)) != 0) {
#ifdef _WIN32
    ::closesocket(s);
#else
    ::close(s);
#endif
    return out;
  }

  sockaddr_in local{};
#ifdef _WIN32
  int len = sizeof(local);
#else
  socklen_t len = sizeof(local);
#endif
  if (::getsockname(s, reinterpret_cast<sockaddr*>(&local), &len) == 0) {
    char text[INET_ADDRSTRLEN] = {0};
    ::inet_ntop(AF_INET, &local.sin_addr, text, sizeof(text));
    out.src_ip = text;
    out.ok = true;
    // 源地址不在真实网卡上 → 被虚拟网卡接走了。
    // 用 RealNicSummary 的同一套排除规则判断，避免两处口径不一致。
    bool on_real_nic = false;
    for (const std::string& line : RealNicSummary()) {
      const size_t slash = line.find('/');
      if (slash == std::string::npos) continue;
      if (line.substr(0, slash) == out.src_ip) {
        on_real_nic = true;
        break;
      }
    }
    // 回环不算"被劫持"：本机自测（audioflow-test 用 127.0.0.2）走的就是它。
    const bool is_loopback = (out.src_ip.rfind("127.", 0) == 0);
    out.hijacked = !on_real_nic && !is_loopback;
  }

#ifdef _WIN32
  ::closesocket(s);
#else
  ::close(s);
#endif
  return out;
}

std::vector<std::string> RealNicSummary() {
  std::vector<std::string> out;
#ifdef _WIN32
  ULONG size = 16384;
  std::vector<BYTE> buf(size);
  PIP_ADAPTER_ADDRESSES addrs = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data());
  if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                        GAA_FLAG_SKIP_DNS_SERVER, nullptr, addrs, &size) != NO_ERROR) {
    return out;
  }
  for (PIP_ADAPTER_ADDRESSES a = addrs; a != nullptr; a = a->Next) {
    if (a->OperStatus != IfOperStatusUp) continue;
    if (a->IfType == IF_TYPE_TUNNEL || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK ||
        a->IfType == IF_TYPE_PPP) {
      continue;
    }
    // 与 LocalIPv4List 完全一致的虚拟网卡剔除规则（VMware / Clash / vEthernet）。
    const std::string friendly = NarrowLower(a->FriendlyName);
    const std::string desc = NarrowLower(a->Description);
    if (LooksVirtual(friendly) || LooksVirtual(desc)) continue;
    const bool has_gateway = a->FirstGatewayAddress != nullptr;

    for (PIP_ADAPTER_UNICAST_ADDRESS ua = a->FirstUnicastAddress; ua != nullptr;
         ua = ua->Next) {
      if (ua->Address.lpSockaddr == nullptr) continue;
      if (ua->Address.lpSockaddr->sa_family != AF_INET) continue;
      const auto* sa = reinterpret_cast<const sockaddr_in*>(ua->Address.lpSockaddr);
      const uint32_t ip = ntohl(sa->sin_addr.s_addr);
      if ((ip >> 24) == 127) continue;
      if ((ip & 0xFFFF0000u) == 0xA9FE0000u) continue;
      if ((ip & 0xFFFE0000u) == 0xC6120000u) continue;
      if ((ip & 0xFFC00000u) == 0x64400000u) continue;

      char text[INET_ADDRSTRLEN] = {0};
      in_addr tmp{};
      tmp.s_addr = htonl(ip);
      ::inet_ntop(AF_INET, &tmp, text, sizeof(text));

      std::ostringstream line;
      line << text << "/" << static_cast<int>(ua->OnLinkPrefixLength)
           << " gateway=" << (has_gateway ? "yes" : "no")
           // FriendlyName 是 WCHAR*，直接往 ostringstream 里塞会打出指针地址，
           // 必须先按 UTF-8 转成窄字符串（这里就是 ASCII 网卡名）。
           << " name=" << NarrowFromWide(a->FriendlyName);
      out.push_back(line.str());
    }
  }
#endif
  return out;
}

//
// 2026-11-03（v0.20）：Windows 上"绑定具体 IP"和"绑定通配 IP"是两件事 ——
// 一个进程绑了 127.0.0.1:47002，另一个进程再绑 0.0.0.0:47002 **照样成功**，
// 但发往 127.0.0.1 的包全部进前者。表现出来就是 hub 日志正常、界面正常，
// 唯独收不到手机的任何握手帧；用户反复退出重进，偶尔某次"好了"（那次残留
// 进程恰好退了），观感就是"多次退出重进后有遗留"。
//
// bind() 本身查不出来，只能在绑之前枚举一遍现有 UDP 端点。
// 返回占用该端口的地址列表（"*" 表示通配绑定）；空 = 端口干净。
std::vector<std::string> ProbeUdpPortOwners(uint16_t port) {
  std::vector<std::string> owners;
#ifdef _WIN32
  DWORD size = 0;
  if (::GetExtendedUdpTable(nullptr, &size, FALSE, AF_INET, UDP_TABLE_OWNER_PID, 0) !=
      ERROR_INSUFFICIENT_BUFFER) {
    return owners;
  }
  std::vector<char> buffer(size);
  auto* table = reinterpret_cast<PMIB_UDPTABLE_OWNER_PID>(buffer.data());
  if (::GetExtendedUdpTable(table, &size, FALSE, AF_INET, UDP_TABLE_OWNER_PID, 0) != NO_ERROR) {
    return owners;
  }
  for (DWORD i = 0; i < table->dwNumEntries; ++i) {
    const MIB_UDPROW_OWNER_PID& row = table->table[i];
    const uint16_t local_port = ntohs(static_cast<uint16_t>(row.dwLocalPort));
    if (local_port != port) continue;
    in_addr addr{};
    addr.s_addr = row.dwLocalAddr;
    char text[INET_ADDRSTRLEN] = {0};
    if (::inet_ntop(AF_INET, &addr, text, sizeof(text)) == nullptr) continue;
    if (row.dwLocalAddr == 0) {
      owners.emplace_back("*");
    } else {
      owners.emplace_back(text);
    }
  }
#else
  (void)port;
#endif
  return owners;
}

}  // namespace audiohub
