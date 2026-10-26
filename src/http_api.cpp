#include "audiohub/http_api.hpp"

#ifdef _WIN32

#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <cctype>
#include <cstdio>
#include <cstdlib>
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

  // 从头部块里取 Content-Length（大小写不敏感）。
  //
  // 为什么不能只等 "\r\n\r\n"：客户端常常把"请求头"和"请求体"分两次 write，
  // 两次 write 在回环上也可能落在两个 TCP 段里。原来收到头部就返回，
  // body 为空——于是 /api/graph/switch 的 nodeId、/api/auto-accept 的 on
  // 全都解析不到，表现为 "unknown node" 或"设置无效"，甚至客户端还在写请求体
  // 时服务端已经回包并关连接，报 "connection was closed unexpectedly"。
  // 现在按 Content-Length 把请求体读完再交给处理器（GET 没有长度，长度 0）。
  static size_t HeaderContentLength(const std::string& head) {
    size_t pos = 0;
    while (pos < head.size()) {
      size_t eol = head.find("\r\n", pos);
      if (eol == std::string::npos) eol = head.size();
      const std::string line = head.substr(pos, eol - pos);
      const size_t colon = line.find(':');
      if (colon != std::string::npos) {
        std::string name = line.substr(0, colon);
        for (char& c : name) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        if (name == "content-length") {
          // strtoul 不抛异常，解析失败返回 0（没有请求体）。
          return static_cast<size_t>(std::strtoul(line.c_str() + colon + 1, nullptr, 10));
        }
      }
      pos = eol + 2;
    }
    return 0;
  }

  static bool ReadRequest(SOCKET client, std::string* out) {
    // 客户端可能半途不发，别让一个坏连接永久占住线程。
    DWORD timeout = 3000;
    ::setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout),
                 sizeof(timeout));

    std::string buf;
    char tmp[4096];
    size_t header_end = std::string::npos;
    size_t body_size = 0;
    while (true) {
      if (header_end == std::string::npos) {
        header_end = buf.find("\r\n\r\n");
        if (header_end != std::string::npos) {
          body_size = HeaderContentLength(buf.substr(0, header_end));
          // 声明了不可能的体积就直接拒绝，别白等到 recv 超时。
          if (body_size > 64 * 1024) return false;
        }
      }
      if (header_end != std::string::npos && buf.size() >= header_end + 4 + body_size) break;
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
