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
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
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
import java.nio.charset.StandardCharsets;

/**
 * 前台的采集服务。
 *
 * 生命周期里有两个容易踩的坑：
 *
 * 1. Android 14 起，<b>必须先 startForeground 并带上 mediaProjection 类型</b>，
 *    之后才能调 getMediaProjection()。顺序反了会抛 SecurityException。
 *
 * 2. AudioPlaybackCapture 每次会话都要用户授权（MediaProjection），
 *    Android 14 不再支持"一次授权长期有效"。
 */
public class CaptureService extends Service {

    private static final String TAG = "AudioHubCapture";

    public static final String ACTION_START = "com.audiohub.source.action.START";
    public static final String ACTION_STOP = "com.audiohub.source.action.STOP";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_STREAM_ID = "streamId";

    private static final String CHANNEL_ID = "audiohub_capture";
    private static final int NOTIFICATION_ID = 42;

    // ---- 给界面轮询的静态快照。不持有 Activity 引用，避免内存泄漏。----
    public static volatile boolean sRunning = false;
    public static volatile String sStatus = "未启动";
    public static volatile long sPackets = 0;
    public static volatile int sLevelDbfs = -120;
    public static volatile double sKbps = 0;

    // ---- 当前采集目标。MainActivity 的 400ms reconcile 拿它和图谱比对：
    // 目标 IP / 端口 / streamId 任何一个变了，就用已保存的 MediaProjection 授权重启。----
    public static volatile String sHost = "";
    public static volatile int sPort = 0;
    public static volatile int sStreamId = 0;

    // ---- 连接后静音（用户 2026-10-27 定稿）：手机作音源时静音媒体音量，只让汇聚端出声。
    // 停止/异常/线程退出都恢复。另带自愈：若这台机器上"静音"真的把采集掐了，自动取消静音。
    // 阈值与时长：电平 >-60 dBFS 视为有信号，<-100 dBFS 视为静音，持续 3 s 触发自愈。----
    private static final int MUTE_SIGNAL_DBFS = -60;
    private static final int MUTE_SILENT_DBFS = -100;
    private static final long MUTE_HEAL_SILENCE_MS = 3000L;

    // ---- 停止/退出时立刻断开（协议 §5 CONN-BYE）：让汇聚端马上摘掉本机这一路流和会话，
    // 不用等 5 秒音频超时才掉流。以前一个 BYE 都不发，用户报的就是
    // "手机端退出后，桌面端不会同步退出，重新邀请也没有响应"。----
    private static final int BYE_SENDS = 3;
    private static final long BYE_GAP_MS = 50L;

