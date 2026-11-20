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

public class ChessTapAccessibilityService extends AccessibilityService {
    private static volatile ChessTapAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
     * 依次模拟点击起点格与目标格完成落子
     */
    public static boolean performMoveTaps(
            final float x1,
            final float y1,
            final float x2,
            final float y2,
            final int gapMs,
            final Runnable onFinished
    ) {
        final ChessTapAccessibilityService svc = instance;
        if (svc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        svc.dispatchTwoTaps(x1, y1, x2, y2, gapMs, onFinished);
        return true;
    }

    private void dispatchTwoTaps(
            final float x1,
            final float y1,
            final float x2,
            final float y2,
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

        final long clampedGap = Math.max(60L, Math.min(240L, gapMs));
        // 兜底保护：防止极少数系统手势回调丢失导致界面停在穿透态
        mainHandler.postDelayed(safeFinish, 950L);

        // 稳健高速双连点：单次按压 36ms（适配 30/60fps 小游戏不丢点），起止格默认间隔 105ms，落子后 65ms 交还录入控制权
        dispatchSingleTap(x1, y1, () -> mainHandler.postDelayed(() ->
                dispatchSingleTap(x2, y2, () -> mainHandler.postDelayed(safeFinish, 65L)),
                clampedGap
        ));
    }

    private void dispatchSingleTap(float x, float y, final Runnable next) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            if (next != null) next.run();
            return;
        }
        float safeX = Math.max(1f, x);
        float safeY = Math.max(1f, y);
        Path path = new Path();
        path.moveTo(safeX, safeY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 36L);
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
