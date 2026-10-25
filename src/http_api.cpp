#include "audiohub/http_api.hpp"

#ifdef _WIN32

#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <cctype>
#include <cstdio>
#include <sstream>
#include <thread>
#include <vector>

#ifdef _MSC_VER
#pragma comment(lib, "ws2_32.lib")
#endif

namespace audiohub {
namespace {

struct WinsockGuard {
  WinsockGuard() {
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
  }
  ~WinsockGuard() { WSACleanup(); }
};

void EnsureWinsock() { static WinsockGuard guard; }

}  // namespace

struct HttpApi::Impl {
  SOCKET listen = INVALID_SOCKET;
  std::atomic<bool> stop{false};
  std::thread thread;
  Handler handler;

  ~Impl() {
    stop.store(true);
    if (listen != INVALID_SOCKET) {
      ::closesocket(listen);
      listen = INVALID_SOCKET;
    }
    if (thread.joinable()) thread.join();
  }

  void Loop() {
    while (!stop.load()) {
      SOCKET client = ::accept(listen, nullptr, nullptr);
      if (client == INVALID_SOCKET) break;
      // 一线程一连接：这个服务同时只有 1~2 个客户端（Electron 壳 + 可能的调试）
      std::thread([this, client] { Serve(client); }).detach();
    }
  }

  static bool ReadRequest(SOCKET client, std::string* out) {
    std::string buf;
    char tmp[4096];
    while (buf.find("\r\n\r\n") == std::string::npos) {
      const int n = ::recv(client, tmp, sizeof(tmp), 0);
      if (n <= 0) return false;
      buf.append(tmp, static_cast<size_t>(n));
      if (buf.size() > 64 * 1024) return false;
    }
    *out = buf;
    return true;
  }

  void Serve(SOCKET client) {
    std::string request;
    if (!ReadRequest(client, &request)) {
      ::closesocket(client);
      return;
    }

    const size_t line_end = request.find("\r\n");
    std::istringstream ls(request.substr(0, line_end));
    std::string method, path, version;
    ls >> method >> path >> version;

    // 去掉查询串，路径只留路由部分
    const size_t query = path.find('?');
    if (query != std::string::npos) path = path.substr(0, query);

    std::string body;
    const size_t header_end = request.find("\r\n\r\n");
    if (header_end != std::string::npos) body = request.substr(header_end + 4);

    std::string response;
    try {
      response = handler ? handler(method, path, body) : std::string("{\"ok\":false}");
    } catch (...) {
      // 处理器抛异常不能让整个服务挂掉——一次坏请求不该杀死音频
      response = "{\"ok\":false,\"error\":\"handler exception\"}";
    }

    std::ostringstream oss;
    oss << "HTTP/1.1 200 OK\r\n"
        << "Content-Type: application/json; charset=utf-8\r\n"
        << "Content-Length: " << response.size() << "\r\n"
        << "Cache-Control: no-store\r\n"
        << "Access-Control-Allow-Origin: *\r\n"
        << "Connection: close\r\n\r\n"
        << response;
    const std::string text = oss.str();
    ::send(client, text.data(), static_cast<int>(text.size()), 0);
    ::closesocket(client);
  }
};

HttpApi::HttpApi() : impl_(new Impl()) { EnsureWinsock(); }
HttpApi::~HttpApi() = default;

bool HttpApi::Start(uint16_t port, Handler handler, std::string* error) {
  impl_->handler = std::move(handler);
  impl_->stop.store(false);

  SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (s == INVALID_SOCKET) {
    if (error) *error = "socket() 失败";
    return false;
  }
  int one = 1;
  ::setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&one), sizeof(one));

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  addr.sin_port = htons(port);
  if (::bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
    if (error) *error = "绑定 127.0.0.1:" + std::to_string(port) + " 失败（端口被占用？）";
    ::closesocket(s);
    return false;
  }
  if (::listen(s, 16) != 0) {
    if (error) *error = "listen() 失败";
    ::closesocket(s);
    return false;
  }
  impl_->listen = s;
  impl_->thread = std::thread([this] { impl_->Loop(); });
  return true;
}

void HttpApi::Stop() {
  if (!impl_) return;
  impl_->stop.store(true);
  if (impl_->listen != INVALID_SOCKET) {
    ::closesocket(impl_->listen);
    impl_->listen = INVALID_SOCKET;
  }
  if (impl_->thread.joinable()) impl_->thread.join();
}

std::string JsonEscape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default: out += c;
    }
  }
  return out;
}

bool JsonFindNumber(const std::string& json, const std::string& key, double* out) {
  const std::string needle = "\"" + key + "\"";
  const size_t k = json.find(needle);
  if (k == std::string::npos) return false;
  size_t pos = json.find(':', k + needle.size());
  if (pos == std::string::npos) return false;
  ++pos;
  while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
  try {
    size_t used = 0;
    *out = std::stod(json.substr(pos), &used);
    return used > 0;
  } catch (...) {
    return false;
  }
}

bool JsonGetString(const std::string& json, const std::string& key, std::string* out) {
  const std::string needle = "\"" + key + "\"";
  const size_t k = json.find(needle);
  if (k == std::string::npos) return false;
  size_t pos = json.find(':', k + needle.size());
  if (pos == std::string::npos) return false;
  ++pos;
  while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
  if (pos >= json.size() || json[pos] != '"') return false;
  ++pos;
  const size_t end = json.find('"', pos);
  if (end == std::string::npos) return false;
  *out = json.substr(pos, end - pos);
  return true;
}

bool JsonFindBool(const std::string& json, const std::string& key, bool* out) {
  const std::string needle = "\"" + key + "\"";
  const size_t k = json.find(needle);
  if (k == std::string::npos) return false;
  size_t pos = json.find(':', k + needle.size());
  if (pos == std::string::npos) return false;
  ++pos;
  while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
  if (json.compare(pos, 4, "true") == 0) {
    *out = true;
    return true;
  }
  if (json.compare(pos, 5, "false") == 0) {
    *out = false;
    return true;
  }
  return false;
}

}  // namespace audiohub

#else

namespace audiohub {
struct HttpApi::Impl {};
HttpApi::HttpApi() : impl_(new Impl()) {}
HttpApi::~HttpApi() = default;
bool HttpApi::Start(uint16_t, Handler, std::string* error) {
  if (error) *error = "HTTP 接口目前只支持 Windows";
  return false;
}
void HttpApi::Stop() {}
std::string JsonEscape(const std::string& s) { return s; }
bool JsonFindNumber(const std::string&, const std::string&, double*) { return false; }
bool JsonFindBool(const std::string&, const std::string&, bool*) { return false; }
}  // namespace audiohub

#endif  // _WIN32
