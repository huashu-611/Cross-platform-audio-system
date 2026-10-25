#include <cmath>
#include <vector>

#include "audiohub/mixer.hpp"
#include "audiohub/pi_controller.hpp"
#include "test_framework.hpp"

TEST(Mixer_SumsWithGains) {
  const float a[4] = {0.5f, 0.5f, 0.5f, 0.5f};
  const float b[4] = {0.5f, 0.5f, 0.5f, 0.5f};
  const audiohub::MixInput inputs[2] = {{a, 1.0f, true}, {b, 0.5f, true}};

  float out[4] = {0};
  audiohub::MixBlock(out, inputs, 2, 2, 2);
  for (float v : out) CHECK_NEAR(v, 0.75, 1e-6);
}

TEST(Mixer_SkipsInactiveAndNull) {
  const float a[4] = {1.0f, 1.0f, 1.0f, 1.0f};
  const audiohub::MixInput inputs[3] = {
      {a, 1.0f, false}, {nullptr, 1.0f, true}, {a, 2.0f, true}};

  float out[4] = {0};
  audiohub::MixBlock(out, inputs, 3, 2, 2);
  for (float v : out) CHECK_NEAR(v, 2.0, 1e-6);
}

TEST(Mixer_EmptyInputClearsOutput) {
  float out[4] = {9, 9, 9, 9};
  audiohub::MixBlock(out, nullptr, 0, 2, 2);
  for (float v : out) CHECK_NEAR(v, 0.0, 1e-9);
}

TEST(Limiter_TransparentBelowThreshold) {
  float buf[3] = {0.5f, -0.5f, 0.84f};
  audiohub::SoftLimit(buf, 3, 0.85f);
  CHECK_NEAR(buf[0], 0.5, 1e-6);
  CHECK_NEAR(buf[1], -0.5, 1e-6);
  CHECK_NEAR(buf[2], 0.84, 1e-6);
}

TEST(Limiter_BoundsAboveThreshold) {
  float buf[4] = {5.0f, -5.0f, 1.2f, -1.2f};
  audiohub::SoftLimit(buf, 4, 0.85f);
  for (float v : buf) {
    // 软限幅的渐近线就是 1.0，所以是 <= 而不是 <：
    // 输入足够大时 tanh 饱和，输出会精确等于 1.0。
    CHECK_TRUE(std::fabs(v) <= 1.0f);
    CHECK_TRUE(std::fabs(v) > 0.84f);
  }
}

TEST(Limiter_IsSymmetric) {
  float buf[2] = {3.0f, -3.0f};
  audiohub::SoftLimit(buf, 2, 0.85f);
  CHECK_NEAR(buf[0], -buf[1], 1e-6);
}

TEST(DbToGain_Basics) {
  CHECK_NEAR(audiohub::DbToGain(0.0f), 1.0, 1e-6);
  CHECK_NEAR(audiohub::DbToGain(-6.0f), 0.5012, 1e-3);
  CHECK_NEAR(audiohub::DbToGain(-60.0f), 0.0, 1e-9);
}

TEST(PiController_ClampsOutput) {
  audiohub::PiController pi(1.0, 0.0, -0.002, 0.002);
  CHECK_NEAR(pi.Update(0.01, 0.2), 0.002, 1e-9);
  CHECK_NEAR(pi.Update(-0.01, 0.2), -0.002, 1e-9);
}

TEST(PiController_IntegralRemovesSteadyStateError) {
  // 只有比例项时，持续误差下永远留一个偏差；积分项负责把它吃掉。
  audiohub::PiController pi(0.0, 0.001, -0.002, 0.002);
  double out = 0;
  for (int i = 0; i < 100; ++i) out = pi.Update(5.0, 0.2);
  // 5 ms 误差 × 0.001 × 0.2 s × 100 次 = 0.1，但被限幅到 0.002
  CHECK_NEAR(out, 0.002, 1e-9);
  CHECK_NEAR(pi.integral(), 0.002, 1e-9);

  pi.Reset();
  CHECK_NEAR(pi.integral(), 0.0, 1e-12);
}
