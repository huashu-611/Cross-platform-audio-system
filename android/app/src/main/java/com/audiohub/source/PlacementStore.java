package com.audiohub.source;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 人工摆位（POS 帧）的纯静态存储。手机端只读：收到 hub 同步来的整表布局后，
 * MainActivity 构建图谱时优先用它给的坐标，没有摆位的节点再回退到 Scene3D 的确定性自动槽位。
 *
 * <p>逐条对齐 PC 端 {@code src/graph.cpp} 的 DecodePlacementFrame / MergePlacement /
 * EncodePlacementFrame 与协议 §13：
 * <pre>
 *   POS|&lt;version u64&gt;|&lt;origin&gt;|&lt;nodeId&gt;,&lt;xCm&gt;,&lt;zCm&gt;;&lt;nodeId&gt;,&lt;xCm&gt;,&lt;zCm&gt;;...
 * </pre>
 * <ul>
 *   <li>nodeId / origin 与 GRAPH 一致：{@code ip:port}；items 可以为空（= 没有任何人工摆位）。</li>
 *   <li>坐标是厘米、有符号十进制整数，范围 ±32000（±320 m）；越界或非法 = 整帧丢弃。</li>
 *   <li>整布局 LWW（不是逐节点）：version 大者胜；version 相等时 origin 字典序大者胜。</li>
 *   <li>帧上限 1000 字节、节点上限 32 个，超出整帧丢弃。</li>
 * </ul>
 *
 * <p>为什么这么严：UDP 收到的字节可能来自任何程序；坏帧整帧丢弃，绝不能让手机崩。
 */
public final class PlacementStore {

    /** 坐标上限（厘米）：±32000 cm = ±320 m，与 PC 端 kPlacementCoordLimitCm 一致。 */
    public static final int COORD_LIMIT_CM = 32000;
    /** 单帧字节上限，与 PC 端 kMaxPlacementFrameBytes 一致。 */
    public static final int MAX_FRAME_BYTES = 1000;
    /** 单帧节点上限，与 PC 端 kMaxGraphNodes 一致。 */
    public static final int MAX_ITEMS = 32;

    private static final Object LOCK = new Object();

    /** Lamport 版本号（u64；Java 用 long 位模式 + Long.compareUnsigned 对齐 uint64 语义）。 */
    private static long versionBits = 0;
    private static String origin = "";
    /** nodeId → {xCm, zCm}；TreeMap 保证按 nodeId 升序，与 PC 端 SortPlacementItems 一致。 */
    private static final TreeMap<String, int[]> items = new TreeMap<>();

    private PlacementStore() {
    }

    /**
     * 合并一帧 POS。返回 true = 采纳了更新版本／并通过 tie-break 更新（或至少整体状态被换掉）。
     * 坏帧返回 false，且不动本地状态。
     */
    public static boolean mergeFromFrame(String frame) {
        final Decoded d = decode(frame);
        if (d == null) return false;
        synchronized (LOCK) {
            final boolean newer = Long.compareUnsigned(d.versionBits, versionBits) > 0;
            final boolean tieBreak = Long.compareUnsigned(d.versionBits, versionBits) == 0
                    && d.origin.compareTo(origin) > 0;
            if (!newer && !tieBreak) return false;

            final TreeMap<String, int[]> next = new TreeMap<>();
            for (int i = 0; i < d.nodeIds.size(); ++i) {
                next.put(d.nodeIds.get(i), d.coords.get(i));
            }
            final boolean itemsChanged = !sameItems(next, items);
            final boolean versionChanged = d.versionBits != versionBits;
            // 与 C++ MergePlacement 一致：内容与版本都没变就不算采纳（origin 也不改写），
            // 避免同版本同内容的 tie-break 反复改写本地 origin。
            if (!itemsChanged && !versionChanged) return false;
            versionBits = d.versionBits;
            origin = d.origin;
            items.clear();
            items.putAll(next);
            return true;
        }
    }

    /** 当前 Lamport 版本（u64 位模式；展示时用 Long.toUnsignedString）。 */
    public static long version() {
        synchronized (LOCK) {
            return versionBits;
        }
    }

    /** 当前版本来源节点 id（"ip:port"），可能为空。 */
    public static String origin() {
        synchronized (LOCK) {
            return origin;
        }
    }

    /** 查某节点的人工摆位：返回 {xCm, zCm}（副本），没有则 null。 */
    public static int[] find(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return null;
        synchronized (LOCK) {
            final int[] p = items.get(nodeId);
            return p != null ? new int[]{p[0], p[1]} : null;
        }
    }

    /** 当前整表布局副本（按 nodeId 升序）。 */
    public static Map<String, int[]> items() {
        synchronized (LOCK) {
            final TreeMap<String, int[]> copy = new TreeMap<>();
            for (Map.Entry<String, int[]> e : items.entrySet()) {
                copy.put(e.getKey(), new int[]{e.getValue()[0], e.getValue()[1]});
            }
            return copy;
        }
    }

    /** 是否有任何人工摆位。 */
    public static boolean hasPlacement() {
        synchronized (LOCK) {
            return !items.isEmpty();
        }
    }

    /** 清空（"断开所有连接"时与新图谱会话一起复位）。 */
    public static void reset() {
        synchronized (LOCK) {
            versionBits = 0;
            origin = "";
            items.clear();
        }
    }

