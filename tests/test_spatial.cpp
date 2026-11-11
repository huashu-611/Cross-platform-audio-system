// build/_plan/test_spatial_draft.cpp —— 拟放入 tests/test_spatial.cpp 的用例清单（可编译验证）。
// 断言值来自 build/_plan/spatial_probe.exe 实测输出。
#include <cmath>
#include <vector>

#include "audiohub/spatial.hpp"
#include "test_framework.hpp"

using namespace audiohub;

namespace {
constexpr float kTol = 1e-4f;

void Fill(std::vector<float>* buf, int frames, float l, float r) {
  for (int i = 0; i < frames; ++i) {
    (*buf)[2 * i] = l;
    (*buf)[2 * i + 1] = r;
  }
}
}  // namespace

TEST(SpatialPanGainsAcrossCircle) {
  float gl = 0.0f, gr = 0.0f;
  PanGains(0.0f, 1.0f, &gl, &gr);
  CHECK_NEAR(gl, 0.70711f, kTol);
  CHECK_NEAR(gr, 0.70711f, kTol);

  PanGains(90.0f, 1.0f, &gl, &gr);
  CHECK_NEAR(gl, 0.0f, kTol);
  CHECK_NEAR(gr, 1.0f, kTol);

  PanGains(-90.0f, 1.0f, &gl, &gr);
  CHECK_NEAR(gl, 1.0f, kTol);
  CHECK_NEAR(gr, 0.0f, kTol);

  PanGains(45.0f, 1.0f, &gl, &gr);
  CHECK_NEAR(gl, 0.38268f, kTol);
  CHECK_NEAR(gr, 0.92388f, kTol);

  PanGains(135.0f, 1.0f, &gl, &gr);
  CHECK_NEAR(gl, 0.38268f, kTol);
  CHECK_NEAR(gr, 0.92388f, kTol);

  // 正后方回中置（不是硬右），只靠后向线索
  PanGains(180.0f, 1.0f, &gl, &gr);
  CHECK_NEAR(gl, 0.70711f, kTol);
  CHECK_NEAR(gr, 0.70711f, kTol);
  CHECK_NEAR(PanValue(180.0f, 1.0f), 0.0f, kTol);

  // 全圆等功率
  for (float az = -180.0f; az <= 180.0f; az += 15.0f) {
    PanGains(az, 1.0f, &gl, &gr);
    CHECK_NEAR(gl * gl + gr * gr, 1.0f, 2e-5f);
  }
}

// v0.46 口径（用户定稿）：0.3 m = 100%，2 m = 1%。
//   r0 = kDistanceRefM = 0.3 m，p = 2.4274533，封顶 1.0（取消原来的 +6 dB 近场升压）。
//   断言值来自 float32 复算：min((0.3f/max(d,0.3f))**2.4274533, 1.0)
TEST(SpatialDistanceGainClamps) {
  CHECK_NEAR(DistanceGain(0.3f), 1.0f, kTol);       // 参考距离 = 100%（0 dB）
  CHECK_NEAR(DistanceGain(0.5f), 0.289383f, kTol);  // -10.77 dB
  CHECK_NEAR(DistanceGain(0.75f), 0.108148f, kTol); // -19.32 dB
  CHECK_NEAR(DistanceGain(1.0f), 0.053794f, kTol);  // -25.39 dB
  CHECK_NEAR(DistanceGain(2.0f), 0.010000f, kTol);  // -40.00 dB ← 用户指定 1%
  CHECK_NEAR(DistanceGain(3.0f), 0.003737f, kTol);  // -48.55 dB
  CHECK_NEAR(DistanceGain(10.0f), 0.000201f, kTol); // -73.93 dB
  // 近场不再升压：贴到 0 也只是 100%（0.3 m 以内全部封顶）。
  CHECK_NEAR(DistanceGain(0.2f), 1.0f, kTol);
  CHECK_NEAR(DistanceGain(0.05f), 1.0f, kTol);
  CHECK_NEAR(DistanceGain(0.0f), 1.0f, kTol);       // 除零保护
  CHECK_NEAR(20.0f * std::log10(DistanceGain(2.0f)), -40.0f, 0.02f);
  // 单调不增，且永远不会是 0（拉得再远也不至于全哑）。
  for (float d = 0.4f; d < 300.0f; d *= 1.7f) {
    CHECK_TRUE(DistanceGain(d) > 0.0f);
    CHECK_TRUE(DistanceGain(d) <= DistanceGain(d / 1.7f) + 1e-6f);
  }
}

