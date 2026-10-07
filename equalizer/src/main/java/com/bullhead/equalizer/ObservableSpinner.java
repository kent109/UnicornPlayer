package com.bullhead.equalizer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.Spinner;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;

/**
 * 扩展 Spinner，暴露下拉打开/收起回调。
 *
 * 背景：Android Spinner 的 setOnDismissListener 是内部隐藏 API，应用层无法直接调用。
 * 方案：通过重写 performClick() 检测下拉打开；重写 onWindowFocusChanged() 检测下拉收起
 * （下拉弹出时 Activity 窗口失去焦点，收起时重新获得焦点）。
 *
 * 注意：onWindowFocusChanged 也会因其他原因（如切换到其他 Activity、拉下通知栏）触发，
 * 但此时下拉也确实已被收起，所以回调语义仍然正确。
 *
 * 另外限制下拉弹窗最大高度（{@link #MAX_VISIBLE_ITEMS} 项）并隐藏滚动条，
 * 避免预设数量增多后弹窗铺满屏幕。
 */
public class ObservableSpinner extends Spinner {

    private static final String TAG = "ObservableSpinner";

    /** 下拉弹窗最多同时显示的条目数，超出部分仍可上下滑动选择 */
    private static final int MAX_VISIBLE_ITEMS = 11;

    /** 下拉条目高度（与 spinner_dropdown_item.xml 的 minHeight 保持一致） */
    private static final int DROPDOWN_ITEM_HEIGHT_DP = 40;

    /** 下拉行间分割线厚度（与 drawable/eq_dropdown_divider 配套，样式对齐 app 模块歌曲排序弹层） */
    private static final float DROPDOWN_DIVIDER_HEIGHT_DP = 0.8f;

    /**
     * 分割线像素高度，供 {@link EqualizerFragment} 设置 ListView divider 时使用，
     * 与弹窗最大高度计算共用同一取值，避免两处不一致导致最后一项显示不全。
     */
    static int dropdownDividerHeightPx(Resources res) {
        return Math.max(1, (int) (DROPDOWN_DIVIDER_HEIGHT_DP * res.getDisplayMetrics().density));
    }

    @Nullable
    private Runnable onDropdownOpenedListener;
    @Nullable
    private Runnable onDropdownDismissedListener;

    private boolean dropdownOpen = false;

    public ObservableSpinner(Context context) {
        super(context);
        applyDropdownMaxHeight();
    }

    public ObservableSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
        applyDropdownMaxHeight();
    }

    public ObservableSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        applyDropdownMaxHeight();
    }

    public ObservableSpinner(Context context, AttributeSet attrs, int defStyleAttr, int defStyleAttrRes) {
        super(context, attrs, defStyleAttr, defStyleAttrRes);
        applyDropdownMaxHeight();
    }

    public void setOnDropdownOpenedListener(@Nullable Runnable listener) {
        this.onDropdownOpenedListener = listener;
    }

    public void setOnDropdownDismissedListener(@Nullable Runnable listener) {
        this.onDropdownDismissedListener = listener;
    }

    @Override
    public boolean performClick() {
        boolean result = super.performClick();
        if (!dropdownOpen) {
            dropdownOpen = true;
            hideDropdownScrollbars();
            if (onDropdownOpenedListener != null) {
                onDropdownOpenedListener.run();
            }
        }
        return result;
    }

    /**
     * 构造阶段提前设置下拉弹窗最大高度。
     *
     * 必须在首次 show() 之前调用：ListPopupWindow 的高度在 show 时参与
     * DropDownListView 的 build/measure，show 之后再 setHeight 对首次弹出无效，
     * 要到下次重建才生效（表现为第一次点开仍是全屏列表）。
     * Spinner 构造函数返回后内部 mPopup（DropdownPopup，继承 ListPopupWindow）已存在。
     */
    @SuppressLint("PrivateApi")
    private void applyDropdownMaxHeight() {
        try {
            Field popupField = Spinner.class.getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object popup = popupField.get(this);
            if (popup instanceof ListPopupWindow) {
                float density = getResources().getDisplayMetrics().density;
                // 11 项之间有 10 条分割线，必须一并计入，否则最后一项会被裁掉一截
                int dividerPx = dropdownDividerHeightPx(getResources());
                int maxHeight = (int) (MAX_VISIBLE_ITEMS * DROPDOWN_ITEM_HEIGHT_DP * density)
                        + (MAX_VISIBLE_ITEMS - 1) * dividerPx;
                ((ListPopupWindow) popup).setHeight(maxHeight);
            }
        } catch (Throwable t) {
            // 厂商 ROM 若改动内部字段，静默降级为系统默认弹窗，不影响功能
            Log.w(TAG, "applyDropdownMaxHeight failed, fallback to default popup", t);
        }
    }

    /**
     * 下拉打开后隐藏滚动条。ListView 在 show() 内部才创建，
     * 因此只能在 performClick（super 已完成 show）之后获取。
     */
    @SuppressLint("PrivateApi")
    private void hideDropdownScrollbars() {
        try {
            Field popupField = Spinner.class.getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object popup = popupField.get(this);
            if (!(popup instanceof ListPopupWindow)) {
                return;
            }
            ListView listView = ((ListPopupWindow) popup).getListView();
            if (listView != null) {
                listView.setVerticalScrollBarEnabled(false);
                listView.setScrollBarSize(0);
                listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                listView.setVerticalFadingEdgeEnabled(false);
                listView.setScrollingCacheEnabled(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hideDropdownScrollbars failed", t);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && dropdownOpen) {
            dropdownOpen = false;
            if (onDropdownDismissedListener != null) {
                onDropdownDismissedListener.run();
            }
        }
    }
}
