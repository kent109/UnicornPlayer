package com.bullhead.equalizer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.example.equalizer.R;

/**
 * Created by Harjot on 23-May-16.
 */
public class AnalogController extends View {

    float midx, midy;
    Paint textPaint, pctPaint, circlePaint, circlePaint2, linePaint;
    String angle;
    float currdeg, deg = 3, downdeg;

    int progressColor, lineColor;

    onProgressChangedListener mListener;

    String label;

    public interface onProgressChangedListener {
        void onProgressChanged(int progress);
    }

    public void setOnProgressChangedListener(onProgressChangedListener listener) {
        mListener = listener;
    }

    public AnalogController(Context context) {
        super(context);
        init();
    }

    public AnalogController(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnalogController(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    void init() {
        textPaint = new Paint();
        textPaint.setColor(getResources().getColor(R.color.text_color, getContext().getTheme()));
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(32);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        circlePaint = new Paint();
        circlePaint.setColor(Color.parseColor("#222222"));
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint2 = new Paint();
        circlePaint2.setColor(EqualizerFragment.themeColor);
//        circlePaint2.setColor(Color.parseColor("#FFA036"));
        circlePaint2.setStyle(Paint.Style.FILL);
        linePaint = new Paint();
        linePaint.setColor(EqualizerFragment.themeColor);
//        linePaint.setColor(Color.parseColor("#FFA036"));
        linePaint.setStrokeWidth(7);
        pctPaint = new Paint();
        pctPaint.setColor(getResources().getColor(R.color.text_color, getContext().getTheme()));
        pctPaint.setStyle(Paint.Style.FILL);
        pctPaint.setTextSize(36);
        pctPaint.setFakeBoldText(true);
        pctPaint.setAntiAlias(true);
        pctPaint.setTextAlign(Paint.Align.CENTER);
        angle = "0.0";
        label = "Label";
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        midx = (float) getWidth() / 2;
        midy = (float) getHeight() / 2 - 8;

        int ang = 0;
        float x = 0, y = 0;
        int radius = (int) (Math.min(midx, midy) * ((float) 14.5 / 16));
        float deg2 = Math.max(3, deg);
        float deg3 = Math.min(deg, 21);
        for (int i = (int) (deg2); i < 22; i++) {
            float tmp = (float) i / 24;
            x = midx + (float) (radius * Math.sin(2 * Math.PI * (1.0 - tmp)));
            y = midy + (float) (radius * Math.cos(2 * Math.PI * (1.0 - tmp)));
            circlePaint.setColor(getResources().getColor(R.color.analog_progress_color, getContext().getTheme()));
            canvas.drawCircle(x, y, ((float) radius / 15), circlePaint);
        }
        for (int i = 3; i <= deg3; i++) {
            float tmp = (float) i / 24;
            x = midx + (float) (radius * Math.sin(2 * Math.PI * (1.0 - tmp)));
            y = midy + (float) (radius * Math.cos(2 * Math.PI * (1.0 - tmp)));
            canvas.drawCircle(x, y, ((float) radius / 15), circlePaint2);
        }

        float tmp2 = deg / 24;
        float x1 = midx + (float) (radius * ((float) 2 / 5) * Math.sin(2 * Math.PI * (1.0 - tmp2)));
        float y1 = midy + (float) (radius * ((float) 2 / 5) * Math.cos(2 * Math.PI * (1.0 - tmp2)));
        float x2 = midx + (float) (radius * ((float) 3 / 5) * Math.sin(2 * Math.PI * (1.0 - tmp2)));
        float y2 = midy + (float) (radius * ((float) 3 / 5) * Math.cos(2 * Math.PI * (1.0 - tmp2)));

        circlePaint.setColor(getResources().getColor(R.color.analog_ring_color, getContext().getTheme()));
        canvas.drawCircle(midx, midy, radius * ((float) 13 / 15), circlePaint);
        circlePaint.setColor(getResources().getColor(R.color.analog_fill_color, getContext().getTheme()));
        canvas.drawCircle(midx, midy, radius * ((float) 11 / 15), circlePaint);
        canvas.drawText(label, midx, midy + (float) (radius * 1.15), textPaint);
        canvas.drawLine(x1, y1, x2, y2, linePaint);

        // 旋钮中心显示百分比（progress/19），滑动时随 invalidate 实时刷新；
        // progress <= 0（指针垂直向下，效果关闭）时不显示
        int progress = (int) (deg - 2);
        if (progress > 0) {
            int pct = Math.round(progress * 100f / 19);
            if (pct > 100) {
                pct = 100;
            }
            // 颜色与边缘已点亮的小圆点（主题色）保持一致，每次绘制时实时读取以同步主题变化
            pctPaint.setColor(circlePaint2.getColor());
            float textY = midy - ((pctPaint.ascent() + pctPaint.descent()) / 2);
            canvas.drawText(pct + "%", midx, textY, pctPaint);
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) {
            return false;
        }

        // 圆心与圆弧半径（与 onDraw 一致；onTouchEvent 中独立计算，避免依赖 onDraw 的赋值时序）
        float cx = (float) getWidth() / 2;
        float cy = (float) getHeight() / 2 - 8;
        int radius = (int) (Math.min(cx, cy) * ((float) 14.5 / 16));

        // 只有手指沿圆弧滑动时才响应；中心区域（dist < radius*0.5）不响应，避免误触导致指针滑动
        float touchDx = e.getX() - cx;
        float touchDy = e.getY() - cy;
        float touchDist = (float) Math.sqrt(touchDx * touchDx + touchDy * touchDy);
        if (touchDist < radius * 0.5f) {
            // ACTION_DOWN 时不消费事件让父视图接管；其他动作消费但不处理（保持手势不中断）
            return e.getAction() != MotionEvent.ACTION_DOWN;
        }

        if (mListener != null) {
            mListener.onProgressChanged((int) (deg - 2));
        }

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float dx = e.getX() - midx;
            float dy = e.getY() - midy;
            downdeg = (float) ((Math.atan2(dy, dx) * 180) / Math.PI);
            downdeg -= 90;
            if (downdeg < 0) {
                downdeg += 360;
            }
            downdeg = (float) Math.floor(downdeg / 15);
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = e.getX() - midx;
            float dy = e.getY() - midy;
            currdeg = (float) ((Math.atan2(dy, dx) * 180) / Math.PI);
            currdeg -= 90;
            if (currdeg < 0) {
                currdeg += 360;
            }
            currdeg = (float) Math.floor(currdeg / 15);

            // 记录修改前的 deg，用于跳过无效区间 (0, 3) 时的方向判断
            int prevDeg = (int) deg;

            if (currdeg == 0 && downdeg == 23) {
                deg++;
                if (deg > 21) {
                    deg = 21;
                }
                downdeg = currdeg;
            } else if (currdeg == 23 && downdeg == 0) {
                deg--;
                if (deg < 0) {
                    deg = 0;
                }
                downdeg = currdeg;
            } else {
                deg += (currdeg - downdeg);
                if (deg > 21) {
                    deg = 21;
                }
                // 下限 0 = 指针垂直向下（中立位，progress=-2），允许滑回关闭状态
                if (deg < 0) {
                    deg = 0;
                }
                downdeg = currdeg;
            }

            // 跳过无效区间 (0, 3) 中的 1、2 位（对应 progress=-1、0）
            // 这两个位置视觉上偏离关闭态、但 strength/preset 仍为 0，无实际作用
            // 从关闭位（prevDeg<=0）向上滑 -> 直接跳到最小有效位 deg=3（progress=1）
            // 从有效位（prevDeg>=3）向下滑 -> 直接跳回关闭位 deg=0（progress=-2）
            if (deg > 0 && deg < 3) {
                if (prevDeg <= 0) {
                    deg = 3;
                } else {
                    deg = 0;
                }
            }

            angle = String.valueOf(deg);
            invalidate();
            return true;
        }
        return e.getAction() == MotionEvent.ACTION_UP || super.onTouchEvent(e);
    }

    public int getProgress() {
        return (int) (deg - 2);
    }

    public void setProgress(int param) {
        deg = param + 2;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String txt) {
        label = txt;
    }

    public int getLineColor() {
        return lineColor;
    }

    public void setLineColor(int lineColor) {
        this.lineColor = lineColor;
    }

    public int getProgressColor() {
        return progressColor;
    }

    public void setProgressColor(int progressColor) {
        this.progressColor = progressColor;
    }
}
