package com.audiohub.source;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PathEffect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 全网图谱：把 GRAPH 里的所有设备画成一张网。
 *
 *   · 汇聚端画在正中间，球大 1.5 倍 + 光晕 + "汇聚端"角标；
 *   · 其它设备围成一圈，hub 到每个节点有连线（音频流的走向）；
 *   · 本机带 "本机" 角标；离线节点半透明 + 虚线描边；
 *   · 选中的节点描边加粗；有音频流的节点在名字下面显示电平；
 *   · 点某个球回调 node id，由 MainActivity 决定"切换汇聚端 / 调音量"。
 *
 * 之前这里只画"一个 hub + 它收到的几路流"，球用 key 标识；换成图谱后
 * 球的身份必须是节点 id，否则点球没法对应到图谱里的节点。
 *
 * 教训：drawText 收到 null 会直接 NPE 崩在 onDraw（UI 线程），所以
 * 所有文本都经过 displayName()/空串兜底。
 */
public class HubGraphView extends View {

    /** 图谱里的一个球。MainActivity 每次 refresh 重新构造列表（不可变快照）。 */
    public static final class Node {
        public String id = "";          // 节点 id "ip:audioPort"
        public String name = "";        // 显示名（可能为空 → 退回 id）
        public String kind = "other";   // pc | android | other
        public boolean hub;             // 是不是汇聚端
        public boolean self;            // 是不是本机
        public boolean online = true;   // 10s 内有过数据报
        public double level = -120;     // 该节点音频流电平（dBFS）
        public int streamId = 0;        // 0 = 没有音频流
        public float x, y, r;           // 布局结果（onDraw 填充）
    }

    public interface OnNodeTap {
        /** 已选中该节点并重绘；node 不会为 null。 */
        void onTap(Node node);
    }

    private static final int COLOR_BALL = 0xFF3B82F6;
    private static final int COLOR_HUB = 0xFF60A5FA;
    private static final int COLOR_SELF = 0xFF34D399;
    private static final int COLOR_LINE = 0xFF2A3348;
    private static final int COLOR_TEXT = 0xFFEEF2F7;
    private static final int COLOR_DIM = 0xFF8B98A8;

    private final List<Node> nodes = new ArrayList<>();
    private OnNodeTap onNodeTap;
    private String selectedId = "";
    private String statusText = "";

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final PathEffect dashEffect = new DashPathEffect(new float[]{dp(6), dp(5)}, 0);

    public HubGraphView(Context context) {
        super(context);
        init();
    }

