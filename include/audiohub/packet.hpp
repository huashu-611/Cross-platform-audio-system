// 线格式（wire format）定义。
//
// 设计取舍：
//   * 固定 16 字节包头，字段全部小端，显式按字节编解码——
//     不用 struct 直接 memcpy，避免不同编译器/架构的 padding 与字节序差异。
//   * 保留 sample_pos 而不是只留 seq：丢包后接收端需要知道"缺了多少采样"，
//     靠 seq 反推在 seq 回绕或跨包重排时会出错。
//   * capture_ts_ms 用 uint32 毫秒（可表示 49.7 天）而不是 uint32 微秒（71 分钟就回绕）。
//     时钟环只需要 ±1 ms 精度，毫秒足够，且回绕周期长到可以忽略。
#pragma once

#include <cstddef>
#include <cstdint>

namespace audiohub {

constexpr uint8_t kProtocolVersion = 1;
constexpr size_t kHeaderSize = 16;

enum PacketFlags : uint8_t {
  kFlagNone = 0,
  // 本包与上一包之间发生过丢包。接收端可以据此决定是否插入 PLC。
  kFlagDiscontinuity = 1u << 0,
  // 流的起点或重同步点，接收端应重置抖动缓冲的水位。
  kFlagMarker = 1u << 1,
};

struct PacketHeader {
  uint8_t version = kProtocolVersion;
  uint8_t flags = kFlagNone;
  uint16_t stream_id = 0;
  uint32_t seq = 0;
  // 本帧首采样在该流内的绝对序号（48 kHz）。回绕周期 24.8 小时。
  uint32_t sample_pos = 0;
  // 源端单调时钟，流起点为 0，单位毫秒。
  uint32_t capture_ts_ms = 0;
};

// 编解码。EncodeHeader 返回实际写入字节数（恒为 kHeaderSize）。
size_t EncodeHeader(const PacketHeader& header, uint8_t* out);

// 返回 false 表示数据不足或协议版本不匹配。
bool DecodeHeader(const uint8_t* data, size_t size, PacketHeader* out);

// 两个 uint32 序号之间的距离，处理回绕。返回值可能为负。
inline int32_t SequenceDistance(uint32_t a, uint32_t b) {
  return static_cast<int32_t>(a - b);
}

}  // namespace audiohub
