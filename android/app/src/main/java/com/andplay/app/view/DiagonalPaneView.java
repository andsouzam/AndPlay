package com.andplay.app.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Painel com corte diagonal para dividir a tela entre Modo Cinema e Modo TV.
 * Corta os elementos filhos via Canvas.clipPath usando um polígono convexo.
 */
public class DiagonalPaneView extends FrameLayout {

    private final Path clipPath = new Path();
    private boolean isLeftSide = true; // true = Cinema (esquerda), false = TV (direita)

    public DiagonalPaneView(@NonNull Context context) {
        super(context);
        init();
    }

    public DiagonalPaneView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DiagonalPaneView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    public void setLeftSide(boolean left) {
        this.isLeftSide = left;
        updateClipPath();
        invalidate();
    }

    public boolean isLeftSide() {
        return isLeftSide;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateClipPath();
    }

    private void updateClipPath() {
        clipPath.reset();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Corte diagonal suave: 54% no topo, 46% na base
        float topSplit = w * 0.54f;
        float bottomSplit = w * 0.46f;

        if (isLeftSide) {
            clipPath.moveTo(0, 0);
            clipPath.lineTo(topSplit, 0);
            clipPath.lineTo(bottomSplit, h);
            clipPath.lineTo(0, h);
            clipPath.close();
        } else {
            clipPath.moveTo(topSplit, 0);
            clipPath.lineTo(w, 0);
            clipPath.lineTo(w, h);
            clipPath.lineTo(bottomSplit, h);
            clipPath.close();
        }
    }

    public boolean containsPoint(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return false;

        float dividerX = w * (0.54f - 0.08f * (y / (float) h));
        if (isLeftSide) {
            return x <= dividerX;
        } else {
            return x > dividerX;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clipPath);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}
