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
- **Kotlin** + AppCompat + RecyclerView + ConstraintLayout（无 Compose），**JDK 17+**
- **AGP 9.0.0** + Gradle 9.1，依赖用 `gradle/libs.versions.toml` 版本目录管理
- **SQLite** (WAL)，schema 与桌面端 `src/database.py` 一致
- **Android Keystore** (AES-GCM) 加密配置密钥字段
- **SAF**（Storage Access Framework）批量导入，免存储权限
- minSdk 28 / targetSdk 36 / compileSdk 36

## 核心原则
- **不重构桌面端** — 桌面端 `若用户提供了桌面端本地源码位置` 仅做最小必要修改；如需同步桌面端数据层逻辑，以 `database.py`/`config.py`/`webui.py` 为唯一事实来源
- **存储结构对齐桌面端** — 表 schema、列名、重命名规则、缩略图命名、去重逻辑逐条对照，不得随意改动
- **增改同步** — 新功能/新文件必须同步更新 `README.md`、`CONTRIBUTING.md` 和 `AGENTS.md`（用户可见改动进 README，构建/签名/CI 信息进 CONTRIBUTING，实现细节进 AGENTS）
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
    StoragePaths.kt     # 路径解析（base/data/cache/thumbnails/db/config + SAF 树持久化 + .nomedia 标记 ensureNomedia）
    StorFile.kt         # 统一文件句柄：SAF content URI / 真实路径 双模式读写 cache/thumbnails
    FileUtils.kt        # SHA-256 + 魔数识别扩展名
    CacheScanner.kt     # 缓存扫描（双重去重）
    MemeImporter.kt     # SAF 批量导入（含 20MiB/2560px 上限，ImportOutcome/ImportResult）
    ShizukuBridge.kt    # Shizuku 权限三态 + 远程 shell 执行（newProcess 经反射，stderr 后台排空）
    QqCacheImporter.kt  # 手机QQ缓存扫描（收藏/聊天图片/表情候选根）+ 多选导入/SAF 转存
    SidebarTreeAdapter.kt # 分组树侧栏适配器（CollectionEntry/CollectionNode/SidebarRow 平铺树）
    GifFrameDecoder.kt  # 自研最小 GIF 解码器（LZW/interlace/色板，与 Pillow 一致）
    GifEncoder.kt       # 自研最小 GIF 编码器（median cut 256 色 + LZW，与 GifFrameDecoder 严格对应）
    GifStego.kt         # STG3 隐写检测 + 7 模式解码 + encode 写入（FULL/LZMA/WebP 候选）+ 自研 PNG 编码
    AndroidGifDecoder.kt# 设备端 WebP→RGBA（反预乘 alpha）
    Thumbnailer.kt      # 缩略图生成 {id}_{size}.png
    MemeCopyProcessor.kt# 复制处理：分享前按 copy_resize_mode 缩放 WebP / 转 GIF / 转隐写 GIF；copy_avoid_webp 开关走 avoidWebp（静态 WebP→PNG/JPG，动图回退原图）
    WebpAnim.kt         # 动画 WebP 解析（RIFF/VP8X/ANIM/ANMF 子块）+ 帧包装，供 WebP→GIF
    BackupManager.kt    # 设置页 ZIP 备份/恢复（db+config+cache+thumbnails，staging 校验防路径穿越）
    CloudSync.kt        # 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单）+ 云端直接使用（清单缓存/差集合并/点击下载/缩略图预取与补传）
    LanClient.kt        # 局域网互联客户端（UDP 发现 + TCP 握手 + AES-GCM 会话）
    UpdateChecker.kt    # 版本更新检查（GitHub Releases API，24h TTL 启动自动检查）
    SetupGuideActivity.kt # 首次设置向导（5 步：欢迎/存储/复制/云同步/完成）
    QuickTileService.kt # 控制中心快捷磁贴（TileService，点击打开主界面）
  res/
    layout/activity_main.xml / activity_settings.xml / activity_setup_guide.xml / item_* / dialog_tag_editor.xml / dialog_add_collection.xml / dialog_qq_import.xml
    values/colors.xml   # 暗色配色（slate 体系：bg #0D0D0F、card/surface #1A1A1F、fg #E2E8F0、fg_secondary #94A3B8、muted #8A94A8、border #2A2A32、accent #3B82F6、primary_strong #1D4ED8）
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
- `DEFAULTS` 逐字段照搬桌面端 `config.py`（含 `s3_path`、`webdav_timeout`、`record_recent_use=true`、`s3_addressing_style="virtual"`、`s3_signature_version="s3"`、`copy_avoid_webp=false`、`manifest_include_tags=true`、`manifest_include_favorites=true`、`cloud_direct=true`、`cloud_thumb_auto_push=true` 等）
- `SECRET_KEYS` 6 个密钥字段（s3_access_key/s3_secret_key/r2_access_key_id/r2_secret_access_key/ftp_password/webdav_password）写入前加密、读取后解密
- `load()` 在读取时对密钥字段先解密；`save()` 加密副本后写盘；损坏文件回退默认值；**首次运行文件不存在时自动落盘默认配置**
- 与桌面端差异：桌面端 Fernet，安卓端用 Android Keystore（硬件背书），格式不互通但字段名一致

### 首次设置向导 / 存储位置
- `StoragePaths.isFirstRun` 检测（SharedPreferences 标记 `setup_done`），首次启动进入 `SetupGuideActivity` 5 步向导（欢迎 → 存储位置 → 复制处理 → 云同步介绍（可跳过）→ 完成）；向导可退出，下次启动继续（`RESULT_OK` 才 `reloadData`）
- 存储步：默认位置直接 `markSetupDone`（向导完成时统一打点）；选自定义走 SAF `ACTION_OPEN_DOCUMENT_TREE`，`persistDataTree`（`takePersistableUriPermission` + 探针校验，失败即拒绝并回退默认选项）
- 复制步把 `copy_resize_mode`（从 `copy_mode_options` 动态生成 RadioButton）与 `copy_avoid_webp` 写入 `ConfigStore.save`
- 设置页修改位置：`onStorageDirPicked` 同样先 `persistDataTree`，弹窗询问是否转移，`moveDataToTree` 用 `StorFile` 只拷贝 `cache`/`thumbnails` 两个子目录（绝不拷贝 memes.db），成功后删除源子树并 `applyStorageTree`

