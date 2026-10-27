// 3D 空间化：把一路立体声按"声源/听者位姿"渲染成带方位与距离感的立体声。
//
// 为什么单独成文件（和 graph.hpp 一样的取舍）：
//   1. 纯 DSP、不依赖 socket / 音频后端，才能进 tests/ 直接单测；
//   2. 汇聚端 C++ 与 Android 汇聚端 Java 必须算出同样的结果，公式和常数只能有一份权威定义。
//
// 坐标系（世界坐标；UI 与 C++ 必须一致）：
//   右手系，+Y 向上，1 unit ≈ 1 m。
//   听者 yaw=0 面朝 -Z：
//     forward = (-sin(yaw)*cos(pitch),  sin(pitch), -cos(yaw)*cos(pitch))
//     right   = ( cos(yaw),               0,          -sin(yaw) )
//     up      = ( sin(yaw)*sin(pitch),   cos(pitch),  cos(yaw)*sin(pitch) ) = right × forward
//   world = listener.position + right*右 + up*上 + forward*前。
//   相机即听者（P0）：UI 把相机位置/朝向原样报上来，C++ 只当外部输入。
//
// 听者位置：P0 固定在汇聚端（场景原点）；相机只当观察者，相机 orbit 改 yaw 时听者 yaw 跟随
//   （声音随视角旋转），相机拉近/拉远不改变听者位置，因此不改变音量。
//
// 声像：pan = sin(clamp(az*kAz, -180, 180))，等功率
//       gL = sqrt((1-pan)/2)，gR = sqrt((1+pan)/2)（gL^2+gR^2 = 1）。
//   kAz 默认 1.0（物理正确）。听者固定在原点、节点在 R=1 圆环上时方位覆盖 ±90°，
//   左右天然可达 20 dB（az=±90°），不需要方位增强；参数保留给"听者在中心之外"的布局模式。
//   sin 在整圆上给出正确的左右符号与两侧对称：az=±90 最偏、az=±135 回收到 ±0.707、
//   az=180（正后方）回到中置——后半球不会被饱和成硬左/硬右，正后方只靠后向线索区分。
//
// 前后（诚实定位）：立体声两声道没有 HRTF 卷积时无法可靠区分前/后。
//   P0 只做两条弱线索：rear = max(0, -cos az)，后向增益 kRearGain=0.7，
//   一阶低通截止从 20 kHz 随 rear 降到 4 kHz。它能让"屏幕下方（身后）"的节点
//   听起来明显更闷、更轻，但这不是真正的前后定位；真要前后定位需要 HRTF，本轮不做。
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "audiohub/audio_format.hpp"

