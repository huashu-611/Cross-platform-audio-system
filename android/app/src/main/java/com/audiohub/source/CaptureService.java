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
    /** 停采集但保留投屏会话（本机暂时被设为汇聚端时用，见 stopCaptureKeepProjection）。 */
    public static final String ACTION_PAUSE = "com.audiohub.source.action.PAUSE";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_STREAM_ID = "streamId";

    private static final String CHANNEL_ID = "audiohub_capture";
    private static final int NOTIFICATION_ID = 42;

    // ---- 给界面轮询的静态快照。不持有 Activity 引用，避免内存泄漏。----
    public static volatile boolean sRunning = false;
    /**
     * 服务里是否还留着一个可复用的 MediaProjection 会话（暂停但没销毁时有）。
     * MainActivity 用它判断"能不能不弹授权框直接恢复采集"。
     */
    public static volatile boolean sHasProjection = false;
    public static volatile String sStatus = "未启动";
    public static volatile long sPackets = 0;
    public static volatile int sLevelDbfs = -120;
    public static volatile double sKbps = 0;

    // v0.33：三个只增不减的计数，用来分开「没在发」「发了但抛异常」「压根没读到数据」。
    // 用户报「采集到的是静音」时，屏幕上必须还能看见 sPackets 和 sendTarget —— 
    // 那两个数才是判断包到底有没有出去、发错地址没有的唯一依据。以前静音提示
    // 把整行替换掉了，于是最关键的两个数被提示顶掉，只能靠猜。
    public static volatile long sSendErrors = 0;
    public static volatile String sLastSendError = "";
    public static volatile long sReadZero = 0;
    public static volatile long sReadTotal = 0;

    /**
     * 最近一次启动/运行失败的原因，MainActivity 每 400ms 轮询取走并清空。
     * 主要用途：把「采集授权已失效，请重新授权」这种必须让用户看到的信息
     * 从服务传到界面（服务里没有 Activity，不能直接弹 Toast）。
     */
    public static volatile String sLastError = null;

    /** 取走并清空最近一次错误；没有错误返回 null。 */
    public static String consumeError() {
        final String e = sLastError;
        sLastError = null;
        return e;
    }

    // ---- 当前采集目标。MainActivity 的 400ms reconcile 拿它和图谱比对：
    // 目标 IP / 端口 / streamId 任何一个变了，就用已保存的 MediaProjection 授权重启。----
    public static volatile String sHost = "";
    public static volatile int sPort = 0;
    public static volatile int sStreamId = 0;

    // ---- 媒体音量策略（v0.25 起不再主动静音）----
    // 旧版连接后把 STREAM_MUSIC 设成 0，想让声音只从汇聚端出。真机实测：部分 ROM 在
    // 媒体音量为 0 时会把 AudioPlaybackCapture 一起掐成静音（播放器音轨被一并静音），
    // 现象就是「手机在放、桌面端电平条不动、完全没声音」；模拟器 ROM 不受影响所以没暴露。
    // 静音自愈（maybeHealMute）本该 3 秒兜底，但它带 isMusicActive() 闸门，某些 ROM 上
    // 恒 false，自愈永远不触发，整条流变成永久静音。用户 2026-11-06 定稿：从头到尾不碰
    // 媒体音量 —— 手机照常出声、汇聚端也收得到，两害相权先保证有声音。
    // 下面的常量与 mute/restore/heal 方法保留作兼容兜底（mutedByUs 恒 false 时全是 no-op），
    // 这样所有收尾路径都不用跟着大改，也不会有谁再误把音量设成 0。
    // 阈值与时长：电平 >-60 dBFS 视为有信号，<-100 dBFS 视为静音，持续 3 s 触发自愈。
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
    /**
     * 本次停止是"暂停"（保留投屏会话）而不是真停。
     * 采集线程退出时会看这个标志决定要不要 stopSelf：
     * 一旦 stopSelf，onDestroy 会走 stopCapture() 把 projection 一起销毁，
     * 保留会话的努力就白费了。
     */
    private volatile boolean pausing;

    /**
     * 发送开关。暂停（本机被设为汇聚端）时置 false：采集线程继续读 AudioRecord
     * 但不再发包，切回音源时置 true 立刻恢复。
     *
     * 为什么不干脆停掉采集：AudioRecord 的 stop/release 与阻塞中的 read() 之间
     * 有竞态，反复切换会踩到 releaseBuffer: mUnreleased out of range 的原生 abort。
     * 保持"一条线程、一个 record、从头到尾"是唯一稳定的用法。
     */
    private volatile boolean sending;
    private volatile DatagramSocket socket;
    private volatile InetAddress address;
    private volatile int port;
    private volatile int streamId;
    /** CONN-BYE 只发一次；开始新会话时复位（见 onStartCommand）。 */
    private volatile boolean byeSent;

    // 最近一次 onStartCommand 的 startId：收尾时用 stopSelfResult(它)，
    // 不能用裸 stopSelf()（会把仍在路上的 startForegroundService 请求一并丢掉）。
    private volatile int pendingStartId = -1;

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * onStartCommand 运行在主线程：这里只要漏出一个未捕获异常，Android 就会把整个
     * 进程杀掉（用户看到的就是「一进控制界面就退出」）。里面的每一步都已经各自兜住
     * 了，外面再套一层总保险，保证采集起不来时只是没声音 + 一条 Toast，不会闪退。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        pendingStartId = startId;

        // ---- 前台义务：进前台必须排在**所有**分支之前 ----
        //
        // startForegroundService() 一发出，系统就开始 10 秒倒计时；到点没看到
        // startForeground() 就抛 ForegroundServiceDidNotStartInTimeException
        // 并把进程整个杀掉。这里原本只在 ACTION_START 分支里调用，于是
        // intent == null / 未知 action / ACTION_STOP 三条早退路径都留着义务洞；
        // 尤其 ACTION_STOP 的裸 stopSelf() 会把仍在路上的 startForegroundService()
        // 请求一起丢掉，那条请求的义务就永远没人履行。
        //
        // 注意与 ReceiverService 的差别：采集例外地**允许**前台起不来
        // （Android 14 复用过期授权时会抛 SecurityException），那种情况要让
        // sLastError 把原因交给界面，而不是崩进程 —— 所以这里吞掉异常，
        // 由 handleStartCommand 的 ACTION_START 分支再判一次。
        boolean fg = false;
        try {
            startForegroundNotification();
            fg = true;
        } catch (Throwable t) {
            Log.e(TAG, "进入采集前台失败: " + Log.getStackTraceString(t));
            sLastError = "采集授权已失效，请在手机上重新点「送入」";
            sStatus = sLastError;
            sRunning = false;
        }

        try {
            return handleStartCommand(intent, startId, fg);
        } catch (Throwable t) {
            Log.e(TAG, "采集启动异常: " + Log.getStackTraceString(t));
            sLastError = "采集启动失败（" + t.getClass().getSimpleName() + "），请重新点「送入」";
            sStatus = sLastError;
            sRunning = false;
            try {
                stopCapture();
            } catch (Throwable ignored) {
            }
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
    }

    private int handleStartCommand(Intent intent, int startId, boolean fg) {
        // 没有 intent 就没有活干；义务已经在 onStartCommand 顶部履行完。
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            // stopSelfResult(startId)：只在这是最后一次 start 请求时才真停，
            // 避免把仍在路上的 startForegroundService() 请求连义务一起丢掉。
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE.equals(intent.getAction())) {
            // 暂停但保住投屏会话：切回音源时能直接恢复，不用重新弹授权框。
            pausing = true;
            stopCaptureKeepProjection();
            return START_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        // 重新开始采集：无论走 retarget 还是 resume，都不再是暂停态
        pausing = false;

        // 前台已在 onStartCommand 顶部尝试过；这里只判断结果。
        // Android 14 起 startForeground 会校验进程当前是否还持有有效的采集授权，
        // 复用上一次会话的授权时会抛 SecurityException —— 那种情况要给出可读原因
        // 并停止采集，而不是留下一个没有前台的采集线程被系统回收。
        if (!fg) {
            sLastError = "采集授权已失效，请在手机上重新点「送入」";
            sStatus = sLastError;
            sRunning = false;
            stopCapture();
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        sLastError = null;

        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        final Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        final String host = intent.getStringExtra(EXTRA_HOST);
        final int wantPort = intent.getIntExtra(EXTRA_PORT, 47000);
        final int wantStreamId = intent.getIntExtra(EXTRA_STREAM_ID, 1);

        // 幂等：400ms reconcile 可能重复下发同一个 START。
        if (running || sRunning) {
            if (host != null && host.equals(sHost)
                    && wantPort == sPort && wantStreamId == sStreamId) {
                return START_NOT_STICKY;
            }
            // 目标变了（切换汇聚端 / 音源通道号变了）：只换发送目标，复用当前
            // AudioRecord 与 MediaProjection 会话。Android 14 的采集授权是一次性的，
            // 「先 stopCapture 再用旧授权重建」必然在 startForeground /
            // getMediaProjection 处被系统拒绝 —— 这正是 2026-10-28 闪退的根因。
            if (host == null || host.isEmpty()) {
                fail("没有填写汇聚端地址");
                return START_NOT_STICKY;
            }
            try {
                // 先用旧的目标地址给旧汇聚端发一次 CONN-BYE，让它立刻摘掉这一路流，
                // 否则要等 5 秒音频超时。发完再把 byeSent 复位给新会话用。
                sendByeBestEffort();
                final InetAddress nextAddress = InetAddress.getByName(host);
                final DatagramSocket nextSocket = new DatagramSocket();
                nextSocket.setSendBufferSize(1 << 20);
                final DatagramSocket oldSocket = socket;
                socket = nextSocket;
                if (oldSocket != null) oldSocket.close();
                address = nextAddress;
                port = wantPort;
                streamId = wantStreamId;
                sHost = host;
                sPort = wantPort;
                sStreamId = wantStreamId;
                // 新目标：允许本次会话结束时向新汇聚端发 CONN-BYE
                byeSent = false;
                // 从暂停态切回音源时走的就是这条 retarget 分支（running 一直是 true），
                // 所以这里必须把发送开关重新打开，否则采集线程还在读、但一个包都不发。
                sending = true;
                sRunning = true;
                sStatus = "已连接 " + host + ":" + wantPort + " · 通道 " + wantStreamId;
                Log.i(TAG, "采集目标已切换 → " + host + ":" + wantPort);
            } catch (Exception e) {
                fail("切换采集目标失败: " + e.getMessage());
            }
            return START_NOT_STICKY;
        }

        // 新会话：允许本次会话结束时再发 CONN-BYE（上一次会话的 BYE 已在 stopCapture 发过）
        port = wantPort;
        streamId = wantStreamId;
        byeSent = false;

        // 没有新授权不是错误：可能只是"暂停过"（本机曾被设为汇聚端）。
        // 见下面的 resume 分支 —— Android 14 的投屏授权一次性，切回音源时
        // 拿不到第二次授权，只能复用保留下来的投屏会话。
        final boolean haveAuth = resultCode == Activity.RESULT_OK && resultData != null;
        if (!haveAuth && projection == null) {
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

        // ---- 复用保留的采集会话恢复发送（不重新要授权）----
        if (!haveAuth) {
            if (projection == null) {
                fail("没有拿到屏幕/音频采集授权");
                return START_NOT_STICKY;
            }
            // 暂停期间 AudioRecord 和采集线程都还在（见 stopCaptureKeepProjection）：
            // 这里只要重新打开 socket、设好新目标、把发送开关打开就行。
            // 不用新建 AudioRecord，也就不会再有"两个线程抢一个 record"的竞态。
            running = true;
            sending = true;
            sRunning = true;
            sHost = host;
            sPort = port;
            sStreamId = streamId;
            sPackets = 0;
            sSendErrors = 0;
            sLastSendError = "";
            sReadZero = 0;
            sReadTotal = 0;
            sStatus = "已恢复 " + host + ":" + port + " · 通道 " + streamId;
            Log.i(TAG, "复用保留的采集会话恢复发送 → " + host + ":" + port);
            muteMediaVolume();
            acquireLocks();
            ensureWorkerRunning();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = manager.getMediaProjection(resultCode, resultData);
            sHasProjection = projection != null;
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
                stopSelfResult(pendingStartId);
            }
        };
        try {
            projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            // v0.4：个别 ROM 在注册采集回调时会抛 SecurityException/IllegalStateException。
            // 之前这里没有兜底，异常会把 Service 主线程带走 —— 用户看到的就是
            // “同意邀请、刚进控制屏 App 就消失”。这里改成失败即停，绝不崩进程。
            fail("注册采集回调失败: " + t.getClass().getSimpleName());
            return START_NOT_STICKY;
        }

        if (!startRecording()) {
            return START_NOT_STICKY;
        }

        // v0.25：不静音媒体音量（真机上会连带掐掉 AudioPlaybackCapture，见字段区说明）。
        muteMediaVolume();

        acquireLocks();
        sRunning = true;
        sHost = host;
        sPort = port;
        sStreamId = streamId;
        sPackets = 0;
        sSendErrors = 0;
        sLastSendError = "";
        sReadZero = 0;
        sReadTotal = 0;
        sStatus = "已连接 " + host + ":" + port + " · 通道 " + streamId;
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ 采集

    /** 采集线程应该一直在跑（暂停时只关发送），这里只在它真的没跑时补起一条。 */
    private void ensureWorkerRunning() {
        if (worker != null && worker.isAlive()) return;
        final AudioRecord current = record;
        if (current == null) {
            fail("采集会话已失效，请重新点「送入」授权");
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workerLoop(current);
            }
        }, "audiohub-capture");
        worker.start();
    }

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

        // 只允许 MEDIA / GAME / UNKNOWN —— 这不是"偷懒"，是平台的硬限制。
        //
        // v0.38 一度把 ALARM / NOTIFICATION* / ASSISTANCE* / VOICE_COMMUNICATION*
        // 一起 addMatchingUsage()，Builder 全部接受（不抛异常），但随后
        // AudioRecord.Builder().build() 直接失败：
        //     E AudioHubCapture: 创建 AudioRecord 失败: Error: could not register audio policy
        // 采集线程根本没起来，一个包都发不出去 —— 比"抓到静音"更糟。
        // 所以这里必须**只加平台允许的三类**（它们的值恰好也是
        // AudioPlaybackCaptureConfiguration 文档里写明的可采集 usage）。
        //
        // 由此带来的固有限制要如实告诉用户：手机若把声音挂在别的声音类型上
        //（通话、通知提示音、无障碍朗读等），播放采集永远抓不到。抓不到时
        // Android 返回的是静音帧而不是 0 —— 这就是 UI 上"有特效但电脑没声"
        // 的那一类情况；v0.24 的 silentHint 会在这时给出提示。
        //
        // 仍然逐个 add + try/catch：万一某个 OEM ROM 对某一类另有规定，
        // 少一类总比整个 AudioRecord 建不起来强。
        AudioPlaybackCaptureConfiguration config;
        try {
            final AudioPlaybackCaptureConfiguration.Builder builder =
                    new AudioPlaybackCaptureConfiguration.Builder(projection);
            final int[] usages = new int[]{
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME,
                    AudioAttributes.USAGE_UNKNOWN,
            };
            int added = 0;
            for (int usage : usages) {
                try {
                    builder.addMatchingUsage(usage);
                    ++added;
                } catch (Throwable ignored) {
                    // 这个 usage 不被采集框架允许，跳过即可
                }
            }
            if (added == 0) {
                fail("构建采集配置失败：没有任何可用的音频类型");
                return false;
            }
            config = builder.build();
            Log.i(TAG, "采集匹配音频类型 " + added + "/" + usages.length + " 种");
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

        try {
            record.startRecording();
        } catch (Throwable t) {
            // v0.4：startRecording 在部分机型上会抛（录音被占用/播放采集不可用）。
            record.release();
            record = null;
            fail("启动采集失败: " + t.getMessage());
            return false;
        }
        running = true;
        sending = true;   // 新会话默认就是"正在发送"，暂停由 ACTION_PAUSE 单独关
        // 用匿名内部类而不是方法引用：android.jar 里没有
        // java.lang.invoke.LambdaMetafactory，lambda/方法引用在 javac 阶段就编不过。
        final AudioRecord startedRecord = record;   // 启动时抓死，见 workerLoop 注释
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workerLoop(startedRecord);
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
    private void workerLoop(final AudioRecord capture) {
        final short[] block = new short[PacketProtocol.FRAMES_PER_PACKET * PacketProtocol.CHANNELS];
        final byte[] packet = new byte[PacketProtocol.PACKET_BYTES];
        // 目标地址可以被 onStartCommand 的 retarget 分支换掉（切换汇聚端时不重建采集）。
        // 所以每包发送前检查一次，目标变了就重建 DatagramPacket，别一直往旧地址发。
        DatagramPacket datagram = null;
        InetAddress datagramAddress = null;
        int datagramPort = 0;

        final long t0 = SystemClock.elapsedRealtime();
        int seq = 0;
        int samplePos = 0;
        int fill = 0;  // 已填充的 short 数
        long nextSend = SystemClock.elapsedRealtime();
        long levelWindowStart = nextSend;
        long levelPackets = 0;
        double levelSum = 0;

        try {
        while (running) {
            // 用线程启动时抓到的那个 record（见 workerLoop 的参数）：
            // 恢复采集会新建 AudioRecord，字段 record 会被换掉；
            // 如果这里读字段，旧线程就会去读新对象 —— 两个线程同时 read 同一个
            // AudioRecord 会直接触发 releaseBuffer: mUnreleased out of range 的原生 abort
            // （v0.11 模拟器实测：切回音源后进程 SIGABRT）。
            final int read = capture.read(block, fill, block.length - fill, AudioRecord.READ_BLOCKING);
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                    sStatus = "采集中断（错误码 " + read + "）";
                    break;
                }
                // v0.33：非致命错误码走 continue 时什么都没记，于是「read() 一直
                // 返回 0」和「好好地读着只是没凑满」在界面上长得一模一样。
                // AudioPlaybackCapture 抓不到声音时就是前者：包不发、电平停在
                // -120，而 sStatus 还是「已连接」。计数只增不减，让界面说得出区别。
                sReadZero++;
                continue;
            }
            sReadTotal += read;
            fill += read;
            if (fill < block.length) continue;  // 还没凑满一包

            // 暂停态：继续读（保持 AudioRecord 一直在跑），但什么都不发。
            // 见 stopCaptureKeepProjection —— 不销毁 record 就不会有生命周期竞态。
            if (!sending) {
                fill = 0;
                levelSum = 0;
                levelPackets = 0;
                continue;
            }

            // 电平：给界面看"到底抓到声音没有"
            for (int i = 0; i < block.length; ++i) {
                final double v = block[i] / 32768.0;
                levelSum += v * v;
            }
            levelPackets++;

            PacketProtocol.writeHeader(packet, streamId, seq++, samplePos,
                    (int) (SystemClock.elapsedRealtime() - t0));
            PacketProtocol.writePayload(packet, PacketProtocol.HEADER_BYTES, block, block.length);

            final DatagramSocket target = socket;
            try {
                final InetAddress addr = address;
                final int destPort = port;
                if (target != null) {
                    if (datagram == null || datagramAddress != addr || datagramPort != destPort) {
                        datagramAddress = addr;
                        datagramPort = destPort;
                        datagram = new DatagramPacket(packet, packet.length, addr, destPort);
                    }
                    target.send(datagram);
                    sPackets++;
                }
            } catch (Exception e) {
                // 切换目标那一瞬间旧 socket 会被关掉，正常的 "Socket closed" 不要报给界面
                if (target == socket) {
                    sStatus = "发送失败: " + e.getMessage();
                    // v0.33：以前只写一行会被下一帧覆盖的文案。计数留着，
                    // 这样「sPackets 不涨」能区分是没数据还是 send() 一直在抛。
                    sSendErrors++;
                    sLastSendError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
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
                // v0.25 起 mutedByUs 恒 false，这里安全空转；保留调用以防将来重新启用静音策略。
                maybeHealMute(sLevelDbfs);
                levelSum = 0;
                levelPackets = 0;
                levelWindowStart = now;
            }
        }
        } catch (Throwable t) {
            // v0.4：采集线程的异常以前直接终止线程（甚至可能被系统当成崩溃），
            // 现在统一记到 sLastError，由 MainActivity 轮询提示用户。
            Log.e(TAG, "采集线程异常退出: " + t, t);
            sLastError = "采集线程异常退出: " + t.getClass().getSimpleName();
            sStatus = sLastError;
        }

        // 采集线程自己退出（AudioRecord 错误等）：也要恢复音量，不能把用户手机留在静音。
        sRunning = false;
        restoreMediaVolume();
        // 采集结束（错误码 / 被系统掐断）：先发 CONN-BYE 让桌面立刻掉流。
        // 暂停态不能 stopSelf —— 那会让 onDestroy 走 stopCapture() 把投屏会话销毁掉，
        // 切回音源时又得重新弹授权框（Android 14 一次性授权根本弹不出来）。
        sendByeBestEffort();
        // 用 stopSelfResult 而不是 stopSelf：若此刻还有 START/STOP 请求在排队，
        // 裸 stopSelf 会把它们连同已计时的前台义务一起丢掉（10 秒后系统杀进程）。
        if (!pausing) stopSelfResult(pendingStartId);
    }

    // ------------------------------------------------------------ 静音与自愈

    /**
     * 媒体音量策略入口（v0.25 起为 no-op，保留签名与全部调用点）。
     *
     * 旧版在这里把 STREAM_MUSIC 设 0；真机实测部分 ROM 会连 AudioPlaybackCapture 一起掐成
     * 静音，而自愈的 isMusicActive() 闸门在这些 ROM 上恒 false，整条流会变成永久静音。
     * 现在全程不碰媒体音量；mutedByUs 恒 false，restore/heal 的所有收尾调用都安全空转。
     */
    private synchronized void muteMediaVolume() {
        // v0.25：保留方法签名与全部调用点，但**不再修改媒体音量**。
        // 真机实测 setStreamVolume(STREAM_MUSIC, 0) 会连带掐掉 AudioPlaybackCapture 的声音，
        // 静音自愈又因 isMusicActive() 闸门在某些 ROM 上永不触发，整条流会变成永久静音。
        // 现在任何路径都不会把 mutedByUs 置 true，restoreMediaVolume()/maybeHealMute()
        // 也就永远安全空转；用户手机音量保持原样，手机照常出声、汇聚端也收得到。
        Log.i(TAG, "v0.25：保持媒体音量不变（不静音，避免掐断音频采集）");
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
     * v0.25：mutedByUs 恒 false（已不再主动静音），本方法首行即返回，保留作兼容兜底。
     * 历史背景：旧版连接后把 STREAM_MUSIC 设 0，Windows 的 WASAPI 回环不受端点静音影响，
     * 曾据此推测 Android 也一样 —— 真机证明不成立：部分 ROM 会连 AudioPlaybackCapture 一起
     * 掐成静音，而且 isMusicActive() 闸门在这些 ROM 上恒 false，自愈永远不触发。所以 v0.25
     * 直接放弃静音，而不是继续修这条自愈链路。
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

    /**
     * 暂停发送但**保留**整个采集会话（AudioRecord + 投屏授权 + 采集线程）。
     *
     * 用在「本机被设为汇聚端」这条路径上：汇聚端不能再采集（会回授），可用户
     * 下一秒可能又切回音源。Android 14 的投屏授权是一次性的 —— 如果这里走
     * stopCapture()（它会 projection.stop() 并清掉会话），切回来就必须重新弹
     * 系统授权框，用户看到的就是「反复切换之后再也发不出声音」。
     *
     * 注意这里**不**销毁 AudioRecord、也不结束采集线程：早期实现那样做过，
     * 模拟器上反复 abort（releaseBuffer: mUnreleased out of range）—— 只要有一个
     * 线程还阻塞在 read() 里，另一条路径去 stop()/release() 就会踩到 AudioRecord
     * 内部未归还缓冲的记账。改成"只停发送"后，一个 record 从会话开始用到结束，
     * 生命周期端点从"每次切换"收敛到"会话开始/结束"两次，竞态窗口消失。
     * 代价是暂停期间仍在采集（CPU 极低，且不发送就不会回授）。
     */
    private void stopCaptureKeepProjection() {
        Log.i(TAG, "暂停发送（保留投屏会话与采集线程，切回音源即恢复）");
        final boolean wasSending = sending;
        sending = false;
        // 只有真在发的时候才需要通知对端；socket 还开着，这一句必须排在关 socket 之前。
        if (wasSending) sendByeBestEffort();
        // running 保持 true：采集线程继续读 AudioRecord（暂停只关发送）。
        // 让它退出的唯一路径是 stopCapture()（真停会把 running 置 false）。
        restoreMediaVolume();
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
        address = null;
        port = 0;
        sRunning = false;
        sHost = "";
        sPort = 0;
        sStreamId = 0;
        sStatus = "已暂停（投屏会话保留，切回音源即恢复）";
    }

    private void stopCapture() {
        pausing = false;   // 真停：允许 stopSelf / onDestroy 释放投屏会话
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

        // 同 stopCaptureKeepProjection：必须先 stop 再 join，否则可能 release 掉
        // 采集线程仍在使用的 AudioRecord（原生 abort: releaseBuffer out of range）。
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
        final AudioRecord dying = record;
        if (worker != null) {
            try {
                worker.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        if (dying != null) {
            try {
                dying.release();
            } catch (Exception ignored) {
            }
            if (record == dying) record = null;
        }
        if (projection != null) {
            try {
                if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
            sHasProjection = false;
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
        // 错误原因要能被 MainActivity 取走（它负责提示用户 + 清掉失效授权），
        // 否则 400ms 一次的 reconcile 会拿旧授权反复重试，每次都崩一次。
        sLastError = message;
        sRunning = false;
        stopCapture();
        // stopCapture 会把 sStatus 覆盖成"已停止"，这里再写回失败原因。
        sStatus = message;
        stopSelfResult(pendingStartId);
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
        stopSelfResult(pendingStartId);
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
