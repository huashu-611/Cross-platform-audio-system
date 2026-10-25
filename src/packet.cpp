#include "audiohub/packet.hpp"

namespace audiohub {
namespace {

inline void PutU16(uint8_t* p, uint16_t v) {
  p[0] = static_cast<uint8_t>(v & 0xFF);
  p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

inline void PutU32(uint8_t* p, uint32_t v) {
  p[0] = static_cast<uint8_t>(v & 0xFF);
  p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
  p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
  p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

inline uint16_t GetU16(const uint8_t* p) {
  return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

inline uint32_t GetU32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

}  // namespace

size_t EncodeHeader(const PacketHeader& header, uint8_t* out) {
  out[0] = static_cast<uint8_t>(((header.version & 0x0F) << 4) | (header.flags & 0x0F));
  out[1] = 0;  // reserved，留给后续扩展（加密标志、编码类型等）
  PutU16(out + 2, header.stream_id);
  PutU32(out + 4, header.seq);
  PutU32(out + 8, header.sample_pos);
  PutU32(out + 12, header.capture_ts_ms);
  return kHeaderSize;
}

bool DecodeHeader(const uint8_t* data, size_t size, PacketHeader* out) {
  if (data == nullptr || out == nullptr || size < kHeaderSize) return false;

  const uint8_t version = static_cast<uint8_t>(data[0] >> 4);
  // 只接受同版本。未来做兼容时应该按 version 分支解析，而不是宽松放行——
  // 宽松解析在字段语义变化时会静默地把垃圾数据喂给音频线程。
  if (version != kProtocolVersion) return false;

  out->version = version;
  out->flags = static_cast<uint8_t>(data[0] & 0x0F);
  out->stream_id = GetU16(data + 2);
  out->seq = GetU32(data + 4);
  out->sample_pos = GetU32(data + 8);
  out->capture_ts_ms = GetU32(data + 12);
  return true;
}

}  // namespace audiohub
