// MixCheck —— 纯 Java 数值对照，验证"接收端混音标度"修复。
//
// 背景：android/.../ReceiverService.java 的 playLoop 里，JitterBuffer.pull() 输出
// 的是 int16 样本，但 716c4b4 之后的代码直接 `mix[i] += block[i] * g` 累加，
// 丢掉了归一化（1/32768）。后续软限幅却按 [-1,1] 设计：
//   shaped = 0.85 + 0.15 * tanh((|x| - 0.85) / 0.15)
// 于是任何 |x| > 0.85 的样本都被压成 ±1，输出 = sign(x) * 32767，
// 即跟随音乐零交叉的满幅方波 —— 听感是持续的重电流音/爆音。
//
// 用法（只需 JDK，无需 Android SDK）：
//   javac -d out tools/mixcheck/MixCheck.java
//   java -cp out MixCheck
//
// 判定：旧路径（int16 直加）满载率应接近 100%（方波），
//       新路径（先归一化）应与输入几乎一致（最大误差 ≤ 1 LSB）。
public class MixCheck {
    static final float KNEE = 0.85f;
    static final float HEADROOM = 1.0f - KNEE;

    static float softLimit(float x) {
        final float a = Math.abs(x);
        if (a <= KNEE) return x;
        final float shaped = KNEE + HEADROOM * (float) Math.tanh((a - KNEE) / HEADROOM);
        return x < 0 ? -shaped : shaped;
    }

    static short clampToS16(float v) {
        return (short) Math.max(-32768, Math.min(32767, v * 32767f));
    }

    public static void main(String[] args) {
        final int framesPerBlock = 240;          // 5ms @48k，与 PacketProtocol 一致
        final int blocks = 200;                  // 1 秒
        final int channels = 2;
        final int n = framesPerBlock * channels;

        final short[] input = new short[n];
        for (int b = 0; b < blocks; ++b) {
            for (int i = 0; i < n; ++i) {
                // 440Hz、半满幅（真正音乐的能量远高于此），左右声道同相
                final double t = (b * framesPerBlock + (i / channels)) / 48000.0;
                input[i] = (short) Math.round(16384.0 * Math.sin(2 * Math.PI * 440.0 * t));
            }
        }

        int oldFullScale = 0;
        int oldTotal = 0;
        int newMaxErr = 0;
        int newFullScale = 0;

        // 旧路径：mix 直接累加 int16 量级
        // 新路径：mix 先除以 32768
        final float inv = 1.0f / 32768.0f;
        for (int b = 0; b < blocks; ++b) {
            for (int i = 0; i < n; ++i) {
                final short s = input[i];
                final float oldMix = s * 1.0f;          // 716c4b4 之后的行为
                final float newMix = s * inv;           // 修复后

                final short oldOut = clampToS16(softLimit(oldMix));
                final short newOut = clampToS16(softLimit(newMix));

                oldTotal++;
                if (oldOut == 32767 || oldOut == -32768 || Math.abs(oldOut) > 30000) oldFullScale++;
                if (newOut == 32767 || newOut == -32768) newFullScale++;
                newMaxErr = Math.max(newMaxErr, Math.abs(newOut - s));
            }
        }

        final double oldPct = 100.0 * oldFullScale / oldTotal;
        System.out.println("旧路径（int16 直加）：近满幅样本 " + oldFullScale + "/" + oldTotal
                + " = " + String.format("%.1f", oldPct) + "%  → 方波/爆音");
        System.out.println("新路径（/32768）   ：近满幅样本 " + newFullScale + "/" + oldTotal
                + "，与输入最大误差 " + newMaxErr + " LSB");

        final boolean ok = oldPct > 90.0 && newFullScale == 0 && newMaxErr <= 1;
        System.out.println(ok ? "MIXCHECK PASSED" : "MIXCHECK FAILED");
        if (!ok) System.exit(1);
    }
}