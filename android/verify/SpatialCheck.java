import com.audiohub.source.Spatial;

/**
 * Spatial.java 的纯 Java 自检（无需 Android SDK）。
 *
 * 用法：
 *   android\verify\run-spatial-check.ps1
 * 或手动：
 *   javac -d %TEMP%\spatial-check Spatial.java SpatialCheck.java
 *   java -cp %TEMP%\spatial-check SpatialCheck
 *
 * 期望值全部来自 C++ 权威实现：
 *   - include/audiohub/spatial.hpp / src/spatial.cpp（公式）
 *   - tests/test_spatial.cpp（13 个用例的期望值与容差）
 * 这里逐条镜像，确保 Java 移植与 C++ 端“差异为零”。失败时进程退出码 = 1。
 *
 * 只允许 Java 8 语法、不 import android.*（本机只有 JDK 8，无 Android SDK）。
 */
public final class SpatialCheck {

    private static final float kTol = 1e-4f;

    private static int checks = 0;
    private static int failures = 0;
    private static String currentCase = "";

    public static void main(String[] args) {
        constantsMatchCpp();
        panGainsAcrossCircle();
        distanceGainClamps();
        itdFullCircle();
        rearCue();
        listenerYawRotatesScene();
        monoDownmixAndMakeup();
        userGainFoldsIntoOutput();
        bypassPassthrough();
        processWithoutTargetIsNoOp();
        itdActualDelay();
        smoothingAndDelaySlew();
        smoothingTimeConstantIs50ms();
        itdSlewBoundedPerBlock();
        processFiniteAndBounded();
        framesEdgeCases();
        resetClearsState();
        defaultLayoutStable();

        System.out.println("---------------------------------------------");
        if (failures == 0) {
            System.out.println("PASS: " + checks + " 项检查全部通过（与 C++ 期望值一致）");
        } else {
            System.out.println("FAIL: " + failures + " / " + checks + " 项检查失败");
            System.exit(1);
        }
    }

    // ------------------------------------------------ 测试骨架（打印 PASS/FAIL）

    private static int begin(String name) {
        currentCase = name;
        return failures;
    }

    private static void end(String name, int failuresBefore) {
        if (failures == failuresBefore) {
            System.out.println("PASS  " + name);
        } else {
            System.out.println("FAIL  " + name + "  (" + (failures - failuresBefore) + " 项)");
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL  [" + currentCase + "] " + what);
        }
    }

    private static void near(String what, float actual, float expected, float tol) {
        checks++;
        if (Float.isNaN(actual) || Math.abs(actual - expected) > tol) {
            failures++;
            System.out.println("FAIL  [" + currentCase + "] " + what
                    + ": actual=" + actual + " expected=" + expected + " tol=" + tol);
        }
    }

    private static void eq(String what, int actual, int expected) {
        checks++;
        if (actual != expected) {
            failures++;
            System.out.println("FAIL  [" + currentCase + "] " + what
                    + ": actual=" + actual + " expected=" + expected);
        }
    }

    /** 与 C++ 字面量逐位相同（常数表检查用，容差 0）。 */
    private static void exact(String what, float actual, float expected) {
        checks++;
        if (Float.floatToIntBits(actual) != Float.floatToIntBits(expected)) {
            failures++;
            System.out.println("FAIL  [" + currentCase + "] " + what
                    + ": bits differ, actual=" + actual + " expected=" + expected);
        }
    }

    private static void finite(String what, float v, float bound) {
        checks++;
        if (Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > bound) {
            failures++;
            System.out.println("FAIL  [" + currentCase + "] " + what
                    + ": v=" + v + " bound=" + bound);
        }
    }

    // ------------------------------------------------ 测试数据小工具

    private static void fill(float[] buf, int frames, float l, float r) {
        for (int i = 0; i < frames; ++i) {
            buf[2 * i] = l;
            buf[2 * i + 1] = r;
        }
    }

    private static void zeros(float[] buf) {
        java.util.Arrays.fill(buf, 0f);
    }

    private static Spatial.SpatialState newState() {
        final Spatial.SpatialState st = new Spatial.SpatialState();
        st.reset(48000.0f);
        return st;
    }

