#include <cmath>
#include <vector>

#include "audiohub/audio_format.hpp"
#include "audiohub/jitter_buffer.hpp"
#include "test_framework.hpp"

namespace {

constexpr int kCh = audiohub::kChannels;
constexpr int kBlock = 240;          // 5 ms
constexpr int kCapacity = 48000;     // 1 秒，测试用不着 400 ms

// 每个采样值 = 它的绝对位置，便于断言"读出来的确实是那个位置的数据"。
void FillRamp(std::vector<float>* buffer, uint32_t start_sample, int frames = kBlock) {
  buffer->resize(static_cast<size_t>(frames) * kCh);
  for (int i = 0; i < frames; ++i) {
    const float v = static_cast<float>(start_sample + i);
    for (int c = 0; c < kCh; ++c) (*buffer)[static_cast<size_t>(i) * kCh + c] = v;
  }
}

}  // namespace

TEST(JitterBuffer_InitialDelayIsSilence) {
  audiohub::JitterBuffer jb(kCh, audiohub::kSampleRate, kCapacity);
  jb.SetTargetDelayMs(20);  // 960 帧

  std::vector<float> pkt;
  FillRamp(&pkt, 0);
  CHECK_TRUE(jb.Push(0, pkt.data(), kBlock));

  std::vector<float> out(static_cast<size_t>(kBlock) * kCh);

  // 前 4 个块（960 帧）应当全是静音——这就是"抖动缓冲的余量"被显式施加的地方
  for (int b = 0; b < 4; ++b) {
    jb.Pull(out.data(), kBlock);
    for (float v : out) CHECK_NEAR(v, 0.0, 1e-6);
  }

  // 第 5 个块才轮到第一包
  jb.Pull(out.data(), kBlock);
  CHECK_NEAR(out[0], 0.0, 1e-6);
  CHECK_NEAR(out[kCh], 1.0, 1e-6);
  CHECK_NEAR(out[static_cast<size_t>(kBlock - 1) * kCh], static_cast<float>(kBlock - 1), 1e-6);
}

TEST(JitterBuffer_SequentialPlayoutKeepsOrder) {
  audiohub::JitterBuffer jb(kCh, audiohub::kSampleRate, kCapacity);
  jb.SetTargetDelayMs(20);

  std::vector<float> pkt;
  for (int i = 0; i < 10; ++i) {
    FillRamp(&pkt, static_cast<uint32_t>(i * kBlock));
    CHECK_TRUE(jb.Push(static_cast<uint32_t>(i * kBlock), pkt.data(), kBlock));
  }

  std::vector<float> out(static_cast<size_t>(kBlock) * kCh);
  for (int b = 0; b < 4; ++b) jb.Pull(out.data(), kBlock);  // 跳过初始静音

  for (int i = 0; i < 10; ++i) {
    jb.Pull(out.data(), kBlock);
    CHECK_NEAR(out[0], static_cast<float>(i * kBlock), 1e-6);
    CHECK_NEAR(out[static_cast<size_t>(kBlock - 1) * kCh],
               static_cast<float>(i * kBlock + kBlock - 1), 1e-6);
  }
}

TEST(JitterBuffer_LatePacketDropped) {
  audiohub::JitterBuffer jb(kCh, audiohub::kSampleRate, kCapacity);
  jb.SetTargetDelayMs(20);

  std::vector<float> pkt;
  FillRamp(&pkt, 0);
  jb.Push(0, pkt.data(), kBlock);

  std::vector<float> out(static_cast<size_t>(kBlock) * kCh);
  for (int b = 0; b < 5; ++b) jb.Pull(out.data(), kBlock);  // read_pos 推进到 240

  jb.ResetStats();
  // 整包都落在读指针之前：必须丢弃，不能"补播"（补播会让音频倒退）
  CHECK_FALSE(jb.Push(0, pkt.data(), kBlock));
  CHECK_EQ(jb.stats().packets_late, static_cast<uint64_t>(1));
}