    /**
     * 编码当前状态为 POS 帧。用于回 hub 的 POS-REQ（手机作为中转节点时也能把已知布局给出去）；
     * 手机端不会主动广播布局。
     */
    public static String encodeFrame() {
        synchronized (LOCK) {
            final StringBuilder sb = new StringBuilder("POS|");
            sb.append(Long.toUnsignedString(versionBits)).append('|').append(origin).append('|');
            boolean first = true;
            for (Map.Entry<String, int[]> e : items.entrySet()) {
                if (!first) sb.append(';');
                first = false;
                sb.append(e.getKey()).append(',').append(e.getValue()[0]).append(',')
                        .append(e.getValue()[1]);
            }
            sb.append('\n');
            return sb.toString();
        }
    }

    // =====================================================================
    // 解码（与 PC 端 DecodePlacementFrame 逐条对齐）
    // =====================================================================

    private static final class Decoded {
        long versionBits;
        String origin = "";
        final List<String> nodeIds = new ArrayList<>();
        final List<int[]> coords = new ArrayList<>();
    }

    /** 严格解码；任何一处不合法都返回 null（= 整帧丢弃）。 */
    private static Decoded decode(String frame) {
        if (frame == null) return null;
        final String text = trimTrailing(frame);
        if (text.isEmpty()) return null;
        // 与 C++ 的 std::string::size() 一致：按 UTF-8 字节数算长度
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) return null;

        final List<String> parts = split(text, '|');
        if (parts.size() != 4) return null;
        if (!"POS".equals(parts.get(0))) return null;

        final long[] ver = new long[1];
        if (!parseU64(parts.get(1), ver)) return null;

        final String org = parts.get(2);
        if (!validIdText(org)) return null;

        final Decoded d = new Decoded();
        d.versionBits = ver[0];
        d.origin = org;

        final String body = parts.get(3);
        if (!body.isEmpty()) {
            final List<String> tokens = split(body, ';');
            if (tokens.size() > MAX_ITEMS) return null;
            final Set<String> seen = new HashSet<>();
            for (String token : tokens) {
                if (token.isEmpty()) return null;
                final List<String> fields = split(token, ',');
                if (fields.size() != 3) return null;
                final String id = fields.get(0);
                if (!validId(id)) return null;
                final Integer x = parseSignedCm(fields.get(1));
                if (x == null) return null;
                final Integer z = parseSignedCm(fields.get(2));
                if (z == null) return null;
                // 重复 id：保留第一个出现（与 C++ 一致）
                if (!seen.add(id)) continue;
                d.nodeIds.add(id);
                d.coords.add(new int[]{x.intValue(), z.intValue()});
            }
        }
        return d;
    }

    /** 去结尾的 \n \r 空格 \t（只去结尾，与 C++ TrimTrailing 一致）。 */
    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0) {
            final char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                --end;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /** 按分隔符切分，保留空段（等价 C++ Split(text, sep)）。 */
    private static List<String> split(String text, char sep) {
        final List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); ++i) {
            if (text.charAt(i) == sep) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    /** 十进制 u64：非空、≤20 位、全数字、不溢出（对应 C++ ParseU64）。 */
    private static boolean parseU64(String s, long[] out) {
        if (s == null || s.isEmpty() || s.length() > 20) return false;
        for (int i = 0; i < s.length(); ++i) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        try {
            out[0] = Long.parseUnsignedLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 有符号厘米整数：可选前导 '-'、其余全数字、绝对值 ≤ 32000（±320 m，对应 C++ ParseSignedCm）。
     * 注意：最长 "-32000"（6 字符）；-32768 这类"清除哨兵"不在协议里（PC 端也不支持），
     * 会被判非法并整帧丢弃；清除某个摆位的正确方式是下一帧里直接不包含该 nodeId。
     */
    private static Integer parseSignedCm(String s) {
        if (s == null || s.isEmpty() || s.length() > 6) return null;
        int i = 0;
        boolean negative = false;
        if (s.charAt(0) == '-') {
            negative = true;
            i = 1;
            if (s.length() == 1) return null;
        }
        int v = 0;
        for (; i < s.length(); ++i) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
            v = v * 10 + (c - '0');
            if (v > COORD_LIMIT_CM) return null;
        }
        return Integer.valueOf(negative ? -v : v);
    }

    /** 节点 id / origin 的字符合法性（对应 C++ ValidIdText）。 */
    private static boolean validIdText(String s) {
        if (s == null) return false;
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            final int c = b & 0xFF;
            if (c < 0x20 || c == 0x7F || c == '"' || c == '\\') return false;
        }
        return true;
    }

    /** "ip:port"（对应 C++ ValidId）：最后一个冒号后是 1..65535 的十进制端口。 */
    private static boolean validId(String s) {
        if (!validIdText(s) || s.isEmpty()) return false;
        final int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon + 1 >= s.length()) return false;
        return validPort(s.substring(colon + 1));
    }

    private static boolean validPort(String s) {
        if (s.isEmpty() || s.length() > 5) return false;
        int v = 0;
        for (int i = 0; i < s.length(); ++i) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
            v = v * 10 + (c - '0');
            if (v > 65535) return false;
        }
        return v >= 1;
    }

    private static boolean sameItems(Map<String, int[]> a, Map<String, int[]> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, int[]> e : a.entrySet()) {
            final int[] bp = b.get(e.getKey());
            if (bp == null) return false;
            final int[] ap = e.getValue();
            if (ap[0] != bp[0] || ap[1] != bp[1]) return false;
        }
        return true;
    }
}