### 导入（MemeImporter.kt）
- 点击标题栏「导入」弹 `menu_import.xml` 菜单：从文件导入（`pickImages`，SAF `ACTION_OPEN_DOCUMENT` 多选）/ 从手机相册导入（`pickAlbumImages`：`isPhotoPickerAvailable` 为真走 Photo Picker `PickMultipleVisualMedia`，否则回退 `ACTION_GET_CONTENT` 相册，避免库默认回退文件选择器）/ 从手机QQ缓存导入（Shizuku 授权后扫描 → 目录/文件双栏可视化勾选 → 导入或转存，见下方「从手机QQ缓存导入」小节）
- 两种导入共用 `doImport(uris)` → `MemeImporter.importUris`：逐文件：查哈希去重 → 魔数识别扩展名 → 拷贝到 `cache/{hash16}{ext}` → 读尺寸 → `addMeme`
- **导入上限**（对齐桌面端 `config.py` `_IMPORT_MAX_BYTES`/`_IMPORT_MAX_PX`）：单文件 >20MiB（`MAX_BYTES`）或任一边 >2560px（`MAX_PX`）拒绝导入并计入 rejected（`ImportOutcome` 枚举：`OVER_LIMIT`/`INVALID`/`FAILED`/`DUPLICATE`/`OK`，`ImportResult` 汇总 imported/rejected/skipped/errors）；局域网拉取与云端 pull 复用 `MAX_BYTES` 校验
- 单文件失败不影响其余文件（catch 后继续），结束 Toast 汇总成功/跳过/超限/失败数（`import_rejected`/`import_errors`）
- **隐写 GIF 解码**（对应桌面端 `_try_decode_stego` + `gif_stego.py`）：GIF 且含 `STG3` 时 `GifStego.decode` 还原原图（7 种模式），只导入还原结果 `fromStego=1`，GIF 本身不入库；WebP 模式经 `AndroidGifDecoder.webpToRgba` 解码（反预乘 alpha）
- `GifFrameDecoder` 为自研最小 GIF 解码器（GIF87a/89a、全局/局部色板、LZW、interlace、透明索引直映射 RGB、Pillow 一致的 `(R*299+G*587+B*114+500)/1000` 灰度），与 Pillow 逐字节一致；LZMA 用 `org.tukaani:xz`（`XZInputStream`）；PNG 输出用自研 RGBA 编码器
- `CacheScanner` 不做 STG3 检测（对齐桌面端 `scan_cache`）

### 从手机QQ缓存导入（ShizukuBridge.kt + QqCacheImporter.kt）
- **前提**：需安装并启动 Shizuku（ADB/root 授权）；manifest 声明 `rikka.shizuku.ShizukuProvider`（`${applicationId}.shizuku`，INTERACT_ACROSS_USERS_FULL 保护）与 `<queries>`（`moe.shizuku.privileged.api`），依赖 `dev.rikka.shizuku:api/provider` 13.1.5（`libs.versions.toml`）
- **权限三态**（`ShizukuBridge`）：`available()`（`pingBinder`）→ `hasPermission()`（`checkSelfPermission`）→ `requestPermission`，结果经 `MainActivity.qqPermissionListener`（`Shizuku.addRequestPermissionResultListener`，onCreate 注册 / onDestroy 移除）回调 `beginQqScan()`；未安装/未授权分别 Toast 引导
- **远程执行**：`Shizuku.newProcess` 在 13.1.5 为 private（规划 API 14 移除）→ 反射调用；`exec` 走 `sh -c` 返回退出码 + stdout 文本，`readFile` 直接 `cat` 取字节，stderr 由 daemon 线程排空防管道写满死锁
- **扫描**（对应桌面端 `adb_util` 路径发现，改为手机获取自身缓存）：候选根 = `/storage/emulated/0`、`/sdcard`、`/storage/*` 外置卷 × 后缀（`QQ_Favorite` 桌面同款 / `chatpic` 含 chatraw·chatimg·chatthumb / `files/tencent/MicroMsg/.emotionsm`）；单条命令 `for d in …; [ -d "$d" ] && readlink -f | sort -u` 归一去重（`/sdcard` 与主存储同源不重复），逐根 `find -type f` + `stat -c %s` 输出「size<TAB>path」行解析（跳过 stat 失败、相对路径、`.nomedia` 与 `SKIP_EXT` 非图片扩展名；无扩展名/未知扩展名保留交魔数判定）；候选与根经 `shellQuote` 单引号包裹防注入，结果按根 + 路径排序去重
- **可视化选择界面**（`dialog_qq_import.xml`，仿桌面端添加分组双栏弹窗）：顶部搜索框（命中文件名时右栏跨目录过滤，标签用 `displayLabel` 带相对路径）；左栏目录列表（`dirLabel` 根相对路径 + 总数/已选数，`simple_list_item_activated_1` 单选高亮），右栏文件多选列表（`fileNameLabel` 文件名 + 体积，`simple_list_item_multiple_choice`，跨目录勾选状态按 path 集合持久）；右栏头「全选/清空」一键切换全部文件 + 「（已选 n）」计数；「导入」→ `importSelected`（逐文件 >20MiB 跳过计入 rejected → `MemeImporter.importBytes` 去重/上限/魔数/隐写全链路）；「转存到…」→ 系统 `ACTION_OPEN_DOCUMENT_TREE` 每次让用户指定目录（不持久化授权）→ `exportSelected`（单文件 ≤64MiB 整读 → `exportName` 魔数补正扩展名（QQ 存 .jpg 实为 png/webp，无扩展名补全）→ `contentResolver.openOutputStream` 写入）
- **进度**：两路均复用共享 `SyncProgressDialog`（`report` 累计字节，`syncExecutor` 后台执行），完成 Toast 汇总（导入复用 `import_done`/`import_rejected`/`import_errors`，转存 `qq_export_done`）；单测 `QqCacheImporterTest`（18 例）

