package com.audiohub.source;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 常驻的握手监听器：始终占着"音频口 + 2"，处理三族帧。
 *
 * 为什么必须常驻：之前这个口是在 ReceiverService 启动时才绑定的，
 * 于是手机停在连接界面（没开接收服务）时，47002 上根本没人收 ——
 * 电脑发来的 CONN-INVITE 被静默丢弃，表现为"电脑邀请手机，手机没反应"。
 *
 * 处理：
 *   CONN-INVITE|对端名|对端ip:音频口|序号   → 交给界面弹窗确认
 *   CONN-REQ|发起方名|streamId|音频口       → 本机是 hub 且来源是图谱成员 → 回 CONN-ACK（协议 §5）
 *   ROLE|hub / ROLE|source|ip:port          → 旧版角色指令（兼容窗口，60 秒自动放行）
 *   GRAPH|version|origin|hubId|nodes        → 合并图谱（协议 §2.1 / §4.2）
 *   GRAPH-REQ                               → 单播回当前图谱，同源 200ms 限速（协议 §4.3）
 *   POS|version|origin|items                → 合并人工摆位整表（协议 §13；整布局 LWW）
 *   POS-REQ                                 → 单播回当前 POS，同源 200ms 限速
 *
 * 图谱广播由本线程按 deadline 驱动（3 秒周期 + 本地变化的 2 次/60ms + 限速），
 * 不新开线程：接收超时是 300ms，tick() 在每次收包后和每次超时后执行。
 */
public final class InviteListener {

    public static final int PORT_OFFSET = 2;

    /** 协议 §4.4：每 3 秒向所有节点广播一次当前图谱（补偿丢包）。 */
    private static final long GRAPH_PERIOD_MS = 3000L;
    /** 协议 §4.3：同一来源 200ms 内不重复回 GRAPH。 */
    private static final long GRAPH_REPLY_MIN_GAP_MS = 200L;
    /** 协议 §4.4：同一设备 1 秒内最多广播 5 次，超出合并为一次延迟发送。 */
    private static final int GRAPH_MAX_PER_SEC = 5;
    /** 协议 §4.4：本地变化立即发 2 次，间隔 60ms。 */
    private static final int GRAPH_LOCAL_BURST = 2;
    private static final long GRAPH_LOCAL_GAP_MS = 60L;
    /** 协议 §13：同一来源 200ms 内不重复回 POS（与 GRAPH-REQ 同策略）。 */
    private static final long POS_REPLY_MIN_GAP_MS = 200L;
    /** 协议 §13：采纳后中继也要限速，避免多端同时采纳时打风暴（C++ 同值）。 */
    private static final int POS_MAX_PER_SEC = 5;

    // 界面轮询的快照
    /** 一条待确认的接入请求（别人要把声音送到本机）。 */
    public static final class Pending {
        public final int streamId;
        public final String name;
        public final String ip;
        public final int port;
        /** 对端音频口（CONN-REQ 第 4 段，老版本不带则退回 47000）。 */
        public final int audioPort;

        Pending(int streamId, String name, String ip, int port, int audioPort) {
            this.streamId = streamId;
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.audioPort = audioPort;
        }
    }

