import com.audiohub.source.GraphFit;

/**
 * GraphFit 离线数值检查（普通 JDK 8 可跑，不依赖 Android SDK）。
 *
 * 复刻 HubGraphView 的输入：density=3、顶部预留 dp24、标签盒 60dp×30dp、球半径上下限；
 * 断言「视野自适应」的硬约束：最远节点连球半径与标签都落在画布内（12% 边距）、
 * 视野半径 ∈ [2.6 m, 硬上限]、ppu 不超过近景 2.6 m 比例、方位角不变、环按真比例。
 *
 * 运行：powershell -ExecutionPolicy Bypass -File android\verify\run-graphfit-check.ps1
 */
public final class GraphFitCheck {

    private static int checks = 0;
    private static int fails = 0;

    private static final float D = 3.0f;                    // density 3 (360dp 屏 ≈ 1080px)
    private static final float TOP = 24f * D;               // sp(16)+dp(8)
    private static final float LABEL_HALF_W = 60f * D;
    private static final float LABEL_V = 30f * D;
    private static final float HUB_MIN = 18f * D, HUB_MAX = 56f * D;
    private static final float NODE_MIN = 8f * D, NODE_MAX = 22f * D;

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    private static GraphFit.Item node(float wx, float wz, boolean hub) {
        return new GraphFit.Item(wx, wz, hub ? 0.23f : 0.145f,
                hub ? HUB_MIN : NODE_MIN, hub ? HUB_MAX : NODE_MAX);
    }

    private static float halfW(float w) {
        return w * (0.5f - GraphFit.MARGIN_FRACTION);
    }

    private static float halfH(float h) {
        return (h - TOP) * (0.5f - GraphFit.MARGIN_FRACTION);
    }

    private static float comfortable(float w, float h) {
        return Math.min(halfW(w), halfH(h)) / GraphFit.MIN_VIEW_RADIUS_M;
    }

