#include "media_file.hpp"

#ifdef _WIN32

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "audiohub/audio_format.hpp"

namespace audiohub {
namespace {

// MinGW 的 mfapi.h 里这几个属性的定义不总是可用（依赖 INITGUID），
// 这里按 SDK 里的值直接写死。注意 IMFAttributes::GetUINT32 收的是 GUID，
// 不是 PROPERTYKEY——虽然头文件里它们成对出现。
const GUID kMtAudioSamplesPerSecond = {
    0x5FAEEAE7, 0x0290, 0x4C31, {0x9E, 0x8A, 0xC5, 0x34, 0xF6, 0x8D, 0x9D, 0xBA}};
const GUID kMtAudioNumChannels = {
    0x37E48BF5, 0x645E, 0x4C5B, {0x89, 0xDE, 0xAD, 0xA9, 0xE2, 0x9B, 0x69, 0x6A}};

std::wstring WidenAnsi(const std::string& s) {
  if (s.empty()) return {};
  const int n = MultiByteToWideChar(CP_ACP, 0, s.c_str(), -1, nullptr, 0);
  if (n <= 0) return {};
  std::wstring w(static_cast<size_t>(n - 1), L'\0');
  MultiByteToWideChar(CP_ACP, 0, s.c_str(), -1, w.data(), n);
  return w;
}

// MF 的 RAII：即使中途出错也要保证 Shutdown / CoUninitialize 成对。
struct MfSession {
  bool com = false;
  bool mf = false;
  MfSession() {
    com = SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED));
    mf = SUCCEEDED(MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET));
  }
  ~MfSession() {
    if (mf) MFShutdown();
    if (com) CoUninitialize();
  }
};

}  // namespace

bool MediaFile::Load(const std::string& path, std::string* error) {
  Clear();

  MfSession session;
  if (!session.mf) {
    *error = "Media Foundation 初始化失败";
    return false;
  }

  const std::wstring wide = WidenAnsi(path);
  IMFSourceReader* reader = nullptr;
  HRESULT hr = MFCreateSourceReaderFromURL(wide.c_str(), nullptr, &reader);
  if (FAILED(hr) || reader == nullptr) {
    char buf[160];
    std::snprintf(buf, sizeof(buf), "打不开文件（hr=0x%08lX）", static_cast<unsigned long>(hr));
    *error = buf;
    return false;
  }

  // 让 MF 自己解成 16 bit PCM；采样率和声道数保持源的样子，
  // 转换到 48k/双声道由我们做（这样重采样逻辑只在自己手里有一份）。
  IMFMediaType* pcm_type = nullptr;
  MFCreateMediaType(&pcm_type);
  pcm_type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
  pcm_type->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
  hr = reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, pcm_type);
  pcm_type->Release();
  if (FAILED(hr)) {
    *error = "这个格式的解码器不可用（系统没装对应 codec）";
    reader->Release();
    return false;
  }

  IMFMediaType* current = nullptr;
  UINT32 rate = 0;
  UINT32 channels = 0;
  if (SUCCEEDED(reader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, &current)) &&
      current != nullptr) {
    current->GetUINT32(kMtAudioSamplesPerSecond, &rate);
    current->GetUINT32(kMtAudioNumChannels, &channels);
    current->Release();
  }
  if (rate == 0 || channels == 0 || channels > 8) {
    *error = "无法确定音频格式";
    reader->Release();
    return false;
  }
  source_rate_ = static_cast<int>(rate);
  source_channels_ = static_cast<int>(channels);

  // 先收集原始 PCM（源采样率、源声道数）
  std::vector<int16_t> raw;
  const size_t kMaxSourceSamples = static_cast<size_t>(source_rate_) * 2 * 60 * 20;  // 20 分钟上限

  for (;;) {
    DWORD flags = 0;
    LONGLONG timestamp = 0;
    IMFSample* sample = nullptr;
    hr = reader->ReadSample(MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, nullptr, &flags, &timestamp,
                            &sample);
    if (FAILED(hr)) break;
    if ((flags & MF_SOURCE_READERF_ENDOFSTREAM) != 0) {
      if (sample != nullptr) sample->Release();
      break;
    }
    if (sample == nullptr) continue;

    IMFMediaBuffer* buffer = nullptr;
    if (SUCCEEDED(sample->ConvertToContiguousBuffer(&buffer)) && buffer != nullptr) {
      BYTE* data = nullptr;
      DWORD length = 0;
      if (SUCCEEDED(buffer->Lock(&data, nullptr, &length)) && data != nullptr) {
        const size_t count = length / sizeof(int16_t);
        const size_t old = raw.size();
        raw.resize(old + count);
        std::memcpy(raw.data() + old, data, count * sizeof(int16_t));
        buffer->Unlock();
      }
      buffer->Release();
    }
    sample->Release();

    if (raw.size() > kMaxSourceSamples) {
      *error = "文件超过 20 分钟，拒绝整段载入内存（测试用途，换短一点的素材）";
      reader->Release();
      return false;
    }
  }
  reader->Release();

  if (raw.empty()) {
    *error = "解码后没有音频数据（可能是纯视频文件或损坏）";
    return false;
  }

  // 源 -> 48k 双声道。线性插值足够：这是一次性离线转换，
  // 而且测试素材的分辨力远高于插值误差。
  const size_t src_frames = raw.size() / static_cast<size_t>(source_channels_);
  const double step = static_cast<double>(source_rate_) / kSampleRate;
  const size_t out_frames = static_cast<size_t>(static_cast<double>(src_frames) / step);

  pcm_.resize(out_frames * 2);
  double pos = 0.0;
  const int src_ch = source_channels_;
  for (size_t i = 0; i < out_frames; ++i) {
    const size_t idx = static_cast<size_t>(pos);
    const double frac = pos - static_cast<double>(idx);
    for (int c = 0; c < 2; ++c) {
      const int ch = c < src_ch ? c : src_ch - 1;
      const int16_t a = raw[idx * src_ch + ch];
      const int16_t b = (idx + 1 < src_frames) ? raw[(idx + 1) * src_ch + ch] : a;
      const double v = a + (b - a) * frac;
      pcm_[i * 2 + c] = static_cast<int16_t>(std::lrint(v));
    }
    pos += step;
  }

  return true;
}

void MediaFile::Clear() {
  pcm_.clear();
  pcm_.shrink_to_fit();
  source_rate_ = 0;
  source_channels_ = 0;
}

}  // namespace audiohub

#endif  // _WIN32
