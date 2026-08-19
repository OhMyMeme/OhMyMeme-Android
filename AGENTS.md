# OhMyMeme Android — AI Agent Guide

## 项目概述
桌面端表情包管理系统（OhMyMeme）的安卓版。存储结构、数据库 schema、导入/扫描/缩略图命名规则与桌面端 `https://github.com/OhMyMeme/OhMyMeme` 完全一致，便于多端同步。

## 架构
```
MainActivity / SettingsActivity
        │ 调用
MemeDb (SQLite WAL) ──► files/memes.db
StoragePaths          ──► config:Android/data/com.ohmymeme.app/  localdata:files/
ConfigStore + CryptoUtil ──► config.json（密钥 AES-GCM 加密）
    CacheScanner / MemeImporter / Thumbnailer ──► data/cache/、data/thumbnails/
    CloudSync ──► 远端 memes/ + meme-index.json（FTP/S3/R2/WebDAV）
```

## 技术栈
- **Kotlin** + AppCompat + RecyclerView + ConstraintLayout（无 Compose）
- **AGP 9.0.0** + Gradle 9.1，依赖用 `gradle/libs.versions.toml` 版本目录管理
- **SQLite** (WAL)，schema 与桌面端 `src/database.py` 一致
- **Android Keystore** (AES-GCM) 加密配置密钥字段
- **SAF**（Storage Access Framework）批量导入，免存储权限
- minSdk 28 / targetSdk 36 / compileSdk 36

## 核心原则
- **不重构桌面端** — 桌面端 `若用户提供了桌面端本地源码位置` 仅做最小必要修改；如需同步桌面端数据层逻辑，以 `database.py`/`config.py`/`webui.py` 为唯一事实来源
- **存储结构对齐桌面端** — 表 schema、列名、重命名规则、缩略图命名、去重逻辑逐条对照，不得随意改动
- **增改同步** — 新功能/新文件必须同步更新 `README.md` 和 `AGENTS.md`
- **无 emoji**（除非用户要求）
- **代码风格** — 无冗余注释；单线程 Executor 跑数据库/IO，`runOnUiThread` 回主线程更新 UI；Kotlin 按语言惯例写类型标注
- 使用中文回答用户的问题（除非用户要求其他语言）

## 关键目录
```
app/src/main/
  java/com/ohmymeme/app/
    MainActivity.kt     # 主界面：导入/刷新/搜索/网格 + 空状态
    SettingsActivity.kt # 设置页：loadConfig/saveConfig/reset 接真实配置
    ChipAdapter.kt      # 分组胶囊适配器（仅 COLLECTION 样式）
    MemeGridAdapter.kt  # 表情网格，异步缩略图加载，按 meme.id 打 tag 防错位；整理模式勾选态
    Meme.kt             # 数据模型（对应 memes 表，含 stego/fromStego 字段）
    MemeDb.kt           # SQLite 封装（7 表 + 索引 + 列迁移）
    ConfigStore.kt      # JSON 配置（DEFAULTS 与桌面端 config.py 一致）
    CryptoUtil.kt       # Android Keystore AES-GCM 加解密
    StoragePaths.kt     # 路径解析（base/data/cache/thumbnails/db/config + SAF 树持久化）
    StorFile.kt         # 统一文件句柄：SAF content URI / 真实路径 双模式读写 cache/thumbnails
    FileUtils.kt        # SHA-256 + 魔数识别扩展名
    CacheScanner.kt     # 缓存扫描（双重去重）
    MemeImporter.kt     # SAF 批量导入（含 20MiB/2560px 上限，ImportOutcome/ImportResult）
    SidebarTreeAdapter.kt # 分组树侧栏适配器（CollectionEntry/CollectionNode/SidebarRow 平铺树）
    GifFrameDecoder.kt  # 自研最小 GIF 解码器（LZW/interlace/色板，与 Pillow 一致）
    GifEncoder.kt       # 自研最小 GIF 编码器（median cut 256 色 + LZW，与 GifFrameDecoder 严格对应）
    GifStego.kt         # STG3 隐写检测 + 7 模式解码 + encode 写入（FULL/LZMA/WebP 候选）+ 自研 PNG 编码
    AndroidGifDecoder.kt# 设备端 WebP→RGBA（反预乘 alpha）
    Thumbnailer.kt      # 缩略图生成 {id}_{size}.png
    MemeCopyProcessor.kt# 复制处理：分享前按 copy_resize_mode 缩放 WebP / 转 GIF / 转隐写 GIF
    CloudSync.kt        # 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单）
    LanClient.kt        # 局域网互联客户端（UDP 发现 + TCP 握手 + AES-GCM 会话）
    UpdateChecker.kt    # 版本更新检查（GitHub Releases API）
    QuickTileService.kt # 控制中心快捷磁贴（TileService，点击打开主界面）
  res/
    layout/activity_main.xml / activity_settings.xml / item_* / dialog_tag_editor.xml / dialog_add_collection.xml
    values/colors.xml   # 暗色配色（bg #0D0D0F、card #1E1E22、accent #3B82F6、muted #71717A）
    values/themes.xml   # Theme.OhMyMeme（含 values-night）
    values/strings.xml  # 含 copy_mode_options / sync_type_options / s3_addressing_options / s3_signature_options
```

