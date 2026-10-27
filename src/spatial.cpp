// 3D 空间化实现（纯函数 + 每源状态机）。设计说明见 include/audiohub/spatial.hpp。
#include "audiohub/spatial.hpp"

#include <algorithm>
#include <cmath>

namespace audiohub {
namespace {

constexpr float kHalfPi = kPi * 0.5f;
constexpr float kSqrtHalf = 0.70710678f;

float Clamp(float v, float lo, float hi) {
  return v < lo ? lo : (v > hi ? hi : v);
}

float ClampDeg(float deg) { return Clamp(deg, -180.0f, 180.0f); }

float SmoothCoef(float dt_sec, float tau_sec) {
  if (tau_sec <= 0.0f) return 1.0f;
  return 1.0f - std::exp(-dt_sec / tau_sec);
}

}  // namespace

Vec3 ToListenerFrame(const Vec3& world, const ListenerPose& listener) {
  const float yaw = listener.yaw_deg * kDegToRad;
  const float pitch = listener.pitch_deg * kDegToRad;
  const float sy = std::sin(yaw), cy = std::cos(yaw);
  const float sp = std::sin(pitch), cp = std::cos(pitch);

  const Vec3 right{cy, 0.0f, -sy};
  const Vec3 up{sy * sp, cp, cy * sp};
  const Vec3 forward{-sy * cp, sp, -cy * cp};

  const Vec3 rel{world.x - listener.position.x, world.y - listener.position.y,
                 world.z - listener.position.z};
  return Vec3{rel.x * right.x + rel.y * right.y + rel.z * right.z,
              rel.x * up.x + rel.y * up.y + rel.z * up.z,
              rel.x * forward.x + rel.y * forward.y + rel.z * forward.z};
}

Geo AnalyzePosition(const Vec3& world, const ListenerPose& listener) {
  const Vec3 l = ToListenerFrame(world, listener);
  Geo g;
  g.distance = std::sqrt(l.x * l.x + l.y * l.y + l.z * l.z);
  const float horiz = std::sqrt(l.x * l.x + l.z * l.z);
  g.azimuth_deg = std::atan2(l.x, l.z) * kRadToDeg;
  if (horiz > 1e-6f) {
    g.elevation_deg = std::atan2(l.y, horiz) * kRadToDeg;
  } else {
    g.elevation_deg = l.y >= 0.0f ? 90.0f : -90.0f;
  }
  return g;
}

float PanValue(float azimuth_deg, float azimuth_boost) {
  // 整圆 clamp 到 ±180，再取 sin：az=±90 最偏，az=±135 回收到 ±0.707，az=180 回中置。
  const float a = ClampDeg(azimuth_deg * azimuth_boost);
  return std::sin(a * kDegToRad);
}

void PanGains(float azimuth_deg, float azimuth_boost, float* gain_l, float* gain_r) {
  const float pan = PanValue(azimuth_deg, azimuth_boost);
  if (gain_l) *gain_l = std::sqrt(0.5f * (1.0f - pan));
  if (gain_r) *gain_r = std::sqrt(0.5f * (1.0f + pan));
}

float DistanceGain(float distance) {
  const float d = std::max(distance, kMinDistanceM);
  return Clamp(kSceneRadius / d, kMinDistanceGain, kMaxDistanceGain);
}

float ItdSeconds(float azimuth_deg) {
  // 整圆连续：sin(±90°)=±1 最大；sin(180°)=0，正后方 ITD 回落（不要截到 90°）。
  return std::sin(ClampDeg(azimuth_deg) * kDegToRad) * kMaxItdSeconds;
}

float RearFactor(float azimuth_deg) {
  return Clamp(-std::cos(ClampDeg(azimuth_deg) * kDegToRad), 0.0f, 1.0f);
}

float RearCutoffHz(float rear) {
  const float r = Clamp(rear, 0.0f, 1.0f);
  // 在 log 频率上插值，听感更均匀。
  return kRearLpFrontHz * std::pow(kRearLpBackHz / kRearLpFrontHz, r);
}

Vec3 DefaultScenePlacement(const std::string& node_id, bool is_hub) {
  if (is_hub) return Vec3{0.0f, 0.0f, 0.0f};
  // FNV-1a 哈希 -> [0,1) 分数角。默认布局稳定：节点进出不会重排其它节点，
  // 也不依赖 map 的遍历顺序（P1 让 UI 覆盖，仅作首次进入的兜底）。
  std::uint32_t h = 2166136261u;
  for (char c : node_id) h = (h ^ static_cast<std::uint32_t>(static_cast<unsigned char>(c))) * 16777619u;
  const float frac = static_cast<float>(h % 10000u) / 10000.0f;
  const float angle = -kHalfPi + 2.0f * kPi * frac;  // 从 -Z（正前）起绕一圈
  return Vec3{kSceneRadius * std::cos(angle), 0.0f, kSceneRadius * std::sin(angle)};
}

ListenerPose DefaultListener() {
  ListenerPose l;
  l.position = Vec3{0.0f, 0.0f, 0.0f};
  l.yaw_deg = 0.0f;
  l.pitch_deg = 0.0f;
  return l;
}

// ---------------------------------------------------------------- SpatialState

void SpatialState::Reset(float sample_rate) {
  sample_rate_ = sample_rate > 0.0f ? sample_rate : static_cast<float>(kSampleRate);
  target_ = Vec3{};
  has_target_ = false;
  sm_dist_ = 1.0f;
  sm_rear_ = 0.0f;
  sm_delay_ = 0.0f;
  lp_ = 0.0f;
  for (auto& ch : hist_)
    for (float& v : ch) v = 0.0f;
  hist_pos_ = 0;
  gain_l_ = kMonoMakeup * kSqrtHalf;
  gain_r_ = kMonoMakeup * kSqrtHalf;
  delay_l_ = 0.0f;
  delay_r_ = 0.0f;
}

void SpatialState::SetTargetPosition(const Vec3& world) {
  target_ = world;
  has_target_ = true;
}

float SpatialState::ReadHistory(float delay_frames) const {
  const int n = kSpatialHistoryFrames;
  const float limit = static_cast<float>(n - 3);
  const float d = Clamp(delay_frames, 0.0f, limit);
  const int i = static_cast<int>(d);
  const float frac = d - static_cast<float>(i);
  const int pos0 = (hist_pos_ - 1 + n) % n;
  const int i0 = (pos0 - i + n * 4) % n;
  const int i1 = (i0 - 1 + n) % n;
  const float s0 = hist_[0][i0];
  const float s1 = hist_[0][i1];
  return s0 + frac * (s1 - s0);
}

void SpatialState::Process(float* interleaved, int frames, const ListenerPose& listener,
                           const SpatialParams& params, float user_gain) {
  if (interleaved == nullptr || frames <= 0) return;
  if (!enabled_) return;     // 每源旁路：保持原立体声直通，调用方按旧路径乘 user_gain
  if (!has_target_) return;  // 未设置位置：按原样直通，由调用方决定是否给默认位置

  const Geo geo = AnalyzePosition(target_, listener);
  const float dt = static_cast<float>(frames) / sample_rate_;
  const float k = SmoothCoef(dt, params.smooth_tau_sec);

  // ---- 目标参数（只算标量，不进逐样本循环）
  float pan_l = 0.0f, pan_r = 0.0f;
  PanGains(geo.azimuth_deg, params.azimuth_boost, &pan_l, &pan_r);
  const float dist_gain = DistanceGain(geo.distance);
  const float rear = RearFactor(geo.azimuth_deg);
  const float cutoff_hz = RearCutoffHz(rear);

  const float itd_frames = ItdSeconds(geo.azimuth_deg) * sample_rate_;
  const float max_slew = params.max_delay_slew_frames;
  sm_delay_ += Clamp(itd_frames - sm_delay_, -max_slew, max_slew);

  sm_dist_ += (dist_gain - sm_dist_) * k;
  sm_rear_ += (rear - sm_rear_) * k;

  const float rear_gain = 1.0f - (1.0f - kRearGain) * sm_rear_;  // 正后 -> 0.7
  const float raw_l = pan_l * kMonoMakeup * sm_dist_ * user_gain * rear_gain;
  const float raw_r = pan_r * kMonoMakeup * sm_dist_ * user_gain * rear_gain;
  // 块间平滑（50 ms）：200 Hz /api/state 快照 + 拖动节点时避免增益台阶。
  const float target_l = gain_l_ + (raw_l - gain_l_) * k;
  const float target_r = gain_r_ + (raw_r - gain_r_) * k;
  const float dl = sm_delay_ > 0.0f ? sm_delay_ : 0.0f;
  const float dr = sm_delay_ < 0.0f ? -sm_delay_ : 0.0f;

  // ---- 逐样本：写历史 -> 双耳分数延时读取 -> 后向闷化(干湿混合) -> 等功率/距离增益
  const float alpha = 1.0f - std::exp(-2.0f * kPi * cutoff_hz / sample_rate_);
  const bool ramp = frames > 1;
  // 延时也要逐样本铺开：只在块边界改延时，等于每 5~10 ms 给读指针一个台阶，
  // 宽带信号上听起来就是"嘶啦"的电流声（拖动节点时 ITD 每块都在变，尤其明显）。
  // 把上一块结束时的延时到本块目标延时在块内做线性插值，读指针才是连续的。
  const float dl_start = delay_l_;
  const float dr_start = delay_r_;
  const float dl_span = dl - dl_start;
  const float dr_span = dr - dr_start;
  for (int i = 0; i < frames; ++i) {
    hist_[0][hist_pos_] = 0.5f * (interleaved[2 * i] + interleaved[2 * i + 1]);
    hist_pos_ = (hist_pos_ + 1) % kSpatialHistoryFrames;

    const float t = ramp ? static_cast<float>(i) / static_cast<float>(frames - 1) : 1.0f;
    const float l = ReadHistory(dl_start + dl_span * t);
    const float r = ReadHistory(dr_start + dr_span * t);
    const float mid = 0.5f * (l + r);
    lp_ += alpha * (mid - lp_);
    // 干湿混合：前方全频；正后方只剩 4 kHz 以下（一阶），两条线索叠加才够"听到身后"。
    const float dull = sm_rear_ * (lp_ - mid);
    const float gl = gain_l_ + (target_l - gain_l_) * t;
    const float gr = gain_r_ + (target_r - gain_r_) * t;
    interleaved[2 * i] = (l + dull) * gl;
    interleaved[2 * i + 1] = (r + dull) * gr;
  }

  gain_l_ = target_l;
  gain_r_ = target_r;
  delay_l_ = dl;
  delay_r_ = dr;
}

}  // namespace audiohub