package com.audiohub.source;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接收方模式：手机作为汇聚端，收 UDP 音频并用扬声器播放。
 *
 * 音频流向固定是"音源 → 汇聚端"。手机在这里只做汇聚端；
 * 连接可以由手机发起（发 CONN-REQ）也可以由 PC 发起（发 CONN-INVITE），
 * 两条路径最终都汇到同一条握手流程。
 */
public class ReceiverService extends Service {

    private static final String TAG = "AudioHubReceiver";

    public static final String ACTION_START = "com.audiohub.source.receiver.START";
    public static final String ACTION_STOP = "com.audiohub.source.receiver.STOP";

    /** PC 发来的邀请：name = PC 名，host = PC 的 ip:audio_port */
    public static final String EXTRA_INVITE_NAME = "invite_name";
    public static final String EXTRA_INVITE_HOST = "invite_host";
    public static final String EXTRA_INVITE_PORT = "invite_port";

    public static final String EXTRA_PORT = "port";

    private static final String CHANNEL_ID = "audiohub_receiver";
    private static final int NOTIFICATION_ID = 43;

    // ---- 界面轮询的静态快照 ----
    public static volatile boolean sRunning = false;
    public static volatile String sStatus = "未启动";
    public static volatile double sBusLevel = -120.0;
    public static volatile int sOccupancyMs = 0;
    public static volatile int sStreamCount = 0;

    // 收到的连接邀请（界面轮询后弹窗）
    public static volatile boolean sInvitePending = false;
    public static volatile String sInviteName = "";
    public static volatile String sInviteIp = "";
    public static volatile int sInvitePort = 0;

    private DatagramSocket dataSocket;
    private AudioTrack track;
    private final Map<Integer, JitterBuffer> streams = new ConcurrentHashMap<>();
    private final Map<Integer, Float> gains = new ConcurrentHashMap<>();
    private Thread recvThread;
    private Thread playThread;
    private volatile boolean running;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private int port = 47000;
    private final Map<String, byte[]> pendingInvites = new ConcurrentHashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ReceiverService.ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        // Android 12+ 对前台服务启动有严格限制，Android 14 对
        // foregroundServiceType 也有要求；任何一项不满足都会抛异常
        // 直接杀进程（表现就是"闪退"）。这里兜住并给出可读原因。
        try {
            // Android 12+ 对前台服务启动有严格限制，Android 14 对
        // foregroundServiceType 也有要求；任何一项不满足都会抛异常
        // 直接杀进程（表现就是"闪退"）。这里兜住并给出可读原因。
        try {
            startForegroundNotification();
        } catch (Throwable t) {
            android.util.Log.e("AudioHubReceiver", "startForeground 失败: "
                    + android.util.Log.getStackTraceString(t));
            sStatus = "无法启动前台服务: " + t.getClass().getSimpleName();
            stopSelf();
            return START_NOT_STICKY;
        }
        } catch (Throwable t) {
            android.util.Log.e("AudioHubReceiver", "startForeground 失败: "
                    + android.util.Log.getStackTraceString(t));
            sStatus = "无法启动前台服务: " + t.getClass().getSimpleName();
            stopSelf();
            return START_NOT_STICKY;
        }

        port = intent.getIntExtra(EXTRA_PORT, 47000);

        try {
            dataSocket = new DatagramSocket(null);
            dataSocket.bind(new java.net.InetSocketAddress(port));
            dataSocket.setReceiveBufferSize(1 << 20);
            dataSocket.setSoTimeout(50);

            // 握手口（port+2）交给 InviteListener 常驻监听。
            // 这里再绑一次会冲突，而且会导致"没开接收服务时收不到邀请"。
        } catch (Exception e) {
            fail("端口被占用: " + e.getMessage());
            return START_NOT_STICKY;
        }

        // 每路一条抖动缓冲：要做"点某个球调它的音量"，
        // 就必须知道每路各自的声音，混在一起之后再想分开是不可能的。
        streams.clear();