## 存储布局（与桌面端对应）
```
Android/data/com.ohmymeme.app/
├── config.json                       ← 桌面端 %APPDATA%/OhMyMeme/config.json
└── files/                            ← localdata（对应桌面端 %LOCALAPPDATA%/OhMyMeme）
    ├── memes.db                      ← 始终在真实路径，不随 SAF 存储位置迁移
    ├── cache/                        ← 导入原图，命名 {sha256前16位}{ext}（默认位置）
    └── thumbnails/                   ← {meme_id}_{size}.png（默认位置）
```
- 用户可在首次运行或设置页经 SAF（`ACTION_OPEN_DOCUMENT_TREE`）选择数据位置：
  - 选中后 `StoragePaths.persistDataTree` 取持久化读写权限并写入探针校验
  - `cache/` 与 `thumbnails/` 通过 `StorFile` 抽象读写（content URI / 真实路径双模式），共享目录在作用域存储下只能走 content URI
  - `memes.db` 与云端临时清单始终放在 `dataDir()`（真实路径，`KEY_DATA_DIR`），SAF 只存 `KEY_DATA_TREE`，切换时不清除 `KEY_DATA_DIR`
  - 转移数据只拷贝 `cache`/`thumbnails` 两个子目录（绝不拷贝 memes.db），成功后删除源子树

## 关键实现细节

### 数据库（MemeDb.kt）
- 7 表：`memes`/`tags`/`meme_tags`/`collections`/`meme_collections`/`favorites`/`recent_uses`，字段与桌面端 `database.py` 逐列一致
- `PRAGMA`：`enableWriteAheadLogging()` 替代桌面端 `journal_mode=WAL`；外键依赖 ON DELETE CASCADE
- `getCollectionDepth` 在安卓上按 `pid == 0L` 判根（SQLite parent_id 无 NULL 时以 0 存储）
- 列迁移：与桌面端相同的 `ALTER TABLE ... ADD COLUMN` 容错迁移
- 单例：`MemeDb.get(context)`，用 `applicationContext` 防泄漏

### 配置（ConfigStore.kt）
- `DEFAULTS` 逐字段照搬桌面端 `config.py`（含 `s3_path`、`webdav_timeout`、`record_recent_use=true`、`s3_addressing_style="virtual"`、`s3_signature_version="s3"` 等）
- `SECRET_KEYS` 6 个密钥字段（s3_access_key/s3_secret_key/r2_access_key_id/r2_secret_access_key/ftp_password/webdav_password）写入前加密、读取后解密
- `load()` 在读取时对密钥字段先解密；`save()` 加密副本后写盘；损坏文件回退默认值；**首次运行文件不存在时自动落盘默认配置**
- 与桌面端差异：桌面端 Fernet，安卓端用 Android Keystore（硬件背书），格式不互通但字段名一致

### 首次运行存储位置 / 修改存储位置
- `StoragePaths.isFirstRun` 检测（SharedPreferences 标记 `setup_done`），首次启动弹窗二选一：默认位置（应用专属外部目录）或「选择其他位置」（SAF `ACTION_OPEN_DOCUMENT_TREE`）
- 选中后 `StoragePaths.persistDataTree`（`takePersistableUriPermission` + 探针校验，失败即拒绝）→ `setDataTree` 持久化 `KEY_DATA_TREE`；`describeDataLocation` 用 `resolveTreeUriPath` 把树 URI 解析为真实路径仅作展示（`primary:`→外部存储根，`home:`→Downloads），解析失败显示 URI
- 选完位置后 `markSetupDone` + `ConfigStore.invalidate` 再加载数据
- 设置页修改位置：`onStorageDirPicked` 同样先 `persistDataTree`，弹窗询问是否转移，`moveDataToTree` 用 `StorFile` 只拷贝 `cache`/`thumbnails` 两个子目录（绝不拷贝 memes.db），成功后删除源子树并 `applyStorageTree`

### 导入（MemeImporter.kt）
- 点击标题栏「导入」弹 `menu_import.xml` 菜单：从文件导入（`pickImages`，SAF `ACTION_OPEN_DOCUMENT` 多选）/ 从手机相册导入（`pickAlbumImages`：`isPhotoPickerAvailable` 为真走 Photo Picker `PickMultipleVisualMedia`，否则回退 `ACTION_GET_CONTENT` 相册，避免库默认回退文件选择器）/ 从手机QQ缓存导入（占位 Toast「开发中，后续用 Shizuku 获取文件」）
- 两种导入共用 `doImport(uris)` → `MemeImporter.importUris`：逐文件：查哈希去重 → 魔数识别扩展名 → 拷贝到 `cache/{hash16}{ext}` → 读尺寸 → `addMeme`
- **导入上限**（对齐桌面端 `config.py` `_IMPORT_MAX_BYTES`/`_IMPORT_MAX_PX`）：单文件 >20MiB（`MAX_BYTES`）或任一边 >2560px（`MAX_PX`）拒绝导入并计入 rejected（`ImportOutcome` 枚举：`OVER_LIMIT`/`INVALID`/`FAILED`/`DUPLICATE`/`OK`，`ImportResult` 汇总 imported/rejected/skipped/errors）；局域网拉取与云端 pull 复用 `MAX_BYTES` 校验
- 单文件失败不影响其余文件（catch 后继续），结束 Toast 汇总成功/跳过/超限/失败数（`import_rejected`/`import_errors`）
- **隐写 GIF 解码**（对应桌面端 `_try_decode_stego` + `gif_stego.py`）：GIF 且含 `STG3` 时 `GifStego.decode` 还原原图（7 种模式），只导入还原结果 `fromStego=1`，GIF 本身不入库；WebP 模式经 `AndroidGifDecoder.webpToRgba` 解码（反预乘 alpha）
- `GifFrameDecoder` 为自研最小 GIF 解码器（GIF87a/89a、全局/局部色板、LZW、interlace、透明索引直映射 RGB、Pillow 一致的 `(R*299+G*587+B*114+500)/1000` 灰度），与 Pillow 逐字节一致；LZMA 用 `org.tukaani:xz`（`XZInputStream`）；PNG 输出用自研 RGBA 编码器
- `CacheScanner` 不做 STG3 检测（对齐桌面端 `scan_cache`）

