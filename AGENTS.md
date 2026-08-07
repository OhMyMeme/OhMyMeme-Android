# OhMyMeme Android — AI Agent Guide

## 项目概述
桌面端表情包管理系统（OhMyMeme）的安卓移植版。存储结构、数据库 schema、导入/扫描/缩略图命名规则与桌面端 `D:\code\OhMyMeme` 完全一致，便于多端同步。

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
- **不重构桌面端** — 桌面端 `D:\code\OhMyMeme` 仅做最小必要修改；如需同步桌面端数据层逻辑，以 `database.py`/`config.py`/`webui.py` 为唯一事实来源
- **存储结构对齐桌面端** — 表 schema、列名、重命名规则、缩略图命名、去重逻辑逐条对照，不得随意改动
- **增改同步** — 新功能/新文件必须同步更新 `README.md` 和 `AGENTS.md`
- **无 emoji**（除非用户要求）
- **代码风格** — 无冗余注释；单线程 Executor 跑数据库/IO，`runOnUiThread` 回主线程更新 UI；Kotlin 按语言惯例写类型标注

## 关键目录
```
app/src/main/
  java/com/ohmymeme/app/
    MainActivity.kt     # 主界面：导入/刷新/搜索/网格 + 空状态
    SettingsActivity.kt # 设置页：loadConfig/saveConfig/reset 接真实配置
    ChipAdapter.kt      # 分组胶囊适配器（仅 COLLECTION 样式）
    MemeGridAdapter.kt  # 表情网格，异步缩略图加载，按 meme.id 打 tag 防错位
    Meme.kt             # 数据模型（对应 memes 表，含 stego/fromStego 字段）
    MemeDb.kt           # SQLite 封装（7 表 + 索引 + 列迁移）
    ConfigStore.kt      # JSON 配置（DEFAULTS 与桌面端 config.py 一致）
    CryptoUtil.kt       # Android Keystore AES-GCM 加解密
    StoragePaths.kt     # 路径解析（base/data/cache/thumbnails/db/config）
    FileUtils.kt        # SHA-256 + 魔数识别扩展名
    CacheScanner.kt     # 缓存扫描（双重去重）
    MemeImporter.kt     # SAF 批量导入
    GifFrameDecoder.kt  # 自研最小 GIF 解码器（LZW/interlace/色板，与 Pillow 一致）
    GifStego.kt         # STG3 隐写检测 + 7 模式解码 + 自研 PNG 编码
    AndroidGifDecoder.kt# 设备端 WebP→RGBA（反预乘 alpha）
    Thumbnailer.kt      # 缩略图生成 {id}_{size}.png
    CloudSync.kt        # 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单）
    UpdateChecker.kt    # 版本更新检查（GitHub Releases API）
  res/
    layout/activity_main.xml / activity_settings.xml / item_*
    values/colors.xml   # 暗色配色（bg #0D0D0F、card #1E1E22、accent #3B82F6、muted #71717A）
    values/themes.xml   # Theme.OhMyMeme（含 values-night）
    values/strings.xml  # 含 copy_mode_options / sync_type_options
```

## 存储布局（与桌面端对应）
```
Android/data/com.ohmymeme.app/
├── config.json                       ← 桌面端 %APPDATA%/OhMyMeme/config.json
└── files/                            ← localdata（对应桌面端 %LOCALAPPDATA%/OhMyMeme）
    ├── memes.db
    ├── cache/                        ← 导入原图，命名 {sha256前16位}{ext}
    └── thumbnails/                   ← {meme_id}_{size}.png（默认 150）
```

## 关键实现细节

