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

    private static String localIp() {
        try {
            final java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                final java.util.Enumeration<java.net.InetAddress> addrs =
                        nis.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    final java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void handle(String line, String fromIp) {
        if (!line.startsWith(MAGIC)) return;
        String[] parts = line.split("\\|", -1);
        if (parts.length < 7) return;
        // 手机端不广播信标（省电），但收到 WHO 时要立刻回一帧，
        // 这样 PC 也能发现本机，不必等下个广播周期
        if ("WHO".equals(parts[2])) {
            respondToWho(fromIp);
            return;
        }

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
        d.ip = parts[5].isEmpty() ? fromIp : parts[5];
        d.name = parts[6];
        d.lastSeenMs = System.currentTimeMillis();

        peers.put(d.ip + ":" + d.audioPort, d);
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
