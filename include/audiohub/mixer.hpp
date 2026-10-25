// 多源混音与末端限幅。
//
// 混音在 float32 上做累加：多路音频直接相加很容易超过 ±1.0，
// 如果用定点或在过程中就夹紧，会得到硬削顶的"糊"声。
// 这里全程 float，最后过一遍软限幅。
#pragma once

#include <cmath>

namespace audiohub {

struct MixInput {
  const float* data = nullptr;  // 交织样本，frame 数由调用方保证
  float gain = 1.0f;
  bool active = true;
};

// 清零 out，然后把所有 active 的输入按 gain 累加进去。
void MixBlock(float* out, const MixInput* inputs, int num_inputs, int frames, int channels);

// 软限幅：阈值以下完全线性（不做任何染色），以上用 tanh 平滑压到 1.0。
// 比直接 clamp 好在没有硬拐点，比全局 tanh 好在不会压小信号。
inline float SoftLimitSample(float x, float threshold) {
  const float a = std::fabs(x);
  if (a <= threshold) return x;
  const float headroom = 1.0f - threshold;
  if (headroom <= 1e-6f) return x < 0 ? -threshold : threshold;
  const float shaped = threshold + headroom * std::tanh((a - threshold) / headroom);
  return x < 0 ? -shaped : shaped;
}

void SoftLimit(float* buffer, int sample_count, float threshold = 0.85f);

// dB -> 线性增益。静音用 -INFINITY 之外的值表示，避免产生 NaN。
inline float DbToGain(float db) { return db <= -60.0f ? 0.0f : std::pow(10.0f, db / 20.0f); }

}  // namespace audiohub
