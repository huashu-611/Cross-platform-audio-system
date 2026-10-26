package com.audiohub.source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 3D 物理房间场景的纯数学层：逐函数移植 PC 端 ui/scene3d.js（v1.3 设计，
 * 见 docs/3D-界面与空间音频-可行性与实现方案.md §7）。
 *
 * <p>为什么单独一个类：本机没有 Android SDK，透视投影 / 反投影 / 32 槽位金角螺旋布局 /
 * 听者方位角这些数学必须能脱离 android.* 用 javac + main() 自检（见
 * android/verify/SceneCheck.java）。HubGraphView 只做 Canvas 绘制与手势，全部数值从本类取，
 * 不重复推导公式；MainActivity 用同一套函数算世界坐标并上报空间化。
 *
 * <p>坐标系（与 scene3d.js 逐字一致）：右手系，+Y 向上，1 unit = 1 m；听者/汇聚端在原点。
 * 听者固定 yaw=0、面朝 <b>-Z</b>（相机默认 φ=0 站在 +Z 看向 -Z）。因此：
 * <ul>
 *   <li>屏幕上方 = 远处 = 听者正前（-Z）；屏幕下方 = 身后（+Z）；</li>
 *   <li>屏幕右 = +X = 右耳；屏幕左 = -X = 左耳；</li>
 *   <li>相机 orbit 只改“从哪看”，不转听者、不转声场。</li>
 * </ul>
 *
 * <p>屏幕坐标一律使用 PC 端 920x460 的“虚拟画布”坐标（CX=460, CY=208），
 * HubGraphView 用等比缩放映射到真实 View，所有美术尺寸与 PC 端对齐。
 *
 * <p>Java 8、无 android.* 依赖、无第三方依赖。
 */
public final class Scene3D {

    private Scene3D() {
    }

    // =====================================================================
    // 一、虚拟画布（与 scene3d.js 顶部常量逐字一致）
    // =====================================================================

    /** 角度制 → 弧度（必须先声明：FOCAL 的初始化表达式要用，Java 禁止前向引用）。 */
    public static final double DEG = Math.PI / 180.0;

    /** 虚拟画布宽（px）。 */
    public static final double VW = 920;
    /** 虚拟画布高（px）。 */
    public static final double VH = 460;
    /** 画面中心 x：汇聚端固定投影在 (460,208)。 */
    public static final double CX = 460;
    /** 画面中心 y（水平线，略高于几何中心，给下方地面留视觉空间）。 */
    public static final double CY = 208;
    /** 垂直视场角（度）。 */
    public static final double FOV_Y = 47.5;
    /** 焦距（px）：(VH/2)/tan(FOV_Y/2) = 522.714762，与旧 920x460 视口对齐。 */
    public static final double FOCAL = (VH / 2) / Math.tan((FOV_Y / 2) * DEG);

    /** 32 个预计算槽位（金角螺旋）。 */
    public static final int SLOT_COUNT = 32;

    // =====================================================================
    // 二、CFG（逐项自 scene3d.js 的 CFG 平移，数值完全一致）
    // =====================================================================

    /** 与 scene3d.js 的 CFG 一一对应（命名改为 Java 常量风格，值不变）。 */
    public static final class CFG {
        private CFG() {
        }

        // —— 房间 / 听者（v1.3 物理空间模型）——
        /** 听者固定朝 -Z（屏幕上方=正前）；相机绕看不改变声场。 */
        public static final double LISTENER_YAW_DEG = 0;
        /** 房间参考半径（地面方位刻度用，米）。 */
        public static final double ROOM_R = 2.4;
        /** 内圈参考半径（地面 R=1 环）。 */
        public static final double RING_R = 1.0;
        /** 默认相机距离：要同时看下 1 m / 2 m 环与后方节点（原 2.6 只够 1 m 环）。 */
        public static final double CAM_DIST = 3.4;

        // —— 默认摆放：32 个互不重叠的预计算槽位 ——
        public static final double SLOT_R_MIN = 0.85;
        public static final double SLOT_R_MAX = 2.2;
        /** 前方（-Z）节点投影会贴近 hub，太近会叠球。 */
        public static final double SLOT_FRONT_MIN = 1.35;
        /** 后方（+Z，靠近相机）再远投影会掉出屏幕下沿。 */
        public static final double SLOT_REAR_MAX = 1.5;
        /** 槽位间最小平面距离（米），保证球不叠。 */
        public static final double SLOT_GAP = 0.42;

