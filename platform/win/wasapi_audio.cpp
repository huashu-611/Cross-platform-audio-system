#include "wasapi_audio.hpp"

#ifdef _WIN32

#include <windows.h>

#include <audioclient.h>
#include <mmdeviceapi.h>
#include <tlhelp32.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

#include "audiohub/audio_format.hpp"

// 老版本 SDK 头文件里没有 IAgileObject（Win8 才加），这里兜底声明。
// 它是纯标记接口，没有方法——作用只是告诉 COM"可以从任何套间调用我"。
#ifndef __IAgileObject_INTERFACE_DEFINED__
MIDL_INTERFACE("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90")
IAgileObject : public IUnknown {};
#define __IAgileObject_INTERFACE_DEFINED__
#endif

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

const GUID kIID_IUnknown_ = {
    0x00000000, 0x0000, 0x0000, {0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
const GUID kIID_ActivateCompletionHandler = {
    0x41D949AB, 0x9862, 0x444A, {0x80, 0xF6, 0xC2, 0x61, 0x33, 0x4D, 0xA5, 0xEB}};
const GUID kIID_AgileObject = {
    0x94EA2B94, 0xE9CC, 0x49E0, {0xC0, 0xFF, 0xEE, 0x64, 0xCA, 0x8F, 0x5B, 0x90}};

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

// ---------------------------------------------------------------- 进程回环

// AUDIOCLIENT_ACTIVATION_PARAMS 在 audioclientactivationparams.h 里，
// MinGW 不一定带这个头文件。按 SDK 布局手工定义（两个 enum + 一个 DWORD，
// 全是 4 字节，没有对齐陷阱）。
struct ActivationParams {
  int activation_type;  // 0 = 默认, 1 = 进程回环
  struct {
    DWORD target_pid;
    int loopback_mode;  // 0 = 只抓目标进程树, 1 = 排除目标进程树
  } process;
};

using PfnActivateAudioInterfaceAsync = HRESULT(WINAPI*)(
    LPCWSTR device_interface_path, REFIID riid, PROPVARIANT* activation_params,
    IActivateAudioInterfaceCompletionHandler* completion_handler,
    IActivateAudioInterfaceAsyncOperation** activation_operation);

// 必须同时实现 IActivateAudioInterfaceCompletionHandler 和 IAgileObject。
// 只实现前者时 ActivateAudioInterfaceAsync 会返回 E_ILLEGAL_METHOD_CALL，
// 而且不告诉你原因——这是这个 API 最著名的坑。
class ActivationHandler final : public IActivateAudioInterfaceCompletionHandler,
                                public IAgileObject {
 public:
  explicit ActivationHandler(HANDLE done) : done_(done) {}

  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
    if (ppv == nullptr) return E_POINTER;
    if (IsEqualIID(riid, kIID_IUnknown_) || IsEqualIID(riid, kIID_ActivateCompletionHandler)) {
      *ppv = static_cast<IActivateAudioInterfaceCompletionHandler*>(this);
    } else if (IsEqualIID(riid, kIID_AgileObject)) {
      *ppv = static_cast<IAgileObject*>(this);
    } else {
      *ppv = nullptr;
      return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
  }

  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&ref_); }

  ULONG STDMETHODCALLTYPE Release() override {
    const ULONG r = InterlockedDecrement(&ref_);
    if (r == 0) delete this;
    return r;
  }

  HRESULT STDMETHODCALLTYPE ActivateCompleted(
      IActivateAudioInterfaceAsyncOperation* op) override {
    HRESULT hr = E_FAIL;
    IUnknown* unknown = nullptr;
    if (op != nullptr) {
      op->GetActivateResult(&hr, &unknown);
      if (SUCCEEDED(hr) && unknown != nullptr) {
        unknown->QueryInterface(kIID_IAudioClient, reinterpret_cast<void**>(&client_));
        unknown->Release();
      }
    }
    result_ = SUCCEEDED(hr) && client_ != nullptr ? hr : (FAILED(hr) ? hr : E_FAIL);
    SetEvent(done_);
    return S_OK;
  }

  IAudioClient* TakeClient() {
    IAudioClient* c = client_;
    client_ = nullptr;
    return c;
  }
  HRESULT result() const { return result_; }

 private:
  ~ActivationHandler() = default;

  LONG ref_ = 1;
  HANDLE done_ = nullptr;
  IAudioClient* client_ = nullptr;
  HRESULT result_ = E_FAIL;
};