TEST(SpatialItdFullCircle) {
  CHECK_NEAR(ItdSeconds(0.0f), 0.0f, 1e-9f);
  CHECK_NEAR(ItdSeconds(30.0f) * 48000.0f, 15.12f, 0.05f);
  CHECK_NEAR(ItdSeconds(60.0f) * 48000.0f, 26.18f, 0.05f);
  CHECK_NEAR(ItdSeconds(90.0f) * 48000.0f, 30.24f, 0.05f);
  CHECK_NEAR(ItdSeconds(120.0f) * 48000.0f, 26.18f, 0.05f);  // 后半球回落
  CHECK_NEAR(ItdSeconds(180.0f), 0.0f, 1e-6f);               // 正后方 0
  CHECK_NEAR(ItdSeconds(-90.0f), -ItdSeconds(90.0f), 1e-6f); // 左右对称
}

TEST(SpatialRearCue) {
  CHECK_NEAR(RearFactor(0.0f), 0.0f, kTol);    // 正前无线索
  CHECK_NEAR(RearFactor(90.0f), 0.0f, kTol);   // 正侧无线索
  CHECK_NEAR(RearFactor(180.0f), 1.0f, kTol);  // 正后最强
  CHECK_NEAR(RearCutoffHz(0.0f), 20000.0f, 1.0f);
  // v0.46：后向低通放宽到 6 kHz（4 kHz 会把"身后"糊成同一团），
  // 判决权交给陷波与早期反射；这里只断言"越后越闷"的单调底色。
  CHECK_NEAR(RearCutoffHz(1.0f), 6000.0f, 1.0f);
  CHECK_NEAR(RearCutoffHz(0.5f), 10954.451f, 1.0f);  // 几何中点 sqrt(20k*6k)
}

TEST(SpatialListenerYawRotatesScene) {
  const ListenerPose listener = DefaultListener();
  CHECK_NEAR(listener.position.x, 0.0f, kTol);
  CHECK_NEAR(listener.position.y, 0.0f, kTol);
  CHECK_NEAR(listener.position.z, 0.0f, kTol);
  CHECK_NEAR(listener.yaw_deg, 0.0f, kTol);

  // 屏幕上方 = 远处 = 正前（-Z）
  Geo g = AnalyzePosition(Vec3{0.0f, 0.0f, -1.0f}, listener);
  CHECK_NEAR(g.azimuth_deg, 0.0f, 1e-3f);
  CHECK_NEAR(g.distance, 1.0f, kTol);

  // 屏幕右侧 = +X = az=+90
  g = AnalyzePosition(Vec3{1.0f, 0.0f, 0.0f}, listener);
  CHECK_NEAR(g.azimuth_deg, 90.0f, 1e-3f);

  // 屏幕下方 = +Z = 身后 = az=180
  g = AnalyzePosition(Vec3{0.0f, 0.0f, 1.0f}, listener);
  CHECK_NEAR(std::fabs(g.azimuth_deg), 180.0f, 1e-3f);
  CHECK_NEAR(RearFactor(g.azimuth_deg), 1.0f, kTol);

  // 相机 orbit 90 度：听者 yaw 跟随，正前节点变成右侧
  ListenerPose turned = listener;
  turned.yaw_deg = 90.0f;
  g = AnalyzePosition(Vec3{0.0f, 0.0f, -1.0f}, turned);
  CHECK_NEAR(g.azimuth_deg, 90.0f, 1e-3f);

  // 抬头：世界正上方的节点落到局部前方（el=0）；世界正前方的节点落到局部下方（el=-90）
  ListenerPose up = listener;
  up.pitch_deg = 90.0f;
  g = AnalyzePosition(Vec3{0.0f, 1.0f, 0.0f}, up);
  CHECK_NEAR(g.elevation_deg, 0.0f, 0.5f);
  g = AnalyzePosition(Vec3{0.0f, 0.0f, -1.0f}, up);
  CHECK_NEAR(g.elevation_deg, -90.0f, 0.5f);
}

