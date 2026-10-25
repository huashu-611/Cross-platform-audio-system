#include <cmath>
#include <vector>

#include "audiohub/asrc.hpp"
#include "test_framework.hpp"

namespace {
constexpr int kCh = 2;

std::vector<float> MakeRamp(int frames) {
  std::vector<float> v(static_cast<size_t>(frames) * kCh);
  for (int i = 0; i < frames; ++i) {
    v[static_cast<size_t>(i) * kCh + 0] = static_cast<float>(i);
    v[static_cast<size_t>(i) * kCh + 1] = static_cast<float>(i) * 0.5f;
  }
  return v;
}
}  // namespace

TEST(Asrc_UnityRatioPassesThrough) {
  audiohub::Asrc asrc(kCh);
  asrc.SetRatio(1.0);

  const auto in = MakeRamp(1000);
  asrc.Push(in.data(), 1000);

  std::vector<float> out(static_cast<size_t>(500) * kCh);
  CHECK_TRUE(asrc.Pull(out.data(), 500));

  // 实现里固定保留 1 帧供插值回看，所以输出相对输入有 1 帧（0.02 ms）的偏移。
  // 这是可接受的常量延迟，但必须被测试固定住——否则将来改动会静默地引入更大偏移。
  for (int i = 0; i < 499; ++i) {
    CHECK_NEAR(out[static_cast<size_t>(i) * kCh + 0], in[static_cast<size_t>(i + 1) * kCh + 0], 1e-3);
  }
}

TEST(Asrc_RatioIsClampedToSaneRange) {
  audiohub::Asrc asrc(kCh);
  asrc.SetRatio(5.0);
  CHECK_NEAR(asrc.ratio(), 1.002, 1e-9);
  asrc.SetRatio(0.1);
  CHECK_NEAR(asrc.ratio(), 0.998, 1e-9);
  asrc.SetRatio(1.0005);
  CHECK_NEAR(asrc.ratio(), 1.0005, 1e-9);
}

TEST(Asrc_PullWithoutInputReturnsFalse) {
  audiohub::Asrc asrc(kCh);
  std::vector<float> out(static_cast<size_t>(240) * kCh, 0.0f);
  CHECK_FALSE(asrc.Pull(out.data(), 240));
  CHECK_EQ(asrc.AvailableInputFrames(), 0);
}

TEST(Asrc_HigherRatioConsumesMoreInput) {
  audiohub::Asrc asrc(kCh);
  asrc.SetRatio(1.002);

  asrc.Push(MakeRamp(48000).data(), 48000);
  const int before = asrc.AvailableInputFrames();

  std::vector<float> out(static_cast<size_t>(1000) * kCh);
  CHECK_TRUE(asrc.Pull(out.data(), 1000));

  const int after = asrc.AvailableInputFrames();
  // 1000 个输出帧、比率 1.002 -> 应当消耗约 1002 个输入帧
  CHECK_NEAR(before - after, 1002, 2);
}

TEST(Asrc_LowerRatioConsumesLessInput) {
  audiohub::Asrc asrc(kCh);
  asrc.SetRatio(0.998);

  asrc.Push(MakeRamp(48000).data(), 48000);
  const int before = asrc.AvailableInputFrames();

  std::vector<float> out(static_cast<size_t>(1000) * kCh);
  CHECK_TRUE(asrc.Pull(out.data(), 1000));

  const int after = asrc.AvailableInputFrames();
  // 比率 < 1 意味着源偏快，同样的输出只需要更少的输入
  CHECK_NEAR(before - after, 998, 2);
}

TEST(Asrc_OutputIsFiniteOnSine) {
  audiohub::Asrc asrc(kCh);
  asrc.SetRatio(1.001);

  std::vector<float> in(static_cast<size_t>(4800) * kCh);
  for (int i = 0; i < 4800; ++i) {
    const float s = std::sin(2.0 * 3.14159265358979 * 1000.0 * i / 48000.0);
    in[static_cast<size_t>(i) * kCh + 0] = s;
    in[static_cast<size_t>(i) * kCh + 1] = s;
  }
  asrc.Push(in.data(), 4800);

  std::vector<float> out(static_cast<size_t>(480) * kCh);
  CHECK_TRUE(asrc.Pull(out.data(), 480));
  for (float v : out) {
    CHECK_TRUE(std::isfinite(v));
    CHECK_TRUE(std::fabs(v) <= 1.5f);
  }
}