### 数据库（MemeDb.kt）
- 7 表：`memes`/`tags`/`meme_tags`/`collections`/`meme_collections`/`favorites`/`recent_uses`，字段与桌面端 `database.py` 逐列一致
- `PRAGMA`：`enableWriteAheadLogging()` 替代桌面端 `journal_mode=WAL`；外键依赖 ON DELETE CASCADE
- `getCollectionDepth` 在安卓上按 `pid == 0L` 判根（SQLite parent_id 无 NULL 时以 0 存储）
- 列迁移：与桌面端相同的 `ALTER TABLE ... ADD COLUMN` 容错迁移
- 单例：`MemeDb.get(context)`，用 `applicationContext` 防泄漏

### 配置（ConfigStore.kt）
- `DEFAULTS` 逐字段照搬桌面端 `config.py`（含 `s3_path`、`webdav_timeout` 等）
- `SECRET_KEYS` 6 个密钥字段（s3_access_key/s3_secret_key/r2_access_key_id/r2_secret_access_key/ftp_password/webdav_password）写入前加密、读取后解密
- `load()` 在读取时对密钥字段先解密；`save()` 加密副本后写盘；损坏文件回退默认值；**首次运行文件不存在时自动落盘默认配置**
- 与桌面端差异：桌面端 Fernet，安卓端用 Android Keystore（硬件背书），格式不互通但字段名一致

### 首次运行存储位置（MainActivity.kt）
- `StoragePaths.isFirstRun` 检测（SharedPreferences 标记 `setup_done`），首次启动弹窗二选一：默认位置（应用专属外部目录）或「选择其他位置」（SAF `ACTION_OPEN_DOCUMENT_TREE`）
- `StoragePaths.resolveTreeUriPath` 把 SAF 树 URI 解析为真实路径（`primary:`→外部存储根，`home:`→Downloads），解析失败回退默认并提示；`setDataDir` 覆盖 localdata 目录
- 选完位置后 `markSetupDone` + `ConfigStore.invalidate` 再加载数据

### 导入（MemeImporter.kt）
- 点击标题栏「导入」弹 `menu_import.xml` 菜单：从文件导入（`pickImages`，SAF `ACTION_OPEN_DOCUMENT` 多选）/ 从手机相册导入（`pickAlbumImages`：`isPhotoPickerAvailable` 为真走 Photo Picker `PickMultipleVisualMedia`，否则回退 `ACTION_GET_CONTENT` 相册，避免库默认回退文件选择器）/ 从手机QQ缓存导入（占位 Toast「开发中，后续用 Shizuku 获取文件」）
- 两种导入共用 `doImport(uris)` → `MemeImporter.importUris`：逐文件：查哈希去重 → 魔数识别扩展名 → 拷贝到 `cache/{hash16}{ext}` → 读尺寸 → `addMeme`
- 单文件失败不影响其余文件（catch 后继续）
- **隐写 GIF 解码**（对应桌面端 `_try_decode_stego` + `gif_stego.py`）：GIF 且含 `STG3` 时 `GifStego.decode` 还原原图（7 种模式），只导入还原结果 `fromStego=1`，GIF 本身不入库；WebP 模式经 `AndroidGifDecoder.webpToRgba` 解码（反预乘 alpha）
- `GifFrameDecoder` 为自研最小 GIF 解码器（GIF87a/89a、全局/局部色板、LZW、interlace、透明索引直映射 RGB、Pillow 一致的 `(R*299+G*587+B*114+500)/1000` 灰度），与 Pillow 逐字节一致；LZMA 用 `org.tukaani:xz`（`XZInputStream`）；PNG 输出用自研 RGBA 编码器
- `CacheScanner` 不做 STG3 检测（对齐桌面端 `scan_cache`）

### 缓存扫描（CacheScanner.kt）
- 遍历 cache 目录：跳过非图片扩展名、`thumbnails` 路径、与同名 `.webp` 共存的 `.gif`
- **双重去重**：`getByFilename` 跳过已注册 → SHA-256 → `getByHash` 跳过重复内容
- 与桌面端 `scan_cache` 逻辑一致