TEST(SpatialMonoDownmixAndMakeup) {
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  // v0.45：目标放在参考距离 0.1 m —— 该处距离增益正好 1.0，
  // 于是这条用例只考"单声道化 + 等功率 + makeup"，不受距离曲线影响。
  st.SetTargetPosition(Vec3{0.0f, 0.0f, -kDistanceRefM});
  std::vector<float> buf(480);
  for (int k = 0; k < 20; ++k) {
    Fill(&buf, 240, 1.0f, 1.0f);
    st.Process(buf.data(), 240, listener, params, 1.0f);
  }
  // 居中素材：mid=1，等功率 0.7071 * √2 = 1.0
  CHECK_NEAR(buf[478], 1.0f, 2e-3f);
  CHECK_NEAR(buf[479], 1.0f, 2e-3f);

  SpatialState st2;
  st2.Reset(48000.0f);
  st2.SetTargetPosition(Vec3{0.0f, 0.0f, -kDistanceRefM});
  for (int k = 0; k < 20; ++k) {
    Fill(&buf, 240, 1.0f, 0.0f);  // 硬左素材：单声道化后 mid=0.5 -> 双耳各 0.5
    st2.Process(buf.data(), 240, listener, params, 1.0f);
  }
  CHECK_NEAR(buf[478], 0.5f, 2e-3f);
  CHECK_NEAR(buf[479], 0.5f, 2e-3f);
}

TEST(SpatialUserGainFoldsIntoOutput) {
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  st.SetTargetPosition(Vec3{0.0f, 0.0f, -kDistanceRefM});  // 参考距离，距离增益 = 1.0
  std::vector<float> buf(480);
  // 60 块 = 300 ms = 6*tau，平滑收敛到 0.5（tau=50 ms）
  for (int k = 0; k < 60; ++k) {
    Fill(&buf, 240, 1.0f, 1.0f);
    st.Process(buf.data(), 240, listener, params, 0.5f);  // 用户音量 0.5
  }
  // 调用方必须把 MixInput.gain 置 1，否则音量乘两次
  CHECK_NEAR(buf[478], 0.5f, 2e-3f);
  CHECK_NEAR(buf[479], 0.5f, 2e-3f);
}

TEST(SpatialBypassPassthrough) {
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  st.SetTargetPosition(Vec3{1.0f, 0.0f, 0.0f});  // 即使位置在右侧
  st.SetEnabled(false);
  CHECK_FALSE(st.enabled());
  std::vector<float> buf(480);
  Fill(&buf, 240, 1.0f, 0.0f);
  st.Process(buf.data(), 240, listener, params, 1.0f);
  CHECK_NEAR(buf[0], 1.0f, 1e-6f);  // 原样直通，采样未被改写
  CHECK_NEAR(buf[1], 0.0f, 1e-6f);
}

TEST(SpatialProcessWithoutTargetIsNoOp) {
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  CHECK_FALSE(st.has_target());
  std::vector<float> buf(480);
  Fill(&buf, 240, 0.3f, -0.4f);
  st.Process(buf.data(), 240, listener, params, 1.0f);
  CHECK_NEAR(buf[0], 0.3f, 1e-6f);
  CHECK_NEAR(buf[1], -0.4f, 1e-6f);
}

