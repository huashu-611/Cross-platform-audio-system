// 单生产者 / 单消费者无锁环形缓冲。
//
// 为什么必须是 SPSC 无锁：设备回调（WASAPI 的实时线程 / AAudio 的 MMAP 回调）
// 里不能加锁。一旦后台线程持有锁时被调度走，回调就会阻塞超过一个设备周期，
// 直接产生可听见的爆音。而且这种 bug 在开发机上几乎复现不出来。
//
// 约定：容量会自动向上取整到 2 的幂，用位与代替取模。
#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <vector>

namespace audiohub {

template <typename T>
class SpscRing {
 public:
  explicit SpscRing(size_t min_capacity) {
    size_t cap = 1;
    while (cap < min_capacity) cap <<= 1;
    buffer_.resize(cap);
    mask_ = cap - 1;
  }

  // 返回实际写入的元素数。空间不足时只写入能容纳的部分，不阻塞。
  size_t Write(const T* src, size_t count) {
    const size_t w = write_.load(std::memory_order_relaxed);
    const size_t r = read_.load(std::memory_order_acquire);
    const size_t free_slots = buffer_.size() - (w - r);
    if (count > free_slots) count = free_slots;
    if (count == 0) return 0;

    const size_t idx = w & mask_;
    const size_t first = (buffer_.size() - idx) < count ? (buffer_.size() - idx) : count;
    std::memcpy(&buffer_[idx], src, first * sizeof(T));
    if (count > first) {
      std::memcpy(&buffer_[0], src + first, (count - first) * sizeof(T));
    }
    // release：保证数据可见性先于索引更新对消费者可见。
    write_.store(w + count, std::memory_order_release);
    return count;
  }

  // 返回实际读出的元素数。读取后相应位置清零不是必须的，调用方应检查返回值。
  size_t Read(T* dst, size_t count) {
    const size_t r = read_.load(std::memory_order_relaxed);
    const size_t w = write_.load(std::memory_order_acquire);
    const size_t avail = w - r;
    if (count > avail) count = avail;
    if (count == 0) return 0;

    const size_t idx = r & mask_;
    const size_t first = (buffer_.size() - idx) < count ? (buffer_.size() - idx) : count;
    std::memcpy(dst, &buffer_[idx], first * sizeof(T));
    if (count > first) {
      std::memcpy(dst + first, &buffer_[0], (count - first) * sizeof(T));
    }
    read_.store(r + count, std::memory_order_release);
    return count;
  }

  size_t Available() const {
    return write_.load(std::memory_order_acquire) - read_.load(std::memory_order_acquire);
  }

  size_t Capacity() const { return buffer_.size(); }

  // 只在两端都停止时调用（如重新连接）。
  void Clear() {
    read_.store(0, std::memory_order_relaxed);
    write_.store(0, std::memory_order_relaxed);
  }

 private:
  std::vector<T> buffer_;
  size_t mask_ = 0;
  alignas(64) std::atomic<size_t> write_{0};
  alignas(64) std::atomic<size_t> read_{0};
};

}  // namespace audiohub
