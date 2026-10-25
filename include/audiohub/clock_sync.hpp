// 源端与汇聚端（Hub）之间的时钟偏移估计。
//
// 用四次时间戳的 ping/pong（和 NTP 同构），但不用系统时钟，只用各自的单调时钟。
// 为什么不用系统 NTP：系统时钟会被校时"跳变"，而音频时钟必须单调；
// 而且系统 NTP 的精度只有 ±10 ms，我们需要的相对精度是 ±1 ms。
//
// 关键点：offset 取**中位数**而不是平均值。WiFi 上的排队尖峰是单边的
// （只会让包变慢，不会变快），均值会被这些尖峰拖偏，中位数不会。
// 同时丢弃 RTT 明显高于窗口内最小值的样本——那些样本的回程被排队了，
// 算出来的 offset 本身就不可信。
#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>

namespace audiohub {

struct ClockSample {
  double offset_ms = 0;
  double rtt_ms = 0;
};

class ClockSync {
 public:
  explicit ClockSync(size_t window = 50);

  // t1 源端发送、t2 Hub 收到、t3 Hub 回复、t4 源端收到（各自单调时钟，毫秒）
  void AddSample(uint32_t t1, uint32_t t2, uint32_t t3, uint32_t t4);

  // Hub 时钟 = 源时钟 + offset。未就绪返回 false。
  bool OffsetMs(double* out) const;
  double RttMs() const;
  size_t SampleCount() const { return samples_.size(); }
  bool Ready() const;

  void Reset();

 private:
  size_t window_;
  std::deque<ClockSample> samples_;
};

}  // namespace audiohub
