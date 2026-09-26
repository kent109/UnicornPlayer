package com.hw.lrcviewlib;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目内 fork 的歌词控件（基于 bifan-wei/LrcView V1.4 源码修改）。
 * <p>
 * 与原库的区别：
 * 原库 initLrcRowData 用"普通字号画笔 + 视口宽度 6/7"对歌词断句，而高亮行用
 * 更大字号绘制（本项目 15sp -> 18sp，放大 1.2 倍）。放大后的文本宽度可达
 * 0.857 * 1.2 约 1.03 倍视口宽，居中绘制时左右两侧会被裁切。
 * <p>
 * 修复：断句测量改用普通/高亮/拖选中最大的字号画笔，并在左右各预留
 * ROW_EDGE_PADDING_RATIO 比例的安全边距，保证任何状态下文本都不会超出控件。
 * <p>
 * 本类必须放在 com.hw.lrcviewlib 包下（而不是 com.unicorn.player.widget）：
 * 库中 LrcShowRow 的 Data/YPosition/RowHeight/RowPadding 字段是包私有的且没有
 * getter，只有同包才能直接访问；类名与库中 LrcView 不同以避免 dex 合并冲突。
 * <p>
 * 注意：PlayerActivity 通过反射访问本类私有成员
 * （mRows、FirstRowPositionY、HeightLightRowPosition、valueAnimator、
 * OnAnimation、InitLrcRowDada、initLrcRowData），重命名会破坏反射。
 */
public class UnicornLrcView extends View implements ILrcView {

    /**
     * 断句后左右各预留的视口宽度比例（高亮行与普通行共用的安全边距）
     */
    private static final float ROW_EDGE_PADDING_RATIO = 0.06f;

    private String NoDataMessage = "加载歌词中";
    private LrcViewContext lrcContext;
    private List<LrcRow> mRows;
    private Boolean TextSizeAutomaticMode = false;
    private long ActionDownTimeMoment = 0L;
    private float ActionDownY = 0.0f;
    private float ActionFirstY = 0.0f;
    private float HightLightRowPositionY = 0.0f;
    private float TrySelectRowPositionY = 0.0f;
    private int HeightLightRowPosition = 0;
    private int TrySelectRowPosition = 0;
    private float FirstRowPositionY = 0.0f;
    private float DragRowPositionY = 0.0f;
    private Boolean InitLrcRowDada = false;
    private Path TrianglePath = new Path();
    private View.OnClickListener mClickListener;
    private ILrcViewSeekListener mSeekListener;
    private Boolean OnAnimation = false;
    private int automaticMoveAnimationDuration = 400;
    private ValueAnimator valueAnimator;

    public UnicornLrcView(Context context) {
        super(context);
        this.init();
    }

    public UnicornLrcView(Context context, AttributeSet attr) {
        super(context, attr);
        this.init();
    }

    private void init() {
        this.lrcContext = new LrcViewContext(this.getContext());
        this.lrcContext.initTextPaint();
        this.applyHighlightBold();
    }