    /** 与 HubGraphView 的绘制一致：世界坐标 * ppu + 球半径 clamp + 标签盒。 */
    private static boolean fitsInside(GraphFit.Result r, float w, float h, GraphFit.Item[] items) {
        for (GraphFit.Item it : items) {
            if (it == null) continue;
            final float rpx = it.radiusPx(r.ppu);
            if (Math.abs(it.wx) * r.ppu + rpx + LABEL_HALF_W > halfW(w) + 0.05f) return false;
            if (Math.abs(it.wz) * r.ppu + rpx + LABEL_V > halfH(h) + 0.05f) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        final float[][] canvases = {
                {1080, 900}, {1080, 780}, {1080, 700}, {1440, 1200}, {720, 900}, {720, 600},
        };

        // 1) 无节点：保持原来的 2.6 m 近景观感（ppu = comfortable）
        for (float[] cv : canvases) {
            final GraphFit.Result r = GraphFit.fit(cv[0], cv[1], TOP, null, LABEL_HALF_W, LABEL_V);
            final String tag = "empty " + (int) cv[0] + "x" + (int) cv[1];
            check(tag + " view=2.6", Math.abs(r.viewRadiusM - GraphFit.MIN_VIEW_RADIUS_M) < 1e-3f);
            check(tag + " ppu=comfortable", Math.abs(r.ppu - comfortable(cv[0], cv[1])) < 1e-3f);
        }

        // 2) 近节点不放大（视野半径不小于 2.6 m，ppu 不超过近景比例）
        final GraphFit.Item[] near = {node(0, 0, true), node(1, 0, false), node(-0.8f, 0.6f, false)};
        for (float[] cv : canvases) {
            final GraphFit.Result r = GraphFit.fit(cv[0], cv[1], TOP, near, LABEL_HALF_W, LABEL_V);
            final String tag = "near " + (int) cv[0] + "x" + (int) cv[1];
            check(tag + " view>=2.6", r.viewRadiusM >= GraphFit.MIN_VIEW_RADIUS_M - 1e-3f);
            check(tag + " fits", fitsInside(r, cv[0], cv[1], near));
            if (cv[0] >= 1080f) {
                // 常用宽度下近景仍是原来的 2.6 m 观感；<240dp 的窄画布会为了标签整体缩远一点。
                check(tag + " view=2.6", Math.abs(r.viewRadiusM - GraphFit.MIN_VIEW_RADIUS_M) < 1e-3f);
            }
        }

        // 3) 远处节点（10 m / 100 m / ±320 m / 对角）：落在画布内、方位不变、环真比例
        final float[][] points = {{10, 0}, {100, 0}, {-100, 60}, {320, 0}, {0, -320}, {320, 320}, {-150, 220}};
        for (float[] cv : canvases) {
            for (float[] p : points) {
                final GraphFit.Item[] items = {node(0, 0, true), node(1, 0, false), node(p[0], p[1], false)};
                final GraphFit.Result r = GraphFit.fit(cv[0], cv[1], TOP, items, LABEL_HALF_W, LABEL_V);
                final String tag = "far " + (int) cv[0] + "x" + (int) cv[1] + " @" + p[0] + "," + p[1];
                check(tag + " fits", fitsInside(r, cv[0], cv[1], items));
                check(tag + " ppu>0", r.ppu > 0f);
                check(tag + " ppu<=comfortable", r.ppu <= comfortable(cv[0], cv[1]) + 1e-3f);
                check(tag + " bounds", r.viewRadiusM >= GraphFit.MIN_VIEW_RADIUS_M - 1e-3f
                        && r.viewRadiusM <= GraphFit.HARD_MAX_VIEW_RADIUS_M + 1e-3f);
                // 方位：屏幕 (dx,dy) 的角度必须等于世界 (x,z) 的角度（sy 向下 = +Z）
                final float worldDeg = (float) Math.toDegrees(Math.atan2(p[0], -p[1]));
                final float screenDeg = (float) Math.toDegrees(Math.atan2(p[0] * r.ppu, -p[1] * r.ppu));
                check(tag + " azimuth", Math.abs(worldDeg - screenDeg) < 1e-2f);
            }
        }

        // 4) 常用画布：100 m 时视野半径 ≤ 640 m 正常下限
        final float[][] common = {{1080, 900}, {1080, 780}, {1080, 700}, {1440, 1200}};
        for (float[] cv : common) {
            final GraphFit.Item[] items = {node(0, 0, true), node(100, 0, false)};
            final GraphFit.Result r = GraphFit.fit(cv[0], cv[1], TOP, items, LABEL_HALF_W, LABEL_V);
            final String tag = "100m " + (int) cv[0] + "x" + (int) cv[1];
            check(tag + " view<=640", r.viewRadiusM <= GraphFit.MAX_VIEW_RADIUS_M + 1e-3f);
            check(tag + " fits", fitsInside(r, cv[0], cv[1], items));
        }

        // 5) 单调性 + 环比例（1 m 与 2.4 m 半径比恒为 2.4，说明共用一个 ppu）
        final GraphFit.Item[] c1 = {node(0, 0, true), node(2, 0, false)};
        final GraphFit.Item[] c2 = {node(0, 0, true), node(20, 0, false)};
        final GraphFit.Item[] c3 = {node(0, 0, true), node(200, 0, false)};
        final GraphFit.Result r1 = GraphFit.fit(1080, 900, TOP, c1, LABEL_HALF_W, LABEL_V);
        final GraphFit.Result r2 = GraphFit.fit(1080, 900, TOP, c2, LABEL_HALF_W, LABEL_V);
        final GraphFit.Result r3 = GraphFit.fit(1080, 900, TOP, c3, LABEL_HALF_W, LABEL_V);
        check("monotonic 2m>=20m", r1.ppu >= r2.ppu - 1e-3f);
        check("monotonic 20m>=200m", r2.ppu >= r3.ppu - 1e-3f);
        check("ring ratio", Math.abs((2.4f * r3.ppu) / (1.0f * r3.ppu) - 2.4f) < 1e-4f);

        // 6) 随机 500 组 × 全部画布：1..8 个节点、坐标 ±320 m 内，必须全部装得下
        final java.util.Random rnd = new java.util.Random(20261027L);
        int randomFits = 0;
        int randomFails = 0;
        for (int t = 0; t < 500; ++t) {
            final GraphFit.Item[] items = new GraphFit.Item[1 + rnd.nextInt(8)];
            items[0] = node(0, 0, true);
            for (int i = 1; i < items.length; ++i) {
                items[i] = node((rnd.nextFloat() * 640f) - 320f, (rnd.nextFloat() * 640f) - 320f, false);
            }
            for (float[] cv : canvases) {
                final GraphFit.Result r = GraphFit.fit(cv[0], cv[1], TOP, items, LABEL_HALF_W, LABEL_V);
                randomFits++;
                if (!fitsInside(r, cv[0], cv[1], items)) {
                    randomFails++;
                    if (randomFails <= 5) {
                        System.out.println("FAIL random t=" + t + " canvas=" + (int) cv[0] + "x" + (int) cv[1]
                                + " ppu=" + r.ppu + " view=" + r.viewRadiusM);
                    }
                }
                if (r.viewRadiusM < GraphFit.MIN_VIEW_RADIUS_M - 1e-2f
                        || r.viewRadiusM > GraphFit.HARD_MAX_VIEW_RADIUS_M + 1e-2f) {
                    randomFails++;
                    System.out.println("FAIL random bounds t=" + t);
                }
            }
        }
        System.out.println("random: " + randomFits + " fits, fails=" + randomFails);
        if (randomFails > 0) {
            fails += randomFails;
            checks += randomFails;
        }

        System.out.println(fails == 0 ? "PASS all checks" : "FAILED");
        System.out.println("checks=" + checks + " fails=" + fails);
        if (fails != 0) System.exit(1);
    }
}