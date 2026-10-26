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

import org.json.JSONArray;
import org.json.JSONObject;

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

    // 用户主动点了"断开"：服务真正停掉之前，不能被图谱/服务状态拉回控制界面
    private boolean userLeft = false;

    // ---- 图谱 / 采集职责（协议 §6.2）----
    /** 已保存的 MediaProjection 授权：切换目标或 streamId 变了复用它重启采集。 */
    private int projectionResultCode = 0;
    private Intent projectionResultData;
    /** 用户希望本机在采集（点过"送入"或连接成功后自动开始）。 */
    private boolean sourceIntent = false;
    /** 上次因目标变化重启采集的时间，防止 400ms 轮询里反复重启。 */
    private long lastSourceRestartAt = 0;
    /** 切换汇聚端后的提示状态。 */
    private String pendingSwitchTarget = "";
    private long switchSyncedAt = 0;
    /** 音量面板当前选中的音频流（0 = 没有流）。 */
    private int selectedStreamId = 0;
    /** 一次性提示（只 toast 一次，避免每 400ms 刷屏）。 */
    private String hintText = "";

    private LinearLayout ctrlPeerList;

    private HubGraphView graph;

    // ---- 3D 房间场景（PC 端 v1.3 ui/scene3d.js 的持久化约定：SharedPreferences "audiohub" / key "scene3d"）----
    private static final String PREF_SCENE3D = "scene3d";
    private static final int SCENE_STORE_VERSION = 2;
    /** 用户拖动 pin 住的节点：id → [x, 0, z]（米，与 PC 端 store.pins 同构）。 */
    private final java.util.Map<String, double[]> scenePins = new java.util.LinkedHashMap<>();
    /** 自动槽位分配：id → 0..31（与 PC 端 store.slots 同构）。 */
    private final java.util.Map<String, Integer> sceneSlots = new java.util.LinkedHashMap<>();
    /** 相机（绕原点 orbit）：yaw/pitch 度、dist 米；与 PC 端 store.cam 同构。 */
    private double sceneYawDeg = 0;
    private double scenePitchDeg = Scene3D.CFG.PITCH_DEFAULT;
    private double sceneDist = Scene3D.CFG.CAM_DIST;
    private long sceneSavedAtMs = 0;
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

    /** 构建标记：与 APK versionName 保持一致，用于在手机上确认装的是哪一版。 */
    private static final String BUILD_TAG = "1.4-3d-2";

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
        // 3D 场景：相机/pin/槽位从 PC 端同一份 JSON 恢复；没有就用默认视角（φ=0、pitch 40°、dist 3.4 m）。
        loadSceneStore(prefs);
        if (graph != null) graph.setCamera(sceneYawDeg, scenePitchDeg, sceneDist);

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
        saveSceneStore();   // 相机/pin/槽位最后落盘一次
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

    @Override
    protected void onPause() {
        super.onPause();
        saveSceneStore();
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

        // 版本/特性标记：手机上装的是哪一版一眼可辨（排查“装了新版还是旧界面”类问题）
        TextView buildTag = new TextView(this);
        buildTag.setText("3D 房间视图 · v" + BUILD_TAG);
        buildTag.setTextSize(11f);
        buildTag.setTextColor(COLOR_DIM);
        buildTag.setPadding(0, 0, 0, dp(10));
        root.addView(buildTag);

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
                onGraphNodeTap(node);
            }
        });
        graph.setOnNodeDoubleTap(new HubGraphView.OnNodeDoubleTap() {
            @Override
            public void onDoubleTap(HubGraphView.Node node) {
                onSceneDoubleTap(node);
            }
        });
        graph.setOnSceneChanged(new HubGraphView.OnSceneChanged() {
            @Override
            public void onCameraChanged(double yawDeg, double pitchDeg, double dist) {
                onSceneCamera(yawDeg, pitchDeg, dist);
            }

            @Override
            public void onPinChanged(String nodeId, double wx, double wz) {
                onScenePin(nodeId, wx, wz);
            }

            @Override
            public void onPinCleared(String nodeId) {
                onSceneUnpin(nodeId);
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
        hubSub = sub("点图谱里的球可切换汇聚端");
        hubInfoC.addView(hubSub);
        hubRowC.addView(hubInfoC);
        hubBar.addView(hubRowC);
        screenControl.addView(hubBar);

        Button leave = textButton("断开所有连接", COLOR_WARN);
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 协议 §4.6：清空图谱（不广播），两个服务都停，回连接界面
                GraphStore.resetSession();
                startService(new Intent(MainActivity.this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_STOP));
                sourceIntent = false;
                stopSource();
                projectionResultCode = 0;
                projectionResultData = null;
                userLeft = true;      // 立刻回连接界面，且不被服务状态拉回
                manualPeers.clear();  // 主动断开：关系表一起清掉
                hubIp = null;
                hubName = null;
                saveHub();
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

    /** 音量面板：streamId=0 表示这个节点当前没有音频流（只看状态）。 */
    private void showVolumePanelForStream(int streamId, String label) {
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
        sourceIntent = false;
        stopSource();
        if (!ReceiverService.sRunning) {
            safeStartForegroundService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_START)
                    .putExtra(ReceiverService.EXTRA_PORT, GraphStore.AUDIO_PORT));
        }
        refresh();
        Toast.makeText(this, "本机已成为汇聚端", Toast.LENGTH_SHORT).show();
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
        userLeft = false;
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
                            Toast.makeText(MainActivity.this,
                                    "对方还没有同意连接，界面已切换但声音还没接通",
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
        // 保存授权：切换汇聚端 / 音源序号变了要用它重启采集（协议 §6.2）
        projectionResultCode = resultCode;
        projectionResultData = data;
        sourceIntent = true;

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
        }
    }

    // ------------------------------------------------------------ 刷新

    private void refresh() {
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
        // 不再用粘滞会话标志——图谱本身就是会话状态的权威来源。
        if (userLeft && !hubRunning && !srcRunning) userLeft = false;
        final boolean showControl = !userLeft
                && (g.nodes.size() >= 2 || hubRunning || srcRunning);
        showScreen(showControl ? "control" : "connect");

        // 画全网图谱：每个节点一个球，汇聚端在中间（协议 §9.3）
        // 3D 房间布局：非 hub 节点按 id 升序取最小空闲槽位（0..31），pin 过的用固定位置。
        // roomLayout/allocateSlots 就是 PC 端 ui/scene3d.js 的同名函数；hub 固定在原点、听者固定在原点朝 -Z。
        final java.util.List<String> sceneIds = new java.util.ArrayList<>();
        for (GraphStore.Node n : g.nodes) {
            if (n.id.equals(g.hubId)) continue;
            sceneIds.add(n.id);
        }
        java.util.Collections.sort(sceneIds);
        final boolean sceneDirty = Scene3D.allocateSlots(sceneIds, sceneIds, sceneSlots);
        final java.util.Map<String, Scene3D.Placement> sceneLayout =
                Scene3D.roomLayout(sceneIds, scenePins, sceneSlots);
        if (sceneDirty) saveSceneStore();

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
                final Scene3D.Placement placement = sceneLayout.get(n.id);
                if (placement != null) {
                    v.wx = placement.pos.x;
                    v.wz = placement.pos.z;
                    v.slot = placement.slot;
                    v.pinned = placement.pinned;
                    v.azDeg = placement.azDeg;
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
     *   isSource   → 采集目标 / streamId 对不上就用已保存授权重启；没授权只提示
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

        final GraphStore.Snapshot g = GraphStore.snapshot();
        final boolean hubRunning = ReceiverService.sRunning;
        final boolean srcRunning = CaptureService.sRunning;

        if (g.isHub()) {
            // 汇聚端：本机负责收流放音，不能同时采集（回授）
            sourceIntent = false;
            hintText = "";
            if (srcRunning) stopSource();
            if (!hubRunning) {
                safeStartForegroundService(new Intent(this, ReceiverService.class)
                        .setAction(ReceiverService.ACTION_START)
                        .putExtra(ReceiverService.EXTRA_PORT, GraphStore.AUDIO_PORT));
            }
            return;
        }

        final GraphStore.Node hub = g.hubNode();
        if (hub != null) {
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
                    // 目标或音源序号变了：停旧的，下个 tick 用新目标重启（复用授权）
                    restartSource();
                } else {
                    hintText = "";
                }
            } else if (sourceIntent) {
                if (hasProjectionAuth()) {
                    hintText = "";
                    startCapture(hub, streamId);
                } else {
                    graphHint("本机已设为音源，开始采集后生效");
                }
            }
            return;
        }

        // 汇聚端未知（可能刚断开 / 还没同步到）：停掉一切，保持空闲
        if (hubRunning) {
            startService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_STOP));
        }
        if (srcRunning) stopSource();
    }

    private void startCapture(final GraphStore.Node hub, final int streamId) {
        if (!hasProjectionAuth()) {
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
        } catch (Throwable t) {
            android.util.Log.e("AudioHub", "启动采集失败: "
                    + android.util.Log.getStackTraceString(t));
            graphHint("启动采集失败：" + t.getClass().getSimpleName());
        }
    }

    /** 目标变化时重启采集：先停旧服务（避免同设备双采），1.5s 防抖。 */
    private void restartSource() {
        final long now = System.currentTimeMillis();
        if (now - lastSourceRestartAt < 1500L) return;
        lastSourceRestartAt = now;
        startService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_STOP));
    }

    /** 点图谱里的球：有流的节点展开音量面板；非汇聚端节点弹确认切换。 */
    /** 双击节点 = PC 端 dblclick → onActivate：直接把汇聚端切过去（单击仍是音量面板+确认框，旧语义不变）。 */
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

    /** 相机变化：拖动时高频回调，落盘节流 500ms（app 退到后台/退出时还会强制存一次）。 */
    private void onSceneCamera(double yawDeg, double pitchDeg, double dist) {
        sceneYawDeg = yawDeg;
        scenePitchDeg = pitchDeg;
        sceneDist = dist;
        final long now = System.currentTimeMillis();
        if (now - sceneSavedAtMs >= 500) saveSceneStore();
    }

    /** 拖动节点结束：pin 到新位置、落盘、立刻上报空间位置（不等 400ms 轮询）。 */
    private void onScenePin(String nodeId, double wx, double wz) {
        if (nodeId == null || nodeId.isEmpty()) return;
        scenePins.put(nodeId, new double[]{wx, 0, wz});
        saveSceneStore();
        final GraphStore.Snapshot g = GraphStore.snapshot();
        final GraphStore.Node n = g.find(nodeId);
        if (n != null) {
            final int sid = ReceiverService.streamIdForIp(n.ip);
            if (sid > 0) ReceiverService.setStreamPosition(sid, (float) wx, 0f, (float) wz);
        }
        refresh();
    }

    /** 长按取消 pin：节点回到自动槽位。 */
    private void onSceneUnpin(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return;
        scenePins.remove(nodeId);
        saveSceneStore();
        refresh();
    }

    /** 读 PC 端同一份场景 JSON（v2 优先；v1 只有 cam/pins 也能读）。坏 JSON 不能拦住启动。 */
    private void loadSceneStore(SharedPreferences prefs) {
        if (prefs == null) return;
        final String raw = prefs.getString(PREF_SCENE3D, "");
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            final JSONObject root = new JSONObject(raw);
            final JSONObject cam = root.optJSONObject("cam");
            if (cam != null) {
                sceneYawDeg = cam.optDouble("yawDeg", 0);
                scenePitchDeg = cam.optDouble("pitchDeg", Scene3D.CFG.PITCH_DEFAULT);
                sceneDist = cam.optDouble("dist", Scene3D.CFG.CAM_DIST);
            }
            final JSONObject pins = root.optJSONObject("pins");
            if (pins != null) {
                final java.util.Iterator<String> it = pins.keys();
                while (it.hasNext()) {
                    final String id = it.next();
                    final JSONArray arr = pins.optJSONArray(id);
                    if (arr == null || arr.length() < 3) continue;
                    final double x = arr.optDouble(0, 0);
                    final double z = arr.optDouble(2, 0);
                    if (!Scene3D.isFinite(x) || !Scene3D.isFinite(z)) continue;
                    scenePins.put(id, new double[]{x, 0, z});
                }
            }
            final JSONObject slots = root.optJSONObject("slots");
            if (slots != null) {
                final java.util.Iterator<String> it = slots.keys();
                while (it.hasNext()) {
                    final String id = it.next();
                    final int idx = slots.optInt(id, -1);
                    if (idx >= 0 && idx < Scene3D.SLOT_COUNT) sceneSlots.put(id, Integer.valueOf(idx));
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("AudioHub", "scene3d 读失败，回默认视角: " + t);
            scenePins.clear();
            sceneSlots.clear();
            sceneYawDeg = 0;
            scenePitchDeg = Scene3D.CFG.PITCH_DEFAULT;
            sceneDist = Scene3D.CFG.CAM_DIST;
        }
    }

    /** 把相机/pin/槽位写回 PC 端同一份 JSON（v2，org.json；键名与 ui/scene3d.js store 逐字一致）。 */
    private void saveSceneStore() {
        sceneSavedAtMs = System.currentTimeMillis();
        try {
            final JSONObject root = new JSONObject();
            root.put("v", SCENE_STORE_VERSION);
            final JSONObject cam = new JSONObject();
            cam.put("yawDeg", Scene3D.round3(sceneYawDeg));
            cam.put("pitchDeg", Scene3D.round3(scenePitchDeg));
            cam.put("dist", Scene3D.round3(sceneDist));
            root.put("cam", cam);
            final JSONObject pins = new JSONObject();
            for (java.util.Map.Entry<String, double[]> e : scenePins.entrySet()) {
                final double[] p = e.getValue();
                final JSONArray arr = new JSONArray();
                arr.put(Scene3D.round3(p[0]));
                arr.put(0);
                arr.put(Scene3D.round3(p[2]));
                pins.put(e.getKey(), arr);
            }
            root.put("pins", pins);
            final JSONObject slots = new JSONObject();
            for (java.util.Map.Entry<String, Integer> e : sceneSlots.entrySet()) {
                slots.put(e.getKey(), e.getValue().intValue());
            }
            root.put("slots", slots);
            getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                    .putString(PREF_SCENE3D, root.toString()).apply();
        } catch (Throwable t) {
            android.util.Log.w("AudioHub", "scene3d 写失败: " + t);
        }
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
        new AlertDialog.Builder(this)
                .setTitle("切换汇聚端")
                .setMessage(msg)
                .setPositiveButton("切换", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
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
                    // 同意"把声音送到本机"= 本机要当汇聚端（协议 §5）：
                    // 先切图谱 hub，再保证接收服务在跑，否则对方发来的流没人收。
                    if (!GraphStore.isHub()) {
                        final String sid = GraphStore.selfId();
                        if (!sid.isEmpty() && GraphStore.switchHub(sid)) {
                            pendingSwitchTarget = sid;
                            switchSyncedAt = 0;
                            InviteListener.broadcastGraph();
                        }
                    }
                    if (!ReceiverService.sRunning) {
                        safeStartForegroundService(new Intent(MainActivity.this,
                                ReceiverService.class)
                                .setAction(ReceiverService.ACTION_START)
                                .putExtra(ReceiverService.EXTRA_PORT, GraphStore.AUDIO_PORT));
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
