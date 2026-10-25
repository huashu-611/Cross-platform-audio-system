// 局域网设备发现。
//
// 为什么不用 mDNS/Bonjour：Android 有 NsdManager，但 Windows 没有内置的
// mDNS responder，得自己写 DNS 报文；而这里的需求只是"同一网段内互相看见"，
// 一个 UDP 广播信标就够了——少一半代码，少一类坑。
//
// 协议（UDP 47300，纯文本，| 分隔，显示名放最后所以可以含任意字符）：
//
//   AUDIOHUB|1|<role>|<audio_port>|<http_port>|<ip>|<display_name>\n
//
//   role: S = 只能当音源   R = 只能当汇聚端(放音)   SR = 两者皆可
//
// 另外支持一个探测帧 `AUDIOHUB|1|WHO\n`，收到的人立即回一帧信标，
// 这样点"刷新"不用等下一个广播周期。
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace audiohub {

struct Peer {
  std::string name;
  std::string ip;
  uint16_t audio_port = 0;
  uint16_t http_port = 0;
  bool can_receive = false;  // 能放音（可当汇聚端）
  bool can_send = false;     // 能采音（可当音源）
  uint64_t last_seen_ms = 0;  // 单调时钟，毫秒

  // 给界面显示的一句话角色
  const char* role_text() const {
    if (can_send && can_receive) return "音源 / 汇聚";
    return can_receive ? "汇聚端" : "音源";
  }
};

class Discovery {
 public:
  Discovery();
  ~Discovery();

  Discovery(const Discovery&) = delete;
  Discovery& operator=(const Discovery&) = delete;

  // 每隔 1 秒广播一次自己的存在。
  //
  // local_ip 必须由调用方显式传入：广播可能从虚拟网卡（VMware/WSL/蓝牙）
  // 发出去，靠 recvfrom 反推来址会拿到一个对端根本连不通的地址。
  // can_receive 决定别人能不能把音频送过来。
  bool StartAdvertise(const std::string& display_name, const std::string& local_ip,
                      uint16_t audio_port, uint16_t http_port, bool can_receive,
                      bool can_send = true);

  // 监听别人的广播。可以只浏览不广播。
  bool StartBrowse();

  void Stop();

  // 5 秒没再见到就认为掉线，从列表剔除。
  std::vector<Peer> Peers() const;

  // 立即向全网发一轮探测，让所有人马上回一帧
  void Probe();

  bool running() const { return running_.load(); }

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
  std::atomic<bool> running_{false};
};

}  // namespace audiohub
