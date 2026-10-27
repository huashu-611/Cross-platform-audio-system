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

TEST(SpatialDistanceGainClamps) {
  CHECK_NEAR(DistanceGain(1.0f), 1.0f, kTol);    // 0 dB
  CHECK_NEAR(DistanceGain(2.0f), 0.5f, kTol);    // -6.02 dB
  CHECK_NEAR(DistanceGain(0.5f), 2.0f, kTol);    // +6.02 dB
  CHECK_NEAR(DistanceGain(0.25f), 2.0f, kTol);   // 近场封顶
  CHECK_NEAR(DistanceGain(4.0f), 0.5f, kTol);    // 远场下限
  CHECK_NEAR(DistanceGain(0.0f), 2.0f, kTol);    // 除零保护
  CHECK_NEAR(20.0f * std::log10(DistanceGain(2.0f)), -6.0206f, 0.01f);
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
  CHECK_NEAR(RearCutoffHz(1.0f), 4000.0f, 1.0f);
  CHECK_NEAR(RearCutoffHz(0.5f), 8944.27f, 1.0f);  // 几何中点 sqrt(20k*4k)
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
  st.SetTargetPosition(Vec3{0.0f, 0.0f, -1.0f});
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
  st2.SetTargetPosition(Vec3{0.0f, 0.0f, -1.0f});
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
  st.SetTargetPosition(Vec3{0.0f, 0.0f, -1.0f});
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
  st.SetTargetPosition(Vec3{0.0f, 0.0f, -1.0f});  // 正前
  for (int k = 0; k < 40; ++k) {
    for (float& v : buf) v = 0.0f;
    st.Process(buf.data(), 240, listener, params, 1.0f);
  }
  CHECK_NEAR(st.gain_l(), 1.0f, 1e-3f);  // 正前每耳 1.0
  st.SetTargetPosition(Vec3{1.0f, 0.0f, 0.0f});  // 突跳到正右侧（左耳目标 0）
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
