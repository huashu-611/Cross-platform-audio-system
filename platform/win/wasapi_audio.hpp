// WASAPI 采集与渲染（Windows 专用）。
//
// 只依赖 Windows 自带的 WASAPI，不引入任何第三方库——
// 这是"零安装成本"的前提：用户不需要装虚拟声卡驱动。
//
// 两个关键设计点：
//   1) 采集用 AUDCLNT_STREAMFLAGS_LOOPBACK，抓的是系统混音之后的信号。
//      这意味着它也会抓到"本程序自己播出来的声音"。如果 Hub 同时要抓本机音频，
//      必须改用 process loopback 的 EXCLUDE 模式排除自身进程树，
//      否则会形成无限反馈。P0 先不支持 Hub 自采，接口预留。
//   2) 用 AUTOCONVERTPCM 强制协商到 48 kHz / float32 / 立体声。
//      否则拿到的是设备混音格式（可能是 44.1 kHz 或 16 bit），
//      一旦自己转换，延迟和重采样质量都不可控。
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

  // buffer_ms 越小采集延迟越低，但被调度打断的风险越高。20 ms 是稳妥值。
  bool Start(CaptureCallback callback, int buffer_ms = 20);
  void Stop();
  bool running() const { return running_.load(); }
  const std::string& last_error() const { return last_error_; }

  // 打印可用的输出设备（loopback 是针对"渲染设备"的）。
  static std::string ListRenderDevices();

 private:
  void ThreadMain();

  CaptureCallback callback_;
  int buffer_ms_ = 20;
  std::atomic<bool> running_{false};
  std::atomic<bool> stop_requested_{false};
  std::thread thread_;
  std::string last_error_;
};

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
