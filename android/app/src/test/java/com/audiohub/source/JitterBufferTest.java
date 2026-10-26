package com.audiohub.source;

/**
 * 抖动缓冲离线验证：模拟"发 5ms 包 / 收 5ms 块"，逐样本比对输出与输入。
 *
 * 需要覆盖两种起点：
 *   1) 大起点（音源已跑了一阵）
 *   2) **起点为 0** —— 真实场景：PC 音源的 sample_pos 从 0 开始，
 *      而缓冲会把 readPos 初始化成 pos - 60ms，于是 readPos 为负。
 *      Java 的 % 对负数返回负值，直接当数组下标就会
 *      ArrayIndexOutOfBoundsException: length=12000; index=-2880
 *      （这个崩溃真实发生过，必须有用例守住）。
 */
public class JitterBufferTest {

    public static void main(String[] args) {
        boolean allOk = true;
        allOk &= runCase("大起点", 123456789L);
        allOk &= runCase("从 0 开始（真实音源）", 0L);
        allOk &= runCase("起点小于预填长度", 100L);
        System.out.println(allOk ? "✔ 全部通过" : "✗ 存在失败用例");
        if (!allOk) System.exit(1);
    }

    private static boolean runCase(String label, long startPos) {
        final int packetFrames = 240;                 // 5 ms
        final int ticks = 48000 / packetFrames;       // 1 秒
        final int prefill = 60 * 48;                  // 与 PREFILL_MS 一致

        final JitterBuffer jb = new JitterBuffer(2, 48000 / 4);
        long written = 0;
        long read = 0;
        int mismatches = 0;
        int checked = 0;
        int expectedSilence = 0;

        final short[] pkt = new short[packetFrames * 2];
        final short[] out = new short[packetFrames * 2];

        try {
            for (int tick = 0; tick < ticks; ++tick) {
                final long pos = startPos + written;
                for (int i = 0; i < packetFrames; ++i) {
                    final short v = (short) ((pos + i) & 0x7FFF);
                    pkt[i * 2] = v;
                    pkt[i * 2 + 1] = v;
                }
                jb.push(pos, pkt, packetFrames);
                written += packetFrames;

                jb.pull(out, packetFrames);

                for (int i = 0; i < packetFrames; ++i) {
                    final long outAbs = startPos - prefill + read + i;
                    if (outAbs < startPos) {
                        if (out[i * 2] != 0) mismatches++;
                        expectedSilence++;
                    } else {
                        final short want = (short) (outAbs & 0x7FFF);
                        if (out[i * 2] != want) mismatches++;
                    }
                    checked++;
                }
                read += packetFrames;
            }
        } catch (RuntimeException e) {
            System.out.printf("[%s] ✗ 抛异常: %s%n", label, e);
            return false;
        }

        System.out.printf("[%s] 校验 %d 样本（预填静音 %d），不一致 %d，"
                        + "欠载 %d / 丢 %d / 补 %d，水位 %.0f ms%n",
                label, checked, expectedSilence, mismatches,
                jb.underrunFrames(), jb.droppedFrames(), jb.duplicatedFrames(),
                jb.occupancyFrames() / 48.0);
        return mismatches == 0;
    }
}