TEST(SpatialItdActualDelay) {
  const int kSettle = 30, kBlocks = 12, kFrames = 240, kImpulseInBlock = 100;
  std::vector<float> out_l(kBlocks * kFrames, 0.0f), out_r(kBlocks * kFrames, 0.0f);
  std::vector<float> buf(kFrames * 2);
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  st.SetTargetPosition(Vec3{std::sin(60.0f * kDegToRad), 0.0f, -std::cos(60.0f * kDegToRad)});
  for (int k = 0; k < kSettle; ++k) {
    for (float& v : buf) v = 0.0f;
    st.Process(buf.data(), kFrames, listener, params, 1.0f);
  }
  for (int k = 0; k < kBlocks; ++k) {
    Fill(&buf, kFrames, 0.0f, 0.0f);
    if (k == 0) buf[2 * kImpulseInBlock] = buf[2 * kImpulseInBlock + 1] = 1.0f;
    st.Process(buf.data(), kFrames, listener, params, 1.0f);
    for (int i = 0; i < kFrames; ++i) {
      out_l[k * kFrames + i] = buf[2 * i];
      out_r[k * kFrames + i] = buf[2 * i + 1];
    }
  }
  int pl = 0, pr = 0;
  for (int i = 1; i < kBlocks * kFrames; ++i) {
    if (std::fabs(out_l[i]) > std::fabs(out_l[pl])) pl = i;
    if (std::fabs(out_r[i]) > std::fabs(out_r[pr])) pr = i;
  }
  CHECK_NEAR(st.delay_l_frames(), 26.18f, 0.1f);  // az=60 理想 ITD
  CHECK_EQ(pr, kImpulseInBlock);                  // 近耳不延时
  CHECK_EQ(pl - pr, 26);                          // 远耳延时 26 帧（分数延时峰值取整）
}

TEST(SpatialSmoothingAndDelaySlew) {
  std::vector<float> buf(480);
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  st.SetTargetPosition(Vec3{0.0f, 0.0f, -kDistanceRefM});  // 正前（参考距离，距离增益 1.0）
  for (int k = 0; k < 40; ++k) {
    for (float& v : buf) v = 0.0f;
    st.Process(buf.data(), 240, listener, params, 1.0f);
  }
  CHECK_NEAR(st.gain_l(), 1.0f, 1e-3f);  // 正前每耳 1.0
  st.SetTargetPosition(Vec3{kDistanceRefM, 0.0f, 0.0f});  // 突跳到正右侧（左耳目标 0）
  st.Process(buf.data(), 240, listener, params, 1.0f);
  CHECK_NEAR(st.gain_l(), 0.9048f, 0.01f);  // 5 ms 只走 9.5%，无台阶
  // ITD 从 0 -> 30.24 帧，限速 2 帧/块
  CHECK_NEAR(st.delay_l_frames(), 2.0f, 0.1f);
  for (int k = 0; k < 20; ++k) st.Process(buf.data(), 240, listener, params, 1.0f);
  CHECK_NEAR(st.delay_l_frames(), 30.24f, 0.1f);
}

TEST(SpatialResetClearsState) {
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  st.SetTargetPosition(Vec3{1.0f, 0.0f, 0.0f});
  std::vector<float> buf(480);
  Fill(&buf, 240, 1.0f, 1.0f);
  st.Process(buf.data(), 240, listener, params, 1.0f);
  st.Reset(48000.0f);
  CHECK_FALSE(st.has_target());
  CHECK_NEAR(st.delay_l_frames(), 0.0f, 1e-6f);
  CHECK_NEAR(st.delay_r_frames(), 0.0f, 1e-6f);
}

TEST(SpatialDefaultLayoutStable) {
  const Vec3 hub = DefaultScenePlacement("192.168.1.10:47000", true);
  CHECK_NEAR(hub.x, 0.0f, 1e-6f);
  CHECK_NEAR(hub.y, 0.0f, 1e-6f);
  CHECK_NEAR(hub.z, 0.0f, 1e-6f);
  for (const char* id : {"192.168.1.11:47000", "192.168.1.12:47000", "android-1"}) {
    const Vec3 a = DefaultScenePlacement(id, false);
    const Vec3 b = DefaultScenePlacement(id, false);
    CHECK_NEAR(a.x, b.x, 1e-6f);  // 同 id 稳定（不依赖图谱遍历顺序）
    CHECK_NEAR(a.z, b.z, 1e-6f);
    CHECK_NEAR(std::sqrt(a.x * a.x + a.z * a.z), 1.0f, 1e-4f);  // 在 R=1 圆环上
    CHECK_NEAR(a.y, 0.0f, 1e-6f);
  }
}