    private AudioManager audioManager;
    private int savedMusicVolume = -1;          // -1 = 没有保存（当前音量不是我们静音的）
    private volatile boolean mutedByUs = false;
    private boolean healWarned = false;         // 自愈告警只打一次（采集线程读写）
    private boolean hadSignalLevel = false;     // 静音后是否见过信号（采集线程读写）
    private long silentSinceElapsed = -1;       // 连续静音的起点（elapsedRealtime）

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private AudioRecord record;
    private Thread worker;
    private volatile boolean running;
    private DatagramSocket socket;
    private InetAddress address;
    private int port;
    private int streamId;
    /** CONN-BYE 只发一次；开始新会话时复位（见 onStartCommand）。 */
    private volatile boolean byeSent;

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        // 顺序不能变：先进入前台（带类型），再取 MediaProjection。
        startForegroundNotification();

        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        final Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        final String host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, 47000);
        streamId = intent.getIntExtra(EXTRA_STREAM_ID, 1);

        // 幂等：400ms reconcile 可能重复下发同一个 START。
        // 目标完全一样 → 直接忽略；目标变了（切换汇聚端 / streamId 变化）→ 先收尾再用
        // 调用方保存的授权重建，避免两个 AudioRecord / 两个 socket 同时存在。
        if (running || sRunning) {
            if (host != null && host.equals(sHost)
                    && port == sPort && streamId == sStreamId) {
                return START_NOT_STICKY;
            }
            stopCapture();
        }

        // 新会话：允许本次会话结束时再发 CONN-BYE（上一次会话的 BYE 已在 stopCapture 发过）
        byeSent = false;

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            fail("没有拿到屏幕/音频采集授权");
            return START_NOT_STICKY;
        }
        if (host == null || host.isEmpty()) {
            fail("没有填写汇聚端地址");
            return START_NOT_STICKY;
        }

        try {
            address = InetAddress.getByName(host);
        } catch (Exception e) {
            fail("解析地址失败: " + e.getMessage());
            return START_NOT_STICKY;
        }

        try {
            socket = new DatagramSocket();
            socket.setSendBufferSize(1 << 20);
        } catch (Exception e) {
            fail("创建 UDP socket 失败: " + e.getMessage());
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = manager.getMediaProjection(resultCode, resultData);
        } catch (Throwable t) {
            // 切换汇聚端时复用上次授权：Android 14 起部分 ROM 会在这里抛
            // SecurityException（一个授权令牌不能重开两路采集）。兜住并让用户重新授权，
            // 否则这里会直接崩进程。
            fail("复用采集授权失败，请重新授权: " + t.getClass().getSimpleName());
            return START_NOT_STICKY;
        }
        if (projection == null) {
            fail("获取 MediaProjection 失败（可能授权已过期，请重新授权）");
            return START_NOT_STICKY;
        }

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // 用户在系统通知里点了"停止共享"
                stopCapture();
                stopSelf();
            }
        };
        projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));

        if (!startRecording()) {
            return START_NOT_STICKY;
        }

        // 开始采集后立刻静音媒体音量：声音只从汇聚端耳机出（用户定稿）。
        muteMediaVolume();

        acquireLocks();
        sRunning = true;
        sHost = host;
        sPort = port;
        sStreamId = streamId;
        sPackets = 0;
        sStatus = "已连接 " + host + ":" + port + " · 通道 " + streamId;
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ 采集

    private boolean startRecording() {
        int minBytes = AudioRecord.getMinBufferSize(
                PacketProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            fail("设备不支持 48 kHz 双声道采集");
            return false;
        }
        // 4 包以上的缓冲：太小会在系统调度抖动时丢采样，太大则平白增加延迟。
        int bufferBytes = Math.max(minBytes * 2, PacketProtocol.PAYLOAD_BYTES * 4);

        AudioPlaybackCaptureConfiguration config;
        try {
            config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    // 只抓这三类。注意不能和 excludeUsage 混用。
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
        } catch (Exception e) {
            fail("构建采集配置失败: " + e.getMessage());
            return false;
        }

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PacketProtocol.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        try {
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
        } catch (Exception e) {
            fail("创建 AudioRecord 失败: " + e.getMessage());
            return false;
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            fail("AudioRecord 初始化失败（可能被系统的通话/录音占用）");
            record.release();
            record = null;
            return false;
        }

        record.startRecording();
        running = true;
        // 用匿名内部类而不是方法引用：android.jar 里没有
        // java.lang.invoke.LambdaMetafactory，lambda/方法引用在 javac 阶段就编不过。
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workerLoop();
            }
        }, "audiohub-capture");
        worker.start();
        return true;
    }

    /**
     * 采集线程：按 5 ms 一包取数并发送。
     *
     * 用阻塞读而不是回调：read() 凑够 240 帧才返回，天然就是 5 ms 的节拍；
     * 而回调模式下数据是系统按自己的周期送来的，反而要额外做重分块。
     */
    private void workerLoop() {
        final short[] block = new short[PacketProtocol.FRAMES_PER_PACKET * PacketProtocol.CHANNELS];
        final byte[] packet = new byte[PacketProtocol.PACKET_BYTES];
        final DatagramPacket datagram =
                new DatagramPacket(packet, packet.length, address, port);

        final long t0 = SystemClock.elapsedRealtime();
        int seq = 0;
        int samplePos = 0;
        int fill = 0;  // 已填充的 short 数
        long nextSend = SystemClock.elapsedRealtime();
        long levelWindowStart = nextSend;
        long levelPackets = 0;
        double levelSum = 0;

        while (running) {
            final int read = record.read(block, fill, block.length - fill, AudioRecord.READ_BLOCKING);
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                    sStatus = "采集中断（错误码 " + read + "）";
                    break;
                }
                continue;
            }
            fill += read;
            if (fill < block.length) continue;  // 还没凑满一包

            // 电平：给界面看"到底抓到声音没有"
            for (int i = 0; i < block.length; ++i) {
                final double v = block[i] / 32768.0;
                levelSum += v * v;
            }
            levelPackets++;

            PacketProtocol.writeHeader(packet, streamId, seq++, samplePos,
                    (int) (SystemClock.elapsedRealtime() - t0));
            PacketProtocol.writePayload(packet, PacketProtocol.HEADER_BYTES, block, block.length);

            try {
                socket.send(datagram);
                sPackets++;
            } catch (Exception e) {
                sStatus = "发送失败: " + e.getMessage();
            }

            samplePos += PacketProtocol.FRAMES_PER_PACKET;
            fill = 0;

            // 节拍：平均严格 5 ms 一包。落后超过 100 ms 就重新对时，
            // 避免长时间卡顿后"补课"式地连发几十个包。
            nextSend += PacketProtocol.PACKET_INTERVAL_MS;
            final long now = SystemClock.elapsedRealtime();
            if (nextSend > now) {
                SystemClock.sleep(nextSend - now);
            } else if (now - nextSend > 100) {
                nextSend = now;
            }

            if (now - levelWindowStart >= 1000) {
                final double mean = levelPackets > 0 ? levelSum / (levelPackets * block.length) : 0;
                sLevelDbfs = mean > 0 ? (int) Math.round(10 * Math.log10(mean)) : -120;
                sKbps = sPackets * PacketProtocol.PACKET_BYTES * 8.0 / 1000.0
                        / ((now - t0) / 1000.0);
                // 静音自愈：静音后若采集电平持续静音，说明静音掐掉了采集，自动取消静音。
                maybeHealMute(sLevelDbfs);
                levelSum = 0;
                levelPackets = 0;
                levelWindowStart = now;
            }
        }

        // 采集线程自己退出（AudioRecord 错误等）：也要恢复音量，不能把用户手机留在静音。
        sRunning = false;
        restoreMediaVolume();
        // 采集结束（错误码 / 被系统掐断）：先发 CONN-BYE 让桌面立刻掉流，再停服务释放前台通知。
        // onDestroy 还会再走一遍 stopCapture，sendByeBestEffort 有幂等保护，不会重复发。
        sendByeBestEffort();
        stopSelf();
    }

    // ------------------------------------------------------------ 静音与自愈

    /**
     * 开始采集后静音媒体音量（用户定稿：连接后直接静音，只有汇聚端的耳机出声）。
     *
     * 只接管"当前不是静音"的情况：本来就是 0，说明用户自己静音了，不接管，停止时也不弹回来。
     * 部分机型/勿扰模式会拒绝改音量（SecurityException），失败只记日志，绝不影响采集。
     */
    private synchronized void muteMediaVolume() {
        if (mutedByUs) return;
        healWarned = false;
        hadSignalLevel = false;
        silentSinceElapsed = -1;
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            final int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (current <= 0) {
                Log.i(TAG, "媒体音量本来就是 0，不接管（停止采集时不恢复）");
                return;
            }
            savedMusicVolume = current;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            mutedByUs = true;
            Log.i(TAG, "已静音媒体音量（原音量 " + current + "/"
                    + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    + "，声音只从汇聚端播放）");
        } catch (Exception e) {
            mutedByUs = false;
            savedMusicVolume = -1;
            Log.w(TAG, "静音媒体音量失败（不影响采集）: " + e.getMessage());
        }
    }

    /**
     * 恢复采集前的媒体音量。停止采集/服务销毁/异常退出/采集线程结束/自愈都走这里。
     * 若用户在静音期间自己把音量调回去了，保留用户的值，不再覆盖（"只在采集起停时动"）。
     */
    private synchronized void restoreMediaVolume() {
        if (!mutedByUs) return;
        mutedByUs = false;
        final int restore = savedMusicVolume;
        savedMusicVolume = -1;
        if (restore <= 0) return;
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                Log.i(TAG, "媒体音量已被用户改动，保留当前值");
                return;
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0);
            Log.i(TAG, "已恢复媒体音量 " + restore);
        } catch (Exception e) {
            Log.w(TAG, "恢复媒体音量失败: " + e.getMessage());
        }
    }

    /**
     * 静音自愈（采集线程每个 1 秒电平窗口调用一次）。
     *
     * Windows 上验证过"端点静音不影响 WASAPI 回环采集"；Android 的 AudioPlaybackCapture
     * 理论上也不受媒体音量影响，但没实测。所以留一个安全阀：静音后若采集电平持续静音，
     * 自动取消静音并 log 一次警告，避免把整个流变成静音。
     *
     * 触发条件：静音电平持续 ≥3 秒 **且** 系统确实在放声音（isMusicActive，说明有内容要被采集
     * 却抓不到信号）。额外用 isMusicActive 做闸门是必要的：只看"由有信号变为静音"的话，
     * 用户暂停播放 3 秒就会把音量弹回来，之后恢复播放手机又出声，违背"连接后保持静音"；
     * 这个闸门也顺带兜住了"静音从一开始就掐断采集"（启动路径上永远见不到"有信号"）。
     */
    private void maybeHealMute(int levelDbfs) {
        if (!mutedByUs) return;
        if (levelDbfs > MUTE_SILENT_DBFS) {
            if (levelDbfs > MUTE_SIGNAL_DBFS) hadSignalLevel = true;
            silentSinceElapsed = -1;   // 不静音了，清掉计时
            return;
        }
        final boolean musicActive = isMusicActive();
        if (!musicActive) return;      // 系统没在放声音：静音是正常状态，不要弹回音量
        final long now = SystemClock.elapsedRealtime();
        if (silentSinceElapsed < 0) {
            silentSinceElapsed = now;
            return;
        }
        if (now - silentSinceElapsed < MUTE_HEAL_SILENCE_MS) return;
        if (!healWarned) {
            healWarned = true;
            Log.w(TAG, "静音自愈：静音后采集电平持续 " + MUTE_HEAL_SILENCE_MS + " ms 静音（"
                    + levelDbfs + " dBFS，musicActive=" + musicActive
                    + "，hadSignal=" + hadSignalLevel
                    + "），自动取消静音，避免把流变成静音");
        }
        restoreMediaVolume();
    }

    /** 系统当前是否有音乐类播放（自愈用：有播放却抓不到信号 = 采集可能被静音掐了）。 */
    private boolean isMusicActive() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            return audioManager != null && audioManager.isMusicActive();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 收尾

    private void stopCapture() {
        // 先尽力让汇聚端立刻摘掉本机这一路流（协议 §5 CONN-BYE）：用户停止/采集结束/
        // 异常/服务销毁都走这里；此时 socket 还开着、streamId 还在，发出去才有效。
        // 不发的话桌面端要等 5 秒音频超时才掉流，用户看到的就是"手机退了桌面还显示在线"。
        sendByeBestEffort();
        // 恢复音量：任何结束路径（停止/失败/销毁/授权被系统撤销）都走这里。
        restoreMediaVolume();
        running = false;
        sRunning = false;
        sHost = "";
        sPort = 0;
        sStreamId = 0;

        if (worker != null) {
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            record.release();
            record = null;
        }
        if (projection != null) {
            try {
                if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        releaseLocks();
        sStatus = "已停止";
    }

    private void fail(String message) {
        Log.e(TAG, message);
        sStatus = message;
        sRunning = false;
        stopCapture();
        stopSelf();
    }

    /**
     * best-effort 发 2~3 次 CONN-BYE（协议 §5），让汇聚端立刻摘掉本机这一路流和会话。
     *
     * 为什么连发：UDP 会丢包，BYE 丢了就要等桌面端 5 秒音频超时才掉流；连发 3 次的成本可忽略。
     * 为什么在 stopCapture 最前面调：BYE 要发到 host 的握手口（音频口 + InviteListener.PORT_OFFSET），
     * 复用采集 socket（未 connect，可发任意地址），所以必须在 socket.close() 之前发。
     * 幂等：同一次会话只发一轮；发送失败（网络已断）只记日志，绝不阻断本地清理。
     */
    private void sendByeBestEffort() {
        if (byeSent) return;
        final DatagramSocket s = socket;
        final InetAddress host = address;
        final int hubPort = port;
        final int sid = streamId;
        if (s == null || host == null || hubPort <= 0 || sid <= 0) return;
        byeSent = true;
        final int targetPort = hubPort + InviteListener.PORT_OFFSET;
        final byte[] data = ("CONN-BYE|" + sid + "\n").getBytes(StandardCharsets.UTF_8);
        final DatagramPacket pkt = new DatagramPacket(data, data.length, host, targetPort);
        for (int i = 0; i < BYE_SENDS; ++i) {
            try {
                s.send(pkt);
            } catch (Exception ignored) {
                // UDP 无连接：发不出去说明网已经断了，桌面会按超时处理
            }
            if (i + 1 < BYE_SENDS) SystemClock.sleep(BYE_GAP_MS);
        }
        Log.i(TAG, "已发送 CONN-BYE（流 " + sid + " → " + host.getHostAddress() + ":" + targetPort + "）");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户在最近任务里把 App 划掉：系统会调这里（前台采集服务还在跑时）。
        // 立刻走正常停止路径（发 CONN-BYE + 恢复媒体音量 + 松开锁），再 stopSelf 去掉前台通知。
        // 以前这里没处理：前台服务会一直活着继续推流 —— 桌面端就"永远显示手机在线"、
        // 重新邀请也没有任何响应（手机端进程还在但已经没人应答握手）。
        Log.i(TAG, "任务被移除：停止采集并断开连接");
        stopCapture();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 前台与锁

    private void startForegroundNotification() {
        final NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "音频采集", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("采集系统播放音频并发送到汇聚端");
            nm.createNotificationChannel(channel);
        }

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AudioHub 正在采集")
                .setContentText("声音将发送到汇聚端")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();

        // 第三个参数：必须声明 mediaProjection 类型（Android 14 的硬性要求）
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    /**
     * 省电策略是这类应用最大的敌人：Doze 一上来就把 WiFi 降频，
     * 延迟会从几十毫秒跳到几百毫秒，而且表现为"偶发"，极难复现。
     */
    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "audiohub:capture");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "获取 WifiLock 失败: " + e.getMessage());
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiohub:capture");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "获取 WakeLock 失败: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        wifiLock = null;
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }
}
