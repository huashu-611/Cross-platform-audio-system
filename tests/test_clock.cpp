#include "audiohub/clock_sync.hpp"
#include "test_framework.hpp"

TEST(ClockSync_SymmetricPathRecoversOffset) {
  audiohub::ClockSync cs;
  // 场景：Hub 时钟比源端快 50 ms，往返各 10 ms
  const uint32_t t1 = 1000;
  const uint32_t t2 = 1060;  // t1 + 10 + 50
  const uint32_t t3 = 1060;
  const uint32_t t4 = 1020;  // t3 - 50 + 10

  for (int i = 0; i < 5; ++i) cs.AddSample(t1, t2, t3, t4);

  double offset = 0;
  CHECK_TRUE(cs.OffsetMs(&offset));
  CHECK_NEAR(offset, 50.0, 0.001);
  CHECK_NEAR(cs.RttMs(), 20.0, 0.001);
  CHECK_TRUE(cs.Ready());
}

TEST(ClockSync_NegativeOffsetIsValid) {
  audiohub::ClockSync cs;
  // Hub 时钟比源端慢 50 ms。此时 a、b 都会是负数——
  // 早期版本按符号过滤样本，会把这个完全正常的场景全部丢掉。
  const uint32_t t1 = 1000;
  const uint32_t t2 = 960;
  const uint32_t t3 = 960;
  const uint32_t t4 = 1020;

  for (int i = 0; i < 5; ++i) cs.AddSample(t1, t2, t3, t4);

  double offset = 0;
  CHECK_TRUE(cs.OffsetMs(&offset));
  CHECK_NEAR(offset, -50.0, 0.001);
  CHECK_NEAR(cs.RttMs(), 20.0, 0.001);
}

TEST(ClockSync_TimestampWraparound) {
  audiohub::ClockSync cs;
  const uint32_t t1 = 0xFFFFFFF0u;
  const uint32_t t2 = 0xFFFFFFFAu;
  const uint32_t t3 = 0xFFFFFFFAu;
  const uint32_t t4 = 0x00000004u;  // 已回绕

  for (int i = 0; i < 5; ++i) cs.AddSample(t1, t2, t3, t4);

  double offset = 0;
  CHECK_TRUE(cs.OffsetMs(&offset));
  CHECK_NEAR(offset, 0.0, 0.001);
  CHECK_NEAR(cs.RttMs(), 20.0, 0.001);
}

TEST(ClockSync_MedianRejectsQueuedOutlier) {
  audiohub::ClockSync cs;
  // 9 个好样本：offset=50，rtt=20
  for (int i = 0; i < 9; ++i) cs.AddSample(1000, 1060, 1060, 1020);

  // 1 个被排队污染的样本：rtt=400，算出来的 offset 完全跑偏。
  // 均值会被它拖走，中位数 + RTT 过滤不会。
  cs.AddSample(1000, 1400, 1400, 1100);

  double offset = 0;
  CHECK_TRUE(cs.OffsetMs(&offset));
  CHECK_NEAR(offset, 50.0, 0.5);
}

TEST(ClockSync_NotReadyEarly) {
  audiohub::ClockSync cs;
  cs.AddSample(1000, 1060, 1060, 1020);
  CHECK_FALSE(cs.Ready());
  for (int i = 0; i < 4; ++i) cs.AddSample(1000, 1060, 1060, 1020);
  CHECK_TRUE(cs.Ready());
}

TEST(ClockSync_WindowIsBounded) {
  audiohub::ClockSync cs(10);
  for (int i = 0; i < 100; ++i) cs.AddSample(1000, 1060, 1060, 1020);
  CHECK_EQ(cs.SampleCount(), static_cast<size_t>(10));
}
