package com.audiohub.source;

/**
 * 线格式，必须和 PC 端 include/audiohub/packet.hpp 完全一致。
 *
 * <pre>
 *  0                   1                   2                   3
 * +-------+-------+---------------+---------------+---------------+
 * | ver 4 |flags 4|    reserved   |        stream_id (16)         |
 * +-------+-------+---------------+---------------+---------------+
 * |                          seq (32)                             |
 * +---------------------------------------------------------------+
 * |                       sample_pos (32)                         |
 * +---------------------------------------------------------------+
 * |                     capture_ts_ms (32)                        |
 * +---------------------------------------------------------------+
 * |                  int16 PCM payload (960 B)                    |
 * +---------------------------------------------------------------+
 * </pre>
 *
 * 全部小端。这里手写字节序而不是用 struct，原因和 PC 端一样：
 * 跨语言、跨平台时，任何"依赖内存布局"的写法都会在某个环节静默错位。
 */
final class PacketProtocol {

    static final int VERSION = 1;

    static final int HEADER_BYTES = 16;
    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;
    static final int FRAMES_PER_PACKET = 240;  // 5 ms
    static final int BYTES_PER_SAMPLE = 2;
    static final int PAYLOAD_BYTES = FRAMES_PER_PACKET * CHANNELS * BYTES_PER_SAMPLE;
    static final int PACKET_BYTES = HEADER_BYTES + PAYLOAD_BYTES;

    /** 一包对应的毫秒数，发送节拍用。 */
    static final int PACKET_INTERVAL_MS = FRAMES_PER_PACKET * 1000 / SAMPLE_RATE;

    private PacketProtocol() {}

    static void writeHeader(byte[] dst, int streamId, int seq, int samplePos, int captureTsMs) {
        dst[0] = (byte) ((VERSION << 4) & 0xF0);
        dst[1] = 0;
        putShort(dst, 2, streamId);
        putInt(dst, 4, seq);
        putInt(dst, 8, samplePos);
        putInt(dst, 12, captureTsMs);
    }

    private static void putShort(byte[] d, int off, int v) {
        d[off] = (byte) (v & 0xFF);
        d[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putInt(byte[] d, int off, int v) {
        d[off] = (byte) (v & 0xFF);
        d[off + 1] = (byte) ((v >> 8) & 0xFF);
        d[off + 2] = (byte) ((v >> 16) & 0xFF);
        d[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
