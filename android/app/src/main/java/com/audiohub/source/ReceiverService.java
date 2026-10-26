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
    // 累计欠载帧数：持续增长说明缓冲在饿（时钟偏差或丢包）
    public static volatile long sUnderrun = 0;

    // 收到的连接邀请（界面轮询后弹窗）
    public static volatile boolean sInvitePending = false;
    public static volatile String sInviteName = "";
    public static volatile String sInviteIp = "";
    public static volatile int sInvitePort = 0;

    private DatagramSocket dataSocket;
    private AudioTrack track;
    private final Map<Integer, JitterBuffer> streams = new ConcurrentHashMap<>();
    private final Map<Integer, Float> gains = new ConcurrentHashMap<>();
    // 每路一个空间化状态机（只由播放线程读写；streams 清空时一起清，见 stopAll）
    private final Map<Integer, Spatial.SpatialState> spatialStates = new ConcurrentHashMap<>();
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

        // 幂等：400ms reconcile / 角色命令可能导致同一个 START 重复下发。
        // 已经在本端口收流就直接忽略；端口变了则先彻底收尾再按新端口重建，
        // 否则 bind 失败会被 fail() 当成"端口被占用"直接 stopSelf。
        final int wantPort = intent.getIntExtra(EXTRA_PORT, 47000);
        if (running && wantPort == port) return START_NOT_STICKY;
        if (running) stopAll();

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
        spatialStates.clear();   // 状态机属于流，流没了就一起清（位置表在 stopAll 里清）
        sStreamPeerIp.clear();

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
        // 缓冲越小延迟越低，但低于设备最小值会频繁欠载。
        // 原来取 minBuf*2（相当于白送一整个最小缓冲的延迟），
        // 现在取"设备最小值"与"4 个包"的较大者，延迟能降一半。
        final int bufBytes = Math.max(minBuf, PacketProtocol.PAYLOAD_BYTES * 4);

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
            final String fromIp;
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                dataSocket.receive(pkt);
                n = pkt.getLength();
                fromIp = pkt.getAddress() != null
                        ? pkt.getAddress().getHostAddress() : "";
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

            // 协议 §8：音频包也是"这台设备活着"的证据（不必等到它的 GRAPH）
            GraphStore.markSeen(fromIp);

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
            if (!fromIp.isEmpty()) sStreamPeerIp.put(streamId, fromIp);
            jb.push(samplePos, scratch, frames);
        }
    }

    /**
     * 某一路当前的混音增益。
     *
     * 注意这里有两张表：{@link #sGains}（UI 通过 {@link #setStreamGain} 写的静态表）和
     * {@code gains}（recvLoop 建流时 putIfAbsent 的每路默认值 1.0）。716c4b4 重构两屏流程时
     * playLoop 读的是实例表、而 setStreamGain 写的是静态表，两张表断开 → 界面上拖音量对
     * 实际播放无效。这里以 UI 表优先，恢复"点球调音量"的既有语义；实例表只做默认值。
     */
    private float streamGain(int streamId) {
        final Float ui = sGains.get(streamId);
        if (ui != null) return ui.floatValue();
        return gains.getOrDefault(streamId, 1.0f);
    }

    /** 播放线程：把每路按各自增益混音后喂 AudioTrack。 */
    private void playLoop() {
        final int frames = PacketProtocol.FRAMES_PER_PACKET;   // 240 帧 = 5ms
        final short[] block = new short[frames * PacketProtocol.CHANNELS];
        final float[] mix = new float[frames * PacketProtocol.CHANNELS];
        // 3D 空间化用的复用缓冲（每块原地改写，避免 200 Hz 热路径上分配临时数组）
        final float[] spatialBuf = new float[frames * PacketProtocol.CHANNELS];
        // 听者固定在场景原点、yaw=0、pitch=0（v1.3 物理房间模型，与 PC 端 SoundStage 一致）：
        // Android 汇聚端的"听者"就是拿着手机的人，房间中心 = 场景原点，UI 只报节点世界坐标。
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final Spatial.SpatialParams spatialParams = new Spatial.SpatialParams();
        // int16 -> [-1,1]。2^-15 在 float 里是精确值，所以旧路径的标度与改动前逐位相同。
        final float norm = 1.0f / 32768.0f;

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
                final int streamId = e.getKey().intValue();
                final float g = streamGain(streamId);
                // 3D 空间化（v1.3）：这一路有位置且总开关打开时，先归一化到 [-1,1]
                // 再交给每源的 SpatialState 原地渲染。每耳增益里已经折进了用户音量 g，
                // 所以这里以 gain=1 直接相加（与 C++ MixInput.gain=1 同一约定）。
                // 没有位置 / 旁路 / 关闭时走下面的旧路径，逐样本与改动前完全一致。
                final float[] pos = sSpatialEnabled ? sStreamPositions.get(streamId) : null;
                if (pos != null) {
                    Spatial.SpatialState st = spatialStates.get(streamId);
                    if (st == null) {
                        st = new Spatial.SpatialState();
                        st.reset(Spatial.kSampleRate);
                        spatialStates.put(streamId, st);
                    }
                    // pos 是每次上报新分配的 float[3]，ConcurrentHashMap.put 发布后
                    // 播放线程读到的是一份完整快照（不会出现"x 新 z 旧"）。
                    st.setTargetPosition(pos[0], pos[1], pos[2]);
                    for (int i = 0; i < block.length; ++i) spatialBuf[i] = block[i] * norm;
                    st.process(spatialBuf, frames, listener, spatialParams, g);
                    for (int i = 0; i < block.length; ++i) mix[i] += spatialBuf[i];
                } else {
                    // ⚠ 标度：pull() 写出的是 int16 样本（-32768..32767），必须先归一化到
                    // [-1,1] 再混音。后面的软限幅（0.85 阈值）、dBFS 统计、输出换算全部
                    // 按归一化域设计，与 C++ 端一致（src/hub_core.cpp 里
                    // scratch[i] = src[i] * (1/32768) 之后才进 MixBlock）。
                    //
                    // 716c4b4 重构两屏流程时把这里的 /32768 弄丢了，
                    // 导致 mix 一直是 int16 量级：软限幅会把每个 |x|>0.85 的样本都
                    // 压成 ±1，输出就变成跟随音乐零交叉的满幅方波 —— 听感就是
                    // 很重的电流音/爆音。3ba77d9 与 40491ec 修的都是它的表面症状。
                    final float scale = g * norm;
                    for (int i = 0; i < block.length; ++i) mix[i] += block[i] * scale;
                }
                active++;
            }
            sStreamCount = streams.size();

            // 软限幅：**与 C++ 端 SoftLimitSample 完全同一个公式**。
            //   threshold + headroom * tanh((|x| - threshold) / headroom)
            //
            // 这里原来写的是 0.85 - 0.15/(1+8x)：代入 x=0 得 0.70，
            // 也就是"刚过 0.85 就被砸到 0.70"，函数在阈值处既不连续
            // 也不单调。音乐里大量样本超过 0.85，每个都被这样砍一刀，
            // 听感就是刺耳的电音 / 破音。
            // （C++ 端一直是对的，是照着移植到 Java 时写错了。）
            final float kKnee = 0.85f;
            final float kHeadroom = 1.0f - kKnee;
            for (int i = 0; i < mix.length; ++i) {
                final float x = mix[i];
                final float a = Math.abs(x);
                if (a <= kKnee) continue;      // 阈值以下原样保留
                final float shaped = kKnee + kHeadroom
                        * (float) Math.tanh((a - kKnee) / kHeadroom);
                mix[i] = x < 0 ? -shaped : shaped;
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
                long under = 0;
                for (JitterBuffer jb : streams.values()) {
                    occ += jb.occupancyFrames();
                    under += jb.underrunFrames();
                }
                sUnderrun = under;
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

    // ------------------------------------------------------------ 3D 空间化（UI → 播放线程）
    //
    // 位置按 streamId 存：UI（MainActivity/Scene3D）用
    // ReceiverService.setStreamPosition(ReceiverService.streamIdForIp(node.ip), x, y, z)
    // 上报节点在房间里的世界坐标（米，y=0）。播放线程每块取一次快照，与 C++ 端 HubCore
    // 按设备 IP 存 spatial_placements（src/hub_core.cpp SpatialPlacement）是同一套语义：
    // 有位置 -> 空间化后按 gain=1 混入；没位置 -> 旧路径 g/32768。
    //
    // 每次 set 都换一个新的 float[3] 数组：ConcurrentHashMap.put 负责安全发布，播放线程
    // 读到的要么是旧三元组、要么是新三元组，不会出现"x 新 z 旧"的半更新快照。
    private static final ConcurrentHashMap<Integer, float[]> sStreamPositions =
            new ConcurrentHashMap<>();

    /** 空间化总开关（默认开）。关闭后所有路都走旧混音路径，2D 行为逐样本不变。 */
    private static volatile boolean sSpatialEnabled = true;

    /**
     * UI 上报某一路声源的世界坐标（米）。
     *
     * @param streamId 音频流 id（用 {@link #streamIdForIp} 由节点 ip 取）；<=0 表示该设备
     *                 还没有音频流，本次上报被忽略
     * @param x        世界坐标 x（米，+X = 听者右侧）
     * @param y        世界坐标 y（米；v1.3 房间模型里恒为 0，保留是为和 C++ 三元组对齐）
     * @param z        世界坐标 z（米，+Z = 听者身后；听者面朝 -Z）
     */
    public static void setStreamPosition(int streamId, float x, float y, float z) {
        if (streamId <= 0) return;   // 还没有流的节点：等有流了再报，别留无主条目
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) return;   // NaN/Inf 直接拒绝
        // 与内核/协议同一上限（kSpatialCoordLimit=20 m）：越界截断而不是丢弃，
        // 免得 UI 一时算出的离群值把这一路永久变成"无位置"。
        final float lim = Spatial.kSpatialCoordLimit;
        sStreamPositions.put(streamId, new float[]{
                clampCoord(x, lim), clampCoord(y, lim), clampCoord(z, lim)});
    }

    /** 清掉某一路的位置（节点移出房间 / 断开 / 停止空间化）。之后该路回到旧混音路径。 */
    public static void clearStreamPosition(int streamId) {
        if (streamId <= 0) return;
        sStreamPositions.remove(streamId);
    }

    /** 空间化总开关（默认 true）。false 时所有流都走旧路径。 */
    public static void setSpatialEnabled(boolean enabled) {
        sSpatialEnabled = enabled;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clampCoord(float v, float lim) {
        return v < -lim ? -lim : (v > lim ? lim : v);
    }

    // streamId → 来源 IP。图谱界面用它把"有音频流的节点"和 streamId 对上：
    // 点某个球调音量时，必须知道这个球对应哪一路流（协议 §7.2 的 Android 版）。
    private static final Map<Integer, String> sStreamPeerIp = new ConcurrentHashMap<>();

    /** 某个 ip 当前对应的音频流 id；0 = 没有流（离线或还没收到包）。 */
    public static int streamIdForIp(String ip) {
        if (ip == null || ip.isEmpty()) return 0;
        for (Map.Entry<Integer, String> e : sStreamPeerIp.entrySet()) {
            if (ip.equals(e.getValue())) return e.getKey().intValue();
        }
        return 0;
    }

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
            // 带上自己的音频口：电脑要能反过来把汇聚端切到本机，
            // 就必须知道本机在哪个端口收流（老版本不带，对端回落到 47000）。
            final String req = "CONN-REQ|" + deviceName + "|1|" + 47000 + "\n";
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
        // 流没了，对应的空间化状态机与上报位置也一起清：别让重连后的新流
        // 沿用上一次会话的旧位置（Lead 要求：移除流时同步清理状态与位置）。
        spatialStates.clear();
        sStreamPositions.clear();
        sStreamPeerIp.clear();
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