TEST(JitterBuffer_PartialLateIsTrimmedNotDropped) {
  audiohub::JitterBuffer jb(kCh, audiohub::kSampleRate, kCapacity);
  jb.SetTargetDelayMs(20);

  std::vector<float> pkt;
  FillRamp(&pkt, 0);
  jb.Push(0, pkt.data(), kBlock);

  std::vector<float> out(static_cast<size_t>(kBlock) * kCh);
  for (int b = 0; b < 4; ++b) jb.Pull(out.data(), kBlock);  // read_pos = 0
  jb.Pull(out.data(), 120);                                  // read_pos = 120

  jb.ResetStats();
  // 前半段（0..120）已经播过了，后半段（120..240）还有价值 -> 裁掉前半段而不是丢整包
  CHECK_TRUE(jb.Push(0, pkt.data(), kBlock));
  CHECK_EQ(jb.stats().frames_trimmed, static_cast<uint64_t>(120));
  CHECK_EQ(jb.stats().packets_late, static_cast<uint64_t>(0));
}

TEST(JitterBuffer_GapCountsUnderrun) {
  audiohub::JitterBuffer jb(kCh, audiohub::kSampleRate, kCapacity);
  jb.SetTargetDelayMs(20);

  std::vector<float> pkt;
  FillRamp(&pkt, 0);
  jb.Push(0, pkt.data(), kBlock);
  FillRamp(&pkt, 2 * kBlock);  // 故意跳过 240..480
  jb.Push(2 * kBlock, pkt.data(), kBlock);

  std::vector<float> out(static_cast<size_t>(kBlock) * kCh);
  for (int b = 0; b < 4; ++b) jb.Pull(out.data(), kBlock);
  jb.ResetStats();

  jb.Pull(out.data(), kBlock);  // 0..240 正常
  CHECK_EQ(jb.stats().frames_underrun, static_cast<uint64_t>(0));

  jb.Pull(out.data(), kBlock);  // 240..480 缺失
  CHECK_EQ(jb.stats().frames_underrun, static_cast<uint64_t>(kBlock));

  jb.Pull(out.data(), kBlock);  // 480..720 恢复
  CHECK_EQ(jb.stats().frames_underrun, static_cast<uint64_t>(kBlock));
  CHECK_NEAR(out[0], static_cast<float>(2 * kBlock), 1e-6);
}

TEST(JitterBuffer_OverflowRejected) {
  audiohub::JitterBuffer jb(kCh, audiohub::kSampleRate, kCapacity);
  jb.SetTargetDelayMs(20);

  std::vector<float> pkt;
  FillRamp(&pkt, 0);
  jb.Push(0, pkt.data(), kBlock);
  jb.ResetStats();

  // 时间戳跳到容量之外：整包丢弃，宁可不播也不能写坏环形缓冲
  CHECK_FALSE(jb.Push(static_cast<uint32_t>(kCapacity * 4), pkt.data(), kBlock));
  CHECK_EQ(jb.stats().packets_overflow, static_cast<uint64_t>(1));
}

TEST(JitterBuffer_InsertSilenceRaisesLatency) {
  audiohub::JitterBuffer jb(kCh, audiohub::kSampleRate, kCapacity);
  jb.SetTargetDelayMs(20);  // 960 帧初始静音

  std::vector<float> pkt;
  FillRamp(&pkt, 0);
  jb.Push(0, pkt.data(), kBlock);

  // 再插 240 帧（5 ms）静音，等于把延迟从 20 ms 拉到 25 ms。
  jb.InsertSilence(kBlock);

  // 数一数要多少块静音之后才听到第一包。
  // 没有 InsertSilence 时是 4 块（960 帧），插了之后应该是 5 块（1200 帧）。
  std::vector<float> out(static_cast<size_t>(kBlock) * kCh);
  int silent_blocks = 0;
  for (;;) {
    jb.Pull(out.data(), kBlock);
    bool has_audio = false;
    for (float v : out) {
      if (std::fabs(v) > 1e-9f) {
        has_audio = true;
        break;
      }
    }
    if (has_audio) break;
    if (++silent_blocks > 20) break;  // 防死循环
  }
  CHECK_EQ(silent_blocks, 5);
}
