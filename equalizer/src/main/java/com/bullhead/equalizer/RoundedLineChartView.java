package com.bullhead.equalizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;

import com.db.chart.view.LineChartView;

import java.lang.reflect.Field;

public class RoundedLineChartView extends LineChartView {

    private Paint linePaint;

    public RoundedLineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RoundedLineChartView(Context context) {
        super(context);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        initLinePaint();
    }

    private void initLinePaint() {
        try {
            Field styleField = LineChartView.class.getDeclaredField("mStyle");
            styleField.setAccessible(true);
            Object style = styleField.get(this);
            Field paintField = style.getClass().getDeclaredField("mLinePaint");
            paintField.setAccessible(true);
            linePaint = (Paint) paintField.get(style);
            if (linePaint != null) {
                linePaint.setStrokeCap(Paint.Cap.ROUND);
            }
        } catch (Exception e) {
            linePaint = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (linePaint != null) {
            linePaint.setStrokeCap(Paint.Cap.ROUND);
        }
        super.onDraw(canvas);
    }
}
