package com.bullhead.equalizer;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Spinner;

import androidx.annotation.Nullable;

/**
 * 扩展 Spinner，暴露下拉打开/收起回调。
 *
 * 背景：Android Spinner 的 setOnDismissListener 是内部隐藏 API，应用层无法直接调用。
 * 方案：通过重写 performClick() 检测下拉打开；重写 onWindowFocusChanged() 检测下拉收起
 * （下拉弹出时 Activity 窗口失去焦点，收起时重新获得焦点）。
 *
 * 注意：onWindowFocusChanged 也会因其他原因（如切换到其他 Activity、拉下通知栏）触发，
 * 但此时下拉也确实已被收起，所以回调语义仍然正确。
 */
public class ObservableSpinner extends Spinner {

    @Nullable
    private Runnable onDropdownOpenedListener;
    @Nullable
    private Runnable onDropdownDismissedListener;

    private boolean dropdownOpen = false;

    public ObservableSpinner(Context context) {
        super(context);
    }

    public ObservableSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ObservableSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ObservableSpinner(Context context, AttributeSet attrs, int defStyleAttr, int defStyleAttrRes) {
        super(context, attrs, defStyleAttr, defStyleAttrRes);
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
            if (onDropdownOpenedListener != null) {
                onDropdownOpenedListener.run();
            }
        }
        return result;
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
