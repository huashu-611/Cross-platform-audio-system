#include "audiohub/mixer.hpp"

#include <cstring>

namespace audiohub {

void MixBlock(float* out, const MixInput* inputs, int num_inputs, int frames, int channels) {
  const size_t total = static_cast<size_t>(frames) * channels;
  std::memset(out, 0, total * sizeof(float));

  for (int i = 0; i < num_inputs; ++i) {
    const MixInput& in = inputs[i];
    if (!in.active || in.data == nullptr || in.gain == 0.0f) continue;
    const float* src = in.data;
    for (size_t s = 0; s < total; ++s) {
      out[s] += src[s] * in.gain;
    }
  }
}

void SoftLimit(float* buffer, int sample_count, float threshold) {
  if (threshold <= 0.0f || threshold >= 1.0f) threshold = 0.85f;
  for (int i = 0; i < sample_count; ++i) {
    buffer[i] = SoftLimitSample(buffer[i], threshold);
  }
}

}  // namespace audiohub
