---
name: saf-content-export
description: 在 UnicornPlayer 中为某类数据新增基于 SAF 的导出/导入功能，包括文件管理器、导出预检、同名冲突与数量上限弹窗。用户要求实现导出、导入、批量导出或导出文件管理时使用。不适用于仅保存到 DataStore/Room 的本地状态持久化。
---

# SAF 内容导出 / 导入实施套路

本技能总结 UnicornPlayer 中已经验证的导出实现（均衡器配置、歌单导出/导入）。
权威参考实现：

- `app/src/main/java/com/unicorn/player/util/EqualizerConfigManager.kt`
- `app/src/main/java/com/unicorn/player/util/PlaylistFileManager.kt`
- `app/src/main/java/com/unicorn/player/ui/PlaylistExportCleanupDialog.kt`（清理弹窗 + 同名冲突弹窗）
- `app/src/main/java/com/unicorn/player/EqualizerActivity.kt`（Activity 宿主的 launcher 模式）
- `app/src/main/java/com/unicorn/player/ui/PlaylistFragment.kt`（Fragment 宿主的 launcher 模式）

开始前先把对应参考文件读一遍，按下面步骤替换为目标数据的命名。

## 0. 先与用户确认的决策点

1. 导出目录名（如 `Unicorn/Playlist`）；文件名规则（优先用稳定 ID，不要用用户可改的名称）。
2. 导出内容的匹配键：MediaStore `_ID` 在文件重新拷入后会变化，**跨设备/重扫场景一律用文件路径 `Song.path`**；不要把 title/artist/duration 落文件（用户可改标签，导入时查库取最新值）。
3. 数量上限：**最多 10 个导出文件**，达到后先弹清理弹窗；覆盖同一对象的旧文件不占名额。
4. 同名冲突（文件名键不同但展示名相同，常见于清除应用数据后重建）：弹「合并 / 覆盖」选择弹窗，结果一律以当前对象 ID 重新保存。
5. 空集合（如空歌单）允许导出与导入；重复导出直接覆盖。
6. 删除源对象（单个 + 批量）时同步删除导出文件。
7. 所有成功 / 失败都 Toast；批量给汇总（`成功 N 个，失败 M 个`）。

## 1. 文件管理器 object

在 `util/` 下新建 `<Xxx>FileManager.kt`，整体照抄 `PlaylistFileManager`，只改三处：

- 目录常量（`UNICORN_DIR` 固定为 `Unicorn`，子目录改为目标名）
- `PREFS_NAME`（独立 prefs，如 `playlist_file_prefs`）
- 文件名规则（`fileNameFor...`）

必须保留的关键行为：

- 权限三级优先：`LyricsSaveManager` 的树 URI（`hasSavedTreeUri` + `isTreePermissionValid`）→ 自管 prefs 树 URI（校验 `persistedUriPermissions`）→ 无权限由调用方引导 `OpenDocumentTree`。
- `saveTreeUri` 中 `takePersistableUriPermission` 同时要 READ + WRITE。
- 所有操作通过 `DocumentFile.fromTreeUri` 逐级 `findFile` / `createDirectory`，禁止直接拼 document URI（否则触发 MANAGE_DOCUMENTS SecurityException）。
- 写文件：先删旧文件（覆盖），再 `createFile("application/octet-stream", name)`，不能用 `text/plain`（会被追加 `.txt`）。
- 常量 `const val MAX_EXPORT_COUNT = 10`。
- 两个删除 API：`deleteFile(context, fileName)`（清理弹窗用）与按业务键删除的 `deleteExport(context, id)`（后者委托前者）；文件不存在视为成功。

## 2. 数据层（本项目特有约束）

**本项目 Room + KAPT 不支持 `suspend` DAO 方法**（编译报 "abstract member cannot be accessed directly"）。DAO 一律写同步函数 / Flow，由 Repository 用 `withContext(Dispatchers.IO)` 包装。既有 DAO 全部是这个模式，新增方法保持一致。

- 路径 / 名称查询示例（DAO 同步签名）：

```kotlin
@Query("SELECT * FROM songs WHERE path IN (:paths)")
fun getSongsByPaths(paths: List<String>): List<Song>

@Query("SELECT * FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
fun findPlaylistByName(name: String): Playlist?
```