        // —— 拖动 / 相机 ——
        /** 拖节点离听者太近会“穿过头”。 */
        public static final double DRAG_R_MIN = 0.45;
        /** 房间边界（地面 R=2 环外侧留一点余量）。 */
        public static final double DRAG_R_MAX = 2.3;

        // —— 球体尺寸：屏幕半径 = 透视投影 × 距离缩放（近大远小，与音量衰减同向）——
        /** 旧 HUB_R=46px 反解：46*2.6/522.71≈0.229。 */
        public static final double HUB_BALL_R = 0.23;
        /** 旧 NODE_R=29px 反解：29*2.6/522.71≈0.144。 */
        public static final double NODE_BALL_R = 0.145;
        /** 距离 1 m 时缩放 = 1。 */
        public static final double SIZE_REF = 1.0;
        /** scale = (sizeRef/dist)^sizePow。 */
        public static final double SIZE_POW = 0.55;
        public static final double SIZE_MIN = 0.55;
        public static final double SIZE_MAX = 1.5;

        public static final double FOV_Y_DEG = FOV_Y;
        /** 仅 camera() 省略 pitch 时反解用（原型口径 / 反解工具），交互不使用。 */
        public static final double CAM_H = 0.72;
        /** 默认俯角（D1 修订：16.07° → 40°，避免前排节点被 hub 球遮挡）。 */
        public static final double PITCH_DEFAULT = 40;
        /** 俯仰下限，避免视线与地面平行（unproject 退化）。 */
        public static final double PITCH_MIN = 6;
        public static final double PITCH_MAX = 74;
        /** 滚轮缩放下限/上限。 */
        public static final double DIST_MIN = 1.2;
        public static final double DIST_MAX = 6.0;

        public static final double NEAR = 0.05;
        public static final double FOCAL_PX = FOCAL;
        /** 球半径像素夹紧：远处可读、近处不压邻居。 */
        public static final double HUB_R_MIN = 26;
        public static final double HUB_R_MAX = 64;
        public static final double NODE_R_MIN = 12;
        public static final double NODE_R_MAX = 40;
        public static final double DPR_MAX = 2;
        /** 非 hub 节点 >8 时只给选中/悬停画名称（报告 §2.1.3/R3）。 */
        public static final int LABEL_LOD = 8;
        /** 切 hub / 节点增删的位置插值时长（ms）。 */
        public static final int ANIM_MS = 250;
    }

    // =====================================================================
    // 三、向量工具（对应 scene3d.js 的 add/sub/mul/dot/cross/len/norm）
    // =====================================================================

    /** 不可变三维向量（世界坐标，米）。 */
    public static final class Vec3 {
        public final double x;
        public final double y;
        public final double z;

        public Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** 地面点 (x, 0, z)。 */
        public static Vec3 ground(double x, double z) {
            return new Vec3(x, 0, z);
        }

        public static Vec3 add(Vec3 a, Vec3 b) {
            return new Vec3(a.x + b.x, a.y + b.y, a.z + b.z);
        }

        public static Vec3 sub(Vec3 a, Vec3 b) {
            return new Vec3(a.x - b.x, a.y - b.y, a.z - b.z);
        }

        public static Vec3 mul(Vec3 a, double s) {
            return new Vec3(a.x * s, a.y * s, a.z * s);
        }

        public static double dot(Vec3 a, Vec3 b) {
            return a.x * b.x + a.y * b.y + a.z * b.z;
        }

        public static Vec3 cross(Vec3 a, Vec3 b) {
            return new Vec3(
                    a.y * b.z - a.z * b.y,
                    a.z * b.x - a.x * b.z,
                    a.x * b.y - a.y * b.x);
        }

        public static double len(Vec3 a) {
            return Math.hypot(Math.hypot(a.x, a.y), a.z);
        }

        public static Vec3 norm(Vec3 a) {
            final double l = len(a);
            return l > 1e-12 ? new Vec3(a.x / l, a.y / l, a.z / l) : new Vec3(0, 0, 0);
        }

        public String toString() {
            return String.format(Locale.US, "(%.6f, %.6f, %.6f)", x, y, z);
        }
    }