### 缓存扫描（CacheScanner.kt）
- 遍历 cache 目录：跳过非图片扩展名、`thumbnails` 路径、与同名 `.webp` 共存的 `.gif`
- **双重去重**：`getByFilename` 跳过已注册 → SHA-256 → `getByHash` 跳过重复内容
- 与桌面端 `scan_cache` 逻辑一致

### 缩略图（Thumbnailer.kt）
- 命名 `{meme_id}_{size}.png`，存在即复用（与桌面端一致）
- `findMemeFile` 先查缓存根目录，再递归遍历，返回 `StorFile`（对应桌面端 `_find_meme_file`）
- `getThumbBitmap(context, memeId, filename, size)` 读现有缩略图或生成：BitmapFactory `inSampleSize` 先按 2×size 降采样，再 createScaledBitmap 到 150×150，保存 PNG；缓存/缩略图均经 `StorFile` 读写（SAF content URI 或真实路径）

### 网格加载（MemeGridAdapter.kt）
- 单线程 Executor 后台生成/解码缩略图，`img.tag = meme.id` 防列表复用错位
- 占位图用 `ic_photo` + muted 色，加载后 `setColorFilter(null)` + `setImageTintList(null)` 清色/清 XML tint
- 名称取 `original_name`，为空回退文件名去扩展名
- **动图渲染**（对应桌面端 webui `m.is_animated && m.auto_play_gif`）：后台判 `isAnimatedFile`（`FileUtils.isAnimatedFile`：GIF89a 头或 RIFF+WEBP+ANIM；webp 直查 cache 根避免全目录遍历），且 `ConfigStore` 的 `auto_play_gif` 为 true 时用 `ImageDecoder` + `AnimatedImageDrawable`（`setTargetSampleSize` 目标 300）播放原图，否则用静态缩略图；动画解码失败回退缩略图
- 右上角 badge：GIF / WebP（动图）/ 隐写导入（`fromStego==1`），`bg_badge.xml` 蓝底圆角
- 卡片主体的长按回调 `onLongClick` 由 MainActivity 弹 PopupMenu；主体点击继续分享

### 长按右键菜单（MainActivity.kt）
- `menu/menu_meme.xml`：重命名 / 收藏（标题随状态切换）/ 打标签 / 添加分组 / 加入小分组（仅查看具体分组时显示）/ 从分组移除 / 从最近使用中删除 / 删除（红），对齐桌面端 webui 右键菜单
- 「添加分组」走两段式 `promptAddCollection`（对齐桌面端 CollectionBuilder 的精简版）：`dialog_add_collection.xml` 输入新分组名创建，或从「加入已有分组」列表（仅 id>0 真实分组，`item_add_collection_row.xml`）点选即加入
- 删除走 `deleteMemeFiles`：删物理文件（`Thumbnailer.findMemeFile`）+ 删 `{id}_*.png` 缩略图 + `deleteMeme` 删库，与桌面端一致
- 设置页用 Activity Result API（`registerForActivityResult`，`settingsLauncher`）打开，返回 `RESULT_OK` 后 `reloadData()` 使配置（如动图开关）立即生效；导入/选目录/设置统一走 `ActivityResultContracts.StartActivityForResult`

### 分组胶囊过滤（MainActivity.kt）
- 顶栏标签行（`rv_tags`）点击标签过滤表情：多选叠加（`activeTags`，全含匹配 `memeIdsWithAllTags`），再次点击取消；选中态由 `ChipAdapter.activeItems` 控制（accent 色 + active 背景）
- 分组/收藏/未分类过滤走左侧常驻侧栏（`rv_sidebar`，`SidebarTreeAdapter`，`SidebarRow(entry,depth,expanded)` 平铺树，箭头点击展开收起、整行点击单选切换 `activeCollectionId`，默认收起），分组单选切换，再次点击取消
- `ChipAdapter` 泛型化（TAG 用 `String`，COLLECTION 用 `CollectionEntry(id,name,count,hasChildren)`），分组胶囊带数量，label 显示 `名称 (count)`；有子分组时追加 `▼`
- 系统分组：收藏夹 `-2`（`favoriteOnly`）、最近使用 `-3`（`getRecent`）、未分类 `-4`（`uncategorizedOnly`，受 `ConfigStore` 的 `show_uncategorized` 控制且仅在计数 > 0 时显示，对齐桌面端 `webui.py` 系统分组），与桌面端 `get_collections` 一致
- 过滤与关键词叠加后走 `MemeDb.search(keyword, tags, collectionId, favoriteOnly, offset, limit)`；收藏夹走 `favoriteOnly`，最近使用走 `getRecent`，无过滤时 `getAll`。`collectionId != null` 时 ORDER BY 按 `meme_collections.sort_order`（子查询）排序，与桌面端分组内排序一致

### 标签系统（MainActivity.kt + MemeDb.kt）
- 长按菜单「打标签」（`act_tag`）→ `promptEditTags(meme)`：`dialog_tag_editor.xml`（输入框 + `rv_tag_list` + 已选摘要），输入框实时过滤已有标签（`getAllTags`），点选/取消多选，回车把当前输入文本作为新标签加入，保存走 `setMemeTags`
- `setMemeTags` 重写后新增孤儿标签清理（`DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tag_id FROM meme_tags)`），对齐桌面端 `set_meme_tags` 的清理逻辑
- 标签行过滤与分组/关键词叠加，全含匹配（`memeIdsWithAllTags(tags)` 逐标签 INTERSECT），对齐桌面端 `search_memes` 的 `tags` 参数

