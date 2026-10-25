// 异步采样率转换（ASRC）——消除源端与 Hub 之间的时钟漂移。
//
// 需要它的原因很直接：两台设备的晶振不同源，典型偏差 ±20~100 ppm。
// 100 ppm 跑一小时 = 360 ms。不补偿的话，一小时的会话里缓冲要么被撑爆，
// 要么被抽干，没有第三种结局。
//
// 实现选择：4 点 Catmull-Rom 三次插值 + 连续可调的读取速率。
//   * 为什么不用线性插值：比率接近 1 时分数部分变化极慢，线性插值的
//     高频衰减（10 kHz 处约 -1.3 dB）会变成一种缓慢的幅度调制，
//     在音乐上能听出"糊"。三次插值把这项误差压到 0.1 dB 以内，代价只是多两次乘法。
//   * 为什么不做 sample slip：插/删整帧会产生周期性的相位跳变，
//     在有持续音（弦乐、合成器 pad）时比插值更难听。
//   * 比率范围 ±0.2%，足够覆盖最差的晶振组合。
#pragma once

#include <vector>

namespace audiohub {

class Asrc {
 public:
  explicit Asrc(int channels);

  // ratio = 输出帧率 / 输入帧率。
  //   ratio > 1：源偏慢，同样多的输出需要更少的输入（缓冲会涨）
  //   ratio < 1：源偏快，需要更多输入（缓冲会落）
  void SetRatio(double ratio);
  double ratio() const { return ratio_; }

  // 产出 out_frames 帧需要喂进来多少输入帧（含插值所需的保护帧）。
  int InputNeededFor(int out_frames) const;

  void Push(const float* interleaved, int frames);

  // 返回 false 表示输入不够，已经尽可能多写（剩余部分未定义，调用方要自己补）。
  bool Pull(float* out, int frames);

  int AvailableInputFrames() const;
  void Reset();

 private:
  int channels_;
  double ratio_ = 1.0;
  std::vector<float> buffer_;  // 交织样本
  size_t head_frames_ = 0;     // buffer_ 中已消费的帧数（用索引而非 erase，避免搬内存）
  size_t size_frames_ = 0;     // buffer_ 中有效帧数
  double pos_ = 1.0;           // 相对于 head_frames_ 的分数读位置，恒 >= 1
};

}  // namespace audiohub
