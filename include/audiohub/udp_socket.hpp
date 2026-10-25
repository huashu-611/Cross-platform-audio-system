// 跨平台 UDP socket 薄封装。
//
// 只做 P0 需要的事：绑定、收发、非阻塞、放大接收缓冲。
// 不做：IPv6、组播、NAT 穿透（P0 假设同一局域网）。
//
// 为什么接收缓冲要显式放大：Windows 默认 SO_RCVBUF 只有 64 KB，
// 一次短暂的线程调度延迟就能把队列打满，之后到达的包被内核直接丢弃——
// 表现出来是"偶发的、无法复现的爆音"。放大到 1 MB 能让抖动缓冲真正
// 起到它的作用。
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace audiohub {

class UdpSocket {
 public:
  UdpSocket();
  ~UdpSocket();

  UdpSocket(const UdpSocket&) = delete;
  UdpSocket& operator=(const UdpSocket&) = delete;

  // bind_port = 0 表示由系统分配（源端用）。
  bool Open(uint16_t bind_port, bool reuse_addr = true);
  void Close();
  bool valid() const;

  bool SetNonBlocking(bool on);

  // 阻塞读 + 超时。比"非阻塞读 + sleep 轮询"准得多：
  // Windows 上 sleep_for(1ms) 实际会睡 15.6ms（默认定时器精度），
  // 用它做 RTT 测量会凭空多出十几毫秒。
  bool SetReceiveTimeoutMs(int ms);
  bool SetReceiveBufferBytes(int bytes);
  // 设置对端地址（P0 只支持 IPv4 字面量，避免引入 DNS 依赖）。
  bool SetPeer(const std::string& ipv4, uint16_t port);

  bool SendToPeer(const void* data, size_t size);

  // 未 connect 的 socket 用这个（Hub 的控制口需要回复"谁问就回谁"）。
  bool SendToAddr(const std::string& ipv4, uint16_t port, const void* data, size_t size);

  // 返回收到的字节数；-1 表示没有数据（非阻塞）或出错。
  int ReceiveFrom(void* buffer, size_t capacity, std::string* from_ipv4, uint16_t* from_port);

  const std::string& last_error() const { return last_error_; }

 private:
  void SetError(const char* what);

#ifdef _WIN32
  uintptr_t handle_ = ~uintptr_t(0);
#else
  int handle_ = -1;
#endif
  std::string last_error_;
};

// 返回本机所有 IPv4 地址，便于启动时打印"把手机指向这个 IP"。
std::string LocalIPv4List();

}  // namespace audiohub
