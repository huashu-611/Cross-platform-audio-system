package com.audiohub.source;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * AudioHub。
 *
 * 两屏流程：
 *   连接界面 —— 选汇聚端、连设备；连接建立后自动进控制界面
 *   控制界面 —— 星空图谱：汇聚端是中心恒星，其他设备是围绕它的行星，光束 = 音频流；
 *               点任意星球调它的音量，实时生效。
 *
 * v1.7 视觉：整页深空星野（{@link StarSkyView} 在最底层画星点/星云/银河带），
 * 卡片改成半透明玻璃 + 蓝紫描边，按钮改胶囊描边，字号与层级不变。
 *
 * 架构上两端能力完全一致：都能采音、都能放音、都能发起或接受连接。
 * 唯一的差别是用户选了哪台设备当汇聚端，默认是电脑。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_PROJECTION = 101;

    private LinearLayout screenConnect;
    private LinearLayout screenControl;

    private TextView hubValue;
    private TextView hubSub;
    private TextView sourceState;
    private Button sourceToggle;
    private TextView scanHint;
    // 主界面只放摘要；完整设备列表 / 待确认请求都在弹窗里（一屏装完、不滚动）
    private TextView devSummary;
    private TextView connectPending;
    private TextView ctrlPending;
    /** 当前合并后的设备列表（手动 + 自动发现），供「设备列表」弹窗使用。 */
    private final java.util.List<Discovery.Device> currentPeers = new java.util.ArrayList<>();
    // 手动添加 / 上次连接的设备（广播被拦时靠它们）
    private final java.util.List<Discovery.Device> manualPeers = new java.util.ArrayList<>();

    // 用户主动点了"断开"：服务真正停掉之前，不能被图谱/服务状态拉回控制界面
    private boolean userLeft = false;

    // ---- 图谱 / 采集职责（协议 §6.2）----
    /**
     * 从系统授权界面刚拿到的 MediaProjection 授权，只用于「立刻启动一次采集」。
     * Android 14 起这是一次性令牌：启动后必须丢弃（见 clearProjectionAuth），
     * 不能存着给后续重启用，否则 startForeground 会抛 SecurityException。
     */
    private int projectionResultCode = 0;
    private Intent projectionResultData;
    /** 用户希望本机在采集（点过"送入"或连接成功后自动开始）。 */
    private boolean sourceIntent = false;
    /** 切换汇聚端后的提示状态。 */
    private String pendingSwitchTarget = "";
    private long switchSyncedAt = 0;
    /** 音量面板当前选中的音频流（0 = 没有流）。 */
    private int selectedStreamId = 0;
    /** 一次性提示（只 toast 一次，避免每 400ms 刷屏）。 */
    private String hintText = "";

    private HubGraphView graph;

    // ---- 图谱布局：权威来自 hub 同步的 POS 帧（人工摆位），没摆位的节点用确定性自动槽位。
    // 手机端只读：不再持久化相机 / pin（旧的 "scene3d" SharedPreferences 直接弃用）----
    private LinearLayout volPanel;
    private TextView volName;
    private SeekBar volSlider;
    private TextView volPct;

    private final Handler poller = new Handler(Looper.getMainLooper());
    private Discovery discovery;

    // 必须由字段持有：MulticastLock 的 finalize 会释放锁。
    // 只在 onCreate 里用局部变量 acquire，方法返回后这个对象就没人引用，
    // GC 一回收就把锁放了 —— 之后手机静默收不到任何广播，
    // 表现为"扫描不到任何设备"，而且完全没有任何报错。
    private android.net.wifi.WifiManager.MulticastLock multicastLock;
    private android.net.wifi.WifiManager wifiManager;
    private boolean inviteDialogShowing;

    /** hubId 指向的节点缺失的起始时间（0 = 正常）：
     * 图谱帧可能比自己延迟一两个拉手，这段时间内不把正在跑的采集掐掉。 */
    private long hubMissingSinceMs = 0L;

    /** 图谱里找不到 hub 时的宽限期：
     * 这段时间内用上次记住的 hubIp 兜底继跑，
     * 避免图谱抖动一下就把正在工作的采集掐掉（
     * 用户报的「进控制屏瞬间就退」就是这条路径）。 */
    private static final long HUB_MISSING_GRACE_MS = 6000L;

    /** 汇聚端：null = 本机（默认，因为电脑跑着汇聚端）；否则是那台设备。 */
    private String hubIp;
    private int hubPort;
    private String hubName;

    // ---- 星空色板（docs/starfield-ui-preview/shared.css 设计令牌）----
    /** 深空底色：整页星野的兜底色（真正背景由 StarSkyView 画）。 */
    private static final int COLOR_BG = 0xFF04060F;
    /** 卡片兜底底色（对话框 / 输入框 / 不透明场景）。 */
    private static final int COLOR_CARD = 0xFF0B1224;
    /** 主文字 #eaf1ff。 */
    private static final int COLOR_TEXT = 0xFFEAF1FF;
    /** 次文字 #a3b3d1。 */
    private static final int COLOR_DIM = 0xFFA3B3D1;
    /** 弱文字 #6e7d9d。 */
    private static final int COLOR_FAINT = 0xFF6E7D9D;
    /** 线：rgba(120,150,200,.18)。 */
    private static final int COLOR_LINE = 0x2E7896C8;
    /** 主强调色：青 #6fe0ff。 */
    private static final int COLOR_ACCENT = 0xFF6FE0FF;
    /** 告警：琥珀 #ffd166。 */
    private static final int COLOR_WARN = 0xFFFFD166;
    /** 危险 / 断开：红 #ff8a99。 */
    private static final int COLOR_BAD = 0xFFFF8A99;
    /** 同步成功：绿 #7df0b3。 */
    private static final int COLOR_GREEN = 0xFF7DF0B3;
    /** 恒星金 #ffd27a。 */
    private static final int COLOR_GOLD = 0xFFFFD27A;
    /** 玻璃卡片底色（半透明，能透出后面的星野）。 */
    private static final int COLOR_GLASS_1 = 0xBD18244A;
    private static final int COLOR_GLASS_2 = 0xAD0B1226;
    private static final int COLOR_GLASS_3 = 0xA30F162E;
    /** 玻璃描边：rgba(126,163,255,.17)。 */
    private static final int COLOR_GLASS_STROKE = 0x2B7EA3FF;
    /** 文字光晕（星空底上直接写字时的深空色描边）。 */
    private static final int COLOR_TEXT_HALO = 0xCC04060F;

    /** 构建标记：与 APK versionName 保持一致，用于在手机上确认装的是哪一版。 */
    private static final String BUILD_TAG = "0.17";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 窗口底色：连接页内容短 / 系统栏区域都不能露白（AndroidManifest 没有指定 theme）
        getWindow().setBackgroundDrawable(new ColorDrawable(COLOR_BG));

        // 兜底：任何未捕获异常都写进 logcat，并把堆栈落盘一份。
        // 用户报"闪退"时，没有堆栈就完全没法查。
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    final String text = android.util.Log.getStackTraceString(e);
                    android.util.Log.e("AudioHub", "未捕获异常 @ " + t.getName() + ": " + text);
                    java.io.FileOutputStream fos = openFileOutput("crash.txt", MODE_PRIVATE);
                    fos.write((t.getName() + "\n" + text).getBytes("UTF-8"));
                    fos.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });

        setContentView(buildUi());

        SharedPreferences prefs = getSharedPreferences("audiohub", MODE_PRIVATE);
        hubIp = prefs.getString("hub_ip", null);
        hubPort = prefs.getInt("hub_port", 47000);
        hubName = prefs.getString("hub_name", null);
        // 会话状态跨进程保留：默认「不在任何会话里」→ 打开就是连接界面。
        // 否则清后台重开时，远端广播的旧图谱会直接把界面拉进控制屏（没有汇聚端的
        // 双星界面），而那时并没有真正连上，把任一端设成汇聚端也不会传声。
        userLeft = prefs.getBoolean("user_left", true);

        // 图谱：本机节点尽早就位；上次连过的设备先登记成占位节点
        // （kind 先 other，对端发来 GRAPH 后会被校正成它自己的名字/类型）
        final String myIp = localIp();
        if (!myIp.isEmpty()) GraphStore.setSelf(myIp, android.os.Build.MODEL);
        if (hubIp != null && !hubIp.isEmpty()) {
            GraphStore.upsertNode(hubIp, hubPort > 0 ? hubPort : GraphStore.AUDIO_PORT,
                    hubName != null ? hubName : hubIp, GraphStore.KIND_OTHER);
        }

        // 上次如果崩过，把堆栈直接摆到眼前——用户不用连电脑也能发给我们
        showLastCrashIfAny();

        poller.post(new Runnable() {
            @Override
            public void run() {
                // Handler 里的 Runnable 抛异常 = 主线程崩溃 = 闪退。
                // 刷新只是投影状态，绝不能因为它把整个进程带走。
                try {
                    refresh();
                } catch (Throwable t) {
                    android.util.Log.e("AudioHub", "refresh 异常: " + android.util.Log.getStackTraceString(t));
                }
                poller.postDelayed(this, 400);
            }
        });

        wifiManager = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("audiohub");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();   // 字段持有，不会被 GC 释放
        }
        discovery = new Discovery();
        discovery.setListener(new Discovery.Listener() {
            @Override
            public void onPeersChanged(final List<Discovery.Device> peers) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { renderPeers(peers); }
                });
            }
        });
        // 常驻握手监听：不管有没有开接收服务，都要能收到别人的邀请
        InviteListener.setSelfName(android.os.Build.MODEL);
        if (!InviteListener.get().start(47000)) {
            // 端口被占不算致命：只是收不到邀请，手动连接仍可用
            android.util.Log.w("AudioHub", "握手口 47002 绑定失败，可能已被占用");
        }

        if (!discovery.start()) {
            Toast.makeText(this, discovery.lastError(), Toast.LENGTH_LONG).show();
        } else {
            // 进来就发一帧探测，让对端立刻回信标，不用等它下一次广播
            discovery.probe();
            // 广播被拦的网络（办公/校园 WiFi 常见）里，单播探测是唯一手段
            if (hubIp != null && !hubIp.isEmpty()) {
                discovery.probeTo(hubIp);
                addManualPeer(hubIp, hubName != null ? hubName : hubIp, hubPort);
            }
            scanHint.setTextColor(COLOR_DIM);
            scanHint.setText("正在扫描附近设备…");
            poller.postDelayed(new Runnable() {
                @Override
                public void run() {
                    final int n = discovery.peers().size();
                    scanHint.setTextColor(n > 0 ? COLOR_ACCENT : COLOR_DIM);
                    scanHint.setText(n > 0 ? ("已发现 " + n + " 台设备")
                                           : "暂未发现设备，点「扫描」重试");
                }
            }, 1500);
        }
    }

    @Override
    protected void onDestroy() {
        // 退出 App（返回键 / 划掉最近任务）：必须马上停掉采集服务，否则前台服务继续推流、
        // 汇聚端一直显示本机在线，再邀请这台手机也不会有响应。
        // stopService 会走 CaptureService.onDestroy → stopCapture → 先发 CONN-BYE 再关 socket，
        // 且这里是"停止服务"，不受 Android 12+ 后台启动限制；转屏重建（isChangingConfigurations）
        // 不打断采集；划掉任务另有 CaptureService.onTaskRemoved() + manifest stopWithTask 兜底。
        // 转屏重建（isChangingConfigurations）时什么都不要拆：新的 Activity 实例马上会把
        // 同样的东西重新建起来，中间那一拍没有任何好处，只有坏处 —— 图谱广播一断，
        // 汇聚端 10 秒（GraphStore.ONLINE_TIMEOUT_MS）后就把本机判成离线。
        final boolean reallyLeaving = isFinishing() && !isChangingConfigurations();
        if (reallyLeaving) {
            stopService(new Intent(this, CaptureService.class));
        }
        poller.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.stop();
        // 图谱广播器（InviteListener）原来是无条件停的，这是「老是意外断开」的主因：
        // 它就是全网唯一一帧 GRAPH 的产生者，停掉它等于本机对所有对端消失。
        // 现在只在真的退出 App 时停；系统回收 / 转屏重建都保留它继续广播。
        if (reallyLeaving) {
            InviteListener.get().stop();
        }
        // 退出才释放多播锁
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
        }
        super.onDestroy();
    }

    /** 上次运行留下的崩溃日志：有就弹出来，并告诉用户文件在哪。 */
    private void showLastCrashIfAny() {
        final java.io.File f = new java.io.File(getFilesDir(), "crash.txt");
        if (!f.exists()) return;
        final String text;
        try {
            final byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
            final java.io.FileInputStream in = new java.io.FileInputStream(f);
            final int n = in.read(buf);
            in.close();
            text = new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Exception e) {
            return;
        }
        dialog()
                .setTitle("上次运行时崩溃了")
                .setMessage(text.length() > 1200 ? text.substring(0, 1200) + "…" : text)
                .setPositiveButton("复制并清除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        final android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(
                                        Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text));
                        f.delete();
                        Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------ 控件

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView sectLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10.5f);
        tv.setLetterSpacing(0.14f);
        tv.setAllCaps(true);
        tv.setTextColor(COLOR_DIM);
        haloText(tv, 0.55f);
        return tv;
    }

    private TextView sub(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(COLOR_DIM);
        tv.setPadding(0, dp(2), 0, 0);
        haloText(tv, 0.55f);
        return tv;
    }

    /** 星空底上直接写字的兜底：给文字加深空色柔光描边，避免压在亮星上读不清。 */
    private void haloText(TextView tv, float strength) {
        tv.setShadowLayer(dp(4), 0f, dp(1), withAlpha(0xFF04060F, (int) (255 * strength)));
    }

    /** 半透明玻璃卡片：深蓝渐变 + 蓝紫细描边 + 12dp 圆角（星野能透出来）。 */
    private Drawable glassCard() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COLOR_GLASS_1, COLOR_GLASS_2, COLOR_GLASS_3});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), COLOR_GLASS_STROKE);
        return d;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(8), dp(11), dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(6));
        card.setLayoutParams(p);
        card.setBackground(glassCard());
        return card;
    }

    /** 胶囊按钮：透明底 + 同色描边（颜色决定按钮语义：青=主操作 / 琥珀=提醒 / 红=断开）。 */
    private Button textButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackground(pillButton(color));
        // 尺寸/内边距保持改造前不变（只换外观，不动布局）
        b.setPadding(dp(7), dp(5), dp(7), dp(5));
        b.setStateListAnimator(null);
        return b;
    }

    private Drawable pillButton(int color) {
        final int rgb = color & 0x00FFFFFF;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(16));
        d.setColor(rgb | 0x17000000);       // 内部极淡同色填充
        d.setStroke(dp(1), rgb | 0x66000000); // 半透明同色描边
        return d;
    }

    /** 给颜色换透明度（COLOR_* 都是不透明 ARGB）。 */
    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** 深色对话框：和星空夜配色一致，避免设备浅色主题下白底 + 青字看不清。 */
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }

    private View buildUi() {
        // 根容器：FrameLayout。两屏都 MATCH_PARENT、靠 VISIBLE/GONE 切换 ——
        // 不再用滚动容器：没有滚动条，也不会两屏共用一个滚动位置。
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        // 最底层：整页星空（固定种子的星点/星云/银河带，静态缓存位图）。
        // 两屏都不再画不透明底色，星野从卡片半透明玻璃后面透出来。
        root.addView(new StarSkyView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 标题区（放进连接屏；控制屏顶部用 hubValue 那一行）
        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("AudioHub");
        title.setTextSize(22);
        title.setLetterSpacing(0.02f);
        title.setTextColor(COLOR_TEXT);
        haloText(title, 0.75f);
        header.addView(title);

        TextView subT = new TextView(this);
        subT.setText("多台设备的声音，汇到一处");
        subT.setTextSize(12f);
        subT.setTextColor(COLOR_DIM);
        subT.setPadding(0, dp(2), 0, dp(6));
        haloText(subT, 0.55f);
        header.addView(subT);

        // 版本/特性标记：手机上装的是哪一版一眼可辨（排查“装了新版还是旧界面”类问题）
        TextView buildTag = new TextView(this);
        buildTag.setText("v" + BUILD_TAG + " · 星图");
        buildTag.setTextSize(11f);
        buildTag.setTextColor(COLOR_FAINT);
        buildTag.setPadding(0, 0, 0, dp(6));
        haloText(buildTag, 0.55f);
        header.addView(buildTag);

        // ================= 连接界面 =================
        screenConnect = new LinearLayout(this);
        screenConnect.setOrientation(LinearLayout.VERTICAL);
        screenConnect.setPadding(dp(16), dp(12), dp(16), dp(10));
        screenConnect.setBackground(null);   // 透明：星空由底层 StarSkyView 提供
        screenConnect.addView(header);
        root.addView(screenConnect, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 待确认的接入请求：有才显示一行提示，点开对话框处理（不占主界面高度）
        connectPending = pendingBanner();
        screenConnect.addView(connectPending);

        TextView connectHint = sub("先建立连接，再在控制界面里指定谁当汇聚端");
        connectHint.setPadding(0, 0, 0, dp(6));
        screenConnect.addView(connectHint);

        screenConnect.addView(sectLabel("本机声音"));
        LinearLayout srcCard = card();
        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout srcInfo = new LinearLayout(this);
        srcInfo.setOrientation(LinearLayout.VERTICAL);
        srcInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView srcTitle = new TextView(this);
        srcTitle.setText("把本机的声音送进汇聚");
        srcTitle.setTextSize(14);
        srcTitle.setTextColor(COLOR_TEXT);
        srcInfo.addView(srcTitle);
        sourceState = sub("未发送");
        srcInfo.addView(sourceState);
        srcRow.addView(srcInfo);
        sourceToggle = textButton("送入", COLOR_ACCENT);
        sourceToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleSource(); }
        });
        srcRow.addView(sourceToggle);
        srcCard.addView(srcRow);
        screenConnect.addView(srcCard);

        // 设备区标题行 + 主动扫描按钮
        LinearLayout devRow = new LinearLayout(this);
        devRow.setOrientation(LinearLayout.HORIZONTAL);
        devRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView devLabel = sectLabel("设备");
        devLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        devRow.addView(devLabel);
        Button scanBtn = textButton("扫描", COLOR_ACCENT);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { scanNow((Button) v); }
        });
        devRow.addView(scanBtn);
        Button manualBtn = textButton("手动添加", COLOR_DIM);
        manualBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showManualAddDialog(); }
        });
        devRow.addView(manualBtn);
        Button peerBtn = textButton("设备列表", COLOR_DIM);
        peerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showPeersDialog(); }
        });
        devRow.addView(peerBtn);
        screenConnect.addView(devRow);

        scanHint = sub("");
        scanHint.setPadding(0, 0, 0, dp(4));
        screenConnect.addView(scanHint);

        devSummary = sub("");
        devSummary.setPadding(0, 0, 0, 0);
        screenConnect.addView(devSummary);

        // ================= 控制界面 =================
        screenControl = new LinearLayout(this);
        screenControl.setOrientation(LinearLayout.VERTICAL);
        screenControl.setPadding(dp(14), dp(8), dp(14), dp(6));
        screenControl.setBackground(null);   // 透明：露出星空
        screenControl.setVisibility(View.GONE);

        graph = new HubGraphView(this, null);
        graph.setOnNodeTap(new HubGraphView.OnNodeTap() {
            @Override
            public void onTap(HubGraphView.Node node) {
                onGraphNodeTap(node);
            }
        });
        graph.setOnNodeDoubleTap(new HubGraphView.OnNodeDoubleTap() {
            @Override
            public void onDoubleTap(HubGraphView.Node node) {
                onSceneDoubleTap(node);
            }
        });
        // 图谱吃满剩余高度（权重 1），最小高度保证小屏也看得清俯视图；不再写死 300dp。
        // 位置/缩放全由 View 自己按实际宽高等比算 ppu，禁止任何拖动/捏合/旋转手势。
        graph.setMinimumHeight(dp(200));
        screenControl.addView(graph, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        volPanel = new LinearLayout(this);
        volPanel.setOrientation(LinearLayout.VERTICAL);
        volPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.setMargins(0, dp(4), 0, dp(4));
        volPanel.setLayoutParams(vp);
        volPanel.setBackground(glassCard());
        // 默认收起：点节点才展开，主界面尽量一屏装完
        volPanel.setVisibility(View.GONE);
        volName = new TextView(this);
        volName.setTextSize(13.5f);
        volName.setTextColor(COLOR_TEXT);
        volPanel.addView(volName);

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.setPadding(0, dp(4), 0, 0);
        volSlider = new SeekBar(this);
        volSlider.setMax(150);
        volSlider.setProgress(100);
        // 星空风上色：青进度条 + 亮色滑块 + 半透明深色轨道
        volSlider.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        volSlider.setThumbTintList(ColorStateList.valueOf(0xFFEAF1FF));
        volSlider.setProgressBackgroundTintList(ColorStateList.valueOf(0x5980A0C8));
        volSlider.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || selectedStreamId <= 0) return;
                final float g = progress / 100f;
                volPct.setText(Math.round(g * 100) + "%");
                applyVolume(g);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        volRow.addView(volSlider);
        volPct = new TextView(this);
        volPct.setText("100%");
        volPct.setTextSize(12);
        volPct.setTextColor(COLOR_DIM);
        volPct.setPadding(dp(10), 0, 0, 0);
        volRow.addView(volPct);
        Button mute = textButton("静音", COLOR_DIM);
        mute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { applyVolume(0f); volSlider.setProgress(0); }
        });
        volRow.addView(mute);
        volPanel.addView(volRow);
        screenControl.addView(volPanel);

        // 会话进行中也可能有新设备接入：待确认提示 + 设备列表弹窗入口
        ctrlPending = pendingBanner();
        screenControl.addView(ctrlPending);

        LinearLayout ctrlDevRow = new LinearLayout(this);
        ctrlDevRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlDevRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ctrlDevLabel = sectLabel("新设备");
        ctrlDevLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ctrlDevRow.addView(ctrlDevLabel);
        Button ctrlAdd = textButton("添加设备", COLOR_ACCENT);
        ctrlAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showPeersDialog(); }
        });
        ctrlDevRow.addView(ctrlAdd);
        screenControl.addView(ctrlDevRow);

        // 汇聚端信息：切换靠点图谱里的球（协议 §9.3），不再有下拉/切换按钮
        LinearLayout hubBar = card();
        LinearLayout hubRowC = new LinearLayout(this);
        hubRowC.setOrientation(LinearLayout.HORIZONTAL);
        hubRowC.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hubInfoC = new LinearLayout(this);
        hubInfoC.setOrientation(LinearLayout.VERTICAL);
        hubInfoC.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hubValue = new TextView(this);
        hubValue.setTextSize(15);
        hubValue.setTextColor(COLOR_TEXT);
        hubInfoC.addView(hubValue);
        hubSub = sub("点星球可切换汇聚端");
        hubInfoC.addView(hubSub);
        hubRowC.addView(hubInfoC);
        hubBar.addView(hubRowC);
        screenControl.addView(hubBar);

        Button leave = textButton("断开所有连接", COLOR_BAD);
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 协议 §4.6：清空图谱（不广播），两个服务都停，回连接界面
                GraphStore.resetSession();
                PlacementStore.reset();   // 人工摆位表一起清掉；重连后再向 hub 要 POS
                startService(new Intent(MainActivity.this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_STOP));
                sourceIntent = false;
                stopSource();
                projectionResultCode = 0;
                projectionResultData = null;
                setUserLeft(true);    // 立刻回连接界面，且进程重启后仍保持
                manualPeers.clear();  // 主动断开：关系表一起清掉
                hubIp = null;
                hubName = null;
                saveHub();
                refresh();
                Toast.makeText(MainActivity.this, "已断开", Toast.LENGTH_SHORT).show();
            }
        });
        screenControl.addView(leave);
        root.addView(screenControl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return root;
    }

    // ------------------------------------------------------------ 两屏切换

    private void showScreen(String next) {
        screenControl.setVisibility("control".equals(next) ? View.VISIBLE : View.GONE);
        screenConnect.setVisibility("control".equals(next) ? View.GONE : View.VISIBLE);
    }

    /** 音量面板：streamId=0 表示这个节点当前没有音频流（只看状态）。 */
    private void showVolumePanelForStream(int streamId, String label) {
        // 点节点才展开音量面板（默认 GONE，省主界面高度）
        if (volPanel != null) volPanel.setVisibility(View.VISIBLE);
        selectedStreamId = streamId;
        if (streamId <= 0) {
            volName.setText(label + " · 暂无音频流");
            volSlider.setEnabled(false);
            volSlider.setProgress(100);
            volPct.setText("—");
            return;
        }
        if (!ReceiverService.sRunning) {
            // 本机当音源时，音量由汇聚端控制，这里只说明状态
            volName.setText(label + " · 音量由汇聚端调节");
            volSlider.setEnabled(false);
            volPct.setText("—");
            return;
        }
        volSlider.setEnabled(true);
        volName.setText(label);
        volSlider.setProgress(100);
        volPct.setText("100%");
    }

    private void applyVolume(float gain) {
        if (selectedStreamId <= 0) return;
        ReceiverService.setStreamGain(selectedStreamId, gain);
    }

    // ------------------------------------------------------------ 汇聚端

    private boolean isLocalHub() {
        return GraphStore.isHub();
    }

    private void safeStartForegroundService(Intent intent) {
        try {
            startForegroundService(intent);
        } catch (Throwable t) {
            android.util.Log.e("AudioHub", "启动服务失败: "
                    + android.util.Log.getStackTraceString(t));
            Toast.makeText(this, "启动接收服务失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setLocalHub() {
        final String sid = GraphStore.selfId();
        if (sid.isEmpty()) {
            Toast.makeText(this, "本机还没有局域网地址（等 WiFi 连上）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (GraphStore.switchHub(sid)) {
            pendingSwitchTarget = sid;
            switchSyncedAt = 0;
            InviteListener.broadcastGraph();   // 本地变化：2 次 / 60ms（协议 §4.5）
        }
        setUserLeft(false);   // 明确把本机设为汇聚端 = 会话开始
        GraphStore.reattach();   // 重新入网：解除「全部断开」留下的退网状态
        // 不要清 sourceIntent！它是"用户希望本机把声音送出去"的意愿，
        // 不是"现在正在发"的状态。被设为汇聚端期间由 reconcile 的 isHub 分支
        // 挡住采集就够了；清掉它以后，用户把汇聚端切回电脑时就再也不会恢复
        // 采集了（用户看到的正是「切到手机再切回来，手机就不再送声音」）。
        // 真正的显式关闭只有 toggleSource / 断开连接 / onActivityResult 失败三处。
        stopSourceKeepingAuth();
        if (!ReceiverService.sRunning) {
            safeStartForegroundService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_START)
                    .putExtra(ReceiverService.EXTRA_PORT, GraphStore.AUDIO_PORT));
        }
        refresh();
        Toast.makeText(this, "本机已成为汇聚端", Toast.LENGTH_SHORT).show();
    }

    /**
     * 对端用 ROLE|source 指定了"谁来当汇聚端"——跟着走。
     *
     * 为什么不能复用 {@link #pickRemoteHub}：那条路是**用户主动连一台设备**，
     * 语义是"我要加入它"，所以它会做两件在这里有害的事：
     *   1. 先 stop 掉 ReceiverService（用户以为只是换目标，实际把接收端拆了）；
     *   2. 握手失败就 setUserLeft(true) 退回连接界面 + 弹"对方还没有同意连接"。
     * ROLE 是汇聚端那边的决定，不是用户操作：远端一台设备在反复切换时会连发
     * 好几次 ROLE|source，中途任何一次 CONN-ACK 抖动都会把用户踢出控制台
     * （用户 2026-11-02 报的「来回切换时手机退出控制台，说桌面端没同意连接」）。
     *
     * 这里只做三件事：记住目标、把自己当成音源（等 reconcile 决定起/停采集）、
     * 保持 ReceiverService 不动（万一图谱又切回来，接收端还是活的）。
     */
    private void followRemoteRole(final Discovery.Device d) {
        if (d == null || d.ip == null || d.ip.isEmpty()) return;
        final int audioPort = d.audioPort > 0 ? d.audioPort : GraphStore.AUDIO_PORT;
        GraphStore.upsertNode(d.ip, audioPort, d.name, GraphStore.KIND_OTHER);
        hubIp = d.ip;
        hubPort = audioPort;
        hubName = d.name;
        saveHub();
        addManualPeer(d.ip, d.name, audioPort);
        // 对端说"你往我这里送" = 会话语义成立：解除退网状态、留在控制界面。
        setUserLeft(false);
        GraphStore.reattach();
        // 用户意愿：本机当音源。真正起不起采集由 reconcile 按图谱 hub 决定。
        sourceIntent = true;
        hintText = "";
        // 不 stop ReceiverService：对端随时可能把汇聚端切回本机，届时它要能立刻收流。
        refresh();
    }

    /**
     * 连入一台设备：图谱语义是先加入它的网络，真正的汇聚端由对端 GRAPH 决定。
     * 旧版"选中远端当汇聚端"的行为保留兼容：握手成功后自动开始采集，
     * 音源目标由 reconcile 按图谱里的 hub 自动改投。
     */
    private void pickRemoteHub(final Discovery.Device d) {
        if (d == null || d.ip == null || d.ip.isEmpty()) return;
        final int audioPort = d.audioPort > 0 ? d.audioPort : GraphStore.AUDIO_PORT;
        // 协议 §4.1：手动连接先登记占位节点（kind 先 other，等对端 GRAPH 校正）
        GraphStore.upsertNode(d.ip, audioPort, d.name, GraphStore.KIND_OTHER);
        // 旧版兼容：记住这台设备，下次界面里还能选
        hubIp = d.ip;
        hubPort = audioPort;
        hubName = d.name;
        saveHub();
        addManualPeer(d.ip, d.name, audioPort);
        setUserLeft(false);
        GraphStore.reattach();   // 重新入网：解除「全部断开」留下的退网状态
        hintText = "";
        startService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_STOP));
        refresh();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = ReceiverService.respondToInvite(
                        d.ip, audioPort, android.os.Build.MODEL);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            // 没握手成功就不算进过会话：把「已断开」状态写回去，
                            // 否则下次冷启动可能又被远端残留图谱拉进控制屏。
                            setUserLeft(true);
                            refresh();
                            Toast.makeText(MainActivity.this,
                                    "对方还没有同意连接，请稍后再试",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        // 收到 ACK = 握手成功（协议 §4.1）：对端入图谱 + 立刻单播 GRAPH
                        GraphStore.upsertNode(d.ip, audioPort, d.name, GraphStore.KIND_OTHER);
                        InviteListener.sendGraphTo(d.ip, audioPort + GraphStore.PORT_OFFSET);
                        InviteListener.requestSync();
                        Toast.makeText(MainActivity.this, "已连接 " + d.name,
                                Toast.LENGTH_SHORT).show();
                        // 音源目标交给 reconcile：图谱里的 hub 才是真正的汇聚端
                        if (!GraphStore.isHub()) {
                            sourceIntent = true;
                            ensurePermissionsThenStart();
                        }
                    }
                });
            }
        }, "audiohub-hubpick").start();
    }

    private void saveHub() {
        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putString("hub_ip", hubIp)
                .putInt("hub_port", hubPort)
                .putString("hub_name", hubName)
                .apply();
    }

    /**
     * userLeft 落盘：断开后即使清后台重开，也稳定停在连接界面，直到用户明确发起
     * 新连接（点「送入」、同意邀请、从设备列表 / 手动 IP 连入、把本机设为汇聚端）。
     * 只放内存里的话，进程重启后默认 false，远端残留图谱会把界面拉进控制屏。
     */
    private void setUserLeft(boolean value) {
        userLeft = value;
        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putBoolean("user_left", value).apply();
    }

    // ------------------------------------------------------------ 本机声音

    private void toggleSource() {
        if (CaptureService.sRunning) {
            sourceIntent = false;
            stopSource();
        } else {
            final GraphStore.Snapshot g = GraphStore.snapshot();
            final GraphStore.Node hub = g.hubNode();
            if (g.isHub()) {
                Toast.makeText(this, "本机是汇聚端，声音由其它设备送过来",
                        Toast.LENGTH_SHORT).show();
            } else if (hub == null) {
                Toast.makeText(this, "请先连接一台设备，它会告诉我们谁是汇聚端",
                        Toast.LENGTH_SHORT).show();
            } else {
                sourceIntent = true;
                setUserLeft(false);   // 用户主动「送入」= 明确发起新连接
                GraphStore.reattach();   // 重新入网：解除「全部断开」留下的退网状态
                ensurePermissionsThenStart();
            }
        }
        refresh();
    }

    private void stopSource() {
        if (CaptureService.sRunning) {
            startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP));
        }
        // 服务停下后这条采集授权就作废了（Android 14 一次性授权）：留着它会被
        // reconcile 当成"可以自动恢复采集"，下次启动时被系统拒绝 → 闪退。
        clearProjectionAuth();
    }

    /**
     * 停采集但**保留**投屏会话：本机暂时被设为汇聚端时用。
     *
     * 与 stopSource 的区别是不动 CaptureService 里那份 MediaProjection ——
     * 用户几乎必然会来回切换汇聚端，每次都要重新弹系统授权框是不可接受的
     * （而且 Android 14 的一次性授权本来就不给第二次机会）。
     */
    private void stopSourceKeepingAuth() {
        if (CaptureService.sRunning) {
            startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_PAUSE));
        }
        // 不调 clearProjectionAuth()：保留授权，切回音源时还能接着用。
    }

    private void ensurePermissionsThenStart() {
        List<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要录音权限才能采集音频", Toast.LENGTH_LONG).show();
            return;
        }
        requestProjection();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "没有授权，无法采集", Toast.LENGTH_SHORT).show();
            return;
        }
        // 注意：这份授权只在本次启动有效（Android 14 一次性令牌），启动后立刻丢弃；
        // 切换汇聚端由 CaptureService 内部 retarget 复用当前采集会话完成（协议 §6.2）。
        projectionResultCode = resultCode;
        projectionResultData = data;
        sourceIntent = true;
        setUserLeft(false);   // 用户授权采集 = 明确发起新连接
        GraphStore.reattach();   // 重新入网：解除「全部断开」留下的退网状态

        final GraphStore.Snapshot g = GraphStore.snapshot();
        final GraphStore.Node hub = g.hubNode();
        final String host = hub != null ? hub.ip : hubIp;
        final int port = hub != null ? hub.audioPort
                : (hubPort != 0 ? hubPort : GraphStore.AUDIO_PORT);
        if (host == null || host.isEmpty()) {
            Toast.makeText(this, "还没有汇聚端，先连接一台设备", Toast.LENGTH_SHORT).show();
            return;
        }
        final int streamId = g.streamIdForSelf();
        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putInt("port", port).putInt("streamId", streamId).apply();
        // Android 14 起采集授权是一次性的：这次启动之后立刻丢弃，不能再留着
        // 让 reconcile 自动重启采集 —— 用旧授权重新 startForeground 会被系统拒绝
        // 并抛 SecurityException（2026-10-28 闪退的根因）。切换目标由
        // CaptureService 内部 retarget 处理，不需要这份授权。
        try {
            startForegroundService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    .putExtra(CaptureService.EXTRA_HOST, host)
                    .putExtra(CaptureService.EXTRA_PORT, port)
                    .putExtra(CaptureService.EXTRA_STREAM_ID, streamId));
        } catch (Throwable t) {
            android.util.Log.e("AudioHub", "启动采集失败: "
                    + android.util.Log.getStackTraceString(t));
            Toast.makeText(this, "启动采集失败：" + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        } finally {
            clearProjectionAuth();
        }
    }

    /** 丢弃当前保存的采集授权（一次性令牌：用过一次就不能再用来启动采集）。 */
    private void clearProjectionAuth() {
        projectionResultCode = 0;
        projectionResultData = null;
    }

    // ------------------------------------------------------------ 刷新

    /**
     * 界面刷新的安全壳：refresh 会被 400ms 轮询之外的很多入口直接调用（同意邀请、
     * 断开、切换汇聚端、权限回调……）。只要里面抛一个运行时异常，Android 就会把
     * 整个进程带走 —— 用户看到的就是「点同意的瞬间 App 直接退出」。所以外壳统一
     * 兜住：记日志、不崩，界面下一轮 400ms 自己会恢复。
     */
    private void refresh() {
        try {
            refreshInner();
        } catch (Throwable t) {
            android.util.Log.e("AudioHub", "refresh 异常: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private void refreshInner() {
        // 采集服务启动/运行失败时会把原因放在 CaptureService.sLastError 里。
        // 这里取走：清掉已经失效的授权（否则 400ms 一次的 reconcile 会反复用旧授权
        // 重试 → 每次都崩），并明确告诉用户下一步该做什么。
        final String captureError = CaptureService.consumeError();
        if (captureError != null) {
            clearProjectionAuth();
            sourceIntent = false;
            Toast.makeText(this, captureError, Toast.LENGTH_LONG).show();
        }
        // 协议 §6.2：先按图谱校正本机职责（采集 / 接收），再刷新界面
        reconcile();

        final GraphStore.Snapshot g = GraphStore.snapshot();
        final boolean isHub = g.isHub();
        final GraphStore.Node hub = g.hubNode();
        final boolean srcRunning = CaptureService.sRunning;
        final boolean hubRunning = ReceiverService.sRunning;

        // 汇聚端显示：图谱是唯一依据
        if (isHub) {
            hubValue.setText("本机（" + android.os.Build.MODEL + "）");
        } else if (hub != null) {
            hubValue.setText(hub.displayName() + (g.isOnline(hub) ? "" : "（离线）"));
        } else {
            hubValue.setText("汇聚端未知");
        }
        if (hubSub != null) {
            final StringBuilder sb = new StringBuilder();
            sb.append("图谱 v").append(g.version).append(" · ")
              .append(g.nodes.size()).append(" 台设备");
            if (isHub && hubRunning) {
                sb.append(" · 已接收 ").append(ReceiverService.sStreamCount).append(" 路");
            } else if (srcRunning) {
                sb.append(" · 发送 ").append(CaptureService.sPackets).append(" 包");
            }
            if (!hintText.isEmpty()) sb.append(" · ").append(hintText);
            hubSub.setText(sb.toString());
        }

        // 本机声音
        sourceToggle.setText(srcRunning ? "停止" : "送入");
        sourceState.setText(srcRunning
                ? ("发送中 · " + CaptureService.sPackets + " 包 · "
                   + CaptureService.sLevelDbfs + " dBFS · 通道 " + CaptureService.sStreamId)
                : (isHub ? "本机是汇聚端"
                         : (hub != null ? "尚未开始发送" : "未连接")));

        // 控制界面显示条件：图谱里至少两台设备，或本机正在采集 / 接收。
        // userLeft 只在「用户明确发起新连接」时清除（本机声音送入 / 接受待确认请求 /
        // 设备列表或手动 IP 发起连接）；不因服务停止或远端残留图谱自动清除，
        // 否则断开后旧图谱（10s 才转离线）会把界面又拉回控制屏。
        // 图谱里至少两台设备、且汇聚端已知，才算真的进过会话；只有半张图
        // （远端广播的残留 / 刚发现的设备）时留在连接界面，避免用户看到的
        // 「没有汇聚端的双星界面」。
        final boolean hasHub = g.hubId != null && !g.hubId.isEmpty();
        // 只数**在线**的其他设备：图谱节点是并集、从不删除，退出后残留的旧节点会一直
        // 留在表里。以前用 nodes.size() >= 2，于是「全部断开后过一会儿又弹回控制屏，
        // 而画面上只有一个自己的汇聚端」（用户反馈）。
        int onlinePeers = 0;
        for (GraphStore.Node n : g.nodes) {
            if (n.id.equals(GraphStore.selfId())) continue;
            if (GraphStore.isOnline(n)) onlinePeers++;
        }
        final boolean showControl = !userLeft
                && (hubRunning || srcRunning || (onlinePeers >= 1 && hasHub));
        showScreen(showControl ? "control" : "connect");

        // 画全网图谱：每个节点一个球，汇聚端在中间（协议 §9.3）。
        // 布局权威 = hub 同步的 POS 帧（人工摆位）；表里没有的节点用确定性自动槽位兜底
        // （sceneIds 按 id 升序 → 各端对同一节点集算出一致布局）。
        // 手机端只读：不产生摆位、不写回任何场景持久化；hub 固定在原点。
        final java.util.List<String> sceneIds = new java.util.ArrayList<>();
        for (GraphStore.Node n : g.nodes) {
            if (n.id.equals(g.hubId)) continue;
            sceneIds.add(n.id);
        }
        java.util.Collections.sort(sceneIds);
        final java.util.Map<String, Scene3D.Placement> sceneLayout =
                Scene3D.roomLayout(sceneIds, null, null);

        final java.util.List<HubGraphView.Node> viewNodes = new java.util.ArrayList<>();
        for (GraphStore.Node n : g.nodes) {
            final HubGraphView.Node v = new HubGraphView.Node();
            v.id = n.id;
            v.name = n.displayName();   // displayName 保证非 null（drawText NPE 教训）
            v.kind = n.kind;
            v.hub = n.id.equals(g.hubId);
            v.self = n.id.equals(g.selfId);
            v.online = g.isOnline(n);
            if (v.hub) {
                v.wx = 0;
                v.wz = 0;
                v.slot = -1;
            } else {
                final int[] placedCm = PlacementStore.find(n.id);
                if (placedCm != null) {
                    // hub 同步来的人工摆位：厘米 → 米（x 右正；z 后正，屏幕上方 = -Z = 正前）
                    v.wx = placedCm[0] / 100.0;
                    v.wz = placedCm[1] / 100.0;
                    v.slot = -1;
                    v.pinned = true;
                    v.azDeg = Scene3D.wrap360(Math.atan2(v.wx, -v.wz) / Scene3D.DEG);
                } else {
                    final Scene3D.Placement placement = sceneLayout.get(n.id);
                    if (placement != null) {
                        v.wx = placement.pos.x;
                        v.wz = placement.pos.z;
                        v.slot = placement.slot;
                        v.pinned = placement.pinned;
                        v.azDeg = placement.azDeg;
                    }
                }
            }
            if (v.self) {
                v.streamId = srcRunning ? CaptureService.sStreamId : 0;
                v.level = srcRunning ? CaptureService.sLevelDbfs
                        : (isHub ? ReceiverService.sBusLevel : -120);
            } else {
                final int sid = ReceiverService.streamIdForIp(n.ip);
                v.streamId = sid;
                v.level = sid > 0 ? ReceiverService.sBusLevel : -120;
            }
            viewNodes.add(v);
        }
        graph.setData(viewNodes);

        // 空间音频：把每个节点在地面上的世界坐标报给汇聚端（ReceiverService 内部按 streamId 映射）。
        // 对应 PC 端 spatialFrame() 的 world 坐标；hub 在原点不用报。
        for (int i = 0; i < g.nodes.size() && i < viewNodes.size(); ++i) {
            final GraphStore.Node sn = g.nodes.get(i);
            final HubGraphView.Node vn = viewNodes.get(i);
            if (vn.hub) continue;
            final int sid = ReceiverService.streamIdForIp(sn.ip);
            if (sid > 0) {
                ReceiverService.setStreamPosition(sid, (float) vn.wx, 0f, (float) vn.wz);
            }
        }

        // 切换汇聚端后的"正在同步 / 已同步"提示
        if (!pendingSwitchTarget.isEmpty()) {
            if (pendingSwitchTarget.equals(g.hubId)) {
                if (switchSyncedAt == 0) switchSyncedAt = System.currentTimeMillis();
                graph.setStatus("已同步（v" + g.version + "）");
                if (System.currentTimeMillis() - switchSyncedAt > 2500) {
                    pendingSwitchTarget = "";
                    switchSyncedAt = 0;
                }
            } else {
                graph.setStatus("已切换，正在同步…");
            }
        } else {
            graph.setStatus("");
        }

        // 对端下发的角色指令：自动同步角色（方案 7.3 的远程分支）
        final String roleCmd = InviteListener.sRoleCommand;
        if (roleCmd != null) {
            InviteListener.sRoleCommand = null;
            if ("hub".equals(roleCmd)) {
                Toast.makeText(this, "对端已把本机设为汇聚端", Toast.LENGTH_SHORT).show();
                setLocalHub();
            } else if (roleCmd.startsWith("source|")) {
                final String[] p3 = roleCmd.split("\\|");
                if (p3.length >= 3) {
                    Discovery.Device d = new Discovery.Device();
                    d.ip = p3[1];
                    d.audioPort = parseInt(p3[2], 47000);
                    d.name = p3[1];
                    d.canSend = true;
                    d.canReceive = true;
                    Toast.makeText(this, "对端已把本机设为音源", Toast.LENGTH_SHORT).show();
                    followRemoteRole(d);
                }
            }
        }

        // 待确认的接入请求：两屏各有一行提示（点开进对话框），数据源是同一份
        final java.util.List<InviteListener.Pending> pending;
        synchronized (InviteListener.sPending) {
            pending = new java.util.ArrayList<>(InviteListener.sPending);
        }
        renderPending(pending);

        // 对端发来的邀请：弹窗确认（同意后要真的建立连接）
        if (InviteListener.sInvitePending && !inviteDialogShowing) {
            inviteDialogShowing = true;
            InviteListener.sInvitePending = false;
            final String name = InviteListener.sInviteName;
            final String ip = InviteListener.sInviteIp;
            final int port = InviteListener.sInvitePort > 0 ? InviteListener.sInvitePort : 47000;

            dialog()
                    .setTitle("连接请求")
                    .setMessage(name + "（" + ip + "）希望本机把声音送过去")
                    .setPositiveButton("同意", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            Discovery.Device d = new Discovery.Device();
                            d.name = name;
                            d.ip = ip;
                            d.audioPort = port;
                            d.canSend = true;
                            d.canReceive = true;
                            pickRemoteHub(d);
                        }
                    })
                    .setNegativeButton("拒绝", null)
                    .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(android.content.DialogInterface d) {
                            // 旧代码只置 true 不复位：第一次邀请弹过之后，后面所有邀请
                            // 都会被 !inviteDialogShowing 挡掉（用户反馈「再邀请没弹窗」）。
                            inviteDialogShowing = false;
                        }
                    })
                    .show();
        }
    }

    // ------------------------------------------------ 图谱职责 / 点击交互

    private boolean hasProjectionAuth() {
        return projectionResultCode == RESULT_OK && projectionResultData != null;
    }

    /** 一次性提示：只在内容变化时 toast，避免 400ms 轮询刷屏。 */
    private void graphHint(String text) {
        final String t = text != null ? text : "";
        if (t.equals(hintText)) return;
        hintText = t;
        if (!t.isEmpty()) Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    /**
     * 协议 §6.2：每个 400ms 轮询按图谱校正本机职责。
     *   isHub      → 保持 ReceiverService 运行，停采集
     *   isSource   → 采集目标 / streamId 对不上就让服务内部 retarget（不重建采集）；
     *                服务没跑时用刚拿到的一次性授权启动，没授权只提示
     *   汇聚端未知  → 两个服务都停，回到空闲
     */
    private void reconcile() {
        // 本机 IP 可能后到（WiFi 刚连上）：拿到后更新图谱里的自身节点
        final String myIp = localIp();
        if (!myIp.isEmpty() && !myIp.equals(GraphStore.selfIp())) {
            final boolean first = GraphStore.selfIp().isEmpty();
            GraphStore.setSelf(myIp, android.os.Build.MODEL);
            if (first) {
                InviteListener.requestSync();
            } else {
                InviteListener.broadcastGraph();
            }
        }

        // 用户已主动「断开」：不要再被远端残留图谱把服务拉起来。界面停在连接页，
        // 直到用户明确发起新连接（此时会把 userLeft 落盘为 false）。
        if (userLeft) {
            if (ReceiverService.sRunning) {
                startService(new Intent(this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_STOP));
            }
            if (CaptureService.sRunning) {
                sourceIntent = false;
                stopSource();
            }
            return;
        }

        final GraphStore.Snapshot g = GraphStore.snapshot();
        final boolean hubRunning = ReceiverService.sRunning;
        final boolean srcRunning = CaptureService.sRunning;

        if (g.isHub()) {
            // 汇聚端：本机负责收流放音，不能同时采集（回授）。
            //
            // 但**不能顺手把一次性的采集授权毁掉**：用户反复切换汇聚端时，
            // 每次切回"本机当音源"都需要重新弹一次系统投屏授权框 ——
            // 而 Android 14 的授权是一次性的，`stopSource()` 里那句
            // `clearProjectionAuth()` 会让"切回来之后再也发不出声音"。
            // 所以这里只停采集，保留授权；授权真的过期时由 reconcile 的
            // hasProjectionAuth() 分支提示用户点「送入」重新授权。
            //
            // 注意：**不能**把 sourceIntent 清掉。它是"用户想让本机送声"的意愿，
            // 不是"现在正在送"的状态；汇聚端期间本来就会被下面的 return 挡住。
            // 清掉它以后切回来就再也不会恢复采集 —— 用户看到的就是
            // 「切一次汇聚端之后就没声音了」。真正的显式关闭在 toggleSource /
            // 断开连接 / 用户把本机设为汇聚端（setLocalHub）这几处。
            hintText = "";
            if (srcRunning) stopSourceKeepingAuth();
            if (!hubRunning) {
                safeStartForegroundService(new Intent(this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_START)
                        .putExtra(ReceiverService.EXTRA_PORT, GraphStore.AUDIO_PORT));
            }
            return;
        }

        final GraphStore.Node hub = g.hubNode();
        if (hub == null && hubMissingSinceMs == 0L) hubMissingSinceMs = System.currentTimeMillis();
        if (hub != null) {
            hubMissingSinceMs = 0L;
            // 音源：同一台设备不能既发又收，接收服务要停掉
            if (hubRunning) {
                startService(new Intent(this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_STOP));
            }
            final int streamId = g.streamIdForSelf();
            final String desired = hub.ip + ":" + hub.audioPort + ":" + streamId;
            if (srcRunning) {
                final String actual = CaptureService.sHost + ":" + CaptureService.sPort
                        + ":" + CaptureService.sStreamId;
                if (!desired.equals(actual)) {
                    // 目标或音源序号变了：只换发送目标，复用正在跑的采集与 MediaProjection
                    // 会话。Android 14 的采集授权一次性，之前"停旧服务再用旧授权重启"会
                    // 在 startForeground 处被系统拒绝 → 进程闪退（2026-10-28 报的 bug）。
                    startCapture(hub, streamId);
                } else {
                    hintText = "";
                }
            } else if (sourceIntent) {
                // 有一次性授权 → 直接起采集；
                // 没有授权但服务里还留着投屏会话（之前被设为汇聚端暂停过）→
                // ACTION_START 会走 CaptureService 的 resume 分支，不需要再弹授权框。
                if (hasProjectionAuth() || CaptureService.sHasProjection) {
                    hintText = "";
                    startCapture(hub, streamId);
                } else {
                    graphHint("本机已设为音源，开始采集后生效");
                }
            }
            return;
        }

        // 汇聚端未知（刚扫手还没同步到 / 对端 GRAPH 还没到）：
        // 先给 HUB_MISSING_GRACE_MS 宽限期，期间用上次记住的 hubIp 兜底继跑；
        // 超时还没同步到才停。
        final GraphStore.Node remembered = rememberedHub();
        final boolean withinGrace = System.currentTimeMillis() - hubMissingSinceMs < HUB_MISSING_GRACE_MS;
        if (remembered != null && withinGrace && (srcRunning || hubRunning)) {
            if (srcRunning) {
                // 只是 retarget（服务内目标没变就直接返回），不重建采集会话
                startCapture(remembered, g.streamIdForSelf());
            }
            return;
        }
        if (hubRunning) {
            startService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_STOP));
        }
        if (srcRunning) stopSource();
    }

    /**
     * 用上次记住的 hubIp/hubPort 构造一个临时节点：
     * 图谱里 hubId 指向的节点缺失时（对端 GRAPH 还没到 / 节点 id 对不上）
     * 用它兜底，而不是立刻把采集停掉。
     */
    private GraphStore.Node rememberedHub() {
        if (hubIp == null || hubIp.isEmpty()) return null;
        final int port = hubPort > 0 ? hubPort : GraphStore.AUDIO_PORT;
        return new GraphStore.Node(hubIp + ":" + port, hubIp, port,
                hubName != null ? hubName : hubIp, GraphStore.KIND_OTHER, 0);
    }

    private void startCapture(final GraphStore.Node hub, final int streamId) {
        final boolean alreadyRunning = CaptureService.sRunning;
        // 两条路都能启动采集：
        //   1) 手上还有一次性授权（用户刚点过「送入」/ 刚同意邀请）；
        //   2) 服务里还留着上次暂停保住的投屏会话（来回切汇聚端时的常态）。
        // 只看 1) 会让"切一次汇聚端就再也发不出声音"——那正是用户报的现象。
        if (!alreadyRunning && !hasProjectionAuth() && !CaptureService.sHasProjection) {
            // 两条都没有：只能让用户点「送入」重新授权（一次性授权真的用掉了）
            graphHint("本机已设为音源，开始采集后生效");
            return;
        }
        try {
            startForegroundService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, projectionResultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, projectionResultData)
                    .putExtra(CaptureService.EXTRA_HOST, hub.ip)
                    .putExtra(CaptureService.EXTRA_PORT, hub.audioPort)
                    .putExtra(CaptureService.EXTRA_STREAM_ID, streamId));
            if (!alreadyRunning) {
                // 授权只用这一次：服务已经在跑时是 retarget（不需要授权），
                // 其余情况启动完就丢弃，避免之后被当成可复用的授权。
                // （复用暂停保住的会话不需要——那条路走的是 sHasProjection）
                clearProjectionAuth();
            }
        } catch (Throwable t) {
            android.util.Log.e("AudioHub", "启动采集失败: "
                    + android.util.Log.getStackTraceString(t));
            graphHint("启动采集失败：" + t.getClass().getSimpleName());
        }
    }

    /** 双击节点 = PC 端 dblclick → onActivate：直接把汇聚端切过去（单击仍是音量面板+确认框）。
     *  （旧的相机复位/拖动 pin 入口随 3D 视图一起删除了，这里只剩角色切换。） */
    private void onSceneDoubleTap(final HubGraphView.Node viewNode) {
        if (viewNode == null || viewNode.id == null) return;
        final GraphStore.Snapshot g = GraphStore.snapshot();
        final GraphStore.Node target = g.find(viewNode.id);
        if (target == null || target.id.equals(g.hubId)) return;
        if (!GraphStore.switchHub(target.id)) return;
        pendingSwitchTarget = target.id;
        switchSyncedAt = 0;
        InviteListener.broadcastGraph();   // 本地变化：2 次 / 60ms（协议 §4.5）
        refresh();
        Toast.makeText(this, "已切换汇聚端", Toast.LENGTH_SHORT).show();
    }

    private void onGraphNodeTap(final HubGraphView.Node viewNode) {
        if (viewNode == null || viewNode.id == null) return;
        final GraphStore.Snapshot g = GraphStore.snapshot();
        final GraphStore.Node target = g.find(viewNode.id);
        if (target == null) return;

        // 选中高亮 + 音量面板（有流才可调；协议 §9.3：点带流的球调音量）
        graph.setSelected(target.id);
        final int streamId = viewNode.self
                ? (CaptureService.sRunning ? CaptureService.sStreamId : 0)
                : ReceiverService.streamIdForIp(target.ip);
        showVolumePanelForStream(streamId, target.displayName());

        // 当前汇聚端 / 本身是汇聚端时点自己：只显示信息，不切换
        if (target.id.equals(g.hubId)) return;
        confirmSwitchHub(target, g.isOnline(target));
    }

    /** 切换汇聚端确认（协议 §4.5 / §8：离线节点切换前要警告）。 */
    private void confirmSwitchHub(final GraphStore.Node target, final boolean online) {
        final String msg = "把汇聚端切到「" + target.displayName() + "」？"
                + (online ? "\n其它设备的声音会改送到它那里播放。"
                          : "\n它当前离线，切换后会等它恢复在线。");
        dialog()
                .setTitle("切换汇聚端")
                .setMessage(msg)
                .setPositiveButton("切换", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        GraphStore.reattach();   // 主动切汇聚端 = 重新入网
                        if (!GraphStore.switchHub(target.id)) {
                            Toast.makeText(MainActivity.this, "节点已不在图谱里",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        pendingSwitchTarget = target.id;
                        switchSyncedAt = 0;
                        InviteListener.broadcastGraph();   // 本地变化：2 次 / 60ms
                        refresh();
                        Toast.makeText(MainActivity.this, "已切换，正在同步…",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 主动扫描：发一帧 WHO 让所有设备立刻回信标，1.2 秒后汇总结果。
     * 不做成"一直转圈"：UDP 是尽力而为，等一个固定窗口就够了。
     */
    private void scanNow(final Button btn) {
        btn.setEnabled(false);
        btn.setText("扫描中…");
        scanHint.setTextColor(COLOR_DIM);
        scanHint.setText("正在向局域网广播探测…");
        if (discovery != null) discovery.probe();

        poller.postDelayed(new Runnable() {
            @Override
            public void run() {
                btn.setEnabled(true);
                btn.setText("扫描");
                final int n = discovery != null ? discovery.peers().size() : 0;
                if (n > 0) {
                    scanHint.setTextColor(COLOR_ACCENT);
                    scanHint.setText("扫描完成，发现 " + n + " 台设备");
                } else {
                    scanHint.setTextColor(COLOR_WARN);
                    scanHint.setText("没有发现设备。请确认：① 电脑已打开 AudioHub；"
                            + "② 手机与电脑在同一 WiFi / 网段（本机 " + Discovery.localIp() + "）");
                }
            }
        }, 1200);
    }

    /** 手动填 IP 连过去：广播被拦时的兜底手段。 */
    private void showManualAddDialog() {
        final EditText input = new EditText(this);
        input.setHint("电脑的 IP，例如 192.168.1.10（端口可省）");
        input.setText(hubIp != null ? hubIp : "");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setTextColor(COLOR_TEXT);
        dialog()
                .setTitle("手动添加设备")
                .setMessage("填电脑上显示的地址（可只填 IP，端口默认 47000）")
                .setView(input)
                .setPositiveButton("连接", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String text = input.getText().toString().trim();
                        if (text.isEmpty()) return;
                        int port = 47000;
                        String ip = text;
                        final int colon = text.lastIndexOf(':');
                        if (colon > 0) {
                            ip = text.substring(0, colon);
                            port = parseInt(text.substring(colon + 1), 47000);
                        }
                        Discovery.Device d = new Discovery.Device();
                        d.name = ip + "（手动）";
                        d.ip = ip;
                        d.audioPort = port;
                        d.canSend = true;
                        d.canReceive = true;
                        manualPeers.clear();
                        manualPeers.add(d);
                        renderPeers(discovery != null ? discovery.peers()
                                                      : new java.util.ArrayList<Discovery.Device>());
                        // 先探一下，对方在线就会回信标
                        if (discovery != null) discovery.probeTo(ip);
                        pickRemoteHub(d);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把一个已知地址放进手动列表（上次连过的那个）。 */
    private void addManualPeer(String ip, String name, int port) {
        for (Discovery.Device d : manualPeers) {
            if (d.ip.equals(ip)) return;
        }
        Discovery.Device d = new Discovery.Device();
        d.name = name + "（上次连接）";
        d.ip = ip;
        d.audioPort = port > 0 ? port : 47000;
        d.canSend = true;
        d.canReceive = true;
        manualPeers.add(d);
    }

/** 待确认请求的一行提示（两屏共用）；没有请求时 GONE，点开走对话框。 */
    private TextView pendingBanner() {
        final TextView tv = new TextView(this);
        tv.setTextSize(12.5f);
        tv.setTextColor(COLOR_WARN);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        // 琥珀玻璃条：rgba(255,209,102,.10) 填充 + .38 描边 + 10dp 圆角
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(10));
        bg.setColor(0x1AFFD166);
        bg.setStroke(dp(1), 0x61FFD166);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(6));
        tv.setLayoutParams(lp);
        tv.setVisibility(View.GONE);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showPendingDialog(); }
        });
        return tv;
    }

    /** 待确认请求：主界面只显示一行提示，点开对话框处理（一屏装完、不滚动）。 */
    private void renderPending(List<InviteListener.Pending> pending) {
        final String text = pending.isEmpty()
                ? "" : ("有 " + pending.size() + " 个待确认的接入请求 · 点击处理");
        final TextView[] banners = {connectPending, ctrlPending};
        for (TextView tv : banners) {
            if (tv == null) continue;
            tv.setText(text);
            tv.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** 待确认请求对话框：同意 = 本机切汇聚端并保证接收服务在跑（协议 §5）。 */
    private void showPendingDialog() {
        final java.util.List<InviteListener.Pending> pending;
        synchronized (InviteListener.sPending) {
            pending = new java.util.ArrayList<>(InviteListener.sPending);
        }
        if (pending.isEmpty()) return;

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(4));
        for (final InviteListener.Pending p : pending) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView who = new TextView(this);
            who.setText(p.name + "  ·  " + p.ip);
            who.setTextSize(13.5f);
            who.setTextColor(COLOR_TEXT);
            info.addView(who);
            TextView desc = new TextView(this);
            desc.setText("请求把声音送到本机");
            desc.setTextSize(11.5f);
            desc.setTextColor(COLOR_DIM);
            info.addView(desc);
            row.addView(info);

            Button ok = textButton("同意", COLOR_ACCENT);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { acceptPending(p.streamId); }
            });
            row.addView(ok);

            Button no = textButton("拒绝", COLOR_WARN);
            no.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    InviteListener.reject(p.streamId);
                    refresh();
                }
            });
            row.addView(no);
            list.addView(row);
        }

        dialog()
                .setTitle("接入请求")
                .setView(list)
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 同意一条接入请求（旧逻辑：同意"把声音送到本机"= 本机要当汇聚端，协议 §5）：
     * 先切图谱 hub，再保证接收服务在跑，否则对方发来的流没人收。
     */
    private void acceptPending(int streamId) {
        InviteListener.accept(streamId);
        setUserLeft(false);   // 用户同意接入 = 明确发起新连接
        GraphStore.reattach();   // 重新入网：解除「全部断开」留下的退网状态
        if (!GraphStore.isHub()) {
            final String sid = GraphStore.selfId();
            if (!sid.isEmpty() && GraphStore.switchHub(sid)) {
                pendingSwitchTarget = sid;
                switchSyncedAt = 0;
                InviteListener.broadcastGraph();   // 本地变化：2 次 / 60ms
            }
        }
        if (!ReceiverService.sRunning) {
            safeStartForegroundService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_START)
                    .putExtra(ReceiverService.EXTRA_PORT, GraphStore.AUDIO_PORT));
        }
        refresh();
    }

    /**
     * 设备列表（自动发现 + 手动 / 上次连接）：主界面只放摘要，完整列表进对话框。
     * 手动 / 上次连接的设备排在前面——广播被拦时只有它们能用。
     */
    private void renderPeers(List<Discovery.Device> autoPeers) {
        final java.util.List<Discovery.Device> peers = new java.util.ArrayList<>(manualPeers);
        for (Discovery.Device d : autoPeers) {
            boolean dup = false;
            for (Discovery.Device m : manualPeers) {
                if (m.ip.equals(d.ip) && m.audioPort == d.audioPort) { dup = true; break; }
            }
            if (!dup) peers.add(d);
        }
        currentPeers.clear();
        currentPeers.addAll(peers);
        if (devSummary != null) {
            // 带本机 IP：扫不到时最常见的原因是两台设备不在同一网段
            devSummary.setText(peers.isEmpty()
                    ? ("正在扫描附近设备…  本机 IP " + localIp())
                    : ("已发现 " + peers.size() + " 台设备 · 点「设备列表」连接/邀请"));
        }
    }

    /** 设备列表对话框：每行可「邀请」（本机当 hub 时）/「连入」。 */
    private void showPeersDialog() {
        if (currentPeers.isEmpty() && discovery != null) {
            renderPeers(discovery.peers());
        }
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(4));
        if (currentPeers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂未发现设备。请确认：① 电脑已打开 AudioHub；"
                    + "② 手机与电脑在同一 WiFi / 网段（本机 " + localIp() + "）");
            empty.setTextSize(12.5f);
            empty.setTextColor(COLOR_DIM);
            empty.setLineSpacing(0, 1.3f);
            empty.setPadding(dp(2), dp(6), dp(2), dp(6));
            list.addView(empty);
        } else {
            for (Discovery.Device d : currentPeers) {
                list.addView(buildPeerRow(d));
            }
        }
        dialog()
                .setTitle("设备列表")
                .setView(list)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 构建一行设备卡片：邀请 / 连入。 */
    private LinearLayout buildPeerRow(final Discovery.Device d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(4), dp(6));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(d.name + "  ·  " + d.roleText());
        name.setTextSize(13.5f);
        name.setTextColor(COLOR_TEXT);
        info.addView(name);
        TextView addr = new TextView(this);
        addr.setText(d.ip + ":" + d.audioPort);
        addr.setTextSize(11.5f);
        addr.setTextColor(COLOR_DIM);
        info.addView(addr);
        row.addView(info);

        Button invite = textButton("邀请", COLOR_ACCENT);
        invite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { invitePeer(d); }
        });
        row.addView(invite);

        Button connect = textButton("连入", COLOR_ACCENT);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickRemoteHub(d); }
        });
        row.addView(connect);
        return row;
    }

    private void invitePeer(final Discovery.Device d) {
        if (!isLocalHub() || !ReceiverService.sRunning) {
            Toast.makeText(this, "只有汇聚端能邀请别人，请先把汇聚端设为本机",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.DatagramSocket s = new java.net.DatagramSocket();
                    String invite = "CONN-INVITE|AudioHub 手机|" + localIp() + ":47000\n";
                    byte[] data = invite.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    s.send(new java.net.DatagramPacket(data, data.length,
                            java.net.InetAddress.getByName(d.ip), d.audioPort + 2));
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }, "audiohub-invite").start();
        Toast.makeText(this, "已向 " + d.name + " 发出邀请", Toast.LENGTH_SHORT).show();
    }

    /**
     * 本机局域网地址。这里**必须**用 Discovery 那一份实现，不要自己再写一遍枚举 ——
     * 之前这里有一份"返回第一个非回环 IPv4"的简化版，而 reconcile() 每 400ms 用它比对
     * GraphStore.selfIp()：一旦网卡枚举顺序变化（插拔热点 / VPN / 蜂窝待机唤醒），
     * 就会挑到另一个地址，selfId 跟着漂移 → streamIdForSelf() 变 → 触发 retarget，
     * 而 retarget 会先发 CONN-BYE，表现为「莫名其妙断一下」。
     * Discovery 版有 wlan/eth/ap/en 优先 + 跳过 tun/ppp/rmnet/clat/169.254/198.18/100.64 的过滤。
     */
    private String localIp() {
        return Discovery.localIp();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
