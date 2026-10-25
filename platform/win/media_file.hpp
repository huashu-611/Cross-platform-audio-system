// 用 Windows Media Foundation 把一个音频文件解码成 48 kHz / 双声道 PCM16。
//
// 为什么走 Media Foundation 而不是自己解 WAV：MP3 / FLAC / M4A 都是
// 系统自带解码器就能处理的格式，自己写解码器纯属浪费。MF 的唯一代价是
// COM 初始化 + 几百行胶水，但它换来"什么格式都能放"。
//
// 做法是**整文件解码进内存**，而不是流式解码。原因：
//   * 流式解码要维护跨 sample 的重采样状态，而 MF 的 sample 边界是任意的；
//   * 播放只要顺序索引，代码量差好几倍；
//   * 一首 5 分钟的歌约 57 MB，一次性载入完全可以接受（超过 20 分钟会拒绝）。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace audiohub {

class MediaFile {
 public:
  // 解码到 48 kHz / 双声道 / int16。失败时返回 false 并写 error。
  bool Load(const std::string& path, std::string* error);
  void Clear();

  bool loaded() const { return !pcm_.empty(); }
  const std::vector<int16_t>& pcm() const { return pcm_; }
  int frames() const { return static_cast<int>(pcm_.size() / 2); }
  int source_rate() const { return source_rate_; }
  int source_channels() const { return source_channels_; }
  double seconds() const { return frames() / 48000.0; }

 private:
  std::vector<int16_t> pcm_;  // 交织双声道
  int source_rate_ = 0;
  int source_channels_ = 0;
};

}  // namespace audiohub