### 整理模式（MainActivity.kt + MemeGridAdapter.kt）
- 顶栏排序图标（`btn_sort_mode`，`ic_sort`，contentDescription「整理模式」）进入**整理模式（多选批量删除）**，对齐桌面端多选操作栏；与拖拽排序互斥（进入一个自动退出另一个）
- 整理模式下：卡片显示勾选徽标（`tv_select_check`，`bg_select_check` 圆底 ✓），点击卡片切换选中（不走分享/记录最近使用），菜单按钮隐藏、拖拽手柄隐藏；底部操作栏 `manage_bar`（「已选 n 项 / 全选 / 取消 / 批量删除」，网格底部 padding 加大防遮挡）
- 批量删除：`MemeGridAdapter.itemsByIds(ids)` 取 Meme → `deleteMemeFiles` 删物理文件与缩略图 → `MemeDb.deleteMemes(ids)` 单事务批量删（`id IN (...)`，外键 ON DELETE CASCADE 清理关联表 + 孤儿标签清理），对齐桌面端 `delete_memes`
- 拖拽排序入口移至「更多」菜单 `act_toggle_drag_sort`（`toggleDragSort`），门控条件不变（`canOrderCards`）

### 小分组（子分组）
- 对齐桌面端 `create_subcollection` + webui 顶栏：**仅 1 层**——`getCollectionDepth(parentId) >= 1` 时拒绝创建并提示「最多支持1层小分组」
- 顶栏分组胶囊按 `MemeDb.getCollections()` 构建树（`buildTree`），仅当父分组激活或在激活路径（`computeActivePath` 向上追溯祖先）时 `flatten` 展开其子分组胶囊，与桌面端 `renderCollections` 的 `parentActive || activeCollection===c.id || activePath.has(c.id)` 逻辑一致
- 长按顶栏分组胶囊 → `showCollectionMenu` 弹 PopupMenu（`menu_collection.xml`）：普通分组（id>0）含「新建小分组 / 重命名分组 / 删除分组」；最近使用（id=-3）含「清空最近使用」；系统分组（id=-2）与 id<=0 长按无反应
  - 重命名走 `promptRenameCollection` → `MemeDb.renameCollection`
  - 删除走 `promptDeleteCollection`：先查父分组，`search(collectionId)` 取成员逐一 `addToCollection(parentId)` 移回上层（顶层分组成员直接脱离分组），再 `deleteCollection`；当前视图是该分组时切回父分组（无父则 null）
  - 清空最近使用走 `promptClearRecent` → `MemeDb.clearRecent`，若在最近使用视图则退出该视图
- 表情长按菜单「加入小分组」→ `promptAddToSubgroup`：列出当前分组子分组 + 「新建小分组」，选中即 `addToCollection`；新建时走 `promptCreateSubcollectionFor`（同样受 1 层限制），对齐桌面端网格右键 `add-to-subgroup`

### 从分组移除 / 空分组自动删除（MainActivity.kt）
- 长按菜单「从分组移除」仅在查看具体分组（`activeCollectionId > 0`）时显示；从最近使用视图查看时显示「从最近使用中删除」
- 移除逻辑对齐桌面端 webui：若是小分组（`parentId != 0`）移回上层分组；移除后该分组计数为 0 则自动 `deleteCollection`，并把当前视图切回上层（无上层则 null）
- 取消收藏/移出最近使用后，若对应系统分组（收藏夹/最近使用）计数归零，自动退出该视图

### 拖拽排序（MainActivity.kt）
- 「更多」菜单 `act_toggle_drag_sort` 开启拖拽排序（`toggleDragSort`），与整理模式互斥。仅当关键词为空、当前为全局视图或正数真实分组，且网格至少有 2 张卡片时，卡片左上拖拽手柄显示并允许排序
- 搜索中，以及收藏夹 `-2`、最近使用 `-3`、未分类 `-4` 视图均隐藏手柄且不得重排
- `ItemTouchHelper` 的 `SortCallback.isLongPressDragEnabled()` 返回 false。仅手柄按下后拖动可调用 `startDrag`；卡片主体点击继续分享，主体长按继续打开右键菜单
- `clearView` 落库：`activeCollectionId > 0` 时 `reorderCollectionMembers(cid, ids)`，否则 `reorderMemes(ids)`，与桌面端 `reorder_collection_members`/`reorder_memes` 一致
- `MemeGridAdapter` 持有可变 `items`，`move(from,to)` 用 `notifyItemMoved`，`currentIds()` 供落库取序

### 版本更新（UpdateChecker.kt）
- 桌面端 `updater.py` 迁移：`_parse_version` → `parseVersion`，`_pick_asset_url` → 遍历 assets 找 `.apk`
- GitHub Releases API：`https://api.github.com/repos/OhMyMeme/OhMyMeme-Android/releases/latest`，repo 地址与桌面端不同（Android 仓库）
- **两级回退**（对齐桌面端 `check_latest`）：先并发 `fetchFirst(GITHUB_LATEST)`（镜像+直连，`invokeAny`），404/失败时回退 `GITHUB_LIST`（`releases?per_page=5`）`pickHighestFromList` 取最高版本（无 `.apk` 资产时回退 release `html_url`）
- **稳定版过滤**：`pickHighestFromList` 跳过 `draft`/`prerelease` 与 tag 含 `nightly`/`beta`/`rc` 的版本，只推送正式版
- **并发抓取**：`fetchFirst` 把 4 镜像前缀 + 直连共 5 个 URL 并发 GET，`invokeAny` 等待首个真正成功（`fetchBody` 失败抛异常，避免早期修复中"null 被当作成功"导致直连没机会）；UA 伪造安卓 Chrome（`UA` 常量）
- 版本比较：`parseVersion` 拆 `v0.1.0` 为 `[0,1,0]`，按位比较（`compareVersions`），大于当前 versionName 视为有更新
- 安卓无法自动安装 APK：检测到新版本用 `AlertDialog` 引导，点击「下载」`ACTION_VIEW` 打开 APK `browser_download_url`（无 apk 资产时回退 release `html_url`）
- **镜像下载**：API 仍直连 GitHub，仅下载地址走镜像。`mirrorDownloadUrl` 按桌面端 `updater.py` `_GH_MIRRORS`（github.dpik.top / gh.dpik.top / gh-proxy.org / proxy.starsfire.top/-----）前缀逐个 HEAD 探测，返回第一个可用镜像 URL，全失败回退直连；`SettingsActivity` 下载时先经 `mirrorDownloadUrl`
- `checkUpdate()` 在后台 `Thread` 跑 `checkLatest`（网络阻塞），`runOnUiThread` 回主线程更新按钮状态/弹窗；`UpdateInfo` 的 `error` 字段承载网络失败文案（未接显示系统，暂以 Toast 呈现）

