#include "audiohub/asrc.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace audiohub {
namespace {

// Catmull-Rom 样条：过 p1、p2 两点，斜率由 p0/p3 决定。
// 与线性插值相比多两次乘法，换掉 10 kHz 处约 -1.2 dB 的高频衰减。
inline float CatmullRom(float p0, float p1, float p2, float p3, float t) {
  const float c0 = p1;
  const float c1 = 0.5f * (p2 - p0);
  const float c2 = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
  const float c3 = 0.5f * (p3 - p0) + 1.5f * (p1 - p2);
  return ((c3 * t + c2) * t + c1) * t + c0;
}

}  // namespace

Asrc::Asrc(int channels) : channels_(channels) {
  if (channels_ <= 0) channels_ = 1;
  // 预留 1 秒，避免 Push 时频繁重分配（重分配发生在混音线程，不是设备回调，
  // 但仍应尽量避免在稳态下触发）。
  buffer_.reserve(static_cast<size_t>(48000) * channels_);
}

void Asrc::SetRatio(double ratio) {
  // ±0.2% 是硬边界。超出这个范围的漂移说明设备有问题，
  // 与其用力补偿，不如让上层把它当异常报出来。
  ratio_ = std::clamp(ratio, 0.998, 1.002);
}

int Asrc::InputNeededFor(int out_frames) const {
  if (out_frames <= 0) return 0;
  return static_cast<int>(std::ceil(static_cast<double>(out_frames) * ratio_)) + 4;
}

int Asrc::AvailableInputFrames() const {
  // 注意要减掉 head_frames_：那是已消费但还没搬走的前缀，
  // 漏掉它会让控制器以为缓冲比实际更满，比率就会持续偏错。
  const int64_t usable_frames = static_cast<int64_t>(size_frames_ - head_frames_);
  if (usable_frames < 3) return 0;
  const double usable = static_cast<double>(usable_frames) - pos_ - 2.0;
  if (usable <= 0) return 0;
  return static_cast<int>(usable);
}

void Asrc::Push(const float* interleaved, int frames) {
  if (interleaved == nullptr || frames <= 0) return;

  // 定期把已消费的前缀搬掉，避免 buffer_ 无限增长。
  if (head_frames_ > 4096) {
    const size_t remain = size_frames_ - head_frames_;
    std::memmove(buffer_.data(), buffer_.data() + head_frames_ * channels_,
                 remain * channels_ * sizeof(float));
    buffer_.resize(remain * channels_);
    size_frames_ = remain;
    head_frames_ = 0;
  }

  const size_t need = size_frames_ * channels_;
  buffer_.resize(need + static_cast<size_t>(frames) * channels_);
  std::memcpy(buffer_.data() + need, interleaved,
              static_cast<size_t>(frames) * channels_ * sizeof(float));
  size_frames_ += static_cast<size_t>(frames);
}

bool Asrc::Pull(float* out, int frames) {
  if (out == nullptr || frames <= 0) return true;

  int produced = 0;
  while (produced < frames) {
    const size_t idx = static_cast<size_t>(pos_);
    // 需要 idx-1、idx、idx+1、idx+2 四个点（相对 head_frames_）
    if (head_frames_ + idx + 2 >= size_frames_) break;

    const float t = static_cast<float>(pos_ - static_cast<double>(idx));
    // base 一定要加上 head_frames_：head_frames_ 之前的数据已经被消费，
    // 但仍留在 buffer_ 里没搬走。漏掉这一项会让输出整体错位一个"已消费长度"。
    const size_t center = (head_frames_ + idx) * static_cast<size_t>(channels_);
    for (int c = 0; c < channels_; ++c) {
      const size_t base = center + static_cast<size_t>(c);
      out[static_cast<size_t>(produced) * channels_ + c] =
          CatmullRom(buffer_[base - channels_], buffer_[base], buffer_[base + channels_],
                     buffer_[base + 2 * channels_], t);
    }
    pos_ += ratio_;
    ++produced;

    // 保留 pos_ 前的一帧，供下一轮的 idx-1 使用
    const long consumed = static_cast<long>(pos_) - 1;
    if (consumed > 0) {
      head_frames_ += static_cast<size_t>(consumed);
      pos_ -= static_cast<double>(consumed);
    }
  }
  return produced == frames;
}

void Asrc::Reset() {
  buffer_.clear();
  head_frames_ = 0;
  size_frames_ = 0;
  pos_ = 1.0;
}

}  // namespace audiohub