### 缓存扫描（CacheScanner.kt）
- 遍历 cache 目录：跳过非图片扩展名、`thumbnails` 路径、与同名 `.webp` 共存的 `.gif`
- **双重去重**：`getByFilename` 跳过已注册 → SHA-256 → `getByHash` 跳过重复内容
- 与桌面端 `scan_cache` 逻辑一致
- **`.nomedia` 标记**（`StoragePaths.ensureNomedia`）：启动（MainActivity executor）、设置页修改存储位置（`applyStorageTree`）、恢复备份、向导选目录后，在 `configRoot`/`dataDir` 真实目录与 SAF 树根写入空 `.nomedia` 阻止媒体库扫描；云端 push/pull/清单、LAN push、缩略图补传全部以数据库条目驱动，`.nomedia` 不在库中故**不会上传云端**；pull 的 `remove_local`/`deleteAllLocal` 按库文件名删除，不触碰 `.nomedia`

### 缩略图（Thumbnailer.kt）
- 命名 `{meme_id}_{size}.png`，存在即复用（与桌面端一致）
- `findMemeFile` 先查缓存根目录，再递归遍历，返回 `StorFile`（对应桌面端 `_find_meme_file`）
- `getThumbBitmap(context, memeId, filename, size)` 读现有缩略图或生成：BitmapFactory `inSampleSize` 先按 2×size 降采样，再 createScaledBitmap 到 150×150，保存 PNG；缓存/缩略图均经 `StorFile` 读写（SAF content URI 或真实路径）
- 云端直接使用辅助：`cloudThumbFile(sha256)` 指向 `thumbnails/{sha256}.webp`、`cloudThumbBitmap` 读云缩略图（文件缺失/解码失败回退占位）、`cloudThumbWebpBytes` 把原图缩放到最长边 150px（宽高比不变）编码 WebP q85（API≥30 走 `WEBP_LOSSY`，低版本 `LOSSY`），`decodeFit(bytes, maxEdge)` 为共用降采样解码

### 网格加载（MemeGridAdapter.kt）
- 单线程 Executor 后台生成/解码缩略图，`img.tag = meme.id` 防列表复用错位
- 占位图用 `ic_photo` + muted 色，加载后 `setColorFilter(null)` + `setImageTintList(null)` 清色/清 XML tint
- 名称取 `original_name`，为空回退文件名去扩展名
- **动图渲染**（对应桌面端 webui `m.is_animated && m.auto_play_gif`）：后台判 `isAnimatedFile`（`FileUtils.isAnimatedFile`：GIF89a 头或 RIFF+WEBP+ANIM；webp 直查 cache 根避免全目录遍历），且 `ConfigStore` 的 `auto_play_gif` 为 true 时用 `ImageDecoder` + `AnimatedImageDrawable`（`setTargetSampleSize` 目标 300）播放原图，否则用静态缩略图；动画解码失败回退缩略图
- 右上角 badge：GIF / WebP（动图）/ 隐写导入（`fromStego==1`），`bg_badge.xml` 蓝底圆角
- **云行分支**（`meme.cloud`）：点击直接交 `onItemClick`（整理模式不勾选、不发起分享前置；下载中点击被吞防重复触发）、长按吞掉（不弹菜单不启动拖拽），隐藏右上「⋯」按钮 / 勾选徽标 / 拖拽 handle，左下角 `tv_cloud_badge` 云朵图标角标（`ic_cloud` 白色描边 24dp + `bg_cloud_badge` 半透明黑圆底，18dp + 3.5dp padding，对齐桌面端 `.cloud-badge`）；缩略图走 `Thumbnailer.cloudThumbBitmap`（`thumbnails/{sha256}.webp`，缺则占位），动图不预解码播放
- **下载遮罩与退场动画**：整卡 `cloud_mask`（`bg_cloud_mask` 半透明黑 8dp 圆角 + 「下载中…」，`item_meme.xml` 置于最顶层）；下载状态存 `MemeGridAdapter` 伴生对象 `downloadingIds`（跨 reloadData 重建 adapter 不丢，bind 时按 `meme.cloud && downloadingIds.contains(id)` 显隐并 `clipBounds=null` 复位）；`setDownloading(id, active)` 增删 + `notifyItemChanged`，`wipeDownloadingMask(id, onWiped)` 用 `ValueAnimator` 驱动 `clipBounds = Rect(0, top, w, h)` 自上而下 0.40s 擦除退场（holder 不可见时直接回调），动画结束清状态并回调 MainActivity 刷新+分享；下载失败即时摘遮罩
- 卡片主体的长按回调 `onLongClick` 由 MainActivity 弹 PopupMenu；主体点击继续分享

### 长按右键菜单（MainActivity.kt）
- `menu/menu_meme.xml`：重命名 / 收藏（标题随状态切换）/ 打标签 / 添加分组 / 加入小分组（仅查看具体分组时显示）/ 从分组移除 / 从最近使用中删除 / 删除（红），对齐桌面端 webui 右键菜单
- 「添加分组」走两段式 `promptAddCollection`（对齐桌面端 CollectionBuilder 的精简版）：`dialog_add_collection.xml` 输入新分组名创建，或从「加入已有分组」列表（仅 id>0 真实分组，`item_add_collection_row.xml`）点选即加入
- 删除走 `deleteMemeFiles`：删物理文件（`Thumbnailer.findMemeFile`）+ 删 `{id}_*.png` 缩略图 + `deleteMeme` 删库，与桌面端一致
- 设置页用 Activity Result API（`registerForActivityResult`，`settingsLauncher`）打开，返回 `RESULT_OK` 后 `reloadData()` 使配置（如动图开关）立即生效；导入/选目录/设置统一走 `ActivityResultContracts.StartActivityForResult`

