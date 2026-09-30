package com.andplay.app.view;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Linha divisória diagonal com efeito de laser neon e brilho brilhante.
 * Conecta o topo (54% de largura) com a base (46% de largura).
 */
public class DiagonalDividerView extends View {

    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint midGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int currentColor = Color.parseColor("#FFD700");
    private ValueAnimator colorAnimator;

    public DiagonalDividerView(Context context) {
        super(context);
        init();
    }

    public DiagonalDividerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DiagonalDividerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        float density = getResources().getDisplayMetrics().density;

        bloomPaint.setStyle(Paint.Style.STROKE);
        bloomPaint.setStrokeWidth(16f * density);
        bloomPaint.setStrokeCap(Paint.Cap.ROUND);

        midGlowPaint.setStyle(Paint.Style.STROKE);
        midGlowPaint.setStrokeWidth(6f * density);
        midGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeWidth(2.5f * density);
        corePaint.setStrokeCap(Paint.Cap.ROUND);

        updatePaints();
    }

    public void setGlowColor(int targetColor, boolean animate) {
        if (!animate) {
            if (colorAnimator != null && colorAnimator.isRunning()) {
                colorAnimator.cancel();
            }
            currentColor = targetColor;
            updatePaints();
            invalidate();
            return;
        }

        if (colorAnimator != null && colorAnimator.isRunning()) {
            colorAnimator.cancel();
        }

        colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), currentColor, targetColor);
        colorAnimator.setDuration(280);
        colorAnimator.addUpdateListener(anim -> {
            currentColor = (int) anim.getAnimatedValue();
            updatePaints();
            invalidate();
        });
        colorAnimator.start();
    }

    private void updatePaints() {
        int r = Color.red(currentColor);
        int g = Color.green(currentColor);
        int b = Color.blue(currentColor);

        bloomPaint.setColor(Color.argb(0x35, r, g, b));
        midGlowPaint.setColor(Color.argb(0x80, r, g, b));
        corePaint.setColor(Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float startX = w * 0.54f;
        float startY = 0f;
        float endX = w * 0.46f;
        float endY = (float) h;

        // 1. Brilho ambiente espalhado
        canvas.drawLine(startX, startY, endX, endY, bloomPaint);
        // 2. Halo médio
        canvas.drawLine(startX, startY, endX, endY, midGlowPaint);
        // 3. Núcleo branco laser
        canvas.drawLine(startX, startY, endX, endY, corePaint);
    }
}