### 云端同步（CloudSync.kt）
- **多线程 + 进度回调**（对齐桌面端 `sync_threads`，默认 3，1-8）：`push`/`pull` 分块并发（`chunkList` + `ThreadPoolExecutor`），每块 worker 独立 `createBackend`/`connect` 连接（对应桌面端 `_push_worker`/`_pull_worker` 独立后端）；worker 内跳过/成功/失败分别计数，单文件失败不影响其余；pull 下载后统一回主线程写 DB（避免多线程并发写 SQLite）
- `SyncProgress` 线程安全计数类（`filesTotal`/`bytesTotal`/`report`/`done`/`bytesDone`/`currentFile`/`startTime`/`onProgress` 回调），worker 线程回调、UI 自行 `runOnUiThread`
- 对齐桌面端 `sync.py` + `manifest.py`：远端目录 `memes/`（REMOTE_MEME_DIR）+ `meme-index.json`（INDEX_FILENAME，清单 version 3）；远端根：FTP→`ftp_path`、WebDAV→`webdav_path`、对象存储→空
- 清单字段与桌面端一致：`memes[]`（filename/name/sha256/file_size/mtime，name 取 `original_name` 空时回退文件名去扩展名）+ `collections[]`（嵌套树，name/filenames/children；空集合在构建时自动 `deleteCollection`，与 `_build_collection_tree` 一致）
- 后端实现（无第三方依赖，纯 `java.net`）：FTP 手写控制/数据通道（被动模式 PASV，STOR/RETR/SIZE/DELE/NLST/MKD，UTF-8）；S3/R2 用 `S3Backend`（isR2 标志读 r2_* 配置；**S3 兼容阿里云 OSS**：`signature_version="s3"` 走 SigV2 签名（HMAC-SHA1 + `Authorization: AWS ak:base64`，`signV2`），`addressing_style="virtual"` 走虚拟主机寻址 `https://bucket.endpoint/key`（`urlForKey`），默认即 V2+virtual（对齐桌面端 `config.py` 默认）；R2 强制 SigV4 + path 寻址不变；endpoint=`https://{account_id}.r2.cloudflarestorage.com`，list 用 `<Key>` 正则解析 ListObjectsV2）；WebDAV 用**原始 socket HTTP/1.1**（`davHttp`：任意方法 PROPFIND/MKCOL/PUT/GET/HEAD/DELETE，HTTPS 走 SSLSocket，每次独立建连 `Connection: close` 不复用，Content-Length/chunked/读到关闭三种响应体读取，下载流式写盘 `sink`）——因 Android `HttpURLConnection` 拒绝 PROPFIND/MKCOL（`ProtocolException: Expected one of ...`），与 FTP 一样手写协议层
- `downloadIndex` 下载远端清单到 dataDir 临时文件再解析，失败清理；`writeTempIndex` 上传前写本地临时清单
- `push`：本地清单与远端清单按 `filename+sha256` 比对，相同且远端文件存在则跳过；`sync_delete_remote` 时删除远端多余文件；成功后合并远端仍保留的孤儿项重建清单并上传；上传失败即抛 `SyncError` 不更新远端清单
- **Backend 接口只接受真实 `File`**（`uploadFile(local: File)/downloadFile(remotePath, dest: File)` 三实现不变）；cache/thumbnails 在 SAF 模式下经 `StorFile` 读写，push 上传前 `StorFile.copyTo(context.cacheDir 临时文件)` 物化，pull 先下载到 `context.cacheDir` 临时文件再 `createFile(fname, mime).writeFrom(tmp)` 落入 cache，用完删除
- `pull`：下载清单→按哈希/文件存在跳过→下载缺失文件（超限文件跳过并删除临时文件、空文件计失败并清理）→`getByFilename` 无记录时读尺寸 `addMeme`；`sync_remove_local` 时删除本地多余文件+库记录+缩略图；`applyRemoteCollections` 按远端分组建集合并挂成员（顶层，含子集合的文件已并入父集合 filenames）；`applyRemoteOrder` 按远端 manifest 的 `memes` 顺序重排本地 `sort_order`（`reorderMemes`，`isSafeRemoteFname` 校验文件名），保留云端排序，避免再 push 覆盖远端顺序（对齐桌面端 `_apply_remote_order`，removeLocal 分支也执行）
- 公开 API：`syncTest`（返回 "ok" 或错误信息）、`checkSyncStatus`（返回本地/远端计数与仅本地/仅远端文件名摘要）、`push`/`pull`（返回 `SyncResult(uploaded/downloaded/skipped/errors/deleted/removedLocal/failed)`，失败抛 `SyncError`）、`deleteAllRemote`、`deleteAllLocal`
- 单线程顺序执行（安卓端不做多线程分片）；同步配置读 `ConfigStore`（密钥字段已解密）