### 分组胶囊过滤（MainActivity.kt）
- 顶栏标签行（`rv_tags`）点击标签过滤表情：多选叠加（`activeTags`，全含匹配 `memeIdsWithAllTags`），再次点击取消；选中态由 `ChipAdapter.activeItems` 控制（accent 色 + active 背景）
- 分组/收藏/未分类过滤走左侧常驻侧栏（`rv_sidebar`，`SidebarTreeAdapter`，`SidebarRow(entry,depth,expanded)` 平铺树，箭头点击展开收起、整行点击单选切换 `activeCollectionId`，默认收起），分组单选切换，再次点击取消；**滑动手势**：侧栏收起时从屏幕左缘（≤16dp）右滑 ≥64dp 展开，展开时在侧栏内左滑 ≥64dp 收起（均要求水平主导），`MainActivity.dispatchTouchEvent` 仅观察不拦截子视图，触发后给子视图补发 CANCEL 并吞掉 UP，避免滑动收起时误触分组行点击
- `ChipAdapter` 泛型化（TAG 用 `String`，COLLECTION 用 `CollectionEntry(id,name,count,hasChildren)`），分组胶囊带数量，label 显示 `名称 (count)`；有子分组时追加 `▼`
- 系统分组：收藏夹 `-2`（`favoriteOnly`）、最近使用 `-3`（`getRecent`）、未分类 `-4`（`uncategorizedOnly`，受 `ConfigStore` 的 `show_uncategorized` 控制且仅在计数 > 0 时显示，对齐桌面端 `webui.py` 系统分组），与桌面端 `get_collections` 一致
- 过滤与关键词叠加后走 `MemeDb.search(keyword, tags, collectionId, favoriteOnly, offset, limit)`；收藏夹走 `favoriteOnly`，最近使用走 `getRecent`，无过滤时 `getAll`。`collectionId != null` 时 ORDER BY 按 `meme_collections.sort_order`（子查询）排序，与桌面端分组内排序一致

### 标签系统（MainActivity.kt + MemeDb.kt）
- 长按菜单「打标签」（`act_tag`）→ `promptEditTags(meme)`：`dialog_tag_editor.xml`（输入框 + `rv_tag_list` + 已选摘要 + `tv_tag_empty` 空态提示），输入框实时过滤已有标签（`getAllTags`），点选/取消多选，回车把当前输入文本作为新标签加入，保存走 `setMemeTags`；`bind()` 按过滤结果切换空态提示可见性
- `setMemeTags` 重写后新增孤儿标签清理（`DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tag_id FROM meme_tags)`），对齐桌面端 `set_meme_tags` 的清理逻辑
- 标签行过滤与分组/关键词叠加，全含匹配（`memeIdsWithAllTags(tags)` 逐标签 INTERSECT），对齐桌面端 `search_memes` 的 `tags` 参数
- 批量打标签：整理模式操作栏「打标签」按钮 → `promptEditTagsInternal(ids, replace=false)`，在每个表情既有标签上追加所选（`getMemeTags(mid)+picked` 去重并集）；单张入口仍 `replace=true` 覆盖；批量保存后自动退出整理模式并刷新
- 同步并入：`MemeDb.mergeMemeTags(memeId, tags)` 事务内 trim/去空/去重后 `INSERT OR IGNORE`，**只增不减**（不做孤儿清理，与 pull/LAN applyRemoteTags 并集语义配套）

### 整理模式（MainActivity.kt + MemeGridAdapter.kt）
- 顶栏排序图标（`btn_sort_mode`，`ic_sort`，contentDescription「整理模式」）进入**整理模式（多选批量删除 + 拖拽排序共存）**，对齐桌面端多选操作栏
- 整理模式下：卡片显示勾选徽标（`tv_select_check`，`bg_select_check` 圆底 ✓），点击卡片切换选中（不走分享/记录最近使用），菜单按钮隐藏；**拖拽 handle 始终可见可拖拽**（`canOrder = manageMode || sortModeEnabled`），tap=选中/handle=排序共存；底部操作栏 `manage_bar`（「已选 n 项 / 全选 / 取消 / 批量删除 / 加入分组 / 打标签」，网格底部 padding 加大防遮挡）
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
- 拖拽排序已融入整理模式：整理模式下拖拽 handle 始终可见可拖拽，tap=选中，handle=排序，共存不互斥
- 仅当关键词为空、当前为全局视图或正数真实分组，且网格至少有 2 张卡片时，卡片左上拖拽手柄显示并允许排序
- 搜索中，以及收藏夹 `-2`、最近使用 `-3`、未分类 `-4` 视图均隐藏手柄且不得重排
- `ItemTouchHelper` 的 `SortCallback.isLongPressDragEnabled()` 返回 false。仅手柄按下后拖动可调用 `startDrag`；卡片主体点击继续分享，主体长按继续打开右键菜单
- `clearView` 落库：`activeCollectionId > 0` 时 `reorderCollectionMembers(cid, ids)`，否则 `reorderMemes(ids)`，与桌面端 `reorder_collection_members`/`reorder_memes` 一致
- `MemeGridAdapter` 持有可变 `items`，`move(from,to)` 用 `notifyItemMoved`，`currentIds()` 供落库取序

