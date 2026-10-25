#include "audiohub/udp_socket.hpp"

#include <cstring>
#include <sstream>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
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

std::string LocalIPv4List() {
  // 不依赖 getifaddrs（Windows 上要用 IP Helper API），P0 用"连一个公网地址
  // 让内核选出口网卡"的经典技巧，再补上回环地址。
  std::ostringstream oss;
  bool first = true;
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
        oss << text;
        first = false;
      }
    }
#ifdef _WIN32
    ::closesocket(s);
#else
    ::close(s);
#endif
  }
  if (!first) oss << ", ";
  oss << "127.0.0.1";
  return oss.str();
}

}  // namespace audiohub
