package com.audiohub.source;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 局域网设备发现：监听 PC 端的广播信标。
 *
 * 协议与 C++ 端（src/discovery.cpp）逐字节一致：
 *   AUDIOHUB|1|<role>|<audio_port>|<http_port>|<ip>|<显示名>\n
 *   role 形如 "SR"/"S-"/"-R"；显示名是最后一段，所以可以含任意字符。
 *
 * 为什么不用 NsdManager：它走 mDNS，而 PC 端实现的是自定义广播信标——
 * 两边必须说同一种话。UDP 广播在 Android 上需要 MulticastLock，
 * 否则部分机型的 WiFi 驱动会丢弃组播/广播包。
 */
public final class Discovery {

    public static final int DISCOVERY_PORT = 47300;
    static final String MAGIC = "AUDIOHUB";
    static final long PEER_TIMEOUT_MS = 5000;

    /** 一台发现的设备（通常是 PC 汇聚端）。 */
    public static final class Device {
        public String name = "";
        public String ip = "";
        public int audioPort;
        public int httpPort;
        public boolean canReceive;   // 能放音（可当汇聚端）
        public boolean canSend;      // 能采音（可当音源）
        public long lastSeenMs;

        public String roleText() {
            if (canSend && can_receive()) return "音源 / 汇聚";
            return can_receive() ? "汇聚端" : "音源";
        }
        private boolean can_receive() { return canReceive; }
    }

    public interface Listener {
        /** 设备列表变化时回调（已在后台线程，别在这里碰 UI）。 */
        void onPeersChanged(List<Device> peers);
    }

    private final Map<String, Device> peers = new ConcurrentHashMap<>();
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;
    private volatile Listener listener;

    public void setListener(Listener l) { listener = l; }

