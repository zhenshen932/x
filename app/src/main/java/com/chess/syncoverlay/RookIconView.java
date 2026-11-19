package com.chess.syncoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

public class RookIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public RookIconView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float iconH = h * 0.62f;
        float iconW = iconH * 0.86f;
        float left = (w - iconW) / 2f;
        float top = (h - iconH) / 2f;
        float gap = iconH * 0.042f;

        float crownH = iconH * 0.355f;
        float merlonH = crownH * 0.36f;
        float gapW = iconW * 0.145f;
        float merlonW = (iconW - 2f * gapW) / 3f;
        float crownBottomR = iconW * 0.16f;
        float merlonTopR = iconW * 0.035f;

        paint.setShader(new LinearGradient(
                left, top, left + iconW, top + crownH,
                Color.parseColor("#4F96FF"),
                Color.parseColor("#1D63D8"),
                Shader.TileMode.CLAMP
        ));
        paint.setShadowLayer(iconW * 0.06f, 0f, iconH * 0.025f, Color.parseColor("#45000000"));

        path.reset();
        rect.set(left, top, left + merlonW, top + merlonH + merlonTopR);
        path.addRoundRect(rect, new float[]{merlonTopR, merlonTopR, merlonTopR, merlonTopR, 0, 0, 0, 0}, Path.Direction.CW);
        float midX = left + merlonW + gapW;
        rect.set(midX, top, midX + merlonW, top + merlonH + merlonTopR);
        path.addRoundRect(rect, new float[]{merlonTopR, merlonTopR, merlonTopR, merlonTopR, 0, 0, 0, 0}, Path.Direction.CW);
        float rightX = left + 2f * (merlonW + gapW);
        rect.set(rightX, top, left + iconW, top + merlonH + merlonTopR);
        path.addRoundRect(rect, new float[]{merlonTopR, merlonTopR, merlonTopR, merlonTopR, 0, 0, 0, 0}, Path.Direction.CW);
        rect.set(left, top + merlonH, left + iconW, top + crownH);
        path.addRoundRect(rect, new float[]{0, 0, 0, 0, crownBottomR, crownBottomR, crownBottomR, crownBottomR}, Path.Direction.CW);
        canvas.drawPath(path, paint);

        float shaftTop = top + crownH + gap;
        float shaftH = iconH * 0.315f;
        float shaftW = iconW * 0.68f;
        float shaftLeft = left + (iconW - shaftW) / 2f;

        paint.setShader(new LinearGradient(
                shaftLeft, shaftTop, shaftLeft + shaftW, shaftTop + shaftH,
                Color.parseColor("#334155"),
                Color.parseColor("#1E293B"),
                Shader.TileMode.CLAMP
        ));
        rect.set(shaftLeft, shaftTop, shaftLeft + shaftW, shaftTop + shaftH);
        canvas.drawRect(rect, paint);

        float baseTop = shaftTop + shaftH + gap;
        float step1H = iconH * 0.112f;
        float step2H = iconH * 0.134f;
        float step1W = iconW * 0.85f;
        float step1Left = left + (iconW - step1W) / 2f;
        float stepR = iconW * 0.045f;

        paint.setShader(new LinearGradient(
                left, baseTop, left + iconW, baseTop + step1H + step2H,
                Color.parseColor("#273449"),
                Color.parseColor("#111827"),
                Shader.TileMode.CLAMP
        ));

        path.reset();
        rect.set(step1Left, baseTop, step1Left + step1W, baseTop + step1H + stepR);
        path.addRoundRect(rect, new float[]{stepR, stepR, stepR, stepR, 0, 0, 0, 0}, Path.Direction.CW);

        float step2Top = baseTop + step1H;
        rect.set(left, step2Top, left + iconW, step2Top + step2H);
        path.addRoundRect(rect, new float[]{stepR, stepR, stepR, stepR, stepR, stepR, stepR, stepR}, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }
}