### 版本更新（UpdateChecker.kt）
- **启动自动检查（24h TTL）**：`shouldCheck`/`markChecked`（SharedPreferences `update_check`，发起即打点），距上次检查超 24h 才在启动后台自动查一次（首跑跳过），避免每次启动重复弹更新框；设置页手动检查不受 TTL 限制
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
- 清单字段与桌面端一致：`memes[]`（filename/name/sha256/file_size/mtime，name 取 `original_name` 空时回退文件名去扩展名）+ `collections[]`（嵌套树，name/filenames/children；空集合在构建时自动 `deleteCollection`，与 `_build_collection_tree` 一致）；**`tags: [...]` 数组随条目写入**（受设置 `manifest_include_tags` 门控，默认开；关闭时条目不含该键），不再输出顶层 `tag_map`（读侧仍兼容回退）；**顶层 `favorite` 文件名数组**（无收藏为 `[]`，取自 `db.search(favoriteOnly=true)`，受设置 `manifest_include_favorites` 门控，默认开；关闭时整个 `favorite` 键不写入）
- 后端实现（无第三方依赖，纯 `java.net`）：FTP 手写控制/数据通道（被动模式 PASV，STOR/RETR/SIZE/DELE/NLST/MKD，UTF-8）；S3/R2 用 `S3Backend`（isR2 标志读 r2_* 配置；**S3 兼容阿里云 OSS**：`signature_version="s3"` 走 SigV2 签名（HMAC-SHA1 + `Authorization: AWS ak:base64`，`signV2`），`addressing_style="virtual"` 走虚拟主机寻址 `https://bucket.endpoint/key`（`urlForKey`），默认即 V2+virtual（对齐桌面端 `config.py` 默认）；R2 强制 SigV4 + path 寻址不变；endpoint=`https://{account_id}.r2.cloudflarestorage.com`，list 用 `<Key>` 正则解析 ListObjectsV2）；WebDAV 用**原始 socket HTTP/1.1**（`davHttp`：任意方法 PROPFIND/MKCOL/PUT/GET/HEAD/DELETE，HTTPS 走 SSLSocket，每次独立建连 `Connection: close` 不复用，Content-Length/chunked/读到关闭三种响应体读取，下载流式写盘 `sink`）——因 Android `HttpURLConnection` 拒绝 PROPFIND/MKCOL（`ProtocolException: Expected one of ...`），与 FTP 一样手写协议层
- `downloadIndex` 下载远端清单到 dataDir 临时文件再解析，失败清理；`writeTempIndex` 上传前写本地临时清单
- `push`：本地清单与远端清单按 `filename+sha256` 比对，相同且远端文件存在则跳过；`sync_delete_remote` 时删除远端多余文件；成功后合并远端仍保留的孤儿项重建清单并上传；上传失败即抛 `SyncError` 不更新远端清单
- **Backend 接口只接受真实 `File`**（`uploadFile(local: File)/downloadFile(remotePath, dest: File)` 三实现不变）；cache/thumbnails 在 SAF 模式下经 `StorFile` 读写，push 上传前 `StorFile.copyTo(context.cacheDir 临时文件)` 物化，pull 先下载到 `context.cacheDir` 临时文件再 `createFile(fname, mime).writeFrom(tmp)` 落入 cache，用完删除
- `pull`：下载清单→按哈希/文件存在跳过→下载缺失文件（超限文件跳过并删除临时文件、空文件计失败并清理）→`getByFilename` 无记录时读尺寸 `addMeme`；`sync_remove_local` 时删除本地多余文件+库记录+缩略图；`applyRemoteCollections` 按远端分组建集合并挂成员（顶层，含子集合的文件已并入父集合 filenames）；`applyRemoteOrder` 按远端 manifest 的 `memes` 顺序重排本地 `sort_order`（`reorderMemes`，`isSafeRemoteFname` 校验文件名），保留云端排序，避免再 push 覆盖远端顺序（对齐桌面端 `_apply_remote_order`，removeLocal 分支也执行）；`applyRemoteTags` 按条目 `tags`（缺失回退顶层 `tag_map`）经 `mergeMemeTags` **并集只增**合入本地（不清理本地独有标签，removeLocal 分支同样执行），不受 `manifest_include_tags` 开关限制；`applyRemoteFavorites` 按顶层 `favorite` 文件名数组（`favoriteFilenamesFrom` 纯函数过滤非字符串/`isSafeRemoteFname` 不安全名）经 `MemeDb.addFavorite`（`INSERT OR IGNORE`）**并集只增**合入本地收藏，removeLocal 分支同样执行，不受 `manifest_include_favorites` 开关限制
- 公开 API：`syncTest`（返回 "ok" 或错误信息）、`checkSyncStatus`（返回本地/远端计数与仅本地/仅远端文件名摘要）、`push`/`pull`（返回 `SyncResult(uploaded/downloaded/skipped/errors/deleted/removedLocal/failed)`，失败抛 `SyncError`）、`deleteAllRemote`、`deleteAllLocal`
- 单线程顺序执行（安卓端不做多线程分片）；同步配置读 `ConfigStore`（密钥字段已解密）
- **云端直接使用（cloud_direct，默认开）**：主界面默认视图把远端 `memes/` 中尚未下载的表情合并展示（带「云」角标），仅在无关键词/标签/分组过滤的全局视图合并
  - 清单缓存：`loadCloudManifest`（内存 → `dataDir/cloud-index.json` 文件双层，损坏/不存在回退 null）、`refreshCloudManifest`（`downloadIndex` 成功后写缓存，失败保留旧缓存）、`clearCloudCache`（`sync_type`/`cloud_direct` 变更时由设置页调用）
  - 差集与合并：`cloudMissing`（`isSafeRemoteFname` + 64 位小写 `isSafeSha` 校验、跳过已本地文件名，携带条目 `tags`、`cloudCollectionPaths` 分组全路径、`favorite` 收藏）、`cloudMeme(CloudEntry)`（云行 id = `-(清单位置+1)` 负数，保证 adapter tag 与整理模式 id>0 过滤互不冲突）、`mergeCloudOrder`（不在清单的本地行按原序排最前，其余本地行与云行按清单序 `manifestOrder` 穿插，云行补到尾部）
  - 预取与补传：`prefetchCloudThumbs`（差集下载 `thumbnails/{sha}.webp`，单张失败整体重试一遍）、`autoPushThumbs`（三重门控 `cloud_direct` + `cloud_thumb_auto_push` + `sync_type`；远端 `thumbnails/` listFiles 差集，缺失项用 `Thumbnailer.cloudThumbWebpBytes` 生成 150px WebP q85 上传，list 结果为空回退 `fileExists` 单查）
  - 点击下载：`downloadCloudMeme(ctx, row)` 状态对齐桌面端 `download_meme`（`disabled`/`no_sync`/`not_found`/`busy` 防重入/`download_failed`/`sha_mismatch`/`too_large`/`invalid_image`/`ok`）：流式 SHA-256 比对 → `MemeImporter.MAX_BYTES`/`MAX_PX` 上限 → 按**清单文件名**去重入库（哈希命中复用已有本地行）→ `cloudBackfill` 后补标签（`mergeMemeTags` 并集）/ 分组链（逐段 `createCollection` 复用）/ 收藏（`addFavorite` INSERT OR IGNORE），返回 `CloudDownloadResult` 供主界面刷新 + 自动分享