    // =====================================================================
    // 四、相机与投影（逐行对应 scene3d.js camera/project/unproject）
    // =====================================================================

    /** 透视相机（lookAt 原点）。 */
    public static final class Camera {
        public final Vec3 eye;
        public final Vec3 fwd;
        public final Vec3 right;
        public final Vec3 up;
        /** 环绕角 yaw（度，wrap180）。 */
        public final double phiDeg;
        /** 轨道距离（m）。 */
        public final double dist;
        /** 俯仰角（度）。 */
        public final double pitchDeg;
        /** 焦距（px）。 */
        public final double focal;

        Camera(Vec3 eye, Vec3 fwd, Vec3 right, Vec3 up,
               double phiDeg, double dist, double pitchDeg, double focal) {
            this.eye = eye;
            this.fwd = fwd;
            this.right = right;
            this.up = up;
            this.phiDeg = phiDeg;
            this.dist = dist;
            this.pitchDeg = pitchDeg;
            this.focal = focal;
        }

        public String toString() {
            return String.format(Locale.US,
                    "Camera{yaw=%.3f, pitch=%.3f, dist=%.3f, eye=%s}",
                    phiDeg, pitchDeg, dist, eye);
        }
    }

    /**
     * 相机（移植自 scene3d.js camera()）：绕原点轨道，φ 绕 +Y，pitch 为俯角。
     *
     * <p>eye = [d*cos(p)*sin(φ), d*sin(p), d*cos(p)*cos(φ)]；fwd = norm(-eye)；
     * right = norm(cross(fwd, WORLD_UP))（φ=0 时 = +X 右耳）；up = cross(right, fwd)。
     *
     * @param phiDeg   环绕角（NaN → 0）
     * @param dist     距离（NaN → 3.4，夹 1.2..6.0）
     * @param pitchDeg 俯角（NaN → 按相机高度 camH 反解 asin(camH/dist)；显式值时夹 6..74）
     */
    public static Camera camera(double phiDeg, double dist, double pitchDeg) {
        final double d = isFinite(dist) ? clamp(dist, CFG.DIST_MIN, CFG.DIST_MAX) : CFG.CAM_DIST;
        final double phi = (isFinite(phiDeg) ? phiDeg : 0) * DEG;
        final double p = isFinite(pitchDeg)
                ? clamp(pitchDeg, CFG.PITCH_MIN, CFG.PITCH_MAX) * DEG
                : Math.asin(Math.min(CFG.CAM_H / d, 0.999));
        final Vec3 eye = new Vec3(
                d * Math.cos(p) * Math.sin(phi),
                d * Math.sin(p),
                d * Math.cos(p) * Math.cos(phi));
        final Vec3 fwd = Vec3.norm(Vec3.sub(new Vec3(0, 0, 0), eye));
        final Vec3 right = Vec3.norm(Vec3.cross(fwd, new Vec3(0, 1, 0)));
        final Vec3 up = Vec3.cross(right, fwd);
        return new Camera(eye, fwd, right, up,
                wrap180(phi / DEG), d, p / DEG, CFG.FOCAL_PX);
    }

    /** 相机（省略 pitch：按相机高度 camH 反解，仅供反解工具使用）。 */
    public static Camera camera(double phiDeg, double dist) {
        return camera(phiDeg, dist, Double.NaN);
    }

    /** 投影结果。 */
    public static final class Projection {
        /** 是否在近裁剪面之前（depth &gt; near）。 */
        public final boolean visible;
        /** 屏幕 x（虚拟画布坐标；不可见时无意义）。 */
        public final double sx;
        /** 屏幕 y（虚拟画布坐标；不可见时无意义）。 */
        public final double sy;
        /** 沿视线方向的深度（m）。 */
        public final double depth;
        /** 每米对应多少屏幕像素（focal/depth）。 */
        public final double ppu;

        Projection(boolean visible, double sx, double sy, double depth, double ppu) {
            this.visible = visible;
            this.sx = sx;
            this.sy = sy;
            this.depth = depth;
            this.ppu = ppu;
        }

        public String toString() {
            return String.format(Locale.US,
                    "Projection{visible=%s, sx=%.3f, sy=%.3f, depth=%.3f, ppu=%.3f}",
                    visible, sx, sy, depth, ppu);
        }
    }