    /**
     * 高亮行加粗（fake bold）：库的 initTextPaint 每次都会重建画笔，
     * 因此在 fork 内每次 initTextPaint 之后重新应用，保证 commitLrcSettings
     * 等场景下依然生效。断句测量与绘制共用同一画笔，加粗后的宽度
     * 变化已被断句测量覆盖，不会引入切边。
     */
    private void applyHighlightBold() {
        if (this.lrcContext.HeightLightRowPaint != null) {
            // this.lrcContext.HeightLightRowPaint.setFakeBoldText(true);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (this.mRows == null || this.mRows.size() == 0) {
            this.DoClick();
            return true;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                this.ActionFirstY = this.ActionDownY = event.getY();
                this.ActionDownTimeMoment = System.currentTimeMillis();
                this.invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float Distance = event.getY() - this.ActionDownY;
                if (Math.abs(Distance) > 5.0f) {
                    this.lrcContext.CurrentState = LrcViewState.Seeking;
                    this.doSeek(event);
                    break;
                }
                this.lrcContext.CurrentState = LrcViewState.normal;
                break;
            }
            case MotionEvent.ACTION_UP: {
                float moveDistance = event.getY() - this.ActionDownY;
                LrcShowRow lastShowRow = this.getLastShowRow();
                if (!(lastShowRow != null && lastShowRow.YPosition <= (float) (this.getViewHeight() / 2)
                        || this.lrcContext.CurrentState != LrcViewState.Seeking
                        || !(moveDistance > 5.0f) && !(moveDistance < -5.0f))) {
                    this.seekToPosition();
                }
                boolean needPerformClick = System.currentTimeMillis() - this.ActionDownTimeMoment < 200L;
                if (needPerformClick && this.lrcContext.CurrentState == LrcViewState.normal) {
                    this.DoClick();
                }
                this.lrcContext.CurrentState = LrcViewState.normal;
                this.invalidate();
                break;
            }
        }
        return true;
    }

    private void seekToPosition() {
        this.HightLightRowPositionY = this.TrySelectRowPositionY;
        this.HeightLightRowPosition = this.TrySelectRowPosition;
        this.postInvalidate();
        if (this.mSeekListener != null) {
            this.mSeekListener.onSeek(this.mRows.get(this.HeightLightRowPosition),
                    this.mRows.get(this.HeightLightRowPosition).CurrentRowTime);
        }
    }

    private void doSeek(MotionEvent event) {
        float currentY = event.getY();
        LrcShowRow lastShowRow = this.getLastShowRow();
        float offsetY = currentY - this.ActionFirstY;
        this.FirstRowPositionY += offsetY;
        if (lastShowRow != null && lastShowRow.YPosition < (float) (this.getViewHeight() / 3)) {
            if (offsetY < 0.0f) {
                this.FirstRowPositionY -= offsetY;
            }
        } else {
            int rowOffset = Math.abs((int) (offsetY / (float) this.lrcContext.setting.NormalRowTextSize));
            if (offsetY < 0.0f) {
                this.TrySelectRowPosition += rowOffset;
            } else if (offsetY > 0.0f) {
                this.TrySelectRowPosition -= rowOffset;
            }
            this.TrySelectRowPosition = Math.max(0, this.TrySelectRowPosition);
            this.TrySelectRowPosition = Math.min(this.TrySelectRowPosition, this.mRows.size() - 1);
        }
        this.makeFirstRowPositionSecure();
        this.invalidate();
        this.ActionFirstY = currentY;
    }

    private LrcShowRow getLastShowRow() {
        if (this.mRows != null && this.mRows.size() > 0) {
            LrcRow lastRow = null;
            for (int i = this.mRows.size() - 1; i >= 0 && !(lastRow = this.mRows.get(i)).hasData().booleanValue(); --i) {
            }
            if (lastRow != null && lastRow.getShowRows() != null && lastRow.getShowRows().size() > 0) {
                return lastRow.getShowRows().get(lastRow.getShowRows().size() - 1);
            }
        }
        return null;
    }

    private int getTextFontHeight(Paint paint, String text) {
        Rect rect = new Rect();
        paint.getTextBounds(text, 0, text.length(), rect);
        return rect.height();
    }

    private void makeFirstRowPositionSecure() {
        float defaultFirstRowY = this.getHeight() / 2;
        this.FirstRowPositionY = Math.min(this.FirstRowPositionY, defaultFirstRowY);
        if (this.FirstRowPositionY == 0.0f) {
            this.FirstRowPositionY = defaultFirstRowY;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int ViewWidth = this.getViewWidth();
        int ViewHeight = this.getViewHeight();
        if (this.listIsEmpty(this.mRows).booleanValue()) {
            if (!TextUtils.isEmpty(this.NoDataMessage)) {
                canvas.drawText(this.NoDataMessage + "", (float) (ViewWidth / 2),
                        (float) (ViewHeight / 2 - this.lrcContext.setting.MessagePaintTextSize / 2),
                        this.lrcContext.MessagePaint);
            }
            return;
        }
        if (!this.InitLrcRowDada.booleanValue()) {
            this.InitLrcRowDada = true;
            this.initLrcRowData(this.mRows);
        }
        float rowX = ViewWidth / 2;
        float timeLineY = ViewHeight / 2;
        this.makeFirstRowPositionSecure();
        this.DragRowPositionY = this.FirstRowPositionY;
        if (this.lrcContext.CurrentState == LrcViewState.Seeking && this.getLrcSetting() != null) {
            LrcRow TrySelectRow = this.mRows.get(this.TrySelectRowPosition);
            String TrySelectTimeText = TrySelectRow.TimeText + "";
            float lStartX = (float) (this.getLrcSetting().TimeTextPaddingRight + this.getLrcSetting().TimeTextPaddingLeft)
                    + this.MeasureText(TrySelectTimeText, this.lrcContext.TimeTextPaint);
            int height = this.getTextFontHeight(this.lrcContext.TimeTextPaint, TrySelectTimeText);
            if (this.getLrcSetting().ShowTimeText.booleanValue()) {
                canvas.drawText(TrySelectTimeText, (float) this.getLrcSetting().TimeTextPaddingLeft,
                        timeLineY + (float) (height / 2), this.lrcContext.TimeTextPaint);
            } else {
                lStartX = this.getLrcSetting().SelectLinePaddingLeft;
            }
            float lStartY = timeLineY;
            float lStopX = ViewWidth - this.getLrcSetting().SelectLinePaddingRight;
            float lStopY = lStartY;
            if (this.getLrcSetting().ShowSelectLine.booleanValue()) {
                canvas.drawLine(lStartX, lStartY, lStopX, lStopY, this.lrcContext.SelectLinePaint);
            }
            if (this.getLrcSetting().ShowTriangle.booleanValue()) {
                int TriangleWidth = this.getLrcSetting().TriangleWidth;
                this.TrianglePath.moveTo(lStopX, lStopY - (float) TriangleWidth);
                this.TrianglePath.lineTo((float) ((double) lStopX - (double) TriangleWidth * 1.3), lStopY);
                this.TrianglePath.lineTo(lStopX, lStopY + (float) TriangleWidth);
                this.TrianglePath.lineTo(lStopX, lStopY - (float) TriangleWidth);
                canvas.drawPath(this.TrianglePath, this.lrcContext.SelectLinePaint);
            }
        }
        int RowPositionBottom = (int) this.DragRowPositionY;
        for (int i = 0; i < this.mRows.size(); ++i) {
            LrcRow lrcRow = this.mRows.get(i);
            if (i > 0) {
                RowPositionBottom = RowPositionBottom + this.getLrcSetting().LinePadding + lrcRow.ContentHeight;
            }
            int RowPositionTop = RowPositionBottom - lrcRow.ContentHeight;
            if (this.lrcContext.CurrentState == LrcViewState.normal) {
                if (i == this.HeightLightRowPosition) {
                    this.drawHeightLightRow(i, lrcRow, canvas, rowX);
                    continue;
                }
                this.drawNormalRow(i, lrcRow, canvas, rowX);
                continue;
            }
            int offset = this.getLrcSetting().LinePadding / 2;
            if (i == this.HeightLightRowPosition) {
                this.drawHeightLightRow(i, lrcRow, canvas, rowX);
                continue;
            }
            if ((float) (RowPositionBottom + offset) >= timeLineY && (float) (RowPositionTop - offset) <= timeLineY) {
                this.drawTrySelectRow(i, lrcRow, canvas, rowX);
                continue;
            }
            this.drawNormalRow(i, lrcRow, canvas, rowX);
        }
    }

    private void drawNormalRow(int rawIndex, LrcRow lrcRow, Canvas canvas, float rowX) {
        List<LrcShowRow> showRows = lrcRow.getShowRows();
        for (LrcShowRow sr : showRows) {
            sr.YPosition = this.DragRowPositionY;
            canvas.drawText(sr.Data + "", rowX, sr.YPosition, this.lrcContext.NormalRowPaint);
            this.DragRowPositionY += sr.RowHeight + sr.RowPadding;
        }
    }

    private void drawTrySelectRow(int rawIndex, LrcRow lrcRow, Canvas canvas, float rowX) {
        List<LrcShowRow> showRows = lrcRow.getShowRows();
        for (LrcShowRow sr : showRows) {
            sr.YPosition = this.DragRowPositionY;
            canvas.drawText(sr.Data + "", rowX, sr.YPosition, this.lrcContext.TrySelectRowPaint);
            this.DragRowPositionY += sr.RowHeight + sr.RowPadding;
        }
        this.TrySelectRowPosition = rawIndex;
        this.TrySelectRowPositionY = this.DragRowPositionY;
    }

    private void drawHeightLightRow(int rawIndex, LrcRow lrcRow, Canvas canvas, float rowX) {
        List<LrcShowRow> showRows = lrcRow.getShowRows();
        for (LrcShowRow sr : showRows) {
            sr.YPosition = this.DragRowPositionY;
            canvas.drawText(sr.Data + "", rowX, sr.YPosition, this.lrcContext.HeightLightRowPaint);
            this.DragRowPositionY += sr.RowHeight + sr.RowPadding;
        }
        if (showRows.size() > 0) {
            this.HightLightRowPositionY = showRows.get(showRows.size() - 1).YPosition;
        }
    }

    private float MeasureText(String text, Paint paint) {
        if (TextUtils.isEmpty(text)) {
            return 0.0f;
        }
        return paint.measureText(text);
    }

    private Boolean DoClick() {
        if (this.mClickListener != null) {
            this.mClickListener.onClick(null);
            return true;
        }
        return false;
    }

    @Override
    public void setOnClickListener(View.OnClickListener l) {
        this.mClickListener = l;
    }

    @Override
    public void setLrcData(List<LrcRow> lrcRows) {
        this.InitLrcRowDada = false;
        this.mRows = lrcRows;
        this.initData();
        this.postInvalidate();
    }

    private void initData() {
        this.ActionDownTimeMoment = 0L;
        this.ActionDownY = 0.0f;
        this.ActionFirstY = 0.0f;
        this.HightLightRowPositionY = 0.0f;
        this.TrySelectRowPositionY = 0.0f;
        this.HeightLightRowPosition = 0;
        this.TrySelectRowPosition = 0;
        this.FirstRowPositionY = 0.0f;
        this.DragRowPositionY = 0.0f;
    }

    private void initLrcRowData(List<LrcRow> lrcRows) {
        this.initLrcView();
        if (!this.listIsEmpty(lrcRows).booleanValue()) {
            // 修复高亮行切边：断句测量使用三种行样式中最大的字号画笔，
            // 并在左右各预留 ROW_EDGE_PADDING_RATIO 的安全边距。
            // 原库用普通字号测量断句（视口 6/7），高亮行放大字号后同文本
            // 会超出视口被裁切；用最大字号测量后，普通/拖选行只会更窄，均安全。
            Paint measurePaint = this.getLargestRowPaint();
            float edgePadding = this.getViewWidth() * ROW_EDGE_PADDING_RATIO;
            float lineWidth = Math.max(0f, this.getViewWidth() - edgePadding * 2);
            int index = 0;
            for (LrcRow r : lrcRows) {
                String content = r.getRowData();
                if (!TextUtils.isEmpty(content.trim())) {
                    List<String> lines = this.makeSecureLines(content, measurePaint, lineWidth);
                    for (String line : lines) {
                        int showRowHeight = this.getTextFontHeight(this.lrcContext.NormalRowPaint, line);
                        r.ContentHeight = r.ContentHeight + showRowHeight + this.getLrcSetting().LinePadding;
                        LrcShowRow showRow = new LrcShowRow(index++, line, showRowHeight, this.getLrcSetting().LinePadding);
                        r.getShowRows().add(showRow);
                    }
                } else {
                    int showRowHeight = this.getTextFontHeight(this.lrcContext.NormalRowPaint, "A") * 2;
                    r.ContentHeight = r.ContentHeight + showRowHeight + this.getLrcSetting().LinePadding;
                    LrcShowRow showRow = new LrcShowRow(index++, " ", showRowHeight, this.getLrcSetting().LinePadding);
                    r.getShowRows().add(showRow);
                }
                if (r.ContentHeight <= 0) continue;
                r.ContentHeight -= this.getLrcSetting().LinePadding;
            }
        }
    }

    /**
     * 返回普通行/高亮行/拖选中字号最大的画笔，用于断句测量
     */
    private Paint getLargestRowPaint() {
        Paint largest = this.lrcContext.NormalRowPaint;
        if (this.lrcContext.HeightLightRowPaint != null
                && this.lrcContext.HeightLightRowPaint.getTextSize() > largest.getTextSize()) {
            largest = this.lrcContext.HeightLightRowPaint;
        }
        if (this.lrcContext.TrySelectRowPaint != null
                && this.lrcContext.TrySelectRowPaint.getTextSize() > largest.getTextSize()) {
            largest = this.lrcContext.TrySelectRowPaint;
        }
        return largest;
    }

    private void initLrcView() {
        if (this.TextSizeAutomaticMode.booleanValue()) {
            int textHeight;
            this.getLrcSetting().NormalRowTextSize = textHeight = this.getViewHeight() / 20 - this.getLrcSetting().LinePadding;
            this.getLrcSetting().HeightLightRowTextSize = textHeight;
            this.getLrcSetting().TrySelectRowTextSize = textHeight;
            this.getLrcSetting().TimeTextSize = textHeight * 2 / 3;
            this.getLrcSetting().LinePadding = textHeight;
        }
        if (this.getLrcSetting().TriangleWidth <= 0) {
            this.getLrcSetting().TriangleWidth = this.getViewWidth() / 50;
        }
        this.lrcContext.initTextPaint();
        this.applyHighlightBold();
    }

    private List<String> makeSecureLines(String text, Paint mPaint, float secureLineWidth) {
        ArrayList<String> lines = new ArrayList<String>();
        if (!TextUtils.isEmpty(text)) {
            float maxWidth = secureLineWidth;
            int measuredNum = mPaint.breakText(text, true, maxWidth, null);
            lines.add(text.substring(0, measuredNum));
            String leftStr = text.substring(measuredNum);
            while (leftStr.length() > 0) {
                measuredNum = mPaint.breakText(leftStr, true, maxWidth, null);
                if (measuredNum > 0) {
                    lines.add(leftStr.substring(0, measuredNum));
                }
                leftStr = leftStr.substring(measuredNum);
            }
        }
        return lines;
    }

    @Override
    public void setLrcViewSeekListener(ILrcViewSeekListener seekListener) {
        this.mSeekListener = seekListener;
    }

    @Override
    public void setLrcViewMessage(String messageText) {
        this.NoDataMessage = messageText;
    }

    private void StartMoveAnimation(LrcRow trySelectRow, final int trySelectRowIndex) {
        if (trySelectRowIndex == this.HeightLightRowPosition) {
            return;
        }
        if (trySelectRow == null) {
            return;
        }
        if (this.mRows == null || this.HeightLightRowPosition >= this.mRows.size()) {
            return;
        }
        List<LrcShowRow> heightLightRs = this.mRows.get(this.HeightLightRowPosition).getShowRows();
        List<LrcShowRow> tryRs = trySelectRow.getShowRows();
        if (this.listIsEmpty(heightLightRs).booleanValue() || this.listIsEmpty(tryRs).booleanValue()) {
            return;
        }
        float heightLightRowShowY = this.getTimeLineYPosition() + this.getLrcSetting().HeightLightRowTextSize / 2;
        float trySelectRowY = tryRs.get(0).YPosition;
        float distance = heightLightRowShowY - trySelectRowY;
        final float FirstRowPositionPreY = this.FirstRowPositionY;
        if (this.valueAnimator != null) {
            this.valueAnimator.cancel();
        }
        this.valueAnimator = ValueAnimator.ofFloat(0.0f, distance);
        this.valueAnimator.setDuration((long) this.automaticMoveAnimationDuration);
        this.valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (Float) animation.getAnimatedValue();
                UnicornLrcView.this.FirstRowPositionY = FirstRowPositionPreY + value;
                UnicornLrcView.this.invalidate();
            }
        });
        this.valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                UnicornLrcView.this.HeightLightRowPosition = trySelectRowIndex;
                UnicornLrcView.this.valueAnimator = null;
                UnicornLrcView.this.invalidate();
                UnicornLrcView.this.OnAnimation = false;
            }
        });
        this.OnAnimation = true;
        this.valueAnimator.start();
    }

    public void seekLrcToTime(long time) {
        if (this.listIsEmpty(this.mRows).booleanValue()) {
            return;
        }
        if (this.lrcContext.CurrentState != LrcViewState.normal) {
            return;
        }
        if (this.OnAnimation.booleanValue() && this.valueAnimator != null) {
            this.valueAnimator.cancel();
            this.OnAnimation = false;
        }
        for (int i = 0; i < this.mRows.size(); ++i) {
            LrcRow next;
            LrcRow current = this.mRows.get(i);
            LrcRow lrcRow = next = i + 1 == this.mRows.size() ? null : this.mRows.get(i + 1);
            if ((time < current.CurrentRowTime || next == null || time >= next.CurrentRowTime)
                    && (time <= current.CurrentRowTime || next != null)) continue;
            this.StartMoveAnimation(current, i);
            return;
        }
    }

    public int getViewWidth() {
        return this.getWidth();
    }

    public int getViewHeight() {
        return this.getHeight();
    }

    public boolean hasData() {
        return !this.listIsEmpty(this.mRows).booleanValue();
    }

    public void setNoDataMessage(String noDataMessage) {
        this.NoDataMessage = noDataMessage;
        this.postInvalidate();
    }

    private Boolean listIsEmpty(List<?> list) {
        return list == null || list.size() == 0;
    }

    private int getTimeLineYPosition() {
        return this.getViewHeight() / 2;
    }

    @Override
    public void smoothScrollToTime(long time) {
        this.seekLrcToTime(time);
    }

    public LrcViewSetting getLrcSetting() {
        return this.lrcContext.setting;
    }

    public void commitLrcSettings() {
        this.lrcContext.initTextPaint();
        this.applyHighlightBold();
        if (this.getLrcSetting() != null && this.getLrcSetting().TriangleWidth <= 0) {
            this.getLrcSetting().TriangleWidth = this.getViewWidth() / 50;
        }
    }

    public void setTextSizeAutomaticMode(Boolean textSizeAutomaticMode) {
        this.TextSizeAutomaticMode = textSizeAutomaticMode;
        this.initLrcView();
    }

    public void setAutomaticMoveAnimationDuration(int automaticMoveAnimationDuration) {
        this.automaticMoveAnimationDuration = automaticMoveAnimationDuration;
    }

    public void onDestroy() {
        this.lrcContext.onDestroy();
        if (this.valueAnimator != null) {
            this.valueAnimator.cancel();
        }
        this.OnAnimation = false;
    }

    public int getAutomaticMoveAnimationDuration() {
        return this.automaticMoveAnimationDuration;
    }

    public LrcViewContext getLrcContext() {
        return this.lrcContext;
    }
}
