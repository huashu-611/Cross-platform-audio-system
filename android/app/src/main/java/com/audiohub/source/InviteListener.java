package com.audiohub.source;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * 常驻的握手监听器：始终占着"音频口 + 2"，处理两类帧。
 *
 * 为什么必须常驻：之前这个口是在 ReceiverService 启动时才绑定的，
 * 于是手机停在连接界面（没开接收服务）时，47002 上根本没人收 ——
 * 电脑发来的 CONN-INVITE 被静默丢弃，表现为"电脑邀请手机，手机没反应"。
 *
 * 处理：
 *   CONN-INVITE|<对端名>|<对端ip:音频口>|<序号>  → 交给界面弹窗确认
 *   CONN-REQ|<发起方名>|<streamId>              → 本机当汇聚端，回 CONN-ACK
 */
public final class InviteListener {

    public static final int PORT_OFFSET = 2;

    // 界面轮询的快照
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
            socket.bind(new java.net.InetSocketAddress(audioPort + PORT_OFFSET));
            socket.setSoTimeout(300);
        } catch (Exception e) {
            socket = null;
            return false;
        }
        running = true;
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

    private void loop() {
        final byte[] buf = new byte[1024];
        while (running) {
            final DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            final int n;
            try {
                socket.receive(pkt);
                n = pkt.getLength();
            } catch (java.net.SocketTimeoutException ignored) {
                continue;
            } catch (Exception e) {
                if (!running) break;
                continue;
            }

            final String line = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            final String[] parts = line.split("\\|");
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
                    try {
                        port = Integer.parseInt(parts[2].substring(colon + 1));
                    } catch (NumberFormatException ignored) {
                        port = 0;
                    }
                }
                sInviteName = parts.length >= 2 ? parts[1] : ip;
                sInviteIp = ip;
                sInvitePort = port;
                sInvitePending = true;
            } else if ("CONN-REQ".equals(parts[0]) && parts.length >= 3) {
                // 别的设备要把声音送到本机：本机当汇聚端，回 ACK
                final String ack = "CONN-ACK|" + selfName + "|" + parts[2] + "\n";
                try {
                    final byte[] data = ack.getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(data, data.length,
                            InetAddress.getByName(fromIp), fromPort));
                } catch (Exception ignored) {
                }
            }
        }
    }
}
