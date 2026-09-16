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
import android.widget.Spinner;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;


/**
 * A simple {@link Fragment} subclass.
 */
public class EqualizerFragment extends Fragment {

    public static final String ARG_AUDIO_SESSIOIN_ID = "audio_session_id";
    private static final String TAG = "EqualizerFragment";

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

    SeekBar[] seekBarFinal = new SeekBar[5];

    AnalogController bassController, reverbController;

    Spinner presetSpinner;

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
            for (short bandIdx = 0; bandIdx < mEqualizer.getNumberOfBands(); bandIdx++) {
                mEqualizer.setBandLevel(bandIdx, (short) Settings.seekbarpos[bandIdx]);
            }
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

        updateSpinnerIconColor();

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

        updateSpinnerIconColor();

        presetSpinner = view.findViewById(R.id.equalizer_preset_spinner);

        equalizerBlocker = view.findViewById(R.id.equalizerBlocker);
        equalizerTouchBlocker = view.findViewById(R.id.equalizerTouchBlocker);

        chart = view.findViewById(R.id.lineChart);
        paint = new Paint();
        dataset = new LineSet();

        bassController = view.findViewById(R.id.controllerBass);
        reverbController = view.findViewById(R.id.controller3D);

        bassController.setLabel("低音增强");
        reverbController.setLabel("虚拟音效");

        bassController.circlePaint2.setColor(themeColor);
        bassController.linePaint.setColor(themeColor);
        bassController.invalidate();
        reverbController.circlePaint2.setColor(themeColor);
        bassController.linePaint.setColor(themeColor);
        reverbController.invalidate();

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

        numberOfFrequencyBands = 5;

        points = new float[numberOfFrequencyBands];

        if (!isAudioEffectsAvailable) {
            return;
        }

        final short lowerEqualizerBandLevel = mEqualizer.getBandLevelRange()[0];
        final short upperEqualizerBandLevel = mEqualizer.getBandLevelRange()[1];

        for (short i = 0; i < numberOfFrequencyBands; i++) {
            final short equalizerBandIndex = i;
            final TextView frequencyHeaderTextView = new TextView(getContext());
            frequencyHeaderTextView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            frequencyHeaderTextView.setGravity(Gravity.CENTER_HORIZONTAL);
            frequencyHeaderTextView.setTextColor(Color.parseColor("#FFFFFF"));
            int freq = mEqualizer.getCenterFreq(equalizerBandIndex) / 1000;
            String k = "";
            if (freq >= 1000) {
                freq /= 1000;
                k = "k";
            }
            frequencyHeaderTextView.setText(freq + k + "Hz");

            LinearLayout seekBarRowLayout = new LinearLayout(getContext());
            seekBarRowLayout.setOrientation(LinearLayout.VERTICAL);

            TextView lowerEqualizerBandLevelTextView = new TextView(getContext());
            lowerEqualizerBandLevelTextView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            lowerEqualizerBandLevelTextView.setTextColor(Color.parseColor("#FFFFFF"));
            lowerEqualizerBandLevelTextView.setText((lowerEqualizerBandLevel / 100) + "dB");

            TextView upperEqualizerBandLevelTextView = new TextView(getContext());
            lowerEqualizerBandLevelTextView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            upperEqualizerBandLevelTextView.setTextColor(Color.parseColor("#FFFFFF"));
            upperEqualizerBandLevelTextView.setText((upperEqualizerBandLevel / 100) + "dB");

            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layoutParams.weight = 1;

            SeekBar seekBar = new SeekBar(getContext());
            TextView textView = new TextView(getContext());
            switch (i) {
                case 0:
                    seekBar = view.findViewById(R.id.seekBar1);
                    textView = view.findViewById(R.id.textView1);
                    break;
                case 1:
                    seekBar = view.findViewById(R.id.seekBar2);
                    textView = view.findViewById(R.id.textView2);
                    break;
                case 2:
                    seekBar = view.findViewById(R.id.seekBar3);
                    textView = view.findViewById(R.id.textView3);
                    break;
                case 3:
                    seekBar = view.findViewById(R.id.seekBar4);
                    textView = view.findViewById(R.id.textView4);
                    break;
                case 4:
                    seekBar = view.findViewById(R.id.seekBar5);
                    textView = view.findViewById(R.id.textView5);
                    break;
            }
            seekBarFinal[i] = seekBar;
            seekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(Color.DKGRAY, PorterDuff.Mode.SRC_IN));
            seekBar.getThumb().setColorFilter(new PorterDuffColorFilter(themeColor, PorterDuff.Mode.SRC_IN));
            seekBar.setId(i);
//            seekBar.setLayoutParams(layoutParams);
            seekBar.setMax(upperEqualizerBandLevel - lowerEqualizerBandLevel);

