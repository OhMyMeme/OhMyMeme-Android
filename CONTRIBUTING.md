# 贡献指南

面向开发者的环境搭建、构建、签名与 CI 说明。用户文档见 [README.md](README.md)，AI 编码助手的完整项目上下文见 [AGENTS.md](AGENTS.md)。

## 开发环境

- JDK 17+、Android Studio（AGP 9.0.0, Gradle 9.1）
- Android SDK 36（compileSdk / targetSdk），minSdk 28
- 依赖用 `gradle/libs.versions.toml` 版本目录管理

```bash
./gradlew :app:compileDebugKotlin   # 快速编译验证（约 12-19s）
./gradlew :app:assembleDebug        # Debug APK
```

- **跑 gradle 命令务必设置超时**（600s+），否则可能卡死
- 改动后先跑 `compileDebugKotlin` 验证，用户通常自行 `assembleDebug`

## 构建与签名

```bash
./gradlew :app:assembleRelease      # 完整构建已签名 Release APK
./gradlew :app:testDebugUnitTest    # 单元测试
./gradlew :app:lintDebug            # Lint
```

产物：`app/build/outputs/apk/release/OhMyMeme-Android-{appVersionName}.apk`（APK 命名由 `app/build.gradle.kts` 的 `renameReleaseApk` 任务完成）。

**Release 与 Debug 共用一把共享密钥**，保证本地/CI 所有 APK 签名一致、可互相覆盖安装：

- 本地构建读项目根 `keystore.properties` + `keystore/ohmymeme-release.jks`（均 gitignored，来自私有仓库 `OhMyMeme/OhMyMeme-Android-keystore`）
- 新成员先运行 `scripts/setup-keystore.ps1` 从私有密钥仓库拷贝密钥；没有密钥时打包报错 `SigningConfig "shared" is missing required property "storeFile"`（**刻意设计**，保证签名一致）

### CI Secrets（签名密钥）

仓库 `Settings → Secrets and variables → Actions` 配置 4 项：

| Secret | 值 |
|--------|-----|
| `SIGNING_KEYSTORE_BASE64` | `keystore/ohmymeme-release.jks` 的 base64（`certutil -encode` 或 `base64` 命令生成） |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名（默认 `ohmymeme`） |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

## 代码规范

- Kotlin，原生视图（AppCompat + RecyclerView + ConstraintLayout，**无 Compose**）
- 无冗余注释；Kotlin 按语言惯例写类型标注
- 无 emoji（除非用户要求）
- 数据库/IO 在单线程 Executor 中执行，`runOnUiThread` 回主线程更新 UI
- **存储结构严格对齐桌面端** — 表 schema、列名、重命名规则（`{sha256 前16位}{魔数扩展名}`）、缩略图命名、去重逻辑逐条对照桌面端 `src/database.py`/`config.py`，不得随意改动；如需同步桌面端数据层逻辑，以桌面端源码为唯一事实来源
- 增改功能后同步更新 `README.md` 和 `AGENTS.md`

## 主要模块

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | 主界面：导入/刷新/搜索/网格/整理模式/拖拽/分享/云行下载 |
| `SettingsActivity.kt` | 设置页：配置读写、云端同步、局域网互联、备份、更新检查 |
| `MemeDb.kt` | SQLite 封装（7 表 schema 与桌面端逐列一致 + 列迁移） |
| `ConfigStore.kt` | JSON 配置（DEFAULTS 与桌面端 `config.py` 一致），6 个密钥字段经 Keystore 加解密 |
| `CryptoUtil.kt` | Android Keystore AES-GCM 加解密（硬件背书密钥） |
| `StoragePaths.kt` | 路径解析（SAF 树持久化 + `.nomedia` 标记） |
| `StorFile.kt` | cache/thumbnails 统一文件句柄（content URI / 真实路径双模式） |
| `MemeImporter.kt` | 导入管线：去重/魔数/20MiB-2560px 上限/隐写解码 |
| `CacheScanner.kt` | 缓存扫描（文件名 + 哈希双重去重） |
| `MemeCopyProcessor.kt` | 分享前复制处理（WebP 缩放 / 转 GIF / 转隐写 GIF） |
| `GifFrameDecoder.kt` / `GifEncoder.kt` | 自研最小 GIF 解码/编码器（与 Pillow 逐字节一致） |
| `GifStego.kt` | STG3 隐写检测/7 模式解码/encode 写入 + 自研 PNG 编码 |
| `WebpAnim.kt` | 动画 WebP 解析（RIFF/VP8X/ANIM/ANMF），WebP→GIF 帧源 |
| `CloudSync.kt` | 云端同步（FTP/S3/R2/WebDAV + `meme-index.json` 清单 v3）+ 云端直接使用 |
| `LanClient.kt` | 局域网互联客户端（UDP 发现 + TCP 握手 + AES-GCM 会话，协议逐字节对齐桌面端 `lan.py`） |
| `QqCacheImporter.kt` + `ShizukuBridge.kt` | 手机 QQ 缓存扫描（Shizuku 远程 shell）+ 双栏可视化勾选导入/转存 |
| `BackupManager.kt` | ZIP 备份/恢复（staging 校验防路径穿越） |
| `UpdateChecker.kt` | 版本更新检查（GitHub Releases + 24h TTL + 镜像下载） |
| `SetupGuideActivity.kt` | 首次设置向导（5 步） |
| `QuickTileService.kt` | 控制中心快捷磁贴（TileService） |

## 存储布局（与桌面端对应）

```
Android/data/com.ohmymeme.app/
├── config.json                       ← 桌面端 %APPDATA%/OhMyMeme/config.json
└── files/                            ← 桌面端 %LOCALAPPDATA%/OhMyMeme
    ├── memes.db                      ← 始终在真实路径，不随 SAF 存储位置迁移
    ├── cache/                        ← 导入原图，命名 {sha256前16位}{ext}
    └── thumbnails/                   ← {meme_id}_{size}.png
```

- 存储位置可经 SAF（`ACTION_OPEN_DOCUMENT_TREE`）自定义：`cache/`/`thumbnails/` 经 `StorFile` 双模读写，`memes.db` 与云端临时清单始终在真实路径
- 切换存储位置时只转移 `cache`/`thumbnails` 两个子目录（绝不拷贝 `memes.db`），成功后删除源子树
- 自有数据目录写空 `.nomedia` 阻止媒体库扫描；同步以数据库驱动，不会上传该文件

## CI（.github/workflows）

- **`check.yml`**：push/PR 触发，JDK 17 运行 `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest`
- **`build.yml`**：Check 通过（main 分支）或手动触发，运行 `assembleRelease`（Secrets 解码签名密钥）并上传 `app/build/outputs/apk/release/*.apk` artifact

## 测试

- `GifEncoderTest` / `GifStegoEncodeTest` / `WebpAnimTest` 等纯 JVM 单测覆盖自研编解码器（与 Pillow 跨边界逐字节验证）
- `CloudDirectTest`（8 例）覆盖云端直接使用合并/差集/回填
- `QqCacheImporterTest`（18 例）覆盖 QQ 缓存扫描/导入/转存

## 桌面端参考

与桌面端行为对齐时，以桌面端仓库的 `src/database.py`（schema/迁移）、`src/config.py`（配置默认值）、`src/sync.py` + `src/manifest.py`（清单格式）、`src/lan.py`（局域网协议）、`src/clipboard_util.py`（复制处理模式）为唯一事实来源。
