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
    ChipAdapter.kt      # 标签/分组胶囊（TAG/COLLECTION 两种样式）
    MemeGridAdapter.kt  # 表情网格，异步缩略图加载，按 meme.id 打 tag 防错位
    Meme.kt             # 数据模型（对应 memes 表，含 stego/fromStego 字段）
    MemeDb.kt           # SQLite 封装（7 表 + 索引 + 列迁移）
    ConfigStore.kt      # JSON 配置（DEFAULTS 与桌面端 config.py 一致）
    CryptoUtil.kt       # Android Keystore AES-GCM 加解密
    StoragePaths.kt     # 路径解析（base/data/cache/thumbnails/db/config）
    FileUtils.kt        # SHA-256 + 魔数识别扩展名
    CacheScanner.kt     # 缓存扫描（双重去重）
    MemeImporter.kt     # SAF 批量导入
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
- SAF `ACTION_OPEN_DOCUMENT` 多选 → 逐文件：查哈希去重 → 魔数识别扩展名 → 拷贝到 `cache/{hash16}{ext}` → 读尺寸 → `addMeme`
- 单文件失败不影响其余文件（catch 后继续）
- 隐写 GIF 解码（桌面端 `_try_decode_stego`）尚未移植

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
- `menu/menu_meme.xml`：重命名 / 收藏（标题随状态切换）/ 添加分组 / 从最近使用中删除 / 删除（红），对齐桌面端 webui 右键菜单
- 删除走 `deleteMemeFiles`：删物理文件（`Thumbnailer.findMemeFile`）+ 删 `{id}_*.png` 缩略图 + `deleteMeme` 删库，与桌面端一致
- 设置页用 Activity Result API（`registerForActivityResult`，`settingsLauncher`）打开，返回 `RESULT_OK` 后 `reloadData()` 使配置（如动图开关）立即生效；导入/选目录/设置统一走 `ActivityResultContracts.StartActivityForResult`

### 标签/分组胶囊过滤（MainActivity.kt）
- 点击 `rv_tags`/`rv_collections` 胶囊过滤表情：标签可多选叠加（`activeTags`），分组单选切换（`activeCollectionId`），再次点击取消；选中态由 `ChipAdapter.activeItems` 控制（accent 色 + active 背景）
- `ChipAdapter` 泛型化（TAG 用 `String`，COLLECTION 用 `CollectionEntry(id,name,count)`），分组胶囊带数量，label 显示 `名称 (count)`
- 分组栏含系统分组：收藏夹 `-2`（`favoriteOnly`）、最近使用 `-3`（`getRecent`），与桌面端 `get_collections` 一致
- 过滤与关键词叠加后走 `MemeDb.search(keyword, tags, collectionId, favoriteOnly)`；收藏夹走 `favoriteOnly`，最近使用走 `getRecent`，无过滤时 `getAll`

### 从分组移除 / 空分组自动删除（MainActivity.kt）
- 长按菜单「从分组移除」仅在查看具体分组（`activeCollectionId > 0`）时显示；从最近使用视图查看时显示「从最近使用中删除」
- 移除逻辑对齐桌面端 webui：若是小分组（`parentId != 0`）移回上层分组；移除后该分组计数为 0 则自动 `deleteCollection`，并把当前视图切回上层（无上层则 null）
- 取消收藏/移出最近使用后，若对应系统分组（收藏夹/最近使用）计数归零，自动退出该视图

### 版本更新（UpdateChecker.kt）
- 桌面端 `updater.py` 迁移：`_parse_version` → `parseVersion`，`_pick_asset_url` → 遍历 assets 找 `.apk`
- GitHub Releases API：`https://api.github.com/repos/OhMyMeme/OhMyMeme-Android/releases/latest`，repo 地址与桌面端不同（Android 仓库）
- 版本比较：`parseVersion` 拆 `v0.1.0` 为 `[0,1,0]`，按位比较（`compareVersions`），大于当前 versionName 视为有更新
- 安卓无法自动安装 APK：检测到新版本用 `AlertDialog` 引导，点击「下载」`ACTION_VIEW` 打开 APK `browser_download_url`（无 apk 资产时回退 release `html_url`）
- **镜像下载**：API 仍直连 GitHub，仅下载地址走镜像。`mirrorDownloadUrl` 按桌面端 `updater.py` `_GH_MIRRORS`（github.dpik.top / gh.dpik.top / gh-proxy.org / proxy.starsfire.top/-----）前缀逐个 HEAD 探测，返回第一个可用镜像 URL，全失败回退直连；`SettingsActivity` 下载时先经 `mirrorDownloadUrl`
- `checkUpdate()` 在后台 `Thread` 跑 `checkLatest`（网络阻塞），`runOnUiThread` 回主线程更新按钮状态/弹窗；`UpdateInfo` 的 `error` 字段承载网络失败文案（未接显示系统，暂以 Toast 呈现）

### 云端同步（CloudSync.kt）
- 对齐桌面端 `sync.py` + `manifest.py`：远端目录 `memes/`（REMOTE_MEME_DIR）+ `meme-index.json`（INDEX_FILENAME，清单 version 3）；远端根：FTP→`ftp_path`、WebDAV→`webdav_path`、对象存储→空
- 清单字段与桌面端一致：`memes[]`（filename/name/sha256/file_size/mtime，name 取 `original_name` 空时回退文件名去扩展名）+ `collections[]`（嵌套树，name/filenames/children；空集合在构建时自动 `deleteCollection`，与 `_build_collection_tree` 一致）
- 后端实现（无第三方依赖，纯 `java.net`）：FTP 手写控制/数据通道（被动模式 PASV，STOR/RETR/SIZE/DELE/NLST/MKD，UTF-8）；S3/R2 用 `S3Backend`（isR2 标志读 r2_* 配置，AWS SigV4 手写签名，endpoint=`https://{account_id}.r2.cloudflarestorage.com`，list 用 `<Key>` 正则解析 ListObjectsV2）；WebDAV 用 HTTP（MKCOL 幂等建目录/PUT/GET/PROPFIND/HEAD 回退/DELETE）
- `downloadIndex` 下载远端清单到 dataDir 临时文件再解析，失败清理；`writeTempIndex` 上传前写本地临时清单
- `push`：本地清单与远端清单按 `filename+sha256` 比对，相同且远端文件存在则跳过；`sync_delete_remote` 时删除远端多余文件；成功后合并远端仍保留的孤儿项重建清单并上传；上传失败即抛 `SyncError` 不更新远端清单
- `pull`：下载清单→按哈希/文件存在跳过→下载缺失文件（空文件计失败并清理）→`getByFilename` 无记录时读尺寸 `addMeme`；`sync_remove_local` 时删除本地多余文件+库记录+缩略图；`applyRemoteCollections` 按远端分组建集合并挂成员（顶层，含子集合的文件已并入父集合 filenames）
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
- 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单 push/pull/test/status）

### 未实现（后续待做）
- 点击复制到剪贴板（桌面端 clipboard_util 移植）
- 标签/分组管理交互（增删改）
- 隐写 GIF 解码导入
