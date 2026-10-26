package com.audiohub.source;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全网图谱（GRAPH）在 Android 端的内存数据源，算法与 PC 端 graph.cpp 逐字一致。
 * 契约见 docs/GRAPH-PROTOCOL.md §1–§3、§8、§10.3。
 *
 * 模型：
 *   图谱 = 节点集合（按 id 取并集，只增不删）+ 汇聚端指针（Lamport 版本号 + LWW）。
 *   任何一台设备都能改汇聚端：version = max(version, 见过的最大 version) + 1，origin = selfId，
 *   收到别人的 GRAPH 时按 §3 合并，全网几秒内收敛到同一张图。
 *
 * 名字清洗 / UTF-8 40 字节截断、非法帧整帧丢弃、节点数上限、帧长上限都按协议实现。
 *
 * 线程模型：所有 public 方法 static synchronized（一把锁足够：临界区都是微秒级的内存操作）。
 * InviteListener 的接收线程、MainActivity 的 UI 线程、CaptureService 重启判断都会读它。
 */
public final class GraphStore {

    /** 本机音频口，与 InviteListener / CaptureService 对齐。 */
    public static final int AUDIO_PORT = 47000;
    /** 握手口在音频口 +2 上（协议 §2）。 */
    public static final int PORT_OFFSET = 2;

    /** 协议 §2：节点数 > 32 整帧丢弃。 */
    public static final int MAX_NODES = 32;
    /** 协议 §2.1：名字 UTF-8 ≤ 40 字节。 */
    public static final int MAX_NAME_BYTES = 40;
    /** 协议 §2：帧总长必须 < 1000 字节。 */
    public static final int MAX_FRAME_BYTES = 1000;
    /** 协议 §8：10 秒内有该 ip 的数据报就判在线。 */
    public static final long ONLINE_TIMEOUT_MS = 10000L;

    public static final String KIND_PC = "pc";
    public static final String KIND_ANDROID = "android";
    public static final String KIND_OTHER = "other";

    /** 图谱里的一个设备。id / ip / 端口 / 名字在会话内不变（协议 §3 补充规则）。 */
    public static final class Node {
        public final String id;        // "ip:audioPort"
        public final String ip;
        public final int audioPort;
        public final String name;
        public final String kind;      // pc | android | other
        public final int httpPort;
        /** 最近一次收到该设备数据报的时间（活标志，协议 §8）。 */
        public volatile long lastSeenMs;

        public Node(String id, String ip, int audioPort, String name, String kind, int httpPort) {
            this.id = id != null ? id : "";
            this.ip = ip != null ? ip : "";
            this.audioPort = audioPort;
            this.name = name != null ? name : "";
            this.kind = normalizeKind(kind);
            this.httpPort = httpPort;
        }

        /** 显示用名字，永远不为 null（drawText(null) 会 NPE）。 */
        public String displayName() {
            if (name != null && !name.isEmpty()) return name;
            if (ip != null && !ip.isEmpty()) return ip;
            return "未知设备";
        }

        public boolean isPc() { return KIND_PC.equals(kind); }
        public boolean isAndroid() { return KIND_ANDROID.equals(kind); }
    }

    /** snapshot() 的只读副本：UI 线程拿着它渲染，不受后续合并影响。 */
    public static final class Snapshot {
        public final long version;
        public final String origin;
        public final String hubId;
        public final String selfId;
        public final List<Node> nodes;

        Snapshot(long version, String origin, String hubId, String selfId, List<Node> nodes) {
            this.version = version;
            this.origin = origin != null ? origin : "";
            this.hubId = hubId != null ? hubId : "";
            this.selfId = selfId != null ? selfId : "";
            this.nodes = nodes;
        }

        public Node find(String id) {
            if (id == null || id.isEmpty()) return null;
            for (Node n : nodes) {
                if (id.equals(n.id)) return n;
            }
            return null;
        }

        public Node findIp(String ip) {
            if (ip == null || ip.isEmpty()) return null;
            for (Node n : nodes) {
                if (ip.equals(n.ip)) return n;
            }
            return null;
        }

        /** hubId 指向的节点；可能不存在（成员可能稍后到达，协议 §3）。 */
        public Node hubNode() { return find(hubId); }

        public Node selfNode() { return find(selfId); }

        /** isHub = 本机就是汇聚端。 */
        public boolean isHub() { return !selfId.isEmpty() && selfId.equals(hubId); }

        public boolean hasNodeIp(String ip) { return findIp(ip) != null; }

