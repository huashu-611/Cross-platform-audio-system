#include "audiohub/clock_sync.hpp"

#include <algorithm>
#include <limits>
#include <vector>

namespace audiohub {
namespace {

double Median(std::vector<double> v) {
  if (v.empty()) return 0.0;
  const size_t mid = v.size() / 2;
  std::nth_element(v.begin(), v.begin() + static_cast<long>(mid), v.end());
  double hi = v[mid];
  if (v.size() % 2 == 1) return hi;
  // 偶数个取中间两个的平均
  const double lo = *std::max_element(v.begin(), v.begin() + static_cast<long>(mid));
  return (lo + hi) * 0.5;
}

}  // namespace

ClockSync::ClockSync(size_t window) : window_(window == 0 ? 1 : window) {}

void ClockSync::AddSample(uint32_t t1, uint32_t t2, uint32_t t3, uint32_t t4) {
  // 全部用 uint32 减法再转 int32，天然处理 49.7 天的回绕。
  const int32_t a = static_cast<int32_t>(t2 - t1);  // 去程时延 + 时钟差
  const int32_t b = static_cast<int32_t>(t3 - t4);  // 时钟差 - 回程时延

  // 注意：a 和 b 都可以是负数（时钟差为负、或回程时延大于时钟差），
  // 所以不能按符号过滤。唯一可靠的健全性检查是往返时间：
  //   rtt = a - b = (t2-t1) + (t4-t3)，必然 >= 0。
  const int32_t rtt = a - b;
  const double offset = (static_cast<double>(a) + static_cast<double>(b)) * 0.5;
  if (rtt < 0 || rtt > 5000) return;
  if (offset < -60000.0 || offset > 60000.0) return;

  samples_.push_back(ClockSample{offset, static_cast<double>(rtt)});
  while (samples_.size() > window_) samples_.pop_front();
}

bool ClockSync::OffsetMs(double* out) const {
  if (out == nullptr || samples_.empty()) return false;

  double min_rtt = std::numeric_limits<double>::max();
  for (const auto& s : samples_) min_rtt = std::min(min_rtt, s.rtt_ms);

  // 只保留 RTT 接近最小值的样本。1.5 倍是经验值：
  // 太严会样本不足，太松会把排队尖峰算进来。
  const double threshold = min_rtt * 1.5 + 1.0;
  std::vector<double> offsets;
  offsets.reserve(samples_.size());
  for (const auto& s : samples_) {
    if (s.rtt_ms <= threshold) offsets.push_back(s.offset_ms);
  }
  if (offsets.empty()) return false;

  *out = Median(std::move(offsets));
  return true;
}

double ClockSync::RttMs() const {
  if (samples_.empty()) return 0.0;
  // 用窗口最后几个样本的 RTT，反映当前网络状况而不是历史最小值
  const size_t n = std::min<size_t>(samples_.size(), 5);
  double sum = 0;
  for (size_t i = samples_.size() - n; i < samples_.size(); ++i) sum += samples_[i].rtt_ms;
  return sum / static_cast<double>(n);
}

bool ClockSync::Ready() const { return samples_.size() >= 5; }

void ClockSync::Reset() { samples_.clear(); }

}  // namespace audiohub
