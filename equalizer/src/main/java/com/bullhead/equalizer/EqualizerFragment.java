package com.bullhead.equalizer;


import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.db.chart.model.LineSet;
import com.db.chart.view.AxisController;
import com.db.chart.view.ChartView;
import com.db.chart.view.LineChartView;
import com.example.equalizer.R;
import com.h6ah4i.android.widget.verticalseekbar.VerticalSeekBar;
import com.h6ah4i.android.widget.verticalseekbar.VerticalSeekBarWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;


/**
 * A simple {@link Fragment} subclass.
 */
public class EqualizerFragment extends Fragment {

    public static final String ARG_AUDIO_SESSIOIN_ID = "audio_session_id";
    private static final String TAG = "EqualizerFragment";

    /**
     * 10 段均衡器频段数量（固定，不再读系统 mEqualizer.getNumberOfBands()）。
     */
    private static final int NUM_BANDS = 10;

    /**
     * 频段电平下限（毫贝 mB，1 dB = 100 mB）。固定值，不再读 mEqualizer.getBandLevelRange()。
     */
    private static final int LOWER_BAND_LEVEL_MB = -1500;

    /**
     * 频段电平上限（毫贝 mB）。固定值。
     */
    private static final int UPPER_BAND_LEVEL_MB = 1500;

    /**
     * 频段标签（固定频点，对应 TenBandEqualizerProcessor.BAND_LABELS）。
     * 与 app 模块保持一致；equalizer 模块不能反向依赖 app，所以在此硬编码。
     */
    private static final String[] BAND_LABELS = {
            "31Hz", "62Hz", "125Hz", "250Hz", "500Hz",
            "1kHz", "2kHz", "4kHz", "8kHz", "16kHz"
    };

