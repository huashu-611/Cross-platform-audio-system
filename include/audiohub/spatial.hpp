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
// 前后（v0.46：四条线索叠加，仍然不是 HRTF 定位）
//   两声道没有 HRTF 卷积时，"前后"在物理上就是近乎对称的，人耳主要靠**音色 + 时间**
//   这两类线索去辨认。本轮把原来的两条弱线索扩成四条，全部以 rear = max(0, -cos az)
//   为权重；rear=0（正前 / 正侧）时整条后向链**逐位透明**，只有后半球被染色：
//     ① 电平：kRearGain=0.7（正后 -3.1 dB）。粗、稳、单声道也成立，但方向感最弱。
//     ② 一阶低通：20 kHz → kRearLpBackHz=6 kHz。比上一版的 4 kHz **放宽**：
//        过度闷化会把所有后向声音压成同一团低频糊，反而更难分辨；
//        把判据让给 ③④，低通只留"钝一点"的底子。
//     ③ 耳廓陷波：并联二阶带通（RBJ bandpass，y = x + beta*bp），中心随 rear 从
//        6 kHz 抬到 8 kHz（log 插值），深度到 -15 dB，Q=1.6（8 kHz 处 -3 dB 带宽
//        约 5 kHz）。耳廓对不同入射方向的梳状响应在 6~10 kHz 留下不同位置的陷波，
//        这是前后音色差异里最有"方向感"的一维。选并联而不是串联 peaking：
//        beta=0 时输出与输入**逐位相同**，前向能做到真正透明。
//     ④ 早期反射：两路延迟副本（7.5~11 ms、12.4~18.2 ms），rear 越大越长越响
//        （合计 0.50，约 +3.5 dB 湿声），过 2.5 kHz 一阶低通模拟墙面/肩部吸声。
//        真实房间里"身后"的声音有相当一部分是通过反射听到的，这条给的是时间/
//        梳状线索，在纯幅度谱上不明显，但对"在外侧/在身后"的空间感贡献最大。
//
//   v0.46b 关键修正：裸 rear 在 az=135°（斜后方）只有 0.707，于是 ③④ 只按七成
//   力度生效，斜后方只比斜前方暗约 2 dB，人耳分不出来。现在 RearFactor 做对比度
//   拉伸（见 kRearContrastExponent）：135° 抬到 0.826、120° 抬到 0.68，两端不动。
//   实测（build/_diag/rear_probe2）：斜后方 6/7/12 kHz 相对斜前方拉开 4~10 dB，
//   正后方 8 kHz 挖深 -17.7 dB，前向 11 个频点仍恒为输入电平、L-R=0。
//   仍然做不到（诚实说明）：真正的锥形混淆消解（无 HRTF 时前/后误判率不会归零）、
//   俯仰方向的定位、个体耳廓差异、以及单声道兼容播放时的前后区分。
//   要那些必须上 HRIR 卷积（且需要个体化或至少 KEMAR 这类通用 HRIR 库）。
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