TEST(SpatialDelayRampHasNoBlockJumps) {
  // 拖动节点时 ITD 每块都在变。如果只在块边界改延时，读指针会每 5~10 ms 被拽 1~2
  // 个样点，宽带/高频内容上就是"嘶啦"的电流声；逐样本铺开后读指针才是连续的。
  //
  // 判据：正弦的"接缝残差" r[n] = y[n] - 2*y[n-1] + y[n-2]。
  //   慢速正弦的块内残差恒为 A*(2πf/fs)^2 量级；延时在块边界跳 2 帧会把它抬高一个量级。
  // 信号频率故意取 503 Hz（与 480 帧块长不整除），让块边界的正弦相位扫遍全周，避免
  // 撞在零交叉上把台阶掩蔽掉——第一版用 9 kHz（每块恰好 90 周）和 ±90° 甩动都栽在这。
  const int kFrames = 480;
  const float kAmp = 0.3f;
  const float kFreq = 503.0f;
  const double two_pi = 6.28318530717958647692;
  std::vector<float> buf(static_cast<size_t>(kFrames) * 2);
  SpatialState st;
  st.Reset(48000.0f);
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  for (int k = 0; k < 40; ++k) {  // 先让增益/延时平滑量收敛
    for (float& v : buf) v = 0.0f;
    st.Process(buf.data(), kFrames, listener, params, 1.0f);
  }

  // 匀速拖动：每块转 0.75°，延时以 2 帧/块的恒定速度滑动（最坏情况），不会来回抖动。
  const int kBlocks = 240;
  float prev[2] = {0.0f, 0.0f};       // y[n-1]
  float prev2[2] = {0.0f, 0.0f};      // y[n-2]
  int have = 0;
  float max_boundary = 0.0f, max_interior = 0.0f;
  long long n = 0;
  for (int k = 0; k < kBlocks; ++k) {
    const float deg = -90.0f + 180.0f * static_cast<float>(k) / static_cast<float>(kBlocks - 1);
    const float rad = deg * kPi / 180.0f;
    st.SetTargetPosition(Vec3{std::sin(rad), 0.0f, -std::cos(rad)});
    for (int i = 0; i < kFrames; ++i) {
      const float v = kAmp * static_cast<float>(std::sin(two_pi * kFreq * (static_cast<double>(n) / 48000.0)));
      buf[2 * i] = buf[2 * i + 1] = v;
      ++n;
    }
    st.Process(buf.data(), kFrames, listener, params, 1.0f);
    for (int i = 0; i < kFrames; ++i) {
      for (int c = 0; c < 2; ++c) {
        const float y = buf[2 * i + c];
        if (have >= 2) {
          const float r = std::fabs(y - 2.0f * prev[c] + prev2[c]);
          // 块首两个样本的残差都跨接缝，都算 boundary。
          float& bucket = (i < 2) ? max_boundary : max_interior;
          bucket = std::max(bucket, r);
        }
        prev2[c] = prev[c];
        prev[c] = y;
      }
      if (have < 2) ++have;
    }
  }
  // 平滑极限：A*(2πf/fs)^2*增益 ≈ 0.3*0.00434*1.4 ≈ 0.0018。
  // 块常量延时的旧实现实测 0.032（18 倍），逐样本铺开后 0.0018。
  CHECK_TRUE(max_interior > 0.001f);                 // 有信号、判据有效
  CHECK_TRUE(max_boundary < 0.01f);                  // 旧实现 0.032 -> 红
}