    /** 开始监听。返回 false 表示端口被占（通常同机已有实例）。 */
    public boolean start() {
        if (running) return true;
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setBroadcast(true);
            socket.setSoTimeout(500);
        } catch (Exception e) {
            lastError = "发现端口 " + DISCOVERY_PORT + " 绑定失败（可能被其它实例占用）";
            return false;
        }
        lastError = null;
        running = true;
        // 不能用方法引用：android.jar 里没有 java.lang.invoke.LambdaMetafactory，
// javac 阶段就编不过（android-native-dev/主工程注释里都记过这个坑）
        thread = new Thread(new Runnable() {
            @Override
            public void run() { loop(); }
        }, "audiohub-discovery");
        thread.start();
        return true;
    }

    /** 主动发一帧 WHO，让所有设备立刻回信标（不用等它下一次广播）。 */
    public void probe() {
        if (!running || socket == null) return;
        try {
            final byte[] data = (MAGIC + "|1|WHO\n").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                    java.net.InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
        } catch (Exception ignored) {
        }
    }

    /**
     * 单播探测指定地址。
     *
     * 办公 / 校园 WiFi 普遍开了 AP 隔离或广播过滤，广播帧到不了对端，
     * 但同一网段的**单播**通常是放行的。所以除了广播探测，还要能
     * 直接对某个已知 IP 探一下——这就是广播不可用时唯一的自动发现手段。
     */
    public void probeTo(String ip) {
        if (!running || socket == null || ip == null || ip.isEmpty()) return;
        try {
            final byte[] data = (MAGIC + "|1|WHO\n").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                    java.net.InetAddress.getByName(ip), DISCOVERY_PORT));
        } catch (Exception ignored) {
        }
    }

    /**
     * 启动失败的原因。为空表示正常。
     * 常见原因：端口被别的实例占着。
     */
    public String lastError() { return lastError; }

    private volatile String lastError;

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        peers.clear();
    }

    private void loop() {
        byte[] buf = new byte[1024];
        long lastPrune = 0;
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                final String text = new String(pkt.getData(), 0, pkt.getLength(),
                        java.nio.charset.StandardCharsets.UTF_8);
                final String fromIp = pkt.getAddress().getHostAddress();
                for (String line : text.split("\n")) {
                    handle(line.trim(), fromIp);
                }
                // 每 500ms 清一次掉线的设备并回调，避免每次收包都惊动 UI
                final long now = System.currentTimeMillis();
                if (now - lastPrune > 500) {
                    lastPrune = now;
                    prune(now);
                }
            } catch (java.net.SocketTimeoutException ignored) {
                prune(System.currentTimeMillis());
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    /** 收到探测帧：直接单播回一帧信标。 */
    private void respondToWho(String toIp) {
        try {
            final String beacon = MAGIC + "|1|SR|47000|0|" + localIp() + "|"
                    + android.os.Build.MODEL + "\n";
            final byte[] data = beacon.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final DatagramPacket pkt = new DatagramPacket(data, data.length,
                    java.net.InetAddress.getByName(toIp), DISCOVERY_PORT);
            final DatagramSocket tmp = new DatagramSocket();
            tmp.send(pkt);
            tmp.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 本机的局域网地址。
     * 优先 wlan / eth 开头的网卡，跳过回环、链路本地、VPN/TUN 段
     * （手机上装了代理 App 也会有 198.18.x 的假地址，挑错了就"扫得到连不上"）。
     */
    static String localIp() {
        String fallback = "";
        try {
            final java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                final java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                final String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                final boolean preferred = name.startsWith("wlan") || name.startsWith("eth")
                        || name.startsWith("ap") || name.startsWith("en");
                final boolean virtual = name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("rmnet") || name.startsWith("clat");

                final java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    final java.net.InetAddress a = addrs.nextElement();
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    final String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("169.254.")) continue;    // 链路本地
                    if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) continue;  // 代理 TUN
                    if (ip.startsWith("100.64.") || ip.startsWith("100.65.")) continue;  // CGNAT
                    if (virtual) continue;
                    if (preferred) return ip;
                    if (fallback.isEmpty()) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void handle(String line, String fromIp) {
        if (!line.startsWith(MAGIC)) return;
        String[] parts = line.split("\\|", -1);
        // WHO 帧只有 3 段（AUDIOHUB|1|WHO），必须在长度检查之前判断，
        // 否则探测帧会被当成残缺信标直接丢掉——PC 就永远发现不了手机。
        if (parts.length >= 3 && "WHO".equals(parts[2])) {
            respondToWho(fromIp);
            return;
        }
        if (parts.length < 7) return;

        Device d = new Device();
        d.canSend = parts[2].indexOf('S') >= 0;
        d.canReceive = parts[2].indexOf('R') >= 0;
        try {
            d.audioPort = Integer.parseInt(parts[3]);
            d.httpPort = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            return;
        }
        // 信标里的 IP 是发送方自己填的真实地址；
        // 空的话退回来源地址（可能是虚拟网卡，所以 C++ 端总是填上）
        d.ip = pickBestIp(parts[5], fromIp);
        d.name = parts[6];
        d.lastSeenMs = System.currentTimeMillis();

        peers.put(d.ip + ":" + d.audioPort, d);
    }

    /**
     * 信标里可能给了多个候选 IP（一台机器同时有 WiFi 和网线）。
     * 挑"与本机公共前缀最长"的那个——同网段的地址才连得通。
     * 全都不匹配时退回第一项（总比没有好）。
     */
    private static String pickBestIp(String list, String fallback) {
        if (list == null || list.trim().isEmpty()) return fallback;
        final String[] items = list.split(",");
        final String mine = localIp();
        String best = null;
        int bestLen = -1;
        for (String raw : items) {
            final String ip = raw.trim();
            if (ip.isEmpty()) continue;
            final int common = commonPrefixLen(ip, mine);
            if (common > bestLen) {
                bestLen = common;
                best = ip;
            }
        }
        return best != null ? best : fallback;
    }

    private static int commonPrefixLen(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        final String[] sa = a.split("\\.");
        final String[] sb = b.split("\\.");
        int n = 0;
        for (int i = 0; i < Math.min(sa.length, sb.length); ++i) {
            if (!sa[i].equals(sb[i])) break;
            n++;
        }
        return n;
    }

    private void prune(long now) {
        boolean changed = false;
        for (Map.Entry<String, Device> e : peers.entrySet()) {
            if (now - e.getValue().lastSeenMs > PEER_TIMEOUT_MS) {
                peers.remove(e.getKey());
                changed = true;
            }
        }
        if (changed || listener != null) {
            List<Device> snapshot = new ArrayList<>(peers.values());
            java.util.Collections.sort(snapshot, new java.util.Comparator<Device>() {
                @Override
                public int compare(Device a, Device b) { return a.name.compareTo(b.name); }
            });
            Listener l = listener;
            if (l != null) l.onPeersChanged(snapshot);
        }
    }

    public List<Device> peers() {
        return new ArrayList<>(peers.values());
    }
}