constexpr float kSceneRadius = 1.0f;          // 节点默认**圆环半径**（只给 DefaultScenePlacement 用）
// v0.45：距离衰减的参考距离从 kSceneRadius 拆出来单独一个量。
//   以前两者共用一个 1.0 —— 于是"改衰减参考距离"会顺带把默认摆位圆环也改掉。
//   现在 r0 = 0.3 m：站着说话的距离是 100%，与"最近 0.3 m"的交互口径一致。
constexpr float kDistanceRefM = 0.3f;         // 距离衰减参考距离 r0（米）
constexpr float kDefaultCameraRadius = 2.6f;  // UI 默认相机距离（只给 UI 用，DSP 不读它）
constexpr float kMaxItdSeconds = 0.00063f;    // 人最大双耳时间差 ≈0.63 ms @48k ≈30.2 帧
constexpr float kAzimuthBoost = 1.0f;         // 方位增强系数（1.0 = 物理正确；本布局不需要增强）
constexpr float kMonoMakeup = 1.41421356f;    // √2：单声道化后每声道补 -3 dB
constexpr float kRearGain = 0.7f;             // 正后方增益 ≈ -3.1 dB（弱线索，保留）
constexpr float kRearLpFrontHz = 20000.0f;    // 正前方一阶低通截止
// v0.46：后向低通从 4 kHz 放宽到 6 kHz。理由：4 kHz 的一阶低通把后向压得太闷，
//   所有"身后"声音都变成同一团低频糊，"后 vs 后右"反而更难分；放宽后把判决权
//   交给陷波（③）与反射（④），低通只负责"钝一点"的底子。
constexpr float kRearLpBackHz = 6000.0f;      // 正后方一阶低通截止
// ---- v0.46 新增：耳廓陷波（③）。**并联**二阶带通：y = x + beta*bp(x)，
//   beta 为负即在 bp 中心处挖坑，深度 = -depth_dB。选并联而非串联 peaking 的理由：
//   beta=0 时 y 与 x 逐位相同，前向才能做到"完全透明"（串联做不到）。
//   中心频率随 rear 从 6 kHz 抬到 8 kHz：这是耳廓梳状响应随入射角移动的主瓣位置。
//   深度 15 dB 是可明显察觉、又不至于像 EQ 故障的量（Q=1.6 时是宽带凹陷）。
constexpr float kRearNotchFrontHz = 6000.0f;  // rear=0 时的陷波中心（实际未启用）
constexpr float kRearNotchBackHz = 8000.0f;   // rear=1 时的陷波中心
constexpr float kRearNotchDepthDb = 15.0f;    // v0.46b：12 -> 15 dB（斜后方也要听得出来）
// v0.46b：Q 2.5 -> 1.6。Q=2.5 的坑只有 ±1.2 kHz 宽，宽带音乐里很容易被相邻频段填满；
//   放宽后是一条"presence 区整体发闷"的宽带凹陷，比窄坑更像耳廓效应、也更抗掩蔽。
constexpr float kRearNotchQ = 1.6f;           // Q=1.6 -> 8 kHz 处 -3 dB 带宽 ≈ 5 kHz
// ---- v0.46b 新增：后向对比度拉伸。
//   裸 rear = max(0, -cos az) 在「斜后方」只有 0.707（135°），于是陷波/反射/闷化
//   全都只按七成力度生效 —— 实测 135° 与 45° 只差约 2 dB，人耳分不出来。
//   指数 0.55 把 135°抬到 0.83、120° 抬到 0.68，两端（0°/180°）保持不变，
//   等于"后半球一进来就有大半个线索"，这才是能分辨的前后差。
constexpr float kRearContrastExponent = 0.55f;
// ---- v0.46 新增：早期反射（④）。两路延迟副本，rear 越大越长、越响。
//   延迟取 7.5~11 ms 与 12.4~18.2 ms：第一路是"同侧墙面/肩部"的近反射，
//   第二路是"对侧墙"的远反射；两路互相不成简单整数比，避免并入同一个梳状峰。
constexpr float kRearEr1DelayFrontMs = 7.5f;  // rear=0 时的第一路延迟（实际强度 0）
constexpr float kRearEr1DelayBackMs = 11.0f;  // rear=1 时的第一路延迟
constexpr float kRearEr2DelayFrontMs = 12.4f; // rear=0 时的第二路延迟（实际强度 0）
constexpr float kRearEr2DelayBackMs = 18.2f;  // rear=1 时的第二路延迟
constexpr float kRearEr1Gain = 0.34f;         // rear=1 时第一路相对干声的幅度（v0.46b：0.26 -> 0.34）
constexpr float kRearEr2Gain = 0.16f;         // rear=1 时第二路（合计 0.50，约 +3.5 dB 湿声）
constexpr float kRearErLpHz = 2500.0f;        // 反射路径的一阶低通（墙面/肩部吸高频）
constexpr float kSmoothTauSec = 0.05f;        // 增益/声像平滑时间常数（50 ms）
constexpr float kMaxDelaySlewFrames = 2.0f;   // ITD 每块（5 ms）最多变 2 帧，防爆音
// v0.46：64 -> 1024。下一节④最长反射 18.2 ms @48k ≈ 874 帧，还要 2 帧插值余量；
//   取 1024（2 的幂，取模是掩码级开销）。状态从 512 B 涨到 8 KB/源，可忽略。
constexpr int kSpatialHistoryFrames = 1024;   // 每源历史：≥ 最长反射 874 帧 + 插值余量
constexpr int kSpatialHistoryChannels = 2;    // P0 只用 [0]（单声道化后）；P1 保留 side 用 [1]
constexpr float kMinDistanceM = 0.3f;         // 距离下限，防除零（= r0，靠得再近也不再变响）
// 距离就是音量（用户 2026-10-27 定稿）：取消远场下限，拖多远就衰减多少。
//   g = min((r0 / max(d, kMinDistanceM))^p, kMaxDistanceGain)，p = kDistanceExponent
//   r0 = kDistanceRefM = 0.3 m；封顶 1.0；p = 2.4275。远场不截断。
// v0.45：取消 +6 dB 近场升压。以前近场封顶 2.0（+6 dB），于是"最近处"不是 100%
// 而是 200%，和"最近 0.3 m = 100%"的口径对不上。现在封顶 1.0：r0 处正好 100%。
constexpr float kMaxDistanceGain = 1.0f;      // 100% 封顶（r0 处即满）
// 距离衰减指数（v0.4，用户反馈"拖远了听不出变小"）：1.0 是纯 1/d，近处变化太缓；
// 1.6 让"距离就是音量"更直观：d=1 → 0 dB，d=2 → -9.6 dB，d=3 → -14.4 dB，
// d=4 → -19.3 dB，近场仍封顶 +6 dB。C++/Android 必须用同一个值（跨端契约）。
//
// v0.46 口径（用户 2026-11-10 定稿）：0.3 m = 100%，2.0 m = 1%。
//   由 (r0/d)^p 解出 p = ln(0.01)/ln(kDistanceRefM/2.0) = 2.4274533（r0=0.3）
//   实测：≤0.3→100%，0.5→28.94%(-10.8dB)，0.75→10.81%(-19.3dB)，
//        1.0→5.38%(-25.4dB)，1.5→2.01%(-33.9dB)，2.0→1.000%(-40.0dB)，
//        3.0→0.374%(-48.6dB)，10.0→0.020%(-73.9dB)
//   注意动态范围很大：1 m 外已经 -25.4 dB。这是"距离就是音量"的直译，
//   想要整体更响请调各源音量（user_gain），不要改这条曲线的口径。
constexpr float kDistanceExponent = 2.4274533f;
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

