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
    // 死区：水位在 [目标-bad, 目标+band] 之内不动（读速率 = 1）。
    //
    // 这里踩过两个坑，都值得写清楚：
    //
    // 坑 1（老）：为了让水位"精确"跟随目标，允许每块 ±4 帧的调整。但 step>frames
    //   的实现是"读 244 帧、只输出前 240 帧"——等于每 5ms 硬切掉 4 帧，
    //   一秒切 200 次，听感就是持续的电流爆音。改成"稀疏修正 + 每次 1 帧"。
    //
    // 坑 2（v0.12 修）：只做稀疏修正还是不够。发送端按 5 ms 一拍发包，接收端按
    //   5 ms 一块取数，但两者的时钟不同源；再加上 Android 的播放线程调度粒度，
    //   实测水位会稳定停在 150~190 ms，而目标是 60 ms —— 偏差 90~130 ms，
    //   远远超出 ±40 ms 死区，于是"每 60ms 修一次"变成常态：每秒硬切 16 次，
    //   听感就是持续的咔哒/电流音（用户 2026-11-02 报的「明显杂音和电流音」）。
    //
    // 正解：
    //   * 读位置用**分数游标**（pos_），每块按速率前进；取样做线性插值。
    //     这样调速是"连续改变读取速度"，不是"整帧丢弃"，不会有任何硬切。
    //   * 死区放宽到 ±80 ms（配合 250 ms 容量），常见的调度抖动都落在死区内，
    //     根本不需要调速；只有真正的晶振漂移才会慢慢触发。
    //   * 速率上限 ±0.5%（48 kHz 下每秒 240 帧），比 ±20~100 ppm 的真实晶振偏差
    //     大一个数量级，够用且听不出来。
    private static final int DEADBAND_MS = 80;
    private static final double MAX_RATE_ADJUST = 0.005;   // ±0.5%
    // 连续这么多块（约 200 ms）一帧数据都没有，就认为时间轴已经跑偏，
    // 重新对齐到下一个包的序号上。没有这个机制，一次卡顿之后
    // 读指针会永远跑在写指针前面，欠载数一路涨（实测到 38573 帧）。
    private static final int REALIGN_TICKS = 40;

    private final short[] data;
    private final boolean[] valid;
    private final int capacityFrames;
    private final int channels;
    private final Object lock = new Object();

    private long readPos;          // 下一个要读的绝对帧序号（整数部分）
    private double pos_;           // 分数读取位置：readPos + [0,1)。调速靠它连续前进
    private long highestWritten;
    private boolean started;

    private int starveTicks;
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
                // 首包决定了时间轴起点。这里**只**摆好游标，不动 readPos。
                //
                // 曾经写成 readPos = pos - PREFILL_MS*48（把读指针往回拨 60ms），
                // 意思是"预填 60ms 再开始播"。但那段区间里 valid[] 全是 false ——
                // occupancyFrames() 报着 2880 帧"有效数据"，实际一帧都没有。
                // 于是 playLoop 的开播条件在只收到一个包时就成立，前 60ms 全部走
                // underrun 分支补静音：一串静音台阶 → 启动时的"杂音"。
                readPos = pos;
                pos_ = pos;
                highestWritten = pos;
                started = true;
            }
            final long end = pos + frames;

            // ---- 时间轴断档检测（v0.15）----
            //
            // 发送方换了时间基准时（音源进程重启后 sample_pos 从 0 重新开始，
            // 或对端被切换后由另一台设备接管），本缓冲的 readPos 还停在旧的
            // 大序号上。这时下面两条"正常丢包"规则会把**每一个包**都吃掉：
            //   end <= readPos                  → 判为"已经全部过期"
            //   pos >= readPos + capacityFrames → 判为"超出容量窗口"
            // 一包都进不来 → highestWritten 永不前进 → prefilled() 永远 false
            // → 播放线程一直 continue → **彻底没声**（用户 2026-11-02 报的
            //「切换后开始有点声音并伴随杂音，然后就彻底没声了」）。
            //
            // 以前没暴露，是因为 ROLE|source 会走 pickRemoteHub → ACTION_STOP →
            // stopAll() → streams.clear()，顺手把旧缓冲清了。v0.14 让 ROLE 不再停
            // 接收服务（对端切回来要能立刻收流，"不断音"必须这么做），
            // 这个巧合就没了，问题才浮出来。
            //
            // 判据必须精确，不能误伤正常推流：正常时 readPos 永远跟在 pos 后面
            // ~60–140ms（容量 250ms），所以这两种情况在正常流里根本不会出现。
            if (end <= readPos) {
                // 整包在读指针之后 = 已经过期。小幅乱序（网络重排几帧）是正常的，
                // 但整整落后半个窗口就只能是"换了时间轴"。
                if (readPos - end > capacityFrames / 2L) {
                    reanchorLocked(pos);
                } else {
                    return false;
                }
            } else if (pos >= readPos + capacityFrames) {
                // 太超前：正常流最多领先 ~140ms，容量是 250ms。
                // 能撞到这里只可能是新时间轴（例如从 0 重新开始，而 readPos 很大）。
                reanchorLocked(pos);
            }
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

    /**
     * 把缓冲整体挪到新的时间轴上（调用方必须已持有 lock）。
     *
     * 关键：**必须清掉 valid[]**。不清的话读指针在新时间轴上按 floorMod
     * 绕回旧槽位，会把上一段会话的音频当成本次的样本放出来 ——
     * 听感正好是「切换后先有一小段带杂音的声音，然后才是静音」。
     */
    private void reanchorLocked(long pos) {
        java.util.Arrays.fill(valid, false);
        readPos = pos;
        pos_ = (double) pos;
        highestWritten = pos;
        starveTicks = 0;
        started = true;
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

            // 读速率：水位在死区内就是 1.0（原速），超出才缓慢修正。
            // 关键区别：修正改的是**速度**（pos_ 每块前进多少），不是"整帧丢弃"。
            final long target = (long) PREFILL_MS * 48;
            final long err = occ - target;                    // >0 偏多，<0 偏少
            final long band = (long) DEADBAND_MS * 48;

            double rate = 1.0;
            if (err > band) {
                // 水位偏高（收得比放得快）：读快一点，把积压慢慢吃掉。
                // 偏差越大修得越快，但不超过 MAX_RATE_ADJUST。
                final double over = (double) (err - band) / (double) target;
                rate = 1.0 + Math.min(MAX_RATE_ADJUST, over * MAX_RATE_ADJUST);
            } else if (err < -band) {
                // 水位偏低（放得比收得快）：读慢一点，等数据追上来。
                final double under = (double) (-err - band) / (double) target;
                rate = 1.0 - Math.min(MAX_RATE_ADJUST, under * MAX_RATE_ADJUST);
            }

            // 分数游标推进 + 线性插值取样。
            // 用线性而不是"整帧丢/重复"：后者的相位跳变在持续音上就是咔哒声。
            // 线性插值在 48 kHz 上对 ±0.5% 的速率偏差完全听不出来。
            final int cap = scratch.length / channels;
            final int n = Math.min(frames, cap);
            for (int i = 0; i < n; ++i) {
                final long base = (long) Math.floor(pos_);
                final double frac = pos_ - (double) base;
                // 必须用 floorMod：首包之前 pos_ 会是负数
                // （pos - 60ms），而 Java 的 % 对负数返回负值，
                // 直接当数组下标就是 ArrayIndexOutOfBoundsException。
                final int i0 = (int) Math.floorMod(base, capacityFrames);
                final int i1 = (int) Math.floorMod(base + 1, capacityFrames);
                if (valid[i0]) {
                    if (frac <= 0.0001 || !valid[i1]) {
                        // 没有下一帧可插值：直接用当前帧（块边界 / 数据没到）
                        scratch[i * channels] = data[i0 * channels];
                        if (channels > 1) scratch[i * channels + 1] = data[i0 * channels + 1];
                    } else {
                        for (int c = 0; c < channels; ++c) {
                            final double a = data[i0 * channels + c];
                            final double b = data[i1 * channels + c];
                            scratch[i * channels + c] = (short) Math.round(a + (b - a) * frac);
                        }
                    }
                } else {
                    // 这一帧真的没到（丢包/欠载）：补静音，不能拿上一帧凑数
                    underrunFrames++;
                    for (int c = 0; c < channels; ++c) scratch[i * channels + c] = 0;
                }
                pos_ += rate;
            }
            // 读过的槽位标记失效（floor(base) 之前的一律可以释放）
            final long consumed = (long) Math.floor(pos_);
            while (readPos < consumed) {
                final int idx = (int) Math.floorMod(readPos, capacityFrames);
                valid[idx] = false;
                readPos++;
            }

            // 输出恒为 frames 帧。n 恒等于 frames（cap 比 frames 大得多），
            // 这里保留兜底：万一 scratch 不够长，尾部补静音而不是重复上一帧
            // —— 重复一个采样会在波形上留一个台阶，正是"咔哒"声的来源。
            final int copy = Math.min(frames, n);
            System.arraycopy(scratch, 0, out, 0, copy * channels);
            if (copy < frames) {
                for (int i = copy * channels; i < frames * channels; ++i) out[i] = 0;
            }
            // 注意：readPos / valid 的推进已经在上面按 pos_ 的整数部分做完了，
            // 这里不能再 readPos += n（会双倍推进，表现为"每块丢半块音频"）。
        }
    }

    /** 缓冲里的有效帧数。 */
    int occupancyFrames() {
        synchronized (lock) {
            final long n = highestWritten - readPos;
            return n > 0 ? (int) Math.min(n, Integer.MAX_VALUE) : 0;
        }
    }

    /**
     * 攒够启动预填了吗？没攒够就先别开播。
     *
     * 开播前必须真的垫够 PREFILL_MS，否则第一块一取就是"数据没到"，只能补静音 ——
     * 听感上就是起播时"嗒"一下的杂音。判断放在这里而不是 ReceiverService，
     * 是为了让"预填多少"只有一个真源（PREFILL_MS）。
     */
    boolean prefilled() {
        synchronized (lock) {
            if (!started) return false;
            return highestWritten - readPos >= PREFILL_MS * 48L;
        }
    }

    /** 清空回到"从未收到过任何包"的状态。 */
    void reset() {
        synchronized (lock) {
            java.util.Arrays.fill(valid, false);
            readPos = 0;
            pos_ = 0;
            highestWritten = 0;
            started = false;
            starveTicks = 0;
        }
    }

    long underrunFrames() { synchronized (lock) { return underrunFrames; } }
    long droppedFrames() { synchronized (lock) { return droppedFrames; } }
    long duplicatedFrames() { synchronized (lock) { return duplicatedFrames; } }
}
