// 极简 HTTP/JSON 服务：给图形界面（Electron 壳）提供状态查询和音量控制。
//
// 为什么值得单独一个模块：音频核心跑在 C++ 里，而好看的表面积在网页技术栈那边。
// 中间只需要一条很窄的 HTTP 通道——查询状态 + 几个控制动作。
// 这里刻意不做完整 HTTP（无 chunked、无 keep-alive、无 TLS），
// 因为唯一客户端是本机的 Electron 壳，能跑就行，多一行都是维护负担。
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace audiohub {

class HttpApi {
 public:
  // 返回响应体；method 为 "GET"/"POST"，path 不含查询串。
  using Handler = std::function<std::string(const std::string& method, const std::string& path,
                                            const std::string& body)>;

  HttpApi();
  ~HttpApi();

  HttpApi(const HttpApi&) = delete;
  HttpApi& operator=(const HttpApi&) = delete;

  // 只监听 127.0.0.1：这个接口不该暴露到局域网。
  bool Start(uint16_t port, Handler handler, std::string* error = nullptr);
  void Stop();

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

// 下面三个是给 JSON 用的极简helper。不做完整解析：
// 客户端是我们自己发的，字段固定，完整解析器只会带来更多出错可能。
std::string JsonEscape(const std::string& s);
bool JsonFindNumber(const std::string& json, const std::string& key, double* out);
bool JsonFindBool(const std::string& json, const std::string& key, bool* out);
bool JsonGetString(const std::string& json, const std::string& key, std::string* out);

}  // namespace audiohub
