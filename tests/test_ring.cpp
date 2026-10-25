#include <numeric>
#include <vector>

#include "audiohub/spsc_ring.hpp"
#include "test_framework.hpp"

TEST(Ring_CapacityRoundsUpToPowerOfTwo) {
  audiohub::SpscRing<float> ring(100);
  CHECK_EQ(ring.Capacity(), static_cast<size_t>(128));
}

TEST(Ring_WriteThenRead) {
  audiohub::SpscRing<float> ring(8);
  const float src[5] = {1, 2, 3, 4, 5};
  CHECK_EQ(ring.Write(src, 5), static_cast<size_t>(5));
  CHECK_EQ(ring.Available(), static_cast<size_t>(5));

  float dst[5] = {0};
  CHECK_EQ(ring.Read(dst, 5), static_cast<size_t>(5));
  for (int i = 0; i < 5; ++i) CHECK_NEAR(dst[i], src[i], 1e-9);
  CHECK_EQ(ring.Available(), static_cast<size_t>(0));
}

TEST(Ring_WrapAroundKeepsOrder) {
  audiohub::SpscRing<float> ring(8);
  float dst[8];
  float src[8];

  // 推 6 读 4，让写指针跨过边界
  for (int round = 0; round < 6; ++round) {
    for (int i = 0; i < 6; ++i) src[i] = static_cast<float>(round * 10 + i);
    CHECK_EQ(ring.Write(src, 6), static_cast<size_t>(6));
    CHECK_EQ(ring.Read(dst, 6), static_cast<size_t>(6));
    for (int i = 0; i < 6; ++i) CHECK_NEAR(dst[i], static_cast<float>(round * 10 + i), 1e-9);
  }
}

TEST(Ring_OverflowTruncatesInsteadOfOverwriting) {
  audiohub::SpscRing<float> ring(4);
  const float src[6] = {1, 2, 3, 4, 5, 6};
  // 只应该写进去 4 个，而不是覆盖旧数据
  CHECK_EQ(ring.Write(src, 6), static_cast<size_t>(4));
  CHECK_EQ(ring.Available(), static_cast<size_t>(4));

  float dst[4];
  CHECK_EQ(ring.Read(dst, 4), static_cast<size_t>(4));
  CHECK_NEAR(dst[0], 1.0f, 1e-9);
  CHECK_NEAR(dst[3], 4.0f, 1e-9);
}

TEST(Ring_ReadReturnsZeroWhenEmpty) {
  audiohub::SpscRing<float> ring(4);
  float dst[2] = {9, 9};
  CHECK_EQ(ring.Read(dst, 2), static_cast<size_t>(0));
  // 没读到就不能改动调用方缓冲，否则会污染上一块音频
  CHECK_NEAR(dst[0], 9.0f, 1e-9);
}