### 设置页同步接线（SettingsActivity.kt）
- `sp_sync_type` 位置→`sync_type` 映射：0 无 / 1 ftp / 2 s3 / 3 r2 / 4 webdav（`syncTypes` 列表）；`loadConfig` 回填 `setSelection`，`saveConfig` 写入
- 测试连接/检查状态/上传/下载按钮跑后台 `Thread` 后 `runOnUiThread` 用 Toast 呈现；`btn_sync_push` 文本作进度占位；危险操作（删除本地/云端）先弹确认框
- 「删除本地所有」复用 `MemeDb.deleteAll` + 清理 cache/缩略图；「删除云端所有」遍历远端清单删除文件+清单
- 网格间距：`item_meme.xml` 卡片 `layout_margin 5dp`（对应桌面端网格 `gap: 10px`）

### 局域网互联（LanClient.kt）
- **角色**：安卓端仅客户端，连接桌面端 `lan.py` 服务（UDP 发现 + TCP 握手 + AES-GCM 会话），协议逐字节对齐
- **UDP 发现**：`discover(context, port)` 广播 `{"t":"discover"}` 到 255.255.255.255:port，收集 1.5s 内 `{"t":"hello","name","os","ver","need_secret"}` 应答去重返回 `LanPeer` 列表
- **IP:端口 直连**：设置页新增直连输入框 + 「直连」按钮（`connectDirect`），解析 `IP:端口` 构造 `LanPeer` 直接走 `doConnect`（复用 `LanClient.connect`，跳过 UDP 扫描），状态显示「已连接 IP（直连）」
- **TCP 握手**（对齐 `lan.py._handshake`）：有密钥时收 `challenge{nonce}` → 回 `proof{mac=HMAC-SHA256(secret,nonce)}` → `ok/no`（3 次）；无密钥直接收 `ok`，会话密钥 32 个零字节
- **设备确认**（`connect` 内握手后）：客户端发 `device_info` 加密帧 `{cmd, name, model, os, ver}`（`Build.MODEL`/`MANUFACTURER`/`VERSION.RELEASE`/versionName），等待电脑端弹窗确认；`{ok:true, approved:true}` 才放行，`approved:false`/错误则 `LanError`，超时 `DEVICE_CONFIRM_TIMEOUT_MS`=60s；旧版电脑端回「未知命令」时提示升级；确认响应携带 `allow_secret_config` bool 存入 `LanConnection.allowSecretConfig`
- **会话密钥**：`PBKDF2WithHmacSHA256(secret, salt="ohmy-meme-lan", 100000, 32)`（`deriveKey`）；无密钥返回零字节数组
- **加密帧**：`[4B 大端长度][12B IV][AES-GCM 密文+16B tag]`；明文帧（握手期）`[4B 长度][JSON]`；`request(cmd, params)` 用 `synchronized(writeLock)` 保证请求/响应配对不交错
- **命令**：`ping`/`pull_manifest`/`push_manifest`/`pull_file`/`push_file`/`get_config`/`send_config`/`device_info`
- **pull**：`pullManifest` → 遍历 `memes[]` 逐文件四重校验（文件名安全 `isSafeRemoteFname`、单文件 ≤20MiB `MemeImporter.MAX_BYTES`、清单 `sha256` 哈希一致、`MemeImporter.isValidImageContent` 魔数+可解码）→ 通过才 `getByFilename` 去重 → `pullFile` 字节 → `MemeImporter.importBytes`（内部同样先校验可解码再落盘，杜绝孤儿文件）→ `CloudSync.applyRemoteOrder` 回写本地排序；任一检查不过即跳过计入 failed 且不落盘
- **push**：先 `pullManifest` 拿远端文件名集合 → 本地 `getAll` 逐个 `pushFile`（桌面端 `_import_bytes` 内部哈希去重幂等）→ 最后 `pushManifest(CloudSync.buildManifest)` 同步顺序/分组
- **配置同步（双向，独立按钮）**：「拉取配置」/「推送配置」两个独立按钮（`configOp` 后台执行），普通同步两端均剔除 `ConfigStore.SECRET_KEYS`（对齐桌面端 `allow_secret_config` 默认关）
- **密钥同步（随开关动态显示）**：电脑端确认响应 `allow_secret_config=true` 时，设置页动态显示「拉取密钥」/「推送密钥」按钮（`lan_key_row` 可见性由 `updateKeyRow()` 控制）；点击先弹「请勿在公共网络或不信任的网络进行此操作！」警告，确认后走 `pullConfig`/`pushConfig` 的 `includeSecrets=true`（不过滤密钥字段，拉取后经 `ConfigStore.save` 用本机 Keystore 重新加密）；`allow_secret_config=false` 或未连接时按钮隐藏
- **UI**：设置页「局域网互联」区块（端口/密钥/IP:端口直连/扫描/连接/断开/拉取/上传/拉取配置/推送配置/拉取密钥/推送密钥），`LanConnection` 生命周期跟随 `SettingsActivity`（`onDestroy` 关闭）
- **配置键**：`lan_port`（默认 17852）/`lan_secret`（`SECRET_KEYS` 加密存储，对齐桌面端 `_SECRET_KEYS`）

## 构建 & 验证
```bash
./gradlew :app:compileDebugKotlin   # 快速编译验证（约 12-19s）
./gradlew :app:assembleRelease      # 完整构建已签名 Release APK
```
- **跑 gradle 必须设置超时**（`timeout` 参数 600s+），否则可能卡死
- **签名**：Release 与 Debug 共用共享密钥（`keystore/ohmymeme-release.jks` + 项目根 `keystore.properties`，
  均 gitignored，来自私有仓库 `OhMyMeme/OhMyMeme-Android-keystore`）；没有密钥时打包报错（刻意保证签名一致）
