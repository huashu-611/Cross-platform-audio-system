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
#include <vector>

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
  // 发送缓冲：音频包是 5 ms 一拍的稳定小包，但系统调度抖动会让某几拍挤在一起。
  // 默认缓冲（Windows 约 64 KB）装得下 ~65 个包，够用；显式调大是为了给
  // "切换目标时旧包还在队列里"留余量，避免 sendto 直接返回 WSAEWOULDBLOCK。
  bool SetSendBufferBytes(int bytes);
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

// 本机**全部** IPv4 地址（v0.41）。
//
// 和 LocalIPv4List() 的区别很重要，别混用：
//   * LocalIPv4List() 回答的是"我该对外通告哪个地址" —— 所以它剔除 VMware /
//     WSL / Clash 这些虚拟网卡，还按"像不像真实局域网"打分排序。
//   * LocalIPv4Addresses() 回答的是"这些地址里哪些是我" —— 判断身份时必须把
//     虚拟网卡也算进来：手机看到的电脑地址完全可能是 192.168.x（VMnet）或多
//     出口 NAT 后的地址，那不是"对外通告的选择"，而是"对端眼里的我"。
// 用途：GRAPH 里 hubId 的归属判定（见 HubCore::IsSelfIdLocked）。
std::vector<std::string> LocalIPv4Addresses();

// 出网路径判定（v0.36）。只读，不发包，不改变任何 socket 行为。
//
// 为什么需要它：装了 Clash / V2Ray 的 TUN 模式后，0.0.0.0/0 默认路由会被
// 虚拟网卡抢走（本机实测：Meta 网卡 198.18.0.1 抢到默认路由），于是发往
// "不在本网段"的对端时源地址会变成 198.18.0.1 —— 那些包永远到不了手机，
// 而 mDNS 广播因为在同一网段内还能通，界面照样显示"星空里有对方"。
// 结果就是用户看到的"两端都显示对方没响应"，且从协议层怎么查都查不出问题。
//
// 判据不能用"连公网看来路"：那条路由恰好就是被劫持的那条。
// 这里用 connect()+getsockname() 对具体对端问一次内核 —— UDP connect 只做
// 路由查询，不产生任何流量。
struct EgressInfo {
  bool ok = false;        // false = 判定失败（地址非法 / socket 建不出来）
  std::string src_ip;     // 内核选定的源地址
  bool hijacked = false;  // true = 源地址不在任何"真实"网卡上（落进了 TUN 等虚拟网卡）
};

// 对 dst_ipv4 做一次不发包的路由查询。
EgressInfo ProbeEgress(const std::string& dst_ipv4);

// 本机"真实"网卡（已排除 VMware / TUN / 回环 / 链路本地）的清单，
// 每行 "ip/prefix gateway=yes|no name=<友好名>"。用于启动自检输出。
std::vector<std::string> RealNicSummary();

// 枚举当前已绑定 port 的 UDP 端点地址（"*" = 通配绑定）；空 = 端口干净。
//
// Windows 上"绑具体 IP"与"绑通配 IP"可以并存：一个残留进程占着
// 127.0.0.1:47002 时，新进程绑 0.0.0.0:47002 仍然成功，但发往 127.0.0.1
// 的包会全部进残留进程。bind() 看不出这件事，只能在绑之前问系统。
std::vector<std::string> ProbeUdpPortOwners(uint16_t port);

}  // namespace audiohub
