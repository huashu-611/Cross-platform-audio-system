package com.audiohub.source;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 3D 物理房间视图（PC 端 v1.3 ui/scene3d.js 的 Canvas2D 透视渲染在 Android 上的等价实现）。
 *
 *   · 世界坐标：听者固定在原点，+X 是右耳方向，-Z 是正前方；地面是 y=0 平面（米）。
 *   · 相机只改视角、不改声场：单指空白拖动 = orbit（yaw/pitch），双指捏合 = zoom（dist）。
 *   · 汇聚端（hub）永远画在原点；其它节点按其世界坐标 (wx, wz) 投影，近大远小、远的先画。
 *   · 地面画 1 m / 2 m 环、前/右/后/左方位与距离标注，节点间连线表示音频流向。
 *   · 拖动节点 = 在地面上移动并 pin 到新位置（半径夹在 0.45..2.3 m，房间边界内）。
 *   · 点击节点保留旧的 OnNodeTap 语义（MainActivity 用它调音量/切 hub）；双击空白复位相机；
 *     长按已 pin 的节点取消 pin。
 *
 * 数学部分（camera / project / unproject / hitTest / 32 槽位 / 听者方位 / 球半径 / 槽位分配）
 * 全部在纯 Java 类 {@link Scene3D} 里逐函数移植自 ui/scene3d.js，由 android/verify/SceneCheck 校验；
 * 本类只负责绘制与手势，所有屏幕坐标都先算到 920x460 的虚拟画布坐标再整体缩放，保证与 PC 端一致。
 *
 * 教训：drawText 收到 null 会直接 NPE 崩在 onDraw（UI 线程），所以所有文本都经过
 * displayName()/shortName() 兜底。
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

        // ---- 3D 房间（由 MainActivity 每帧从 GraphStore + roomLayout 填进来）----
        /** 世界坐标 x（米，右正）。 */
        public double wx = 0;
        /** 世界坐标 z（米，前为 -Z）。 */
        public double wz = 0;
        /** 分配到的槽位 index（0..31；-1 = pin 出来的自由位置）。 */
        public int slot = -1;
        /** 是否被用户拖动 pin 住（不再跟随自动槽位）。 */
        public boolean pinned = false;
        /** 相对听者的方位角（度，0=正前，+90=右）；由 roomLayout/azOf 填。 */
        public double azDeg = 0;

        // ---- 旧 2D 布局结果字段：保留是为了不破坏旧调用方，3D 绘制不再依赖 ----
        public float x, y, r;
    }

    public interface OnNodeTap {
        /** 已选中该节点并重绘；node 不会为 null。（旧语义：MainActivity 用它调音量/切 hub） */
        void onTap(Node node);
    }

    public interface OnNodeDoubleTap {
        /** PC 端 dblclick → onActivate 的 Android 对应：双击节点时切 hub。 */
        void onDoubleTap(Node node);
    }

    /** 场景状态变化回调：MainActivity 负责写 SharedPreferences / 上报 setStreamPosition。 */
    public interface OnSceneChanged {
        /** 相机（orbit/zoom）变化；拖动过程中高频触发，调用方自行节流。 */
        void onCameraChanged(double yawDeg, double pitchDeg, double dist);

        /** 节点被拖到新位置（ACTION_UP 时）。pos 已夹到 0.45..2.3 m 房间内。 */
        void onPinChanged(String nodeId, double wx, double wz);

        /** 长按取消 pin，节点回到自动槽位。 */
        void onPinCleared(String nodeId);
    }

    // =====================================================================
    // 颜色：直接用 PC 端 styles.css / scene3d.js 的 oklch 字面值转 sRGB，
    // 这样深色主题下的球体/地面/文字色与 PC 保持一致（oklch 为 CSS4 颜色空间，
    // Android 没有现成实现，这里做 oklab→线性 sRGB→gamma 的精确换算）。
    // =====================================================================

    private static final int COLOR_LINE = oklch(0.35, 0.03, 255, 0.60);
    private static final int COLOR_FLOW = oklch(0.72, 0.15, 255, 0.90);
    private static final int COLOR_TEXT = oklch(0.94, 0.01, 255, 1.0);
    private static final int COLOR_DIM = oklch(0.68, 0.02, 255, 1.0);
    private static final int COLOR_FAINT = oklch(0.52, 0.02, 255, 1.0);
    private static final int COLOR_ACCENT = oklch(0.62, 0.17, 255, 1.0);
    private static final int COLOR_ACCENT_HI = oklch(0.72, 0.15, 255, 1.0);
    private static final int COLOR_STROKE = oklch(0.55, 0.10, 255, 1.0);
    private static final int COLOR_STROKE_OFF = oklch(0.42, 0.02, 255, 1.0);
    private static final int COLOR_FILL_NODE = oklch(0.40, 0.06, 255, 1.0);
    private static final int COLOR_FILL_OFF = oklch(0.30, 0.03, 255, 1.0);
    private static final int COLOR_FILL_GHOST = oklch(0.26, 0.02, 255, 1.0);
    private static final int COLOR_GROUND = oklch(0.42, 0.03, 255, 0.42);
    private static final int COLOR_GROUND_DIM = oklch(0.42, 0.03, 255, 0.22);

    /** 点击命中容差（虚拟画布 px，对应 scene3d.js hitTest 默认 slop=6）。 */
    private static final double HIT_SLOP = 6;
    /** 手势位移阈值（虚拟画布 px）：超过才算"拖动"，否则算点击。 */
    private static final double TAP_SLOP = 4;
    /** 双击时间窗（ms）。 */
    private static final long DOUBLE_TAP_MS = 320;
    /** 长按取消 pin 的判定时长（ms）。 */
    private static final long LONG_PRESS_MS = 550;

    private static final int MODE_NONE = 0;
    private static final int MODE_ORBIT = 1;
    private static final int MODE_NODE = 2;
    private static final int MODE_PINCH = 3;

    private final List<Node> nodes = new ArrayList<>();
    private OnNodeTap onNodeTap;
    private OnNodeDoubleTap onNodeDoubleTap;
    private OnSceneChanged onSceneChanged;
    private String selectedId = "";
    private String statusText = "";

    // 相机（持久化在 MainActivity 的 SharedPreferences 里，key "scene3d"）
    private double camYaw = 0;                                 // φ，绕 +Y
    private double camPitch = Scene3D.CFG.PITCH_DEFAULT;       // 俯仰 40°
    private double camDist = Scene3D.CFG.CAM_DIST;             // 3.4 m

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dashGround = new DashPathEffect(new float[]{4, 6}, 0);
    private final DashPathEffect dashOffline = new DashPathEffect(new float[]{5, 5}, 0);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mode != MODE_NODE || gestureMoved || gestureNode == null || !gestureNode.pinned) return;
            gestureLongPressed = true;
            gestureNode.pinned = false;
            final String id = gestureNode.id;
            invalidate();
            final OnSceneChanged cb = onSceneChanged;
            if (cb != null) cb.onPinCleared(id);
        }
    };

    // 手势状态
    private int mode = MODE_NONE;
    private Node gestureNode;
    private double downVX, downVY;      // 按下点（虚拟坐标）
    private double lastVX, lastVY;      // orbit 上一帧（虚拟坐标）
    private boolean gestureMoved;
    private boolean gestureLongPressed;
    private double pinchStartPxDist;
    private double pinchStartCamDist;
    private double dragStartWX, dragStartWZ;
    private long lastTapUpMs;
    private double lastTapVX, lastTapVY;
    private String lastTapNodeId = "";

    // 当前帧投影结果（onDraw 里算好，onTouchEvent 复用做命中测试）
    private List<Item> frameItems = new ArrayList<>();

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
        strokePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
        arcPaint.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        setFocusable(true);
        setClickable(true);
    }

    // =====================================================================
    // 对外 API：旧的 Node/OnNodeTap/setData/setSelected/setStatus 全部保留，
    // 新增 3D 相机/回调接口。
    // =====================================================================

    public void setOnNodeTap(OnNodeTap listener) {
        onNodeTap = listener;
    }

    public void setOnNodeDoubleTap(OnNodeDoubleTap listener) {
        onNodeDoubleTap = listener;
    }

    public void setOnSceneChanged(OnSceneChanged listener) {
        onSceneChanged = listener;
    }

    /** 用当前图谱快照刷新画面。Node 的 wx/wz 必须由调用方（MainActivity）先填好。 */
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

    /** 恢复持久化的相机（启动时由 MainActivity 从 SharedPreferences 读出后调用）。 */
    public void setCamera(double yawDeg, double pitchDeg, double dist) {
        if (Double.isFinite(yawDeg)) camYaw = Scene3D.wrap180(yawDeg);
        if (Double.isFinite(pitchDeg)) camPitch = Scene3D.clamp(pitchDeg, Scene3D.CFG.PITCH_MIN, Scene3D.CFG.PITCH_MAX);
        if (Double.isFinite(dist)) camDist = Scene3D.clamp(dist, Scene3D.CFG.DIST_MIN, Scene3D.CFG.DIST_MAX);
        invalidate();
    }

    /** 当前相机：[yawDeg, pitchDeg, dist]。 */
    public double[] getCamera() {
        return new double[]{camYaw, camPitch, camDist};
    }

    /** 复位到默认视角（双击空白）。 */
    public void resetCamera() {
        updateCamera(0, Scene3D.CFG.PITCH_DEFAULT, Scene3D.CFG.CAM_DIST);
    }

    private void updateCamera(double yaw, double pitch, double dist) {
        camYaw = Scene3D.wrap180(Scene3D.isFinite(yaw) ? yaw : 0);
        camPitch = Scene3D.clamp(Scene3D.isFinite(pitch) ? pitch : Scene3D.CFG.PITCH_DEFAULT,
                Scene3D.CFG.PITCH_MIN, Scene3D.CFG.PITCH_MAX);
        camDist = Scene3D.clamp(Scene3D.isFinite(dist) ? dist : Scene3D.CFG.CAM_DIST,
                Scene3D.CFG.DIST_MIN, Scene3D.CFG.DIST_MAX);
        final OnSceneChanged cb = onSceneChanged;
        if (cb != null) cb.onCameraChanged(camYaw, camPitch, camDist);
        invalidate();
    }

    private Node find(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Node n : nodes) {
            if (id.equals(n.id)) return n;
        }
        return null;
    }

    // =====================================================================
    // 屏幕坐标：所有绘制/手势都换算到 920x460 虚拟画布；touch 事件再换算回来。
    // =====================================================================

    private double scaleX(int w) {
        return w > 0 ? w / Scene3D.VW : 1.0;
    }

    private double scaleY(int h) {
        return h > 0 ? h / Scene3D.VH : 1.0;
    }

    private double toVirtualX(float px) {
        return px / scaleX(getWidth());
    }

    private double toVirtualY(float py) {
        return py / scaleY(getHeight());
    }

    // =====================================================================
    // 绘制
    // =====================================================================

    /** 一帧里一个节点的投影结果（对应 scene3d.js projectAll 的 item）。 */
    private static final class Item implements Scene3D.Hittable {
        Node node;
        boolean visible;
        double sx, sy, depth, ppu, rPx, azDeg, dist;
        boolean selected;

        @Override
        public boolean visible() {
            return visible;
        }

        @Override
        public double sx() {
            return sx;
        }

        @Override
        public double sy() {
            return sy;
        }

        @Override
        public double rPx() {
            return rPx;
        }

        @Override
        public double depth() {
            return depth;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.save();
        canvas.scale((float) scaleX(w), (float) scaleY(h));

        // ---- 背景 + 状态文字（虚拟坐标）----
        canvas.drawColor(Color.TRANSPARENT);
        if (!statusText.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(10.5f);
            textPaint.setColor(COLOR_DIM);
            textPaint.setAlpha(255);
            canvas.drawText(displayText(statusText), (float) Scene3D.CX, 16f, textPaint);
        }

        final Scene3D.Camera view = Scene3D.camera(camYaw, camDist, camPitch);
        final List<Item> items = buildItems(view);

        // ---- 地面（1 m / 2 m 环 + 方位/距离标注）----
        drawGround(canvas, view);

        if (items.isEmpty()) {
            drawGhostHub(canvas, view);
            canvas.restore();
            return;
        }

        // ---- 连线（hub → 每个节点，表示音频流走向）----
        drawLinks(canvas, items);

        // ---- 体：远的先画（painter's algorithm，近的后画压在上面）----
        for (Item it : items) {
            if (!it.visible) continue;
            drawNode(canvas, it);
        }

        canvas.restore();
    }

    /** 对应 scene3d.js projectAll()：投影所有节点，填 rPx/dist/azDeg，按 depth 从远到近排序。 */
    private List<Item> buildItems(Scene3D.Camera view) {
        final List<Item> items = new ArrayList<>(nodes.size());
        final Scene3D.Listener listener = Scene3D.listenerOf(view);
        int others = 0;
        for (Node n : nodes) {
            if (!n.hub) others++;
        }
        for (Node n : nodes) {
            final Item it = new Item();
            it.node = n;
            it.selected = !n.id.isEmpty() && n.id.equals(selectedId);
            final Scene3D.Vec3 world = Scene3D.Vec3.ground(n.wx, n.wz);
            final Scene3D.Projection p = Scene3D.project(view, world);
            it.visible = p.visible;
            it.sx = p.sx;
            it.sy = p.sy;
            it.depth = p.depth;
            it.ppu = p.ppu;
            final Scene3D.Az az = Scene3D.azOf(listener, world);
            it.azDeg = az.azDeg;
            it.dist = az.dist;
            n.azDeg = it.azDeg;
            it.rPx = n.hub
                    ? Scene3D.hubRadiusPx(p.ppu)
                    : Scene3D.nodeRadiusPx(p.ppu, it.dist);
            // 兼容旧字段：留个 2D 屏幕位置，方便调试/无障碍
            n.x = (float) it.sx;
            n.y = (float) it.sy;
            n.r = (float) it.rPx;
            items.add(it);
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Double.compare(b.depth, a.depth);
            }
        });
        frameItems = items;
        return items;
    }

    /** 地面：R=1 m 实线内圈、R=2 m 虚线外圈 + 前/右/后/左方位 + 45° 距离标注 + 原点。 */
    private void drawGround(Canvas canvas, Scene3D.Camera view) {
        drawRing(canvas, view, Scene3D.CFG.RING_R, false, COLOR_GROUND);
        drawRing(canvas, view, Scene3D.CFG.ROOM_R, true, COLOR_GROUND_DIM);

        // 方位标注（1.75 m 处，略小于房间边界，避免压到节点）
        final double markR = (Scene3D.CFG.RING_R + Scene3D.CFG.ROOM_R) / 2.0;
        final String[] marks = {"前", "右", "后", "左"};
        final double[] azs = {0, 90, 180, 270};
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(10f);
        textPaint.setColor(COLOR_FAINT);
        textPaint.setAlpha((int) Math.round(255 * 0.85f));
        for (int i = 0; i < marks.length; ++i) {
            final double a = azs[i] * Scene3D.DEG;
            final Scene3D.Vec3 world = Scene3D.Vec3.ground(markR * Math.sin(a), -markR * Math.cos(a));
            final Scene3D.Projection p = Scene3D.project(view, world);
            if (!p.visible) continue;
            canvas.drawText(marks[i], (float) p.sx, (float) p.sy + 3.5f, textPaint);
        }

        // 距离标注：两个环各在 45° 方向标 "1 m" / "2 m"
        textPaint.setTextSize(9.5f);
        textPaint.setColor(COLOR_FAINT);
        textPaint.setAlpha((int) Math.round(255 * 0.70f));
        final double[] ringRs = {Scene3D.CFG.RING_R, Scene3D.CFG.ROOM_R};
        for (double rr : ringRs) {
            final double a = 45 * Scene3D.DEG;
            final Scene3D.Vec3 world = Scene3D.Vec3.ground(rr * Math.sin(a), -rr * Math.cos(a));
            final Scene3D.Projection p = Scene3D.project(view, world);
            if (!p.visible) continue;
            final String label = formatMeters(rr) + " m";
            canvas.drawText(label, (float) p.sx + 4f, (float) p.sy, textPaint);
        }
        textPaint.setAlpha(255);

        // 原点（听者位置）小圆点
        final Scene3D.Projection origin = Scene3D.project(view, new Scene3D.Vec3(0, 0, 0));
        if (origin.visible) {
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setShader(null);
            fillPaint.setColor(COLOR_GROUND_DIM);
            fillPaint.setAlpha(255);
            canvas.drawCircle((float) origin.sx, (float) origin.sy, 1.6f, fillPaint);
        }
    }

    private void drawRing(Canvas canvas, Scene3D.Camera view, double radius, boolean dashed, int color) {
        final int segments = 96;
        linePaint.setPathEffect(dashed ? dashGround : null);
        linePaint.setColor(color);
        linePaint.setStrokeWidth(1f);
        linePaint.setAlpha(255);
        double prevX = 0, prevY = 0;
        boolean hasPrev = false;
        boolean prevVisible = false;
        for (int i = 0; i <= segments; ++i) {
            final double a = 2 * Math.PI * i / segments;
            final Scene3D.Projection p = Scene3D.project(view,
                    Scene3D.Vec3.ground(radius * Math.sin(a), -radius * Math.cos(a)));
            if (!p.visible) {
                prevVisible = false;
                continue;
            }
            if (hasPrev && prevVisible) {
                canvas.drawLine((float) prevX, (float) prevY, (float) p.sx, (float) p.sy, linePaint);
            }
            prevX = p.sx;
            prevY = p.sy;
            hasPrev = true;
            prevVisible = true;
        }
        linePaint.setPathEffect(null);
    }

    /** 连线：hub → 每个在线/离线节点；选中加粗、有流高亮，电平越亮越粗（对应 scene3d.js drawLinks）。 */
    private void drawLinks(Canvas canvas, List<Item> items) {
        Item hub = null;
        for (Item it : items) {
            if (it.node.hub && it.visible) {
                hub = it;
                break;
            }
        }
        if (hub == null) return;
        for (Item it : items) {
            if (it.node.hub || !it.visible) continue;
            final double boost = levelBoost(it.node);
            linePaint.setPathEffect(null);
            if (it.selected) {
                linePaint.setColor(COLOR_ACCENT_HI);
                linePaint.setStrokeWidth((float) Math.max(3, 2 + boost));
                linePaint.setAlpha(255);
            } else if (it.node.streamId > 0) {
                linePaint.setColor(COLOR_FLOW);
                linePaint.setStrokeWidth((float) (2 + boost));
                linePaint.setAlpha((int) Math.round(255 * 0.95f));
            } else {
                linePaint.setColor(COLOR_LINE);
                linePaint.setStrokeWidth(2f);
                linePaint.setAlpha((int) Math.round(255 * 0.70f));
            }
            canvas.drawLine((float) hub.sx, (float) hub.sy, (float) it.sx, (float) it.sy, linePaint);
        }
        linePaint.setAlpha(255);
    }

    private double levelBoost(Node n) {
        if (n.streamId <= 0 || Double.isNaN(n.level)) return 0;
        return Scene3D.levelPct(n.level) / 26.0;
    }

    /** 单个球：光晕/球体渐变/描边（离线虚线）/电平弧/选中环/内部缩写/角标/名称与信息。 */
    private void drawNode(Canvas canvas, Item it) {
        final Node n = it.node;
        final double r = it.rPx;
        final float cx = (float) it.sx;
        final float cy = (float) it.sy;
        final float alpha = nodeAlpha(it);
        final boolean offline = !n.online && !n.hub;
        final boolean ghost = false; // 空图谱的 ghost hub 单独画

        // 底部光晕：hub 常亮（scene3d.js 两个同心圆 alpha .10/.14）
        if (n.hub) {
            fillPaint.setShader(null);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(COLOR_ACCENT);
            fillPaint.setAlpha((int) Math.round(255 * 0.10f * alpha));
            canvas.drawCircle(cx, cy, (float) (r * (1 + 15.0 / 46.0)), fillPaint);
            fillPaint.setAlpha((int) Math.round(255 * 0.14f * alpha));
            canvas.drawCircle(cx, cy, (float) (r * (1 + 7.0 / 46.0)), fillPaint);
        }

        // 球体底色
        fillPaint.setShader(null);
        fillPaint.setColor(ballColor(n, ghost));
        fillPaint.setAlpha((int) Math.round(255 * alpha));
        canvas.drawCircle(cx, cy, (float) r, fillPaint);

        // 球面渐变（左上高光 → 右下暗角），对应 scene3d.js getSprite 的 radialGradient stops
        shaderPaint.setStyle(Paint.Style.FILL);
        shaderPaint.setShader(new RadialGradient(
                cx - (float) (r * 0.34), cy - (float) (r * 0.38), (float) (r * 1.06),
                new int[]{
                        Color.argb(Math.round(255 * 0.40f), 255, 255, 255),
                        Color.argb(Math.round(255 * 0.10f), 255, 255, 255),
                        Color.argb(Math.round(255 * 0.06f), 0, 0, 0),
                        Color.argb(Math.round(255 * 0.34f), 0, 0, 0)},
                new float[]{0f, 0.42f, 0.82f, 1f},
                Shader.TileMode.CLAMP));
        shaderPaint.setAlpha((int) Math.round(255 * alpha));
        canvas.drawCircle(cx, cy, (float) r, shaderPaint);
        shaderPaint.setShader(null);

        // 描边：选中加粗、离线虚线置灰
        strokePaint.setPathEffect(offline ? dashOffline : null);
        strokePaint.setColor(it.selected ? COLOR_ACCENT_HI
                : (offline ? COLOR_STROKE_OFF : COLOR_STROKE));
        strokePaint.setStrokeWidth(it.selected ? 3f : 2f);
        strokePaint.setAlpha((int) Math.round(255 * alpha));
        canvas.drawCircle(cx, cy, (float) r, strokePaint);
        strokePaint.setPathEffect(null);

        // 电平弧：从正上方顺时针扫 levelPct，半径 r+5
        if (n.streamId > 0 && !Double.isNaN(n.level)) {
            final double pct = Scene3D.levelPct(n.level);
            arcPaint.setColor(COLOR_FLOW);
            arcPaint.setStrokeWidth(2f);
            arcPaint.setAlpha((int) Math.round(255 * 0.85f * alpha));
            canvas.drawArc(new android.graphics.RectF(
                            cx - (float) (r + 5), cy - (float) (r + 5),
                            cx + (float) (r + 5), cy + (float) (r + 5)),
                    -90f, (float) (pct * 360), false, arcPaint);
        }

        // 选中外圈（PC 端 r+3 的 1.5px 描边）
        if (it.selected) {
            strokePaint.setColor(COLOR_ACCENT_HI);
            strokePaint.setStrokeWidth(1.5f);
            strokePaint.setAlpha((int) Math.round(255 * 0.45f));
            canvas.drawCircle(cx, cy, (float) (r + 3), strokePaint);
        }

        // 球内缩写："本机" / "?" / 名称前 4~6 个字符
        final String inside;
        if (n.self) {
            inside = "本机";
        } else if (ghost) {
            inside = "?";
        } else {
            inside = Scene3D.shortName(displayName(n), n.hub ? 6 : 4);
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize((float) Scene3D.clamp(r * 0.34, 11, 15));
        textPaint.setColor(COLOR_TEXT);
        textPaint.setAlpha((int) Math.round(255 * alpha));
        canvas.drawText(displayText(inside), cx, cy - textPaint.getFontMetrics().descent / 2f, textPaint);

        // 右上角标：本机 / 汇聚端 / 本机·汇聚端
        final String badge = badgeText(n);
        if (!badge.isEmpty()) {
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(10.5f);
            textPaint.setColor(COLOR_ACCENT_HI);
            textPaint.setAlpha((int) Math.round(255 * alpha));
            final float textWidth = textPaint.measureText(badge);
            float bx = cx + (float) r + 6f;
            boolean flip = false;
            if (bx + textWidth > Scene3D.VW - 4) {
                flip = true;
                bx = cx - (float) r - 6f;
            }
            final float by = (float) Scene3D.clamp((float) (cy - r + 2), 10, Scene3D.VH - 6);
            textPaint.setTextAlign(flip ? Paint.Align.RIGHT : Paint.Align.LEFT);
            canvas.drawText(badge, bx, by, textPaint);
        }

        // 名称 + 第二行（电平 · 距离）
        textPaint.setTypeface(Typeface.DEFAULT);
        if (n.hub) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(13f);
            textPaint.setColor(COLOR_TEXT);
            textPaint.setAlpha((int) Math.round(255 * alpha));
            canvas.drawText(displayText(Scene3D.clampText(displayName(n), 22)),
                    cx, (float) (cy + r + 18), textPaint);
            if (n.streamId > 0 && !Double.isNaN(n.level)) {
                textPaint.setTextSize(11.5f);
                textPaint.setColor(COLOR_FAINT);
                canvas.drawText(displayText(Scene3D.dbText(n.level)),
                        cx, (float) (cy + r + 33), textPaint);
            }
        } else if (showName(it)) {
            final boolean above = cy < Scene3D.CY;
            final float nameY = above
                    ? (float) (cy - r - 9)
                    : (float) (cy + r + 18);
            final float infoY = above ? nameY - 14f : nameY + 14f;
            final float nameClamped = (float) Scene3D.clamp(nameY, 10, Scene3D.VH - 6);
            final float infoClamped = (float) Scene3D.clamp(infoY, 10, Scene3D.VH - 6);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(12.5f);
            textPaint.setColor(it.selected ? COLOR_ACCENT_HI : COLOR_TEXT);
            textPaint.setAlpha((int) Math.round(255 * Math.min(1.0, alpha + 0.15)));
            canvas.drawText(displayText(Scene3D.clampText(displayName(n), 18)),
                    cx, nameClamped, textPaint);
            textPaint.setTextSize(11.5f);
            textPaint.setColor(COLOR_FAINT);
            textPaint.setAlpha((int) Math.round(255 * alpha));
            canvas.drawText(displayText(infoText(it)), cx, infoClamped, textPaint);
        }
        textPaint.setAlpha(255);
        fillPaint.setAlpha(255);
    }

    private boolean showName(Item it) {
        if (it.selected) return true;
        int others = 0;
        for (Item other : frameItems) {
            if (!other.node.hub) others++;
        }
        return others <= Scene3D.CFG.LABEL_LOD || frameItems.size() < 2;
    }

    private String infoText(Item it) {
        final Node n = it.node;
        final String dist = formatMeters(it.dist) + " m";
        if (n.streamId > 0 && !Double.isNaN(n.level)) {
            return Scene3D.dbText(n.level) + " · " + dist;
        }
        return dist;
    }

    private float nodeAlpha(Item it) {
        if (!it.node.online) return 0.55f;
        if (it.depth > Scene3D.CFG.CAM_DIST) return 0.78f;
        return 1f;
    }

    private int ballColor(Node n, boolean ghost) {
        if (ghost) return COLOR_FILL_GHOST;
        if (!n.online) return COLOR_FILL_OFF;
        if (n.hub) return COLOR_ACCENT;
        return COLOR_FILL_NODE;
    }

    private String badgeText(Node n) {
        if (n.self && n.hub) return "本机·汇聚端";
        if (n.self) return "本机";
        if (n.hub) return "汇聚端";
        return "";
    }

    /** 空图谱：在原点画一个虚线 ghost 球 + "等待设备…"（对应 PC 端 "等待图谱…"）。 */
    private void drawGhostHub(Canvas canvas, Scene3D.Camera view) {
        final Scene3D.Projection p = Scene3D.project(view, new Scene3D.Vec3(0, 0, 0));
        if (p.visible) {
            final float r = (float) Scene3D.hubRadiusPx(p.ppu);
            fillPaint.setShader(null);
            fillPaint.setColor(COLOR_FILL_GHOST);
            fillPaint.setAlpha((int) Math.round(255 * 0.9f));
            canvas.drawCircle((float) p.sx, (float) p.sy, r, fillPaint);
            strokePaint.setColor(COLOR_STROKE_OFF);
            strokePaint.setStrokeWidth(2f);
            strokePaint.setPathEffect(dashOffline);
            strokePaint.setAlpha((int) Math.round(255 * 0.9f));
            canvas.drawCircle((float) p.sx, (float) p.sy, r, strokePaint);
            strokePaint.setPathEffect(null);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize((float) Scene3D.clamp(r * 0.34, 11, 15));
            textPaint.setColor(COLOR_DIM);
            textPaint.setAlpha(255);
            canvas.drawText("?", (float) p.sx,
                    (float) p.sy - textPaint.getFontMetrics().descent / 2f, textPaint);
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(12f);
        textPaint.setColor(COLOR_DIM);
        textPaint.setAlpha(255);
        canvas.drawText("正在等待设备…", (float) Scene3D.CX, (float) Scene3D.VH * 0.62f, textPaint);
    }

    // =====================================================================
    // 手势
    // =====================================================================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final double vx = toVirtualX(event.getX());
        final double vy = toVirtualY(event.getY());

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                handler.removeCallbacks(longPressRunnable);
                mode = MODE_NONE;
                gestureNode = null;
                gestureMoved = false;
                gestureLongPressed = false;
                downVX = vx;
                downVY = vy;
                lastVX = vx;
                lastVY = vy;
                final Item hit = pick(vx, vy);
                if (hit != null && !hit.node.hub) {
                    mode = MODE_NODE;
                    gestureNode = hit.node;
                    dragStartWX = hit.node.wx;
                    dragStartWZ = hit.node.wz;
                    if (hit.node.pinned) {
                        handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    }
                } else {
                    mode = MODE_ORBIT;
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                handler.removeCallbacks(longPressRunnable);
                if (event.getPointerCount() >= 2) {
                    mode = MODE_PINCH;
                    gestureMoved = true;
                    pinchStartPxDist = touchDistance(event);
                    pinchStartCamDist = camDist;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_PINCH) {
                    if (event.getPointerCount() >= 2 && pinchStartPxDist > 1e-3) {
                        final double d = touchDistance(event);
                        if (d > 1e-3) {
                            camDist = Scene3D.clamp(pinchStartCamDist * pinchStartPxDist / d,
                                    Scene3D.CFG.DIST_MIN, Scene3D.CFG.DIST_MAX);
                            final OnSceneChanged cb = onSceneChanged;
                            if (cb != null) cb.onCameraChanged(camYaw, camPitch, camDist);
                            invalidate();
                        }
                    }
                    return true;
                }
                if (mode == MODE_ORBIT) {
                    final double dx = vx - lastVX;
                    final double dy = vy - lastVY;
                    if (Math.abs(dx) + Math.abs(dy) > 0) {
                        camYaw = Scene3D.wrap180(camYaw - dx * 0.35);
                        camPitch = Scene3D.clamp(camPitch + dy * 0.25,
                                Scene3D.CFG.PITCH_MIN, Scene3D.CFG.PITCH_MAX);
                        final OnSceneChanged cb = onSceneChanged;
                        if (cb != null) cb.onCameraChanged(camYaw, camPitch, camDist);
                        invalidate();
                    }
                    lastVX = vx;
                    lastVY = vy;
                    return true;
                }
                if (mode == MODE_NODE && gestureNode != null) {
                    final double total = Math.abs(vx - downVX) + Math.abs(vy - downVY);
                    if (total > TAP_SLOP) {
                        if (!gestureMoved) handler.removeCallbacks(longPressRunnable);
                        gestureMoved = true;
                    }
                    if (gestureMoved) {
                        final Scene3D.Camera view = Scene3D.camera(camYaw, camDist, camPitch);
                        final Scene3D.Vec3 ground = Scene3D.unproject(view, vx, vy, 0);
                        if (ground != null) {
                            double gx = ground.x;
                            double gz = ground.z;
                            double rr = Math.hypot(gx, gz);
                            final double maxR = Math.max(Scene3D.CFG.DRAG_R_MIN,
                                    Math.min(Scene3D.CFG.DRAG_R_MAX, camDist * 0.75));
                            if (rr < 1e-6) {
                                gx = 0;
                                gz = -Scene3D.CFG.DRAG_R_MIN;
                                rr = Scene3D.CFG.DRAG_R_MIN;
                            }
                            if (rr < Scene3D.CFG.DRAG_R_MIN) {
                                final double k = Scene3D.CFG.DRAG_R_MIN / rr;
                                gx *= k;
                                gz *= k;
                            } else if (rr > maxR) {
                                final double k = maxR / rr;
                                gx *= k;
                                gz *= k;
                            }
                            gestureNode.wx = gx;
                            gestureNode.wz = gz;
                            gestureNode.azDeg = Scene3D.wrap360(Math.atan2(gx, -gz) / Scene3D.DEG);
                            invalidate();
                        }
                    }
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPressRunnable);
                final boolean wasLongPress = gestureLongPressed;
                gestureLongPressed = false;
                if (mode == MODE_NODE && gestureNode != null) {
                    final Node n = gestureNode;
                    if (gestureMoved) {
                        n.pinned = true;
                        final OnSceneChanged cb = onSceneChanged;
                        if (cb != null) cb.onPinChanged(n.id, n.wx, n.wz);
                    } else if (!wasLongPress) {
                        handleTap(vx, vy);
                    }
                } else if (mode == MODE_ORBIT && !gestureMoved) {
                    final Item hit = pick(vx, vy);
                    if (hit != null) {
                        handleTap(vx, vy);
                    } else if (isDoubleTap(vx, vy)) {
                        // 双击空白：复位相机
                        lastTapUpMs = 0;
                        updateCamera(0, Scene3D.CFG.PITCH_DEFAULT, Scene3D.CFG.CAM_DIST);
                    }
                }
                mode = MODE_NONE;
                gestureNode = null;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRunnable);
                mode = MODE_NONE;
                gestureNode = null;
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (mode == MODE_PINCH) {
                    mode = MODE_NONE;
                    gestureNode = null;
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleTap(double vx, double vy) {
        final Item hit = pick(vx, vy);
        if (hit == null) {
            lastTapVX = vx;
            lastTapVY = vy;
            lastTapNodeId = "";
            lastTapUpMs = System.currentTimeMillis();
            return;
        }
        final Node n = hit.node;
        selectedId = n.id != null ? n.id : "";
        invalidate();
        final boolean dbl = isDoubleTapNode(vx, vy, n.id);
        lastTapVX = vx;
        lastTapVY = vy;
        lastTapNodeId = n.id != null ? n.id : "";
        lastTapUpMs = System.currentTimeMillis();
        if (dbl) {
            lastTapUpMs = 0;
            final OnNodeDoubleTap cb = onNodeDoubleTap;
            if (cb != null) {
                cb.onDoubleTap(n);
                return;
            }
        }
        final OnNodeTap listener = onNodeTap;
        if (listener != null) listener.onTap(n);
    }

    private boolean isDoubleTap(double vx, double vy) {
        final long now = System.currentTimeMillis();
        final boolean near = Math.abs(vx - lastTapVX) < 24 && Math.abs(vy - lastTapVY) < 24;
        final boolean blank = lastTapNodeId.isEmpty();
        return blank && near && now - lastTapUpMs <= DOUBLE_TAP_MS;
    }

    private boolean isDoubleTapNode(double vx, double vy, String id) {
        final long now = System.currentTimeMillis();
        final boolean near = Math.abs(vx - lastTapVX) < 24 && Math.abs(vy - lastTapVY) < 24;
        return near && id != null && id.equals(lastTapNodeId)
                && now - lastTapUpMs <= DOUBLE_TAP_MS;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 虚拟坐标命中测试（对应 scene3d.js pickAt：对当前帧投影结果做 hitTest，slop=6）。 */
    private Item pick(double vx, double vy) {
        if (frameItems.isEmpty()) return null;
        final Scene3D.Hittable hit = Scene3D.hitTest(frameItems, vx, vy, HIT_SLOP);
        if (hit == null) return null;
        for (Item it : frameItems) {
            if (it == hit) return it;
        }
        // hitTest 返回的可能是别的 Hittable 实现，按 id 找不到就返回 null（防御）
        return null;
    }

    private double touchDistance(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        final double dx = e.getX(0) - e.getX(1);
        final double dy = e.getY(0) - e.getY(1);
        return Math.hypot(dx, dy);
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    /** 名字兜底：永不返回 null（drawText(null) 会 NPE 崩 UI 线程）。 */
    private String displayName(Node n) {
        if (n.name != null && !n.name.trim().isEmpty()) return n.name;
        if (n.id != null && !n.id.isEmpty()) return n.id;
        return "未知设备";
    }

    private String displayText(String s) {
        return s != null ? s : "";
    }

    /** 米数格式化：1 位小数就够（"2.0"），与 PC 端 dist.toFixed(2) 类似。 */
    private String formatMeters(double m) {
        return String.format(Locale.US, "%.1f", m);
    }

    /**
     * CSS oklch(L C H / alpha) → Android ARGB。oklab→线性 sRGB→gamma 编码，
     * 用于让配色与 PC 端 styles.css（深色主题）逐色一致。
     */
    private static int oklch(double l, double c, double hDeg, double alpha) {
        final double h = hDeg * Math.PI / 180.0;
        final double a = c * Math.cos(h);
        final double b = c * Math.sin(h);
        final double l_ = l + 0.3963377774 * a + 0.2158037573 * b;
        final double m_ = l - 0.1055613458 * a - 0.0638541728 * b;
        final double s_ = l - 0.0894841775 * a - 1.2914855480 * b;
        final double l3 = l_ * l_ * l_;
        final double m3 = m_ * m_ * m_;
        final double s3 = s_ * s_ * s_;
        double r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3;
        double g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3;
        double bb = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3;
        r = gamma(r);
        g = gamma(g);
        bb = gamma(bb);
        return Color.argb(
                (int) Math.round(Scene3D.clamp(alpha, 0, 1) * 255),
                (int) Math.round(Scene3D.clamp(r, 0, 1) * 255),
                (int) Math.round(Scene3D.clamp(g, 0, 1) * 255),
                (int) Math.round(Scene3D.clamp(bb, 0, 1) * 255));
    }

    private static double gamma(double c) {
        if (c <= 0.0031308) return 12.92 * c;
        return 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
    }
}