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
- 占位图用 `ic_photo` + muted 色，加载后 `setColorFilter(null)` 清色
- 名称取 `original_name`，为空回退文件名去扩展名

### 版本更新（UpdateChecker.kt）
- 桌面端 `updater.py` 迁移：`_parse_version` → `parseVersion`，`_pick_asset_url` → 遍历 assets 找 `.apk`
- GitHub Releases API：`https://api.github.com/repos/OhMyMeme/OhMyMeme-Android/releases/latest`，repo 地址与桌面端不同（Android 仓库）
- 版本比较：`parseVersion` 拆 `v0.1.0` 为 `[0,1,0]`，按位比较（`compareVersions`），大于当前 versionName 视为有更新
- 安卓无法自动安装 APK：检测到新版本用 `AlertDialog` 引导，点击「下载」`ACTION_VIEW` 打开 APK `browser_download_url`（无 apk 资产时回退 release `html_url`）
- `checkUpdate()` 在后台 `Thread` 跑 `checkLatest`（网络阻塞），`runOnUiThread` 回主线程更新按钮状态/弹窗；`UpdateInfo` 的 `error` 字段承载网络失败文案（未接显示系统，暂以 Toast 呈现）

## 构建 & 验证
```bash
./gradlew :app:compileDebugKotlin   # 快速编译验证（约 12-19s）
./gradlew :app:assembleDebug        # 完整构建 APK
```
- **跑 gradle 必须设置超时**（`timeout` 参数 600s+），否则可能卡死
- 用户通常自行 `assembleDebug`，改动后先跑 `compileDebugKotlin` 验证

## 已实现 / 未实现
### 已实现
- 主界面 / 设置页暗色 UI 复刻
- 存储层：路径、SQLite 数据库、JSON 配置 + 密钥加密
- 缓存扫描、SAF 导入、缩略图生成
- 搜索（关键词实时）+ 空状态切换
- 设置页保存/重置接真实配置
- 首次运行存储位置选择
- 版本更新检查（GitHub Releases API）

### 未实现（后续待做）
- 点击复制到剪贴板（桌面端 clipboard_util 移植）
- GIF 动图播放
- 标签/分组管理交互（增删改）
- 远程同步 FTP/S3/R2/WebDAV
- 隐写 GIF 解码导入