            textView.setText(frequencyHeaderTextView.getText());
            textView.setTextColor(getResources().getColor(R.color.text_color, getContext().getTheme()));
            textView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);

            if (Settings.isEqualizerReloaded) {
                points[i] = Settings.seekbarpos[i] - lowerEqualizerBandLevel;
                dataset.addPoint(frequencyHeaderTextView.getText().toString(), points[i]);
                seekBar.setProgress(Settings.seekbarpos[i] - lowerEqualizerBandLevel);
            } else {
                points[i] = mEqualizer.getBandLevel(equalizerBandIndex) - lowerEqualizerBandLevel;
                dataset.addPoint(frequencyHeaderTextView.getText().toString(), points[i]);
                seekBar.setProgress(mEqualizer.getBandLevel(equalizerBandIndex) - lowerEqualizerBandLevel);
                Settings.seekbarpos[i] = mEqualizer.getBandLevel(equalizerBandIndex);
                Settings.isEqualizerReloaded = true;
            }

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    mEqualizer.setBandLevel(equalizerBandIndex, (short) (progress + lowerEqualizerBandLevel));
                    points[seekBar.getId()] = mEqualizer.getBandLevel(equalizerBandIndex) - lowerEqualizerBandLevel;
                    Settings.seekbarpos[seekBar.getId()] = (progress + lowerEqualizerBandLevel);
                    Settings.equalizerModel.getSeekbarpos()[seekBar.getId()] = (progress + lowerEqualizerBandLevel);
                    dataset.updateValues(points);
                    chart.notifyDataUpdate();
                    if (fromUser) {
                        customModifyFlag = true;
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    if (isAudioEffectsAvailable && presetSpinner != null) {
                        presetSpinner.setSelection(0);
                        Settings.presetPos = 0;
                        Settings.equalizerModel.setPresetPos(0);
                    }
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {

                }
            });
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
        view.findViewById(R.id.btnCancel).setOnClickListener(v -> {
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
        final short numberOfFreqBands = 5;
        final short lowerEqualizerBandLevel = mEqualizer.getBandLevelRange()[0];
        int[] tempSeekbarPos = Settings.loadCustomPreset(ctx);
        int[] seekbarPos = tempSeekbarPos != null ? tempSeekbarPos : Settings.seekbarpos;
        for (short i = 0; i < numberOfFreqBands; i++) {
            mEqualizer.setBandLevel(i, (short) seekbarPos[i]);
            seekBarFinal[i].setProgress(mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel);
            Settings.seekbarpos[seekBarFinal[i].getId()] = seekbarPos[i];
            Settings.equalizerModel.getSeekbarpos()[seekBarFinal[i].getId()] = seekbarPos[i];
            points[i] = mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel;
        }
        dataset.updateValues(points);
        chart.notifyDataUpdate();
    }

    /**
     * 应用导入的自定义均衡器配置。
     *
     * 行为：
     * 1) 持久化写入 Settings（SharedPreferences）；
     * 2) 同步 Settings 静态字段和 EqualizerModel；
     * 3) 若均衡器开关未打开或音效未就绪：仅写数据，不操作 UI（控件本就被遮罩）；
     *    返回 false；
     * 4) 若均衡器开关已打开：刷新 mEqualizer / BassBoost / PresetReverb 的实际音效参数，
     *    切到自定义预设（spinner position=0），并刷新 5 个频段 seekbar、低音/虚拟旋钮、频响曲线；
     *    返回 true。
     *
     * @param bandLevels   5 个频段的电平值（millibels，范围约 -1500 ~ +1500）
     * @param bassStrength 低音强度（0 ~ 1000）
     * @param reverbPreset 虚拟音效预设（0 ~ 6）
     * @return true 表示已应用到运行时音效；false 表示仅写入了持久化数据
     */
    public boolean applyImportedConfig(int[] bandLevels, short bassStrength, short reverbPreset) {
        if (bandLevels == null || bandLevels.length < 5) {
            Log.e(TAG, "applyImportedConfig: invalid bandLevels");
            return false;
        }

        // 1. 持久化写入
        int[] toSave = new int[5];
        System.arraycopy(bandLevels, 0, toSave, 0, 5);
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
        for (int i = 0; i < 5; i++) {
            Settings.seekbarpos[i] = toSave[i];
        }
        Settings.bassStrength = bassStrength;
        Settings.reverbPreset = reverbPreset;
        Settings.presetPos = 0;
        Settings.savePresetPos(ctx, 0);

        // 同步 EqualizerModel
        if (Settings.equalizerModel != null) {
            int[] modelSeek = Settings.equalizerModel.getSeekbarpos();
            if (modelSeek != null && modelSeek.length >= 5) {
                for (int i = 0; i < 5; i++) {
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
            // 已是自定义或 spinner 未就绪，手动刷新 5 频段 seekbar + chart
            refreshBandLevelsInternal(toSave);
        }

        // 4.2 应用低音/虚拟参数到 BassBoost/PresetReverb + 更新 AnalogController 可视化指针
        applyBassAndReverbInternal(bassStrength, bassProgress, reverbPreset, reverbProgress);
        return true;
    }

    /**
     * 内部：刷新 5 频段 seekbar + chart（不依赖 presetSpinner 的 listener）。
     * 参考 {@link #discardEq(boolean)} 的实现。
     */
    private void refreshBandLevelsInternal(int[] bandLevels) {
        final short numberOfFreqBands = 5;
        final short lowerEqualizerBandLevel = mEqualizer.getBandLevelRange()[0];
        for (short i = 0; i < numberOfFreqBands; i++) {
            mEqualizer.setBandLevel(i, (short) bandLevels[i]);
            if (seekBarFinal[i] != null) {
                seekBarFinal[i].setProgress(mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel);
                int id = seekBarFinal[i].getId();
                if (id >= 0 && id < 5) {
                    Settings.seekbarpos[id] = bandLevels[i];
                    if (Settings.equalizerModel != null &&
                            Settings.equalizerModel.getSeekbarpos() != null &&
                            id < Settings.equalizerModel.getSeekbarpos().length) {
                        Settings.equalizerModel.getSeekbarpos()[id] = bandLevels[i];
                    }
                }
            }
            points[i] = mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel;
        }
        if (dataset != null && chart != null) {
            dataset.updateValues(points);
            chart.notifyDataUpdate();
        }
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

        // 2) 预设选择同步到"自定义"（导入写入的就是自定义预设）
        //    若已为 0 则不触发 listener；若不为 0，listener 异步回调 position=0 分支，
        //    会再次从持久化存储加载同一份自定义数据（幂等，无副作用）
        if (presetSpinner != null && presetSpinner.getSelectedItemPosition() != 0) {
            presetSpinner.setSelection(0);
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

    public void equalizeSound() {
        if (!isAudioEffectsAvailable) {
            return;
        }

        ArrayList<String> equalizerPresetNames = new ArrayList<>();
        ArrayAdapter<String> equalizerPresetSpinnerAdapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, equalizerPresetNames);
        equalizerPresetSpinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        equalizerPresetNames.add("自定义");
        for (short i = 0; i < mEqualizer.getNumberOfPresets(); i++) {
            String name = mEqualizer.getPresetName(i).toLowerCase(Locale.getDefault());
            name = name.replace("normal", "正常");
            name = name.replace("classical", "古典");
            name = name.replace("dance", "舞曲");
            name = name.replace("flat", "平直");
            name = name.replace("folk", "民谣");
            name = name.replace("heavy metal", "重金属");
            name = name.replace("hip hop", "嘻哈");
            name = name.replace("jazz", "爵士");
            name = name.replace("pop", "流行");
            name = name.replace("rock", "摇滚");
            equalizerPresetNames.add(name);
        }

        presetSpinner.setAdapter(equalizerPresetSpinnerAdapter);
        //presetSpinner.setDropDownWidth((Settings.screen_width * 3) / 4);
        presetSpinner.setDropDownVerticalOffset(108);
        if (Settings.isEqualizerReloaded) {
            if (Settings.presetPos == 0) {
                final short numberOfFreqBands = 5;
                final short lowerEqualizerBandLevel = mEqualizer.getBandLevelRange()[0];
                for (short i = 0; i < numberOfFreqBands; i++) {
                    mEqualizer.setBandLevel(i, (short) Settings.seekbarpos[i]);
                    seekBarFinal[i].setProgress(mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel);
                    points[i] = mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel;
                }
                dataset.updateValues(points);
                chart.notifyDataUpdate();
            } else {
                presetSpinner.setSelection(Settings.presetPos);
                mEqualizer.usePreset((short) (Settings.presetPos - 1));
            }
        }

        updateSpinnerIconColor();

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d(TAG, "onItemSelected: position=" + position);
                Log.d(TAG, "Settings.seekbarpos BEFORE: " + Arrays.toString(Settings.seekbarpos));
                try {
                    Log.d(TAG, "onItemSelected: position=" + position);
                    if (position != 0) {
                        short numberOfPresets = mEqualizer.getNumberOfPresets();
                        short presetIndex = (short) (position - 1);
                        if (presetIndex >= 0 && presetIndex < numberOfPresets && isAudioEffectsAvailable) {
                            final short numberOfFreqBands = 5;
                            final short lowerEqualizerBandLevel = mEqualizer.getBandLevelRange()[0];

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

                            Log.d(TAG, "Loading preset: " + presetIndex);
                            mEqualizer.usePreset(presetIndex);
                            Settings.presetPos = position;

                            Log.d(TAG, "Updating UI after preset");
                            for (short i = 0; i < numberOfFreqBands; i++) {
                                seekBarFinal[i].setProgress(mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel);
                                points[i] = mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel;
                                Settings.equalizerModel.getSeekbarpos()[i] = mEqualizer.getBandLevel(i);
                            }
                            dataset.updateValues(points);
                            chart.notifyDataUpdate();
                        }
                    } else {
                        Log.d(TAG, "Position is 0 (Custom), restoring from persistent storage");
                        final short numberOfFreqBands = 5;
                        final short lowerEqualizerBandLevel = mEqualizer.getBandLevelRange()[0];
                        int[] tempSeekbarPos = Settings.loadCustomPreset(ctx);
                        int[] seekbarPos = tempSeekbarPos != null ? tempSeekbarPos : Settings.seekbarpos;
                        for (short i = 0; i < numberOfFreqBands; i++) {
                            Log.d(TAG, "  Band " + i + ": " + seekbarPos[i]);
                            mEqualizer.setBandLevel(i, (short) seekbarPos[i]);
                            Log.d(TAG, "  After setBandLevel Band " + i + ": " + mEqualizer.getBandLevel(i));
                            seekBarFinal[i].setProgress(mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel);
                            points[i] = mEqualizer.getBandLevel(i) - lowerEqualizerBandLevel;
                        }
                        dataset.updateValues(points);
                        chart.notifyDataUpdate();
                    }
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
        // 退出前是自定义，保存
        if (Settings.presetPos == 0) {
            Log.d(TAG, "Saving current settings before exit");
            Settings.saveCustomPreset(ctx, Settings.seekbarpos.clone());
        }
    }

    private void updateSpinnerIconColor() {
        if (spinnerDropDownIcon != null) {
            boolean isDarkMode = getResources().getBoolean(R.bool.is_dark_mode);
            spinnerDropDownIcon.setColorFilter(isDarkMode ? Color.WHITE : Color.BLACK);
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