    /** 世界点 → 虚拟 920x460 屏幕坐标（hub 恒为 (460,208)）。 */
    public static Projection project(Camera view, Vec3 p) {
        final Vec3 rel = Vec3.sub(p, view.eye);
        final double depth = Vec3.dot(rel, view.fwd);
        if (depth <= CFG.NEAR) {
            return new Projection(false, 0, 0, depth, 0);
        }
        final double k = view.focal / depth;
        return new Projection(true,
                CX + k * Vec3.dot(rel, view.right),
                CY - k * Vec3.dot(rel, view.up),
                depth, k);
    }

    /**
     * 虚拟屏幕 → 水平面 y=planeY 的世界点。拾取与拖动共用；pitch≥6° 时不会退化。
     *
     * @return 交点；视线与平面近平行（|dir.y|&lt;1e-6）或交点在相机背后（t&lt;=0）时返回 null
     */
    public static Vec3 unproject(Camera view, double sx, double sy, double planeY) {
        final double dx = (sx - CX) / view.focal;
        final double dy = -(sy - CY) / view.focal;
        final Vec3 dir = Vec3.norm(Vec3.add(
                Vec3.add(Vec3.mul(view.right, dx), Vec3.mul(view.up, dy)), view.fwd));
        if (Math.abs(dir.y) < 1e-6) {
            return null;
        }
        final double t = (planeY - view.eye.y) / dir.y;
        return t <= 0 ? null : Vec3.add(view.eye, Vec3.mul(dir, t));
    }

    /** 地面反投影（planeY=0）。 */
    public static Vec3 unproject(Camera view, double sx, double sy) {
        return unproject(view, sx, sy, 0);
    }

    // =====================================================================
    // 五、命中测试（移植自 scene3d.js hitTest()）
    // =====================================================================

    /** 参与命中测试的已投影节点（由 HubGraphView 的投影项实现，保证纯类可测）。 */
    public interface Hittable {
        boolean visible();

        double sx();

        double sy();

        double rPx();

        double depth();
    }

    /** 命中测试：默认容差 6 虚拟 px（与 PC 端 pickAt 一致）。 */
    public static Hittable hitTest(List<? extends Hittable> items, double sx, double sy) {
        return hitTest(items, sx, sy, 6);
    }