- **新成员**：先跑 `scripts/setup-keystore.ps1`（从私有密钥仓库拷贝 keystore 到 gitignored 路径），
  否则 `assembleRelease`/`assembleDebug` 在打包时报 `SigningConfig "shared" is missing required property "storeFile"`
- **APK 命名**：`app/build.gradle.kts` 顶部 `appVersionName`（与 defaultConfig.versionName 一致），
  AGP 9 通过 `androidComponents.onVariants` 注册 `renameReleaseApk` 任务，产物
  `app/build/outputs/apk/release/OhMyMeme-Android-{appVersionName}.apk`
- **CI 签名**：`build.yml` 从 Secrets（`SIGNING_KEYSTORE_BASE64`/`SIGNING_STORE_PASSWORD`/`SIGNING_KEY_ALIAS`/`SIGNING_KEY_PASSWORD`）
  解码 keystore 并注入环境变量构建 `assembleRelease`；本地构建读 `keystore.properties`
- 用户通常自行 `assembleDebug`，改动后先跑 `compileDebugKotlin` 验证

## CI（.github/workflows，参考桌面端）
- `check.yml`：push/PR 触发，JDK 17 跑 `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest`
- `build.yml`：Check 成功（main）或手动触发，跑 `assembleRelease`（Secrets 解码签名密钥）并上传
  `app/build/outputs/apk/release/*.apk` artifact

## 已实现 / 未实现
### 复制处理（MemeCopyProcessor.kt + GifEncoder.kt + GifStego.encode）
- 对应桌面端 `clipboard_util.py` `convert_image_mode_1/2/3`（`_resize_static_to_webp`/`_static_to_gif`/`_make_stego_gif`）+ `gif_stego.py` `_candidates`/`make_stego_gif`
- `MemeCopyProcessor.process(context, file)`：`copy_resize_mode==0` 或 `isAnimatedFile`（动图）或未超 `copy_resize_max` 时返回 null 回退原图；模式 1 缩放 WebP(q90，ARGB_8888 解码，LANCZOS 语义用 `createScaledBitmap` 替代)、模式 2 转 GIF、模式 3 转隐写 GIF
- 像素对齐 Pillow：`getPixels` 取预乘 ARGB 后**反预乘**还原真实 RGB（同 `AndroidGifDecoder`）；kind 判定 `bmp.hasAlpha()`→RGBA、全像素 r==g==b→L、否则 RGB（对应桌面端 `_delta_data` 的 mode 判定）
- `GifEncoder`（纯 JVM，可单测）：median cut 量化到 ≤256 色 + GIF89a/LZW 编码；**LZW 码长升位时机 = 新增条目后 `nextCode == (1 shl codeSize) + 1`**（非标准实现常见的 `== 1 shl codeSize`），与 `GifFrameDecoder.lzwDecode` 的 `dict.size == 1 shl codeSize` 延迟升位严格对应，已用 Python+Pillow 跨 512/1024/2048 边界与表满场景逐字节验证
- `GifStego.encode(gifData, origBytes, origExt, origPixels, kind, w, h, webpLossless?)`：生成 FULL（`extLen+ext+origBytes` 整体 LZMA）与差值候选（LZMA 恒生成；WebP 候选仅当 `webpLossless` 回调返回非空，Android 端 API 30+ 用 `WEBP_LOSSLESS` 保证无损，低版本跳过），取 payload 最小者；LZMA 用 `XZOutputStream`（`LZMA2Options(6)`，preset 9 太重）；RGBA 不生成 WebP 候选（libwebp 可能改写全透明像素 RGB）
- 单测：`GifEncoderTest`（256 色内逐字节精确、灰度精确、跨边界稳定性）、`GifStegoEncodeTest`（RGB/L/RGBA 差值与 FULL 全图 encode→decode 逐字节还原，FULL 用「极小 origBytes + 大差值」保证选中）

