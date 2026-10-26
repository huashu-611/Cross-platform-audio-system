package com.audiohub.source;

/**
 * 音频抖动缓冲：按**绝对采样序号**索引。
 *
 * 这里曾经有一个致命 bug，值得写清楚：
 *   槽位原来是 (abs - readPos) % 容量 —— 写入用的是当时的 readPos，
 *   读取用的是已经推进过的 readPos。读指针一动，写进去的数据就错位了，
 *   读指针翻到的槽位不是当初写的那个，于是绝大多数帧被判为无效输出静音，
 *   只偶尔凑巧对上一点 → 听感上就是"没有声音，偶尔有点电流杂音"。
 *
 * 正确做法：槽位 = abs % 容量。与读指针无关，写入与读取永远指向同一格。
 * 有效性用 valid[] 位图标记，读走一格就失效，避免读到上一圈的旧数据。
 *
 * 另外 Android 端没有 ASRC，收发两端晶振必然有偏差，缓冲会缓慢涨/降。
 * 这里做最轻量的速率微调：缓冲偏高就多消费一帧（丢弃），偏低就少消费一帧
 * （重复），每 5 ms 一个块最多差 1 帧 ≈ 0.4%，足以跟上常见晶振偏差（±0.1%）。
 */
final class JitterBuffer {

    // 包内可见：测试直接引用，避免"改了实现忘了改测试常量"
    static final int PREFILL_MS = 60;      // 起始预留（延迟优先，抖动靠下面的修正吸收）
    // 死区：水位在 [目标-40ms, 目标+40ms] 之内完全不动（step 恒等于 frames）。
    //
    // 这里踩过一个坑：为了让水位"精确"跟随目标，曾经允许每块 ±4 帧的调整。
    // 但 step>frames 的实现是"读 244 帧、只输出前 240 帧"——等于每 5ms
    // 硬切掉 4 帧，一秒切 200 次，听感就是持续的电流爆音。
    // 正确做法是让修正在时间上稀疏：只有偏差持续超出死区才动，且每次只动 1 帧。
    private static final int DEADBAND_MS = 40;
    private static final int MAX_STEP_ADJUST = 1;
    private static final int ADJUST_COOLDOWN = 12;  // 每次修正后至少间隔 12 块（约 60ms）
    // 连续这么多块（约 200 ms）一帧数据都没有，就认为时间轴已经跑偏，
    // 重新对齐到下一个包的序号上。没有这个机制，一次卡顿之后
    // 读指针会永远跑在写指针前面，欠载数一路涨（实测到 38573 帧）。
    private static final int REALIGN_TICKS = 40;

    private final short[] data;
    private final boolean[] valid;
    private final int capacityFrames;
    private final int channels;
    private final Object lock = new Object();

    private long readPos;          // 下一个要读的绝对帧序号
    private long highestWritten;
    private boolean started;

    private int starveTicks;
    private int cooldown;          // 修正冷却，避免每块都切
    private long underrunFrames;
    private long droppedFrames;
    private long duplicatedFrames;

    // drop/duplicate 用的临时缓冲
    private final short[] scratch;

    JitterBuffer(int channels, int capacityFrames) {
        this.channels = channels;
        this.capacityFrames = capacityFrames;
        this.data = new short[capacityFrames * channels];
        this.valid = new boolean[capacityFrames];
        this.scratch = new short[1024 * channels];
    }

    /**
     * 写入一包。
     * @return true = 收下；false = 迟到（已被读过）或超出容量
     */
    boolean push(long pos, short[] samples, int frames) {
        synchronized (lock) {
            if (!started) {
                // 首包之前预留 60 ms，给网络抖动留余量
                readPos = pos - PREFILL_MS * 48L;
                started = true;
            }
            final long end = pos + frames;
            if (end <= readPos) return false;                    // 已经全部过期
            if (pos >= readPos + capacityFrames) return false;   // 超出容量窗口

            for (int i = 0; i < frames; ++i) {
                final long abs = pos + i;
                if (abs < readPos || abs >= readPos + capacityFrames) continue;
                // 必须用 floorMod：首包之前 readPos 会是负数
                // （pos - 60ms），而 Java 的 % 对负数返回负值，
                // 直接当数组下标就是 ArrayIndexOutOfBoundsException。
                final int idx = (int) Math.floorMod(abs, capacityFrames);
                data[idx * channels] = samples[i * channels];
                if (channels > 1) data[idx * channels + 1] = samples[i * channels + 1];
                valid[idx] = true;
            }
            if (end > highestWritten) highestWritten = end;
            return true;
        }
    }

