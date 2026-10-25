#include "wasapi_audio.hpp"

#ifdef _WIN32

#include <windows.h>

#include <audioclient.h>
#include <mmdeviceapi.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <vector>

#include "audiohub/audio_format.hpp"

namespace audiohub {
namespace {

// 显式声明 GUID，而不是用 __uuidof 或依赖 libuuid。
// 原因：GCC(MinGW) 不支持 __uuidof，而 libuuid 里未必带这几个 IID；
// 硬编码值来自 Windows SDK 头文件，稳定且可移植。
const GUID kCLSID_MMDeviceEnumerator = {
    0xBCDE0395, 0xE52F, 0x467C, {0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}};
const GUID kIID_IMMDeviceEnumerator = {
    0xA95664D2, 0x9614, 0x4F35, {0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}};
const GUID kIID_IAudioClient = {
    0x1CB9AD4C, 0xDBFA, 0x4C32, {0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2}};
const GUID kIID_IAudioCaptureClient = {
    0xC8ADBD64, 0xE71E, 0x48A0, {0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17}};
const GUID kIID_IAudioRenderClient = {
    0xF294ACFC, 0x3146, 0x4483, {0xA7, 0xBF, 0xAD, 0xDC, 0xA7, 0xC2, 0x60, 0xE2}};
const GUID kSubFormatIeeeFloat = {
    0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};

std::string HrText(const char* what, HRESULT hr) {
  char buf[128];
  std::snprintf(buf, sizeof(buf), "%s failed (hr=0x%08lX)", what,
                static_cast<unsigned long>(hr));
  return buf;
}

// 构造 48 kHz / float32 / 立体声的 WAVEFORMATEXTENSIBLE。
// 用 EXTENSIBLE 而不是裸 WAVEFORMATEX，是因为不少板载声卡驱动
// 会拒绝非 EXTENSIBLE 的多声道/浮点请求。
void FillWaveFormat(WAVEFORMATEXTENSIBLE* wfx) {
  std::memset(wfx, 0, sizeof(*wfx));
  wfx->Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
  wfx->Format.nChannels = static_cast<WORD>(kChannels);
  wfx->Format.nSamplesPerSec = kSampleRate;
  wfx->Format.wBitsPerSample = 32;
  wfx->Format.nBlockAlign = static_cast<WORD>(kChannels * 4);
  wfx->Format.nAvgBytesPerSec = kSampleRate * wfx->Format.nBlockAlign;
  wfx->Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
  wfx->Samples.wValidBitsPerSample = 32;
  wfx->dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
  wfx->SubFormat = kSubFormatIeeeFloat;
}

}  // namespace

// ---------------------------------------------------------------- Capture

WasapiCapture::WasapiCapture() = default;

WasapiCapture::~WasapiCapture() { Stop(); }

bool WasapiCapture::Start(CaptureCallback callback, int buffer_ms) {
  if (running_.load()) return false;
  callback_ = std::move(callback);
  buffer_ms_ = std::max(5, buffer_ms);
  last_error_.clear();
  stop_requested_.store(false);
  running_.store(true);
  thread_ = std::thread(&WasapiCapture::ThreadMain, this);
  // 给线程一点时间初始化；失败会在 running_ 上体现。
  Sleep(50);
  return running_.load();
}

void WasapiCapture::Stop() {
  stop_requested_.store(true);
  if (thread_.joinable()) thread_.join();
  running_.store(false);
}

void WasapiCapture::ThreadMain() {
  const HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);

  IMMDeviceEnumerator* enumerator = nullptr;
  IMMDevice* device = nullptr;
  IAudioClient* client = nullptr;
  IAudioCaptureClient* capture = nullptr;
  HANDLE event = nullptr;
  bool started = false;

  auto cleanup = [&]() {
    if (started && client != nullptr) client->Stop();
    if (event != nullptr) CloseHandle(event);
    if (capture != nullptr) capture->Release();
    if (client != nullptr) client->Release();
    if (device != nullptr) device->Release();
    if (enumerator != nullptr) enumerator->Release();
    if (SUCCEEDED(co)) CoUninitialize();
    running_.store(false);
  };

  HRESULT hr = CoCreateInstance(kCLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                kIID_IMMDeviceEnumerator, reinterpret_cast<void**>(&enumerator));
  if (FAILED(hr)) {
    last_error_ = HrText("CoCreateInstance(MMDeviceEnumerator)", hr);
    cleanup();
    return;
  }

  hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);
  if (FAILED(hr)) {
    last_error_ = HrText("GetDefaultAudioEndpoint", hr);
    cleanup();
    return;
  }

  hr = device->Activate(kIID_IAudioClient, CLSCTX_ALL, nullptr,
                        reinterpret_cast<void**>(&client));
  if (FAILED(hr)) {
    last_error_ = HrText("Activate(IAudioClient)", hr);
    cleanup();
    return;
  }

  WAVEFORMATEXTENSIBLE wfx;
  FillWaveFormat(&wfx);

  // AUTOCONVERTPCM：让音频引擎替我们把设备混音格式转换到 48k/float32，
  // 而不是我们自己去重采样。SRC_DEFAULT_QUALITY 是它的配套开关。
  const DWORD flags = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
                      AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                      AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
  const REFERENCE_TIME duration = static_cast<REFERENCE_TIME>(buffer_ms_) * 10000;

  hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, duration, 0,
                          reinterpret_cast<WAVEFORMATEX*>(&wfx), nullptr);
  if (FAILED(hr)) {
    last_error_ = HrText("IAudioClient::Initialize(loopback)", hr);
    cleanup();
    return;
  }

  event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
  if (event == nullptr) {
    last_error_ = "CreateEvent failed";
    cleanup();
    return;
  }
  if (FAILED(hr = client->SetEventHandle(event))) {
    last_error_ = HrText("SetEventHandle", hr);
    cleanup();
    return;
  }
  if (FAILED(hr = client->GetService(kIID_IAudioCaptureClient,
                                     reinterpret_cast<void**>(&capture)))) {
    last_error_ = HrText("GetService(IAudioCaptureClient)", hr);
    cleanup();
    return;
  }
  if (FAILED(hr = client->Start())) {
    last_error_ = HrText("IAudioClient::Start", hr);
    cleanup();
    return;
  }
  started = true;

  std::vector<float> silence;

  while (!stop_requested_.load()) {
    const DWORD waited = WaitForSingleObject(event, 200);
    if (waited != WAIT_OBJECT_0) continue;

    UINT32 packet_frames = 0;
    while (SUCCEEDED(capture->GetNextPacketSize(&packet_frames)) && packet_frames > 0) {
      BYTE* data = nullptr;
      UINT32 frames = 0;
      DWORD buffer_flags = 0;
      hr = capture->GetBuffer(&data, &frames, &buffer_flags, nullptr, nullptr);
      if (FAILED(hr)) {
        // 设备被拔掉/切换时会出现，直接退出，由上层决定是否重连。
        last_error_ = HrText("IAudioCaptureClient::GetBuffer", hr);
        stop_requested_.store(true);
        break;
      }

      if ((buffer_flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || data == nullptr) {
        // 静音包里 data 内容是未定义的，必须自己填 0，不能直接拿去用。
        const size_t samples = static_cast<size_t>(frames) * kChannels;
        if (silence.size() < samples) silence.resize(samples, 0.0f);
        callback_(silence.data(), static_cast<int>(frames));
      } else {
        callback_(reinterpret_cast<const float*>(data), static_cast<int>(frames));
      }
      capture->ReleaseBuffer(frames);
    }
  }

  cleanup();
}

std::string WasapiCapture::ListRenderDevices() {
  const HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  std::string out;

  IMMDeviceEnumerator* enumerator = nullptr;
  IMMDevice* device = nullptr;
  if (SUCCEEDED(CoCreateInstance(kCLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                 kIID_IMMDeviceEnumerator,
                                 reinterpret_cast<void**>(&enumerator)))) {
    if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device))) {
      LPWSTR id = nullptr;
      if (SUCCEEDED(device->GetId(&id)) && id != nullptr) {
        // 只打印设备 ID（UTF-16 -> 粗略 ASCII 投影），P0 不做完整枚举。
        char narrow[512] = {0};
        WideCharToMultiByte(CP_ACP, 0, id, -1, narrow, sizeof(narrow) - 1, nullptr, nullptr);
        out = std::string("[default] ") + narrow;
        CoTaskMemFree(id);
      }
      device->Release();
    }
    enumerator->Release();
  }
  if (SUCCEEDED(co)) CoUninitialize();
  return out;
}

// ---------------------------------------------------------------- Render

WasapiRender::WasapiRender() = default;

WasapiRender::~WasapiRender() { Stop(); }