        /** 音源使用的 streamId = 1 + （self 在按 id 升序列表里的 0 基序号）。 */
        public int streamIdForSelf() {
            if (selfId.isEmpty()) return 1;
            for (int i = 0; i < nodes.size(); ++i) {
                if (selfId.equals(nodes.get(i).id)) return i + 1;
            }
            return 1;
        }

        /** 协议 §8：isSelf 恒在线；否则 10 秒内有数据报才算在线。 */
        public boolean isOnline(Node node) {
            if (node == null) return false;
            if (!selfId.isEmpty() && selfId.equals(node.id)) return true;
            return System.currentTimeMillis() - node.lastSeenMs < ONLINE_TIMEOUT_MS;
        }
    }

    private GraphStore() {
    }

    // ------------------------------------------------------------ 状态

    private static volatile String selfId = "";
    private static volatile String selfIp = "";
    private static String selfName = "Android 设备";

    private static long version;
    private static String origin = "";
    private static String hubId = "";

    /** 见过的最大 version（含没采纳的）：本地改动用 max+1，保证单调，协议 §3。 */
    private static long seenVersion;

    /** 始终按 id 升序（协议 §1 的规范序）。 */
    private static final List<Node> nodes = new ArrayList<>();

    /** ip → 最近收到该 ip 数据报的时间。节点还没进图谱时也要记（协议 §8）。 */
    private static final Map<String, Long> seenByIp = new HashMap<>();

    /** 最近一次合并里新学到的节点（供 InviteListener 发 GRAPH-REQ）。 */
    private static final List<Node> newlyLearned = new ArrayList<>();

    // ------------------------------------------------------------ 自节点

    /**
     * 设置本机节点：拿到 WiFi IP / 设备名后调用，可重复调用（IP 变了会替换旧的自身节点）。
     * 启动状态 = {version:0, origin:"", hubId:"", nodes:[self]}（协议 §1）。
     */
    public static synchronized void setSelf(String ip, String name) {
        final String cleanIp = normalizeIp(ip);
        if (cleanIp.isEmpty()) return;

        if (name != null && !name.trim().isEmpty()) {
            selfName = sanitizeName(name.trim());
        }
        if (selfName == null || selfName.isEmpty()) selfName = "Android 设备";

        final String id = cleanIp + ":" + AUDIO_PORT;
        final String oldSelf = selfId;
        if (!oldSelf.isEmpty() && !oldSelf.equals(id)) {
            // 自身节点是"本机自己的地址"，不是观测到的对端，换个 IP 就替换掉；
            // 若汇聚端指针指着旧自身 id，跟着搬过去（角色不变）。
            removeLocked(oldSelf);
            if (hubId.equals(oldSelf)) hubId = id;
        }

        final Node fresh = new Node(id, cleanIp, AUDIO_PORT, selfName, KIND_ANDROID, 0);
        fresh.lastSeenMs = System.currentTimeMillis();
        final Node existing = findLocked(id);
        if (existing != null) {
            fresh.lastSeenMs = Math.max(fresh.lastSeenMs, existing.lastSeenMs);
            nodes.set(nodes.indexOf(existing), fresh);
        } else {
            nodes.add(fresh);
        }
        selfId = id;
        selfIp = cleanIp;
        sortLocked();
    }

    public static synchronized String selfId() { return selfId; }

    public static synchronized String selfIp() { return selfIp; }

    public static synchronized String selfName() { return selfName; }

    // ------------------------------------------------------------ 快照

