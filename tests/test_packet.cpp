#include <cstring>
#include <vector>

#include "audiohub/packet.hpp"
#include "test_framework.hpp"

namespace {

audiohub::PacketHeader MakeHeader() {
  audiohub::PacketHeader h;
  h.stream_id = 0xBEEF;
  h.seq = 0xDEADBEEF;
  h.sample_pos = 0x12345678;
  h.capture_ts_ms = 0x00ABCDEF;
  h.flags = audiohub::kFlagDiscontinuity;
  return h;
}

}  // namespace

TEST(Packet_RoundTrip) {
  const auto in = MakeHeader();
  uint8_t buf[audiohub::kHeaderSize];
  CHECK_EQ(audiohub::EncodeHeader(in, buf), audiohub::kHeaderSize);

  audiohub::PacketHeader out;
  CHECK_TRUE(audiohub::DecodeHeader(buf, sizeof(buf), &out));
  CHECK_EQ(out.version, in.version);
  CHECK_EQ(out.flags, in.flags);
  CHECK_EQ(out.stream_id, in.stream_id);
  CHECK_EQ(out.seq, in.seq);
  CHECK_EQ(out.sample_pos, in.sample_pos);
  CHECK_EQ(out.capture_ts_ms, in.capture_ts_ms);
}

TEST(Packet_IsLittleEndianOnWire) {
  audiohub::PacketHeader h;
  h.stream_id = 0x0102;
  uint8_t buf[audiohub::kHeaderSize];
  audiohub::EncodeHeader(h, buf);
  // 线上字节序必须固定，否则大小端设备互通会静默错位
  CHECK_EQ(static_cast<int>(buf[2]), 0x02);
  CHECK_EQ(static_cast<int>(buf[3]), 0x01);
}

TEST(Packet_RejectsShortBuffer) {
  uint8_t buf[audiohub::kHeaderSize] = {0};
  audiohub::PacketHeader out;
  CHECK_FALSE(audiohub::DecodeHeader(buf, audiohub::kHeaderSize - 1, &out));
}

TEST(Packet_RejectsWrongVersion) {
  auto h = MakeHeader();
  uint8_t buf[audiohub::kHeaderSize];
  audiohub::EncodeHeader(h, buf);
  buf[0] = static_cast<uint8_t>((9 << 4) | (buf[0] & 0x0F));  // version = 9
  audiohub::PacketHeader out;
  CHECK_FALSE(audiohub::DecodeHeader(buf, sizeof(buf), &out));
}

TEST(Packet_SequenceDistanceHandlesWrap) {
  CHECK_EQ(audiohub::SequenceDistance(11, 10), 1);
  CHECK_EQ(audiohub::SequenceDistance(10, 11), -1);
  // 0xFFFFFFFF -> 0x00000000 应当算 +1，而不是 -4294967295
  CHECK_EQ(audiohub::SequenceDistance(0u, 0xFFFFFFFFu), 1);
}