// 距离衰减：指数反比振幅，参考距离 r0 = kDistanceRefM（0.3 m），指数 p。
//   g = min((r0 / max(d, kMinDistanceM))^p, kMaxDistanceGain)，p = 2.4274533
//   d≤0.3 -> 100%(0 dB)；d=0.5 -> 28.9%(-10.8 dB)；d=0.75 -> 10.8%(-19.3 dB)；
//   d=1.0 -> 5.4%(-25.4 dB)；d=2.0 -> 1.0%(-40.0 dB)；d=3.0 -> 0.37%(-48.6 dB)。
float DistanceGain(float distance);

// 双耳时间差（秒，正 = 声源在右，右耳领先；正后方 sin(180°)=0 回落到 0，整圆连续）。
float ItdSeconds(float azimuth_deg);

// 后向因子：0 = 正前，1 = 正后。
float RearFactor(float azimuth_deg);

// 后向低通截止：rear=0 -> 20 kHz，rear=1 -> 6 kHz（几何插值）。
// v0.46：从 4 kHz 放宽到 6 kHz —— 深度闷化会让所有后向声音塌成同一团低频，
// 反而抹掉"后 vs 后右"的差别；现在判决交给陷波与早期反射。
float RearCutoffHz(float rear);

// v0.46 耳廓陷波中心：rear=0 -> 6 kHz，rear=1 -> 8 kHz（几何插值）。
// 与耳廓梳状响应随入射角移动的主瓣对应；rear=0 时陷波强度为 0（不启用）。
float RearNotchHz(float rear);

// v0.46 早期反射延迟（秒）：rear=0 -> front 值，rear=1 -> back 值（线性插值）。
// 强度由 rear 线性加权（rear=0 时为 0，逐位透明）。stage=0 是近反射，stage=1 是远反射。
float RearReflectionDelaySeconds(float rear, int stage);

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

  // v0.46 ③ 耳廓陷波：并联式 "y = x + beta * bp(x)"，bp 是 RBJ 二阶带通（peak gain 0 dB）。
  //   这么写的好处：beta=0 时 y 与 x **逐位相同**（rear=0 纯旁路，不靠浮点巧合），
  //   而串联 peaking biquad 的 b0 会带 1 ulp 误差；beta 怎么扫都不会破坏稳定性
  //   （极点只由 bp 的 a1/a2 决定）。
  //   系数每块按 sm_rear_ 重算一次；同一 50 ms 平滑把 beta 从旧值推向新值，
  //   等价于块间线性插值，避免每块换参数的 zipper 噪声。
  float notch_b0_ = 0.0f;   // bp 的 b0（b1 恒为 0，b2 = -b0）
  float notch_a1_ = 0.0f;
  float notch_a2_ = 0.0f;
  float notch_beta_ = 0.0f;  // 并联增益：-(1 - 10^(-depth*rear/20))，rear=0 时为 0
  float notch_z1_ = 0.0f;    // bp 的 TDF-II 状态
  float notch_z2_ = 0.0f;
  // v0.46 ④ 两路早期反射各自的一阶低通状态（墙面/肩部吸高频）
  float er_lp_[2] = {0.0f, 0.0f};

  // 历史环：单声道化后的信号（干声与两路反射都从这里读）。
  // hist_[0] 是 P0 用的；hist_[1] 留给 P1 的 side 通路。
  float hist_[kSpatialHistoryChannels][kSpatialHistoryFrames]{};
  int hist_pos_ = 0;

  // 上一块结束时的输出参数，用来做块内线性过渡（避免 200 Hz 台阶感/爆音）
  float gain_l_ = kMonoMakeup * 0.70710678f;
  float gain_r_ = kMonoMakeup * 0.70710678f;
  float delay_l_ = 0.0f;
  float delay_r_ = 0.0f;
};

}  // namespace audiohub