bool ActivateProcessLoopbackClient(DWORD target_pid, bool exclude, IAudioClient** out_client,
                                   std::string* error) {
  HMODULE module = LoadLibraryW(L"mmdevapi.dll");
  if (module == nullptr) {
    *error = "加载 mmdevapi.dll 失败";
    return false;
  }
  // 用 memcpy 而不是指针强转：直接 cast FARPROC 到具体签名会触发
  // -Wcast-function-type，用 *reinterpret_cast<FARPROC*>(&fn) 又会触发
  // -Wstrict-aliasing。memcpy 是标准里唯一干净的做法。
  const FARPROC proc = GetProcAddress(module, "ActivateAudioInterfaceAsync");
  PfnActivateAudioInterfaceAsync activate = nullptr;
  static_assert(sizeof(activate) == sizeof(proc), "函数指针尺寸不匹配");
  std::memcpy(&activate, &proc, sizeof(proc));
  if (activate == nullptr) {
    *error = "找不到 ActivateAudioInterfaceAsync（需要 Windows 10 2004 及以上）";
    return false;
  }

  ActivationParams params{};
  params.activation_type = 1;  // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
  params.process.target_pid = target_pid;
  params.process.loopback_mode =
      exclude ? 1 : 0;  // PROCESS_LOOPBACK_MODE_EXCLUDE / INCLUDE_TARGET_PROCESS_TREE

  PROPVARIANT pv;
  std::memset(&pv, 0, sizeof(pv));
  pv.vt = VT_BLOB;
  pv.blob.cbSize = sizeof(params);
  pv.blob.pBlobData = reinterpret_cast<BYTE*>(&params);

  HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (done == nullptr) {
    *error = "CreateEvent 失败";
    return false;
  }

  auto* handler = new ActivationHandler(done);
  IActivateAudioInterfaceAsyncOperation* operation = nullptr;
  const HRESULT hr = activate(L"VAD\\Process_Loopback", kIID_IAudioClient, &pv, handler,
                              &operation);
  if (FAILED(hr)) {
    *error = HrText("ActivateAudioInterfaceAsync", hr);
    handler->Release();
    CloseHandle(done);
    return false;
  }

  bool ok = false;
  if (WaitForSingleObject(done, 5000) == WAIT_OBJECT_0) {
    *out_client = handler->TakeClient();
    if (*out_client != nullptr) {
      ok = true;
    } else {
      *error = HrText("进程回环激活", handler->result());
    }
  } else {
    *error = "进程回环激活超时";
  }

  if (operation != nullptr) operation->Release();
  handler->Release();
  CloseHandle(done);
  return ok;
}

}  // namespace

// ---------------------------------------------------------------- Capture

WasapiCapture::WasapiCapture() = default;

WasapiCapture::~WasapiCapture() { Stop(); }

bool WasapiCapture::Start(CaptureCallback callback, int buffer_ms) {
  if (running_.load()) return false;
  callback_ = std::move(callback);
  buffer_ms_ = std::max(5, buffer_ms);
  mode_ = Mode::kSystemLoopback;
  last_error_.clear();
  stop_requested_.store(false);
  running_.store(true);
  thread_ = std::thread(&WasapiCapture::ThreadMain, this);
  Sleep(50);
  return running_.load();
}

bool WasapiCapture::StartProcessLoopback(CaptureCallback callback, uint32_t target_pid,
                                         bool exclude, int buffer_ms) {
  if (running_.load()) return false;
  if (target_pid == 0) {
    last_error_ = "进程回环需要有效的 PID";
    return false;
  }
  callback_ = std::move(callback);
  buffer_ms_ = std::max(5, buffer_ms);
  mode_ = Mode::kProcessLoopback;
  target_pid_ = target_pid;
  exclude_target_ = exclude;
  last_error_.clear();
  stop_requested_.store(false);
  running_.store(true);
  thread_ = std::thread(&WasapiCapture::ThreadMain, this);
  Sleep(80);  // 激活是异步的，多给一点时间
  return running_.load();
}

