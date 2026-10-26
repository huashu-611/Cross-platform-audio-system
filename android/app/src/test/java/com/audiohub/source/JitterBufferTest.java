package com.audiohub.source;

/** 抖动缓冲离线验证：第一个包之前有 60ms 预填（输出静音），比对时跳过这段。 */
public class JitterBufferTest {

    public static void main(String[] args) {
        final long startPos = 123456789L;
        final int packetFrames = 240;                 // 5 ms
        final int ticks = 48000 / packetFrames;       // 1 秒
        final int prefill = 60 * 48;                  // 与 JitterBuffer.PREFILL_MS 一致

        final JitterBuffer jb = new JitterBuffer(2, 48000 / 4);

        long written = 0;
        long read = 0;
        int mismatches = 0;
        int checked = 0;
        int expectedSilence = 0;

        final short[] pkt = new short[packetFrames * 2];
        final short[] out = new short[packetFrames * 2];

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
                    // 预填区：应该是静音
                    if (out[i * 2] != 0) {
                        mismatches++;
                        if (mismatches <= 3) {
                            System.out.printf("  预填区不为静音: tick=%d i=%d got=%d%n",
                                    tick, i, out[i * 2]);
                        }
                    }
                    expectedSilence++;
                } else {
                    final short want = (short) (outAbs & 0x7FFF);
                    if (out[i * 2] != want) {
                        mismatches++;
                        if (mismatches <= 3) {
                            System.out.printf("  不一致: tick=%d i=%d want=%d got=%d%n",
                                    tick, i, want, out[i * 2]);
                        }
                    }
                }
                checked++;
            }
            read += packetFrames;
        }

        System.out.printf("校验 %d 个样本（其中预填静音 %d），不一致 %d 个%n",
                checked, expectedSilence, mismatches);
        System.out.printf("欠载 %d 帧 / 丢弃 %d / 重复 %d / 最终水位 %d 帧 (%.0f ms)%n",
                jb.underrunFrames(), jb.droppedFrames(), jb.duplicatedFrames(),
                jb.occupancyFrames(), jb.occupancyFrames() / 48.0);
        System.out.println(mismatches == 0 ? "✔ 抖动缓冲输出与输入完全一致" : "✗ 仍有不一致");
    }
}