### 缩略图（Thumbnailer.kt）
- 命名 `{meme_id}_{size}.png`，存在即复用（与桌面端一致）
- `findMemeFile` 先查缓存根目录，再递归遍历（对应桌面端 `_find_meme_file`）
- BitmapFactory `inSampleSize` 先按 2×size 降采样，再 createScaledBitmap 到 150×150，保存 PNG

### 网格加载（MemeGridAdapter.kt）
- 单线程 Executor 后台生成/解码缩略图，`img.tag = meme.id` 防列表复用错位
- 占位图用 `ic_photo` + muted 色，加载后 `setColorFilter(null)` + `setImageTintList(null)` 清色/清 XML tint
- 名称取 `original_name`，为空回退文件名去扩展名
- **动图渲染**（对应桌面端 webui `m.is_animated && m.auto_play_gif`）：后台判 `isAnimatedFile`（`FileUtils.isAnimatedFile`：GIF89a 头或 RIFF+WEBP+ANIM；webp 直查 cache 根避免全目录遍历），且 `ConfigStore` 的 `auto_play_gif` 为 true 时用 `ImageDecoder` + `AnimatedImageDrawable`（`setTargetSampleSize` 目标 300）播放原图，否则用静态缩略图；动画解码失败回退缩略图
- 右上角 badge：GIF / WebP（动图）/ 隐写导入（`fromStego==1`），`bg_badge.xml` 蓝底圆角
- 长按回调 `onLongClick` 属性，由 MainActivity 弹 PopupMenu

### 长按右键菜单（MainActivity.kt）
- `menu/menu_meme.xml`：重命名 / 收藏（标题随状态切换）/ 添加分组 / 加入小分组（仅查看具体分组时显示）/ 从分组移除 / 从最近使用中删除 / 删除（红），对齐桌面端 webui 右键菜单
- 删除走 `deleteMemeFiles`：删物理文件（`Thumbnailer.findMemeFile`）+ 删 `{id}_*.png` 缩略图 + `deleteMeme` 删库，与桌面端一致
- 设置页用 Activity Result API（`registerForActivityResult`，`settingsLauncher`）打开，返回 `RESULT_OK` 后 `reloadData()` 使配置（如动图开关）立即生效；导入/选目录/设置统一走 `ActivityResultContracts.StartActivityForResult`

### 分组胶囊过滤（MainActivity.kt）
- 点击 `rv_collections` 胶囊过滤表情：分组单选切换（`activeCollectionId`），再次点击取消；选中态由 `ChipAdapter.activeItems` 控制（accent 色 + active 背景）
- `ChipAdapter` 泛型化（TAG 用 `String`，COLLECTION 用 `CollectionEntry(id,name,count,hasChildren)`），分组胶囊带数量，label 显示 `名称 (count)`；有子分组时追加 `▼`
- 分组栏含系统分组：收藏夹 `-2`（`favoriteOnly`）、最近使用 `-3`（`getRecent`），与桌面端 `get_collections` 一致
- 过滤与关键词叠加后走 `MemeDb.search(keyword, tags, collectionId, favoriteOnly, offset, limit)`；收藏夹走 `favoriteOnly`，最近使用走 `getRecent`，无过滤时 `getAll`。`collectionId != null` 时 ORDER BY 按 `meme_collections.sort_order`（子查询）排序，与桌面端分组内排序一致

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
- 标题栏「排序」按钮（`btn_sort`）开关 `sortEnabled`，开启后高亮 accent 色并 Toast 提示
- 对齐桌面端 `toggleDragSort`/`canReorderMemes`：仅当 `sortEnabled && 关键词为空 && (activeCollectionId == null || > 0)` 时可排序（搜索中/收藏夹/最近使用禁用）
- 可排序时用 `ItemTouchHelper`（`SortCallback`，`isLongPressDragEnabled`）支持长按拖拽换位，此时禁 `onLongClick` 右键菜单（手势冲突）；否则恢复右键菜单
- `clearView` 落库：`activeCollectionId > 0` 时 `reorderCollectionMembers(cid, ids)`，否则 `reorderMemes(ids)`，与桌面端 `reorder_collection_members`/`reorder_memes` 一致
- `MemeGridAdapter` 持有可变 `items`，`move(from,to)` 用 `notifyItemMoved`，`currentIds()` 供落库取序

