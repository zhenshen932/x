package com.chess.syncoverlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import java.util.Random;

public class ChessTapAccessibilityService extends AccessibilityService {
    private static volatile ChessTapAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();

    public static boolean isServiceConnected() {
        return instance != null;
    }

    public static boolean isEnabledInSettings(Context ctx) {
        if (instance != null) return true;
        try {
            int enabled = Settings.Secure.getInt(
                    ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
            );
            if (enabled != 1) return false;
            String settingValue = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (settingValue == null) return false;
            String expected = ctx.getPackageName() + "/" + ChessTapAccessibilityService.class.getName();
            String expectedShort = ctx.getPackageName() + "/." + ChessTapAccessibilityService.class.getSimpleName();
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String comp = splitter.next();
                if (comp.equalsIgnoreCase(expected) || comp.equalsIgnoreCase(expectedShort)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不监听屏幕内容事件，仅提供模拟点击手势派发
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    /**
     * 依次模拟点击起点格与目标格完成落子（带仿真人高斯随机偏移、微滑、随机按压时长与随机间隔）
     * 说明：Android 原生无障碍 GestureDescription 接口不开放触摸面积 (size) 与触摸压力 (pressure) 字段，
     * 这两项由系统 InputDispatcher 固定填充，无法通过无障碍 API 修改。
     */
    public static boolean performMoveTaps(
            final float x1,
            final float y1,
            final float x2,
            final float y2,
            final float cellSizePx,
            final int gapMs,
            final Runnable onFinished
    ) {
        final ChessTapAccessibilityService svc = instance;
        if (svc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        svc.dispatchTwoTaps(x1, y1, x2, y2, cellSizePx, gapMs, onFinished);
        return true;
    }

    private float nextGaussianClamped(float mean, float stdDev, float minVal, float maxVal) {
        float val = mean + (float) (rng.nextGaussian() * stdDev);
        return Math.max(minVal, Math.min(maxVal, val));
    }

    private long nextLongGaussianClamped(float mean, float stdDev, long minVal, long maxVal) {
        long val = Math.round(mean + rng.nextGaussian() * stdDev);
        return Math.max(minVal, Math.min(maxVal, val));
    }

    private void dispatchTwoTaps(
            final float x1,
            final float y1,
            final float x2,
            final float y2,
            final float cellSizePx,
            final int gapMs,
            final Runnable onFinished
    ) {
        final boolean[] finishedOnce = {false};
        final Runnable safeFinish = () -> {
            if (!finishedOnce[0]) {
                finishedOnce[0] = true;
                if (onFinished != null) onFinished.run();
            }
        };

        // 1. 落子间隔高斯随机化：围绕用户设置的 gapMs 波动，略偏向自然停顿
        final float baseGap = Math.max(60f, Math.min(240f, gapMs));
        final long minGap = Math.max(60L, Math.round(baseGap * 0.78f));
        final long maxGap = Math.min(320L, Math.round(baseGap * 1.45f) + 25L);
        final long randomizedGap = nextLongGaussianClamped(baseGap * 1.08f, baseGap * 0.22f, minGap, maxGap);

        // 2. 落子后交还控制权延迟也做轻微随机化 (55ms ~ 115ms)
        final long restoreDelay = nextLongGaussianClamped(78f, 16f, 55L, 115L);

        // 3. 触摸位置二维高斯偏移：限制在格子中心 ±22% 范围内，绝不误点邻格
        final float safeCell = Math.max(36f, cellSizePx);
        final float maxOffset = safeCell * 0.22f;
        final float posStd = safeCell * 0.095f;

        final float jx1 = Math.max(2f, x1 + nextGaussianClamped(0f, posStd, -maxOffset, maxOffset));
        final float jy1 = Math.max(2f, y1 + nextGaussianClamped(0f, posStd, -maxOffset, maxOffset));
        final float jx2 = Math.max(2f, x2 + nextGaussianClamped(0f, posStd, -maxOffset, maxOffset));
        final float jy2 = Math.max(2f, y2 + nextGaussianClamped(0f, posStd, -maxOffset, maxOffset));

        // 兜底保护：防止极少数系统手势回调丢失导致界面停在穿透态
        mainHandler.postDelayed(safeFinish, 1250L);

        dispatchHumanTap(jx1, jy1, () -> mainHandler.postDelayed(() ->
                dispatchHumanTap(jx2, jy2, () -> mainHandler.postDelayed(safeFinish, restoreDelay)),
                randomizedGap
        ));
    }

    private void dispatchHumanTap(float x, float y, final Runnable next) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            if (next != null) next.run();
            return;
        }
        float startX = Math.max(2f, x);
        float startY = Math.max(2f, y);

        // 随机生成真人按压时的微小手指滚动漂移 (0.8px ~ 3.4px)，避免出现 0 像素静止点
        double angle = rng.nextDouble() * Math.PI * 2.0;
        float driftDist = 0.8f + rng.nextFloat() * 2.6f;
        float endX = Math.max(2f, startX + (float) (Math.cos(angle) * driftDist));
        float endY = Math.max(2f, startY + (float) (Math.sin(angle) * driftDist));

        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);

        // 随机按压持续时间：高斯分布围绕 56ms，范围 36ms ~ 92ms
        long pressDurationMs = nextLongGaussianClamped(56f, 13f, 36L, 92L);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, pressDurationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (next != null) mainHandler.post(next);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (next != null) mainHandler.post(next);
            }
        }, mainHandler);

        if (!dispatched && next != null) {
            mainHandler.post(next);
        }
    }
}