    private static Spatial.Vec3 onCircle(float azimuthDeg) {
        final float a = azimuthDeg * Spatial.kDegToRad;
        // 世界坐标：听者 yaw=0 面朝 -Z；+X 是右。az=+90 在 +X，az=0 在 -Z。
        return new Spatial.Vec3((float) Math.sin(a), 0.0f, -(float) Math.cos(a));
    }

    // ------------------------------------------------ 1. 常数表（与 C++ 逐位一致）

    private static void constantsMatchCpp() {
        final int f = begin("SpatialConstantsMatchCpp");
        exact("kPi", Spatial.kPi, 3.14159265358979323846f);
        exact("kDegToRad", Spatial.kDegToRad, Spatial.kPi / 180.0f);
        exact("kRadToDeg", Spatial.kRadToDeg, 180.0f / Spatial.kPi);
        exact("kSceneRadius", Spatial.kSceneRadius, 1.0f);
        exact("kDefaultCameraRadius", Spatial.kDefaultCameraRadius, 2.6f);
        exact("kMaxItdSeconds", Spatial.kMaxItdSeconds, 0.00063f);
        exact("kAzimuthBoost", Spatial.kAzimuthBoost, 1.0f);
        exact("kMonoMakeup", Spatial.kMonoMakeup, 1.41421356f);
        exact("kRearGain", Spatial.kRearGain, 0.7f);
        exact("kRearLpFrontHz", Spatial.kRearLpFrontHz, 20000.0f);
        exact("kRearLpBackHz", Spatial.kRearLpBackHz, 4000.0f);
        exact("kSmoothTauSec", Spatial.kSmoothTauSec, 0.05f);
        exact("kMaxDelaySlewFrames", Spatial.kMaxDelaySlewFrames, 2.0f);
        eq("kSpatialHistoryFrames", Spatial.kSpatialHistoryFrames, 64);
        eq("kSpatialHistoryChannels", Spatial.kSpatialHistoryChannels, 2);
        exact("kMinDistanceM", Spatial.kMinDistanceM, 0.25f);
        exact("kMinDistanceGain", Spatial.kMinDistanceGain, 0.5f);
        exact("kMaxDistanceGain", Spatial.kMaxDistanceGain, 2.0f);
        exact("kSpatialCoordLimit", Spatial.kSpatialCoordLimit, 20.0f);
        eq("kSampleRate", Spatial.kSampleRate, 48000);
        end("SpatialConstantsMatchCpp", f);
    }

    // ------------------------------------------------ 2. 等功率声像（C++ SpatialPanGainsAcrossCircle）

    private static void panGainsAcrossCircle() {
        final int f = begin("SpatialPanGainsAcrossCircle");
        final float[] g = new float[2];

        Spatial.panGains(0.0f, 1.0f, g);
        near("gl(0)", g[0], 0.70711f, kTol);
        near("gr(0)", g[1], 0.70711f, kTol);

        Spatial.panGains(90.0f, 1.0f, g);
        near("gl(90)", g[0], 0.0f, kTol);
        near("gr(90)", g[1], 1.0f, kTol);

        Spatial.panGains(-90.0f, 1.0f, g);
        near("gl(-90)", g[0], 1.0f, kTol);
        near("gr(-90)", g[1], 0.0f, kTol);

        Spatial.panGains(45.0f, 1.0f, g);
        near("gl(45)", g[0], 0.38268f, kTol);
        near("gr(45)", g[1], 0.92388f, kTol);

        Spatial.panGains(135.0f, 1.0f, g);
        near("gl(135)", g[0], 0.38268f, kTol);
        near("gr(135)", g[1], 0.92388f, kTol);

        // 正后方回中置（不是硬右），只靠后向线索
        Spatial.panGains(180.0f, 1.0f, g);
        near("gl(180)", g[0], 0.70711f, kTol);
        near("gr(180)", g[1], 0.70711f, kTol);
        near("panValue(180)", Spatial.panValue(180.0f, 1.0f), 0.0f, kTol);

        // 全圆等功率：gl^2 + gr^2 = 1
        for (float az = -180.0f; az <= 180.0f + 1e-3f; az += 15.0f) {
            Spatial.panGains(az, 1.0f, g);
            near("gl^2+gr^2 az=" + az, g[0] * g[0] + g[1] * g[1], 1.0f, 2e-5f);
        }
        end("SpatialPanGainsAcrossCircle", f);
    }

