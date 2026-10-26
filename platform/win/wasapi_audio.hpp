// WASAPI 采集与渲染（Windows 专用）。
//
// 只依赖 Windows 自带的 WASAPI，不引入任何第三方库——
// 这是"零安装成本"的前提：用户不需要装虚拟声卡驱动。
//
// 采集有两条路径：
//   1) 整机回环（默认）：抓默认渲染设备的系统混音。
//      它也会抓到"本程序自己播出来的声音"，在单机上跑 source+hub 会形成反馈回路。
//   2) 进程回环（Win10 2004+）：只抓 / 排除指定进程树。
//      这是解除单机反馈回路、以及"只抓某个 App"的正解。
//
// 输出统一协商到 48 kHz / float32 / 立体声：如果设备拒绝，
// 退一步用设备混音格式，但只接受 48k/2ch 的 float32 或 PCM16。
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <thread>

namespace audiohub {

// 采集回调运行在 WASAPI 的采集线程上，必须尽快返回，
// 不要在里面分配内存、加锁或做 IO。
using CaptureCallback = std::function<void(const float* interleaved, int frames)>;

class WasapiCapture {
 public:
  WasapiCapture();
  ~WasapiCapture();

  // 整机回环。buffer_ms 越小采集延迟越低，但被调度打断的风险越高。
  bool Start(CaptureCallback callback, int buffer_ms = 20);

  // 进程回环：只抓（exclude=false）或排除（exclude=true）target_pid 的进程树。
  // 需要 Windows 10 2004（build 19041）及以上。
  bool StartProcessLoopback(CaptureCallback callback, uint32_t target_pid, bool exclude,
                            int buffer_ms = 20);

  void Stop();
  bool running() const { return running_.load(); }
  const std::string& last_error() const { return last_error_; }

  // 打印可用的输出设备（loopback 是针对"渲染设备"的）。
  static std::string ListRenderDevices();

 private:
  void ThreadMain();
  // 拿到 IAudioClient 之后的公共流程：协商格式 → 取 capture client → 循环取数。
  // 参数用 void* 是为了不让这个头文件把 windows.h 拖进来（它也被非 COM 的代码包含）。
  void RunCapture(void* audio_client, void* event_handle, bool event_driven);

  enum class Mode { kSystemLoopback, kProcessLoopback };

  CaptureCallback callback_;
  int buffer_ms_ = 20;
  Mode mode_ = Mode::kSystemLoopback;
  uint32_t target_pid_ = 0;
  bool exclude_target_ = false;

  std::atomic<bool> running_{false};
  std::atomic<bool> stop_requested_{false};
  std::thread thread_;
  std::string last_error_;
};

// 按可执行文件名找 PID（不区分大小写），找不到返回 0。
uint32_t FindProcessIdByName(const std::string& exe_name);

// 静音/取消静音"默认播放设备"。
//
// 为什么需要：作为音源时我们采集的是回环（loopback），回环**不会**
// 把本机输出静音——声音会同时留在电脑耳机里和远端，既重复又有回声。
// 真正"把声音搬走"就必须显式把本机输出静音，退出时再恢复。
//
// previous 非空时回填调用前的静音状态，供退出时还原。
bool SetDefaultRenderMute(bool mute, bool* previous);

// 渲染回调运行在 WASAPI 的实时线程上，只允许从无锁环 memcpy。
using RenderCallback = std::function<void(float* interleaved, int frames)>;

class WasapiRender {
 public:
  WasapiRender();
  ~WasapiRender();

  bool Start(RenderCallback callback, int latency_ms = 10);
  void Stop();
  bool running() const { return running_.load(); }
  const std::string& last_error() const { return last_error_; }

  // 设备侧的真实输出延迟（毫秒）。调用方应该把它当作已知常量从
  // 目标延迟里扣掉，否则"设计 60 ms"实际会是 80 ms。
  int device_latency_ms() const { return device_latency_ms_; }

 private:
  void ThreadMain();

  RenderCallback callback_;
  int latency_ms_ = 10;
  int device_latency_ms_ = 0;
  std::atomic<bool> running_{false};
  std::atomic<bool> stop_requested_{false};
  std::thread thread_;
  std::string last_error_;
};

}  // namespace audiohub