### 设置页同步接线（SettingsActivity.kt）
- `sp_sync_type` 位置→`sync_type` 映射：0 无 / 1 ftp / 2 s3 / 3 r2 / 4 webdav（`syncTypes` 列表）；`loadConfig` 回填 `setSelection`，`saveConfig` 写入
- `sw_manifest_tags`（「将标签写入同步清单」，默认开）读写 `manifest_include_tags`，`loadConfig`/`saveConfig` 双向接线，只门控 `buildManifest` 写入；`sw_manifest_favorites`（「将收藏夹写入同步清单」，默认开）同理读写 `manifest_include_favorites`
- 「云端直接使用」（`sw_cloud_direct`，默认开）读写 `cloud_direct`、「启动时自动上传缩略图到云端」（`sw_cloud_thumb_push`，默认开）读写 `cloud_thumb_auto_push`，`loadConfig`/`saveConfig` 双向接线；`saveConfig` 检测 `sync_type` 由空首次变为非空时先弹确认框「开启云端直接使用？」（「开启」= 勾选 cloud_direct、「关闭」= 取消勾选、取消/Esc 保持当前勾选，三个出口均经 `doSaveConfig` 落盘，对齐桌面端 `showConfirm`）；`doSaveConfig` 内 `sync_type` 或 `cloud_direct` 变更时调用 `CloudSync.clearCloudCache` 清清单缓存
- 测试连接/检查状态/危险操作（删除本地/云端）/孤儿清理跑后台 `Thread` 后 `runOnUiThread` 用 Toast 呈现；上传/下载走 `runCloudSync(isUpload)`：按 `show_upload_progress`/`show_download_progress` 弹共享进度对话框（`SyncProgressDialog`），完成后 Toast 摘要；`runSync(btnId, label, progressRes, block)` 恢复按钮文本并统一启停进度
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
- **传输进度（meta 总量）**：`pull`/`push` 按待传条目预算 `files_total`（pull = 清单中安全且本地不存在；push = 待推列表）与 `bytes_total`（`StorFile.length` 之和），逐条目帧附带 `meta: {files_total, bytes_total}`（`pull_file`/`push_file`/`get_config`/`send_config`，纯增量协议旧端自动忽略；配置同步 `get_config` 为 1 文件 0 字节、`send_config` 按载荷字节数）；每条目 `finally { progress.report(transferred, filename) }` 保证恰好一次回调，供设置页进度对话框显示
- **pull**：`pullManifest` → 遍历 `memes[]` 逐文件四重校验（文件名安全 `isSafeRemoteFname`、单文件 ≤20MiB `MemeImporter.MAX_BYTES`、清单 `sha256` 哈希一致、`MemeImporter.isValidImageContent` 魔数+可解码）→ 通过才 `getByFilename` 去重 → `pullFile` 字节 → `MemeImporter.importBytes`（内部同样先校验可解码再落盘，杜绝孤儿文件）→ `CloudSync.applyRemoteOrder` 回写本地排序 → `CloudSync.applyRemoteCollections` 同步分组（递归子集合）→ `CloudSync.applyRemoteTags` 同步标签 → `CloudSync.applyRemoteFavorites` 同步收藏（顶层 `favorite`，并集只增）；任一检查不过即跳过计入 failed 且不落盘
- **push**：先 `pullManifest` 拿远端文件名集合 → 本地 `getAll` 逐个 `pushFile`（桌面端 `_import_bytes` 内部哈希去重幂等）→ 最后 `pushManifest(CloudSync.buildManifest)` 同步顺序/分组/标签/收藏（条目内 `tags` 数组受 `manifest_include_tags` 门控，顶层 `favorite` 受 `manifest_include_favorites` 门控）
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
- `MemeCopyProcessor.process(context, file)`：`copy_resize_mode==0` 或 `isAnimatedFile`（动图）或未超 `copy_resize_max` 时返回 null 回退原图；模式 1 缩放 WebP(q90，ARGB_8888 解码，LANCZOS 语义用 `createScaledBitmap` 替代)、模式 2 转 GIF、模式 3 转隐写 GIF；**`copy_avoid_webp`（对齐桌面端 `webui.py` copy_meme 的 avoid）开启时**：动图直接回退原图，静态 WebP 走 `avoidWebp`（不生成不透明 WebP）——超限缩放改输出 PNG（有 alpha）/ JPG（`staticWebpToJpg` 白底 flatten，对齐 `_static_webp_to_jpg`），未超限静态 WebP 亦转 JPG；`toResized` 按 avoid 切换 PNG/JPG/WebP 输出
- **动画 WebP → GIF**：`WebpAnim.parse`（RIFF/WEBP/VP8X 动画标志/ANIM 循环与 BGRA 背景/ANMF 帧头：24bit 尺寸与时长、no-blend=bit1、dispose 背景=bit0、VP8L/VP8/ALPH 子块）→ 逐帧 Canvas 合成（pendingClear dispose、no-blend CLEAR、等比缩放 ≤maxSide 不放大、argbToRgba）→ `GifEncoder.encodeAnimated`（GIF89a、无 GCT、NETSCAPE2.0 含终止字节 0x00、逐帧 GCE packed=0x04|(trans?1) delay=max(2,(ms+5)/10)、图像描述符局部色板 256、LZW 子块、trailer 0x3B）；`quantize(reserveTransparent)` 有透明帧时预留 1 色并记录 transIdx，不透明帧单帧字节与原实现一致
- 像素对齐 Pillow：`getPixels` 取预乘 ARGB 后**反预乘**还原真实 RGB（同 `AndroidGifDecoder`）；kind 判定 `bmp.hasAlpha()`→RGBA、全像素 r==g==b→L、否则 RGB（对应桌面端 `_delta_data` 的 mode 判定）
- `GifEncoder`（纯 JVM，可单测）：median cut 量化到 ≤256 色 + GIF89a/LZW 编码；**LZW 码长升位时机 = 新增条目后 `nextCode == (1 shl codeSize) + 1`**（非标准实现常见的 `== 1 shl codeSize`），与 `GifFrameDecoder.lzwDecode` 的 `dict.size == 1 shl codeSize` 延迟升位严格对应，已用 Python+Pillow 跨 512/1024/2048 边界与表满场景逐字节验证
- `GifStego.encode(gifData, origBytes, origExt, origPixels, kind, w, h, webpLossless?)`：生成 FULL（`extLen+ext+origBytes` 整体 LZMA）与差值候选（LZMA 恒生成；WebP 候选仅当 `webpLossless` 回调返回非空，Android 端 API 30+ 用 `WEBP_LOSSLESS` 保证无损，低版本跳过），取 payload 最小者；LZMA 用 `XZOutputStream`（`LZMA2Options(6)`，preset 9 太重）；RGBA 不生成 WebP 候选（libwebp 可能改写全透明像素 RGB）
- 单测：`GifEncoderTest`（256 色内逐字节精确、灰度精确、跨边界稳定性）、`GifStegoEncodeTest`（RGB/L/RGBA 差值与 FULL 全图 encode→decode 逐字节还原，FULL 用「极小 origBytes + 大差值」保证选中）、`WebpAnimTest`、`GifEncoderAnimatedTest`（动画头/GCE/子块遍历）、`NameSorterTest`