    // ------------------------------------------------ 3. 距离增益（C++ SpatialDistanceGainClamps）

    private static void distanceGainClamps() {
        final int f = begin("SpatialDistanceGainClamps");
        near("d=1", Spatial.distanceGain(1.0f), 1.0f, kTol);     // 0 dB
        near("d=2", Spatial.distanceGain(2.0f), 0.5f, kTol);     // -6.02 dB
        near("d=0.5", Spatial.distanceGain(0.5f), 2.0f, kTol);   // +6.02 dB
        near("d=0.25", Spatial.distanceGain(0.25f), 2.0f, kTol); // 近场封顶
        near("d=4", Spatial.distanceGain(4.0f), 0.5f, kTol);     // 远场下限
        near("d=0", Spatial.distanceGain(0.0f), 2.0f, kTol);     // 除零保护
        near("20log10(g(2))", (float) (20.0 * Math.log10(Spatial.distanceGain(2.0f))),
                -6.0206f, 0.01f);
        end("SpatialDistanceGainClamps", f);
    }

    // ------------------------------------------------ 4. ITD 全圆（C++ SpatialItdFullCircle）

    private static void itdFullCircle() {
        final int f = begin("SpatialItdFullCircle");
        final float fs = 48000.0f;
        near("itd(0)", Spatial.itdSeconds(0.0f), 0.0f, 1e-9f);
        near("itd(30) frames", Spatial.itdSeconds(30.0f) * fs, 15.12f, 0.05f);
        near("itd(60) frames", Spatial.itdSeconds(60.0f) * fs, 26.18f, 0.05f);
        near("itd(90) frames", Spatial.itdSeconds(90.0f) * fs, 30.24f, 0.05f);
        near("itd(120) frames", Spatial.itdSeconds(120.0f) * fs, 26.18f, 0.05f); // 后半球回落
        near("itd(180)", Spatial.itdSeconds(180.0f), 0.0f, 1e-6f);                // 正后方 0
        near("-90 对称", Spatial.itdSeconds(-90.0f), -Spatial.itdSeconds(90.0f), 1e-6f);
        end("SpatialItdFullCircle", f);
    }

    // ------------------------------------------------ 5. 后向线索（C++ SpatialRearCue）

    private static void rearCue() {
        final int f = begin("SpatialRearCue");
        near("rear(0)", Spatial.rearFactor(0.0f), 0.0f, kTol);
        near("rear(90)", Spatial.rearFactor(90.0f), 0.0f, kTol);
        near("rear(180)", Spatial.rearFactor(180.0f), 1.0f, kTol);
        near("cutoff(0)", Spatial.rearCutoffHz(0.0f), 20000.0f, 1.0f);
        near("cutoff(1)", Spatial.rearCutoffHz(1.0f), 4000.0f, 1.0f);
        near("cutoff(0.5)", Spatial.rearCutoffHz(0.5f), 8944.27f, 1.0f); // sqrt(20k*4k)
        end("SpatialRearCue", f);
    }

    // ------------------------------------------------ 6. 听者朝向/几何（C++ SpatialListenerYawRotatesScene）

