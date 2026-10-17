# 均衡器"虚拟音效"软件环绕实现计划

## Context

均衡器页面的"虚拟音效"旋钮（混响预设 0-6）目前走系统 `PresetReverb`，在 OPPO PENM00 + 蓝牙 A2DP 上被厂商混音策略旁路，**始终无声**。酷狗音乐在同一设备上环绕音效可生效，说明其原理是 **App 内软件 DSP**（人工混响/去相关展宽），不依赖平台音效——与我们已自研的 10 段 EQ + 低音低架同一路数。

目标：在 `TenBandEqualizerProcessor` 中新增软件环绕（FDN 人工混响）级，旋钮 0-6 预设映射到不同房间/厅堂/板式混响参数，全设备生效；同时强制禁用系统 PresetReverb 避免双份混响。

**约束**：均衡器 UI 已外置为库 `com.github.kent109:AndroidEqualizer:1.0.0`（本工作区不可改）。库的 `Settings.reverbPreset` 静态字段在旋钮拖动时**即时更新**（`applyBassAndReverbInternal` 路径），且库暴露 `AudioEffectManager.getPresetReverb()` 公共静态方法——App 侧可用"轮询 + 强制 disable"闭环，无需库变更。

## 一、处理器 DSP（核心）

文件：`app/src/main/java/com/unicorn/player/equalizer/TenBandEqualizerProcessor.kt`

新增软件混响级，插在**低音低架之后、preGain 之前**（湿信号能量计入限幅/余量）。仅 `channelCount == 2` 时启用，其余直通。

### 算法：FDN-lite 人工混响
- **输入**：mid = (L+R)/2，经 pre-delay 环形缓冲（预设相关 4-30ms）
- **4 条反馈梳状滤波器**（非互质延迟 ~23.7/28.1/35.3/43.1ms）：反馈增益由 RT60 换算 `g = 10^(-3·delay/RT60)`；环路内一极点数通低通做高频阻尼（plate 阻尼小=亮）
- **2 级串联全通扩散器**（~5.2ms / ~1.7ms，系数 0.5）
- **湿输出**：不同梳状组合 + 交替符号 → L/R 去相关尾音；湿路 ~200Hz 高通防低频糊
- **混合**：`out = dry + wet·wetLevel`，wetLevel/RT60/preDelay 按预设取值

### 预设映射表（index 0-6，与 PresetReverb 语义对齐）
| 预设 | RT60 | preDelay | wet | 阻尼 |
|---|---|---|---|---|
| 0 NONE | — | — | 0（旁通） | — |
| 1 SMALLROOM | 0.35s | 5ms | 0.14 | 中 |
| 2 MEDIUMROOM | 0.55s | 10ms | 0.18 | 中 |
| 3 LARGEROOM | 0.8s | 15ms | 0.22 | 中 |
| 4 MEDIUMHALL | 1.2s | 22ms | 0.26 | 大 |
| 5 LARGEHALL | 1.8s | 30ms | 0.30 | 大 |
| 6 PLATE | 1.4s | 4ms | 0.26 | 小（亮） |

### 参数变更平滑
复用现有模式：`surroundPresetPending/Active/Changed`（volatile + lock，`setSurroundPreset(0..6)` 任意线程可调）；队列消费时 wetLevel/反馈增益走 `COEFF_RAMP_FRAMES`（46ms）线性渐变，避免切预设时的"咔哒"。

### 与余量/限幅协同
`recalculateCoefficients()` 中当 surround 激活时，`peakDb += 20·log10(1 + wetLevel)`（湿能量上界），再进 `PROGRAM_HEADROOM_DB` 折中公式——预设切换的响度差自动走已有 800ms smoothstep 长渐变。

### 诊断日志
`Configured` 与 `EQ params changed mid-stream` 行追加 `surround=N`。

## 二、下发链路

### MusicService.kt
- `applyEqProcessorLevels()`：开关开 → `eqProcessor.setSurroundPreset(Settings.reverbPreset)`；关 → `setSurroundPreset(0)`
- 新增公共方法 `applySurroundPreset(preset: Int)`：写 `Settings.reverbPreset` 语义由库负责，本方法只做下发 + 落盘（供轮询器调用）
- **强制禁用系统混响**：`initializeAudioEffects()` 与 `syncSystemEffectsEnableState()` 末尾追加 `AudioEffectManager.getPresetReverb()?.setEnabled(false)`（软件环绕取代之，防止其他机型双份混响）

### EqualizerActivity.kt（实时值传递）
- 库 UI 无回调钩子 → 在 `onStart`（service 已绑定时）启动 250ms Handler 轮询：读 `Settings.reverbPreset`，与上次下发值 diff → 调 `musicService.applySurroundPreset(preset)`；`onStop` 移除回调
- 轮询同时兜底再 disable 一次 PresetReverb（库 Fragment 旋钮交互会重新 enable 它，窗口 ≤250ms，且该手机上本就无声）
- 离散 0-6 旋钮 + 46ms 处理器内部渐变 → 轮询延迟听感不可察

### 持久化/导入导出
无需改动：`REVERB_PRESET` DataStore 键、`EqualizerConfig.reverbPreset` 导出字段、`restoreEqualizerSettings()` 冷启动恢复均已存在，新链路自动受益。

## 三、文档

`app/src/main/res/raw/manual.md`（L229/L237）：更新"虚拟音效"说明——软件实现全设备生效，删除"某些设备上可能不明显"旁路警告。

## 四、验证

1. `gradle app:assembleDebug --console=plain`（先 CRLF 归一化，bareLF=0）
2. 真机（蓝牙耳机）：开 EQ → 播放 → 旋钮切 1-6，应听到房间/厅堂/板式尾音差异；切回 0 尾音消失
3. 日志核对：`Configured: ... surround=N`、`EQ params changed mid-stream ... surround=N`、无 `AudioEffect: enable`（PresetReverb）残留生效
4. 回归：频段/低音调节、开关切换 800ms 渐变、削波 stats `clippedSamples=0`、`limitedFrames` 不显著增长
5. 导出配置含 reverbPreset、导入后环绕即时生效（轮询捕获）

## 关键文件

- `app/src/main/java/com/unicorn/player/equalizer/TenBandEqualizerProcessor.kt`（DSP + API）
- `app/src/main/java/com/unicorn/player/service/MusicService.kt`（下发 + 禁用系统混响）
- `app/src/main/java/com/unicorn/player/EqualizerActivity.kt`（轮询桥）
- `app/src/main/res/raw/manual.md`（文档）

## 后续可选（不在本次范围）

若效果满意，可在库仓库给 `TenBandEqBridge` 加 `SurroundApplier`（模式同 `BassStrengthApplier`），发布 1.0.1 后移除 App 侧轮询，实现真正零延迟回调。