- Repository 转发时：路径先 `filter { it.isNotBlank() }.distinct()`，再 **按 900 一批 `chunked` 分片查询**后合并，规避 SQLite 参数上限。
- 只加查询、不改 Room schema（不升版本）。
- **删除收口**：在 Repository 的 `deleteXxxById` 内，删除库记录成功后追加 `XxxFileManager.deleteExport(context, id)`，无授权 / 无文件静默成功。单个删除与批量删除都走该方法，两个入口自动同步删文件。

## 3. ViewModel：预检 + 导出 + 导入

全部 IO 操作放 `viewModelScope.launch(Dispatchers.IO)`，回调统一切回 `Dispatchers.Main`。

### 3.1 预检（一次扫描同时算出冲突与上限）

定义数据类：

```kotlin
data class ExportConflict(val id: Long, val name: String, val fileNames: List<String>)
data class ExportPrecheck(
    val conflicts: List<ExportConflict>,
    val projectedFileCount: Int,   // 冲突文件删除 + 新文件写入后的预计总数
    val overLimit: Boolean
)
```

算法（见 `PlaylistViewModel.buildExportPrecheck` / `isConflictFile`）：

1. 列出全部导出文件并解析（解析结果缓存复用，避免合并时重复读盘）。
2. 对每个待导出 id，同名（trim + NOCASE）文件按下面规则判冲突：
   - 文件内业务 ID 与当前对象不同 → 冲突；
   - 业务 ID 相同但 `exportedAt < 当前对象.createdAt`（时间倒挂）→ **同样冲突**。
     清除应用数据后自增主键从 1 重新开始，重建的同名对象会与旧文件撞 ID，
     仅靠 ID 无法区分「同一对象重复导出」；导出时间早于对象创建时间是此时
     唯一的判别信号。注意导出 JSON 必须包含 `exportedAt`，解析侧加 `> 0` 守卫。
3. 自身文件（同业务键）存在且非冲突 → 覆盖，数量不变；
   否则预计总数 `projected += 1 - 冲突文件数`（冲突文件无论覆盖/合并都会被删；
   撞 ID 时冲突文件就是自身，净增 0）。
4. `overLimit = projected > MAX_EXPORT_COUNT`。

### 3.2 导出执行

`exportXxx(ids, mergeConflicts: Boolean = false, onResult)`：

- 数据以 repository 实时查询为准（含被 UI 隐藏/过滤的项），不要用界面缓存列表。
- 合并模式：当前路径 + 冲突文件路径取并集（`filter { it.isNotBlank() }.distinct()`）；
  覆盖模式：仅当前数据。
- 两种模式都先删除全部冲突文件，再以**当前业务键**文件名写入。
- 统计成功 / 失败，回主线程回调。

### 3.3 Gson 注意事项（已踩坑）

- 空集合合法：解析校验**不能**写 `data.items.isNullOrEmpty()`，否则空歌单文件被跳过、
  导入列表看不到。只校验 version、业务 ID、name。
- Gson 反序列化绕过构造函数，字段缺失时集合运行时为 null（默认值不生效），
  解析后统一规范化：`data.copy(items = data.items ?: emptyList())`。
- JSON 顶层带 `version` 字段，解析时以文件内容中的业务 ID 为准，不信任文件名。

### 3.4 导入

- 目标对象判定顺序：业务 ID 命中 → 合并；否则同名（trim + NOCASE）命中 → 合并；
  都没有 → 新建（空集合也允许新建空对象）。
- 文件路径经 `getSongsByPaths` 映射为库内对象，匹配不到的丢弃并计数，
  回调把丢弃数告诉 UI 做 Toast。
- 只对库中尚不存在的项调用批量插入（REPLACE 天然去重），实际有新增才更新时间。
- 结束后强制刷新 + 通知刷新器（如 `PlaylistRefresher.notifyPlaylistsChanged()`）。

## 4. UI 宿主

### 4.1 SAF launcher + pendingAction（Fragment / Activity 通用）

宿主内维护枚举挂起态（如 `NONE / EXPORT_ONE / IMPORT`）和挂起参数：

