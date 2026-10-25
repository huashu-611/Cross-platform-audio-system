// 极简电平表：累加平方和，报数时换算 dBFS 并清零。
//
// 为什么值得到处都放一个：音频链路出问题时，"有没有信号、多大"是最先要回答的问题。
// 而且它能给出可验证的数字——两个 0.5 幅度的不相关正弦叠加，总电平必须恰好
// 比单路高 3.01 dB。差得多就是增益结构错了，不用靠耳朵猜。
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace audiohub {

class Meter {
 public:
  void Add(const float* data, size_t count) {
    double local = 0.0;
    for (size_t i = 0; i < count; ++i) local += static_cast<double>(data[i]) * data[i];
    std::lock_guard<std::mutex> lock(mutex_);
    sum_sq_ += local;
    count_ += count;
  }

  // int16 版本：源端统计的是"实际发上线的内容"，
  // 采集/信号源/文件三种模式都走这里，标签才有可比性。
  void AddInt16(const int16_t* data, size_t count) {
    constexpr double kScale = 1.0 / (32768.0 * 32768.0);
    double local = 0.0;
    for (size_t i = 0; i < count; ++i) {
      const double v = static_cast<double>(data[i]);
      local += v * v;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    sum_sq_ += local * kScale;
    count_ += count;
  }

  // 返回 false 表示这一轮没有任何数据（不是静音，是"没收到"）。
  bool Take(double* dbfs) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count_ == 0) return false;
    *dbfs = 10.0 * std::log10(sum_sq_ / static_cast<double>(count_) + 1e-12);
    sum_sq_ = 0.0;
    count_ = 0;
    return true;
  }

 private:
  // Take() 是 const 的：HubCore::Sources()/Stats() 是 const 方法，
  // 而电平表本质上是个"取走即清零"的累加器，语义上就该是 const 的。
  mutable std::mutex mutex_;
  mutable double sum_sq_ = 0.0;
  mutable uint64_t count_ = 0;
};

}  // namespace audiohub
