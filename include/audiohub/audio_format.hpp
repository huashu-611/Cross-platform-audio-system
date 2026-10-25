// 全局音频格式约定。
//
// 为什么全链路锁死 48 kHz / 双声道 / float32 内部表示：
//   1. 48 kHz 是 Opus 唯一支持的全采样率，也是 Android 音频栈的原生采样率；
//   2. 多级重采样会累积相位误差和不可控的延迟，所以只在两处做转换——
//      采集侧（44.1k 源 -> 48k）和时钟环（±0.2% 的极小比率调整）；
//   3. 内部用 float32 做混音累加，避免多源相加时的定点削顶。
#pragma once

#include <cstddef>
#include <cstdint>

namespace audiohub {

constexpr int kSampleRate = 48000;
constexpr int kChannels = 2;

using Sample = float;

// 一个"帧"= 同一采样时刻的所有声道样本。
constexpr size_t kFrameBytes = sizeof(Sample) * kChannels;

// 单包承载的帧数。5 ms @ 48 kHz = 240 帧。
//
// 为什么不是 10 ms：P0 用未压缩 int16 传输（见 docs），240 帧 * 2 声道 * 2 字节
// = 960 字节 payload，加上 16 字节包头后仍远小于以太网 MTU(1500) 和
// 常见的 WiFi MTU(1472)，不会被 IP 分片。分片会显著放大丢包的影响——
// 一个分片丢失等于整个包作废。
constexpr int kFramesPerPacket = 240;

// 单包 payload 上限（int16 交织）。
constexpr size_t kMaxPayloadBytes =
    static_cast<size_t>(kFramesPerPacket) * kChannels * sizeof(int16_t);

inline int FramesToMs(int frames) {
  return frames * 1000 / kSampleRate;
}

inline int MsToFrames(int ms) {
  return ms * kSampleRate / 1000;
}

}  // namespace audiohub