namespace audiohub {

struct Vec3 {
  float x = 0.0f;
  float y = 0.0f;
  float z = 0.0f;
};

// 听者位姿。P0 = 相机位姿（眼位）。
struct ListenerPose {
  Vec3 position;
  float yaw_deg = 0.0f;    // 0 = 面朝 -Z；俯视时顺时针为正
  float pitch_deg = 0.0f;  // + = 抬头
};

// 一个节点的摆放。placed=false 表示用户还没摆过，用 DefaultScenePlacement。
struct Placement {
  Vec3 position;
  bool placed = false;
};

// ------------------------------------------------ 常数（跨端一致；改这里等于改协议）
constexpr float kPi = 3.14159265358979323846f;
constexpr float kDegToRad = kPi / 180.0f;
constexpr float kRadToDeg = 180.0f / kPi;

constexpr float kSceneRadius = 1.0f;          // 节点默认圆环半径，也是距离衰减的参考距离 r0
constexpr float kDefaultCameraRadius = 2.6f;  // UI 默认相机距离（只给 UI 用，DSP 不读它）
constexpr float kMaxItdSeconds = 0.00063f;    // 人最大双耳时间差 ≈0.63 ms @48k ≈30.2 帧
constexpr float kAzimuthBoost = 1.0f;         // 方位增强系数（1.0 = 物理正确；本布局不需要增强）
constexpr float kMonoMakeup = 1.41421356f;    // √2：单声道化后每声道补 -3 dB
constexpr float kRearGain = 0.7f;             // 正后方增益 ≈ -3.1 dB（弱线索）
constexpr float kRearLpFrontHz = 20000.0f;    // 正前方一阶低通截止
constexpr float kRearLpBackHz = 4000.0f;      // 正后方一阶低通截止
constexpr float kSmoothTauSec = 0.05f;        // 增益/声像平滑时间常数（50 ms）
constexpr float kMaxDelaySlewFrames = 2.0f;   // ITD 每块（5 ms）最多变 2 帧，防爆音
constexpr int kSpatialHistoryFrames = 64;     // 每源历史 ≥ 最大延时 30 帧 + 插值余量
constexpr int kSpatialHistoryChannels = 2;    // P0 只用 [0]（单声道化后）；P1 保留 side 用 [1]
constexpr float kMinDistanceM = 0.25f;        // 距离下限，防除零
// 距离就是音量（用户 2026-10-27 定稿）：取消远场下限，拖多远就衰减多少。
//   g = min(r0 / max(d, kMinDistanceM), kMaxDistanceGain)
//   近场仍封顶 +6 dB（防凑到脸上炸耳），远场不再截断（10 m ≈ -20 dB）。
constexpr float kMaxDistanceGain = 2.0f;      // +6 dB，近场封顶
constexpr float kSpatialCoordLimit = 320.0f;  // 世界坐标绝对值上限 ±320 m（= POS 帧 ±32000 cm；HTTP 与内核双重校验）

// ------------------------------------------------ 纯函数（全部可单测，不碰 DSP 状态）

// 世界坐标 -> 听者局部坐标 (x=右, y=上, z=前)。
Vec3 ToListenerFrame(const Vec3& world, const ListenerPose& listener);

struct Geo {
  float azimuth_deg = 0.0f;    // -180..180，+ = 右
  float elevation_deg = 0.0f;  // -90..90，+ = 上
  float distance = 0.0f;       // 3D 欧氏距离（米）
};
Geo AnalyzePosition(const Vec3& world, const ListenerPose& listener);

// pan = sin(clamp(az*kAz, -180, 180)) ∈ [-1, 1]
float PanValue(float azimuth_deg, float azimuth_boost);

// 等功率声像增益：gL=sqrt((1-pan)/2)，gR=sqrt((1+pan)/2)，0..1。
void PanGains(float azimuth_deg, float azimuth_boost, float* gain_l, float* gain_r);

// 距离衰减：线性反比振幅，参考距离 r0 = kSceneRadius（1 unit = 1 m）。
//   g = min(r0 / max(d, kMinDistanceM), kMaxDistanceGain)
//   d=1 -> 0 dB；d=2 -> -6 dB；d=4 -> -12 dB；d=10 -> -20 dB（远场不再截断，距离就是音量）；
//   d<0.5 -> 上限 2.0 (+6 dB)，近场封顶。
float DistanceGain(float distance);

// 双耳时间差（秒，正 = 声源在右，右耳领先；正后方 sin(180°)=0 回落到 0，整圆连续）。
float ItdSeconds(float azimuth_deg);

// 后向因子：0 = 正前，1 = 正后。
float RearFactor(float azimuth_deg);

// 后向低通截止：rear=0 -> 20 kHz，rear=1 -> 4 kHz（几何插值）。
float RearCutoffHz(float rear);

// 默认摆放：hub 在场景中心；其它节点在 XZ 圆环 R=1 上，角度由节点 id 哈希得到
// （稳定：节点进出不改变其他节点的默认位置，也不依赖图谱顺序）。
Vec3 DefaultScenePlacement(const std::string& node_id, bool is_hub);

// 默认听者：固定在场景原点、面朝 -Z（屏幕上方 = 远处 = 听者正前方）。
ListenerPose DefaultListener();

struct SpatialParams {
  float azimuth_boost = kAzimuthBoost;
  float smooth_tau_sec = kSmoothTauSec;
  float max_delay_slew_frames = kMaxDelaySlewFrames;
};

// 每源 DSP 状态。挂在 HubCore::Impl::Stream 上：
//   - Process 只由混音线程调用；
//   - SetTargetPosition 只由 HTTP 线程在 HubCore::mutex 下调用，而 MixerLoop 本来就持这把锁，
//     所以不需要额外原子（对比：给 ListenerPose 用 20 字节 std::atomic 反而会退化成内部锁）。
class SpatialState {
 public:
  void Reset(float sample_rate = static_cast<float>(kSampleRate));

  // 目标世界坐标（HTTP 线程 / 混音线程在 HubCore::mutex 下写）。
  void SetTargetPosition(const Vec3& world);

  // 每源旁路（P0 给 PC 环回音乐源留的退路）：false 时 Process 原样返回，
  // 立体声不被单声道化，调用方按旧路径乘 user_gain。
  void SetEnabled(bool enabled) { enabled_ = enabled; }
  bool enabled() const { return enabled_; }

  // 原地把交错立体声（frames*2）改写成空间化结果。
  // user_gain = 界面上的每源音量（0..4），已折进每耳增益：调用方之后必须把
  // MixInput.gain 置 1，否则会和 MixBlock 的增益重复相乘。
  void Process(float* interleaved, int frames, const ListenerPose& listener,
               const SpatialParams& params, float user_gain);

  // 单测/调试用：最近一块结束时的每耳总增益（含 makeup/user_gain）与延时（帧）。
  float gain_l() const { return gain_l_; }
  float gain_r() const { return gain_r_; }
  float delay_l_frames() const { return delay_l_; }
  float delay_r_frames() const { return delay_r_; }
  Vec3 last_target() const { return target_; }
  bool has_target() const { return has_target_; }

 private:
  float ReadHistory(float delay_frames) const;

  float sample_rate_ = static_cast<float>(kSampleRate);
  Vec3 target_{};
  bool has_target_ = false;
  bool enabled_ = true;

  // 平滑后的中间量（只由混音线程读写）
  float sm_dist_ = 1.0f;   // 距离增益
  float sm_rear_ = 0.0f;   // 0..1
  float sm_delay_ = 0.0f;  // 带符号 ITD（帧），+ = 右耳领先
  float lp_ = 0.0f;        // 后向一阶低通状态

  // ITD 历史：单声道化后的信号。hist_[0] 是 P0 用的；hist_[1] 留给 P1 的 side 通路。
  float hist_[kSpatialHistoryChannels][kSpatialHistoryFrames]{};
  int hist_pos_ = 0;

  // 上一块结束时的输出参数，用来做块内线性过渡（避免 200 Hz 台阶感/爆音）
  float gain_l_ = kMonoMakeup * 0.70710678f;
  float gain_r_ = kMonoMakeup * 0.70710678f;
  float delay_l_ = 0.0f;
  float delay_r_ = 0.0f;
};

}  // namespace audiohub