bool WasapiRender::Start(RenderCallback callback, int latency_ms) {
  if (running_.load()) return false;
  callback_ = std::move(callback);
  latency_ms_ = std::max(5, latency_ms);
  last_error_.clear();
  stop_requested_.store(false);
  running_.store(true);
  thread_ = std::thread(&WasapiRender::ThreadMain, this);
  Sleep(50);
  return running_.load();
}

void WasapiRender::Stop() {
  stop_requested_.store(true);
  if (thread_.joinable()) thread_.join();
  running_.store(false);
}

void WasapiRender::ThreadMain() {
  const HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);

  IMMDeviceEnumerator* enumerator = nullptr;
  IMMDevice* device = nullptr;
  IAudioClient* client = nullptr;
  IAudioRenderClient* render = nullptr;
  HANDLE event = nullptr;
  bool started = false;

  auto cleanup = [&]() {
    if (started && client != nullptr) client->Stop();
    if (event != nullptr) CloseHandle(event);
    if (render != nullptr) render->Release();
    if (client != nullptr) client->Release();
    if (device != nullptr) device->Release();
    if (enumerator != nullptr) enumerator->Release();
    if (SUCCEEDED(co)) CoUninitialize();
    running_.store(false);
  };

  HRESULT hr = CoCreateInstance(kCLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                kIID_IMMDeviceEnumerator, reinterpret_cast<void**>(&enumerator));
  if (FAILED(hr)) {
    last_error_ = HrText("CoCreateInstance(MMDeviceEnumerator)", hr);
    cleanup();
    return;
  }
  if (FAILED(hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device))) {
    last_error_ = HrText("GetDefaultAudioEndpoint", hr);
    cleanup();
    return;
  }
  if (FAILED(hr = device->Activate(kIID_IAudioClient, CLSCTX_ALL, nullptr,
                                   reinterpret_cast<void**>(&client)))) {
    last_error_ = HrText("Activate(IAudioClient)", hr);
    cleanup();
    return;
  }

  WAVEFORMATEXTENSIBLE wfx;
  FillWaveFormat(&wfx);

  const DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                      AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
  const REFERENCE_TIME duration = static_cast<REFERENCE_TIME>(latency_ms_) * 10000;

  if (FAILED(hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, duration, 0,
                                     reinterpret_cast<WAVEFORMATEX*>(&wfx), nullptr))) {
    last_error_ = HrText("IAudioClient::Initialize(render)", hr);
    cleanup();
    return;
  }

  event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
  if (event == nullptr) {
    last_error_ = "CreateEvent failed";
    cleanup();
    return;
  }
  if (FAILED(hr = client->SetEventHandle(event))) {
    last_error_ = HrText("SetEventHandle", hr);
    cleanup();
    return;
  }
  if (FAILED(hr = client->GetService(kIID_IAudioRenderClient,
                                     reinterpret_cast<void**>(&render)))) {
    last_error_ = HrText("GetService(IAudioRenderClient)", hr);
    cleanup();
    return;
  }

  UINT32 buffer_frames = 0;
  if (FAILED(hr = client->GetBufferSize(&buffer_frames)) || buffer_frames == 0) {
    last_error_ = HrText("GetBufferSize", hr);
    cleanup();
    return;
  }
  device_latency_ms_ = latency_ms_;

  // 启动前先灌满一整个缓冲，否则第一次回调之前必然 underrun，
  // 表现为启动瞬间"咔"一声。
  BYTE* data = nullptr;
  if (SUCCEEDED(render->GetBuffer(buffer_frames, &data))) {
    callback_(reinterpret_cast<float*>(data), static_cast<int>(buffer_frames));
    render->ReleaseBuffer(buffer_frames, 0);
  }
  if (FAILED(hr = client->Start())) {
    last_error_ = HrText("IAudioClient::Start", hr);
    cleanup();
    return;
  }
  started = true;

  while (!stop_requested_.load()) {
    if (WaitForSingleObject(event, 200) != WAIT_OBJECT_0) continue;

    UINT32 padding = 0;
    if (FAILED(client->GetCurrentPadding(&padding))) continue;
    const UINT32 available = buffer_frames - padding;
    if (available == 0) continue;

    if (FAILED(render->GetBuffer(available, &data))) continue;
    // 这里就是"设备回调"：只允许 memcpy，回调实现里不能加锁/分配。
    callback_(reinterpret_cast<float*>(data), static_cast<int>(available));
    render->ReleaseBuffer(available, 0);
  }

  cleanup();
}

}  // namespace audiohub

#endif  // _WIN32