```kotlin
private val openDocumentTreeLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { treeUri ->
    if (treeUri != null) {
        XxxFileManager.saveTreeUri(requireContext(), treeUri)
        ensurePermissionAndDir(pendingAction, pendingParam)
    } else {
        // 用户取消：清挂起态 + 恢复被置灰的入口按钮
    }
}
```

`ensurePermissionAndDir`：无权限 → `launch(XxxFileManager.getInitialUri())`；
有权限 → IO 内 `ensureSaveDirExists`，成功后按挂起态分流（预检导出 / 弹导入窗），
失败 Toast「授权目录创建失败」。

### 4.2 交互规则

- FAB / 批量导出按钮：点击立即 `isEnabled = false`，**每个结束分支**（成功、失败、取消授权、取消弹窗）都必须恢复可用。item 滑开按钮触发后滑开项立即合拢，不禁用。
- 批量导出完成后退出多选界面；取消同名处理时停留多选页并恢复按钮（选中保留）。
- 导入弹窗参考 `dialog_playlist_import.xml` + `SelectableImportPlaylistAdapter`：
  整行切换 CheckBox（CheckBox 自身 `clickable=false`），空列表也弹窗但「确定」禁用。

### 4.3 弹窗流程顺序（单个 / 批量一致）

授权建目录 → 预检：

1. 有冲突 → 同名冲突弹窗（合并 / 覆盖 / 取消）；
2. 再判 `overLimit` → 清理弹窗（删除后不自动继续，用户需重新点导出）；
3. 都通过 → 执行导出。

两个弹窗均为公共组件，单个导出（Fragment）与批量导出（Activity）共用，
宿主差异通过传入 `Context` 与调用方 `CoroutineScope`（`lifecycleScope` /
`viewLifecycleOwner.lifecycleScope`）消除。清理弹窗 item 只显示对象名，
重名项追加编号（`流行`、`流行(2)`）以保证删除映射唯一。

所有弹窗（包括只有标题/消息/按钮的确认类弹窗）都用自定义布局，
**不要直接用系统 `AlertDialog.setTitle/setMessage/setXxxButton`**（默认背景不是
项目的圆角风格）：根布局 `android:background="@drawable/bg_select_songs_dialog"`，
代码里 `dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)`，
`setOnCancelListener` 兜底返回键 / 点外部关闭时的取消回调。

## 5. 项目硬约束（违反必返工）

- 所有新建 / 修改的文本文件必须 **CRLF**：Write 工具落盘后立即执行
  `python` 转换：读 bytes → `.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")` 回写，
  并打印 bare LF 数确认是 0。
- Windows 构建用 `gradle`（不是 `./gradlew`）。
- 若 Kotlin daemon 被沙箱拦截（Could not connect to Kotlin compile daemon），
  PowerShell 下用引号传参：
  `gradle "-Dkotlin.compiler.execution.strategy=in-process" app:assembleDebug`。
- 验证：`gradle app:assembleDebug`（含 KAPT + 资源处理）；包名 `com.unicorn.player`，
  ViewBinding，不引入新依赖（Gson、DocumentFile 已在项目中）。
- 手册：若功能面向用户，在 `app/src/main/res/raw/manual.md` 对应章节补充
  （覆盖导出、空集合、上限清理、同名合并/覆盖、删除同步删文件）。

## 6. 交付前自测矩阵

- 首次使用：无权限 → SAF 授权 → 自动建 `Unicorn/<子目录>` → 导出成功。
- 重复导出同一对象：覆盖，文件数不变；导出后改对象名再导出（键仍是 ID 时文件名不变）。
- 空对象导出后，导入弹窗中可见该项；导入后得到空对象。
- 导出满 10 个后导出第 11 个：清理弹窗 → 删除 → 重新点导出成功。
- 同名冲突：导出 → 清除应用数据 → 新建同名 → 再导出，弹窗出现；
  合并结果含并集且只剩当前 ID 一个文件；覆盖只含当前数据；取消中止且批量按钮恢复。
- 删除对象（单个、批量）后对应导出文件消失；导入时不存在的路径被丢弃并有提示。
