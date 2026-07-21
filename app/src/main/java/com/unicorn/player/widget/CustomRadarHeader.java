package com.unicorn.player.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import com.scwang.smart.refresh.header.BezierRadarHeader;
import com.scwang.smart.refresh.layout.util.SmartUtil;

public class CustomRadarHeader extends BezierRadarHeader {
    public CustomRadarHeader(Context context) {
        this(context, null);
    }

    public CustomRadarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDotRadius = SmartUtil.dp2px(6);
        mRadarRadius = SmartUtil.dp2px(12);
    }

    @Override
    protected void drawDot(Canvas canvas, int width, int height) {
        if (mDotAlpha > 0) {
            width /= 3;
            mPaint.setColor(mAccentColor);
            final int num = 3;
            float x = SmartUtil.px2dp(height);
            float wide = (1f * width / num) * mDotFraction - ((mDotFraction > 1) ? ((mDotFraction - 1) * (1f * width / num) / mDotFraction) : 0);//y1 = t*(w/n)-(t>1)*((t-1)*(w/n)/t)
            float high = height - ((mDotFraction > 1) ? ((mDotFraction - 1) * height / 2 / mDotFraction) : 0);//y2 = x - (t>1)*((t-1)*x/t);
            for (int i = 0; i < num; i++) {
                float index = 1f + i - (1f + num) / 2;//y3 = (x + 1) - (n + 1)/2; 居中 index 变量：0 1 2 3 4 结果： -2 -1 0 1 2
                float alpha = 255 * (1 - (2 * (Math.abs(index) / num)));//y4 = m * ( 1 - 2 * abs(y3) / n); 横向 alpha 差
                mPaint.setAlpha((int) (mDotAlpha * alpha * (1d - 1d / Math.pow((x / 800d + 1d), 15))));//y5 = y4 * (1-1/((x/800+1)^15));竖直 alpha 差
                float radius = mDotRadius * (1 - 1 / ((x / 16 + 1)));//y6 = mDotRadius*(1-1/(x/16+1));半径
                canvas.drawCircle((float) width / 2 + width - radius / 2 + wide * index, high / 2, radius, mPaint);
            }
            mPaint.setAlpha(255);
        }
    }

    @Override
    protected void drawRipple(Canvas canvas, int width, int height) {
    }
}