// ------------------------------------------------ v0.46：前后可区分度
//
// 这组用例是"能不能听出前后"的**量化**判据，不是"跑起来不崩"。
// 方法：把一路冲激打进 SpatialState，抓左耳冲激响应 h[n]，再对若干关键频点做单频
// DTFT 求 |H(f)|。为什么必须用冲激响应而不是稳态正弦：后向链是 LTI 的（系数只是
// 每块重算，块内恒定），冲激响应完整包含了"闷化 + 陷波 + 早期反射"三者的频响；
// 直接在稳态里量 7 kHz 会因为陷波把该频点压得太狠，读数全被底噪吃掉。
// 为什么只取左耳：两耳之间是纯延时（ITD），左右相加会形成梳状，把谱形状搅乱。
namespace {

constexpr int kIrFs = 48000;
constexpr int kIrFrames = 8192;  // > 最长反射 874 帧 + 余量

// 单频点 DTFT：|sum h[n] e^{-jwn}|。
float IrMagAt(const std::vector<float>& h, float freq_hz) {
  const double w = 2.0 * 3.14159265358979323846 * static_cast<double>(freq_hz) / kIrFs;
  double re = 0.0, im = 0.0;
  for (size_t n = 0; n < h.size(); ++n) {
    const double a = w * static_cast<double>(n);
    re += h[n] * std::cos(a);
    im -= h[n] * std::sin(a);
  }
  return static_cast<float>(std::sqrt(re * re + im * im));
}

float IrDb(const std::vector<float>& h, float freq_hz, float ref) {
  return 20.0f * std::log10(IrMagAt(h, freq_hz) / ref);
}

// 抓某方位的左耳冲激响应（先跑 1 s 静音让块级平滑量收敛）。
std::vector<float> CaptureLeftIr(float azimuth_deg) {
  SpatialState st;
  st.Reset(static_cast<float>(kIrFs));
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  const float rad = azimuth_deg * kPi / 180.0f;
  st.SetTargetPosition(Vec3{std::sin(rad) * kDistanceRefM, 0.0f, -std::cos(rad) * kDistanceRefM});
  std::vector<float> buf(240 * 2);
  for (int k = 0; k < 200; ++k) {
    for (float& v : buf) v = 0.0f;
    st.Process(buf.data(), 240, listener, params, 1.0f);
  }
  std::vector<float> ir;
  ir.reserve(kIrFrames);
  for (float& v : buf) v = 0.0f;
  buf[0] = buf[1] = 1.0f;  // 冲激：L=R=1 -> 单声道化后 mid=1
  st.Process(buf.data(), 240, listener, params, 1.0f);
  for (int i = 0; i < 240; ++i) ir.push_back(buf[2 * i]);
  while (static_cast<int>(ir.size()) < kIrFrames) {
    for (float& v : buf) v = 0.0f;
    st.Process(buf.data(), 240, listener, params, 1.0f);
    for (int i = 0; i < 240 && static_cast<int>(ir.size()) < kIrFrames; ++i) {
      ir.push_back(buf[2 * i]);
    }
  }
  return ir;
}

}  // namespace

TEST(SpatialRearNotchIsARealDip) {
  // ③ 耳廓陷波的核心判据：正后方在 7~8 kHz 必须真的凹下去，而不是"理论上有个 biquad"。
  // v0.45 基线只有一阶低通，这段频带是单调下坡，没有局部凹陷（实测最深 -0.2 dB）。
  const std::vector<float> front = CaptureLeftIr(0.0f);
  const std::vector<float> back = CaptureLeftIr(180.0f);
  const float ref = IrMagAt(front, 1000.0f);

  // 陷波中心随 rear 从 6 kHz 抬到 8 kHz；正后方 sm_rear_=1 -> 中心 8 kHz。
  const float at_center = IrDb(back, 8000.0f, ref);
  CHECK_TRUE(at_center < -8.0f);   // 实测 -17.6 dB，-8 是"能明显听出闷"的下限

  // 局部凹陷：中心处必须明显比两侧低（否则只是整体变闷，不是"陷波"）。
  const float at_low = IrDb(back, 3000.0f, ref);
  const float at_high = IrDb(back, 12000.0f, ref);
  CHECK_TRUE(at_center < at_low - 6.0f);   // 实测 8 kHz 比 3 kHz 低 14.7 dB
  CHECK_TRUE(at_center < at_high - 4.0f);  // 实测 8 kHz 比 12 kHz 低 8.0 dB

  // 正前方一点都不能动：rear=0 时 beta=0，陷波器逐位透明。
  CHECK_NEAR(IrDb(front, 8000.0f, ref), 0.0f, 1e-4f);
  CHECK_NEAR(IrDb(front, 6000.0f, ref), 0.0f, 1e-4f);
}