### 已实现
- 主界面 / 设置页暗色 UI 复刻 + 桌面端布局复刻（顶栏折叠按钮 + logo + 图标、搜索框独立一行、标签行、左侧常驻分组树侧栏 `rv_sidebar` 默认收起）
- 存储层：路径、SQLite 数据库、JSON 配置 + 密钥加密
- 缓存扫描、SAF 导入（20MiB/2560px 上限，ImportOutcome/ImportResult 汇总）、缩略图生成
- 搜索（关键词实时，含**标签名**，对齐桌面端 `search_memes`）+ 标签行过滤（多选叠加，全含匹配）+ 空状态切换（插画 + 导入按钮）
- 设置页保存/重置接真实配置
- 首次设置向导（`SetupGuideActivity` 5 步：欢迎/存储/复制/云同步/完成，替换原首启 AlertDialog）
- 版本更新检查（GitHub Releases API，列表路径跳过 draft/prerelease/nightly/beta/rc 只推正式版；24h TTL 启动自动检查）
- GIF 动图播放（`auto_play_gif` 开关控制，WebP 动图亦支持）
- 长按右键菜单（重命名/收藏/打标签/添加分组（两段式）/从最近使用中删除/删除）
- 表情网格间距（卡片 5dp 外边距）
- 更新下载镜像源回退（github.dpik.top 等 4 个镜像 + 直连）
- 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单 push/pull/test/status/清理云端孤儿；S3 兼容阿里云 OSS：V2 签名 + 虚拟主机寻址，设置页可切换 V4/AWS 标准）
- 最近使用记录：点击网格卡片 `recordUse` 记入 recent_uses（受 `record_recent_use` 开关控制），最近使用分组自动刷新
- 启动自动同步：MainActivity 启动读 `sync_auto_sync`/`sync_auto_fetch_index` 配置，后台执行 pull/checkSyncStatus
- 日志导出：设置页 `ACTION_CREATE_DOCUMENT` 选保存位置，后台 logcat `--pid` 写入文本文件
- 顶部快捷同步：主界面标题栏「更多」菜单提供上传/下载，一键 push/pull
- 同步进度/完成弹窗：`quickSync` 走独立 `syncExecutor`（不占共享 executor，避免大文件同步卡 UI）；按 `show_upload_progress`/`show_download_progress` 显示 `dialog_sync_progress`（进度条/百分比/速度/当前文件/「后台运行」按钮），`show_upload_done`/`show_download_done` 控制 `dialog_sync_done` 完成弹窗；后台运行后仅 Toast 摘要；进度对话框抽为共享组件 `SyncProgressUi.kt`（`SyncProgressDialog.show(activity, title)`/`progress`/`dismiss()`/`showDone()`，字节制百分比封顶 99、未知总量回退文件数），主界面 `quickSync`、设置页云端同步（`runCloudSync`）与局域网传输（`lanOp`/`configOp`/`runKeyOp`）共用；LAN 进度对话框不接 `show_*_progress` 开关（总是显示）
- 修改存储位置：设置页 `ACTION_OPEN_DOCUMENT_TREE` 选新目录，`persistDataTree` 校验后弹窗询问是否转移；SAF 全量支持（`StorFile` 经 content URI 读写 cache/thumbnails，`memes.db` 留在真实路径），只转移 cache/thumbnails 两个子目录，config.json 保持不变
- 隐写 GIF 解码导入（STG3 检测 + 7 种模式还原，fixture 单测逐字节对齐 Pillow）
- 小分组（子分组）创建与顶栏嵌套胶囊展示（1 层限制，长按分组胶囊新建 + 「加入小分组」）
- 分组管理：长按分组胶囊重命名/删除（成员移回上层），最近使用分组「清空最近使用」
- 标签系统：`promptEditTags` 对话框搜索/点选/回车新建标签，`setMemeTags` 孤儿标签清理；标签行多选叠加过滤（`memeIdsWithAllTags`），对齐桌面端 TagEditor + App.vue 标签栏
- 整理模式（多选批量操作）：顶栏整理图标进入，点击卡片勾选 + 底部操作栏「已选 n 项 / 全选 / 取消 / 批量删除 / 加入分组 / 打标签」（批量打标签为追加模式，单张仍覆盖），`MemeDb.deleteMemes` 单事务批量删 + 物理文件与缩略图清理；整理模式下拖拽 handle 始终可见可拖拽，tap=选中/handle=排序共存
- 拖拽排序：已融入整理模式。仅在空搜索、全局或正数真实分组且至少 2 张卡片时，卡片左上手柄显示并允许排序。卡片主体点击分享、长按打开菜单，搜索/收藏夹/最近使用/未分类隐藏手柄；全局 `reorderMemes` / 分组内 `reorderCollectionMembers` 落库
- 点击分享：点击网格卡片经 FileProvider（`file_paths.xml` 缓存路径）把原图复制到内部 cache 后用 `ACTION_SEND` 打开系统分享（微信/QQ 等），同时 `recordUse` 记最近使用；分享前按设置页「复制处理」模式处理超限静态图（见下方「复制处理」小节）
- 复制处理（GifEncoder + GifStego.encode + MemeCopyProcessor）：对应桌面端 `clipboard_util.py` `convert_image_mode_1/2/3` —— 超过 `copy_resize_max` 上限的静态图在分享前按模式 1 缩放 WebP(q90) / 模式 2 转普通 GIF(256 色) / 模式 3 转隐写 GIF（基座 GIF + STG3 写入原图数据，可无损还原）；动图/未超限/处理失败回退原图直发；`copy_avoid_webp` 开启后动图 WebP 不转 GIF 直发、静态 WebP 走 PNG/JPG 输出（见上方「复制处理」小节）
- 备份与恢复（`BackupManager`）：设置页「备份数据」导出单 ZIP（meta + memes.db（`MemeDb.checkpoint` 先 WAL checkpoint）+ config.json + cache/ 全量 + thumbnails/）；「恢复数据」经 staging 解包校验（拒绝 `..`/绝对路径）后关库替换 DB、替换 config 并 `ConfigStore.reload`、SAF/真实路径双模迁移 cache/thumbnails，覆盖前弹确认框，完成后回设置页触发 `RESULT_OK` 刷新
- Logo 点击回主页：清 `activeTags`/`activeCollectionId`/搜索框并 `reloadData()`
- 云端同步效率：FTP/WebDAV `push`/`pull` 用连接级 `ensuredDirs` 缓存远端目录、循环外仅 `ensureRemoteDir(memeDir)` 一次（消除逐文件 MKO/重复建目录）
- 名称排序：`NameSorter`（数字感知 + 大小写不敏感，对齐桌面端 `_name_sort_key`）取代 SQL `ORDER BY name`
- UI 视觉对齐桌面端 `style.css`：slate 色板、卡片 2dp 描边、按钮/input 4dp、弹窗 12dp 圆角描边（`AlertDialog.OhMyMeme` 用 `android:windowBackground=@drawable/bg_dialog` 提供窗口圆角背景，`android:background` 不作用于窗口会露系统白角；按钮色走 `android:buttonBar*Style`（框架 AlertController 只认 android: 前缀）+ 兼容无前缀 appcompat 版本，标题 `android:windowTitleStyle` 17sp 加粗单行省略）、弹出菜单 8dp、状态栏+导航栏固定 `bg`、空状态插画+导入按钮、主按钮 `#1D4ED8`
- 接收分享导入：MainActivity 声明 `ACTION_SEND`/`ACTION_SEND_MULTIPLE`（image/*）intent-filter，`onCreate`/`onNewIntent` 取 `EXTRA_STREAM` URI 列表直接 `doImport`
- 局域网互联：设置页「局域网互联」区块连接电脑端 `lan.py`，支持扫描发现/配对（发送设备信息待电脑端确认）/IP:端口 直连/拉取/上传/配置双向同步（弹窗确认）/密钥同步（电脑端 `allow_secret_config` 开关开启时动态显示，弹窗警告后同步）；拉取后同步分组（递归子集合）+ 标签
- 「未分类」分组：顶栏胶囊显示未加入任何分组的表情（虚拟分组 `-4`，`MemeDb.search`/`count` 的 `uncategorizedOnly` 参数对应桌面端 `uncategorized_only`），计数 > 0 才显示、清零自动隐藏并退出视图；负数 id 使拖拽排序/长按分组菜单自动禁用；`CloudSync` 清单仅遍历真实 `collections` 表不受影响；设置页「显示『未分类』分组」开关（`show_uncategorized`，默认开，对齐桌面端 `config.py`/`settings.html`）
- 控制中心快捷按钮：`QuickTileService`（`TileService`，manifest 声明 `BIND_QUICK_SETTINGS_TILE` + `quick_settings_tile.xml` 磁贴图标 `ic_qs_tile`），`onClick` 打开 MainActivity（锁屏先 `unlockAndRun`）；设置页「快捷开关」区块 + 「添加到控制中心」按钮弹添加指引（系统磁贴需用户在快捷设置编辑面板手动添加）
- 长按拖拽发送（相册式）：`MemeGridAdapter` 长按卡片直接回调 `onDragStart` → `MainActivity.startGlobalDrag`：`materializeDragFile`（SAF 先 `stor.copyTo(cacheDir)` 物化，统一临时文件路径）→ `FileProvider.getUriForFile` → `ClipData.newUri` → `itemView.startDragAndDrop(DRAG_FLAG_GLOBAL or DRAG_FLAG_GLOBAL_URI_READ)` 跨应用拖入微信/QQ，同时 `recordUse`；`ACTION_DRAG_ENDED` 且未被接收（`!e.result`）时回调 `onDragFailed` 弹原右键菜单兜底；卡片右上角 `btn_meme_menu`（「⋯」）点击回调 `onMenuClick` 打开 `showMemeMenu`；注意鸿蒙/Huawei 上 `TYPE_APPLICATION_OVERLAY` 无法发起全局拖拽，因此拖拽必须由 Activity 窗口内的视图发起（曾用悬浮窗方案失败后改为相册式）；跨应用拖拽需真机验证（接收方是否支持图片拖放，失败有菜单兜底）
- 云端直接使用（`cloud_direct`，默认开）：`ConfigStore` 新增 `cloud_direct`/`cloud_thumb_auto_push`，`Meme.kt` 新增 `cloud` 字段；`MainActivity.startCloudDirect` 启动先用清单缓存合并渲染云行（`reloadData` 默认视图走 `mergeCloudOrder`），后台刷新 `cloud-index.json` 清单重载，`syncExecutor` 依次预取云端缩略图与补传本地缩略图；点击云卡片 `downloadCloudMeme` 下载（先 `setDownloading` 显示整卡 `cloud_mask` 遮罩防重复点击）→ SHA-256/大小/尺寸校验 → 按清单文件名入库 → 后补标签/分组/收藏 → 成功经 `wipeDownloadingMask` 遮罩自上而下退场动画后刷新网格并自动走分享链路（失败即时摘遮罩）；长按云卡片/菜单/整理模式勾选/拖拽排序持久化均过滤负 id 云行（`id > 0`），设置页两个开关 + 首配存储类型「开启/关闭」确认弹窗；单测 `CloudDirectTest`（8 例：合并顺序、差集安全校验、标签/分组/收藏回填、负 id、清单序、sha 校验、默认键）
- 从手机QQ缓存导入（Shizuku）：导入菜单第三项从占位 Toast 改为完整链路 —— Shizuku 权限三态（`ShizukuBridge`）→ 扫描候选根（`QqCacheImporter.scan`，QQ_Favorite/chatpic/表情缓存，跳过 `.nomedia`）→ 目录/文件双栏可视化勾选（`dialog_qq_import.xml`：搜索、全选/清空、目录已选计数）→「导入」入表情库或「转存到…」SAF 写入用户所选目录，进度复用共享 `SyncProgressDialog`
- `.nomedia` 数据目录标记（`StoragePaths.ensureNomedia`）：启动/改存储位置/恢复备份/向导选目录后在自有目录写空 `.nomedia`，阻止媒体库扫描表情缓存；同步上传以数据库驱动不会上传该文件
