package com.audiohub.source;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.util.TypedValue;

import java.util.List;

/**
 * AudioHub。
 *
 * 两屏流程：
 *   连接界面 —— 选汇聚端、连设备；连接建立后自动进控制界面
 *   控制界面 —— 可视化网络图：球 = 设备，线 = 连接，汇聚端的球更大；
 *               点任意球调它的音量，实时生效。
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
    private LinearLayout peerList;
    private TextView scanHint;
    // 手动添加 / 上次连接的设备（广播被拦时靠它们）
    private final java.util.List<Discovery.Device> manualPeers = new java.util.ArrayList<>();
    // 控制界面里的"新设备"区（会话进行中也得能接人）
    private LinearLayout ctrlReqList;
    private LinearLayout connectReqList;

    // 会话是否成立。不能只看"服务是否在跑"：切换角色时旧服务先停、
    // 新服务几百毫秒后才起，这个间隙会让界面误判成"会话结束"而弹回连接页。
    private boolean sessionActive = false;
    private int idleTicks = 0;
    // 用户主动点了"断开"：在服务真正停掉之前，不能再被 sessionActive 拉回来
    private boolean userLeft = false;

    private LinearLayout ctrlPeerList;

    private HubGraphView graph;
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

    /** 汇聚端：null = 本机（默认，因为电脑跑着汇聚端）；否则是那台设备。 */
    private String hubIp;
    private int hubPort;
    private String hubName;

    private static final int COLOR_BG = 0xFF0F1420;
    private static final int COLOR_CARD = 0xFF1A2130;
    private static final int COLOR_TEXT = 0xFFEEF2F7;
    private static final int COLOR_DIM = 0xFF8B98A8;
    private static final int COLOR_LINE = 0xFF2A3348;
    private static final int COLOR_ACCENT = 0xFF3B82F6;
    private static final int COLOR_WARN = 0xFFFBBF24;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        // 上次如果崩过，把堆栈直接摆到眼前——用户不用连电脑也能发给我们
        showLastCrashIfAny();

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
        poller.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.stop();
        InviteListener.get().stop();
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
        new AlertDialog.Builder(this)
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
        return tv;
    }

    private TextView sub(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(COLOR_DIM);
        tv.setPadding(0, dp(3), 0, 0);
        return tv;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(p);
        card.setBackgroundColor(COLOR_CARD);
        return card;
    }

    private Button textButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.getBackground().setAlpha(0);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        return b;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        root.setBackgroundColor(COLOR_BG);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AudioHub");
        title.setTextSize(24);
        title.setLetterSpacing(0.02f);
        title.setTextColor(COLOR_TEXT);
        root.addView(title);

        TextView subT = new TextView(this);
        subT.setText("多台设备的声音，汇到一处");
        subT.setTextSize(12.5f);
        subT.setTextColor(COLOR_DIM);
        subT.setPadding(0, dp(4), 0, dp(14));
        root.addView(subT);

        // ================= 连接界面 =================
        screenConnect = new LinearLayout(this);
        screenConnect.setOrientation(LinearLayout.VERTICAL);

        connectReqList = new LinearLayout(this);
        connectReqList.setOrientation(LinearLayout.VERTICAL);
        screenConnect.addView(connectReqList);

        TextView connectHint = sub("先建立连接，再在控制界面里指定谁当汇聚端");
        connectHint.setPadding(0, 0, 0, dp(10));
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
        screenConnect.addView(devRow);

        scanHint = sub("");
        scanHint.setPadding(0, 0, 0, dp(8));
        screenConnect.addView(scanHint);

        peerList = new LinearLayout(this);
        peerList.setOrientation(LinearLayout.VERTICAL);
        screenConnect.addView(peerList);
        root.addView(screenConnect);

        // ================= 控制界面 =================
        screenControl = new LinearLayout(this);
        screenControl.setOrientation(LinearLayout.VERTICAL);
        screenControl.setVisibility(View.GONE);

        graph = new HubGraphView(this, null);
        graph.setOnNodeTap(new HubGraphView.OnNodeTap() {
            @Override
            public void onTap(HubGraphView.Node node) {
                graph.setSelected(node.key);
                showVolumePanel(node);
            }
        });
        screenControl.addView(graph, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        volPanel = new LinearLayout(this);
        volPanel.setOrientation(LinearLayout.VERTICAL);
        volPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.setMargins(0, dp(6), 0, dp(10));
        volPanel.setLayoutParams(vp);
        volPanel.setBackgroundColor(COLOR_CARD);
        volName = new TextView(this);
        volName.setTextSize(14);
        volName.setTextColor(COLOR_TEXT);
        volPanel.addView(volName);

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.setPadding(0, dp(8), 0, 0);
        volSlider = new SeekBar(this);
        volSlider.setMax(150);
        volSlider.setProgress(100);
        volSlider.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || selectedKey == null) return;
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

        // 会话进行中也可能有新设备接入：待确认请求 + 添加设备入口
        ctrlReqList = new LinearLayout(this);
        ctrlReqList.setOrientation(LinearLayout.VERTICAL);
        screenControl.addView(ctrlReqList);

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
            public void onClick(View v) {
                ctrlPeerList.setVisibility(
                        ctrlPeerList.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        ctrlDevRow.addView(ctrlAdd);
        screenControl.addView(ctrlDevRow);

        ctrlPeerList = new LinearLayout(this);
        ctrlPeerList.setOrientation(LinearLayout.VERTICAL);
        ctrlPeerList.setVisibility(View.GONE);
        screenControl.addView(ctrlPeerList);

        // 汇聚端设定：连接建立之后才出现（方案第 4.2 节）
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
        hubSub = sub("当前承担收流 / 混音 / 播放的一端");
        hubInfoC.addView(hubSub);
        hubRowC.addView(hubInfoC);
        Button switchHub = textButton("切换", COLOR_ACCENT);
        switchHub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showHubPicker(); }
        });
        hubRowC.addView(switchHub);
        hubBar.addView(hubRowC);
        screenControl.addView(hubBar);

        Button leave = textButton("断开所有连接", COLOR_WARN);
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 接收端：清掉所有流
                startService(new Intent(MainActivity.this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_STOP));
                stopSource();
                sessionActive = false;
                userLeft = true;      // 立刻回连接界面，且不被服务状态拉回
                idleTicks = 0;
                refresh();
                Toast.makeText(MainActivity.this, "已断开", Toast.LENGTH_SHORT).show();
            }
        });
        screenControl.addView(leave);
        root.addView(screenControl);

        statusViewPlaceholder();
        return scroll;
    }

    private TextView statusViewPlaceholder() {
        // 占位：状态文本并入各卡片，不再单独放
        return null;
    }

    // ------------------------------------------------------------ 两屏切换

    private void showScreen(String next) {
        screenControl.setVisibility("control".equals(next) ? View.VISIBLE : View.GONE);
        screenConnect.setVisibility("control".equals(next) ? View.GONE : View.VISIBLE);
    }

    private String selectedKey;

    private void showVolumePanel(HubGraphView.Node node) {
        selectedKey = node.key;
        final boolean receiving = ReceiverService.sRunning
                && ReceiverService.sStreamCount > 0;
        if (!receiving) {
            // 本机当音源时，音量由对端控制，这里只说明状态
            volName.setText(("hub".equals(node.key) ? "汇聚端" : node.name)
                    + " · 音量由汇聚端调节");
            volSlider.setEnabled(false);
            volSlider.setProgress(100);
            volPct.setText("—");
            return;
        }
        volSlider.setEnabled(true);
        volName.setText(node.hub ? "汇聚端总音量" : node.name);
        volPct.setText("100%");
        volSlider.setProgress(100);
    }

    private void applyVolume(float gain) {
        if (selectedKey == null || "hub".equals(selectedKey)) return;
        try {
            ReceiverService.setStreamGain(Integer.parseInt(selectedKey), gain);
        } catch (NumberFormatException ignored) {
        }
    }

    // ------------------------------------------------------------ 汇聚端

    private boolean isLocalHub() {
        return hubIp == null;
    }

    private void showHubPicker() {
        final List<Discovery.Device> peers = discovery.peers();
        final String[] names = new String[peers.size() + 1];
        names[0] = "本机（" + android.os.Build.MODEL + "）";
        final Discovery.Device[] devices = new Discovery.Device[peers.size()];
        for (int i = 0; i < peers.size(); ++i) {
            devices[i] = peers.get(i);
            names[i + 1] = devices[i].name + "（" + devices[i].ip + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle("选哪台设备当汇聚端")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) setLocalHub();
                        else pickRemoteHub(devices[which - 1]);
                    }
                })
                .show();
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
        hubIp = null;
        hubName = null;
        saveHub();
        stopSource();
        safeStartForegroundService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_START)
                .putExtra(ReceiverService.EXTRA_PORT, 47000));
        refresh();
        Toast.makeText(this, "本机已成为汇聚端", Toast.LENGTH_SHORT).show();
    }

    private void pickRemoteHub(final Discovery.Device d) {
        // 先把本地角色与界面切过去，握手放到后台。
        // 之前是先阻塞握手再改状态：对方没点同意时会卡满 30 秒，
        // 期间界面一点反应都没有，看起来就是"切换没生效"。
        hubIp = d.ip;
        hubPort = d.audioPort;
        hubName = d.name;
        saveHub();
        sessionActive = true;
        idleTicks = 0;
        startService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_STOP));
        refresh();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = ReceiverService.respondToInvite(
                        d.ip, d.audioPort, android.os.Build.MODEL);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            Toast.makeText(MainActivity.this,
                                    "对方还没有同意连接，界面已切换但声音还没接通",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(MainActivity.this, "已连接 " + d.name,
                                Toast.LENGTH_SHORT).show();
                        ensurePermissionsThenStart();
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

    // ------------------------------------------------------------ 本机声音

    private void toggleSource() {
        if (CaptureService.sRunning) {
            stopSource();
        } else if (hubIp != null) {
            ensurePermissionsThenStart();
        } else {
            Toast.makeText(this, "请先选择一台远端设备当汇聚端", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void stopSource() {
        if (CaptureService.sRunning) {
            startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP));
        }
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
        final int port = hubPort != 0 ? hubPort : 47000;
        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putInt("port", port).putInt("streamId", 1).apply();

        startForegroundService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_HOST, hubIp)
                .putExtra(CaptureService.EXTRA_PORT, port)
                .putExtra(CaptureService.EXTRA_STREAM_ID, 1));
    }

    // ------------------------------------------------------------ 刷新

    private void refresh() {
        // 汇聚端显示
        hubValue.setText(isLocalHub() ? "本机" : (hubName != null ? hubName : hubIp));
        if (hubSub != null) {
            if (isLocalHub()) {
                hubSub.setText(ReceiverService.sRunning
                        ? ("已接收 " + ReceiverService.sStreamCount + " 路 · 总线 "
                           + String.format("%.1f", ReceiverService.sBusLevel) + " dBFS")
                        : "接收服务未启动");
            } else {
                hubSub.setText(CaptureService.sRunning
                        ? ("正在发送 · " + CaptureService.sPackets + " 包")
                        : "尚未开始发送");
            }
        }
        if (hubSub != null) {
            if (isLocalHub()) {
                hubSub.setText(ReceiverService.sRunning
                        ? ("已接收 " + ReceiverService.sStreamCount + " 路 · 总线 "
                           + String.format("%.1f", ReceiverService.sBusLevel) + " dBFS")
                        : "接收服务未启动");
            } else {
                hubSub.setText(CaptureService.sRunning
                        ? ("正在发送 · " + CaptureService.sPackets + " 包")
                        : "尚未开始发送");
            }
        }

        // 本机声音
        final boolean srcRunning = CaptureService.sRunning;
        sourceToggle.setText(srcRunning ? "停止" : "送入");
        sourceState.setText(srcRunning
                ? ("发送中 · " + CaptureService.sPackets + " 包 · "
                   + CaptureService.sLevelDbfs + " dBFS")
                : (isLocalHub() ? "本机是汇聚端" : "未发送"));

        // 只要"有连接"就进控制界面——不论本机是汇聚端还是音源。
        // 两端看到的是同一张网络图，只是谁是中间那个大球不同。
        final boolean hubRunning = ReceiverService.sRunning;
        final boolean receiving = hubRunning && ReceiverService.sStreamCount > 0;

        // 会话成立与否用粘滞标志判断，并给切换留出宽限：
        // 服务停了不代表会话结束（可能正在切换），连续 15 个 tick
        // （约 6 秒）都没有任何服务在跑，才认为会话真的结束了。
        if (userLeft) {
            // 服务还没停干净时不要重置会话；两个服务都停了才解除
            if (!hubRunning && !srcRunning) userLeft = false;
            sessionActive = false;
            idleTicks = 0;
        } else if (hubRunning || srcRunning) {
            sessionActive = true;
            idleTicks = 0;
        } else if (sessionActive) {
            idleTicks++;
            if (idleTicks > 15) {
                sessionActive = false;
                idleTicks = 0;
            }
        }
        final boolean inSession = sessionActive;
        showScreen(inSession ? "control" : "connect");

        if (inSession) {
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<Double> levels = new java.util.ArrayList<>();
            String hubLabel;
            double hubLevel;

            if (isLocalHub()) {
                // 本机是汇聚端：大球是本机，小球是各路音源。
                // 注意判断依据是"角色"，不是"有没有流"——刚切换过来的那一秒
                // 流还是 0，若按流判断就会错误地画成音源视图（图看起来没变）。
                hubLabel = "本机";
                hubLevel = ReceiverService.sBusLevel;
                for (int i = 1; i <= ReceiverService.sStreamCount; ++i) {
                    ids.add(i);
                    names.add("设备 " + i);
                    levels.add(ReceiverService.sBusLevel);
                }
            } else {
                // 本机是音源：大球是对面的汇聚端，小球是本机。
                // 注意：切换角色的瞬间 hubIp/hubName 可能都还是 null，
                // 而这个值会一路传到 Canvas.drawText() —— drawText(null) 是
                // 直接抛 NPE 的（UI 线程 = 闪退）。所以必须有兜底。
                hubLabel = hubName != null ? hubName
                        : (hubIp != null ? hubIp : "汇聚端");
                hubLevel = -120;
                ids.add(0);
                names.add("本机");
                levels.add((double) CaptureService.sLevelDbfs);
            }
            graph.setData(hubLabel, hubLevel, names, ids, levels);
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
                    pickRemoteHub(d);
                }
            }
        }

        // 待确认的接入请求：两块屏都渲染，会话进行中也能接人
        final java.util.List<InviteListener.Pending> pending;
        synchronized (InviteListener.sPending) {
            pending = new java.util.ArrayList<>(InviteListener.sPending);
        }
        if (connectReqList != null) renderPending(connectReqList, pending);
        if (ctrlReqList != null) renderPending(ctrlReqList, pending);

        // 对端发来的邀请：弹窗确认（同意后要真的建立连接）
        if (InviteListener.sInvitePending && !inviteDialogShowing) {
            inviteDialogShowing = true;
            InviteListener.sInvitePending = false;
            final String name = InviteListener.sInviteName;
            final String ip = InviteListener.sInviteIp;
            final int port = InviteListener.sInvitePort > 0 ? InviteListener.sInvitePort : 47000;

            new AlertDialog.Builder(this)
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
                    .show();
        }
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
        input.setHint("电脑的 IP，例如 10.135.129.59");
        input.setText(hubIp != null ? hubIp : "");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setTextColor(COLOR_TEXT);
        new AlertDialog.Builder(this)
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

    /** 待确认请求：连接界面和控制界面各渲染一份。 */
    private String pendingSig = "";

    private void renderPending(LinearLayout container, List<InviteListener.Pending> pending) {
        final StringBuilder sig = new StringBuilder();
        for (InviteListener.Pending p : pending) {
            sig.append(p.streamId).append(':').append(p.name).append(';');
        }
        if (sig.toString().equals(pendingSig)) return;
        pendingSig = sig.toString();

        container.removeAllViews();
        if (pending.isEmpty()) return;
        for (final InviteListener.Pending p : pending) {
            LinearLayout row = card();
            TextView who = new TextView(this);
            who.setText(p.name + "  ·  " + p.ip);
            who.setTextSize(14);
            who.setTextColor(COLOR_TEXT);
            row.addView(who);
            row.addView(sub("请求把声音送到本机"));
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, dp(8), 0, 0);
            Button ok = textButton("同意", COLOR_ACCENT);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    InviteListener.accept(p.streamId);
                    // 同意"把声音送到本机"就意味着本机要当汇聚端 ——
                    // 不启动接收服务的话，对方发过来的流没人收，等于白同意。
                    if (!ReceiverService.sRunning) {
                        hubIp = null;
                        hubName = null;
                        saveHub();
                        safeStartForegroundService(new Intent(MainActivity.this,
                                ReceiverService.class)
                                .setAction(ReceiverService.ACTION_START)
                                .putExtra(ReceiverService.EXTRA_PORT, 47000));
                    }
                    pendingSig = "";
                    refresh();
                }
            });
            Button no = textButton("拒绝", COLOR_WARN);
            no.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { InviteListener.reject(p.streamId); }
            });
            btns.addView(ok);
            btns.addView(no);
            row.addView(btns);
            container.addView(row);
        }
    }

    private void renderPeers(List<Discovery.Device> autoPeers) {
        peerList.removeAllViews();
        if (ctrlPeerList != null) ctrlPeerList.removeAllViews();
        // 手动 / 上次连接的设备排在前面（广播被拦时只有它们能用）
        final java.util.List<Discovery.Device> peers =
                new java.util.ArrayList<>(manualPeers);
        for (Discovery.Device d : autoPeers) {
            boolean dup = false;
            for (Discovery.Device m : manualPeers) {
                if (m.ip.equals(d.ip) && m.audioPort == d.audioPort) { dup = true; break; }
            }
            if (!dup) peers.add(d);
        }
        if (peers.isEmpty()) {
            TextView tv = new TextView(this);
            // 把本机 IP 显示出来：扫不到时最常见的原因是两台设备不在同一网段
            // （比如电脑插网线 10.x，手机连 WiFi 192.168.x）
            tv.setText("正在扫描附近设备…\n本机 IP " + localIp()
                    + "，请确认电脑与本机在同一个 WiFi / 网段");
            tv.setTextSize(12.5f);
            tv.setTextColor(COLOR_DIM);
            tv.setLineSpacing(0, 1.3f);
            tv.setPadding(dp(2), dp(6), 0, dp(6));
            peerList.addView(tv);
            if (ctrlPeerList != null) {
                TextView t2 = new TextView(this);
                t2.setText(tv.getText());
                t2.setTextSize(12.5f);
                t2.setTextColor(COLOR_DIM);
                t2.setPadding(dp(2), dp(6), 0, dp(6));
                ctrlPeerList.addView(t2);
            }
            return;
        }
        for (final Discovery.Device d : peers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rp);
            row.setBackgroundColor(COLOR_CARD);

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
            peerList.addView(row);
            if (ctrlPeerList != null) {
                LinearLayout copy = buildPeerRow(d, false);
                ctrlPeerList.addView(copy);
            }
        }
    }

    /** 构建一行设备卡片。primary=true 时是所有交互按钮齐全的版本。 */
    private LinearLayout buildPeerRow(final Discovery.Device d, boolean primary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rp);
        row.setBackgroundColor(COLOR_CARD);

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

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.util.Enumeration<java.net.InetAddress> addrs =
                        nis.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
