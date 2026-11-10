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

// 一帧交织样本（L,R,L,R...）的波形统计。抽成自由函数是为了能被单元测试
// 直接覆盖：探针本身必须能证明"有声音"和"没声音"两种情况都判对，
// 否则它只是一段自证清白的代码。
struct FrameStats {
  uint64_t audible = 0;  // 至少有一个声道非零的帧数
  double peak = 0.0;     // 最大绝对值
};

// frames_samples = 交织样本数（必须是整帧），channels = 每帧声道数。
inline FrameStats ScanFrames(const float* data, size_t samples, int channels) {
  FrameStats st;
  if (data == nullptr || channels <= 0) return st;
  for (size_t i = 0; i + static_cast<size_t>(channels) <= samples;
       i += static_cast<size_t>(channels)) {
    double frame_peak = 0.0;
    for (int c = 0; c < channels; ++c) {
      const double v = static_cast<double>(data[i + static_cast<size_t>(c)]);
      const double a = v < 0.0 ? -v : v;
      if (a > frame_peak) frame_peak = a;
    }
    if (frame_peak > 0.0) ++st.audible;
    if (frame_peak > st.peak) st.peak = frame_peak;
  }
  return st;
}

class Meter {
 public:
  void Add(const float* data, size_t count) {
    double local = 0.0;
    double peak = 0.0;
    uint64_t nonzero = 0;
    uint64_t clips = 0;
    for (size_t i = 0; i < count; ++i) {
      const double v = static_cast<double>(data[i]);
      local += v * v;
      const double a = v < 0.0 ? -v : v;
      if (a > peak) peak = a;
      if (v != 0.0) ++nonzero;
      if (a >= 0.999) ++clips;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    sum_sq_ += local;
    count_ += count;
    if (peak > peak_abs_) peak_abs_ = peak;
    nonzero_ += nonzero;
    clips_ += clips;
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
    // int16 路径只统计电平（源端/文件模式），峰值与非零计数留空：
    // 它们只在渲染探针（float 路径）里有意义，不在这里伪造。
  }

  // ---- 只读探针：不取走、不清零，任意多个读者同时看都一样 -----------
  //
  // 为什么必须有：Take() 是"取走即清零"，而 UI 每 200 ms 就轮询一次
  // /api/state（见 Sources()）。任何第二个消费者（命令行 curl、诊断脚本、
  // e2e 断言）再去 Take 一次，看到的必然是"刚才那一下刚好把数取空了"，
  // 于是一个正在出声的汇聚端被读成 -120 dBFS 静音。
  //
  //   Take  = 采样器（给 UI 画电平条，谁取谁独占）
  //   Peek  = 观测器（给诊断/测试用，幂等、可反复读、不影响 Take 的节奏）
  struct Snapshot {
    bool has_data = false;    // false = 自上次 Take 以来一个样本都没进过
    double dbfs = -120.0;     // 平均功率电平
    double peak_dbfs = -120.0;
    uint64_t samples = 0;
    uint64_t nonzero = 0;     // >0 就证明真的有过波形，不是纯静音
    uint64_t clips = 0;       // |x| >= 0.999 的样本数
  };

  Snapshot Peek() const {
    Snapshot s;
    std::lock_guard<std::mutex> lock(mutex_);
    s.samples = count_;
    s.nonzero = nonzero_;
    s.clips = clips_;
    if (count_ == 0) return s;
    s.has_data = true;
    s.dbfs = 10.0 * std::log10(sum_sq_ / static_cast<double>(count_) + 1e-12);
    s.peak_dbfs = 20.0 * std::log10(peak_abs_ + 1e-12);
    return s;
  }

  // 返回 false 表示这一轮没有任何数据（不是静音，是"没收到"）。
  bool Take(double* dbfs) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count_ == 0) return false;
    *dbfs = 10.0 * std::log10(sum_sq_ / static_cast<double>(count_) + 1e-12);
    sum_sq_ = 0.0;
    count_ = 0;
    peak_abs_ = 0.0;
    nonzero_ = 0;
    clips_ = 0;
    return true;
  }

 private:
  // Take() 是 const 的：HubCore::Sources()/Stats() 是 const 方法，
  // 而电平表本质上是个"取走即清零"的累加器，语义上就该是 const 的。
  mutable std::mutex mutex_;
  mutable double sum_sq_ = 0.0;
  mutable double peak_abs_ = 0.0;
  mutable uint64_t count_ = 0;
  mutable uint64_t nonzero_ = 0;
  mutable uint64_t clips_ = 0;
};

}  // namespace audiohub