TEST(SpatialRearEarlyReflectionsArriveLate) {
  // ④ 早期反射的判据：正后方的冲激响应在 7~19 ms 之间必须有能量的"第二次到达"，
  // 而正前方必须干干净净（只有 0 时刻那一下）。
  // 注意：这里只证明"反射真的进来了"，不证明"听起来更像身后"——后者靠听感。
  const std::vector<float> front = CaptureLeftIr(0.0f);
  const std::vector<float> back = CaptureLeftIr(180.0f);
  auto energy_between = [](const std::vector<float>& h, int from, int to) {
    double e = 0.0;
    for (int n = from; n < to && n < static_cast<int>(h.size()); ++n) e += static_cast<double>(h[n]) * h[n];
    return static_cast<float>(std::sqrt(e));
  };
  const int ms7 = kIrFs * 7 / 1000;    // 336 帧
  const int ms20 = kIrFs * 20 / 1000;  // 960 帧
  const float front_tail = energy_between(front, ms7, ms20);
  const float back_tail = energy_between(back, ms7, ms20);
  CHECK_TRUE(back_tail > 0.05f);              // 实测 0.1046（两路反射都落在窗内）
  CHECK_TRUE(back_tail > 20.0f * front_tail);  // 前方这条窗内恒为 0（rear=0 时后向链逐位关闭）

  // 反射延迟随 rear 变长：第一路 7.5→11 ms，第二路 12.4→18.2 ms。
  // 判据用 135°/150° 这两个"真有反射"的方位来比，而不是 45°——
  //   45° 在前半球，rear=0，后向链整条是关的，拿它比等于跟数值零比，测不出方向性。
  // 实测 7~20 ms 能量：180°=0.1046，150°=0.0677，135°=0.0488（rear 越大反射越强）。
  const std::vector<float> br = CaptureLeftIr(135.0f);
  const std::vector<float> b150 = CaptureLeftIr(150.0f);
  CHECK_TRUE(back_tail > 1.5f * energy_between(br, ms7, ms20));    // 实测 2.14×
  CHECK_TRUE(back_tail > 1.4f * energy_between(b150, ms7, ms20));  // 实测 1.55×

  // 第一路反射的到达时刻：10~12 ms 窗内正后方能量必须高于 135°（延迟更晚、更弱）。
  const int ms10 = kIrFs * 10 / 1000;
  const int ms12 = kIrFs * 12 / 1000;
  CHECK_TRUE(energy_between(back, ms10, ms12) > 1.5f * energy_between(br, ms10, ms12));
}

