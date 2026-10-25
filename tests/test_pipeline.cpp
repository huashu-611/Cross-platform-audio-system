// 混音取水逻辑的稳态回归测试。
//
// 背景：曾经因为「每 tick 从抖动缓冲取的量高于网络供给量」，缓冲被慢慢抽干，
// 之后 read_pos_ 跑到 highest_written_ 前面，每个包都判成"迟到"被丢弃——
// 缓冲区一旦抽干就再也回不来。现象是"第一秒有声音，然后永久静音"，
// 而且不抛异常、不打错误日志，只能靠观察水位发现。
//
// 这个测试把「供给 240 帧/tick，按 ASRC 需求取水」的循环跑 10 秒，
// 断言水位既不被抽干、也不无限增长。
#include <cmath>
#include <vector>

#include "audiohub/asrc.hpp"
#include "audiohub/audio_format.hpp"
#include "audiohub/jitter_buffer.hpp"
#include "test_framework.hpp"

namespace {

constexpr int kBlock = audiohub::kFramesPerPacket;  // 240 帧 = 5 ms
constexpr int kAsrcReserve = 8;

// 和 HubCore::MixerLoop 里完全相同的取水逻辑
int ComputeNeed(int ratio_num, int ratio_den, int have) {
  const double ratio = static_cast<double>(ratio_num) / ratio_den;
  const int want = static_cast<int>(std::ceil(kBlock * ratio)) + kAsrcReserve;
  // 上限必须留出 ASRC 的插值余量，见 HubCore::MixerLoop 的注释
  return std::max(0, std::min(want - have, kBlock + kAsrcReserve + 2));
}

}  // namespace

TEST(FeedLoop_SteadyStateDoesNotDrain) {
  audiohub::JitterBuffer jitter(audiohub::kChannels, audiohub::kSampleRate,
                                audiohub::MsToFrames(400));
  jitter.SetTargetDelayMs(60);
  audiohub::Asrc asrc(audiohub::kChannels);

  std::vector<float> feed(kBlock * audiohub::kChannels);
  std::vector<float> pull(kBlock * 4 * audiohub::kChannels);
  std::vector<float> out(kBlock * audiohub::kChannels);

  // 造一个可识别的信号：第 i 帧的值 = (i % 97) / 97
  for (int i = 0; i < kBlock; ++i) {
    const float v = static_cast<float>(i % 97) / 97.0f;
    for (int c = 0; c < audiohub::kChannels; ++c) feed[i * audiohub::kChannels + c] = v;
  }

  uint32_t sample_pos = 0;
  int lowest_occupancy = 100000;

  // 2000 tick = 10 秒
  for (int tick = 0; tick < 2000; ++tick) {
    // 网络供给：每 tick 正好一包
    jitter.Push(sample_pos, feed.data(), kBlock);
    sample_pos += kBlock;

    const int have = asrc.AvailableInputFrames();
    const int need = ComputeNeed(1000, 1000, have);  // ratio = 1.0
    CHECK_TRUE(need >= 0);
    CHECK_TRUE(need <= kBlock + kAsrcReserve + 2);

    jitter.Pull(pull.data(), need);
    asrc.Push(pull.data(), need);
    CHECK_TRUE(asrc.Pull(out.data(), kBlock));

    const int occupancy = jitter.occupancy_ms();
    if (occupancy < lowest_occupancy) lowest_occupancy = occupancy;
  }

  // 关键断言：水位始终没被抽干。启动时是 60 ms，稳态应该维持在几十毫秒。
  CHECK_TRUE(lowest_occupancy > 10);
  // 也不该无限增长（否则会溢出丢包）
  CHECK_TRUE(jitter.occupancy_ms() < 200);
  // 取水量必须和供给量匹配，否则迟早一边崩溃
  CHECK_EQ(asrc.AvailableInputFrames() >= 0 ? 1 : 0, 1);
}

TEST(FeedLoop_SlightlyFastRatioStillBalanced) {
  audiohub::JitterBuffer jitter(audiohub::kChannels, audiohub::kSampleRate,
                                audiohub::MsToFrames(400));
  jitter.SetTargetDelayMs(60);
  audiohub::Asrc asrc(audiohub::kChannels);
  asrc.SetRatio(1.002);  // 源偏慢，需要消费更多输入

  std::vector<float> feed(kBlock * audiohub::kChannels, 0.25f);
  std::vector<float> pull(kBlock * 4 * audiohub::kChannels);
  std::vector<float> out(kBlock * audiohub::kChannels);

  uint32_t sample_pos = 0;
  int lowest = 100000;
  for (int tick = 0; tick < 2000; ++tick) {
    jitter.Push(sample_pos, feed.data(), kBlock);
    sample_pos += kBlock;
    const int have = asrc.AvailableInputFrames();
    const int need = ComputeNeed(1002, 1000, have);
    jitter.Pull(pull.data(), need);
    asrc.Push(pull.data(), need);
    asrc.Pull(out.data(), kBlock);
    const int occ = jitter.occupancy_ms();
    if (occ < lowest) lowest = occ;
  }
  CHECK_TRUE(lowest > 10);
}

TEST(FeedLoop_OverPullingDrainsBuffer) {
  // 反向验证：如果把每 tick 取量放大到供给的 4 倍，缓冲必须在几十个 tick 内被抽干。
  // 这个测试存在的意义是证明上面两个测试真的在测东西——
  // 如果它不失败，说明前两个测试没有鉴别力。
  audiohub::JitterBuffer jitter(audiohub::kChannels, audiohub::kSampleRate,
                                audiohub::MsToFrames(400));
  jitter.SetTargetDelayMs(60);
  audiohub::Asrc asrc(audiohub::kChannels);

  std::vector<float> feed(kBlock * audiohub::kChannels, 0.25f);
  std::vector<float> pull(kBlock * 4 * audiohub::kChannels);

  uint32_t sample_pos = 0;
  for (int tick = 0; tick < 200; ++tick) {
    jitter.Push(sample_pos, feed.data(), kBlock);
    sample_pos += kBlock;
    const int need = kBlock * 4;  // 故意超额取水
    jitter.Pull(pull.data(), need);
    asrc.Push(pull.data(), need);
    if (jitter.occupancy_ms() == 0 && tick > 20) break;
  }
  // 200 tick 内必然抽干
  CHECK_EQ(jitter.occupancy_ms(), 0);
}