        if (!startPlayback()) return START_NOT_STICKY;

        acquireLocks();
        running = true;
        sRunning = true;
        sStatus = "等待音源接入 (端口 " + port + ")";

        recvThread = new Thread(new Runnable() {
            @Override
            public void run() { recvLoop(); }
        }, "audiohub-recv");
        playThread = new Thread(new Runnable() {
            @Override
            public void run() { playLoop(); }
        }, "audiohub-play");
        recvThread.start();
        playThread.start();
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ 播放

    private boolean startPlayback() {
        final int minBuf = AudioTrack.getMinBufferSize(
                PacketProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            fail("设备不支持 48kHz 双声道播放");
            return false;
        }
        final int bufBytes = Math.max(minBuf * 2, PacketProtocol.PAYLOAD_BYTES * 6);

        AudioTrack t;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PacketProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            t = new AudioTrack(attrs, fmt, bufBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        } catch (Exception e) {
            fail("创建 AudioTrack 失败: " + e.getMessage());
            return false;
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            fail("AudioTrack 初始化失败");
            t.release();
            return false;
        }
        t.play();
        track = t;
        return true;
    }

    /** 收包线程：UDP → 抖动缓冲。 */
    private void recvLoop() {
        final byte[] buf = new byte[2048];
        final short[] scratch = new short[PacketProtocol.FRAMES_PER_PACKET
                * PacketProtocol.CHANNELS];
        while (running) {
            final int n;
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                dataSocket.receive(pkt);
                n = pkt.getLength();
            } catch (Exception e) {
                continue;
            }
            if (n < PacketProtocol.HEADER_BYTES) continue;

            final int version = (buf[0] & 0xF0) >> 4;
            if (version != PacketProtocol.VERSION) continue;
            final int payload = n - PacketProtocol.HEADER_BYTES;
            if (payload == 0 || payload % (PacketProtocol.CHANNELS * 2) != 0) continue;
            final int frames = payload / (PacketProtocol.CHANNELS * 2);
            final int streamId = (buf[2] & 0xFF) | ((buf[3] & 0xFF) << 8);
            final long samplePos = readU32(buf, 8);

            // 把小端 int16 转成 short[]
            for (int i = 0; i < frames * PacketProtocol.CHANNELS; ++i) {
                final int off = PacketProtocol.HEADER_BYTES + i * 2;
                scratch[i] = (short) ((buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8));
            }
            JitterBuffer jb = streams.get(streamId);
            if (jb == null) {
                jb = new JitterBuffer(2, 48000 / 4);   // 250ms 容量
                streams.put(streamId, jb);
                gains.putIfAbsent(streamId, 1.0f);
                sStreamCount = streams.size();
            }
            jb.push(samplePos, scratch, frames);
        }
    }

    /** 播放线程：把每路按各自增益混音后喂 AudioTrack。 */
    private void playLoop() {
        final int frames = PacketProtocol.FRAMES_PER_PACKET;   // 240 帧 = 5ms
        final short[] block = new short[frames * PacketProtocol.CHANNELS];
        final float[] mix = new float[frames * PacketProtocol.CHANNELS];

        // 先缓冲一小段再开播，避免一上来就欠载
        long waitStart = SystemClock.elapsedRealtime();
        while (running && streams.isEmpty()
                && SystemClock.elapsedRealtime() - waitStart < 3000) {
            SystemClock.sleep(5);
        }


        double levelSum = 0;
        long levelFrames = 0;
        long levelStart = SystemClock.elapsedRealtime();

        while (running) {
            java.util.Arrays.fill(mix, 0f);
            int active = 0;
            for (Map.Entry<Integer, JitterBuffer> e : streams.entrySet()) {
                final JitterBuffer jb = e.getValue();
                if (jb.occupancyFrames() < frames) continue;   // 这路还没攒够，跳过
                jb.pull(block, frames);
                final float g = gains.getOrDefault(e.getKey(), 1.0f);
                for (int i = 0; i < block.length; ++i) mix[i] += block[i] * g;
                active++;
            }
            sStreamCount = streams.size();

            // 软限幅，防止多路叠加爆音
            for (int i = 0; i < mix.length; ++i) {
                float v = mix[i];
                if (v > 0.85f) v = 0.85f - (1f / (1f + (v - 0.85f) * 8f)) * 0.15f;
                else if (v < -0.85f) v = -0.85f + (1f / (1f + (-v - 0.85f) * 8f)) * 0.15f;
                mix[i] = v;
            }

            if (track != null) {
                final short[] out = new short[mix.length];
                for (int i = 0; i < mix.length; ++i) {
                    out[i] = (short) Math.max(-32768, Math.min(32767, mix[i] * 32767f));
                }
                track.write(out, 0, out.length);
            }

            for (int i = 0; i < mix.length; ++i) levelSum += mix[i] * mix[i];
            levelFrames += frames;

            final long now = SystemClock.elapsedRealtime();
            if (now - levelStart >= 500) {
                final double mean = levelFrames > 0 ? levelSum / levelFrames : 0;
                sBusLevel = mean > 0 ? 10 * Math.log10(mean) : -120.0;
                int occ = 0;
                for (JitterBuffer jb : streams.values()) occ += jb.occupancyFrames();
                sOccupancyMs = streams.isEmpty() ? 0 : occ / streams.size() / 48;
                levelSum = 0;
                levelFrames = 0;
                levelStart = now;
            }
        }
    }

    // ------------------------------------------------------------ 收尾

    /** 供界面实时调某一路的音量（streamId 来自网络图上的球）。 */
    public static void setStreamGain(int streamId, float gain) {
        sGains.put(streamId, Math.max(0f, Math.min(4f, gain)));
    }

    private static final Map<Integer, Float> sGains = new ConcurrentHashMap<>();

    /**
     * 向对端发 CONN-REQ 并等待 CONN-ACK。
     * 这是"建立连接"的握手，与谁当汇聚端无关。
     *
     * @return true = 对方已确认
     */
    public static boolean respondToInvite(String pcIp, int pcAudioPort, String deviceName) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(1000);
            final String req = "CONN-REQ|" + deviceName + "|1\n";
            final byte[] data = req.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // 等 30 秒：对方要在界面上点"同意"，10 秒往往不够
            for (int i = 0; i < 30; ++i) {
                s.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName(pcIp), pcAudioPort + 2));
                byte[] rbuf = new byte[256];
                DatagramPacket r = new DatagramPacket(rbuf, rbuf.length);
                try {
                    s.receive(r);
                    if (r.getLength() >= 8
                            && new String(rbuf, 0, 8).equals("CONN-ACK")) {
                        return true;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // 继续重试
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) s.close();
        }
    }

    private void stopAll() {
        running = false;
        sRunning = false;
        streams.clear();
        sStreamCount = 0;
        if (recvThread != null) { try { recvThread.join(400); } catch (Exception ignored) {} }
        if (playThread != null) { try { playThread.join(400); } catch (Exception ignored) {} }
        recvThread = playThread = null;
        if (track != null) {
            try { track.stop(); track.release(); } catch (Exception ignored) {}
            track = null;
        }
        if (dataSocket != null) { dataSocket.close(); dataSocket = null; }
        releaseLocks();
        sStatus = "已停止";
    }

    private void fail(String message) {
        Log.e(TAG, message);
        sStatus = message;
        sRunning = false;
        stopAll();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 前台与锁

    private void startForegroundNotification() {
        final NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "音频汇聚",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("接收其它设备的声音并播放");
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AudioHub 正在接收")
                .setContentText("作为汇聚端播放收到的声音")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private void acquireLocks() {
        try {
            WifiManager wm =
                    (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "audiohub:recv");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiohub:recv");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wifiLock = null;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