TEST(SpatialFrontBackSpectralDistance) {
  // 总判据：把"前"和"后"的频响逐点相减，谱距的 RMS 就是"两个方位听起来有多不一样"。
  // 只比形状不比电平：先把每个位置按 1 kHz 对齐到 0 dB，再算 500 Hz..12 kHz 的 RMS。
  // 这样即使后向增益一样（都是 0.7），谱距也能量出纯音色差异。
  const float bands[] = {500.0f, 1000.0f, 2000.0f, 3000.0f, 4000.0f,
                         6000.0f, 7000.0f, 8000.0f, 10000.0f, 12000.0f};
  const int nb = 10;

  const std::vector<float> front = CaptureLeftIr(0.0f);
  const std::vector<float> fr = CaptureLeftIr(45.0f);
  const std::vector<float> br = CaptureLeftIr(135.0f);
  const std::vector<float> back = CaptureLeftIr(180.0f);

  // 每个位置先按**自己**的 1 kHz 归一到 0 dB，再相减。
  //   为什么不能用同一个全局 ref：那样会把"左右声像增益"也算进谱距。
  //   45° 时左耳被压到 -7.7 dB，用全局 ref 会让"斜前"凭空多出 6.2 dB 谱距，
  //   于是斜前(6.18) 与斜后(9.44) 看起来差不多，单调性被噪声淹没（实测踩过）。
  //   按位置归一之后只剩"音色形状"的差异：0.0 / 1.17(45°) / 4.51(135°) / 7.93(180°)。
  auto spectral_distance = [&](const std::vector<float>& a, const std::vector<float>& b) {
    const float ra = IrMagAt(a, 1000.0f);
    const float rb = IrMagAt(b, 1000.0f);
    double acc = 0.0;
    for (int i = 0; i < nb; ++i) {
      const float d = IrDb(b, bands[i], rb) - IrDb(a, bands[i], ra);
      acc += static_cast<double>(d) * d;
    }
    return static_cast<float>(std::sqrt(acc / nb));
  };

  // 正前 vs 正后：v0.45 基线 4.70 dB，v0.46 7.93 dB。判据定在 6.5（基线会红）。
  CHECK_TRUE(spectral_distance(front, back) > 6.5f);
  // 斜前 vs 斜后（45° vs 135°）：基线 2.66 dB（几乎听不出），v0.46 3.65 dB。
  CHECK_TRUE(spectral_distance(fr, br) > 3.2f);

  // 后半球整段都要"明显不像前方"，而不只是正后方一个点：
  //   实测 135° 4.51 / 150° 4.81 / 165° 6.68 / 180° 7.93（基线分别 2.66 / 2.93 / 3.76 / 4.70）。
  //   注意别把 az=120(2.18) 也放进这个循环 —— 那里 rear 刚过 0.68，线索本来就该弱。
  for (float az = 135.0f; az <= 180.0f; az += 15.0f) {
    const std::vector<float> h = CaptureLeftIr(az);
    CHECK_TRUE(spectral_distance(front, h) > 4.0f);
  }
  // 正后 vs 斜后也要能分开（否则"身后"还是糊成一团：基线 3.05 dB，v0.46 4.97 dB）。
  CHECK_TRUE(spectral_distance(back, br) > 4.0f);
}

TEST(SpatialRearChainLeavesItdIntact) {
  // 后向染色绝不能破坏 ITD —— 它是唯一靠得住的前后无关线索。
  // 判据：45° 与 135° 的冲激响应峰值位置必须一致（都在 21 帧 = 0.438 ms），
  // 因为 |sin az| 相同，ITD 符号相反但绝对值相同；左耳在 45° 领先、135° 落后，
  // 这里比的是"绝对滞后"，两者应当相等。
  auto peak_lag = [](const std::vector<float>& h) {
    int best = 0;
    for (size_t n = 0; n < h.size(); ++n) {
      if (std::fabs(h[n]) > std::fabs(h[static_cast<size_t>(best)])) best = static_cast<int>(n);
    }
    return best;
  };
  CHECK_EQ(peak_lag(CaptureLeftIr(180.0f)), 0);   // 正后方 ITD = 0
  const int lag45 = peak_lag(CaptureLeftIr(45.0f));
  const int lag135 = peak_lag(CaptureLeftIr(135.0f));
  CHECK_EQ(lag45, 21);   // sin(45°)*30.24 ≈ 21.4 帧
  CHECK_EQ(lag135, 21);  // 后向链只加"谱"，不加时延
}

TEST(SpatialRearChainIsBypassedAtFront) {
  // rear=0 时后向链必须**逐位**透明：这是跨端一致性的硬约束，也是"前方音色一点不动"
  // 的保证。做法：正前方恒定直流块，逐个断言输出与"只有等功率 + makeup"的理论值相等。
  // 若陷波的 beta 在 rear=0 时不是精确 0，或反射读历史时引入任何偏移，这里会立刻变红。
  SpatialState st;
  st.Reset(static_cast<float>(kIrFs));
  SpatialParams params;
  const ListenerPose listener = DefaultListener();
  st.SetTargetPosition(Vec3{0.0f, 0.0f, -kDistanceRefM});
  std::vector<float> buf(240 * 2);
  for (int k = 0; k < 40; ++k) {
    Fill(&buf, 240, 1.0f, 1.0f);
    st.Process(buf.data(), 240, listener, params, 1.0f);
  }
  // 居中素材 + 正前 + 参考距离：mid=1，等功率 0.7071*√2 = 1.0，距离增益 1.0，后向增益 1.0。
  CHECK_NEAR(buf[478], 1.0f, 1e-5f);
  CHECK_NEAR(buf[479], 1.0f, 1e-5f);
}