    private static void listenerYawRotatesScene() {
        final int f = begin("SpatialListenerYawRotatesScene");
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        near("listener.x", listener.position.x, 0.0f, kTol);
        near("listener.y", listener.position.y, 0.0f, kTol);
        near("listener.z", listener.position.z, 0.0f, kTol);
        near("listener.yaw", listener.yawDeg, 0.0f, kTol);

        // 屏幕上方 = 远处 = 正前（-Z）
        Spatial.Geo g = Spatial.analyzePosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f), listener);
        near("az front", g.azimuthDeg, 0.0f, 1e-3f);
        near("dist front", g.distance, 1.0f, kTol);

        // 屏幕右侧 = +X = az=+90
        g = Spatial.analyzePosition(new Spatial.Vec3(1.0f, 0.0f, 0.0f), listener);
        near("az right", g.azimuthDeg, 90.0f, 1e-3f);

        // 屏幕下方 = +Z = 身后 = az=180
        g = Spatial.analyzePosition(new Spatial.Vec3(0.0f, 0.0f, 1.0f), listener);
        near("|az| behind", Math.abs(g.azimuthDeg), 180.0f, 1e-3f);
        near("rear behind", Spatial.rearFactor(g.azimuthDeg), 1.0f, kTol);

        // 相机 orbit 90 度：听者 yaw 跟随，正前节点变成右侧
        final Spatial.ListenerPose turned =
                new Spatial.ListenerPose(Spatial.Vec3.ZERO, 90.0f, 0.0f);
        g = Spatial.analyzePosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f), turned);
        near("az yaw90", g.azimuthDeg, 90.0f, 1e-3f);

        // 抬头：世界正上方的节点落到局部前方（el=0）；世界正前方的节点落到局部下方（el=-90）
        final Spatial.ListenerPose up =
                new Spatial.ListenerPose(Spatial.Vec3.ZERO, 0.0f, 90.0f);
        g = Spatial.analyzePosition(new Spatial.Vec3(0.0f, 1.0f, 0.0f), up);
        near("el pitch90 up", g.elevationDeg, 0.0f, 0.5f);
        g = Spatial.analyzePosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f), up);
        near("el pitch90 front", g.elevationDeg, -90.0f, 0.5f);
        end("SpatialListenerYawRotatesScene", f);
    }

    // ------------------------------------------------ 7. 单声道化 + 补偿（C++ SpatialMonoDownmixAndMakeup）

    private static void monoDownmixAndMakeup() {
        final int f = begin("SpatialMonoDownmixAndMakeup");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final float[] buf = new float[480];

        // 中心声像的等功率增益是 0.7071（gl=gr=√0.5），乘以 √2 单声道补偿后回到 1.0。
        final float[] pg = Spatial.panGains(0.0f, 1.0f);
        near("center pan gain", pg[0], 0.70711f, kTol);

        final Spatial.SpatialState st = newState();
        st.setTargetPosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f));
        for (int k = 0; k < 20; ++k) {
            fill(buf, 240, 1.0f, 1.0f);
            st.process(buf, 240, listener, params, 1.0f);
        }
        // 居中素材：mid=1，等功率 0.7071 * √2 = 1.0
        near("center out L", buf[478], 1.0f, 2e-3f);
        near("center out R", buf[479], 1.0f, 2e-3f);

        final Spatial.SpatialState st2 = newState();
        st2.setTargetPosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f));
        for (int k = 0; k < 20; ++k) {
            fill(buf, 240, 1.0f, 0.0f);   // 硬左素材：单声道化后 mid=0.5 -> 双耳各 0.5
            st2.process(buf, 240, listener, params, 1.0f);
        }
        near("hard-left out L", buf[478], 0.5f, 2e-3f);
        near("hard-left out R", buf[479], 0.5f, 2e-3f);
        end("SpatialMonoDownmixAndMakeup", f);
    }

    // ------------------------------------------------ 8. 用户音量折进每耳增益（C++ SpatialUserGainFoldsIntoOutput）

    private static void userGainFoldsIntoOutput() {
        final int f = begin("SpatialUserGainFoldsIntoOutput");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final Spatial.SpatialState st = newState();
        st.setTargetPosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f));
        final float[] buf = new float[480];
        // 60 块 = 300 ms = 6*tau，平滑收敛到 0.5（tau=50 ms）
        for (int k = 0; k < 60; ++k) {
            fill(buf, 240, 1.0f, 1.0f);
            st.process(buf, 240, listener, params, 0.5f);
        }
        near("userGain 0.5 out L", buf[478], 0.5f, 2e-3f);
        near("userGain 0.5 out R", buf[479], 0.5f, 2e-3f);
        end("SpatialUserGainFoldsIntoOutput", f);
    }

    // ------------------------------------------------ 9. 旁路直通（C++ SpatialBypassPassthrough）

    private static void bypassPassthrough() {
        final int f = begin("SpatialBypassPassthrough");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final Spatial.SpatialState st = newState();
        st.setTargetPosition(new Spatial.Vec3(1.0f, 0.0f, 0.0f));  // 即使位置在右侧
        st.setEnabled(false);
        check("isEnabled=false", !st.isEnabled());
        final float[] buf = new float[480];
        fill(buf, 240, 1.0f, 0.0f);
        st.process(buf, 240, listener, params, 1.0f);
        near("bypass buf[0]", buf[0], 1.0f, 1e-6f);   // 原样直通，采样未被改写
        near("bypass buf[1]", buf[1], 0.0f, 1e-6f);
        end("SpatialBypassPassthrough", f);
    }

    // ------------------------------------------------ 10. 无目标位置时不动（C++ SpatialProcessWithoutTargetIsNoOp）

    private static void processWithoutTargetIsNoOp() {
        final int f = begin("SpatialProcessWithoutTargetIsNoOp");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final Spatial.SpatialState st = newState();
        check("hasTarget=false", !st.hasTarget());
        final float[] buf = new float[480];
        fill(buf, 240, 0.3f, -0.4f);
        st.process(buf, 240, listener, params, 1.0f);
        near("no-target buf[0]", buf[0], 0.3f, 1e-6f);
        near("no-target buf[1]", buf[1], -0.4f, 1e-6f);
        end("SpatialProcessWithoutTargetIsNoOp", f);
    }

    // ------------------------------------------------ 11. ITD 真实延时（C++ SpatialItdActualDelay）

    private static void itdActualDelay() {
        final int f = begin("SpatialItdActualDelay");
        final int settle = 30;
        final int blocks = 12;
        final int frames = 240;
        final int impulseInBlock = 100;
        final float[] outL = new float[blocks * frames];
        final float[] outR = new float[blocks * frames];
        final float[] buf = new float[frames * 2];
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final Spatial.SpatialState st = newState();
        st.setTargetPosition(onCircle(60.0f));   // az=+60：右耳近、左耳远

        for (int k = 0; k < settle; ++k) {
            zeros(buf);
            st.process(buf, frames, listener, params, 1.0f);
        }
        for (int k = 0; k < blocks; ++k) {
            zeros(buf);
            if (k == 0) {
                buf[2 * impulseInBlock] = 1.0f;
                buf[2 * impulseInBlock + 1] = 1.0f;
            }
            st.process(buf, frames, listener, params, 1.0f);
            for (int i = 0; i < frames; ++i) {
                outL[k * frames + i] = buf[2 * i];
                outR[k * frames + i] = buf[2 * i + 1];
            }
        }
        int pl = 0;
        int pr = 0;
        for (int i = 1; i < blocks * frames; ++i) {
            if (Math.abs(outL[i]) > Math.abs(outL[pl])) pl = i;
            if (Math.abs(outR[i]) > Math.abs(outR[pr])) pr = i;
        }
        near("delay_l_frames（az=60 理想 ITD）", st.delayLFrames(), 26.18f, 0.1f);
        eq("近耳不延时 peak_r", pr, impulseInBlock);
        eq("远耳延时 pl-pr", pl - pr, 26);   // 分数延时峰值取整
        end("SpatialItdActualDelay", f);
    }

    // ------------------------------------------------ 12. 平滑 + ITD 限速（C++ SpatialSmoothingAndDelaySlew）

    private static void smoothingAndDelaySlew() {
        final int f = begin("SpatialSmoothingAndDelaySlew");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final float[] buf = new float[480];
        final Spatial.SpatialState st = newState();
        st.setTargetPosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f));   // 正前
        for (int k = 0; k < 40; ++k) {
            zeros(buf);
            st.process(buf, 240, listener, params, 1.0f);
        }
        near("gain_l 正前收敛", st.gainL(), 1.0f, 1e-3f);

        st.setTargetPosition(new Spatial.Vec3(1.0f, 0.0f, 0.0f));     // 突跳到正右侧（左耳目标 0）
        st.process(buf, 240, listener, params, 1.0f);
        near("gain_l 5ms 只走 9.5%", st.gainL(), 0.9048f, 0.01f);     // 无台阶
        // ITD 从 0 -> 30.24 帧，限速 2 帧/块
        near("delay_l 首块限速", st.delayLFrames(), 2.0f, 0.1f);

        for (int k = 0; k < 20; ++k) st.process(buf, 240, listener, params, 1.0f);
        near("delay_l 收敛到 30.24", st.delayLFrames(), 30.24f, 0.1f);
        end("SpatialSmoothingAndDelaySlew", f);
    }

    // ------------------------------------------------ 13. 50 ms 平滑时间常数方向（Lead 额外要求）

    private static void smoothingTimeConstantIs50ms() {
        final int f = begin("SpatialSmoothingTau50ms");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final float[] buf = new float[480];
        final Spatial.SpatialState st = newState();
        st.setTargetPosition(new Spatial.Vec3(1.0f, 0.0f, 0.0f));   // 目标：左耳 0
        // 每块 dt=5ms，k = 1-exp(-0.005/0.05) = 0.0951626；n 块后剩余 (1-k)^n = exp(-0.1n)
        zeros(buf);
        st.process(buf, 240, listener, params, 1.0f);
        near("1 块后 gain_l", st.gainL(), (float) Math.exp(-0.1), 2e-3f);
        float prev = st.gainL();
        boolean monotonic = true;
        for (int k = 0; k < 6; ++k) {
            zeros(buf);
            st.process(buf, 240, listener, params, 1.0f);
            if (st.gainL() > prev + 1e-6f || st.gainL() < 0.0f) monotonic = false;
            prev = st.gainL();
        }
        near("7 块后 gain_l（e^-0.7）", st.gainL(), (float) Math.exp(-0.7), 5e-3f);
        check("平滑单调下降（tau=50ms 方向正确）", monotonic);
        end("SpatialSmoothingTau50ms", f);
    }

    // ------------------------------------------------ 14. ITD 每块最多 2 帧（Lead 额外要求）

    private static void itdSlewBoundedPerBlock() {
        final int f = begin("SpatialItdSlewBoundedPerBlock");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final float[] buf = new float[480];
        final Spatial.SpatialState st = newState();
        zeros(buf);
        st.process(buf, 240, listener, params, 1.0f);
        float prevL = st.delayLFrames();
        float prevR = st.delayRFrames();
        boolean slewOk = true;
        boolean rangeOk = true;
        // 整圆扫一遍（含正后方 ITD 符号翻转），逐块检查限速
        for (float az = -180.0f; az <= 180.0f + 1e-3f; az += 10.0f) {
            st.setTargetPosition(onCircle(az));
            zeros(buf);
            st.process(buf, 240, listener, params, 1.0f);
            final float dL = Math.abs(st.delayLFrames() - prevL);
            final float dR = Math.abs(st.delayRFrames() - prevR);
            if (dL > Spatial.kMaxDelaySlewFrames + 1e-4f) slewOk = false;
            if (dR > Spatial.kMaxDelaySlewFrames + 1e-4f) slewOk = false;
            if (st.delayLFrames() > 30.24f + 1e-3f || st.delayRFrames() > 30.24f + 1e-3f) {
                rangeOk = false;
            }
            prevL = st.delayLFrames();
            prevR = st.delayRFrames();
        }
        check("每块 |ΔdelayL| <= 2 帧", slewOk);
        check("delay 不超过最大 ITD 30.24 帧", rangeOk);
        end("SpatialItdSlewBoundedPerBlock", f);
    }

    // ------------------------------------------------ 15. Process 输出有限有界（Lead 额外要求）

    private static void processFiniteAndBounded() {
        final int f = begin("SpatialProcessFiniteAndBounded");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final java.util.Random rng = new java.util.Random(12345L);
        final float[] buf = new float[480];
        final float[][] positions = new float[][]{
                {0.0f, 0.0f, 0.0f},        // 听者所在处（奇点，距离增益封顶 +6 dB）
                {1.0f, 0.0f, -1.0f},
                {-1.0f, 0.0f, 1.0f},       // 正后方
                {20.0f, 0.0f, 20.0f},      // 坐标上限
                {0.0f, 0.0f, -0.001f},     // 极近/正前
                {0.0f, 0.0f, 20.0f},       // 正后方远场
        };
        System.out.println("      （随机噪声 + 极端位置 + userGain=4，检查 NaN/Inf/越界）");
        for (int p = 0; p < positions.length; ++p) {
            final Spatial.SpatialState st = newState();
            st.setTargetPosition(positions[p][0], positions[p][1], positions[p][2]);
            for (int k = 0; k < 8; ++k) {
                for (int i = 0; i < buf.length; ++i) buf[i] = rng.nextFloat() * 2.0f - 1.0f;
                st.process(buf, 240, listener, params, 4.0f);
                for (int i = 0; i < buf.length; ++i) {
                    // 理论上界：0.7071*√2*2(dist)*4(gain)*(1+2(dull)) ≈ 24，留余量取 32
                    finite("pos#" + p + " sample#" + i, buf[i], 32.0f);
                }
            }
        }
        end("SpatialProcessFiniteAndBounded", f);
    }

    // ------------------------------------------------ 16. 边界参数（null / frames<=0 / 单帧）

    private static void framesEdgeCases() {
        final int f = begin("SpatialFramesEdgeCases");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final Spatial.SpatialState st = newState();
        st.setTargetPosition(new Spatial.Vec3(1.0f, 0.0f, 0.0f));

        boolean threw = false;
        try {
            st.process(null, 240, listener, params, 1.0f);   // null 缓冲：直接返回
        } catch (Throwable t) {
            threw = true;
        }
        check("null 缓冲不抛异常", !threw);

        final float[] buf = new float[480];
        fill(buf, 240, 0.25f, -0.25f);
        st.process(buf, 0, listener, params, 1.0f);
        near("frames=0 不写缓冲", buf[0], 0.25f, 1e-6f);
        st.process(buf, -5, listener, params, 1.0f);
        near("frames<0 不写缓冲", buf[0], 0.25f, 1e-6f);

        // 单帧（ramp 关闭，t=1）也要有限
        final Spatial.SpatialState st1 = newState();
        st1.setTargetPosition(new Spatial.Vec3(0.0f, 0.0f, -1.0f));
        final float[] one = new float[]{0.5f, 0.5f};
        st1.process(one, 1, listener, params, 1.0f);
        finite("frames=1 out L", one[0], 32.0f);
        finite("frames=1 out R", one[1], 32.0f);
        end("SpatialFramesEdgeCases", f);
    }

    // ------------------------------------------------ 17. Reset 清状态（C++ SpatialResetClearsState）

    private static void resetClearsState() {
        final int f = begin("SpatialResetClearsState");
        final Spatial.SpatialParams params = new Spatial.SpatialParams();
        final Spatial.ListenerPose listener = Spatial.defaultListener();
        final Spatial.SpatialState st = newState();
        st.setTargetPosition(new Spatial.Vec3(1.0f, 0.0f, 0.0f));
        final float[] buf = new float[480];
        fill(buf, 240, 1.0f, 1.0f);
        st.process(buf, 240, listener, params, 1.0f);
        st.setEnabled(false);        // C++ Reset 不清 enabled，这里一并验证
        st.reset(48000.0f);
        check("reset 后 hasTarget=false", !st.hasTarget());
        near("reset 后 delay_l", st.delayLFrames(), 0.0f, 1e-6f);
        near("reset 后 delay_r", st.delayRFrames(), 0.0f, 1e-6f);
        check("reset 不清 enabled（与 C++ 一致）", !st.isEnabled());
        st.setEnabled(true);
        end("SpatialResetClearsState", f);
    }

    // ------------------------------------------------ 18. 默认摆放稳定（C++ SpatialDefaultLayoutStable）

    private static void defaultLayoutStable() {
        final int f = begin("SpatialDefaultLayoutStable");
        final Spatial.Vec3 hub = Spatial.defaultScenePlacement("192.168.1.10:47000", true);
        near("hub.x", hub.x, 0.0f, 1e-6f);
        near("hub.y", hub.y, 0.0f, 1e-6f);
        near("hub.z", hub.z, 0.0f, 1e-6f);

        final String[] ids = new String[]{"192.168.1.11:47000", "192.168.1.12:47000", "android-1"};
        for (int i = 0; i < ids.length; ++i) {
            final Spatial.Vec3 a = Spatial.defaultScenePlacement(ids[i], false);
            final Spatial.Vec3 b = Spatial.defaultScenePlacement(ids[i], false);
            near("同 id 稳定 x (" + ids[i] + ")", a.x, b.x, 1e-6f);
            near("同 id 稳定 z (" + ids[i] + ")", a.z, b.z, 1e-6f);
            near("半径 1 (" + ids[i] + ")",
                    (float) Math.sqrt(a.x * a.x + a.z * a.z), 1.0f, 1e-4f);
            near("y=0 (" + ids[i] + ")", a.y, 0.0f, 1e-6f);
        }
        end("SpatialDefaultLayoutStable", f);
    }
}