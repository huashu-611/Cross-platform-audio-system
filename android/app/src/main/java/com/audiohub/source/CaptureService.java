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

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private AudioRecord record;
    private Thread worker;
    private volatile boolean running;
    private DatagramSocket socket;
    private InetAddress address;
    private int port;
    private int streamId;

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
                levelSum = 0;
                levelPackets = 0;
                levelWindowStart = now;
            }
        }

        sRunning = false;
    }

    // ------------------------------------------------------------ 收尾

    private void stopCapture() {
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
