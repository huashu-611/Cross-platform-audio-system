// 通用 PI 控制器，用于把缓冲水位锁到目标值。
//
// 为什么用 PI 而不是 P：
//   纯比例控制会留下稳态误差——晶振偏差是持续存在的，P 需要持续输出
//   一个非零比率才能抵消它，而 P 的输出正比于误差，意味着误差必须一直存在。
//   积分项负责吃掉这个常数偏差。
//
// 为什么输出要限幅：ASRC 的比率范围是 ±0.2%，超出这个范围说明设备异常，
// 继续加大补偿只会把问题藏起来（而且 ±0.2% 已经接近人耳可察觉的
// 音高变化下限，再大就有"跑调"风险）。
#pragma once

#include <algorithm>

namespace audiohub {

class PiController {
 public:
  PiController(double kp, double ki, double out_min, double out_max)
      : kp_(kp), ki_(ki), out_min_(out_min), out_max_(out_max) {}

  // error: 目标值 - 实测值（正数表示还不够，需要"追"）
  // dt_seconds: 距上次调用的时间。传参而不是内部计时，便于单元测试。
  double Update(double error, double dt_seconds) {
    if (dt_seconds > 0.0) {
      integral_ += error * ki_ * dt_seconds;
      // 积分限幅（anti-windup）：否则长时间单边误差会让积分项饱和，
      // 之后需要很久才能退出来，表现为延迟缓慢地过冲。
      integral_ = std::clamp(integral_, out_min_, out_max_);
    }
    const double output = kp_ * error + integral_;
    return std::clamp(output, out_min_, out_max_);
  }

  void Reset() { integral_ = 0.0; }
  double integral() const { return integral_; }

 private:
  double kp_;
  double ki_;
  double out_min_;
  double out_max_;
  double integral_ = 0.0;
};

}  // namespace audiohub