    /** 取 frames 帧；无效位置补零。读指针按漂移补偿后的步长推进。 */
    void pull(short[] out, int frames) {
        synchronized (lock) {
            if (!started) {
                java.util.Arrays.fill(out, (short) 0);
                return;
            }

            // 漂移补偿：按"实际水位与目标水位的偏差"按比例调整消费速度。
            //
            // 之前只是"低于 45ms 少消费一帧 / 高于 180ms 多消费一帧"，
            // 上限 0.4%，跟不住实际晶振偏差与发包成簇带来的持续供需差，
            // 于是水位越掉越低、欠载一路上涨。
            // 现在按偏差大小连续调整，最多 ±4 帧/块（约 ±1.7%）。
            final long occ = highestWritten - readPos;

            // 长时间完全没有数据：时间轴已经对不上了，重新对齐。
            // 否则读指针会一直跑在写指针前面，每个块都是欠载。
            if (occ <= 0) {
                if (++starveTicks > REALIGN_TICKS) {
                    started = false;      // 下一个包会重新建立 60ms 预填
                    starveTicks = 0;
                    java.util.Arrays.fill(out, (short) 0);
                    return;
                }
            } else {
                starveTicks = 0;
            }

            final long target = (long) PREFILL_MS * 48;
            final long err = occ - target;                    // >0 偏多，<0 偏少
            final long band = (long) DEADBAND_MS * 48;

            int adjust = 0;
            if (cooldown > 0) {
                cooldown--;
            } else if (err > band) {
                adjust = MAX_STEP_ADJUST;        // 偏高：多消费 1 帧
                cooldown = ADJUST_COOLDOWN;
            } else if (err < -band) {
                adjust = -MAX_STEP_ADJUST;       // 偏低：少消费 1 帧
                cooldown = ADJUST_COOLDOWN;
            }
            int step = Math.max(1, frames + adjust);

            final int cap = scratch.length / channels;
            final int n = Math.min(step, cap);
            for (int i = 0; i < n; ++i) {
                final long abs = readPos + i;
                // 必须用 floorMod：首包之前 readPos 会是负数
                // （pos - 60ms），而 Java 的 % 对负数返回负值，
                // 直接当数组下标就是 ArrayIndexOutOfBoundsException。
                final int idx = (int) Math.floorMod(abs, capacityFrames);
                if (valid[idx]) {
                    scratch[i * channels] = data[idx * channels];
                    if (channels > 1) scratch[i * channels + 1] = data[idx * channels + 1];
                    valid[idx] = false;
                } else {
                    underrunFrames++;
                    scratch[i * channels] = 0;
                    if (channels > 1) scratch[i * channels + 1] = 0;
                }
            }

            // 输出恒为 frames 帧：多读了丢掉尾部，少读了重复最后一帧
            final int copy = Math.min(frames, n);
            System.arraycopy(scratch, 0, out, 0, copy * channels);
            if (copy < frames) {
                duplicatedFrames += (frames - copy);
                final int last = (copy > 0 ? copy - 1 : 0) * channels;
                for (int i = copy; i < frames; ++i) {
                    out[i * channels] = scratch[last];
                    if (channels > 1) out[i * channels + 1] = scratch[last + 1];
                }
            } else if (n > frames) {
                droppedFrames += (n - frames);
            }

            readPos += n;
        }
    }

    /** 缓冲里的有效帧数。 */
    int occupancyFrames() {
        synchronized (lock) {
            final long n = highestWritten - readPos;
            return n > 0 ? (int) Math.min(n, Integer.MAX_VALUE) : 0;
        }
    }

    /** 清空回到"从未收到过任何包"的状态。 */
    void reset() {
        synchronized (lock) {
            java.util.Arrays.fill(valid, false);
            readPos = 0;
            highestWritten = 0;
            started = false;
            starveTicks = 0;
        }
    }

    long underrunFrames() { synchronized (lock) { return underrunFrames; } }
    long droppedFrames() { synchronized (lock) { return droppedFrames; } }
    long duplicatedFrames() { synchronized (lock) { return duplicatedFrames; } }
}
