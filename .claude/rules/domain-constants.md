# 领域常量

## 音质分类

MusicRepository 根据比特率分类音质：

- **SQ** (超品质): 无损格式（FLAC、WAV、ALAC、APE、DSD）
- **HQ** (高品质): MP3 ≥320kbps 或 AAC/OGG ≥256kbps
- **STD** (标准): 128kbps ≤ 比特率 < 320kbps
- **ORD** (普通): 比特率 < 128kbps
- **UNK** (未知): 无法确定比特率

## 播放模式

MusicService 定义 `PlayMode` 枚举：

```kotlin
enum class PlayMode {
    ALL_LOOP,       // 全部循环
    SINGLE_LOOP,    // 单曲循环
    RANDOM,         // 随机播放
    SEQUENCE        // 顺序播放（最后一首自然播放完毕后停止）
}
```

## 通知动作常量

- `ACTION_PLAY` = `"com.unicorn.player.action.PLAY"`
- `ACTION_PAUSE` = `"com.unicorn.player.action.PAUSE"`
- `ACTION_NEXT` = `"com.unicorn.player.action.NEXT"`
- `ACTION_PREVIOUS` = `"com.unicorn.player.action.PREVIOUS"`
- `ACTION_STOP` = `"com.unicorn.player.action.STOP"`