### 版本更新（UpdateChecker.kt）
- 桌面端 `updater.py` 迁移：`_parse_version` → `parseVersion`，`_pick_asset_url` → 遍历 assets 找 `.apk`
- GitHub Releases API：`https://api.github.com/repos/OhMyMeme/OhMyMeme-Android/releases/latest`，repo 地址与桌面端不同（Android 仓库）
- **两级回退**（对齐桌面端 `check_latest`）：先并发 `fetchFirst(GITHUB_LATEST)`（镜像+直连，`invokeAny`），404/失败时回退 `GITHUB_LIST`（`releases?per_page=5`）`pickHighestFromList` 取最高版本（无 `.apk` 资产时回退 release `html_url`）
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
- 后端实现（无第三方依赖，纯 `java.net`）：FTP 手写控制/数据通道（被动模式 PASV，STOR/RETR/SIZE/DELE/NLST/MKD，UTF-8）；S3/R2 用 `S3Backend`（isR2 标志读 r2_* 配置，AWS SigV4 手写签名，endpoint=`https://{account_id}.r2.cloudflarestorage.com`，list 用 `<Key>` 正则解析 ListObjectsV2）；WebDAV 用 HTTP（MKCOL 幂等建目录/PUT/GET/PROPFIND/HEAD 回退/DELETE）
- `downloadIndex` 下载远端清单到 dataDir 临时文件再解析，失败清理；`writeTempIndex` 上传前写本地临时清单
- `push`：本地清单与远端清单按 `filename+sha256` 比对，相同且远端文件存在则跳过；`sync_delete_remote` 时删除远端多余文件；成功后合并远端仍保留的孤儿项重建清单并上传；上传失败即抛 `SyncError` 不更新远端清单
- `pull`：下载清单→按哈希/文件存在跳过→下载缺失文件（空文件计失败并清理）→`getByFilename` 无记录时读尺寸 `addMeme`；`sync_remove_local` 时删除本地多余文件+库记录+缩略图；`applyRemoteCollections` 按远端分组建集合并挂成员（顶层，含子集合的文件已并入父集合 filenames）；`applyRemoteOrder` 按远端 manifest 的 `memes` 顺序重排本地 `sort_order`（`reorderMemes`，`isSafeRemoteFname` 校验文件名），保留云端排序，避免再 push 覆盖远端顺序（对齐桌面端 `_apply_remote_order`，removeLocal 分支也执行）
- 公开 API：`syncTest`（返回 "ok" 或错误信息）、`checkSyncStatus`（返回本地/远端计数与仅本地/仅远端文件名摘要）、`push`/`pull`（返回 `SyncResult(uploaded/downloaded/skipped/errors/deleted/removedLocal/failed)`，失败抛 `SyncError`）、`deleteAllRemote`、`deleteAllLocal`
- 单线程顺序执行（安卓端不做多线程分片）；同步配置读 `ConfigStore`（密钥字段已解密）

### 设置页同步接线（SettingsActivity.kt）
- `sp_sync_type` 位置→`sync_type` 映射：0 无 / 1 ftp / 2 s3 / 3 r2 / 4 webdav（`syncTypes` 列表）；`loadConfig` 回填 `setSelection`，`saveConfig` 写入
- 测试连接/检查状态/上传/下载按钮跑后台 `Thread` 后 `runOnUiThread` 用 Toast 呈现；`btn_sync_push` 文本作进度占位；危险操作（删除本地/云端）先弹确认框
- 「删除本地所有」复用 `MemeDb.deleteAll` + 清理 cache/缩略图；「删除云端所有」遍历远端清单删除文件+清单
- 网格间距：`item_meme.xml` 卡片 `layout_margin 5dp`（对应桌面端网格 `gap: 10px`）

