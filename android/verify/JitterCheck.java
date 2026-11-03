package com.audiohub.source;

/**
 * JitterBuffer 自检：时间轴锚定 / 断档重锚 / 乱序丢弃。
 *
 * 覆盖的是 v0.15 修的那个 bug：手机从「汇聚端」切回「音源」再切回来时，
 * 对端的音频 sample_pos 可能换了一条时间轴（音源进程重启就从头开始），
 * 而缓冲里还压着上一条时间轴的 readPos。旧代码会把每个包都判成
 * "已经过期"或"超出容量窗口"丢掉 → highestWritten 永不前进 →
 * prefilled() 永远 false → 彻底没声。
 *
 * 纯 Java，不依赖 android.*，可以直接用 JDK 跑。
 */
public class JitterCheck {

    private static int fails = 0;

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name + " — " + detail);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        final short[] pkt = new short[240 * 2];
        for (int i = 0; i < pkt.length; ++i) pkt[i] = (short) (1000 + (i % 60));
        final short[] out = new short[480];

        // T1/T2/T3：正常收流 → 时间轴重置 → 仍能重新出声
        JitterBuffer jb = new JitterBuffer(2, 48000 / 4);
        long pos = 0;
        for (int i = 0; i < 40; ++i) { jb.push(pos, pkt, 240); pos += 240; }
        for (int i = 0; i < 20; ++i) jb.pull(out, 240);
        check("T1 切换前正常收流", jb.prefilled(), "prefilled=" + jb.prefilled());

        pos = 0;   // 音源进程重启：sample_pos 从 0 重新开始
        for (int i = 0; i < 20; ++i) { jb.push(pos, pkt, 240); pos += 240; }
        check("T2 时间轴重置后仍能攒够预填", jb.prefilled(), "prefilled=" + jb.prefilled());

        int nonSilent = 0;
        for (int i = 0; i < 10; ++i) {
            jb.pull(out, 240);
            for (short s : out) if (s != 0) { nonSilent++; break; }
        }
        check("T3 重锚后能取出有效音频", nonSilent > 0, nonSilent + "/10 块非静音");

        // T4：正常推流不能被断档判据误伤（2000 包 = 10 秒）
        JitterBuffer jb2 = new JitterBuffer(2, 48000 / 4);
        long p2 = 500000;   // 故意从一个大序号开始
        int rejected = 0;
        for (int i = 0; i < 2000; ++i) {
            if (!jb2.push(p2, pkt, 240)) rejected++;
            p2 += 240;
            if (i % 2 == 0) jb2.pull(out, 240);
        }
        check("T4 正常推流 2000 包无误判", rejected == 0, "被拒 " + rejected + " 包");

        // T5：小幅乱序（网络重排几帧）仍按旧规则丢弃，不触发重锚
        JitterBuffer jb3 = new JitterBuffer(2, 48000 / 4);
        long p3 = 0;
        for (int i = 0; i < 60; ++i) { jb3.push(p3, pkt, 240); p3 += 240; }
        for (int i = 0; i < 30; ++i) jb3.pull(out, 240);
        boolean stale = jb3.push(p3 - 240 * 5, pkt, 240);
        check("T5 小幅乱序仍被丢弃", !stale, "push 返回 " + stale);

        // T6：重锚必须清掉旧时间轴的残留数据（否则会放出上一段的杂音）
        JitterBuffer jb4 = new JitterBuffer(2, 48000 / 4);
        long p4 = 0;
        for (int i = 0; i < 40; ++i) { jb4.push(p4, pkt, 240); p4 += 240; }
        // 完全不 pull，让旧数据留在 valid[] 里，然后直接跳到新时间轴
        for (int i = 0; i < 10; ++i) jb4.push(900000L + i * 240, pkt, 240);
        check("T6 重锚后不残留旧时间轴数据",
                jb4.occupancyFrames() <= 240 * 10,
                "occupancy=" + jb4.occupancyFrames() + " 帧");

        System.out.println(fails == 0
                ? "\n==> JitterBuffer 自检: ALL PASS"
                : "\n==> JitterBuffer 自检: " + fails + " 项失败");
        if (fails != 0) System.exit(1);
    }
}