### 已实现
- 主界面 / 设置页暗色 UI 复刻 + 桌面端布局复刻（顶栏折叠按钮 + logo + 图标、搜索框独立一行、标签行、左侧常驻分组树侧栏 `rv_sidebar` 默认收起）
- 存储层：路径、SQLite 数据库、JSON 配置 + 密钥加密
- 缓存扫描、SAF 导入（20MiB/2560px 上限，ImportOutcome/ImportResult 汇总）、缩略图生成
- 搜索（关键词实时）+ 标签行过滤（多选叠加，全含匹配）+ 空状态切换
- 设置页保存/重置接真实配置
- 首次运行存储位置选择
- 版本更新检查（GitHub Releases API，列表路径跳过 draft/prerelease/nightly/beta/rc 只推正式版）
- GIF 动图播放（`auto_play_gif` 开关控制，WebP 动图亦支持）
- 长按右键菜单（重命名/收藏/打标签/添加分组（两段式）/从最近使用中删除/删除）
- 表情网格间距（卡片 5dp 外边距）
- 更新下载镜像源回退（github.dpik.top 等 4 个镜像 + 直连）
- 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单 push/pull/test/status/清理云端孤儿；S3 兼容阿里云 OSS：V2 签名 + 虚拟主机寻址，设置页可切换 V4/AWS 标准）
- 最近使用记录：点击网格卡片 `recordUse` 记入 recent_uses（受 `record_recent_use` 开关控制），最近使用分组自动刷新
- 启动自动同步：MainActivity 启动读 `sync_auto_sync`/`sync_auto_fetch_index` 配置，后台执行 pull/checkSyncStatus
- 日志导出：设置页 `ACTION_CREATE_DOCUMENT` 选保存位置，后台 logcat `--pid` 写入文本文件
- 顶部快捷同步：主界面标题栏「更多」菜单提供上传/下载，一键 push/pull
- 同步进度/完成弹窗：`quickSync` 走独立 `syncExecutor`（不占共享 executor，避免大文件同步卡 UI）；按 `show_upload_progress`/`show_download_progress` 显示 `dialog_sync_progress`（进度条/百分比/速度/当前文件/「后台运行」按钮），`show_upload_done`/`show_download_done` 控制 `dialog_sync_done` 完成弹窗；后台运行后仅 Toast 摘要
- 修改存储位置：设置页 `ACTION_OPEN_DOCUMENT_TREE` 选新目录，`persistDataTree` 校验后弹窗询问是否转移；SAF 全量支持（`StorFile` 经 content URI 读写 cache/thumbnails，`memes.db` 留在真实路径），只转移 cache/thumbnails 两个子目录，config.json 保持不变
- 隐写 GIF 解码导入（STG3 检测 + 7 种模式还原，fixture 单测逐字节对齐 Pillow）
- 小分组（子分组）创建与顶栏嵌套胶囊展示（1 层限制，长按分组胶囊新建 + 「加入小分组」）
- 分组管理：长按分组胶囊重命名/删除（成员移回上层），最近使用分组「清空最近使用」
- 标签系统：`promptEditTags` 对话框搜索/点选/回车新建标签，`setMemeTags` 孤儿标签清理；标签行多选叠加过滤（`memeIdsWithAllTags`），对齐桌面端 TagEditor + App.vue 标签栏
- 整理模式（多选批量删除）：顶栏整理图标进入，点击卡片勾选 + 底部操作栏「已选 n 项 / 全选 / 取消 / 批量删除」，`MemeDb.deleteMemes` 单事务批量删 + 物理文件与缩略图清理；与拖拽排序互斥
- 拖拽排序：「更多」菜单开启；仅在空搜索、全局或正数真实分组且至少 2 张卡片时，由卡片左上手柄启动。卡片主体点击分享、长按打开菜单，搜索/收藏夹/最近使用/未分类隐藏手柄；全局 `reorderMemes` / 分组内 `reorderCollectionMembers` 落库
- 点击分享：点击网格卡片经 FileProvider（`file_paths.xml` 缓存路径）把原图复制到内部 cache 后用 `ACTION_SEND` 打开系统分享（微信/QQ 等），同时 `recordUse` 记最近使用；分享前按设置页「复制处理」模式处理超限静态图（见下方「复制处理」小节）
- 复制处理（GifEncoder + GifStego.encode + MemeCopyProcessor）：对应桌面端 `clipboard_util.py` `convert_image_mode_1/2/3` —— 超过 `copy_resize_max` 上限的静态图在分享前按模式 1 缩放 WebP(q90) / 模式 2 转普通 GIF(256 色) / 模式 3 转隐写 GIF（基座 GIF + STG3 写入原图数据，可无损还原）；动图/未超限/处理失败回退原图直发
- 接收分享导入：MainActivity 声明 `ACTION_SEND`/`ACTION_SEND_MULTIPLE`（image/*）intent-filter，`onCreate`/`onNewIntent` 取 `EXTRA_STREAM` URI 列表直接 `doImport`
- 局域网互联：设置页「局域网互联」区块连接电脑端 `lan.py`，支持扫描发现/配对（发送设备信息待电脑端确认）/IP:端口 直连/拉取/上传/配置双向同步（弹窗确认）/密钥同步（电脑端 `allow_secret_config` 开关开启时动态显示，弹窗警告后同步）
- 「未分类」分组：顶栏胶囊显示未加入任何分组的表情（虚拟分组 `-4`，`MemeDb.search`/`count` 的 `uncategorizedOnly` 参数对应桌面端 `uncategorized_only`），计数 > 0 才显示、清零自动隐藏并退出视图；负数 id 使拖拽排序/长按分组菜单自动禁用；`CloudSync` 清单仅遍历真实 `collections` 表不受影响；设置页「显示『未分类』分组」开关（`show_uncategorized`，默认开，对齐桌面端 `config.py`/`settings.html`）
- 控制中心快捷按钮：`QuickTileService`（`TileService`，manifest 声明 `BIND_QUICK_SETTINGS_TILE` + `quick_settings_tile.xml` 磁贴图标 `ic_qs_tile`），`onClick` 打开 MainActivity（锁屏先 `unlockAndRun`）；设置页「快捷开关」区块 + 「添加到控制中心」按钮弹添加指引（系统磁贴需用户在快捷设置编辑面板手动添加）
- 长按拖拽发送（相册式）：`MemeGridAdapter` 长按卡片直接回调 `onDragStart` → `MainActivity.startGlobalDrag`：`materializeDragFile`（SAF 先 `stor.copyTo(cacheDir)` 物化，统一临时文件路径）→ `FileProvider.getUriForFile` → `ClipData.newUri` → `itemView.startDragAndDrop(DRAG_FLAG_GLOBAL or DRAG_FLAG_GLOBAL_URI_READ)` 跨应用拖入微信/QQ，同时 `recordUse`；`ACTION_DRAG_ENDED` 且未被接收（`!e.result`）时回调 `onDragFailed` 弹原右键菜单兜底；卡片右上角 `btn_meme_menu`（「⋯」）点击回调 `onMenuClick` 打开 `showMemeMenu`；注意鸿蒙/Huawei 上 `TYPE_APPLICATION_OVERLAY` 无法发起全局拖拽，因此拖拽必须由 Activity 窗口内的视图发起（曾用悬浮窗方案失败后改为相册式）；跨应用拖拽需真机验证（接收方是否支持图片拖放，失败有菜单兜底）

### 未实现（后续待做）
- 从手机QQ缓存导入（当前为占位 Toast，后续用 Shizuku 授权后 ADB 获取文件）