## 构建 & 验证
```bash
./gradlew :app:compileDebugKotlin   # 快速编译验证（约 12-19s）
./gradlew :app:assembleDebug        # 完整构建 APK
```
- **跑 gradle 必须设置超时**（`timeout` 参数 600s+），否则可能卡死
- 用户通常自行 `assembleDebug`，改动后先跑 `compileDebugKotlin` 验证

## CI（.github/workflows，参考桌面端）
- `check.yml`：push/PR 触发，JDK 17 跑 `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest`
- `build.yml`：Check 成功（main）或手动触发，跑 `assembleDebug` 并上传 `app/build/outputs/apk/debug/*.apk` artifact

## 已实现 / 未实现
### 已实现
- 主界面 / 设置页暗色 UI 复刻
- 存储层：路径、SQLite 数据库、JSON 配置 + 密钥加密
- 缓存扫描、SAF 导入、缩略图生成
- 搜索（关键词实时）+ 空状态切换
- 设置页保存/重置接真实配置
- 首次运行存储位置选择
- 版本更新检查（GitHub Releases API）
- GIF 动图播放（`auto_play_gif` 开关控制，WebP 动图亦支持）
- 长按右键菜单（重命名/收藏/添加分组/从最近使用中删除/删除）
- 表情网格间距（卡片 5dp 外边距）
- 更新下载镜像源回退（github.dpik.top 等 4 个镜像 + 直连）
- 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单 push/pull/test/status/清理云端孤儿）
- 最近使用记录：点击网格卡片 `recordUse` 记入 recent_uses，最近使用分组自动刷新
- 启动自动同步：MainActivity 启动读 `sync_auto_sync`/`sync_auto_fetch_index` 配置，后台执行 pull/checkSyncStatus
- 日志导出：设置页 `ACTION_CREATE_DOCUMENT` 选保存位置，后台 logcat `--pid` 写入文本文件
- 顶部快捷同步：主界面标题栏上传/下载图标，一键 push/pull
- 同步进度/完成弹窗：`quickSync` 走独立 `syncExecutor`（不占共享 executor，避免大文件同步卡 UI）；按 `show_upload_progress`/`show_download_progress` 显示 `dialog_sync_progress`（进度条/百分比/速度/当前文件/「后台运行」按钮），`show_upload_done`/`show_download_done` 控制 `dialog_sync_done` 完成弹窗；后台运行后仅 Toast 摘要
- 修改存储位置：设置页 `ACTION_OPEN_DOCUMENT_TREE` 选新 localdata 目录，弹窗询问是否转移现有文件（数据库/缓存/缩略图），config.json 保持不变
- 隐写 GIF 解码导入（STG3 检测 + 7 种模式还原，fixture 单测逐字节对齐 Pillow）
- 小分组（子分组）创建与顶栏嵌套胶囊展示（1 层限制，长按分组胶囊新建 + 「加入小分组」）
- 分组管理：长按分组胶囊重命名/删除（成员移回上层），最近使用分组「清空最近使用」
- 拖拽排序：标题栏「排序」开关 + ItemTouchHelper 长按换位，全局 `reorderMemes` / 分组内 `reorderCollectionMembers` 落库
- 点击分享：点击网格卡片经 FileProvider（`file_paths.xml` 缓存路径）把原图复制到内部 cache 后用 `ACTION_SEND` 打开系统分享（微信/QQ 等），同时 `recordUse` 记最近使用
- 接收分享导入：MainActivity 声明 `ACTION_SEND`/`ACTION_SEND_MULTIPLE`（image/*）intent-filter，`onCreate`/`onNewIntent` 取 `EXTRA_STREAM` URI 列表直接 `doImport`

### 未实现（后续待做）
- 从手机QQ缓存导入（当前为占位 Toast，后续用 Shizuku 授权后 ADB 获取文件）
