package com.audiohub.source;

import android.Manifest;
import android.app.Activity;
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
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * AudioHub 源端 / 接收端。
 *
 * 两种模式：
 *   发送 —— 本机采集声音送到 PC 汇聚端（CaptureService）
 *   接收 —— 本机作为汇聚端，播放其它设备送来的声音（ReceiverService）
 *
 * "发起 / 接受"只指建立连接这一步，与音频方向无关：
 *   手机发起 = 在发现列表里点一台 PC，发 CONN-REQ
 *   PC 发起 = PC 发 CONN-INVITE，本机弹窗确认
 * 两条路径最终都汇到同一条握手流程。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_PROJECTION = 101;

    private EditText hostField;
    private EditText portField;
    private EditText streamField;
    private Button actionButton;
    private TextView statusView;
    private LinearLayout peerList;
    private TextView peerHeader;

    private LinearLayout modeSeg;
    private boolean receiverMode = false;

    private final Handler poller = new Handler(Looper.getMainLooper());
    private Discovery discovery;

    // 深色主题，与桌面端同一个色系
    private static final int COLOR_BG = 0xFF0F1420;
    private static final int COLOR_CARD = 0xFF1A2130;
    private static final int COLOR_TEXT = 0xFFEEF2F7;
    private static final int COLOR_DIM = 0xFF8B98A8;
    private static final int COLOR_LINE = 0xFF2A3348;
    private static final int COLOR_ACCENT = 0xFF3B82F6;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        SharedPreferences prefs = getSharedPreferences("audiohub", MODE_PRIVATE);
        hostField.setText(prefs.getString("host", "192.168.1.100"));
        portField.setText(String.valueOf(prefs.getInt("port", 47000)));
        streamField.setText(String.valueOf(prefs.getInt("streamId", 1)));

        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { onActionButton(); }
        });

        poller.post(new Runnable() {
            @Override
            public void run() {
                refresh();
                poller.postDelayed(this, 400);
            }
        });

        // 设备发现：广播接收需要 MulticastLock，否则部分机型收不到
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            android.net.wifi.WifiManager.MulticastLock lock =
                    wm.createMulticastLock("audiohub");
            lock.setReferenceCounted(false);
            lock.acquire();
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
        discovery.start();
    }

    @Override
    protected void onDestroy() {
        poller.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 界面

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

    private EditText addField(LinearLayout parent, String label, String def, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTextColor(COLOR_DIM);
        tv.setPadding(0, dp(10), 0, dp(4));
        parent.addView(tv);

        EditText field = new EditText(this);
        field.setText(def);
        field.setInputType(inputType);
        field.setTextSize(15);
        field.setTextColor(COLOR_TEXT);
        field.setBackgroundResource(android.R.color.transparent);
        parent.addView(field);
        return field;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        root.setBackgroundColor(COLOR_BG);

        TextView title = new TextView(this);
        title.setText("AudioHub");
        title.setTextSize(24);
        title.setLetterSpacing(0.02f);
        title.setTextColor(COLOR_TEXT);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("多台设备的声音，汇到一处");
        sub.setTextSize(12.5f);
        sub.setTextColor(COLOR_DIM);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // 模式切换
        modeSeg = new LinearLayout(this);
        modeSeg.setOrientation(LinearLayout.HORIZONTAL);
        String[] modes = {"发送（本机当音源）", "接收（本机当汇聚端）"};
        for (int i = 0; i < 2; ++i) {
            Button b = new Button(this);
            b.setText(modes[i]);
            b.setTextSize(12.5f);
            b.setAllCaps(false);
            b.setTextColor(i == 0 ? COLOR_ACCENT : COLOR_DIM);
            b.getBackground().setAlpha(0);
            b.setPadding(0, dp(10), 0, dp(10));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(0, 0, dp(6), 0);
            b.setLayoutParams(p);
            final int idx = i;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { setMode(idx == 1); }
            });
            modeSeg.addView(b);
        }
        root.addView(modeSeg);

        TextView line = new TextView(this);
        line.setBackgroundColor(COLOR_LINE);
        line.setHeight(dp(1));
        line.setPadding(0, dp(12), 0, dp(12));
        root.addView(line);

        root.addView(sectLabel("连接目标"));

        hostField = addField(root, "汇聚端地址", "192.168.1.100", InputType.TYPE_CLASS_TEXT);
        portField = addField(root, "端口", "47000", InputType.TYPE_CLASS_NUMBER);
        streamField = addField(root, "流编号（多台设备用不同编号）", "1",
                InputType.TYPE_CLASS_NUMBER);

        peerHeader = sectLabel("发现的设备");
        root.addView(peerHeader);
        peerList = new LinearLayout(this);
        peerList.setOrientation(LinearLayout.VERTICAL);
        root.addView(peerList);

        actionButton = new Button(this);
        actionButton.setText("开始发送");
        actionButton.setAllCaps(false);
        actionButton.setTextSize(14.5f);
        actionButton.setTextColor(Color.WHITE);
        actionButton.getBackground().setColorFilter(COLOR_ACCENT,
                android.graphics.PorterDuff.Mode.SRC_ATOP);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        bp.topMargin = dp(14);
        actionButton.setLayoutParams(bp);
        root.addView(actionButton);

        statusView = new TextView(this);
        statusView.setText("未启动");
        statusView.setTextSize(12.5f);
        statusView.setTextColor(COLOR_DIM);
        statusView.setPadding(0, dp(16), 0, 0);
        statusView.setLineSpacing(0, 1.2f);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void setMode(boolean receiver) {
        receiverMode = receiver;
        for (int i = 0; i < modeSeg.getChildCount(); ++i) {
            Button b = (Button) modeSeg.getChildAt(i);
            b.setTextColor((i == 1) == receiver ? COLOR_ACCENT : COLOR_DIM);
        }
        actionButton.setText(receiver ? "开始接收" : "开始发送");
        // 接收模式不需要填对端地址（别人连过来）
        hostField.setEnabled(!receiver);
        hostField.setAlpha(receiver ? 0.4f : 1f);
        refresh();
    }

    // ------------------------------------------------------------ 刷新

    private void refresh() {
        boolean running = receiverMode ? ReceiverService.sRunning : CaptureService.sRunning;
        actionButton.setText(running ? "停止" : (receiverMode ? "开始接收" : "开始发送"));

        String status = receiverMode ? ReceiverService.sStatus : CaptureService.sStatus;
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(status).append('\n');
        if (running) {
            if (receiverMode) {
                sb.append("总线电平：")
                  .append(String.format("%.1f", ReceiverService.sBusLevel))
                  .append(" dBFS\n");
                sb.append("缓冲水位：").append(ReceiverService.sOccupancyMs).append(" ms");
            } else {
                sb.append("已发送：").append(CaptureService.sPackets).append(" 包\n");
                sb.append("电平：").append(CaptureService.sLevelDbfs).append(" dBFS\n");
                sb.append(String.format("码率：%.0f kbps", CaptureService.sKbps));
            }
        }
        statusView.setText(sb.toString());
    }

    private void renderPeers(List<Discovery.Device> peers) {
        peerList.removeAllViews();
        if (peers.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("正在扫描附近设备…");
            tv.setTextSize(12.5f);
            tv.setTextColor(COLOR_DIM);
            tv.setPadding(0, dp(6), 0, dp(6));
            peerList.addView(tv);
            return;
        }
        for (final Discovery.Device d : peers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rp);
            row.setBackgroundColor(COLOR_CARD);

            TextView name = new TextView(this);
            name.setText(d.name + "  ·  " + d.roleText());
            name.setTextSize(13.5f);
            name.setTextColor(COLOR_TEXT);
            row.addView(name);

            TextView addr = new TextView(this);
            addr.setText(d.ip + ":" + d.audioPort);
            addr.setTextSize(11.5f);
            addr.setTextColor(COLOR_DIM);
            addr.setPadding(0, dp(2), 0, 0);
            row.addView(addr);

            // 只显示"能放音"的设备（我们要把声音送给它）
            if (d.canReceive) {
                Button go = new Button(this);
                go.setText("连接到这台");
                go.setTextSize(12);
                go.setAllCaps(false);
                go.setTextColor(COLOR_ACCENT);
                go.getBackground().setAlpha(0);
                go.setPadding(0, dp(8), 0, 0);
                go.setGravity(Gravity.START);
                go.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { connectToPeer(d); }
                });
                row.addView(go);
            }
            peerList.addView(row);
        }
    }

    // ------------------------------------------------------------ 流程

    private void onActionButton() {
        final boolean running =
                receiverMode ? ReceiverService.sRunning : CaptureService.sRunning;
        if (running) {
            Intent stop = new Intent(this, receiverMode ? ReceiverService.class
                                                        : CaptureService.class)
                    .setAction(receiverMode ? ReceiverService.ACTION_STOP
                                            : CaptureService.ACTION_STOP);
            startService(stop);
            return;
        }

        if (receiverMode) {
            final int port = parseInt(portField.getText().toString(), 47000);
            getSharedPreferences("audiohub", MODE_PRIVATE).edit().putInt("port", port).apply();
            Intent svc = new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_START)
                    .putExtra(ReceiverService.EXTRA_PORT, port);
            startForegroundService(svc);
            return;
        }

        // 发送模式：需要录音权限 + 屏幕采集授权
        ensurePermissionsThenStart();
    }

    private void connectToPeer(final Discovery.Device d) {
        // 先握手：发 CONN-REQ，等 PC 确认
        hostField.setText(d.ip);
        portField.setText(String.valueOf(d.audioPort));
        Toast.makeText(this, "正在请求连接 " + d.name + " …", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = ReceiverService.respondToInvite(
                        d.ip, d.audioPort, "Android 设备");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            Toast.makeText(MainActivity.this, "对方未确认连接",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(MainActivity.this, "对方已同意",
                                Toast.LENGTH_SHORT).show();
                        // 握手已通过，PC 的 accepted_ids 已有本机，直接开始发送
                        ensurePermissionsThenStart();
                    }
                });
            }
        }, "audiohub-connect").start();
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

        final String host = hostField.getText().toString().trim();
        final int port = parseInt(portField.getText().toString(), 47000);
        final int streamId = parseInt(streamField.getText().toString(), 1);

        getSharedPreferences("audiohub", MODE_PRIVATE).edit()
                .putString("host", host)
                .putInt("port", port)
                .putInt("streamId", streamId)
                .apply();

        Intent service = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_HOST, host)
                .putExtra(CaptureService.EXTRA_PORT, port)
                .putExtra(CaptureService.EXTRA_STREAM_ID, streamId);

        startForegroundService(service);
        statusView.setText("正在启动…");
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