void WasapiCapture::Stop() {
  stop_requested_.store(true);
  if (thread_.joinable()) thread_.join();
  running_.store(false);
}

void WasapiCapture::ThreadMain() {
  const HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);

  IAudioClient* client = nullptr;

  if (mode_ == Mode::kProcessLoopback) {
    if (!ActivateProcessLoopbackClient(target_pid_, exclude_target_, &client, &last_error_)) {
      if (SUCCEEDED(co)) CoUninitialize();
      running_.store(false);
      return;
    }
  } else {
    IMMDeviceEnumerator* enumerator = nullptr;
    IMMDevice* device = nullptr;
    HRESULT hr = CoCreateInstance(kCLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                  kIID_IMMDeviceEnumerator,
                                  reinterpret_cast<void**>(&enumerator));
    if (FAILED(hr)) {
      last_error_ = HrText("CoCreateInstance(MMDeviceEnumerator)", hr);
    } else if (FAILED(hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device))) {
      last_error_ = HrText("GetDefaultAudioEndpoint", hr);
    } else if (FAILED(hr = device->Activate(kIID_IAudioClient, CLSCTX_ALL, nullptr,
                                            reinterpret_cast<void**>(&client)))) {
      last_error_ = HrText("Activate(IAudioClient)", hr);
    }
    if (device != nullptr) device->Release();
    if (enumerator != nullptr) enumerator->Release();

    if (client == nullptr) {
      if (SUCCEEDED(co)) CoUninitialize();
      running_.store(false);
      return;
    }
  }

  // 进程回环用轮询：这个模式下事件驱动回调的支持情况因系统版本而异，
  // 2 ms 一次的轮询换来的是行为确定，代价可忽略。
  const bool event_driven = (mode_ == Mode::kSystemLoopback);
  RunCapture(client, nullptr, event_driven);

  client->Release();
  if (SUCCEEDED(co)) CoUninitialize();
  running_.store(false);
}