    /**
     * 命中测试：投影球半径 + 容差；圆内最近者优先，同距取更近深度；
     * visible=false（相机后方）自然不可点。
     *
     * @param slop 额外命中容差（虚拟 px；NaN → 6）
     */
    public static Hittable hitTest(List<? extends Hittable> items, double sx, double sy, double slop) {
        if (items == null) {
            return null;
        }
        final double s = isFinite(slop) ? slop : 6;
        Hittable best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            final Hittable n = items.get(i);
            if (n == null || !n.visible()) {
                continue;
            }
            final double rr = n.rPx() + s;
            final double ddx = n.sx() - sx;
            final double ddy = n.sy() - sy;
            final double d2 = ddx * ddx + ddy * ddy;
            if (d2 > rr * rr) {
                continue;
            }
            if (best == null || d2 < bestD2
                    || (d2 == bestD2 && n.depth() < best.depth())) {
                best = n;
                bestD2 = d2;
            }
        }
        return best;
    }

    // =====================================================================
    // 六、32 槽位（金角螺旋，逐行移植 scene3d.js buildSlots()）
    // =====================================================================

    /** 槽位：世界坐标（y=0，米）+ 方位角（度，0=正前(-Z)，+90=右耳）。 */
    public static final class Slot {
        public final Vec3 pos;
        public final double azDeg;

        Slot(Vec3 pos, double azDeg) {
            this.pos = pos;
            this.azDeg = azDeg;
        }

        public String toString() {
            return String.format(Locale.US, "Slot{%s, az=%.6f}", pos, azDeg);
        }
    }

    /**
     * 槽位初始半径表（逐字自 scene3d.js）：32 个互不相同的半径，覆盖 0.85–2.2 m。
     */
    private static final double[] SLOT_RADII = {
            2.00, 1.55, 1.20, 1.85, 0.95, 2.10, 1.30, 2.20,
            1.05, 1.70, 1.45, 0.85, 1.90, 1.15, 2.05, 1.60,
            1.00, 1.75, 0.90, 2.15, 1.35, 1.50, 1.80, 1.10,
            1.65, 1.25, 1.95, 0.85, 1.40, 1.85, 1.05, 1.55
    };

    private static final List<Slot> SLOTS = buildSlots();

    /** 候选点是否与已占槽位保持 slotGap（米）；移植自 scene3d.js slotFree()。 */
    private static boolean slotFree(List<Slot> placed, double x, double z) {
        for (int k = 0; k < placed.size(); k++) {
            final Vec3 p = placed.get(k).pos;
            final double dx = x - p.x;
            final double dz = z - p.z;
            if (dx * dx + dz * dz < CFG.SLOT_GAP * CFG.SLOT_GAP) {
                return false;
            }
        }
        return true;
    }

    /**
     * 32 个金角螺旋散点：θ 从 -90° 起每槽 +137.507764°；前方（z&lt;0，sinθ&lt;0）半径
     * 不小于 slotFrontMin=1.35，后方（z&gt;0）半径不超过 slotRearMax=1.5；若与已有槽位
     * 间距 &lt;0.42 m 就 θ+13.5° 重试、半径按前后方向微调，最多重试 24 次。
     * 24 次仍撞位时兜底：在前后/半径规则内全周扫（0.1°、0.01 m 网格，固定次序），
     * 选与已占槽位“最小间距最大”的点；同距取更靠近原方向/半径者（保证确定性）。
     */
    private static List<Slot> buildSlots() {
        final List<Slot> out = new ArrayList<Slot>(SLOT_COUNT);
        final double GA = 137.507764;
        for (int i = 0; i < SLOT_COUNT; i++) {
            double theta = (-90 + i * GA) * DEG;
            double r = SLOT_RADII[i % SLOT_RADII.length];
            // 前方（-Z）不能太近、后方（+Z，靠近相机）不能太远 —— 二者都会投影压球/出屏。
            r = Math.sin(theta) > 0 ? Math.min(r, CFG.SLOT_REAR_MAX) : Math.max(r, CFG.SLOT_FRONT_MIN);
            double x = 0;
            double z = 0;
            boolean accepted = false;
            for (int tries = 0; tries < 24; tries++) {
                x = r * Math.cos(theta);
                z = r * Math.sin(theta);
                if (slotFree(out, x, z)) {
                    accepted = true;
                    break;
                }
                theta += 13.5 * DEG;
                if (Math.sin(theta) > 0) {
                    r = Math.min(r + 0.05, CFG.SLOT_REAR_MAX);
                } else {
                    r = Math.min(r + 0.08, CFG.SLOT_R_MAX);
                }
                if (r < CFG.SLOT_R_MIN) {
                    r = CFG.SLOT_R_MIN;
                }
            }
            if (!accepted) {
                double bestX = x;
                double bestZ = z;
                double bestGap = -1;
                double bestCost = Double.MAX_VALUE;
                boolean hasLegal = false;
                double legalX = 0;
                double legalZ = 0;
                double legalCost = Double.MAX_VALUE;
                for (int a = 0; a < 3600; a++) {
                    final double th = a * 0.1 * DEG;
                    final boolean rear = Math.sin(th) > 0;
                    final double lo = rear ? CFG.SLOT_R_MIN : CFG.SLOT_FRONT_MIN;
                    final double hi = rear ? CFG.SLOT_REAR_MAX : CFG.SLOT_R_MAX;
                    for (double rr = lo; rr <= hi + 1e-9; rr += 0.01) {
                        final double px = rr * Math.cos(th);
                        final double pz = rr * Math.sin(th);
                        double gap = Double.MAX_VALUE;
                        for (int k = 0; k < out.size(); k++) {
                            final Vec3 p = out.get(k).pos;
                            final double dx = px - p.x;
                            final double dz = pz - p.z;
                            final double d = Math.sqrt(dx * dx + dz * dz);
                            if (d < gap) {
                                gap = d;
                            }
                        }
                        final double cost = Math.abs(th - theta) + Math.abs(rr - r);
                        // 优先“间距更大”；间距相同取更靠近原方向/原半径者（cost 越小越好）。
                        if (bestGap < 0 || gap > bestGap + 1e-9
                                || (Math.abs(gap - bestGap) <= 1e-9 && cost < bestCost)) {
                            bestX = px;
                            bestZ = pz;
                            bestGap = gap;
                            bestCost = cost;
                        }
                        // 合法（≥slotGap）里取位移最小者：保持原本的布局方位不乱跳。
                        if (gap >= CFG.SLOT_GAP - 1e-9 && (!hasLegal || cost < legalCost)) {
                            legalX = px;
                            legalZ = pz;
                            legalCost = cost;
                            hasLegal = true;
                        }
                    }
                }
                if (hasLegal) {
                    x = legalX;
                    z = legalZ;
                } else {
                    x = bestX;
                    z = bestZ;
                }
            }
            out.add(new Slot(new Vec3(x, 0, z), wrap360(Math.atan2(x, -z) / DEG)));
        }
        return Collections.unmodifiableList(out);
    }

    /** 所有槽位（只读，32 个）。 */
    public static List<Slot> slots() {
        return SLOTS;
    }

    /** 槽位 idx（越界/负数按 32 取模回绕，移植自 scene3d.js slotAt()）。 */
    public static Slot slotAt(int idx) {
        return SLOTS.get(((idx % SLOT_COUNT) + SLOT_COUNT) % SLOT_COUNT);
    }

    // =====================================================================
    // 七、房间布局（移植自 scene3d.js roomLayout()）
    // =====================================================================

    /** 一台节点的布局结果。 */
    public static final class Placement {
        /** 世界坐标（米，y=0）。 */
        public final Vec3 pos;
        /** 槽位下标（未分配/被 pin 时为 -1）。 */
        public final int slot;
        /** 是否被用户 pin 在任意位置。 */
        public final boolean pinned;
        /** 方位角（度，0..360，0=正前）。 */
        public final double azDeg;

        Placement(Vec3 pos, int slot, boolean pinned, double azDeg) {
            this.pos = pos;
            this.slot = slot;
            this.pinned = pinned;
            this.azDeg = azDeg;
        }

        public String toString() {
            return String.format(Locale.US, "Placement{%s, slot=%d, pinned=%s, az=%.6f}",
                    pos, slot, pinned, azDeg);
        }
    }

    /**
     * 房间布局（逐行移植 scene3d.js roomLayout()）：
     * <ul>
     *   <li>有合法 pin（长度 3 且全有限）→ 用 pin 的 (x,0,z)，slot=-1、pinned=true；</li>
     *   <li>否则查 slots[id]（必须是整数）→ 槽位；没有则按列表下标 i 兜底；</li>
     *   <li>azDeg = wrap360(atan2(x, -z)/DEG)（0=正前 -Z，+90=右耳）。</li>
     * </ul>
     *
     * <p>ids 必须按 id 升序（PC 端 setGraph 保证；顺序只影响“没有槽位记录时”的下标兜底）。
     *
     * @param ids    非 hub 节点 id（升序）
     * @param pins   id → {x, y, z}（米）
     * @param slots  id → 槽位下标
     * @return LinkedHashMap，迭代顺序与 ids 一致
     */
    public static Map<String, Placement> roomLayout(List<String> ids,
                                                    Map<String, double[]> pins,
                                                    Map<String, Integer> slots) {
        final Map<String, Placement> out = new LinkedHashMap<String, Placement>();
        if (ids == null) {
            return out;
        }
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            if (id == null) {
                continue;
            }
            final double[] pin = pins == null ? null : pins.get(id);
            if (pin != null && pin.length == 3
                    && isFinite(pin[0]) && isFinite(pin[1]) && isFinite(pin[2])) {
                final double x = pin[0];
                final double z = pin[2];
                out.put(id, new Placement(
                        new Vec3(x, 0, z), -1, true, wrap360(Math.atan2(x, -z) / DEG)));
                continue;
            }
            final Integer s = slots == null ? null : slots.get(id);
            final int idx = s == null ? -1 : s.intValue();
            final Slot sl = slotAt(idx >= 0 ? idx : i);
            out.put(id, new Placement(sl.pos, idx, false, sl.azDeg));
        }
        return out;
    }

    // =====================================================================
    // 八、听者位姿与方位角（移植自 scene3d.js listenerOf/azOf）
    // =====================================================================

    /** 听者（v1.3：固定在原点、yaw=0 面朝 -Z、pitch=0）。 */
    public static final class Listener {
        public final Vec3 pos;
        /** 水平朝向单位向量（yaw=0 → (0,0,-1)）。 */
        public final Vec3 fwdH;
        /** 水平右向单位向量（yaw=0 → (1,0,0)）。 */
        public final Vec3 rightH;
        public final double yawDeg;
        public final double pitchDeg;

        Listener(Vec3 pos, Vec3 fwdH, Vec3 rightH, double yawDeg, double pitchDeg) {
            this.pos = pos;
            this.fwdH = fwdH;
            this.rightH = rightH;
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
        }
    }

    /**
     * 听者位姿（移植自 scene3d.js listenerOf()）：物理房间模型下听者固定在房间中心
     * (0,0,0)、面朝 -Z，yaw=0、pitch=0。参数 view 仅为与 PC 函数签名一致而保留：
     * 相机 orbit 只改视角，不改听者朝向。
     */
    public static Listener listenerOf(Camera view) {
        final double a = CFG.LISTENER_YAW_DEG * DEG;
        final Vec3 fwdH = new Vec3(-Math.sin(a), 0, -Math.cos(a));
        final Vec3 rightH = Vec3.norm(Vec3.cross(fwdH, new Vec3(0, 1, 0)));
        return new Listener(new Vec3(0, 0, 0), fwdH, rightH,
                CFG.LISTENER_YAW_DEG, 0);
    }

    /** 方位角 / 距离。 */
    public static final class Az {
        /** 方位角（度，0=正前，+90=右耳，-90=左耳，±180=身后）。 */
        public final double azDeg;
        /** 到原点的欧氏距离（m，音量衰减用）。 */
        public final double dist;

        Az(double azDeg, double dist) {
            this.azDeg = azDeg;
            this.dist = dist;
        }

        public String toString() {
            return String.format(Locale.US, "Az{az=%.6f, dist=%.6f}", azDeg, dist);
        }
    }

    /**
     * 相对听者方位角（移植自 scene3d.js azOf()）：az = atan2(dot(rel,rightH), dot(rel,fwdH))；
     * dist = |p|（相对原点，与 PC 端一致）。
     */
    public static Az azOf(Listener listener, Vec3 p) {
        final Vec3 rel = new Vec3(
                p.x - listener.pos.x, 0, p.z - listener.pos.z);
        return new Az(
                Math.atan2(Vec3.dot(rel, listener.rightH), Vec3.dot(rel, listener.fwdH)) / DEG,
                Math.hypot(Math.hypot(p.x, p.y), p.z));
    }

    // =====================================================================
    // 九、球半径（逐行移植 scene3d.js projectAll() 的尺寸计算）
    // =====================================================================

    /** 汇聚端球半径（px）：clamp(0.23 * ppu, 26, 64)。 */
    public static double hubRadiusPx(double ppu) {
        return clamp(CFG.HUB_BALL_R * ppu, CFG.HUB_R_MIN, CFG.HUB_R_MAX);
    }

    /**
     * 近大远小系数：clamp((1.0/max(dist,0.2))^0.55, 0.55, 1.5)。
     * 距离 1 m 时 = 1，越远越小 —— 与音量衰减同向。
     */
    public static double nodeSizeScale(double dist) {
        return clamp(
                Math.pow(CFG.SIZE_REF / Math.max(dist, 0.2), CFG.SIZE_POW),
                CFG.SIZE_MIN, CFG.SIZE_MAX);
    }

    /** 普通节点球半径（px）：clamp(0.145 * ppu * sizeScale, 12, 40)。 */
    public static double nodeRadiusPx(double ppu, double dist) {
        return clamp(CFG.NODE_BALL_R * ppu * nodeSizeScale(dist),
                CFG.NODE_R_MIN, CFG.NODE_R_MAX);
    }

    // =====================================================================
    // 十、持久化槽位分配（逐行移植 scene3d.js setGraph() 的槽位维护段）
    // =====================================================================

    /**
     * 维护持久化槽位表（与 PC 端 setGraph() 同规则，原地修改 slots）：
     * <ol>
     *   <li>先剪掉已不在图中的 id（否则 32 个槽位会被历史设备耗尽）；</li>
     *   <li>再丢弃非法/越界槽号（合法范围 0..31 的整数）；</li>
     *   <li>对按 id 升序的节点，尚未分配槽位的取当前最小空闲槽 0..31
     *       → 同一批节点每次启动都落到同一位置（确定性），除非用户拖动 pin。</li>
     * </ol>
     *
     * <p>注意与 PC 端一致：槽号重复不会被去重（正常流程不会产生重复）。
     *
     * @param presentIds 当前图中的非 hub 节点 id（顺序无关，内部会用升序的传入顺序）
     * @param orderedIds 按 id 升序的节点 id（决定分配先后；传 null 则不分配新槽）
     * @param slots      id → 槽位下标，原地修改（必须是可修改 Map）
     * @return true = 表被修改，调用方应落盘
     */
    public static boolean allocateSlots(Collection<String> presentIds,
                                        List<String> orderedIds,
                                        Map<String, Integer> slots) {
        if (slots == null) {
            return false;
        }
        boolean dirty = false;
        final Set<String> present = new LinkedHashSet<String>();
        if (presentIds != null) {
            for (String id : presentIds) {
                if (id != null) {
                    present.add(id);
                }
            }
        }
        // 1) 剪枝：已不在图中的设备
        final Iterator<Map.Entry<String, Integer>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Integer> e = it.next();
            if (!present.contains(e.getKey())) {
                it.remove();
                dirty = true;
            }
        }
        // 2) 校验：非法值删除；合法值进 used
        final Set<Integer> used = new HashSet<Integer>();
        final Iterator<Map.Entry<String, Integer>> it2 = slots.entrySet().iterator();
        while (it2.hasNext()) {
            final Map.Entry<String, Integer> e = it2.next();
            final Integer v = e.getValue();
            if (v == null || v.intValue() < 0 || v.intValue() >= SLOT_COUNT) {
                it2.remove();
                dirty = true;
            } else {
                used.add(v);
            }
        }
        // 3) 新设备按 id 升序取最小空闲槽
        if (orderedIds != null) {
            for (int i = 0; i < orderedIds.size(); i++) {
                final String id = orderedIds.get(i);
                if (id == null || slots.containsKey(id)) {
                    continue;
                }
                int free = -1;
                for (int k = 0; k < SLOT_COUNT; k++) {
                    if (!used.contains(k)) {
                        free = k;
                        break;
                    }
                }
                if (free < 0) {
                    slots.put(id, Integer.valueOf(-1)); // 槽位耗尽：与 PC 端一致写 -1
                    dirty = true;
                } else {
                    used.add(free);
                    slots.put(id, Integer.valueOf(free));
                    dirty = true;
                }
            }
        }
        return dirty;
    }

    // =====================================================================
    // 十一、文本/绘制辅助（逐行对应 scene3d.js 的 levelPct/dbText/shortName/clampText）
    // =====================================================================

    /** 电平百分比（0..100）：clamp((level+60)/0.6, 0, 100)。 */
    public static double levelPct(double level) {
        return clamp((level + 60) / 0.6, 0, 100);
    }

    /** 电平文本：非有限 → "—"；≤-119 → "静音"；否则 "x.x dBFS"。 */
    public static String dbText(double level) {
        if (!isFinite(level)) {
            return "—";
        }
        if (level <= -119) {
            return "静音";
        }
        return String.format(Locale.US, "%.1f dBFS", level);
    }

    /** 截断到 max 个 Unicode 码点，超出加 "…"（Array.from 等价）。 */
    public static String shortName(String name, int max) {
        final String s = name == null ? "" : name;
        final int cp = s.codePointCount(0, s.length());
        if (cp <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max)) + "…";
    }

    /** 同 shortName，语义命名别名（scene3d.js clampText）。 */
    public static String clampText(String text, int max) {
        return shortName(text, max);
    }

    // =====================================================================
    // 十二、数值工具（对应 scene3d.js clamp/wrap360/wrap180/r3）
    // =====================================================================

    /** 夹取到 [lo, hi]。 */
    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 角度归一化到 [0, 360)。 */
    public static double wrap360(double deg) {
        return ((deg % 360.0) + 360.0) % 360.0;
    }

    /** 角度归一化到 (-180, 180]（scene3d.js：先 wrap360，>180 再 -360）。 */
    public static double wrap180(double deg) {
        final double a = wrap360(deg);
        return a > 180 ? a - 360 : a;
    }

    /** 保留 3 位小数（持久化落盘用，与 PC 端 r3 一致）。 */
    public static double round3(double v) {
        if (!isFinite(v)) {
            return 0;
        }
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 有限数判定（Number.isFinite 的等价物）。 */
    public static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}