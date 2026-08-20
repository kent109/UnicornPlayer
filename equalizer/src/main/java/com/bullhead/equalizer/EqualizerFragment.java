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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
            BassBoost.Settings bassBoostSettingTemp = bassBoost.getProperties();
            BassBoost.Settings bassBoostSetting = new BassBoost.Settings(bassBoostSettingTemp.toString());
            bassBoostSetting.strength = Settings.equalizerModel.getBassStrength();
            bassBoost.setProperties(bassBoostSetting);
            try {
                presetReverb.setPreset(Settings.equalizerModel.getReverbPreset());
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
            }
            int reverb = Settings.loadReverbProgress(ctx);
            if (reverb >= 0) {
                y = reverb;
            }
        }
        if (x == 0) {
            bassController.setProgress(1);
        } else {
            bassController.setProgress(x);
        }
        if (y == 0) {
            reverbController.setProgress(1);
        } else {
            reverbController.setProgress(y);
        }

        bassController.setOnProgressChangedListener(progress -> {
            Settings.bassStrength = (short) (((float) 1000 / 19) * (progress));
            try {
                bassBoost.setStrength(Settings.bassStrength);
                Settings.equalizerModel.setBassStrength(Settings.bassStrength);
                Settings.saveBassProgress(ctx, progress);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        reverbController.setOnProgressChangedListener(progress -> {
            Settings.reverbPreset = (short) ((progress * 6) / 19);
            Settings.equalizerModel.setReverbPreset(Settings.reverbPreset);
            try {
                presetReverb.setPreset(Settings.reverbPreset);
                Settings.saveReverbProgress(ctx, progress);
            } catch (Exception e) {
                e.printStackTrace();
            }
            y = progress;
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
                                Settings.saveCustomPreset(ctx, Settings.seekbarpos.clone());
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
