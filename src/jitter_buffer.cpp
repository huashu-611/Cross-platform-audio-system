#include "audiohub/jitter_buffer.hpp"

#include <algorithm>
#include <cstring>

#include "audiohub/audio_format.hpp"

namespace audiohub {
namespace {
constexpr int kMinTargetDelayMs = 20;
constexpr int kMaxTargetDelayMs = 250;
}  // namespace

JitterBuffer::JitterBuffer(int channels, int sample_rate, int capacity_frames)
    : channels_(channels), capacity_(capacity_frames) {
  (void)sample_rate;
  if (capacity_ <= 0) capacity_ = 1;
  ring_.assign(static_cast<size_t>(capacity_) * channels_, 0.0f);
  valid_bits_.assign((static_cast<size_t>(capacity_) + 63) / 64, 0);
}

void JitterBuffer::SetTargetDelayMs(int ms) {
  std::lock_guard<std::mutex> lock(mutex_);
  target_delay_ms_ = std::clamp(ms, kMinTargetDelayMs, kMaxTargetDelayMs);
}

int JitterBuffer::target_delay_ms() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return target_delay_ms_;
}

bool JitterBuffer::IsValid(int64_t pos) const {
  const int64_t idx = RingIndex(pos);
  return (valid_bits_[static_cast<size_t>(idx) >> 6] >> (idx & 63)) & 1ULL;
}

void JitterBuffer::SetValid(int64_t pos, bool v) {
  const int64_t idx = RingIndex(pos);
  uint64_t& word = valid_bits_[static_cast<size_t>(idx) >> 6];
  const uint64_t bit = 1ULL << (idx & 63);
  if (v) {
    word |= bit;
  } else {
    word &= ~bit;
  }
}

bool JitterBuffer::Push(uint32_t sample_pos, const float* interleaved, int frames) {
  if (interleaved == nullptr || frames <= 0) return false;

  std::lock_guard<std::mutex> lock(mutex_);
  stats_.packets_received++;

  int64_t pos = static_cast<int64_t>(sample_pos);

  // 首包决定读指针的起点：把读位置回退 target_delay，等于先播一段静音，
  // 这段静音就是抖动缓冲的"余量"。这是延迟唯一被显式施加的地方。
  if (!started_) {
    read_pos_ = pos - MsToFrames(target_delay_ms_);
    highest_written_ = read_pos_;
    started_ = true;
  }

  int src_offset = 0;
  int64_t end = pos + frames;

  if (end <= read_pos_) {
    stats_.packets_late++;
    return false;
  }

  // 部分迟到：前半段已经被读走了，但后半段还有用，裁掉不要再丢整包。
  if (pos < read_pos_) {
    const int skip = static_cast<int>(read_pos_ - pos);
    src_offset += skip * channels_;
    pos = read_pos_;
    frames -= skip;
    stats_.frames_trimmed += static_cast<uint64_t>(skip);
  }

  if (pos + frames > read_pos_ + capacity_) {
    // 远超容量：对端时间戳跳变或本端延迟配置错误。整包丢弃比写坏缓冲安全。
    stats_.packets_overflow++;
    return false;
  }

  if (pos < highest_written_) {
    stats_.frames_reordered++;
  }

  const float* src = interleaved + src_offset;
  for (int i = 0; i < frames; ++i) {
    const int64_t p = pos + i;
    const int64_t idx = RingIndex(p);
    std::memcpy(&ring_[static_cast<size_t>(idx) * channels_], src + static_cast<size_t>(i) * channels_,
                sizeof(float) * channels_);
    SetValid(p, true);
  }

  highest_written_ = std::max(highest_written_, pos + frames);
  return true;
}

void JitterBuffer::Pull(float* dst, int frames) {
  std::lock_guard<std::mutex> lock(mutex_);

  const size_t total = static_cast<size_t>(frames) * channels_;
  if (!started_) {
    std::memset(dst, 0, total * sizeof(float));
    return;
  }

  // 扩容时先输出一段静音，且不推进读指针——声音整体后移，延迟真正变大。
  if (pending_silence_frames_ > 0) {
    const int n = std::min(pending_silence_frames_, frames);
    std::memset(dst, 0, static_cast<size_t>(n) * channels_ * sizeof(float));
    pending_silence_frames_ -= n;
    dst += static_cast<size_t>(n) * channels_;
    frames -= n;
    if (frames == 0) return;
  }

  for (int i = 0; i < frames; ++i) {
    const int64_t p = read_pos_ + i;
    float* out = dst + static_cast<size_t>(i) * channels_;
    if (IsValid(p)) {
      const int64_t idx = RingIndex(p);
      std::memcpy(out, &ring_[static_cast<size_t>(idx) * channels_], sizeof(float) * channels_);
      SetValid(p, false);  // 消费掉，避免回绕后被误判为有效
    } else {
      // P0 直接补零。生产版这里应该接 Opus PLC 或"重复上一帧 + 淡出"，
      // 补零会听到明显的"咔"，见 docs 的难点清单第 5 条。
      std::memset(out, 0, sizeof(float) * channels_);
      stats_.frames_underrun++;
    }
  }
  read_pos_ += frames;
}

void JitterBuffer::InsertSilence(int frames) {
  if (frames <= 0) return;
  std::lock_guard<std::mutex> lock(mutex_);
  pending_silence_frames_ += frames;
}

void JitterBuffer::Reset() {
  std::lock_guard<std::mutex> lock(mutex_);
  std::fill(valid_bits_.begin(), valid_bits_.end(), 0ULL);
  read_pos_ = 0;
  highest_written_ = 0;
  started_ = false;
  pending_silence_frames_ = 0;
  stats_ = JitterBufferStats{};
}

void JitterBuffer::FastForward(int frames) {
  if (frames <= 0) return;
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  // 被跳过的那段必须清掉有效位，否则读指针绕回来时会被当成"写过"的旧数据。
  for (int i = 0; i < frames; ++i) SetValid(read_pos_ + i, false);
  read_pos_ += frames;
  stats_.frames_dropped += static_cast<uint64_t>(frames);
}

int JitterBuffer::occupancy_ms() const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return 0;
  const int64_t frames = highest_written_ - read_pos_;
  if (frames <= 0) return 0;
  return static_cast<int>(frames * 1000 / kSampleRate);
}

bool JitterBuffer::started() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return started_;
}

JitterBufferStats JitterBuffer::stats() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return stats_;
}

void JitterBuffer::ResetStats() {
  std::lock_guard<std::mutex> lock(mutex_);
  stats_ = JitterBufferStats{};
}

}  // namespace audiohub
