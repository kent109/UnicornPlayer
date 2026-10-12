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
 * 1. 原库 initLrcRowData 用"普通字号画笔 + 视口宽度 6/7"对歌词断句，而高亮行用
 *    更大字号绘制（本项目 15sp -> 18sp，放大 1.2 倍）。放大后的文本宽度可达
 *    0.857 * 1.2 约 1.03 倍视口宽，居中绘制时左右两侧会被裁切。
 *    修复：断句测量改用普通/高亮/拖选中最大的字号画笔，并在左右各预留
 *    ROW_EDGE_PADDING_RATIO 比例的安全边距，保证任何状态下文本都不会超出控件。
 * 2. 原库每个显示行高度用 getTextBounds 按本行墨迹测量（随文本内容变化），
 *    空白行又是另一套高度，baseline 直接顶格绘制，导致三种行高度不一致、
 *    滚动后顶部容易切掉半个字。
 *    修复：统一"行盒"模型——正常/高亮/空白行使用相同的固定行盒高度
 *    （三种样式中最大的 fontSpacing），文字在盒内按 FontMetrics 垂直居中，
 *    步长恒定；滚动结束时做终点锁相位（applyPhaseLock），保证顶部一行
 *    完整可见、其上方一行完全滑出，任何停留位置都不会切字。
 * 3. 开放原库缺少的即时定位公共 API：
 *    {@link #positionToTimeImmediate(long)}、
 *    {@link #recenterHighlightImmediate()}、{@link #cancelMoveAnimation()}。
 * <p>
 * 本类必须放在 com.hw.lrcviewlib 包下（而不是 com.unicorn.player.widget）：
 * 库中 LrcShowRow 的 Data/YPosition/RowHeight/RowPadding 字段是包私有的且没有
 * getter，只有同包才能直接访问；类名与库中 LrcView 不同以避免 dex 合并冲突。
 */
public class UnicornLrcView extends View implements ILrcView {

    /**
     * 断句后左右各预留的视口宽度比例（高亮行与普通行共用的安全边距）
     */
    private static final float ROW_EDGE_PADDING_RATIO = 0.05f;

    private String NoDataMessage = "";
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

    /**
     * 统一行盒高度：正常/高亮/空白行共用的固定高度（取三种画笔 fontSpacing 的最大值，
     * 在 {@link #initLrcRowData} 时计算），0 表示行布局尚未生成
     */
    private float lineBoxHeight = 0.0f;

    /**
     * 行盒之间的实际间距（像素）。行盒已吸收原 LinePadding 中
     * "测量墨迹小于行距"的部分，此值为补偿后的余量；每个显示行的步长
     * P = lineBoxHeight + currentRowPadding 恒定
     */
    private int currentRowPadding = 0;

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
            int rowOffset = Math.abs((int) (offsetY / (this.lineBoxHeight + this.currentRowPadding)));
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
        // 行盒模型下首行居中 = 行盒顶位于中线之上半个行盒；行布局未生成时回退中线
        float defaultFirstRowY = this.lineBoxHeight > 0.0f
                ? this.getHeight() / 2f - this.lineBoxHeight / 2f
                : this.getHeight() / 2f;
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
                RowPositionBottom = RowPositionBottom + this.currentRowPadding + lrcRow.ContentHeight;
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
            int offset = this.currentRowPadding / 2;
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

    /**
     * 文字在行盒内垂直居中时的 baseline：
     * baseline = 盒顶 + 盒高/2 − (ascent+descent)/2
     */
    private float baselineInBox(Paint paint, float boxTop) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return boxTop + this.lineBoxHeight / 2f - (fm.ascent + fm.descent) / 2f;
    }

    private void drawNormalRow(int rawIndex, LrcRow lrcRow, Canvas canvas, float rowX) {
        List<LrcShowRow> showRows = lrcRow.getShowRows();
        for (LrcShowRow sr : showRows) {
            sr.YPosition = this.baselineInBox(this.lrcContext.NormalRowPaint, this.DragRowPositionY);
            canvas.drawText(sr.Data + "", rowX, sr.YPosition, this.lrcContext.NormalRowPaint);
            this.DragRowPositionY += sr.RowHeight + sr.RowPadding;
        }
    }

    private void drawTrySelectRow(int rawIndex, LrcRow lrcRow, Canvas canvas, float rowX) {
        List<LrcShowRow> showRows = lrcRow.getShowRows();
        for (LrcShowRow sr : showRows) {
            sr.YPosition = this.baselineInBox(this.lrcContext.TrySelectRowPaint, this.DragRowPositionY);
            canvas.drawText(sr.Data + "", rowX, sr.YPosition, this.lrcContext.TrySelectRowPaint);
            this.DragRowPositionY += sr.RowHeight + sr.RowPadding;
        }
        this.TrySelectRowPosition = rawIndex;
        this.TrySelectRowPositionY = this.DragRowPositionY;
    }

    private void drawHeightLightRow(int rawIndex, LrcRow lrcRow, Canvas canvas, float rowX) {
        List<LrcShowRow> showRows = lrcRow.getShowRows();
        for (LrcShowRow sr : showRows) {
            sr.YPosition = this.baselineInBox(this.lrcContext.HeightLightRowPaint, this.DragRowPositionY);
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
        if (this.listIsEmpty(lrcRows).booleanValue()) {
            return;
        }
        // 统一行盒：取普通/高亮/拖选三种画笔 fontSpacing 的最大值作为每个显示行
        // （正常歌词、高亮歌词、空白行、换行后的多行）的固定高度。
        // fontSpacing 覆盖 ascent..descent..leading，任何字号的字形都不会超出行盒，
        // 配合盒内垂直居中后三种行高度完全一致、滚动步长恒定。
        float boxHeight = this.lrcContext.NormalRowPaint.getFontSpacing();
        if (this.lrcContext.HeightLightRowPaint != null) {
            boxHeight = Math.max(boxHeight, this.lrcContext.HeightLightRowPaint.getFontSpacing());
        }
        if (this.lrcContext.TrySelectRowPaint != null) {
            boxHeight = Math.max(boxHeight, this.lrcContext.TrySelectRowPaint.getFontSpacing());
        }
        float normalSpacing = this.lrcContext.NormalRowPaint.getFontSpacing();
        this.lineBoxHeight = (float) Math.ceil(boxHeight);
        // 原行高按本行墨迹测量（小于行距），LinePadding 中包含了这段差值；
        // 行盒按最大行距撑高后吸收该差值，余量作为真正的行盒间距，保持视觉密度基本不变
        int pad = this.getLrcSetting().LinePadding
                - Math.round(this.lineBoxHeight - normalSpacing);
        this.currentRowPadding = Math.max(0, pad);
        int slotStep = (int) this.lineBoxHeight + this.currentRowPadding;

        // 断句测量使用三种行样式中最大的字号画笔，并在左右各预留
        // ROW_EDGE_PADDING_RATIO 的安全边距，防止高亮行左右切边
        Paint measurePaint = this.getLargestRowPaint();
        float edgePadding = this.getViewWidth() * ROW_EDGE_PADDING_RATIO;
        float lineWidth = Math.max(0f, this.getViewWidth() - edgePadding * 2);
        int index = 0;
        for (LrcRow r : lrcRows) {
            String content = r.getRowData();
            List<String> lines;
            if (TextUtils.isEmpty(content == null ? null : content.trim())) {
                // 空白行：与正常行完全相同的行盒，仅绘制一个不可见空格
                lines = new ArrayList<String>();
                lines.add(" ");
            } else {
                lines = this.makeSecureLines(content, measurePaint, lineWidth);
            }
            for (String line : lines) {
                r.ContentHeight += slotStep;
                LrcShowRow showRow = new LrcShowRow(index++, line,
                        (int) this.lineBoxHeight, this.currentRowPadding);
                r.getShowRows().add(showRow);
            }
            if (r.ContentHeight > 0) {
                r.ContentHeight -= this.currentRowPadding;
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
        // 动画过程中目标行仍按普通行绘制（高亮下标在动画结束时才切换），
        // 因此目标 baseline 按普通行画笔在"行盒居中于时间轴"时的位置计算：
        // 行盒顶 = H/2 − box/2，居中 baseline = 盒顶 + box/2 − (ascent+descent)/2
        //        = H/2 − (ascent+descent)/2
        Paint.FontMetrics normalFm = this.lrcContext.NormalRowPaint.getFontMetrics();
        float heightLightRowShowY = this.getTimeLineYPosition()
                - (normalFm.ascent + normalFm.descent) / 2f;
        float trySelectRowY = tryRs.get(0).YPosition;
        float baseDistance = heightLightRowShowY - trySelectRowY;
        // 锁相位修正折入动画总距离：动画直接平滑停在已锁相的终点，
        // 不能在 onAnimationEnd 再修正，否则平滑滚动末尾会出现一次突跳
        float idealFirstY = this.FirstRowPositionY + baseDistance;
        float distance = baseDistance + this.phaseDeltaFor(idealFirstY);
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
                // 锁相位修正已折入动画距离，终点本身就是安全相位，此处不再突跳
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

    /**
     * 终点锁相位：统一行盒后每个显示行步长恒为 P = lineBoxHeight + currentRowPadding，
     * 行盒顶依次为 FirstRowPositionY + k*P。
     * 仅当内容已真正滚过顶部（FirstRowPositionY < 0）时才介入：此时把 F 对 P 取模
     * 的余数 r 锁定到 [0, currentRowPadding]：
     * - r ≥ 0：最顶部行盒顶在控件内，文字居中于行盒，顶部不会切字；
     * - r ≤ padding：上方一行的行盒底（= r − padding）不高于 0，完全滑出屏幕，
     *   不会在顶部露出被切掉的半行。
     * F ≥ 0 时顶部本就是空白（前几句歌词居中显示），不做任何修正，避免居中被拽偏。
     *
     * @return 应用的修正量（像素，0 表示无需修正），供动画把修正量折入滚动距离
     */
    private float applyPhaseLock() {
        float step = this.lineBoxHeight + this.currentRowPadding;
        if (step <= 0f || this.FirstRowPositionY >= 0f) {
            return 0f;
        }
        float r = this.FirstRowPositionY % step;
        if (r < 0f) {
            r += step;
        }
        if (r > this.currentRowPadding) {
            float delta = this.currentRowPadding - r;
            this.FirstRowPositionY += delta;
            return delta;
        }
        return 0f;
    }

    /**
     * 计算"理想居中位置 [idealFirstY] 还需要多少锁相位修正"，但不改变当前偏移。
     * 规则与 {@link #applyPhaseLock()} 一致，供滚动动画在启动前把修正量折入
     * 动画总距离——动画直接平滑停在已锁相的终点，避免动画结束瞬间再突跳。
     */
    private float phaseDeltaFor(float idealFirstY) {
        float step = this.lineBoxHeight + this.currentRowPadding;
        if (step <= 0f || idealFirstY >= 0f) {
            return 0f;
        }
        float r = idealFirstY % step;
        if (r < 0f) {
            r += step;
        }
        return r > this.currentRowPadding ? this.currentRowPadding - r : 0f;
    }

    /**
     * 确保行布局已生成（幂等）。setLrcData 只重置数据与标记，真正的断句/行盒计算
     * 原本延迟到首帧 onDraw；即时定位 API 需要在首帧前拿到行盒数据，故这里按
     * onDraw 相同的标记守卫提前执行一次，之后 onDraw 不会重复执行（initLrcRowData
     * 是累加式的，绝不能执行两次）。
     *
     * @return true 行布局可用；false 视图尚未完成测量或无数据
     */
    private boolean ensureLrcRowData() {
        if (this.listIsEmpty(this.mRows).booleanValue()) {
            return false;
        }
        if (this.getViewWidth() <= 0 || this.getViewHeight() <= 0) {
            return false;
        }
        if (!this.InitLrcRowDada.booleanValue()) {
            this.InitLrcRowDada = true;
            this.initLrcRowData(this.mRows);
        }
        return this.lineBoxHeight > 0f;
    }

    /**
     * 无动画把 [targetIndex] 行的行盒中心放到时间轴（控件垂直中线），
     * 然后做终点锁相位，使第一帧呈现的就是最终位置。
     */
    private void layoutHighlightAt(int targetIndex) {
        float step = this.lineBoxHeight + this.currentRowPadding;
        float slotsBefore = 0f;
        for (int i = 0; i < targetIndex; i++) {
            List<LrcShowRow> showRows = this.mRows.get(i).getShowRows();
            if (showRows != null) {
                slotsBefore += showRows.size() * step;
            }
        }
        this.HeightLightRowPosition = targetIndex;
        this.FirstRowPositionY = this.getViewHeight() / 2f - this.lineBoxHeight / 2f - slotsBefore;
        this.applyPhaseLock();
        this.invalidate();
    }

    /**
     * 取消正在运行的滚动动画并复位动画标记。
     * 库的 setLrcData 与外部即时定位都不会停止运行中的动画，其后续帧会逐帧
     * 覆盖 FirstRowPositionY，因此任何"即时定位/抑制窗口"前都应先调用本方法。
     */
    public void cancelMoveAnimation() {
        if (this.valueAnimator != null) {
            this.valueAnimator.cancel();
        }
        this.OnAnimation = false;
    }

    /**
     * 无动画、无首帧闪烁地定位到 [time] 对应的歌词行并垂直居中（含终点锁相位）。
     * 行选择规则与 seekLrcToTime 一致：最后一个 CurrentRowTime ≤ time 的行，
     * time 早于首行时间时定位首行。
     *
     * @return true 已定位；false 视图尺寸未就绪或无数据，调用方可在下一帧重试
     */
    public boolean positionToTimeImmediate(long time) {
        if (!this.ensureLrcRowData()) {
            return false;
        }
        this.cancelMoveAnimation();
        int targetIndex = 0;
        for (int i = 0; i < this.mRows.size(); ++i) {
            if (this.mRows.get(i).CurrentRowTime <= time) {
                targetIndex = i;
            } else {
                break;
            }
        }
        this.layoutHighlightAt(targetIndex);
        return true;
    }

    /**
     * 无动画把当前高亮行重新居中到时间轴并做终点锁相位。
     * 用于容器高度变化（进入/退出歌词全屏）后不改变高亮行、只修正位置的场景，
     * 与播放/暂停状态无关。
     *
     * @return true 已修正；false 数据/尺寸未就绪
     */
    public boolean recenterHighlightImmediate() {
        if (!this.ensureLrcRowData()) {
            return false;
        }
        if (this.HeightLightRowPosition < 0 || this.HeightLightRowPosition >= this.mRows.size()) {
            return false;
        }
        this.cancelMoveAnimation();
        this.layoutHighlightAt(this.HeightLightRowPosition);
        return true;
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
