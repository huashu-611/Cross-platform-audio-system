package com.audiohub.source;

/**
 * 音频抖动缓冲：按绝对采样序号索引，与 C++ 端（src/jitter_buffer.cpp）同思路。
 *
 * 为什么不能用队列：队列分不清"包还没到"和"包丢了"，也用不了迟到包的后半段。
 * 按绝对序号索引之后，乱序包能落回正确位置，缺口自动补零。
 *
 * 存 short（交织双声道）而不是 float：AudioTrack 原生吃 short[]，
 * 少一次转换就少一次拷贝。
 */
final class JitterBuffer {

    private final short[] data;
    private final boolean[] valid;
    private final int capacityFrames;
    private final int channels;
    private final Object lock = new Object();

    private long readPos;          // 下一个要读的绝对帧序号
    private long highestWritten;
    private boolean started;
    private long framesUnderrun;
    private long packetsOverflow;

    JitterBuffer(int channels, int capacityFrames) {
        this.channels = channels;
        this.capacityFrames = capacityFrames;
        this.data = new short[capacityFrames * channels];
        this.valid = new boolean[capacityFrames];
    }

    /**
     * 写入一包。
     * @return true = 收下；false = 迟到（readPos 已越过）或溢出（超出容量）
     */
    boolean push(long pos, short[] samples, int frames) {
        synchronized (lock) {
            if (!started) {
                // 目标延迟 60ms：把读指针设在首包前 60ms，给网络抖动留余量
                readPos = pos - 60 * 48;
                started = true;
            }
            final long end = pos + frames;
            if (end <= readPos) {
                packetsOverflow++;   // 实为迟到，归到同一计数
                return false;
            }
            if (end > readPos + capacityFrames) {
                packetsOverflow++;
                return false;
            }
            for (int i = 0; i < frames; ++i) {
                final long abs = pos + i;
                if (abs < readPos) continue;    // 部分迟到：跳过已读部分
                final int idx = (int) ((abs - readPos) % capacityFrames);
                data[idx * channels] = samples[i * channels];
                if (channels > 1) data[idx * channels + 1] = samples[i * channels + 1];
                valid[idx] = true;
            }
            if (end > highestWritten) highestWritten = end;
            return true;
        }
    }

    /** 取 frames 帧；无效位置补零。读指针始终推进。 */
    void pull(short[] out, int frames) {
        synchronized (lock) {
            if (!started) {
                java.util.Arrays.fill(out, (short) 0);
                return;
            }
            for (int i = 0; i < frames; ++i) {
                final long abs = readPos + i;
                final int idx = (int) ((abs - readPos) % capacityFrames);
                if (valid[idx]) {
                    out[i * channels] = data[idx * channels];
                    if (channels > 1) out[i * channels + 1] = data[idx * channels + 1];
                } else {
                    framesUnderrun++;
                    out[i * channels] = 0;
                    if (channels > 1) out[i * channels + 1] = 0;
                }
                valid[idx] = false;
            }
            readPos += frames;
        }
    }

    /** 缓冲里的有效帧数。 */
    int occupancyFrames() {
        synchronized (lock) {
            final long n = highestWritten - readPos;
            return n > 0 ? (int) Math.min(n, Integer.MAX_VALUE) : 0;
        }
    }

    /** 清空回到"从未收到过任何包"的状态。连接确认后调用。 */
    void reset() {
        synchronized (lock) {
            java.util.Arrays.fill(valid, false);
            readPos = 0;
            highestWritten = 0;
            started = false;
            pendingSilence = 0;
        }
    }

    private int pendingSilence;

    long underrunFrames() { synchronized (lock) { return framesUnderrun; } }
    long overflowPackets() { synchronized (lock) { return packetsOverflow; } }
}