    public HubGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1.2f));
        linePaint.setColor(COLOR_LINE);
        strokePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setOnNodeTap(OnNodeTap listener) {
        onNodeTap = listener;
    }

    /** 用当前图谱快照刷新画面。传入的 Node 会被复用（点击回调用它取 id）。 */
    public void setData(List<Node> input) {
        nodes.clear();
        if (input != null) nodes.addAll(input);
        if (!selectedId.isEmpty() && find(selectedId) == null) selectedId = "";
        invalidate();
    }

    public void setSelected(String id) {
        selectedId = id != null ? id : "";
        invalidate();
    }

    public String selectedId() {
        return selectedId;
    }

    /** 顶部提示（"已切换，正在同步…" / "已同步 v7" 之类）。 */
    public void setStatus(String text) {
        statusText = text != null ? text : "";
        invalidate();
    }

    private Node find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Node n : nodes) {
            if (id.equals(n.id)) return n;
        }
        return null;
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (!statusText.isEmpty()) {
            textPaint.setColor(COLOR_DIM);
            textPaint.setTextSize(sp(10.5f));
            textPaint.setTypeface(Typeface.DEFAULT);
            canvas.drawText(statusText, w / 2f, dp(18), textPaint);
        }

        if (nodes.isEmpty()) {
            textPaint.setColor(COLOR_DIM);
            textPaint.setTextSize(sp(12));
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("正在等待设备…", w / 2f, h / 2f, textPaint);
            return;
        }

        final Node center = pickCenter();
        final List<Node> ring = new ArrayList<>();
        for (Node n : nodes) {
            if (n != center) ring.add(n);
        }
        layout(center, ring, w, h);

        // 汇聚端到其它设备的连线（音频流从音源汇到汇聚端）
        final Node hub = findHub();
        if (hub != null) {
            linePaint.setColor(COLOR_LINE);
            linePaint.setPathEffect(null);
            for (Node n : nodes) {
                if (n == hub) continue;
                canvas.drawLine(hub.x, hub.y, n.x, n.y, linePaint);
            }
        }

        // 先画普通节点，hub 最后画（叠在上层）
        for (Node n : nodes) {
            if (!n.hub) drawNode(canvas, n);
        }
        for (Node n : nodes) {
            if (n.hub) drawNode(canvas, n);
        }
    }

    /** 汇聚端没有就画本机，再没有就画第一个（总是把"我"放在可理解的位置）。 */
    private Node pickCenter() {
        final Node hub = findHub();
        if (hub != null) return hub;
        for (Node n : nodes) {
            if (n.self) return n;
        }
        return nodes.get(0);
    }

    private Node findHub() {
        for (Node n : nodes) {
            if (n.hub) return n;
        }
        return null;
    }

    private void layout(Node center, List<Node> ring, int w, int h) {
        final float minSide = Math.min(w, h);
        final float baseR = minSide * 0.085f;
        final float hubR = baseR * 1.5f;
        final float cx = w / 2f;
        final float cy = h * 0.46f;
        final float ringRadius = Math.min(minSide * 0.33f,
                Math.min(cx, h - cy) - baseR * 1.6f);

        if (center != null) {
            center.x = cx;
            center.y = cy;
            center.r = center.hub ? hubR : (center.self ? baseR * 1.15f : baseR);
        }

        final int count = ring.size();
        for (int i = 0; i < count; ++i) {
            final Node n = ring.get(i);
            final double angle = -Math.PI / 2.0
                    + 2.0 * Math.PI * i / Math.max(1, count);
            n.x = cx + (float) (Math.cos(angle) * ringRadius);
            n.y = cy + (float) (Math.sin(angle) * ringRadius);
            n.r = n.self ? baseR * 1.15f : baseR;
            // 圈太小 / 圆一大，可能顶到边界：夹一下，标签始终留空间
            final float margin = n.r + sp(20);
            if (n.x < margin) n.x = margin;
            if (n.x > w - margin) n.x = w - margin;
            if (n.y < margin) n.y = margin;
            if (n.y > h - margin) n.y = h - margin;
        }
    }

    private void drawNode(Canvas canvas, Node n) {
        final boolean selected = n.id != null && !n.id.isEmpty() && n.id.equals(selectedId);
        final boolean dim = !n.online;
        final int alpha = dim ? 110 : 255;

        // 光晕：汇聚端常亮；有音频流的节点轻微光晕
        if (n.online && (n.hub || n.streamId > 0)) {
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(n.hub ? 0x333B82F6 : 0x2234D399);
            fillPaint.setAlpha(255);
            canvas.drawCircle(n.x, n.y, n.r + (n.hub ? dp(11) : dp(6)), fillPaint);
        }

        // 球体
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(ballColor(n));
        fillPaint.setAlpha(alpha);
        canvas.drawCircle(n.x, n.y, n.r, fillPaint);
        fillPaint.setAlpha(255);

        // 描边：离线虚线；选中加粗高亮
        strokePaint.setColor(selected ? COLOR_TEXT : (n.hub ? COLOR_HUB : COLOR_LINE));
        strokePaint.setStrokeWidth(selected ? dp(3)
                : (n.hub ? dp(2) : dp(1.4f)));
        strokePaint.setAlpha(dim ? 120 : 255);
        strokePaint.setPathEffect(dim ? dashEffect : null);
        canvas.drawCircle(n.x, n.y, n.r, strokePaint);
        strokePaint.setPathEffect(null);
        strokePaint.setAlpha(255);

        // 名字（永远不为 null）
        final String name = displayName(n);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(n.hub ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        textPaint.setTextSize(sp(12));
        textPaint.setColor(dim ? 0x99EEF2F7 : COLOR_TEXT);
        canvas.drawText(name, n.x, n.y + n.r + sp(17), textPaint);

        // 第二行：有流显示电平；汇聚端显示"汇聚端"
        final String second = n.hub ? "汇聚端" : levelText(n);
        if (!second.isEmpty()) {
            textPaint.setTextSize(sp(10));
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setColor(COLOR_DIM);
            canvas.drawText(second, n.x, n.y + n.r + sp(31), textPaint);
        }

        // 角标：本机 / 汇聚端
        final String badge;
        if (n.self && n.hub) {
            badge = "本机·汇聚端";
        } else if (n.self) {
            badge = "本机";
        } else if (n.hub) {
            badge = "汇聚端";
        } else {
            badge = "";
        }
        if (!badge.isEmpty()) drawBadge(canvas, n, badge);
    }

    private void drawBadge(Canvas canvas, Node n, String text) {
        badgeTextPaint.setTextSize(sp(9));
        final float textWidth = badgeTextPaint.measureText(text);
        final float padH = dp(5);
        final float padV = dp(2.5f);
        final float bw = textWidth + padH * 2f;
        final float bh = sp(11) + padV * 2f;
        final float left = n.x - bw / 2f;
        final float top = n.y - n.r - bh - dp(3);
        tmpRect.set(left, top, left + bw, top + bh);
        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(0xE61A2130);
        canvas.drawRoundRect(tmpRect, dp(6), dp(6), badgePaint);
        badgeTextPaint.setColor(n.self ? COLOR_SELF : COLOR_HUB);
        canvas.drawText(text, n.x, top + bh - padV - dp(1), badgeTextPaint);
    }

    private int ballColor(Node n) {
        if (n.self) return COLOR_SELF;
        if (n.hub) return COLOR_HUB;
        if ("pc".equals(n.kind)) return COLOR_BALL;
        if ("android".equals(n.kind)) return 0xFF0EA5E9;
        return COLOR_BALL;
    }

    /** 名字兜底：永不返回 null（drawText(null) 会 NPE 崩 UI 线程）。 */
    private String displayName(Node n) {
        if (n.name != null && !n.name.trim().isEmpty()) return n.name;
        if (n.id != null && !n.id.isEmpty()) return n.id;
        return "未知设备";
    }

    private String levelText(Node n) {
        if (n.streamId <= 0 || Double.isNaN(n.level)) return "";
        return ((int) Math.round(n.level)) + " dBFS";
    }

    // ------------------------------------------------------------ 点击

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
            final float x = event.getX();
            final float y = event.getY();
            for (int i = nodes.size() - 1; i >= 0; --i) {
                final Node n = nodes.get(i);
                final float dx = x - n.x;
                final float dy = y - n.y;
                final float hit = n.r + dp(12);
                if (dx * dx + dy * dy <= hit * hit) {
                    selectedId = n.id != null ? n.id : "";
                    invalidate();
                    final OnNodeTap listener = onNodeTap;
                    if (listener != null) listener.onTap(n);
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}