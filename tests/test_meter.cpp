// 渲染探针与电平表的自证测试。
//
// 为什么这些用例值得存在：判断"汇聚端有没有真的出声"曾经靠轮询 /api/state
// 的 busLevel，但那个值是 Meter::Take()（取走即清零），UI 每 200ms 取一次，
// 诊断脚本再读一次就必然读到 -120，于是"正在出声"被误判成"静音"。
// 一个测量手段必须先证明自己能把三种状态分清，否则它只是在自证清白：
//   ① 从来没有数据      -> has_data=false（"没收到"，不是静音）
//   ② 有数据但全是零    -> has_data=true, nonzero=0（真的静音）
//   ③ 有数据且非零      -> has_data=true, nonzero>0（真的在出声）
#include <vector>

#include "audiohub/meter.hpp"
#include "test_framework.hpp"

namespace {

std::vector<float> MakeTone(size_t frames, int channels, float amplitude) {
  std::vector<float> v(frames * static_cast<size_t>(channels));
  for (size_t f = 0; f < frames; ++f) {
    // 440Hz @48k：每帧取值不同，避免"全同值"掩盖逐帧统计的错法
    const double t = static_cast<double>(f) / 48000.0;
    const float s = static_cast<float>(amplitude * std::sin(2.0 * 3.14159265358979 * 440.0 * t));
    for (int c = 0; c < channels; ++c) v[f * channels + static_cast<size_t>(c)] = s;
  }
  return v;
}

}  // namespace

TEST(Meter_TakeReportsFalseBeforeAnyData) {
  audiohub::Meter m;
  double db = -120.0;
  // 没数据时必须返回 false：调用方据此区分"没收到"和"收到静音"
  CHECK_FALSE(m.Take(&db));
  CHECK_TRUE(db == -120.0);
}

TEST(Meter_PeekDoesNotConsume) {
  audiohub::Meter m;
  const auto tone = MakeTone(480, 2, 0.5f);
  m.Add(tone.data(), tone.size());

  // 连续读三次必须完全一样：这就是"幂等观测器"的定义，
  // 也正是它能免疫 UI 200ms 轮询的原因。
  const auto a = m.Peek();
  const auto b = m.Peek();
  const auto c = m.Peek();
  CHECK_TRUE(a.has_data);
  CHECK_EQ(a.samples, b.samples);
  CHECK_EQ(b.samples, c.samples);
  CHECK_NEAR(a.dbfs, b.dbfs, 1e-12);
  CHECK_NEAR(b.dbfs, c.dbfs, 1e-12);
  CHECK_EQ(a.nonzero, c.nonzero);

  // Peek 之后 Take 仍然拿得到完整数据（Peek 没偷走任何东西）
  double db = -120.0;
  CHECK_TRUE(m.Take(&db));
  CHECK_TRUE(db > -20.0);  // 0.5 幅度正弦的 RMS 约 -9dBFS
}

TEST(Meter_TakeResetsPeekToo) {
  audiohub::Meter m;
  const auto tone = MakeTone(480, 2, 0.5f);
  m.Add(tone.data(), tone.size());
  double db = -120.0;
  CHECK_TRUE(m.Take(&db));
  // Take 清空后，Peek 必须同步看到"没数据"，否则两个读者会各说各话
  const auto s = m.Peek();
  CHECK_FALSE(s.has_data);
  CHECK_EQ(s.samples, static_cast<uint64_t>(0));
  CHECK_EQ(s.nonzero, static_cast<uint64_t>(0));
}

TEST(Meter_SilenceIsDistinguishableFromNoData) {
  audiohub::Meter m;
  std::vector<float> zeros(960, 0.0f);
  m.Add(zeros.data(), zeros.size());

  const auto s = m.Peek();
  CHECK_TRUE(s.has_data);                       // 有数据……
  CHECK_EQ(s.nonzero, static_cast<uint64_t>(0)); // ……但一个非零样本都没有
  CHECK_TRUE(s.dbfs < -100.0);
  CHECK_TRUE(s.peak_dbfs < -100.0);
}

TEST(Meter_AudibleSignalSetsNonzeroAndPeak) {
  audiohub::Meter m;
  const auto tone = MakeTone(960, 2, 0.5f);
  m.Add(tone.data(), tone.size());

  // 正弦在 t=0 处恰好等于 0.0，所以"零样本"不能假设是 0 个；
  // 直接数一遍，让断言表达的是意图（nonzero == 所有非零样本）而不是巧合。
  size_t expected_nonzero = 0;
  for (float v : tone) {
    if (v != 0.0f) ++expected_nonzero;
  }
  const auto s = m.Peek();
  CHECK_TRUE(s.has_data);
  CHECK_EQ(s.nonzero, static_cast<uint64_t>(expected_nonzero));
  CHECK_EQ(s.samples, static_cast<uint64_t>(tone.size()));
  // 0.5 幅度正弦峰值应接近 -6dBFS
  CHECK_NEAR(s.peak_dbfs, -6.02, 0.2);
  // 平均功率电平约 -9dBFS（0.5/sqrt(2)）
  CHECK_NEAR(s.dbfs, -9.03, 0.3);
}

TEST(Meter_ClipsCountedAboveFullScale) {
  audiohub::Meter m;
  std::vector<float> hot(200, 1.0f);
  m.Add(hot.data(), hot.size());
  CHECK_EQ(m.Peek().clips, static_cast<uint64_t>(200));
}

TEST(ScanFrames_CountsOnlyNonZeroFrames) {
  // 4 帧立体声：前 2 帧静音，第 3 帧只有左声道有值，第 4 帧两声道都有值
  const float data[8] = {0.0f, 0.0f, 0.0f, 0.0f, 0.25f, 0.0f, -0.5f, 0.5f};
  const auto s = audiohub::ScanFrames(data, 8, 2);
  // "audible" 按帧计：第 3、4 帧算有声音（单声道有值也算），共 2 帧
  CHECK_EQ(s.audible, static_cast<uint64_t>(2));
  CHECK_NEAR(s.peak, 0.5, 1e-6);
}

TEST(ScanFrames_AllZeroGivesZeroAudible) {
  std::vector<float> zeros(480, 0.0f);
  const auto s = audiohub::ScanFrames(zeros.data(), zeros.size(), 2);
  CHECK_EQ(s.audible, static_cast<uint64_t>(0));
  CHECK_NEAR(s.peak, 0.0, 1e-12);
}
