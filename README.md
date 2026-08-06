# OhMyMeme Android

轻量化表情包管理系统的安卓端 — 与桌面端（[OhMyMeme](https://github.com/OhMyMeme/OhMyMeme)）存储结构一致，便于多端同步。

## 功能

- **暗色 UI 复刻** — 主界面 / 设置页严格对照桌面端（暗色主题、标题栏、搜索框、分组胶囊、3 列表情网格）
- **表情导入** — 通过系统文件选择器批量导入图片（png/jpg/jpeg/gif/webp/bmp），SHA-256 哈希去重，自动重命名为 `{hash前16位}{真实扩展名}`
- **缓存扫描** — 启动/手动刷新时扫描缓存目录，已有文件自动注册到数据库（文件名 + 哈希双重去重）
- **缩略图** — 懒生成缩略图到 `thumbnails/{meme_id}_{size}.png`，网格加载
- **本地数据库** — SQLite (WAL)，7 表 schema 与桌面端 `src/database.py` 完全一致（memes/tags/meme_tags/collections/meme_collections/favorites/recent_uses）
- **搜索** — 关键词实时筛选（按文件名/原始名），分组/收藏夹/最近使用胶囊过滤（分组带数量，有子分组时追加 `▼`），与桌面端 `search_memes` 一致
- **小分组** — 顶栏分组胶囊长按新建小分组（仅 1 层，对齐桌面端 `create_subcollection`），子分组胶囊在父分组激活时平铺展开（对齐桌面端 webui 顶栏 `renderCollections`）；表情长按「加入小分组」可选目标子分组或新建，对齐桌面端网格右键
- **设置** — 动图开关、复制处理模式、云端同步（FTP/S3/R2/WebDAV）凭据、版本信息、危险操作；保存/恢复默认已接真实配置（密钥字段用 Android Keystore 加密存储）
- **配置加密** — `config.json` 中的密钥字段（s3_secret_key 等）经 Android Keystore AES-GCM 加密后落盘
- **版本更新检查** — 设置页「检查更新」查询 GitHub Releases（`OhMyMeme/OhMyMeme-Android`），发现新版本弹窗引导下载 APK；下载地址按桌面端镜像列表依次探测可用镜像（github.dpik.top / gh.dpik.top / gh-proxy.org / proxy.starsfire.top），失败回退 GitHub 直连
- **云端同步** — 设置页选择 FTP / S3 / R2 / WebDAV 任一后端，配置凭据后可「测试连接 / 检查同步状态 / 上传到远端 / 从远端下载 / 清理云端孤儿」；远端目录结构与桌面端一致（`memes/` 文件 + `meme-index.json` 清单 v3），SHA-256 比对跳过已同步文件，上传可删除远端多余文件、下载可移除本地多余文件并重建远端分组；同步多线程并发（`sync_threads`，默认 3），主界面一键同步带进度条 / 百分比 / 速度 / 后台运行，完成弹窗提示，均可由设置项控制；可在设置中开启启动时自动获取远端索引 / 自动同步
- **动图播放** — GIF/WebP 动图在网格中直接播放（受设置页「动图自动播放」开关控制），右上角显示 GIF/WebP/隐写导入角标
- **长按右键菜单** — 长按表情弹出菜单：重命名 / 收藏 / 添加分组 / 加入小分组 / 从分组移除 / 从最近使用中删除 / 删除，对齐桌面端 webui；分组移除后为空则自动删除该分组（小分组移回上层）
- **最近使用** — 点击表情卡片自动记入最近使用，最近使用分组实时刷新
- **日志导出** — 设置页导出本次运行的 Debug 日志（logcat 按进程 PID 过滤）到用户选择的位置
- **快捷同步** — 主界面标题栏上传/下载图标，一键同步到远端/从远端拉取
- **存储位置** — 设置页可修改 localdata 目录（数据库/缓存/缩略图），可选择转移现有文件；配置文件 config.json 保持不变

## 快速开始

### 环境要求

- Android Studio（AGP 9.0.0, Gradle 9.1）
- JDK 17+
- Android SDK 36（compileSdk）、minSdk 28

### 构建

```bash
# 编译 Debug APK
./gradlew :app:assembleDebug

# 仅编译 Kotlin（快速验证）
./gradlew :app:compileDebugKotlin
```

产物输出到 `app/build/outputs/apk/debug/`。

### CI

GitHub Actions 两个工作流（参考桌面端 `.github/workflows`）：

- `Check` — 每次 push / PR 运行：`compileDebugKotlin` + `lintDebug` + `testDebugUnitTest`（JDK 17）
- `Build` — Check 通过（main 分支）或手动触发时运行 `assembleDebug`，产出 Debug APK 并上传为 artifact

### 安装

将 Debug APK 直接安装到 Android 手机（需开启「允许安装未知来源应用」），或通过 Android Studio 连接设备直接运行。

## 使用

### 基本操作

1. **启动** — 打开应用，首次运行会询问存储位置；标题栏为蓝色 "Meme" 字样，下方为搜索框
2. **导入** — 点击标题栏「导入」按钮，从系统文件选择器选图片（支持多选），自动去重并加入网格
3. **刷新** — 点击「刷新」重新扫描缓存目录，把已有文件注册进数据库
4. **搜索** — 搜索栏输入关键词实时筛选，点击分组胶囊过滤
5. **设置** — 点击⚙按钮进入设置页，修改后点「保存」持久化，「恢复默认」还原出厂配置

### 路径说明

与桌面端存储结构保持一致，便于互相同步：

| 用途 | 路径 |
|------|------|
| 数据根目录 | `Android/data/com.ohmymeme.app/` |
| 配置文件 | `Android/data/com.ohmymeme.app/config.json` |
| 数据库 | `Android/data/com.ohmymeme.app/files/memes.db` |
| 缓存原图 | `Android/data/com.ohmymeme.app/files/cache/` |
| 缩略图 | `Android/data/com.ohmymeme.app/files/thumbnails/` |

> 首次运行会弹出对话框选择存储位置：默认使用应用专属目录，localdata（数据库/缓存/缩略图）放在 `files/` 下；或通过系统目录选择器指定其他位置。存储结构对应桌面端：`config.json` ↔ `%APPDATA%/OhMyMeme/config.json`，`files/` ↔ `%LOCALAPPDATA%/OhMyMeme`（含 memes.db / cache / thumbnails），后续可扩展远程同步。

## 架构

```
┌─────────────┐      ┌───────────────┐
│ MainActivity │ ───► │  RecyclerView │  主界面（搜索/分组/网格）
│ SettingsAct. │ ───► │  设置页        │
└──────┬──────┘      └───────────────┘
       │ 调用
┌──────▼──────┐     ┌──────────────────┐
│   MemeDb    │     │  StoragePaths    │
│  (SQLite)   │     │  路径解析         │
└──────┬──────┘     └──────────────────┘
       │ 写入
┌──────▼──────────────────────────────┐
│  files/cache  files/thumbnails  memes.db │
└──────────────────────────────────────┘
```

```
com.ohmymeme.app/
├── MainActivity.kt     # 主界面：导入/刷新/搜索/网格
├── SettingsActivity.kt # 设置页：读写配置
├── ChipAdapter.kt      # 分组胶囊适配器
├── MemeGridAdapter.kt  # 表情网格适配器（异步加载缩略图 / GIF 动图播放）
├── Meme.kt             # 数据模型（对应 memes 表）
├── MemeDb.kt           # SQLite 封装（7 表 schema 与桌面端一致）
├── ConfigStore.kt      # JSON 配置读写 + 密钥加密
├── CryptoUtil.kt       # Android Keystore AES-GCM 加解密
├── StoragePaths.kt     # 数据/缓存/配置路径解析
├── FileUtils.kt        # SHA-256 + 魔数识别扩展名
├── CacheScanner.kt     # 缓存扫描（双重去重）
├── MemeImporter.kt     # SAF 批量导入
├── Thumbnailer.kt      # 缩略图生成
├── CloudSync.kt        # 云端同步（FTP/S3/R2/WebDAV + meme-index.json 清单）
└── UpdateChecker.kt    # 版本更新检查（GitHub Releases API + 镜像下载）
```

## 实现要点

### 存储结构对齐桌面端
- 数据库 schema（表/列/索引）逐字段照搬 `src/database.py`，含 `stego_of_hash`/`from_stego` 等隐写兼容字段，桌面端 `memes.db` 可被安卓端直接打开
- 导入重命名规则一致：`{sha256 前16位}{魔数识别扩展名}`
- 缩略图命名一致：`{meme_id}_{size}.png`

### 缓存去重
扫描缓存目录时**双重去重**：按文件名查 DB 防止每次启动重复注册，按 SHA-256 哈希查 DB 防止同图不同名重复。导入（SAF）同样有哈希去重。跳过 `thumbnails` 路径，跳过与同名 `.webp` 共存的 `.gif`（动图生成物）。

### 魔数识别扩展名
`FileUtils.detectExt` 读取文件头魔数识别真实扩展名（QQ 保存常为 .jpg 实为 png/webp），支持 PNG/JPEG/GIF/WebP/BMP，与桌面端 `adb_util._QQ_FILE_TYPES` 一致。

### 配置加密
桌面端用 Fernet，安卓端用 Android Keystore AES-GCM：`CryptoUtil` 生成硬件背书密钥，加密 `config.json` 中 `s3_secret_key` 等 6 个密钥字段后落盘，读取时自动解密。

### 线程模型
数据库操作在单线程 Executor 中执行（`MemeDb` 内部由 SQLite WAL + Android 锁保证并发安全），UI 更新通过 `runOnUiThread` 回主线程，避免卡顿。

## 技术栈

| 模块 | 技术 | 理由 |
|------|------|------|
| 语言 | Kotlin | 安卓官方 |
| UI | AppCompat + RecyclerView + ConstraintLayout | 轻量原生视图 |
| 数据库 | SQLite (WAL) | 内置，与桌面端 schema 一致 |
| 加密 | Android Keystore (AES-GCM) | 硬件背书密钥 |
| 导入 | Storage Access Framework | 免存储权限批量选图 |
| 构建 | AGP 9.0 + Gradle 9.1 | 版本目录（libs.versions.toml）管理依赖 |

## 状态

- [x] 主界面 UI 复刻
- [x] 设置页 UI 复刻
- [x] 存储层（路径/数据库/配置）
- [x] 缓存扫描 + 导入 + 缩略图
- [x] 设置保存/重置接真实配置
- [x] 版本更新检查
- [x] GIF 动图播放（含 WebP 动图，`auto_play_gif` 开关）
- [x] 长按右键菜单（重命名/收藏/添加分组/加入小分组/从最近使用中删除/删除）
- [x] 云端同步（FTP/S3/R2/WebDAV，含镜像源更新下载、清理云端孤儿）
- [x] 最近使用记录（点击卡片记录 + 最近使用分组）
- [x] 启动自动同步（sync_auto_sync / sync_auto_fetch_index）
- [x] 日志导出
- [x] 主界面快捷同步（上传/下载图标，多线程 + 进度条/完成弹窗/后台运行）
- [x] 修改存储位置（localdata，可转移现有文件）
- [x] 隐写 GIF 解码导入（STG3 检测 + 7 种模式还原，与桌面端 gif_stego.py 逐字节一致）
- [x] 小分组（子分组）创建与嵌套胶囊展示（1 层限制，对齐桌面端 create_subcollection / renderCollections）
- [ ] 点击复制到剪贴板
- [ ] 分组管理交互

## 许可证

GPL-3.0
