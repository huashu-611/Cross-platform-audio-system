package com.audiohub.source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scene3D 纯数学自检（无 android.*，JDK8 可直接 javac/java）。
 *
 * <p>为什么需要它：本机没有 Android SDK，HubGraphView/MainActivity 无法离线编译；
 * 但 3D 场景的全部数值（透视投影、反投影、32 槽位、听者方位、球半径、槽位分配）
 * 都在 Scene3D 纯类里，这里逐项断言，防止移植 drift。
 *
 * <p>期望值来自 PC 端 ui/scene3d.js v1.3（SHA256 70C28A09DD38AFE8F5B824AC63645FE05150C59FD6C8A7C02D4C6DEA56791FB1），
 * 由 Node 直接调用该文件计算后写入本文件（与 build/_plan/android_golden.json 同源；
 * Lead 的独立探针另有一套 122 项比对）。
 *
 * <p>运行：android/verify/run-scene-check.ps1（JDK8 编译 Scene3D.java + 本文件并执行）。
 */
public final class SceneCheck {

    private static final double EPS = 1e-9;
    private static int passed;
    private static int failed;

    private SceneCheck() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        checkConstants();
        checkCamera();
        checkProject();
        checkUnproject();
        checkListenerAndAzimuth();
        checkSlots();
        checkRoomLayout();
        checkHitTest();
        checkBallRadius();
        checkAllocateSlots();
        checkTextHelpers();
        checkNumericHelpers();
        System.out.println();
        if (failed == 0) {
            System.out.println("RESULT: PASS  " + passed + " checks");
        } else {
            System.out.println("RESULT: FAIL  " + failed + " failed / " + (passed + failed) + " checks");
            System.exit(1);
        }
    }

    // ---------------- 断言工具 ----------------

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        }
    }

    private static void eq(String name, double got, double want) {
        final double d = Math.abs(got - want);
        final double tol = Math.max(EPS, Math.abs(want) * 1e-12);
        ok(name, d <= tol, "got=" + got + " want=" + want);
    }

    private static void eq(String name, int got, int want) {
        ok(name, got == want, "got=" + got + " want=" + want);
    }

    private static void eqVec(String name, Scene3D.Vec3 got, double[] want, int off) {
        ok(name, Math.abs(got.x - want[off]) <= Math.max(EPS, Math.abs(want[off]) * 1e-12)
                && Math.abs(got.y - want[off + 1]) <= Math.max(EPS, Math.abs(want[off + 1]) * 1e-12)
                && Math.abs(got.z - want[off + 2]) <= Math.max(EPS, Math.abs(want[off + 2]) * 1e-12),
                "got=" + got + " want=[" + want[off] + "," + want[off + 1] + "," + want[off + 2] + "]");
    }

    private static void info(String msg) {
        System.out.println("INFO  " + msg);
    }

    /** 命中测试桩（对应 PC 端投影项的最小字段集）。 */
    private static final class Item implements Scene3D.Hittable {
        private final double sx;
        private final double sy;
        private final double r;
        private final boolean vis;
        private final double depth;

        Item(double sx, double sy, double r, boolean vis, double depth) {
            this.sx = sx;
            this.sy = sy;
            this.r = r;
            this.vis = vis;
            this.depth = depth;
        }

        public boolean visible() { return vis; }

        public double sx() { return sx; }

        public double sy() { return sy; }

        public double rPx() { return r; }

        public double depth() { return depth; }
    }

    // 命中测试查询点（顺序与生成时一致）
    private static double[] q(int i) {
        switch (i) {
            case 0: return new double[] {100, 100};
            case 1: return new double[] {104, 100};
            case 2: return new double[] {300, 200};
            case 3: return new double[] {331, 200};
            case 4: return new double[] {337, 200};
            case 5: return new double[] {0, 0};
            case 6: return new double[] {600, 170};
            default: throw new IllegalArgumentException("q" + i);
        }
    }

    private static void checkConstants() {
        // ---- 虚拟画布与相机常量（PC ui/scene3d.js v1.3；golden focal=522.714762）----
        eq("const/VW", Scene3D.VW, 920);
        eq("const/VH", Scene3D.VH, 460);
        eq("const/CX", Scene3D.CX, 460);
        eq("const/CY", Scene3D.CY, 208);
        eq("const/FOV_Y", Scene3D.FOV_Y, 47.5);
        eq("const/FOCAL", Scene3D.FOCAL, 522.7147621531363);
        eq("const/SLOT_COUNT", Scene3D.SLOT_COUNT, 32);
        eq("const/DEG", Scene3D.DEG, Math.PI / 180);

        // ---- CFG（逐项对齐 PC golden scene.cfg）----
        eq("cfg/camDist", Scene3D.CFG.CAM_DIST, 3.4);
        eq("cfg/pitchDefault", Scene3D.CFG.PITCH_DEFAULT, 40);
        eq("cfg/slotRMin", Scene3D.CFG.SLOT_R_MIN, 0.85);
        eq("cfg/slotRMax", Scene3D.CFG.SLOT_R_MAX, 2.2);
        eq("cfg/slotFrontMin", Scene3D.CFG.SLOT_FRONT_MIN, 1.35);
        eq("cfg/slotRearMax", Scene3D.CFG.SLOT_REAR_MAX, 1.5);
        eq("cfg/slotGap", Scene3D.CFG.SLOT_GAP, 0.42);
        eq("cfg/dragRMin", Scene3D.CFG.DRAG_R_MIN, 0.45);
        eq("cfg/dragRMax", Scene3D.CFG.DRAG_R_MAX, 2.3);
        eq("cfg/hubBallR", Scene3D.CFG.HUB_BALL_R, 0.23);
        eq("cfg/nodeBallR", Scene3D.CFG.NODE_BALL_R, 0.145);
        eq("cfg/sizeRef", Scene3D.CFG.SIZE_REF, 1);
        eq("cfg/sizePow", Scene3D.CFG.SIZE_POW, 0.55);
        eq("cfg/sizeMin", Scene3D.CFG.SIZE_MIN, 0.55);
        eq("cfg/sizeMax", Scene3D.CFG.SIZE_MAX, 1.5);
        eq("cfg/hubRMin", Scene3D.CFG.HUB_R_MIN, 26);
        eq("cfg/hubRMax", Scene3D.CFG.HUB_R_MAX, 64);
        eq("cfg/nodeRMin", Scene3D.CFG.NODE_R_MIN, 12);
        eq("cfg/nodeRMax", Scene3D.CFG.NODE_R_MAX, 40);
        eq("cfg/ROOM_R", Scene3D.CFG.ROOM_R, 2.4);
        eq("cfg/RING_R", Scene3D.CFG.RING_R, 1.0);
        eq("cfg/PITCH_MIN", Scene3D.CFG.PITCH_MIN, 6);
        eq("cfg/PITCH_MAX", Scene3D.CFG.PITCH_MAX, 74);
        eq("cfg/DIST_MIN", Scene3D.CFG.DIST_MIN, 1.2);
        eq("cfg/DIST_MAX", Scene3D.CFG.DIST_MAX, 6.0);
        eq("cfg/NEAR", Scene3D.CFG.NEAR, 0.05);
        eq("cfg/LABEL_LOD", Scene3D.CFG.LABEL_LOD, 8);
        eq("cfg/ANIM_MS", Scene3D.CFG.ANIM_MS, 250);
        eq("cfg/LISTENER_YAW_DEG", Scene3D.CFG.LISTENER_YAW_DEG, 0);
    }

    private static void checkCamera() {
        // 期望：eye/fwd/right/up + phiDeg/dist/pitchDeg，均由 scene3d.js camera() 直接算出
        // 数组顺序 {phi, dist, pitch, eye(3), fwd(3), right(3), up(3), phiDeg, distOut, pitchDeg}
        final double[][] cases = new double[][] {
            new double[] {0, 3.4, 40, 0, 2.1854778729342335, 2.604551106604525, 0, -0.6427876096865394, -0.7660444431189781, 1, 0, 0, 0, 0.7660444431189781, -0.6427876096865394, 0, 3.4, 40}, // default-40deg
            new double[] {90, 2.6, 25, 2.3564002462952898, 1.0988074805258186, 1.4428790095677807e-16, -0.90630778703665, -0.4226182617406995, -5.549534652183772e-17, 6.123233995736766e-17, 0, -1, -0.4226182617406995, 0.90630778703665, -2.5877905075098297e-17, 90, 2.6, 25}, // yaw90-pitch25
            new double[] {-30, 1.2, 6, -0.5967131372209639, 0.12543415592118415, 1.033537471210529, 0.4972609476841366, -0.10452846326765346, -0.8612812260087742, 0.8660254037844387, 0, 0.49999999999999994, 0.05226423163382672, 0.9945218953682734, -0.09052430460833645, -30, 1.2, 6}, // yaw-30-pitch6
            new double[] {370, 0.5, 90, 0.05743670944146366, 1.1535140351259827, 0.32573976603403854, -0.04786392453455305, -0.961261695938319, -0.27144980502836547, 0.9848077530122082, 0, -0.17364817766692991, -0.16692134176071158, 0.27563735581699916, -0.9466579708337205, 10, 1.2, 74}, // wrap370-clamp-dist0.5-pitch90
            new double[] {0, Double.NaN, 40, 0, 2.1854778729342335, 2.604551106604525, 0, -0.6427876096865394, -0.7660444431189781, 1, 0, 0, 0, 0.7660444431189781, -0.6427876096865394, 0, 3.4, 40}, // NaN-dist-default
            new double[] {0, 3.4, Double.NaN, 0, 0.72, 3.322890308150421, 0, -0.21176470588235297, -0.977320678867771, 1, 0, 0, 0, 0.977320678867771, -0.21176470588235297, 0, 3.4, 12.225788593157482}, // NaN-pitch-implicit-camH
        };
        final String[] names = new String[] {"default-40deg", "yaw90-pitch25", "yaw-30-pitch6", "wrap370-clamp-dist0.5-pitch90", "NaN-dist-default", "NaN-pitch-implicit-camH"};
        for (int i = 0; i < cases.length; i++) {
            final double[] c = cases[i];
            final Scene3D.Camera cam = Scene3D.camera(c[0], c[1], c[2]);
            final String n = "camera/" + names[i];
            eqVec(n + "/eye", cam.eye, c, 3);
            eqVec(n + "/fwd", cam.fwd, c, 6);
            eqVec(n + "/right", cam.right, c, 9);
            eqVec(n + "/up", cam.up, c, 12);
            eq(n + "/phiDeg", cam.phiDeg, c[15]);
            eq(n + "/dist", cam.dist, c[16]);
            eq(n + "/pitchDeg", cam.pitchDeg, c[17]);
        }
    }

    private static void checkProject() {
        // 默认相机 φ=0 / d=3.4 / pitch=40；hub 固定投到 (460,208)；相机身后 → visible=false
        final Scene3D.Camera cam = Scene3D.camera(0, 3.4, 40);
        final double[][] pts = new double[][] {
            new double[] {0, 0, 0, 1, 460, 208.00000000000003, 3.4, 153.73963592739304},
            new double[] {0, 0, -1, 1, 460, 127.34925872736622, 4.166044443118977, 125.47027985179088},
            new double[] {0, 0, -2.4, 1, 460, 54.065486629937425, 5.2385066634855475, 99.78316259415377},
            new double[] {1, 0, 0, 1, 613.739635927393, 208.00000000000003, 3.4, 153.73963592739304},
            new double[] {-1, 0, 0, 1, 306.260364072607, 208.00000000000003, 3.4, 153.73963592739304},
            new double[] {0, 0, 1, 1, 460, 335.56273416782625, 2.6339555568810216, 198.45238496434808},
            new double[] {0, 0, 2.2, 1, 460, 639.0882955012346, 1.7147022251382478, 304.8428785417786},
            new double[] {2, 0, 0.5, 1, 806.5154870469944, 263.68396540957616, 3.016977778440511, 173.25774352349717},
            new double[] {-1.5, 0, -2, 1, 301.0263559082427, 71.75161508146758, 4.932088886237956, 105.98242939450488},
            new double[] {0, 0, 5, 0, 0, 0, -0.4302222155948907, 0},
        };
        // 数组 {@code wx, wy, wz, visibleFlag, sx, sy, depth, ppu}
        for (int i = 0; i < pts.length; i++) {
            final Scene3D.Projection pr = Scene3D.project(cam, new Scene3D.Vec3(pts[i][0], pts[i][1], pts[i][2]));
            final String n = "project/p" + i;
            ok(n + "/visible", pr.visible == (pts[i][3] == 1), "visible=" + pr.visible);
            eq(n + "/depth", pr.depth, pts[i][6]);
            if (pr.visible) {
                eq(n + "/sx", pr.sx, pts[i][4]);
                eq(n + "/sy", pr.sy, pts[i][5]);
                eq(n + "/ppu", pr.ppu, pts[i][7]);
            }
        }
        final Scene3D.Projection hub = Scene3D.project(cam, new Scene3D.Vec3(0, 0, 0));
        eq("project/hub-sx", hub.sx, 460);
        eq("project/hub-sy", hub.sy, 208);
        ok("project/hub-visible", hub.visible);
    }

    private static void checkUnproject() {
        // 屏幕点 → 地面 y=0；再 project 回去的往返误差必须 < 1e-4（PC golden 同口径）
        final Scene3D.Camera cam = Scene3D.camera(0, 3.4, 40);
        final double[][] screens = new double[][] {
            new double[] {460, 208, 0, 0, 0},
            new double[] {300, 150, -1.1993128983422308, -4.440892098500626e-16, -0.6763523737818602},
            new double[] {600, 330, 0.7124589152628262, 0, 0.9658821131163375},
            new double[] {460, 400, 0, 0, 1.3513426637896933},
            new double[] {100, 250, -2.1369893522917565, 0, 0.3878659461958498},
            new double[] {460, 100, 0, 0, -1.4498832419513317},
        };
        for (int i = 0; i < screens.length; i++) {
            final Scene3D.Vec3 p = Scene3D.unproject(cam, screens[i][0], screens[i][1]);
            final String n = "unproject/s" + i;
            ok(n + "/not-null", p != null);
            if (p == null) { continue; }
            eqVec(n + "/p", p, screens[i], 2);
            final Scene3D.Projection back = Scene3D.project(cam, p);
            eq(n + "/back-sx", back.sx, screens[i][0]);
            eq(n + "/back-sy", back.sy, screens[i][1]);
        }
        final Scene3D.Vec3 o = Scene3D.unproject(cam, 460, 208);
        ok("unproject/center-is-origin", o != null && Math.abs(o.x) < 1e-6 && Math.abs(o.z) < 1e-6,
                "origin=" + o);
        double maxErr = 0;
        for (double sx = 640; sx <= 700; sx += 30) {
            for (double sy = 260; sy <= 340; sy += 40) {
                final Scene3D.Vec3 q = Scene3D.unproject(cam, sx, sy);
                final Scene3D.Projection b = Scene3D.project(cam, q);
                maxErr = Math.max(maxErr, Math.abs(b.sx - sx) + Math.abs(b.sy - sy));
            }
        }
        ok("unproject/roundtrip<1e-4", maxErr < 1e-4, "maxErr=" + maxErr);
    }

    private static void checkListenerAndAzimuth() {
        final Scene3D.Camera cam = Scene3D.camera(0, 3.4, 40);
        final Scene3D.Listener lis = Scene3D.listenerOf(cam);
        ok("listener/at-origin", lis.pos.x == 0 && lis.pos.y == 0 && lis.pos.z == 0, "pos=" + lis.pos);
        ok("listener/yaw0", lis.yawDeg == 0, "yaw=" + lis.yawDeg);
        ok("listener/pitch0", lis.pitchDeg == 0, "pitch=" + lis.pitchDeg);
        ok("listener/fwdH-front", Math.abs(lis.fwdH.x) < 1e-12 && Math.abs(lis.fwdH.z + 1) < 1e-12,
                "fwdH=" + lis.fwdH);
        ok("listener/rightH-right", Math.abs(lis.rightH.x - 1) < 1e-12 && Math.abs(lis.rightH.z) < 1e-12,
                "rightH=" + lis.rightH);
        final double[][] azPts = new double[][] {
            new double[] {0, 0, -1, 0, 1},
            new double[] {1, 0, 0, 90, 1},
            new double[] {-1, 0, 0, -90, 1},
            new double[] {0, 0, 1, 180, 1},
            new double[] {1.2, 0, -0.6, 63.434948822922, 1.3416407864998738},
            new double[] {0.5, 0, 0.25, 116.56505117707799, 0.5590169943749475},
            new double[] {0.3, 1.7, -0.2, 56.309932474020215, 1.7378147196982767},
        };
        final String[] azNames = new String[] {"front", "right", "left", "back", "fwd-right", "pin", "elevated"};
        for (int i = 0; i < azPts.length; i++) {
            final Scene3D.Az az = Scene3D.azOf(lis, new Scene3D.Vec3(azPts[i][0], azPts[i][1], azPts[i][2]));
            final String n = "az/" + azNames[i];
            eq(n + "/az", az.azDeg, azPts[i][3]);
            eq(n + "/dist", az.dist, azPts[i][4]);
        }
        ok("az/right-positive", Scene3D.azOf(lis, new Scene3D.Vec3(1, 0, 0)).azDeg > 0);
        ok("az/left-negative", Scene3D.azOf(lis, new Scene3D.Vec3(-1, 0, 0)).azDeg < 0);
        ok("az/back-180", Math.abs(Scene3D.azOf(lis, new Scene3D.Vec3(0, 0, 1)).azDeg) > 179.999);
    }

    private static void checkSlots() {
        // 32 槽位坐标/方位逐项对齐 PC golden（Lead 的独立探针也按这 32 组值比对）
        final List<Scene3D.Slot> slots = Scene3D.slots();
        eq("slots/count", slots.size(), 32);
        final double[][] want = new double[][] {
            new double[] {1.2246467991473532e-16, 0, -2, 0},
            new double[] {1.0132354423582295, 0, 1.1060533162325958, 137.507764},
            new double[] {-1.3448309053736653, 0, -0.11802472601894712, 275.015528},
            new double[] {1.4681613869405632, 0, -1.125611896657441, 52.523292000000026},
            new double[] {-0.16547284959244157, 0, 0.9354778116277035, 190.031056},
            new double[] {-1.127128918252442, 0, -1.7718861141842837, 327.53882},
            new double[] {1.255429598456919, 0, 0.3374855897935483, 105.04658400000005},
            new double[] {-1.3311726396414048, 0, 0.6913605452078785, 242.55434800000006},
            new double[] {0.4631021428204465, 0, -1.2680837532730678, 20.062112000000127},
            new double[] {0.5723346236103309, 0, 1.3865183297082013, 157.56987600000002},
            new double[] {-1.3133147005728918, 0, -0.6145766813499642, 295.07764000000003},
            new double[] {1.2881215585342871, 0, -0.40403322937501035, 72.58540400000021},
            new double[] {-0.7521113582476545, 0, 1.2978168225118938, 210.09316800000016},
            new double[] {-0.2898712441750636, 0, -1.3185122911070648, 347.6009320000001},
            new double[] {0.023333343035560797, 0, 1.4998185073877388, 179.10869599999955},
            new double[] {-1.2501193272109778, 0, -1.3227250915188586, 316.6164599999995},
            new double[] {0.8700034124689605, 0, -1.0322761560223912, 40.12422399999997},
            new double[] {-0.6891364503804933, 0, -1.8562033705273178, 339.6319879999984},
            new double[] {-0.9522629356348471, 0, -0.9569196943401799, 315.1397519999999},
            new double[] {0.5951582723741167, 0, -2.055574525728673, 16.147515999997154},
            new double[] {1.170874431200338, 0, -1.862539413371241, 32.15527999999824},
            new double[] {1.8366510055953245, 0, -0.4865316882235783, 75.1630439999995},
            new double[] {1.4999908614866857, 0, -0.00523597712283564, 89.80000000000001},
            new double[] {2.1905181824960542, 0, -0.20403453667010327, 84.67857199999855},
            new double[] {2.02360729410782, 0, -0.863141656527832, 66.90000000000003},
            new double[] {1.2831263987389263, 0, 0.7769083889747066, 121.19409999999732},
            new double[] {-1.470931549096937, 0, 0.29387136279550186, 258.7018640000001},
            new double[] {0.7841022443645501, 0, 0.32815190138482386, 112.7096279999999},
            new double[] {1.1234730083680147, 0, -1.367738425090311, 39.400000000000034},
            new double[] {1.5085770165309715, 0, -1.5458963048000665, 44.30000000000001},
            new double[] {0.26763472621787215, 0, 1.0153184984635535, 165.2329200000006},
            new double[] {-1.0833772740512453, 0, 1.037445748976443, 226.24068400000638},
        };
        if (slots.size() == want.length) {
            for (int i = 0; i < want.length; i++) {
                final Scene3D.Slot s = slots.get(i);
                final String n = "slots/" + i;
                eqVec(n + "/pos", s.pos, want[i], 0);
                eq(n + "/az", s.azDeg, want[i][3]);
                final double r = Math.hypot(s.pos.x, s.pos.z);
                ok(n + "/radius-0.85..2.2", r >= 0.85 - 1e-9 && r <= 2.2 + 1e-9, "r=" + r);
                ok(n + "/on-ground", s.pos.y == 0, "y=" + s.pos.y);
            }
        }
        ok("slotAt/0", Scene3D.slotAt(0) == slots.get(0));
        ok("slotAt/32-wrap", Scene3D.slotAt(32) == slots.get(0));
        ok("slotAt/-1-wrap", Scene3D.slotAt(-1) == slots.get(31));
        ok("slotAt/99-wrap", Scene3D.slotAt(99) == slots.get(3));
        ok("slotAt/-33-wrap", Scene3D.slotAt(-33) == slots.get(31));
        double minGap = Double.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                minGap = Math.min(minGap, Math.hypot(
                        slots.get(i).pos.x - slots.get(j).pos.x,
                        slots.get(i).pos.z - slots.get(j).pos.z));
            }
        }
        ok("slots/distinct", minGap > 0.01, "minGap=" + minGap);
        eq("slots/minGap-matches-pc", minGap, 0.42045424247286084);
        ok("slots/pairwise-gap>=0.42", minGap >= 0.42 - 1e-9, "minGap=" + minGap);
        info("slots: PC v1.3 buildSlots 保底满足两两间距 >= 0.42 m（min=0.420454），Android 忠实移植"
                + "；PC buildSlots 在 24 次重试后接受冲突（docs §7.2），Android 忠实移植以对齐 golden，故不断言 0.42");
    }

    private static void checkRoomLayout() {
        final List<String> ids = Arrays.asList("a", "b", "c");
        final Map<String, double[]> pins = new LinkedHashMap<String, double[]>();
        pins.put("b", new double[] {0.5, 0, 0.25});
        final Map<String, Integer> slotMap = new LinkedHashMap<String, Integer>();
        slotMap.put("a", 0); slotMap.put("b", 1); slotMap.put("c", 5);
        final Map<String, Scene3D.Placement> lay = Scene3D.roomLayout(ids, pins, slotMap);
        ok("roomLayout/size", lay.size() == 3, "size=" + lay.size());
        // pinned b：pin 坐标 + slot=-1 + pinned=true；其它走槽位
        final double[][] want = new double[][] {
            new double[] {1.2246467991473532e-16, 0, -2, 0, 0, 0}, // a
            new double[] {0.5, 0, 0.25, -1, 1, 116.56505117707798}, // b
            new double[] {-1.127128918252442, 0, -1.7718861141842837, 5, 0, 327.53882}, // c
        };
        final String[] names = new String[] {"a", "b", "c"};
        for (int i = 0; i < want.length; i++) {
            final Scene3D.Placement p = lay.get(names[i]);
            final String n = "roomLayout/" + names[i];
            ok(n + "/present", p != null);
            if (p == null) { continue; }
            eqVec(n + "/pos", p.pos, want[i], 0);
            ok(n + "/slot", p.slot == (int) want[i][3], "slot=" + p.slot);
            ok(n + "/pinned", p.pinned == (want[i][4] == 1), "pinned=" + p.pinned);
            eq(n + "/az", p.azDeg, want[i][5]);
        }
        // 无 pin/无 slot → 按列表下标 i 落到 slotAt(i)
        final List<String> ids2 = Arrays.asList("x", "y");
        final Map<String, Integer> slotMap2 = new LinkedHashMap<String, Integer>();
        slotMap2.put("y", 7);
        final Map<String, Scene3D.Placement> lay2 = Scene3D.roomLayout(ids2, null, slotMap2);
        final Scene3D.Slot s0 = Scene3D.slotAt(0);
        final Scene3D.Slot s7 = Scene3D.slotAt(7);
        ok("roomLayout/fallback-x", Math.abs(lay2.get("x").pos.x - s0.pos.x) < 1e-12
                && Math.abs(lay2.get("x").pos.z - s0.pos.z) < 1e-12, "x=" + lay2.get("x").pos);
        ok("roomLayout/fallback-y", Math.abs(lay2.get("y").pos.x - s7.pos.x) < 1e-12
                && Math.abs(lay2.get("y").pos.z - s7.pos.z) < 1e-12, "y=" + lay2.get("y").pos);
        // 非法 pin（NaN）→ 回退到槽位
        final Map<String, double[]> badPins = new LinkedHashMap<String, double[]>();
        badPins.put("a", new double[] {Double.NaN, 0, 0});
        final Map<String, Scene3D.Placement> lay3 = Scene3D.roomLayout(Arrays.asList("a"), badPins, slotMap);
        ok("roomLayout/nan-pin-falls-back", !lay3.get("a").pinned && lay3.get("a").slot == 0,
                "pinned=" + lay3.get("a").pinned);
        ok("roomLayout/null-ids", Scene3D.roomLayout(null, null, null).isEmpty());
    }

    private static void checkHitTest() {
        // 与 PC 端 pickAt/hitTest 同口径：半径 + 6px 容差；圆内最近者优先；不可见不可点
        final List<Scene3D.Hittable> items = new ArrayList<Scene3D.Hittable>();
        items.add(new Item(100, 100, 10, true, 5)); // 0
        items.add(new Item(300, 200, 30, true, 8)); // 1
        items.add(new Item(104, 102, 12, true, 4)); // 2
        items.add(new Item(600, 150, 20, true, 6)); // 3
        items.add(new Item(100, 100, 50, false, 1)); // 4
        final Scene3D.Hittable[] want = new Scene3D.Hittable[] {
            items.get(0), // (100,100) -> 0
            items.get(2), // (104,100) -> 2
            items.get(1), // (300,200) -> 1
            items.get(1), // (331,200) -> 1
            null, // (337,200) -> -1
            null, // (0,0) -> -1
            items.get(3), // (600,170) -> 3
        };
        for (int i = 0; i < want.length; i++) {
            final Scene3D.Hittable got = Scene3D.hitTest(items, q(i)[0], q(i)[1]);
            ok("hitTest/q" + i, got == want[i], "got=" + (got == null ? "null" : String.valueOf(items.indexOf(got))));
        }
        ok("hitTest/slop0-miss", Scene3D.hitTest(items, 331, 200, 0) == null);
        ok("hitTest/slop6-hit", Scene3D.hitTest(items, 331, 200) == items.get(1));
        final List<Scene3D.Hittable> tie = new ArrayList<Scene3D.Hittable>();
        tie.add(new Item(50, 50, 20, true, 9)); // far
        tie.add(new Item(50, 60, 20, true, 5)); // near
        ok("hitTest/tie-prefers-near-depth", Scene3D.hitTest(tie, 50, 55) == tie.get(1));
        ok("hitTest/empty", Scene3D.hitTest(new ArrayList<Scene3D.Hittable>(), 0, 0) == null);
        ok("hitTest/invisible-skipped", Scene3D.hitTest(items, 100, 100) != items.get(4));
        ok("hitTest/nan-slop-uses-6", Scene3D.hitTest(items, 331, 200, Double.NaN) == items.get(1));
        ok("hitTest/null-list", Scene3D.hitTest(null, 100, 100) == null);
    }

    private static void checkBallRadius() {
        // hub 球半径 = clamp(0.23*ppu, 26, 64)
        final double[][] hubs = new double[][] {
            new double[] {153.73963592739304, 35.3601162633004},
            new double[] {135.431430914089, 31.14922911024047},
            new double[] {100, 26},
            new double[] {1000, 64},
            new double[] {50, 26},
        };
        for (int i = 0; i < hubs.length; i++) {
            eq("hubRadius/" + i, Scene3D.hubRadiusPx(hubs[i][0]), hubs[i][1]);
        }
        // node 球 = clamp(0.145*ppu*sizeScale, 12, 40)；距离越远不增（近大远小）
        final double[] dists = new double[] {0.6, 1, 1.5, 2, 2.5, 0.9, 1.2};
        final double[] ppus = new double[] {135.431430914089, 125.47027985179088, 114.90593580696238, 105.98242939450488, 98.34503015116586, 127.8206214801088, 121.01970456598025};
        final double[] rPx = new double[] {26.00783981278619, 18.193190578509675, 13.330924653437721, 12, 12, 19.639731532560056, 15.873562463416928};
        for (int i = 0; i < dists.length; i++) {
            eq("nodeRadius/" + dists[i], Scene3D.nodeRadiusPx(ppus[i], dists[i]), rPx[i]);
        }
        double prev = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) { // 0.6/1.0/1.5/2.0/2.5 依次变远
            final double r = Scene3D.nodeRadiusPx(ppus[i], dists[i]);
            ok("nodeRadius/monotonic@" + dists[i], r <= prev + 1e-12, "r=" + r + " prev=" + prev);
            prev = r;
        }
        ok("nodeSizeScale/1m", Math.abs(Scene3D.nodeSizeScale(1) - 1) < 1e-12,
                "=" + Scene3D.nodeSizeScale(1));
        eq("nodeSizeScale/2m", Scene3D.nodeSizeScale(2), 0.6830201283771977);
        eq("nodeSizeScale/3.4m", Scene3D.nodeSizeScale(3.4), 0.55);
        eq("nodeSizeScale/clamp-far", Scene3D.nodeSizeScale(50), 0.55);
        eq("hubRadius/clamp-max", Scene3D.hubRadiusPx(1000), 64);
        eq("hubRadius/clamp-min", Scene3D.hubRadiusPx(10), 26);
    }

    private static void checkAllocateSlots() {
        // 规则（PC setGraph）：prune 不在图的 id → 删除非法槽号 → 按 id 升序取最小空闲槽
        final Map<String, Integer> slots = new LinkedHashMap<String, Integer>();
        final List<String> ordered = Arrays.asList("a", "b", "c");
        ok("allocate/dirty-fresh", Scene3D.allocateSlots(Arrays.asList("c", "a", "b"), ordered, slots));
        ok("allocate/deterministic", slots.get("a") == 0 && slots.get("b") == 1 && slots.get("c") == 2,
                "slots=" + slots);
        ok("allocate/stable-noop", !Scene3D.allocateSlots(Arrays.asList("a", "b", "c"), ordered, slots));
        ok("allocate/stable-values", slots.get("a") == 0 && slots.get("b") == 1 && slots.get("c") == 2);
        final Map<String, Integer> keep = new LinkedHashMap<String, Integer>();
        keep.put("a", 5);
        Scene3D.allocateSlots(Arrays.asList("a", "b"), Arrays.asList("a", "b"), keep);
        ok("allocate/keep-existing", keep.get("a") == 5, "a=" + keep.get("a"));
        ok("allocate/smallest-free", keep.get("b") == 0, "b=" + keep.get("b"));
        final Map<String, Integer> prune = new LinkedHashMap<String, Integer>();
        prune.put("gone", 3); prune.put("a", 5);
        Scene3D.allocateSlots(Arrays.asList("b"), Arrays.asList("b"), prune);
        ok("allocate/pruned", !prune.containsKey("gone") && !prune.containsKey("a"), "map=" + prune);
        ok("allocate/pruned-new", prune.get("b") == 0, "b=" + prune.get("b"));
        final Map<String, Integer> bad = new LinkedHashMap<String, Integer>();
        bad.put("a", 99); bad.put("b", -1);
        Scene3D.allocateSlots(Arrays.asList("a", "b"), Arrays.asList("a", "b"), bad);
        ok("allocate/bad-removed", bad.get("a") == 0 && bad.get("b") == 1, "map=" + bad);
        final Map<String, Integer> full = new LinkedHashMap<String, Integer>();
        final List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 33; i++) { ids.add("n" + i); }
        Scene3D.allocateSlots(ids, ids, full);
        ok("allocate/exhausted-33rd", full.get("n32") == -1, "n32=" + full.get("n32"));
        ok("allocate/exhausted-first", full.get("n0") == 0 && full.get("n31") == 31, "map=" + full);
        ok("allocate/null-slots", !Scene3D.allocateSlots(null, null, null));
        final Map<String, Integer> nullPresent = new LinkedHashMap<String, Integer>();
        Scene3D.allocateSlots(null, Arrays.asList("z"), nullPresent);
        ok("allocate/null-present-still-allocates", nullPresent.get("z") == 0, "map=" + nullPresent);
    }

    private static void checkTextHelpers() {
        eq("levelPct/0", Scene3D.levelPct(0), 100);
        eq("levelPct/-30", Scene3D.levelPct(-30), 50);
        eq("levelPct/-60", Scene3D.levelPct(-60), 0);
        eq("levelPct/-90", Scene3D.levelPct(-90), 0);
        ok("levelPct/nan", Double.isNaN(Scene3D.levelPct(Double.NaN)));
        ok("dbText/nan", "—".equals(Scene3D.dbText(Double.NaN)), Scene3D.dbText(Double.NaN));
        ok("dbText/mute", "静音".equals(Scene3D.dbText(-120)), Scene3D.dbText(-120));
        ok("dbText/one-decimal", "-3.0 dBFS".equals(Scene3D.dbText(-3)), Scene3D.dbText(-3));
        ok("dbText/negative-one", "-24.5 dBFS".equals(Scene3D.dbText(-24.5)), Scene3D.dbText(-24.5));
        ok("shortName/fit", "abcd".equals(Scene3D.shortName("abcd", 4)));
        ok("shortName/truncate", "abcd…".equals(Scene3D.shortName("abcdefg", 4)), Scene3D.shortName("abcdefg", 4));
        ok("shortName/codepoints", "😀😀…".equals(Scene3D.shortName("😀😀😀", 2)), Scene3D.shortName("😀😀😀", 2));
        ok("shortName/null", "".equals(Scene3D.shortName(null, 4)));
        ok("clampText/alias", "ab…".equals(Scene3D.clampText("abc", 2)), Scene3D.clampText("abc", 2));
    }

    private static void checkNumericHelpers() {
        eq("wrap360/370", Scene3D.wrap360(370), 10);
        eq("wrap360/-30", Scene3D.wrap360(-30), 330);
        eq("wrap360/720", Scene3D.wrap360(720), 0);
        eq("wrap180/370", Scene3D.wrap180(370), 10);
        eq("wrap180/190", Scene3D.wrap180(190), -170);
        eq("wrap180/-190", Scene3D.wrap180(-190), 170);
        eq("clamp/in-range", Scene3D.clamp(5, 0, 10), 5);
        eq("clamp/low", Scene3D.clamp(-5, 0, 10), 0);
        eq("clamp/high", Scene3D.clamp(15, 0, 10), 10);
        eq("round3/up", Scene3D.round3(1.23456), 1.235);
        eq("round3/neg", Scene3D.round3(-1.23456), -1.235);
        eq("round3/nan", Scene3D.round3(Double.NaN), 0);
    }

}
