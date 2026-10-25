// 每源独立的抖动缓冲。
//
// 核心模型：以"流内绝对采样序号"为索引，而不是以到达顺序为索引。
//   * 写：包自带 sample_pos，直接写到该位置（环形数组下标 = pos % capacity）
//   * 读：按设备时钟匀速前进，读到哪算哪，没写过的位置补 0 并计 underrun
//   * 延迟 = (已写到的最高位置) - (读位置)，由"首包到达时把读指针回退 target_delay"实现
//
// 为什么不用"队列 + 排序"：队列模型无法区分"包还没到"和"包丢了"，
// 也无法在迟到包的尾部仍有价值时部分利用。绝对序号模型两种都能处理。
//
// 并发：内部用 mutex。这一层不在设备回调线程上跑（回调只从输出环 memcpy），
// 临界区是一次 memcpy，持锁时间在百纳秒量级。
#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

namespace audiohub {

struct JitterBufferStats {
  uint64_t packets_received = 0;
  uint64_t packets_late = 0;      // 整包已过期
  uint64_t packets_overflow = 0;  // 超出缓冲容量（对端时间戳异常或延迟设太大）
  uint64_t frames_underrun = 0;   // 读到了没写过的位置
  uint64_t frames_trimmed = 0;    // 部分迟到，被裁掉的前半段
  uint64_t frames_reordered = 0;  // 乱序到达（sample_pos 小于上次写入位置）
  uint64_t frames_dropped = 0;    // 追帧时主动丢掉的（只发生在启动突发之后）
};

class JitterBuffer {
 public:
  JitterBuffer(int channels, int sample_rate, int capacity_frames);

  // 目标延迟只在流启动时决定初始读位置；运行中调整会插入一次短静音，
  // 这是不可避免的——缓冲区扩容在物理上就等于"晚一点开始播"。
  void SetTargetDelayMs(int ms);
  int target_delay_ms() const;

  // 返回 false 表示整包被丢弃。
  bool Push(uint32_t sample_pos, const float* interleaved, int frames);

  // 读取 frames 帧。不足部分写 0 并计入 underrun。
  void Pull(float* dst, int frames);

  // 主动插入 n 帧静音（不推进读指针），用于扩容后把延迟真正拉大。
  void InsertSilence(int frames);

  // 清空缓冲回到"从未收到过任何包"的状态。
  // 用于连接确认后重新开始：未确认期间缓冲里攒的旧数据没有意义，
  // 直接丢弃比"从当前位置继续"干净——旧数据的 sample_pos 早就落在
  // read_pos_ 前面了，继续用只会全部判成迟到。
  void Reset();

  // 直接丢弃接下来 n 帧并前移读指针——用于启动突发后的"追帧"。
  //
  // 为什么需要它：源端启动的瞬间，WASAPI 会把积压的缓冲一次性交出来，
  // 发送线程会连着发十几个包。如果照单全收，初始水位会远高于目标延迟，
  // 而且此后没有任何机制能把它拉回来（真实时钟的偏差远小于这个量）。
  // 主动丢一小段比长期带着 2 倍延迟要好。
  void FastForward(int frames);

  int occupancy_ms() const;
  bool started() const;

  JitterBufferStats stats() const;
  void ResetStats();

 private:
  // 把绝对采样位置映射到环形数组下标。位置可能是负数（首包之前），
  // C++ 的 % 对负数返回负值，必须补一次 —— 否则会索引到数组外，
  // 或者更糟：映射到某个"看起来有效"的旧槽位，读出上上轮的残留数据。
  inline int64_t RingIndex(int64_t pos) const {
    const int64_t r = pos % capacity_;
    return r < 0 ? r + capacity_ : r;
  }
  inline bool IsValid(int64_t pos) const;
  inline void SetValid(int64_t pos, bool v);

  int channels_;
  int capacity_;
  std::vector<float> ring_;
  std::vector<uint64_t> valid_bits_;

  mutable std::mutex mutex_;
  int64_t read_pos_ = 0;          // 下一个要读出的绝对采样位置
  int64_t highest_written_ = 0;   // 已写入区间的末端（不含）
  int target_delay_ms_ = 60;
  int pending_silence_frames_ = 0;
  bool started_ = false;

  JitterBufferStats stats_;
};

}  // namespace audiohub
