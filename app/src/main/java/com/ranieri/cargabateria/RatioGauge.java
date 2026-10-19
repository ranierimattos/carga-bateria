package com.ranieri.cargabateria;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Régua da razão "carga real ÷ carga pela corrente": faixa de célula única (~1×),
 * faixa de célula dupla (~2×) e um marcador na razão medida até agora.
 */
public class RatioGauge extends View {

    private static final double MIN = 0.5, MAX = 2.8;

    private final Paint single = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dual = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    private double ratio = Double.NaN;

    public RatioGauge(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        track.setColor(0xFF2C2C2C);
        single.setColor(0x997CC242);
        dual.setColor(0x99FF9800);
        marker.setColor(0xFFEDEDED);
        marker.setStrokeWidth(3 * dp);
        label.setColor(0xFF9E9E9E);
        label.setTextSize(12 * dp);
        label.setTextAlign(Paint.Align.CENTER);
    }

    void setRatio(double ratio) {
        if (Double.compare(ratio, this.ratio) != 0) {
            this.ratio = ratio;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(44 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float top = 4 * dp, bottom = 20 * dp;
        c.drawRect(0, top, getWidth(), bottom, track);
        c.drawRect(x(CurrentScale.SINGLE_MIN), top, x(CurrentScale.SINGLE_MAX), bottom, single);
        c.drawRect(x(CurrentScale.DUAL_MIN), top, x(Math.min(CurrentScale.DUAL_MAX, MAX)), bottom, dual);

        float textY = bottom + 16 * dp;
        c.drawText("única (1×)", x(1.0), textY, label);
        c.drawText("dupla (2×)", x(2.0), textY, label);

        if (!Double.isNaN(ratio)) {
            float mx = x(Math.max(MIN, Math.min(MAX, ratio)));
            c.drawLine(mx, 0, mx, bottom + 4 * dp, marker);
        }
    }

    private float x(double r) {
        return (float) ((r - MIN) / (MAX - MIN) * getWidth());
    }
}