    /** 待确认列表。界面轮询后渲染成"同意/拒绝"。 */
    public static final java.util.List<Pending> sPending =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Pending>());

    /**
     * 对端下发的角色指令。界面轮询后执行一次然后清空。
     *   "hub"                 → 本机当汇聚端，开始接收
     *   "source|<ip>|<port>"  → 本机当音源，把声音送到 ip:port
     *
     * 注意：这是局域网内的明文控制帧，没有鉴权。同一局域网内任何设备都能发；
     * 作为内网工具可以接受，但要清楚这一点。图谱（GRAPH）接手后这是兼容路径。
     */
    public static volatile String sRoleCommand = null;

    // 收到 ROLE|hub 之后的一段时间内，自动同意来自该 IP 的 CONN-REQ。
    //
    // 为什么需要：PC 把本机设为汇聚端后，会立刻起一个音源进程发 CONN-REQ。
    // 如果这条请求还要用户手动点"同意"，PC 那头等 30 秒就退出了 ——
    // 表现就是"手机当了汇聚端但一点声音都没有"。
    // 对端既然能下发 ROLE|hub，就是它主动任命本机，放行它的连接是合理的。
    // 图谱模型的正式规则是 §5（hub 对成员自动 ACK），这段 60s 窗口只做旧版兼容。
    private static String autoAcceptIp = null;
    private static long autoAcceptUntil = 0;

    private static boolean shouldAutoAccept(String ip) {
        return autoAcceptIp != null && autoAcceptIp.equals(ip)
                && System.currentTimeMillis() < autoAcceptUntil;
    }

    public static volatile boolean sInvitePending = false;
    public static volatile String sInviteName = "";
    public static volatile String sInviteIp = "";
    public static volatile int sInvitePort = 0;

    private static InviteListener instance;

    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;

    /** 本机名字，用于回 CONN-ACK。 */
    private static String selfName = "Android 设备";

    // ---- 图谱广播调度。除 sendBroadcastNow/ tick / handle* 外，其它线程只入队。----
    private final Object schedLock = new Object();
    private int queuedBroadcasts;      // 待补发的广播次数
    private long nextBroadcastAt;      // 下一次补发的时间
    private long nextPeriodicAt;       // 下一次 3 秒周期广播的时间
    private long rateWindowStart;      // 限速窗口起点
    private int rateWindowCount;       // 窗口内已广播次数
    /** 仅 listener 线程访问：来源 ip → 上次回 GRAPH 的时间。 */
    private final Map<String, Long> graphReplyAt = new HashMap<>();
    /** 仅 listener 线程访问：来源 ip → 上次回 POS 的时间。 */
    private final Map<String, Long> posReplyAt = new HashMap<>();
    /** 采纳远端摆位后向全网中继一次；与 GRAPH 一样按 1s 窗口限速。 */
    private long posRateWindowStart;
    private int posRateWindowCount;

    public static synchronized InviteListener get() {
        if (instance == null) instance = new InviteListener();
        return instance;
    }

    private InviteListener() {
    }

    public static void setSelfName(String name) {
        if (name != null && !name.isEmpty()) selfName = name;
    }

    /** 开始监听。重复调用安全。端口被占时返回 false（不影响其它功能）。 */
    public synchronized boolean start(int audioPort) {
        if (running) return true;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(audioPort + PORT_OFFSET));
            socket.setSoTimeout(300);
        } catch (Exception e) {
            socket = null;
            return false;
        }
        running = true;
        synchronized (schedLock) {
            nextPeriodicAt = System.currentTimeMillis() + GRAPH_PERIOD_MS;
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() { loop(); }
        }, "audiohub-invite");
        thread.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (thread != null) {
            try { thread.join(400); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    // ------------------------------------------------------------ 图谱：对外接口

    /**
     * 本地变化（切换汇聚端 / 新增节点 / 合并导致变化）后广播当前图谱：
     * 立即发 1 次，60ms 后再补 1 次（协议 §4.4）。可在任意线程调用。
     */
    public static void broadcastGraph() {
        final InviteListener self = instance;
        if (self == null) return;
        if (!self.sendBroadcastNow()) {
            // 被限速：sendBroadcastNow 已经排了一次延迟发送
            return;
        }
        synchronized (self.schedLock) {
            self.queuedBroadcasts = Math.max(self.queuedBroadcasts, GRAPH_LOCAL_BURST - 1);
            self.nextBroadcastAt = System.currentTimeMillis() + GRAPH_LOCAL_GAP_MS;
        }
    }

    /** 单播当前图谱到指定握手口（GRAPH-REQ 的回复、手动同步用）。 */
    public static void sendGraphTo(String ip, int port) {
        final InviteListener self = instance;
        if (self == null || ip == null || ip.isEmpty() || port <= 0) return;
        self.sendText(ip, port, GraphStore.encodeFrame());
    }

    /** 立即广播一次 + 向所有节点各发一次 GRAPH-REQ（协议 §4.4 / §7.3 的本地版）。 */
    public static void requestSync() {
        final InviteListener self = instance;
        if (self == null) return;
        self.sendBroadcastNow();
        final GraphStore.Snapshot snap = GraphStore.snapshot();
        for (GraphStore.Node n : snap.nodes) {
            if (n.id.equals(snap.selfId)) continue;
            if (n.audioPort <= 0) continue;
            self.sendGraphRequestTo(n.ip, n.audioPort + PORT_OFFSET);
            // 连上/同步时顺便把人工摆位也请求一份（协议 §13：手机只收不广播）
            self.sendPosRequestTo(n.ip, n.audioPort + PORT_OFFSET);
        }
    }

    /**
     * 向所有节点广播一次（协议 §4.4）。
     *
     * @return false = 被限速（已排入延迟发送）
     */
    private boolean sendBroadcastNow() {
        final long now = System.currentTimeMillis();
        synchronized (schedLock) {
            if (now - rateWindowStart >= 1000L) {
                rateWindowStart = now;
                rateWindowCount = 0;
            }
            if (rateWindowCount >= GRAPH_MAX_PER_SEC) {
                // 超出限速：合并为一次延迟发送
                if (queuedBroadcasts <= 0) {
                    queuedBroadcasts = 1;
                    nextBroadcastAt = rateWindowStart + 1000L;
                }
                return false;
            }
            rateWindowCount++;
        }

        final GraphStore.Snapshot snap = GraphStore.snapshot();
        if (snap.nodes.size() <= 1) return true;   // 只有自己：没有发送目标
        final String frame = GraphStore.encodeFrame();
        for (GraphStore.Node n : snap.nodes) {
            if (n.id.equals(snap.selfId)) continue;
            if (n.audioPort <= 0) continue;
            // 协议 §4.4：目标 = id 的 ip + audioPort+2（握手口）
            sendText(n.ip, n.audioPort + PORT_OFFSET, frame);
        }
        return true;
    }

    private void sendGraphRequestTo(String ip, int port) {
        sendText(ip, port, "GRAPH-REQ\n");
    }

    private void sendPosRequestTo(String ip, int port) {
        sendText(ip, port, "POS-REQ\n");
    }

    private void sendText(String ip, int port, String text) {
        final DatagramSocket s = socket;
        if (s == null || ip == null || ip.isEmpty() || text == null || port <= 0) return;
        try {
            final byte[] data = text.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(data, data.length, InetAddress.getByName(ip), port));
        } catch (Exception ignored) {
            // UDP 无连接：发送失败不影响本地状态（协议 §4.4）
        }
    }

    /** 每次收包后 / 接收超时后调用：补发本地变化广播 + 3 秒周期保活。 */
    private void tick() {
        final long now = System.currentTimeMillis();

        int due = 0;
        synchronized (schedLock) {
            if (queuedBroadcasts > 0 && now >= nextBroadcastAt) {
                due = 1;
                queuedBroadcasts--;
                nextBroadcastAt = now + GRAPH_LOCAL_GAP_MS;
            }
        }
        if (due > 0 && !sendBroadcastNow()) {
            synchronized (schedLock) {
                if (queuedBroadcasts < 1) queuedBroadcasts = 1;
            }
        }

        boolean periodic = false;
        synchronized (schedLock) {
            if (nextPeriodicAt <= 0) nextPeriodicAt = now + GRAPH_PERIOD_MS;
            if (now >= nextPeriodicAt) {
                nextPeriodicAt = now + GRAPH_PERIOD_MS;
                periodic = true;
            }
        }
        if (periodic) sendBroadcastNow();   // 被限速就跳过，下个周期补
    }

    // ------------------------------------------------------------ 图谱：收到帧

    /** 收到 GRAPH：合并 + 活标志；有变化就广播一次并给新节点发 GRAPH-REQ（协议 §4.2）。 */
    private void handleGraph(String line, String fromIp) {
        GraphStore.markSeen(fromIp);
        final boolean changed = GraphStore.mergeFromFrame(line);
        if (!changed) return;   // 未变化不回发，防止广播风暴（协议 §4.2 第 3 条）
        sendBroadcastNow();
        // 学会新节点：向它要一次同步，顺便让它把当前图谱发回来（协议 §4.4）
        final List<GraphStore.Node> fresh = GraphStore.takeNewNodes();
        for (GraphStore.Node n : fresh) {
            if (n.id.equals(GraphStore.selfId())) continue;
            if (n.audioPort <= 0) continue;
            sendGraphRequestTo(n.ip, n.audioPort + PORT_OFFSET);
            // 新学到的节点也可能握着更新的摆位（中继），同样要一份 POS
            sendPosRequestTo(n.ip, n.audioPort + PORT_OFFSET);
        }
    }

    /** 收到 GRAPH-REQ：单播回当前图谱，同源 200ms 限速（协议 §4.3）。 */
    private void handleGraphRequest(String fromIp, int fromPort) {
        GraphStore.markSeen(fromIp);
        final long now = System.currentTimeMillis();
        final Long last = graphReplyAt.get(fromIp);
        if (last != null && now - last.longValue() < GRAPH_REPLY_MIN_GAP_MS) return;
        graphReplyAt.put(fromIp, Long.valueOf(now));
        sendText(fromIp, fromPort, GraphStore.encodeFrame());
        if (graphReplyAt.size() > 64) pruneGraphReplyMap(now);
    }

    private void pruneGraphReplyMap(long now) {
        final java.util.Iterator<Map.Entry<String, Long>> it = graphReplyAt.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Long> e = it.next();
            if (now - e.getValue().longValue() > 10000L) it.remove();
        }
    }

    // ------------------------------------------------------------ 人工摆位：收到 POS 帧

    /**
     * 收到 POS：合并整表布局（协议 §13，整布局 LWW）。
     * 不需要回调 UI —— MainActivity 每 400ms 轮询一次，下一个 tick 自然用新坐标重绘。
     */
    private void handlePos(String line, String fromIp) {
        GraphStore.markSeen(fromIp);
        // 采纳了更新的整表 → 按协议 §13 向所有已知节点重发一次（中继；各端限速）
        if (PlacementStore.mergeFromFrame(line)) broadcastPlacementNow();
    }

    /** 把当前 POS 整表广播给所有已知节点（采纳后中继；手机端不会自己产生摆位）。 */
    private void broadcastPlacementNow() {
        final long now = System.currentTimeMillis();
        synchronized (schedLock) {
            if (now - posRateWindowStart >= 1000L) {
                posRateWindowStart = now;
                posRateWindowCount = 0;
            }
            if (posRateWindowCount >= POS_MAX_PER_SEC) return;   // 限速：本次不中继
            posRateWindowCount++;
        }
        final GraphStore.Snapshot snap = GraphStore.snapshot();
        if (snap.nodes.size() <= 1) return;   // 只有自己：没有发送目标
        final String frame = PlacementStore.encodeFrame();
        for (GraphStore.Node n : snap.nodes) {
            if (n.id.equals(snap.selfId)) continue;
            if (n.audioPort <= 0) continue;
            sendText(n.ip, n.audioPort + PORT_OFFSET, frame);
        }
    }

    /** 收到 POS-REQ：单播回当前 POS，同源 200ms 限速（协议 §13）。 */
    private void handlePosRequest(String fromIp, int fromPort) {
        GraphStore.markSeen(fromIp);
        final long now = System.currentTimeMillis();
        final Long last = posReplyAt.get(fromIp);
        if (last != null && now - last.longValue() < POS_REPLY_MIN_GAP_MS) return;
        posReplyAt.put(fromIp, Long.valueOf(now));
        sendText(fromIp, fromPort, PlacementStore.encodeFrame());
        if (posReplyAt.size() > 64) prunePosReplyMap(now);
    }

    private void prunePosReplyMap(long now) {
        final java.util.Iterator<Map.Entry<String, Long>> it = posReplyAt.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Long> e = it.next();
            if (now - e.getValue().longValue() > 10000L) it.remove();
        }
    }

    // ------------------------------------------------------------ 连接邀请

    /** 握手成功：立刻单播一次当前图谱给对方（协议 §4.1，不等 3 秒定时器）。 */
    private static void sendGraphToPeer(String ip, int audioPort) {
        final InviteListener self = instance;
        if (self == null || ip == null || ip.isEmpty()) return;
        final int port = (audioPort > 0 ? audioPort : GraphStore.AUDIO_PORT) + PORT_OFFSET;
        self.sendText(ip, port, GraphStore.encodeFrame());
    }

    /** 同意某条接入请求：回 CONN-ACK，并把对端登记进图谱（协议 §4.1）。 */
    public static void accept(int streamId) {
        Pending target = null;
        synchronized (sPending) {
            for (Pending p : sPending) {
                if (p.streamId == streamId) { target = p; break; }
            }
            if (target != null) sPending.remove(target);
        }
        if (target == null) return;
        final InviteListener self = instance;
        if (self != null) {
            self.sendText(target.ip, target.port, "CONN-ACK|" + selfName + "|" + streamId + "\n");
        }
        // 握手成功 = 对端入图谱；kind 先 other，等对端自己的 GRAPH 校正名字/kind
        GraphStore.upsertNode(target.ip,
                target.audioPort > 0 ? target.audioPort : GraphStore.AUDIO_PORT,
                target.name, GraphStore.KIND_OTHER);
        // 立刻单播当前图谱（新设备马上知道真正的 hub，不等 3 秒定时器）
        sendGraphToPeer(target.ip, target.audioPort);
        broadcastGraph();
    }

    /** 拒绝某条接入请求：回 CONN-NAK。 */
    public static void reject(int streamId) {
        Pending target = null;
        synchronized (sPending) {
            for (Pending p : sPending) {
                if (p.streamId == streamId) { target = p; break; }
            }
            if (target != null) sPending.remove(target);
        }
        if (target == null) return;
        final InviteListener self = instance;
        if (self != null) {
            self.sendText(target.ip, target.port, "CONN-NAK|" + selfName + "|" + streamId + "\n");
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ 接收循环

    private void loop() {
        final byte[] buf = new byte[1024];
        while (running) {
            final DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            final int n;
            try {
                socket.receive(pkt);
                n = pkt.getLength();
            } catch (java.net.SocketTimeoutException ignored) {
                tick();
                continue;
            } catch (Exception e) {
                if (!running) break;
                tick();
                continue;
            }
            tick();

            final String line = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            final String[] parts = line.split("\\|", -1);
            if (parts.length == 0) continue;

            final String fromIp = pkt.getAddress().getHostAddress();
            final int fromPort = pkt.getPort();

            if ("CONN-INVITE".equals(parts[0])) {
                // CONN-INVITE|<名字>|<ip:音频口>|<序号>
                String ip = fromIp;
                int port = 0;
                if (parts.length >= 3 && parts[2].contains(":")) {
                    final int colon = parts[2].lastIndexOf(':');
                    ip = parts[2].substring(0, colon);
                    port = parseInt(parts[2].substring(colon + 1), 0);
                }
                sInviteName = parts.length >= 2 ? parts[1] : ip;
                sInviteIp = ip;
                sInvitePort = port;
                sInvitePending = true;
            } else if ("ROLE".equals(parts[0])) {
                // ROLE|hub   或   ROLE|source|ip:port
                if (parts.length >= 2 && "hub".equals(parts[1])) {
                    sRoleCommand = "hub";
                    // 该对端随后发来的 CONN-REQ 自动放行（60 秒兼容窗口）
                    autoAcceptIp = fromIp;
                    autoAcceptUntil = System.currentTimeMillis() + 60000L;
                } else if (parts.length >= 3 && "source".equals(parts[1])
                        && parts[2].contains(":")) {
                    final int c = parts[2].lastIndexOf(':');
                    sRoleCommand = "source|" + parts[2].substring(0, c) + "|"
                            + parts[2].substring(c + 1);
                }
            } else if ("GRAPH-REQ".equals(parts[0])) {
                handleGraphRequest(fromIp, fromPort);
            } else if (line.startsWith("GRAPH|")) {
                handleGraph(line, fromIp);
            } else if ("POS-REQ".equals(parts[0])) {
                handlePosRequest(fromIp, fromPort);
            } else if (line.startsWith("POS|")) {
                handlePos(line, fromIp);
            } else if ("CONN-REQ".equals(parts[0]) && parts.length >= 3) {
                // 别的设备要把声音送到本机。
                final int streamId = parseInt(parts[2], -1);
                if (streamId < 0) continue;
                final String peerName = parts.length >= 2 && !parts[1].isEmpty()
                        ? parts[1] : fromIp;
                final int peerAudioPort = parts.length >= 4
                        ? parseInt(parts[3], GraphStore.AUDIO_PORT) : GraphStore.AUDIO_PORT;

                // 协议 §5（新版）：图谱成员自动放行 —— 不论本机是不是 hub。
                // hub 可能刚切到手机上、发现也不可靠，若按角色拒绝会挡住新设备入网。
                // 非成员沿用 auto_accept 语义（ROLE|hub 兼容窗口；否则交界面待确认）。
                final boolean member = GraphStore.hasNodeIp(fromIp);
                final boolean autoAccept = shouldAutoAccept(fromIp);
                if (member || autoAccept) {
                    sendText(fromIp, fromPort, "CONN-ACK|" + selfName + "|" + streamId + "\n");
                    // 握手成功：对端入图谱（协议 §4.1，不 bump version）
                    if (GraphStore.upsertNode(fromIp,
                            peerAudioPort > 0 ? peerAudioPort : GraphStore.AUDIO_PORT,
                            peerName, GraphStore.KIND_OTHER)) {
                        sendBroadcastNow();
                    }
                    // 立刻单播当前图谱：新设备马上知道真正的 hub，音源由 reconcile 自动改投
                    sendGraphToPeer(fromIp, peerAudioPort);
                    continue;
                }
                // 非成员：交给界面确认（旧版 PC 没有图谱时的待确认流程）
                synchronized (sPending) {
                    boolean dup = false;
                    for (Pending p : sPending) {
                        if (p.streamId == streamId && p.ip.equals(fromIp)) { dup = true; break; }
                    }
                    if (!dup) {
                        sPending.add(new Pending(streamId, peerName, fromIp, fromPort,
                                peerAudioPort > 0 ? peerAudioPort : GraphStore.AUDIO_PORT));
                    }
                }
            }
        }
    }
}