void WasapiCapture::RunCapture(void* audio_client, void* event_handle, bool event_driven) {
  IAudioClient* client = static_cast<IAudioClient*>(audio_client);
  HANDLE event = static_cast<HANDLE>(event_handle);

  WAVEFORMATEXTENSIBLE wfx;
  FillWaveFormat(&wfx);

  const DWORD base_flags = AUDCLNT_STREAMFLAGS_LOOPBACK |
                           AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                           AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
  const REFERENCE_TIME duration = static_cast<REFERENCE_TIME>(buffer_ms_) * 10000;

  DWORD flags = event_driven ? (base_flags | AUDCLNT_STREAMFLAGS_EVENTCALLBACK) : base_flags;

  bool is_float = true;
  HRESULT hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, duration, 0,
                                  reinterpret_cast<WAVEFORMATEX*>(&wfx), nullptr);

  if (FAILED(hr) && event_driven) {
    // 有些设备/驱动组合不支持事件驱动，退回轮询。
    event_driven = false;
    flags = base_flags;
    hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, duration, 0,
                            reinterpret_cast<WAVEFORMATEX*>(&wfx), nullptr);
  }

  if (FAILED(hr)) {
    // 最后退一步：用设备混音格式。只接受 48k/2ch 的 float32 或 PCM16——
    // 其它情况宁可报错，也不要在热路径上偷偷重采样（延迟和相位都不可控）。
    WAVEFORMATEX* mix = nullptr;
    if (SUCCEEDED(client->GetMixFormat(&mix)) && mix != nullptr) {
      is_float = (mix->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) ||
                 (mix->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
                  IsEqualGUID(reinterpret_cast<WAVEFORMATEXTENSIBLE*>(mix)->SubFormat,
                              kSubFormatIeeeFloat));
      const bool supported = mix->nChannels == kChannels &&
                             mix->nSamplesPerSec == kSampleRate &&
                             (is_float ? mix->wBitsPerSample == 32 : mix->wBitsPerSample == 16);
      if (supported) {
        hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, duration, 0, mix, nullptr);
      } else {
        char buf[160];
        std::snprintf(buf, sizeof(buf),
                      "设备混音格式不支持：%lu Hz / %u ch / %u bit"
                      "（需要 48 kHz 双声道 float32 或 int16）",
                      static_cast<unsigned long>(mix->nSamplesPerSec), mix->nChannels,
                      mix->wBitsPerSample);
        last_error_ = buf;
      }
      CoTaskMemFree(mix);
    }
  }

  if (FAILED(hr)) {
    if (last_error_.empty()) last_error_ = HrText("IAudioClient::Initialize(capture)", hr);
    return;
  }

  IAudioCaptureClient* capture = nullptr;
  if (FAILED(hr = client->GetService(kIID_IAudioCaptureClient,
                                     reinterpret_cast<void**>(&capture)))) {
    last_error_ = HrText("GetService(IAudioCaptureClient)", hr);
    return;
  }

  if (event_driven) {
    event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (event == nullptr || FAILED(hr = client->SetEventHandle(event))) {
      last_error_ = event == nullptr ? "CreateEvent 失败" : HrText("SetEventHandle", hr);
      if (event != nullptr) CloseHandle(event);
      capture->Release();
      return;
    }
  }

  if (FAILED(hr = client->Start())) {
    last_error_ = HrText("IAudioClient::Start", hr);
    if (event != nullptr) CloseHandle(event);
    capture->Release();
    return;
  }

  std::vector<float> scratch;
  std::vector<float> silence;

  while (!stop_requested_.load()) {
    if (event_driven) {
      if (WaitForSingleObject(event, 200) != WAIT_OBJECT_0) continue;
    } else {
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }

    UINT32 packet_frames = 0;
    while (SUCCEEDED(capture->GetNextPacketSize(&packet_frames)) && packet_frames > 0) {
      BYTE* data = nullptr;
      UINT32 frames = 0;
      DWORD buffer_flags = 0;
      hr = capture->GetBuffer(&data, &frames, &buffer_flags, nullptr, nullptr);
      if (FAILED(hr)) {
        last_error_ = HrText("IAudioCaptureClient::GetBuffer", hr);
        stop_requested_.store(true);
        break;
      }

      if ((buffer_flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || data == nullptr) {
        // 静音包里 data 内容是未定义的，必须自己填 0，不能直接拿去用。
        const size_t samples = static_cast<size_t>(frames) * kChannels;
        if (silence.size() < samples) silence.resize(samples, 0.0f);
        callback_(silence.data(), static_cast<int>(frames));
      } else if (is_float) {
        callback_(reinterpret_cast<const float*>(data), static_cast<int>(frames));
      } else {
        const size_t samples = static_cast<size_t>(frames) * kChannels;
        if (scratch.size() < samples) scratch.resize(samples);
        const int16_t* src = reinterpret_cast<const int16_t*>(data);
        for (size_t i = 0; i < samples; ++i) {
          scratch[i] = static_cast<float>(src[i]) * (1.0f / 32768.0f);
        }
        callback_(scratch.data(), static_cast<int>(frames));
      }
      capture->ReleaseBuffer(frames);
    }
  }

  client->Stop();
  if (event != nullptr) CloseHandle(event);
  capture->Release();
}

uint32_t FindProcessIdByName(const std::string& exe_name) {
  if (exe_name.empty()) return 0;

  wchar_t target[MAX_PATH] = {0};
  if (MultiByteToWideChar(CP_ACP, 0, exe_name.c_str(), -1, target, MAX_PATH) <= 0) return 0;

  HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (snapshot == INVALID_HANDLE_VALUE) return 0;

  PROCESSENTRY32W entry{};
  entry.dwSize = sizeof(entry);
  uint32_t found = 0;
  if (Process32FirstW(snapshot, &entry)) {
    do {
      if (lstrcmpiW(entry.szExeFile, target) == 0) {
        found = entry.th32ProcessID;
        break;
      }
    } while (Process32NextW(snapshot, &entry));
  }
  CloseHandle(snapshot);
  return found;
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