    /**
     * 内置 10 段预设（毫贝 mB），索引与 {@link #PRESET_NAMES} 对应。
     * 第 0 项为"自定义"占位（不会被作为预设应用，仅用于 Spinner 显示）。
     * 与 app 模块 TenBandEqualizerProcessor 文档约定的频点表保持一致。
     */
    private static final int[][] PRESET_LEVELS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},                               // 0: 自定义（占位）
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},                               // 1: 正常
            {-100, 200, 400, 500, 100, -100, -100, -100, 0, -100},       // 2: 流行
            {500, 400, 200, -100, -200, 0, 200, 500, 600, 500},           // 3: 摇滚
            {300, 200, 100, 200, -100, -100, 0, 100, 200, 300},          // 4: 爵士
            {400, 300, 200, 0, -100, -100, 0, 200, 300, 400},            // 5: 古典
            {600, 500, 200, 0, 0, -200, -200, 0, 100, 300},              // 6: 舞曲
            {600, 500, 300, 0, -100, -200, 0, 300, 500, 500},            // 7: 重金属
            {500, 400, 100, 200, -100, -100, 0, 100, 200, 300},          // 8: 嘻哈
            {300, 300, 200, 0, -100, -100, 0, 200, 300, 300}             // 9: 民谣
    };

    /**
     * 预设名称列表（Spinner 显示顺序，position 0 = 自定义）。
     */
    private static final String[] PRESET_NAMES = {
            "自定义", "正常", "流行", "摇滚", "爵士", "古典", "舞曲", "重金属", "嘻哈", "民谣"
    };

    static int themeColor = Color.parseColor("#B24242");
    public Equalizer mEqualizer;
    SwitchCompat equalizerSwitch;
    public BassBoost bassBoost;
    LineChartView chart;
    public PresetReverb presetReverb;
    ImageView backBtn;

    int y = 0;

    ImageView spinnerDropDownIcon;
    TextView fragTitle;
    LinearLayout mLinearLayout;

    SeekBar[] seekBarFinal = new SeekBar[NUM_BANDS];

    AnalogController bassController, reverbController;

    ObservableSpinner presetSpinner;

    FrameLayout equalizerBlocker;
    FrameLayout equalizerTouchBlocker;

    Context ctx;

    private boolean customModifyFlag;

    private boolean isAudioEffectsAvailable = false;

    public EqualizerFragment() {
        // Required empty public constructor
    }

    LineSet dataset;
    Paint paint;
    float[] points;
    short numberOfFrequencyBands;
    private int audioSesionId;
    static boolean showBackButton = false;

    public static EqualizerFragment newInstance(int audioSessionId) {

        Bundle args = new Bundle();
        args.putInt(ARG_AUDIO_SESSIOIN_ID, audioSessionId);

        EqualizerFragment fragment = new EqualizerFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Settings.isEditing = true;

        if (Settings.equalizerModel == null) {
            Settings.equalizerModel = new EqualizerModel();
            Settings.equalizerModel.setReverbPreset(PresetReverb.PRESET_NONE);
            Settings.equalizerModel.setBassStrength((short) (1000 / 19));
        }

        // 点击播放时，AudioEffectManager会初始化，new这三个对象
        mEqualizer = AudioEffectManager.getEqualizer();
        bassBoost = AudioEffectManager.getBassBoost();
        presetReverb = AudioEffectManager.getPresetReverb();
        if (mEqualizer == null || bassBoost == null || presetReverb == null) {
            Log.e(TAG, "Audio effects not initialized. Please enable equalizer first.");
            isAudioEffectsAvailable = false;
        } else {
            isAudioEffectsAvailable = true;
        }

        if (isAudioEffectsAvailable && Settings.isEqualizerEnabled) {
            bassBoost.setEnabled(true);
            presetReverb.setEnabled(true);
            mEqualizer.setEnabled(true);
            BassBoost.Settings bassBoostSetting;
            try {
                BassBoost.Settings bassBoostSettingTemp = bassBoost.getProperties();
                bassBoostSetting = new BassBoost.Settings(bassBoostSettingTemp.toString());
            } catch (Exception e) {
                // 上报日志
                Log.e(TAG, "bassBoost.getProperties error:" + e.getMessage());
                bassBoostSetting = new BassBoost.Settings();
            }
            bassBoostSetting.strength = clampBassStrength(Settings.equalizerModel.getBassStrength());
            // 同步回 EqualizerModel，确保后续读取也是合法值（修复 bassStrength=-1 导致的 crash）
            Settings.equalizerModel.setBassStrength(bassBoostSetting.strength);
            try {
                bassBoost.setProperties(bassBoostSetting);
            } catch (RuntimeException e) {
                // setProperties 可能因底层 AudioEffect 状态异常而抛 RuntimeException
                Log.e(TAG, "bassBoost.setProperties failed, strength=" + bassBoostSetting.strength, e);
            }

            try {
                presetReverb.setPreset(clampReverbPreset(Settings.equalizerModel.getReverbPreset()));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid reverb preset value: " + Settings.equalizerModel.getReverbPreset());
                presetReverb.setPreset(PresetReverb.PRESET_NONE);
            }
        } else if (isAudioEffectsAvailable) { // 已调了全局初始化，但是开关没有打开
            bassBoost.setEnabled(false);
            presetReverb.setEnabled(false);
            mEqualizer.setEnabled(false);
        }

        if (isAudioEffectsAvailable) {
            Log.d(TAG, "onCreate: Loading custom preset from persistent storage");
            int pos = Settings.loadPresetPos(ctx);
            if (pos >= 0) {
                Settings.presetPos = pos;
            }
            if (Settings.presetPos == 0) {
                int[] customPreset = Settings.loadCustomPreset(ctx);
                if (customPreset != null) {
                    Settings.seekbarpos = customPreset;
                }
            }
            // 频段电平交由自研 TenBandEqualizerProcessor 处理（通过桥接回调），
            // 系统 mEqualizer 仅作为 audioSession 锚点存在，不再调用其频段 API。
            TenBandEqBridge.applyBandLevels(Settings.seekbarpos);
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        ctx = context;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_equalizer, container, false);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        backBtn = view.findViewById(R.id.equalizer_back_btn);
        backBtn.setVisibility(showBackButton ? View.VISIBLE : View.GONE);
        backBtn.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });

        fragTitle = view.findViewById(R.id.equalizer_fragment_title);


        equalizerSwitch = view.findViewById(R.id.equalizer_switch);
        equalizerTouchBlocker = view.findViewById(R.id.equalizerTouchBlocker);

        if (!isAudioEffectsAvailable) {
            equalizerSwitch.setChecked(false);
            setControlsEnabled(false);
            return;
        }

        equalizerSwitch.setChecked(Settings.isEqualizerEnabled);
        equalizerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mEqualizer.setEnabled(isChecked);
            bassBoost.setEnabled(isChecked);
            presetReverb.setEnabled(isChecked);
            Settings.isEqualizerEnabled = isChecked;
            Settings.equalizerModel.setEqualizerEnabled(isChecked);
            setControlsEnabled(isChecked);
            if (isChecked) {
                // 打开瞬间从 Settings 重新应用全部值：覆盖"开关未开时导入、之后打开开关"的场景，
                // 否则音效参数和 seekbar/旋钮都停留在 onCreate/onViewCreated 时的旧值
                refreshFromSettingsOnEnable();
            }
        });

        setControlsEnabled(Settings.isEqualizerEnabled);

        spinnerDropDownIcon = view.findViewById(R.id.spinner_dropdown_icon);
        spinnerDropDownIcon.setOnClickListener(v -> presetSpinner.performClick());

        presetSpinner = view.findViewById(R.id.equalizer_preset_spinner);

        // 下拉打开时旋转箭头到 -180°（逆时针），收起时旋转回 0°（顺时针）。
        // 打开检测：performClick()；收起检测：onWindowFocusChanged（下拉弹出时窗口失去焦点，
        // 收起时重新获得焦点）
        presetSpinner.setOnDropdownOpenedListener(() -> rotateDropdownIcon(true));
        presetSpinner.setOnDropdownDismissedListener(() -> rotateDropdownIcon(false));

        equalizerBlocker = view.findViewById(R.id.equalizerBlocker);
        equalizerTouchBlocker = view.findViewById(R.id.equalizerTouchBlocker);

        chart = view.findViewById(R.id.lineChart);
        paint = new Paint();
        dataset = new LineSet();

        bassController = view.findViewById(R.id.controllerBass);
        reverbController = view.findViewById(R.id.controller3D);

        bassController.setLabel("低音增强");
        reverbController.setLabel("虚拟音效");

        updateComponentColors(Settings.isEqualizerEnabled);

        int x;
        if (!Settings.isEqualizerReloaded) {
            x = 0;
            if (bassBoost != null) {
                try {
                    x = ((bassBoost.getRoundedStrength() * 19) / 1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (presetReverb != null) {
                try {
                    y = (presetReverb.getPreset() * 19) / 6;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        } else {
            x = ((Settings.bassStrength * 19) / 1000);
            y = (Settings.reverbPreset * 19) / 6;
            int bass = Settings.loadBassProgress(ctx);
            if (bass >= 0) {
                x = bass;
            } else {
                x = -2;  // 指针垂直向下
            }
            int reverb = Settings.loadReverbProgress(ctx);
            if (reverb >= 0) {
                y = reverb;
            } else {
                y = -2;  // 指针垂直向下
            }
        }
        if (x == 0) {
            bassController.setProgress(-2);
        } else {
            bassController.setProgress(x);
        }
        if (y == 0) {
            reverbController.setProgress(-2);
        } else {
            reverbController.setProgress(y);
        }

        bassController.setOnProgressChangedListener(progress -> {
            // progress 可能为负（-2 = 指针垂直向下，效果关闭），换算前归一化为 0，
            // 避免负 strength 传入 BassBoost.setStrength 抛 RuntimeException
            int p = Math.max(progress, 0);
            Settings.bassStrength = (short) (((float) 1000 / 19) * (p));
            try {
                bassBoost.setStrength(Settings.bassStrength);
                Settings.equalizerModel.setBassStrength(Settings.bassStrength);
                Settings.saveBassProgress(ctx, p);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        reverbController.setOnProgressChangedListener(progress -> {
            // 同上：负 progress（垂直向下）归一化为 0 = PRESET_NONE，
            // 持久化保存 0，恢复路径（onViewCreated/refreshFromSettingsOnEnable）会把 0 显示为垂直向下
            int p = Math.max(progress, 0);
            Settings.reverbPreset = (short) ((p * 6) / 19);
            Settings.equalizerModel.setReverbPreset(Settings.reverbPreset);
            try {
                presetReverb.setPreset(Settings.reverbPreset);
                Settings.saveReverbProgress(ctx, p);
            } catch (Exception e) {
                e.printStackTrace();
            }
            y = p;
        });

        mLinearLayout = view.findViewById(R.id.equalizerContainer);

        TextView equalizerHeading = new TextView(getContext());
        equalizerHeading.setText(R.string.eq);
        equalizerHeading.setTextSize(20);
        equalizerHeading.setGravity(Gravity.CENTER_HORIZONTAL);

        numberOfFrequencyBands = (short) NUM_BANDS;

        points = new float[numberOfFrequencyBands];

        if (!isAudioEffectsAvailable) {
            return;
        }

        // 频段电平上下限固定（不再读 mEqualizer.getBandLevelRange()），与自研
        // TenBandEqualizerProcessor 的 -15 ~ +15 dB 范围对应。
        final short lowerEqualizerBandLevel = (short) LOWER_BAND_LEVEL_MB;
        final short upperEqualizerBandLevel = (short) UPPER_BAND_LEVEL_MB;

        float density = getResources().getDisplayMetrics().density;

        for (short i = 0; i < numberOfFrequencyBands; i++) {
            final short equalizerBandIndex = i;

            // 频率标签（固定，不再查系统 mEqualizer.getCenterFreq）
            final String freqLabel = BAND_LABELS[equalizerBandIndex];

            // === FrameLayout（weight=2）===
            FrameLayout frameLayout = new FrameLayout(getContext());
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
            frameLayout.setLayoutParams(frameParams);

            // === 内部 LinearLayout（vertical，marginTop=8dp）===
            LinearLayout innerLayout = new LinearLayout(getContext());
            innerLayout.setOrientation(LinearLayout.VERTICAL);
            FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            innerParams.topMargin = (int) (8 * density);
            innerLayout.setLayoutParams(innerParams);

            // === VerticalSeekBarWrapper（weight=8，clipChildren=false）===
            VerticalSeekBarWrapper wrapper = new VerticalSeekBarWrapper(getContext());
            LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 8f);
            wrapper.setLayoutParams(wrapperParams);
            wrapper.setClipChildren(false);

            // === VerticalSeekBar（marginTop=20dp，padding L10/T10/R4/B10，rotation=CW270）===
            VerticalSeekBar seekBar = new VerticalSeekBar(getContext());
            FrameLayout.LayoutParams seekParams = new FrameLayout.LayoutParams(
                    0, 0);
            seekParams.topMargin = (int) (20 * density);
            seekBar.setLayoutParams(seekParams);
            seekBar.setProgressDrawable(getResources().getDrawable(
                    R.drawable.eq_seekbar, getContext().getTheme()));
            seekBar.setThumb(getResources().getDrawable(
                    R.drawable.custom_equalizer_thumb, getContext().getTheme()));
            seekBar.setRotationAngle(VerticalSeekBar.ROTATION_ANGLE_CW_270);
            int padLeft = (int) (10 * density);
            int padTop = (int) (10 * density);
            int padRight = (int) (4 * density);
            int padBottom = (int) (10 * density);
            seekBar.setPadding(padLeft, padTop, padRight, padBottom);
            wrapper.addView(seekBar);
            innerLayout.addView(wrapper);

            // === 频率标签 TextView（weight=1，textSize=10sp）===
            TextView textView = new TextView(getContext());
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            textView.setLayoutParams(textParams);
            textView.setTextSize(10f);
            innerLayout.addView(textView);

            frameLayout.addView(innerLayout);

            // === 值显示 TextView（覆盖在顶部，gravity=center_horizontal|top，padding=4dp）===
            TextView valueTextView = new TextView(getContext());
            FrameLayout.LayoutParams valueParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valueParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            valueTextView.setLayoutParams(valueParams);
            valueTextView.setGravity(Gravity.CENTER);
            int pad = (int) (4 * density);
            valueTextView.setPadding(pad, pad, pad, pad);
            valueTextView.setTextColor(getResources().getColor(R.color.text_color, getContext().getTheme()));
            valueTextView.setTextSize(10f);
            frameLayout.addView(valueTextView);

            // === 应用样式与配置 ===
            seekBar.getThumb().setColorFilter(new PorterDuffColorFilter(themeColor, PorterDuff.Mode.SRC_IN));
            seekBar.setId(i);
            seekBar.setMax(upperEqualizerBandLevel - lowerEqualizerBandLevel);

            textView.setText(freqLabel);
            textView.setTextColor(getResources().getColor(R.color.text_color, getContext().getTheme()));
            textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);

            seekBarFinal[i] = seekBar;

            if (Settings.isEqualizerReloaded) {
                points[i] = Settings.seekbarpos[i] - lowerEqualizerBandLevel;
                dataset.addPoint(freqLabel, points[i]);
                seekBar.setProgress(Settings.seekbarpos[i] - lowerEqualizerBandLevel);
            } else {
                // 未重载时使用 0 dB 作为默认值（与 TenBandEqualizerProcessor 默认一致）
                points[i] = -lowerEqualizerBandLevel;
                dataset.addPoint(freqLabel, points[i]);
                seekBar.setProgress(-lowerEqualizerBandLevel);
                Settings.seekbarpos[i] = 0;
                Settings.isEqualizerReloaded = true;
            }

            // 顶部显示当前频段增益值（dB）：初始赋值一次，
            // 之后 setProgress（预设切换/导入/刷新）会触发 onProgressChanged 自动更新
            final TextView bandValueTextView = valueTextView;
            bandValueTextView.setText(formatBandLevelDb(seekBar.getProgress() + lowerEqualizerBandLevel));

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    // 更新值显示
                    int mb = progress + lowerEqualizerBandLevel;
                    bandValueTextView.setText(formatBandLevelDb(mb));

                    // 更新图表
                    points[equalizerBandIndex] = progress;
                    dataset.updateValues(points);
                    chart.notifyDataUpdate();

                    // 更新 Settings 与 EqualizerModel
                    Settings.seekbarpos[equalizerBandIndex] = mb;
                    if (Settings.equalizerModel != null
                            && Settings.equalizerModel.getSeekbarpos() != null
                            && equalizerBandIndex < Settings.equalizerModel.getSeekbarpos().length) {
                        Settings.equalizerModel.getSeekbarpos()[equalizerBandIndex] = mb;
                    }

                    // 通知自研 AudioProcessor（替代 mEqualizer.setBandLevel）
                    TenBandEqBridge.applyBandLevels(Settings.seekbarpos);

                    if (fromUser) {
                        customModifyFlag = true;
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    // 切换到自定义预设的时机移到 onStopTrackingTouch，
                    // 避免拖动开始就触发 spinner listener 重置所有频段 UI。
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    // 拖动结束后切换到自定义预设
                    if (presetSpinner != null) {
                        presetSpinner.setSelection(0);
                        Settings.presetPos = 0;
                        Settings.equalizerModel.setPresetPos(0);
                    }
                }
            });

            mLinearLayout.addView(frameLayout);
        }

        equalizeSound();

        paint.setColor(Color.parseColor("#555555"));
        paint.setStrokeWidth((float) (1.10 * Settings.ratio));

        dataset.setColor(themeColor);
        dataset.setSmooth(true);
        dataset.setThickness(5);

        chart.setXAxis(false);
        chart.setYAxis(false);

        chart.setYLabels(AxisController.LabelPosition.NONE);
        chart.setXLabels(AxisController.LabelPosition.NONE);
        chart.setGrid(ChartView.GridType.NONE, 7, 10, paint);

        chart.setAxisBorderValues(-300, 3300);

        chart.addData(dataset);
        chart.show();

        updateComponentColors(Settings.isEqualizerEnabled);

        Button mEndButton = new Button(getContext());
        mEndButton.setBackgroundColor(themeColor);
        mEndButton.setTextColor(Color.WHITE);

        OnBackPressedCallback backCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showSaveEqDialog(true, null);
            }
        };

        // 将回调添加到 Activity 的 Dispatcher 中，并绑定当前 Fragment 的生命周期
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);
    }

    public void showSaveEqDialog(boolean exit, int[] toSavePos) {
        if (!customModifyFlag) {
            if (exit) {
                requireActivity().finish();
            }
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View view = inflater.inflate(R.layout.dialog_save_eq, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(view)
                .setCancelable(false)
                .setOnDismissListener(dialog1 -> {
                    customModifyFlag = false;
                    if (exit) {
                        requireActivity().finish();
                    }
                })
                .create();
        Objects.requireNonNull(dialog.getWindow()).setBackgroundDrawableResource(android.R.color.transparent);
        view.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            saveCustomEq(toSavePos);
            dialog.dismiss();
        });
        android.widget.Button btnCancel = view.findViewById(R.id.btnCancel);
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorSecondary, tv, true);
        btnCancel.setTextColor(tv.resourceId != 0
                ? getResources().getColor(tv.resourceId, requireContext().getTheme())
                : tv.data);
        btnCancel.setOnClickListener(v -> {
            discardEq(exit);
            dialog.dismiss();
        });
        dialog.show();
    }

    private void saveCustomEq(int[] toSavePos) {
        if (toSavePos == null) {
            toSavePos = Settings.seekbarpos.clone();
        }
        // 保存/覆盖自定义存储
        Settings.saveCustomPreset(ctx, toSavePos);
    }

    private void discardEq(boolean exit) {
        // 切换Spinner时放弃修改，不用做处理
        if (!exit) {
            return;
        }
        // 还原音效，只考虑preset=0(!=0表示spinner切换已处理)
        final short lowerEqualizerBandLevel = (short) LOWER_BAND_LEVEL_MB;
        int[] tempSeekbarPos = Settings.loadCustomPreset(ctx);
        int[] seekbarPos = tempSeekbarPos != null ? tempSeekbarPos : Settings.seekbarpos;
        for (short i = 0; i < NUM_BANDS; i++) {
            Settings.seekbarpos[i] = seekbarPos[i];
            if (Settings.equalizerModel != null
                    && Settings.equalizerModel.getSeekbarpos() != null
                    && i < Settings.equalizerModel.getSeekbarpos().length) {
                Settings.equalizerModel.getSeekbarpos()[i] = seekbarPos[i];
            }
            seekBarFinal[i].setProgress(seekbarPos[i] - lowerEqualizerBandLevel);
            points[i] = seekbarPos[i] - lowerEqualizerBandLevel;
        }
        dataset.updateValues(points);
        chart.notifyDataUpdate();
        // 自定义预设：从 Settings 同步到自研 AudioProcessor
        TenBandEqBridge.applyBandLevels(Settings.seekbarpos);
    }

    /**
     * 应用导入的自定义均衡器配置。
     *
     * 行为：
     * 1) 持久化写入 Settings（SharedPreferences）；
     * 2) 同步 Settings 静态字段和 EqualizerModel；
     * 3) 若均衡器开关未打开或音效未就绪：仅写数据，不操作 UI（控件本就被遮罩）；
     *    返回 false；
     * 4) 若均衡器开关已打开：刷新自研 AudioProcessor 的实际音效参数，
     *    切到自定义预设（spinner position=0），并刷新 10 个频段 seekbar、低音/虚拟旋钮、频响曲线；
     *    返回 true。
     *
     * @param bandLevels   10 个频段的电平值（millibels，范围约 -1500 ~ +1500）
     * @param bassStrength 低音强度（0 ~ 1000）
     * @param reverbPreset 虚拟音效预设（0 ~ 6）
     * @return true 表示已应用到运行时音效；false 表示仅写入了持久化数据
     */
    public boolean applyImportedConfig(int[] bandLevels, short bassStrength, short reverbPreset) {
        if (bandLevels == null || bandLevels.length != NUM_BANDS) {
            Log.e(TAG, "applyImportedConfig: invalid bandLevels (len="
                    + (bandLevels == null ? "null" : bandLevels.length) + ")");
            return false;
        }

        // 1. 持久化写入
        int[] toSave = new int[NUM_BANDS];
        System.arraycopy(bandLevels, 0, toSave, 0, NUM_BANDS);
        Settings.saveCustomPreset(ctx, toSave);

        // 反向换算 AnalogController 进度（0~19）用于持久化 + 可视化刷新
        int bassProgress = (int) Math.round(((float) bassStrength) * 19.0 / 1000.0);
        if (bassProgress < 0) bassProgress = 0;
        if (bassProgress > 19) bassProgress = 19;
        Settings.saveBassProgress(ctx, bassProgress);

        int reverbProgress = (int) Math.round(((float) reverbPreset) * 19.0 / 6.0);
        if (reverbProgress < 0) reverbProgress = 0;
        if (reverbProgress > 19) reverbProgress = 19;
        Settings.saveReverbProgress(ctx, reverbProgress);

        // 2. 同步 Settings 静态字段
        for (int i = 0; i < NUM_BANDS; i++) {
            Settings.seekbarpos[i] = toSave[i];
        }
        Settings.bassStrength = bassStrength;
        Settings.reverbPreset = reverbPreset;
        Settings.presetPos = 0;
        Settings.savePresetPos(ctx, 0);

        // 同步 EqualizerModel
        if (Settings.equalizerModel != null) {
            int[] modelSeek = Settings.equalizerModel.getSeekbarpos();
            if (modelSeek != null && modelSeek.length == NUM_BANDS) {
                for (int i = 0; i < NUM_BANDS; i++) {
                    modelSeek[i] = toSave[i];
                }
            }
            Settings.equalizerModel.setBassStrength(bassStrength);
            Settings.equalizerModel.setReverbPreset(reverbPreset);
            Settings.equalizerModel.setPresetPos(0);
        }

        // 3. 开关未打开 或 音效未就绪：仅写数据即可
        if (!isAudioEffectsAvailable ||
                equalizerSwitch == null || !equalizerSwitch.isChecked()) {
            Log.d(TAG, "applyImportedConfig: EQ switch off, persisted only");
            return false;
        }

        // 4. 开关已打开：刷新运行时音效 + UI
        // 4.1 切到自定义（spinner position=0）；若已为 0，listener 不会触发，需手动刷新 seekbar + chart
        if (presetSpinner != null && presetSpinner.getSelectedItemPosition() != 0) {
            // listener 会从 Settings.loadCustomPreset 加载最新持久化的值
            presetSpinner.setSelection(0);
        } else {
            // 已是自定义或 spinner 未就绪，手动刷新 10 频段 seekbar + chart
            refreshBandLevelsInternal(toSave);
        }

        // 4.2 应用低音/虚拟参数到 BassBoost/PresetReverb + 更新 AnalogController 可视化指针
        applyBassAndReverbInternal(bassStrength, bassProgress, reverbPreset, reverbProgress);
        return true;
    }

    /**
     * 内部：刷新 10 频段 seekbar + chart（不依赖 presetSpinner 的 listener）。
     * 参考 {@link #discardEq(boolean)} 的实现。
     */
    private void refreshBandLevelsInternal(int[] bandLevels) {
        final short lowerEqualizerBandLevel = (short) LOWER_BAND_LEVEL_MB;
        for (short i = 0; i < NUM_BANDS; i++) {
            if (seekBarFinal[i] != null) {
                seekBarFinal[i].setProgress(bandLevels[i] - lowerEqualizerBandLevel);
                Settings.seekbarpos[i] = bandLevels[i];
                if (Settings.equalizerModel != null
                        && Settings.equalizerModel.getSeekbarpos() != null
                        && i < Settings.equalizerModel.getSeekbarpos().length) {
                    Settings.equalizerModel.getSeekbarpos()[i] = bandLevels[i];
                }
            }
            points[i] = bandLevels[i] - lowerEqualizerBandLevel;
        }
        if (dataset != null && chart != null) {
            dataset.updateValues(points);
            chart.notifyDataUpdate();
        }
        // 同步到自研 AudioProcessor
        TenBandEqBridge.applyBandLevels(Settings.seekbarpos);
    }

    /**
     * 内部：将低音/虚拟参数应用到 BassBoost/PresetReverb，并更新 AnalogController 可视化指针。
     * AnalogController.setProgress 不会触发其 onProgressChangedListener（listener 仅 touch 时触发），
     * 因此需直接调用底层 API 应用音效参数。
     */
    private void applyBassAndReverbInternal(short bassStrength, int bassProgress,
                                           short reverbPreset, int reverbProgress) {
        // 直接应用到 BassBoost（强度限制在 [0, 1000]）
        short safeBassStrength = clampBassStrength(bassStrength);
        if (bassBoost != null) {
            try {
                BassBoost.Settings bassSetting = bassBoost.getProperties();
                bassSetting.strength = safeBassStrength;
                bassBoost.setProperties(bassSetting);
            } catch (RuntimeException e) {
                Log.e(TAG, "applyBassAndReverb: bassBoost.setProperties failed, strength=" + safeBassStrength, e);
            }
        }
        // 直接应用到 PresetReverb（预设限制在 [0, 6]）
        short safeReverbPreset = clampReverbPreset(reverbPreset);
        if (presetReverb != null) {
            try {
                presetReverb.setPreset(safeReverbPreset);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "applyBassAndReverb: invalid reverb preset " + safeReverbPreset, e);
                presetReverb.setPreset(PresetReverb.PRESET_NONE);
            }
        }
        // AnalogController 仅更新可视化指针位置（其 listener 只在 touch 时触发，不会重复写入）。
        // 进度为 0 时沿用 onViewCreated 的约定：setProgress(-2) 表示指针垂直向下（中立位）。
        if (bassController != null) {
            bassController.setProgress(bassProgress == 0 ? -2 : bassProgress);
            bassController.invalidate();
        }
        if (reverbController != null) {
            reverbController.setProgress(reverbProgress == 0 ? -2 : reverbProgress);
            reverbController.invalidate();
        }
    }

    /**
     * 开关打开时从 Settings 重新应用全部值到音效和控件。
     *
     * 背景：onCreate/onViewCreated 已经用当时的 Settings 值初始化了音效参数和控件，
     * 若用户在开关未打开时执行导入（applyImportedConfig 走"仅持久化"分支），
     * 之后在同一页面打开开关，则必须在此处重新应用，否则：
     * - mEqualizer 各频段电平仍是旧值（onCreate 设置的）
     * - BassBoost/PresetReverb 仍是旧值
     * - seekbar / 低音旋钮 / 虚拟旋钮的 UI 不显示导入的数据
     */
    private void refreshFromSettingsOnEnable() {
        if (!isAudioEffectsAvailable) {
            return;
        }

        // 1) 5 频段：写入 mEqualizer + 刷新 seekbar + 频响曲线
        refreshBandLevelsInternal(Settings.seekbarpos);

        // 2) 预设选择恢复为 Settings.presetPos（保留关闭前的预设）
        //    - 导入场景：applyImportedConfig 已把 presetPos 写为 0（自定义），此处恢复为 0
        //    - 普通开关场景：保留关闭前的预设（如"流行"），不强制改为自定义
        //    若当前 spinner 位置已与 presetPos 一致，不触发 listener（避免重复加载）
        if (presetSpinner != null && presetSpinner.getSelectedItemPosition() != Settings.presetPos) {
            presetSpinner.setSelection(Settings.presetPos);
        }

        // 3) 低音：优先用持久化的旋钮进度，缺失时从 Settings.bassStrength 反推
        int bassProgress = Settings.loadBassProgress(ctx);
        if (bassProgress < 0) {
            bassProgress = (int) Math.round(((float) Settings.bassStrength) * 19.0 / 1000.0);
        }
        if (bassProgress < 0) bassProgress = 0;
        if (bassProgress > 19) bassProgress = 19;
        short bassStrength = (short) Math.round(((float) bassProgress) * 1000.0 / 19.0);

        // 4) 虚拟：优先用持久化的旋钮进度，缺失时从 Settings.reverbPreset 反推
        int reverbProgress = Settings.loadReverbProgress(ctx);
        if (reverbProgress < 0) {
            reverbProgress = (int) Math.round(((float) clampReverbPreset(Settings.reverbPreset)) * 19.0 / 6.0);
        }
        if (reverbProgress < 0) reverbProgress = 0;
        if (reverbProgress > 19) reverbProgress = 19;
        short reverbPreset = (short) ((reverbProgress * 6) / 19);

        // 5) 将最终应用的值同步回 Settings / EqualizerModel / 持久化，保持三方一致
        Settings.bassStrength = bassStrength;
        if (Settings.equalizerModel != null) {
            Settings.equalizerModel.setBassStrength(bassStrength);
            Settings.equalizerModel.setReverbPreset(reverbPreset);
        }
        Settings.saveBassProgress(ctx, bassProgress);
        Settings.reverbPreset = reverbPreset;
        Settings.saveReverbProgress(ctx, reverbProgress);

        // 6) 应用到 BassBoost/PresetReverb + 刷新旋钮指针
        applyBassAndReverbInternal(bassStrength, bassProgress, reverbPreset, reverbProgress);
    }

    /**
     * 将 BassBoost 强度限制在系统合法范围 [0, 1000] 内。
     * 越界值（如 EqualizerModel 默认构造的 -1）会被映射为安全默认值 0，
     * 防止 {@link BassBoost#setProperties(BassBoost.Settings)} 抛 RuntimeException。
     */
    private static short clampBassStrength(short value) {
        if (value < 0 || value > 1000) {
            return 0;
        }
        return value;
    }

    /**
     * 将 PresetReverb 预设值限制在系统合法范围 [0, 6] 内。
     * 越界值（如 EqualizerModel 默认构造的 -1）会被映射为 PRESET_NONE(0)。
     */
    private static short clampReverbPreset(short value) {
        if (value < 0 || value > 6) {
            return PresetReverb.PRESET_NONE;
        }
        return value;
    }

    /**
     * 将频段电平（毫贝）格式化为带符号的 dB 字符串，如 "+2dB"、"0dB"、"-3dB"。
     */
    private static String formatBandLevelDb(int levelMb) {
        int db = levelMb / 100;
        return (db > 0 ? "+" : "") + db + "dB";
    }

    public void equalizeSound() {
        if (!isAudioEffectsAvailable) {
            return;
        }

        ArrayList<String> equalizerPresetNames = new ArrayList<>();
        ArrayAdapter<String> equalizerPresetSpinnerAdapter = new ArrayAdapter<String>(ctx, R.layout.spinner_item, equalizerPresetNames) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    int color;
                    if (Settings.isEqualizerEnabled) {
                        TypedValue typedValue = new TypedValue();
                        requireActivity().getTheme().resolveAttribute(
                                com.google.android.material.R.attr.colorPrimary, typedValue, true);
                        color = typedValue.resourceId != 0
                                ? getResources().getColor(typedValue.resourceId, requireActivity().getTheme())
                                : typedValue.data;
                    } else {
                        color = getResources().getColor(R.color.eq_disable_color, requireActivity().getTheme());
                    }
                    ((TextView) view).setTextColor(color);
                }
                return view;
            }
        };
        equalizerPresetSpinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        // 使用内置 10 段预设名（不再读 mEqualizer.getNumberOfPresets / getPresetName）
        equalizerPresetNames.addAll(Arrays.asList(PRESET_NAMES));

        presetSpinner.setAdapter(equalizerPresetSpinnerAdapter);
        //presetSpinner.setDropDownWidth((Settings.screen_width * 3) / 4);
        presetSpinner.setDropDownVerticalOffset(108);
        if (Settings.isEqualizerReloaded) {
            if (Settings.presetPos == 0) {
                final short lowerEqualizerBandLevel = (short) LOWER_BAND_LEVEL_MB;
                for (short i = 0; i < NUM_BANDS; i++) {
                    seekBarFinal[i].setProgress(Settings.seekbarpos[i] - lowerEqualizerBandLevel);
                    points[i] = Settings.seekbarpos[i] - lowerEqualizerBandLevel;
                }
                dataset.updateValues(points);
                chart.notifyDataUpdate();
                // 自定义预设：从 Settings 同步到自研 AudioProcessor
                TenBandEqBridge.applyBandLevels(Settings.seekbarpos);
            } else if (Settings.presetPos > 0 && Settings.presetPos < PRESET_LEVELS.length) {
                presetSpinner.setSelection(Settings.presetPos);
                applyPresetLevels(Settings.presetPos);
            }
        }

        updateComponentColors(Settings.isEqualizerEnabled);

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d(TAG, "onItemSelected: position=" + position);
                Log.d(TAG, "Settings.seekbarpos BEFORE: " + Arrays.toString(Settings.seekbarpos));
                try {
                    if (position != 0) {
                        // 切换前是自定义，保存
                        if (Settings.presetPos == 0) {
                            Log.d(TAG, "Saving current settings before preset");
                            int[] existPos = Settings.loadCustomPreset(ctx);
                            if (existPos == null) {
                                Settings.saveCustomPreset(ctx, Settings.seekbarpos.clone());
                            } else {
                                showSaveEqDialog(false, Settings.seekbarpos.clone());
                            }
                        }

                        // 应用内置预设到 seekbar + Settings + 自研 AudioProcessor
                        applyPresetLevels(position);
                    } else {
                        // 自定义：从持久化加载
                        Log.d(TAG, "Position is 0 (Custom), restoring from persistent storage");
                        final short lowerEqualizerBandLevel = (short) LOWER_BAND_LEVEL_MB;
                        int[] tempSeekbarPos = Settings.loadCustomPreset(ctx);
                        int[] seekbarPos = tempSeekbarPos != null ? tempSeekbarPos : Settings.seekbarpos;
                        for (short i = 0; i < NUM_BANDS; i++) {
                            Log.d(TAG, "  Band " + i + ": " + seekbarPos[i]);
                            Settings.seekbarpos[i] = seekbarPos[i];
                            if (Settings.equalizerModel != null
                                    && Settings.equalizerModel.getSeekbarpos() != null
                                    && i < Settings.equalizerModel.getSeekbarpos().length) {
                                Settings.equalizerModel.getSeekbarpos()[i] = seekbarPos[i];
                            }
                            seekBarFinal[i].setProgress(seekbarPos[i] - lowerEqualizerBandLevel);
                            points[i] = seekbarPos[i] - lowerEqualizerBandLevel;
                        }
                        dataset.updateValues(points);
                        chart.notifyDataUpdate();
                        // 自定义预设：从 Settings 同步到自研 AudioProcessor
                        TenBandEqBridge.applyBandLevels(Settings.seekbarpos);
                    }
                    Settings.presetPos = position;
                    Settings.savePresetPos(ctx, position);
                    Log.d(TAG, "Settings.seekbarpos AFTER: " + Arrays.toString(Settings.seekbarpos));
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(ctx, "Error while updating Equalizer", Toast.LENGTH_SHORT).show();
                }
                Settings.equalizerModel.setPresetPos(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    /**
     * 把内置预设（{@link #PRESET_LEVELS}[position]）应用到 seekbar / Settings / EqualizerModel / 自研 AudioProcessor。
     * 调用方需保证 position 在 [1, PRESET_LEVELS.length-1] 范围内（0 是自定义，不走此方法）。
     */
    private void applyPresetLevels(int position) {
        if (position <= 0 || position >= PRESET_LEVELS.length) {
            Log.w(TAG, "applyPresetLevels: invalid position=" + position);
            return;
        }
        final short lowerEqualizerBandLevel = (short) LOWER_BAND_LEVEL_MB;
        int[] levels = PRESET_LEVELS[position];
        for (short i = 0; i < NUM_BANDS; i++) {
            int mb = levels[i];
            Settings.seekbarpos[i] = mb;
            if (Settings.equalizerModel != null
                    && Settings.equalizerModel.getSeekbarpos() != null
                    && i < Settings.equalizerModel.getSeekbarpos().length) {
                Settings.equalizerModel.getSeekbarpos()[i] = mb;
            }
            seekBarFinal[i].setProgress(mb - lowerEqualizerBandLevel);
            points[i] = mb - lowerEqualizerBandLevel;
        }
        dataset.updateValues(points);
        chart.notifyDataUpdate();
        // 通知自研 AudioProcessor
        TenBandEqBridge.applyBandLevels(Settings.seekbarpos);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (equalizerSwitch != null) {
            boolean isEnabled = equalizerSwitch.isChecked();
            setControlsEnabled(isEnabled);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        if (!isAudioEffectsAvailable || equalizerSwitch == null) {
            return;
        }

        boolean isEnabled = Settings.isEqualizerEnabled;
        equalizerSwitch.setChecked(isEnabled);
        setControlsEnabled(isEnabled);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 配置变更（如黑白切换）导致的重建不保存，仅退出时保存
        if (Settings.presetPos == 0 && !requireActivity().isChangingConfigurations()) {
            Log.d(TAG, "Saving current settings before exit");
            Settings.saveCustomPreset(ctx, Settings.seekbarpos.clone());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Settings.isEditing = false;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    private void setControlsEnabled(boolean enabled) {
        if (equalizerTouchBlocker != null) {
            if (enabled) {
                equalizerTouchBlocker.setVisibility(View.INVISIBLE);
            } else {
                equalizerTouchBlocker.setVisibility(View.VISIBLE);
            }
        }

        if (presetSpinner != null) {
            presetSpinner.setEnabled(enabled);
        }

        if (spinnerDropDownIcon != null) {
            spinnerDropDownIcon.setEnabled(enabled);
        }

        if (chart != null) {
            chart.setEnabled(enabled);
        }

        if (bassController != null) {
            bassController.setEnabled(enabled);
        }

        if (reverbController != null) {
            reverbController.setEnabled(enabled);
        }

        for (SeekBar seekBar : seekBarFinal) {
            if (seekBar != null) {
                seekBar.setEnabled(enabled);
            }
        }

        updateComponentColors(enabled);
    }

    /**
     * 旋转 Spinner 下拉箭头：展开时逆时针旋转 180°（rotation: 0 → -180），
     * 收起时顺时针旋转复位（rotation: -180 → 0）。
     * 使用同一个箭头图片，仅靠 View 的 rotation 属性实现方向变化。
     */
    private void rotateDropdownIcon(boolean open) {
        if (spinnerDropDownIcon == null) return;
        float target = open ? -180f : 0f;
        spinnerDropDownIcon.animate()
                .rotation(target)
                .setDuration(200)
                .start();
    }

    private void updateComponentColors(boolean enabled) {
        int color;
        if (enabled) {
            TypedValue typedValue = new TypedValue();
            requireActivity().getTheme().resolveAttribute(
                    com.google.android.material.R.attr.colorPrimary, typedValue, true);
            if (typedValue.resourceId != 0) {
                color = getResources().getColor(typedValue.resourceId, requireActivity().getTheme());
            } else {
                color = typedValue.data;
            }
        } else {
            color = getResources().getColor(R.color.eq_disable_color, getContext().getTheme());
        }

        if (dataset != null) {
            dataset.setColor(color);
        }
        if (chart != null) {
            chart.notifyDataUpdate();
        }

        for (SeekBar seekBar : seekBarFinal) {
            if (seekBar != null) {
                seekBar.getThumb().setColorFilter(
                        new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
        }

        if (bassController != null) {
            bassController.circlePaint2.setColor(color);
            bassController.linePaint.setColor(color);
            bassController.invalidate();
        }
        if (reverbController != null) {
            reverbController.circlePaint2.setColor(color);
            reverbController.linePaint.setColor(color);
            reverbController.invalidate();
        }

        if (spinnerDropDownIcon != null) {
            spinnerDropDownIcon.setColorFilter(color);
        }
        if (presetSpinner != null) {
            View selectedView = presetSpinner.getSelectedView();
            if (selectedView instanceof TextView) {
                ((TextView) selectedView).setTextColor(color);
            }
        }
    }

    public static class Builder {
        private int id = -1;

        public Builder setAudioSessionId(int id) {
            this.id = id;
            return this;
        }

        public Builder setAccentColor(int color) {
            themeColor = color;
            return this;
        }

        public Builder setShowBackButton(boolean show) {
            showBackButton = show;
            return this;
        }

        public EqualizerFragment build() {
            return EqualizerFragment.newInstance(id);
        }
    }


}
