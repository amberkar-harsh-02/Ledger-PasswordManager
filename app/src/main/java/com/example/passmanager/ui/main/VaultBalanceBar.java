package com.example.passmanager.ui.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.example.passmanager.R;
import com.google.android.material.color.MaterialColors;

/**
 * Home's "vault balance": one thin bar split by password strength, read like a balance line.
 * Strong in primary ink, fair in outline grey ("pencil"), weak in attention amber; all three keep
 * 3:1 contrast with the surface. Segments get a small gap so they stay distinguishable without
 * relying on color, and the counts are spelled out in the legend next to it.
 */
public class VaultBalanceBar extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float gap;
    private final float radius;

    private final int strongColor;
    private final int fairColor;
    private final int weakColor;
    private final int emptyColor;

    private int strong, fair, weak;

    public VaultBalanceBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gap = getResources().getDisplayMetrics().density * 2;
        radius = getResources().getDisplayMetrics().density * 4;
        strongColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        fairColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline);
        weakColor = MaterialColors.getColor(this, R.attr.colorAttention);
        emptyColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the legend text carries the numbers
    }

    public void setCounts(int strong, int fair, int weak) {
        this.strong = strong;
        this.fair = fair;
        this.weak = weak;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        int total = strong + fair + weak;

        if (total == 0) {
            paint.setColor(emptyColor);
            rect.set(0, 0, width, height);
            canvas.drawRoundRect(rect, radius, radius, paint);
            return;
        }

        int[] counts = {strong, fair, weak};
        int[] colors = {strongColor, fairColor, weakColor};
        int segments = 0;
        for (int count : counts) if (count > 0) segments++;
        float usable = width - gap * (segments - 1);

        float x = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            float segmentWidth = usable * counts[i] / total;
            paint.setColor(colors[i]);
            rect.set(x, 0, x + segmentWidth, height);
            canvas.drawRoundRect(rect, radius, radius, paint);
            x += segmentWidth + gap;
        }
    }
}