    /** 只读快照，供 UI / 服务职责判断使用。 */
    public static synchronized Snapshot snapshot() {
        final long now = System.currentTimeMillis();
        final List<Node> copy = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            final Node c = new Node(n.id, n.ip, n.audioPort, n.name, n.kind, n.httpPort);
            c.lastSeenMs = effectiveLastSeen(n, now);
            copy.add(c);
        }
        return new Snapshot(version, origin, hubId, selfId, copy);
    }

    /** 节点全集副本（按 id 升序）。 */
    public static synchronized List<Node> nodes() {
        return new ArrayList<>(nodes);
    }

    public static synchronized long version() { return version; }

    /** 当前汇聚端 node id；空串 = 还没确定（Android 未入网时的默认值）。 */
    public static synchronized String hubId() { return hubId; }

    // ------------------------------------------------------------ 合并（协议 §3）

    /**
     * 收到一帧 GRAPH：解码 → 节点并集 → 汇聚端 LWW。
     *
     * @return true = 本地状态有变化（调用方据此决定是否广播、刷新 UI）
     */
    public static synchronized boolean mergeFromFrame(String frame) {
        final RemoteGraph remote = decodeFrame(frame);
        if (remote == null) return false;   // 非法帧：整帧丢弃

        // 1) 节点并集，同 id 保留 local（节点字段会话内不变）；上限 32（与 C++ 一致）
        boolean nodesChanged = false;
        for (Node rn : remote.nodes) {
            if (nodes.size() >= MAX_NODES) break;   // §2.1：超出的只能不接
            if (findLocked(rn.id) != null) continue;
            final Node learned = new Node(rn.id, rn.ip, rn.audioPort, rn.name, rn.kind, rn.httpPort);
            nodes.add(learned);
            newlyLearned.add(learned);
            nodesChanged = true;
        }
        if (nodesChanged) sortLocked();

        // 见过的最大 version 只增（即使本帧没被采纳，协议 §3 补充规则）
        if (remote.version > seenVersion) seenVersion = remote.version;

        // 2) 汇聚端 LWW
        boolean hubChanged = false;
        if (remote.version > version) {
            version = remote.version;
            origin = remote.origin;
            hubId = remote.hubId;
            hubChanged = true;
        } else if (remote.version == version && !remote.origin.equals(origin)) {
            // 同版本按 origin 逐字节字典序裁决（node id 是 ASCII，String.compareTo 等价）
            if (remote.origin.compareTo(origin) > 0) {
                origin = remote.origin;
                hubId = remote.hubId;
                hubChanged = true;
            }
        }

        // 3) 其它情况保持 local；changed = 节点集合变化 或 hubId/version/origin 变化
        return nodesChanged || hubChanged;
    }

    /** 取出"上一次合并新学到的节点"（取走即清空）。 */
    public static synchronized List<Node> takeNewNodes() {
        if (newlyLearned.isEmpty()) return new ArrayList<>();
        final List<Node> out = new ArrayList<>(newlyLearned);
        newlyLearned.clear();
        return out;
    }

    /**
     * 把一帧 GRAPH 解码出来（不合并）。非法返回 null。
     * 宽松解析按协议 §2.1：字段缺失 / 数字非法 / 节点 id 不含 ':' / 节点数 > 32 都判非法。
     */
    private static RemoteGraph decodeFrame(String frame) {
        if (frame == null) return null;
        final String line = frame.trim();
        if (line.isEmpty()) return null;
        // 帧长 > 1000 字节：整帧丢弃（与 C++ 端一致；接收缓冲 1024）
        if (utf8Len(line) > MAX_FRAME_BYTES) return null;
        // split 用 -1：nodes 为空时末尾是空串（协议 §2.1）
        final String[] parts = line.split("\\|", -1);
        if (parts.length != 5) return null;
        if (!"GRAPH".equals(parts[0])) return null;

        final long ver = parseVersion(parts[1]);
        if (ver < 0) return null;

        final String remoteOrigin = parts[2];
        final String remoteHub = parts[3];

        final List<Node> list = new ArrayList<>();
        if (!parts[4].isEmpty()) {
            final String[] items = parts[4].split(";", -1);
            if (items.length > MAX_NODES) return null;
            final Set<String> ids = new HashSet<>();
            for (String item : items) {
                final Node n = decodeNode(item);
                if (n == null) return null;
                if (ids.add(n.id)) list.add(n);
            }
        }
        return new RemoteGraph(ver, remoteOrigin, remoteHub, list);
    }

    /** node = id,name,kind,audioPort,httpPort；id 必须含 ':'。 */
    private static Node decodeNode(String text) {
        if (text == null || text.isEmpty()) return null;
        final String[] f = text.split(",", -1);
        if (f.length != 5) return null;

        final String id = f[0];
        final int colon = id.lastIndexOf(':');
        if (colon <= 0 || colon >= id.length() - 1) return null;
        final String ip = normalizeIp(id.substring(0, colon));
        if (ip.isEmpty()) return null;

        final int idPort = parseIntRange(id.substring(colon + 1), -1, 65535);
        if (idPort <= 0) return null;
        final int audioPort = parseIntRange(f[3], -1, 65535);
        if (audioPort <= 0) return null;            // 数字非法：整帧丢弃（与 C++ 一致）
        final int httpPort = parseIntRange(f[4], -1, 65535);
        if (httpPort < 0) return null;               // 非法 httpPort：整帧丢弃（C++ 同款）

        // id 是唯一标识：id 与 audioPort 打架时以 id 为准（§2.1 / C++ 同款）
        return new Node(id, ip, idPort, sanitizeName(f[1]), normalizeKind(f[2]), httpPort);
    }

    private static final class RemoteGraph {
        final long version;
        final String origin;
        final String hubId;
        final List<Node> nodes;

        RemoteGraph(long version, String origin, String hubId, List<Node> nodes) {
            this.version = version;
            this.origin = origin != null ? origin : "";
            this.hubId = hubId != null ? hubId : "";
            this.nodes = nodes;
        }
    }

    // ------------------------------------------------------------ 编码（协议 §2.1）

    /** 编码当前图谱（含结尾 '\n'，帧总长 < 1000 字节）。 */
    public static synchronized String encodeFrame() {
        final String head = "GRAPH|" + version + "|" + safeIdField(origin)
                + "|" + safeIdField(hubId) + "|";

        // 节点按 id 升序发送（nodes 已是规范序）；帧装不下时从尾部裁，保证确定性
        // （与 C++ 端一致：上限 32 个节点、总长 < 1000 字节）。
        final List<String> tokens = new ArrayList<>(nodes.size());
        long size = utf8Len(head) + 1;   // +1 = 结尾 '\n'
        for (Node n : nodes) {
            if (tokens.size() >= MAX_NODES) break;
            final String token = encodeNode(n);
            final long add = utf8Len(token) + (tokens.isEmpty() ? 0 : 1);
            if (size + add > MAX_FRAME_BYTES) break;   // 与 C++ 一致：帧总长 <= 1000
            tokens.add(token);
            size += add;
        }

        final StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < tokens.size(); ++i) {
            if (i > 0) sb.append(';');
            sb.append(tokens.get(i));
        }
        return sb.append('\n').toString();
    }

    private static String encodeNode(Node n) {
        return n.id + "," + sanitizeName(n.name) + "," + normalizeKind(n.kind)
                + "," + n.audioPort + "," + n.httpPort;
    }

    // ------------------------------------------------------------ 本地动作

    /**
     * 切换汇聚端（协议 §4.5）：节点必须存在；version = max(version, seen)+1；origin = selfId。
     *
     * @return false = 节点不在图谱里，拒绝
     */
    public static synchronized boolean switchHub(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        if (findLocked(nodeId) == null) return false;
        version = Math.max(version, seenVersion) + 1;
        origin = selfId;
        hubId = nodeId;
        return true;
    }

    /**
     * 节点注册（协议 §4.1）：入网事件把对端加进图谱，**不 bump version**。
     *
     * @return true = 新增了节点（调用方随后广播）
     */
    public static synchronized boolean upsertNode(String ip, int audioPort, String name, String kind) {
        final String cleanIp = normalizeIp(ip);
        if (cleanIp.isEmpty()) return false;
        if (audioPort <= 0 || audioPort > 65535) audioPort = AUDIO_PORT;
        if (cleanIp.equals(selfIp)) return false;   // 自己的其它端口不是对端
        final String id = cleanIp + ":" + audioPort;
        if (findLocked(id) != null) return false;
        if (nodes.size() >= MAX_NODES) return false;   // §2.1：节点上限 32（C++ 同款）
        final Node n = new Node(id, cleanIp, audioPort, sanitizeName(name), normalizeKind(kind), 0);
        n.lastSeenMs = System.currentTimeMillis();  // 握手成功 = 对端活着
        nodes.add(n);
        sortLocked();
        return true;
    }

    public static synchronized boolean isHub() {
        return !selfId.isEmpty() && selfId.equals(hubId);
    }

    public static synchronized Node hubNode() {
        return findLocked(hubId);
    }

    public static synchronized boolean hasNodeIp(String ip) {
        final String cleanIp = normalizeIp(ip);
        if (cleanIp.isEmpty()) return false;
        for (Node n : nodes) {
            if (cleanIp.equals(n.ip)) return true;
        }
        return false;
    }

    /** 本机作为音源的 streamId（rank+1）；自身节点还没建立时退回 1。 */
    public static synchronized int streamIdForSelf() {
        if (selfId.isEmpty()) return 1;
        for (int i = 0; i < nodes.size(); ++i) {
            if (selfId.equals(nodes.get(i).id)) return i + 1;
        }
        return 1;
    }

    /** 收到某 ip 的 GRAPH（或其它数据报）：刷新活标志（协议 §8）。 */
    public static synchronized void markSeen(String ip) {
        final String cleanIp = normalizeIp(ip);
        if (cleanIp.isEmpty()) return;
        final long now = System.currentTimeMillis();
        seenByIp.put(cleanIp, now);
        for (Node n : nodes) {
            if (cleanIp.equals(n.ip)) n.lastSeenMs = now;
        }
    }

    /** 协议 §8：isSelf 恒在线；否则 10s 内有过数据报才在线。 */
    public static boolean isOnline(Node node) {
        if (node == null) return false;
        final String sid = selfId;
        if (!sid.isEmpty() && sid.equals(node.id)) return true;
        return System.currentTimeMillis() - node.lastSeenMs < ONLINE_TIMEOUT_MS;
    }

    /**
     * "全部断开"（协议 §4.6）：本地把图谱重置为只有自己，hubId 清空，**不广播**。
     * （协议对 Android 的措辞是"hubId 清空"，与 PC 的 hubId=selfId 不同。）
     */
    public static synchronized void resetSession() {
        version = Math.max(version, seenVersion) + 1;
        origin = selfId;
        hubId = "";
        seenByIp.clear();
        newlyLearned.clear();
        nodes.clear();
        if (!selfId.isEmpty()) {
            final Node self = new Node(selfId, selfIp, AUDIO_PORT,
                    sanitizeName(selfName), KIND_ANDROID, 0);
            self.lastSeenMs = System.currentTimeMillis();
            nodes.add(self);
        }
    }

    // ------------------------------------------------------------ 工具

    /** 名字清洗（协议 §2.1）：4 个分隔符 + 所有 <0x20 控制字符换 '_'，UTF-8 截断到 40 字节。 */
    public static String sanitizeName(String in) {
        if (in == null) return "";
        final StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); ) {
            final int cp = in.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == '|' || cp == ',' || cp == ';') {
                sb.append('_');
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return truncateUtf8(sb, MAX_NAME_BYTES);
    }

    /** 按 UTF-8 字符边界截断（不会截出半个汉字）。 */
    private static String truncateUtf8(CharSequence s, int maxBytes) {
        if (s == null) return "";
        int bytes = 0;
        int end = 0;
        while (end < s.length()) {
            final int cp = Character.codePointAt(s, end);
            final int len = utf8Len(cp);
            if (bytes + len > maxBytes) break;
            bytes += len;
            end += Character.charCount(cp);
        }
        return s.subSequence(0, end).toString();
    }

    private static int utf8Len(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }

    private static int utf8Len(String s) {
        if (s == null) return 0;
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** origin / hubId 里不可能有分隔符，但编码前仍剔一遍，避免产生非法帧。 */
    private static String safeIdField(String in) {
        if (in == null) return "";
        final StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); ) {
            final int cp = in.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == '|' || cp == ';' || cp == ',') continue;
            sb.appendCodePoint(cp);
        }
        return truncateUtf8(sb, 64);
    }

    private static String normalizeKind(String kind) {
        if (KIND_PC.equals(kind)) return KIND_PC;
        if (KIND_ANDROID.equals(kind)) return KIND_ANDROID;
        return KIND_OTHER;
    }

    /** 去掉首尾空白；IPv6 不做支持（协议用 "ip:audioPort"，局域网 IPv4）。 */
    private static String normalizeIp(String ip) {
        if (ip == null) return "";
        return ip.trim();
    }

    private static long parseVersion(String text) {
        if (text == null) return -1;
        final String s = text.trim();
        if (s.isEmpty() || s.length() > 18) return -1;   // 18 位以内不溢出 long
        for (int i = 0; i < s.length(); ++i) {
            if (!Character.isDigit(s.charAt(i))) return -1;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseIntRange(String text, int min, int max) {
        if (text == null) return -1;
        final String s = text.trim();
        if (s.isEmpty() || s.length() > 6) return -1;
        for (int i = 0; i < s.length(); ++i) {
            if (!Character.isDigit(s.charAt(i))) return -1;
        }
        try {
            final int v = Integer.parseInt(s);
            if (v < min || v > max) return -1;
            return v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long effectiveLastSeen(Node n, long now) {
        long last = n.lastSeenMs;
        final Long byIp = seenByIp.get(n.ip);
        if (byIp != null && byIp.longValue() > last) last = byIp.longValue();
        return last;
    }

    private static Node findLocked(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Node n : nodes) {
            if (id.equals(n.id)) return n;
        }
        return null;
    }

    private static void removeLocked(String id) {
        final Node n = findLocked(id);
        if (n != null) nodes.remove(n);
    }

    /** 节点按 id 升序（协议 §1 的规范序；比较是 ASCII/字典序，与 C++ 的 std::sort 一致）。 */
    private static void sortLocked() {
        java.util.Collections.sort(nodes, new java.util.Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                return a.id.compareTo(b.id);
            }
        });
    }
}