# 未发布

## 新增

- **侧栏滑动手势** — 屏幕左缘（≤16dp）右滑 ≥64dp 展开侧栏，侧栏内左滑 ≥64dp 收起（均要求水平主导），对齐桌面端侧栏条滑动手势；`MainActivity.dispatchTouchEvent` 仅观察不拦截子视图，触发后给子视图补发 CANCEL 并吞掉 UP，避免滑动收起时误触分组行点击

- **同步与局域网传输进度弹窗** — 进度对话框抽为共享组件 `SyncProgressUi.kt`（`SyncProgressDialog`：字节制百分比封顶 99、未知总量回退文件数、实时速度、当前文件、「后台运行」；`dialog_sync_progress` meta 行拆为百分比 + 速度并排，对齐桌面端样式）；设置页云端上传/下载从按钮占位改为走进度对话框（受 `show_upload_progress`/`show_download_progress` 门控，`runCloudSync`）；局域网拉取/上传/配置/密钥同步（`lanOp`/`configOp`/`runKeyOp`）均弹进度对话框（总是显示，不接开关）
- **局域网帧附带传输总量（meta）** — `LanClient.pull`/`push` 按待传条目预算 `files_total`/`bytes_total` 并逐条目帧附带 `meta: {files_total, bytes_total}`（`pull_file`/`push_file`/`get_config`/`send_config`，纯增量协议旧端自动忽略），电脑端累计实际收发量后在设置页显示进度；每条目 `finally` 中 `progress.report` 保证恰好一次回调
- **标签随同步清单下发与合入（对齐桌面端）** — `CloudSync.buildManifest` 每条 `memes[]` 条目写入 `tags: [...]` 数组，受新增设置「将标签写入同步清单」（`manifest_include_tags`，默认开）门控，关闭时条目不含该键；不再输出顶层 `tag_map`，读侧仍兼容旧 `tag_map` 并回退；云 pull（`remove_local` 与正常两分支）新增 `applyRemoteTags`（原仅 LAN pull 调用），条目 `tags` 经新增 `MemeDb.mergeMemeTags` **并集只增**合入本地，本地独有标签与删除不同步，该步骤不受开关限制
- **设置页「将标签写入同步清单」开关** — 云端同步区新增 `sw_manifest_tags`（布局 + `loadConfig`/`saveConfig` + 文案三件套），默认开
- **收藏随同步清单下发与合入（对齐桌面端）** — `CloudSync.buildManifest` 新增顶层 `favorite` 文件名数组（无收藏为 `[]`，取自 `db.search(favoriteOnly=true)`），受新增设置「将收藏夹写入同步清单」（`manifest_include_favorites`，默认开）门控，关闭时整个键不写入；云 pull（`remove_local` 与正常两分支）与 `LanClient.pull` 均调用新增 `applyRemoteFavorites`，经纯函数 `favoriteFilenamesFrom`（非字符串/`isSafeRemoteFname` 过滤）+ 新增 `MemeDb.addFavorite`（`INSERT OR IGNORE`）**并集只增**合入本地收藏，该步骤不受开关限制
- **设置页「将收藏夹写入同步清单」开关** — 云端同步区新增 `sw_manifest_favorites`（布局 + `loadConfig`/`saveConfig` + 文案三件套），默认开
- 新增 `ManifestFavoriteTest`（5 例：键缺失/非 list 为空、非字符串过滤、路径穿越/绝对路径/隐藏名拒绝、安全名保序）
- 新增 `ManifestTagsTest`（6 例：条目 tags 优先、空数组不回退、`tag_map` 回退、双缺失为空、非字符串/空白过滤、`isSafeRemoteFname` 路径穿越拒绝），引入 JVM 测试依赖 `org.json:json`

## 修复

- **设置页上传/下载按钮状态** — 原 `runSync` 完成后永远把 `btn_sync_push` 恢复为默认文本（即使本次跑的是下载），改为按 `btnId` 恢复对应按钮文本

# v0.5.5 — 桌面功能迁移 + 首次设置向导 + 视觉对齐

## 新增

- **复制处理「跳过 WebP 转换」（对齐桌面端 `clipboard_util.py` avoid 逻辑）** — 设置页与向导新增 `copy_avoid_webp` 开关：动图 WebP 分享不再转 GIF 也不超限检查，直接回退原图；静态 WebP 分享改走 PNG/JPG 输出避免生成不透明 WebP（`MemeCopyProcessor.avoidWebp`，`staticWebpToJpg` 白底 flatten 对齐桌面端 `_static_webp_to_jpg`）
- **动画 WebP 转 GIF** — 自研 `WebpAnim`（RIFF/VP8X/ANIM/ANMF 解析、帧合成含 dispose/no-blend、有 alpha 帧包 VP8X+ALPH）+ `GifEncoder.encodeAnimated`（GIF89a、NETSCAPE 循环、逐帧 GCE 延迟/透明色、LZW 子块），无 avoid 开关时超限动画 WebP 可转 GIF 分享
- **整理模式批量打标签** — 底部操作栏新增「打标签」按钮，弹标签编辑器批量模式（在各自既有标签上追加所选，保留原标签；单张表情仍为覆盖模式）
- **首次设置向导（裁剪版 5 步）** — 新增 `SetupGuideActivity`：欢迎 → 存储位置（默认 / SAF 自定义，选失败自动回退默认）→ 复制处理（resize 模式 + 跳过 WebP 开关）→ 云同步（介绍，可直接下一步）→ 完成（`markSetupDone`）；替换原首启 AlertDialog 二选一，配置实时写入 `ConfigStore`，全部选项随时可在设置页修改
- **设置页 ZIP 备份 / 恢复** — 新增 `BackupManager`：备份 = meta + memes.db（先 `PRAGMA wal_checkpoint(TRUNCATE)`）+ config.json + cache/ + thumbnails/ 打成单 ZIP；恢复 = 解包 staging 校验（拒绝 `..`/绝对路径防路径穿越）→ 关库替换 DB → 替换 config（`ConfigStore.reload`）→ SAF/真实路径双模迁移 cache/thumbnails；恢复前弹确认框（将覆盖全部数据）
- **启动自动更新检查（24h TTL）** — `UpdateChecker.shouldCheck/markChecked`，启动距上次检查超 24h 自动查一次（首跑跳过，检查即打点不重复弹窗），仍保留设置页手动检查
- **Logo 点击回主页** — 点击顶栏 logo 清除标签/分组/搜索过滤并刷新，等价桌面端回首页
- **搜索支持按标签名** — `MemeDb.search` 关键词匹配扩展为 `name/original_name LIKE ? OR id IN (SELECT ... tags.name LIKE ?)`，对齐桌面端 `search_memes`
- **云端同步远端目录缓存** — FTP/WebDAV push/pull 循环内 `mkd` 前先查 `ensuredDirs` 集合（连接级缓存），循环外仅 `ensureRemoteDir(memeDir)` 一次，消除每文件 MKO 重复建目录
- **名称排序对齐桌面端** — 新增 `NameSorter`（数字感知 + 大小写不敏感，语义对齐桌面端 `_name_sort_key`）取代 SQL `ORDER BY name`，附 `NameSorterTest`
- **UI 视觉对齐桌面端 style.css** — 色板换 slate 体系（`fg #E2E8F0` / `fg_secondary #94A3B8` / `muted #8A94A8` / `border #2A2A32` / `card/surface #1A1A1F` / `surface_2 #222228`），主按钮 `#1D4ED8`、卡片描边 2dp、按钮/输入框圆角 4dp、弹窗圆角 12dp 带描边、弹出菜单圆角 8dp、状态栏与导航栏固定 `bg`；空状态从 `_(:3 」∠)_` 换为插画（ic_photo）+「导入」按钮

## 测试

- 新增 `WebpAnimTest`（RIFF 解析 / VP8X 动画标志 / ANMF 头与 no-blend、dispose 位 / wrapFrame 无 alpha 走 imgType 0x10）、`GifEncoderAnimatedTest`（NETSCAPE 循环块、逐帧 GCE 延迟/透明标志、LZW 图像子块与 trailer 逐帧遍历、有 alpha 帧透明色保留）、`NameSorterTest`（数字感知与大小写不敏感）；全部 `testDebugUnitTest` 通过

## 其他

- **版本号** — versionCode 12 / versionName 0.5.5

# v0.5.0 — 桌面端布局复刻 + 标签系统 + 整理模式 + S3 OSS 兼容

## 新增

- **主界面布局复刻桌面端** — 顶栏改为「折叠按钮 + logo + 图标」一行、搜索框独立一行；新增左侧**常驻分组树侧栏**（桌面式，约 36% 宽，默认收起，点击顶栏折叠按钮切换，`SidebarTreeAdapter` + `rv_sidebar`），支持展开/收起子分组（1 层限制）；原分组胶囊行改为**标签行**（`rv_tags`），分组树/标签/关键词可叠加过滤（`MemeDb.memeIdsWithAllTags` 全含匹配）
- **标签系统（对齐桌面端 TagEditor + App.vue 标签栏）** — 长按菜单新增「打标签」（`act_tag`），对话框支持搜索已有标签、点选/取消、回车新建，保存走 `setMemeTags`（孤儿标签自动清理）；顶栏标签行点击即可叠加筛选（再次点击取消），与分组/关键词过滤叠加，对齐桌面端 `search_memes` 的 `tags` 参数
- **整理模式（对齐桌面端多选批量删除）** — 顶栏排序图标进入整理模式：点击卡片勾选（`tv_select_check` 徽标）、底部操作栏「已选 n 项 / 全选 / 取消 / 批量删除 / 加入分组」；整理模式下拖拽 handle 始终可见可拖拽（tap=选中，handle=排序，共存不互斥）；批量删除走 `MemeDb.deleteMemes`（单事务 + 孤儿标签清理）并物理删除文件与缩略图；批量加入分组走两段式对话框（新建/点选已有）
- **导入上限对齐桌面端（`config.py` `_IMPORT_MAX_BYTES`/`_IMPORT_MAX_PX`）** — 单文件 >20MiB 或任一边 >2560px 拒绝导入（`MemeImporter.MAX_BYTES`/`MAX_PX`，`ImportOutcome`/`ImportResult` 汇总）；SAF 批量导入逐文件失败不影响其余，结束 Toast 汇总成功/跳过/超限/失败数；局域网拉取（`LanClient`）与云端 pull（`CloudSync`）同样执行超限校验并跳过
- **S3 OSS 兼容（对齐桌面端 boto3 配置）** — `S3Backend` 支持 **V2 签名**（`signature_version="s3"`，HMAC-SHA1 + `Authorization: AWS ak:base64sig`）与**虚拟主机寻址**（`addressing_style="virtual"`，`https://bucket.endpoint/key`），默认即 V2+virtual（对齐桌面端 `config.py` 默认值）；设置页 S3 区新增「签名版本 / 寻址方式」下拉；R2 强制 SigV4 + path 寻址不受影响
- **添加分组交互对齐桌面端 CollectionBuilder** — 长按菜单「添加分组」改为两段式对话框：输入新分组名创建，或从「加入已有分组」列表点选即加入（仅列出真实分组 id>0）
- **最近使用开关（对齐桌面端 `record_recent_use`）** — 设置页「分组」区块新增「复制/分享时记录最近使用」开关（默认开），关闭后点击/拖拽/分享不再写入 `recent_uses`，`ConfigStore.DEFAULTS` 已同步默认值
- **更新检查稳定版过滤** — GitHub Releases 列表回退路径跳过 `draft`/`prerelease` 与 tag 含 `nightly`/`beta`/`rc` 的版本，只推送正式版
- **局域网同步分组 + 标签** — `LanClient.pull` 拉取文件后同步电脑端分组（递归子集合）与标签（`CloudSync.applyRemoteCollections` + `applyRemoteTags`）；`buildManifest` 新增 `tag_map` 字段（`filename→tags[]`），push 时同步本地标签；`applyRemoteCollections` 支持递归子集合创建（`parentId` 传入）

## 修复

- **整理模式选中闪烁** — `onSelectToggle` 传 position，改用 `notifyItemChanged(pos)` 按位置刷新；取消全选/全选/取消按钮均改为 `notifyItemRangeChanged`，消除全量 `notifyDataSetChanged` 导致的缩略图重载闪烁

## 其他

- **版本号** — versionCode 11 / versionName 0.5.0

# v0.4.6 — 控制中心快捷按钮 + 相册式拖拽

## 新增

- **通知栏控制中心快捷按钮** — 新增系统快捷设置磁贴（`TileService`，`QuickTileService`），点击即可一键打开主界面（锁屏时先解锁再打开）；设置页新增「快捷开关」区块 + 「添加到控制中心」按钮，弹出操作指引帮助用户把磁贴加入通知栏控制中心
- **主界面长按卡片直接拖拽到聊天软件（相册式）** — 长按网格卡片直接发起跨应用 Drag & Drop（`View.startDragAndDrop` + `ClipData.newUri` + `DRAG_FLAG_GLOBAL|DRAG_FLAG_GLOBAL_URI_READ`），拖拽影子跟随手指，把图片拖入微信/QQ 等聊天窗口（SAF 模式下先物化到 `cacheDir` 再经 FileProvider 暴露 content URI），同时记入最近使用；卡片右上角新增「⋯」按钮（`btn_meme_menu`）承载原右键菜单（重命名/收藏/分组/删除）；拖拽未被任何目标接收（`ACTION_DRAG_ENDED` 无放置）时自动弹原右键菜单兜底（含「分享」入口）；点击分享/排序手柄/动图播放均不受影响

## 修复

- **悬浮窗拖拽方案在鸿蒙/Huawei 上无法发起（长按无拖拽影子）** — 根因：`TYPE_APPLICATION_OVERLAY`（WindowManager 悬浮窗）在鸿蒙/Huawei ROM 上不进入全局拖拽管道，只有普通 Activity 窗口（如系统相册）可以。已删除悬浮窗方案（`MemeDragService`、`OverlayMemeAdapter`、overlay 布局/菜单/资源、`SYSTEM_ALERT_WINDOW`/FGS 权限与 Service 声明），改为上方相册式交互（拖拽由 Activity 窗口内的卡片视图发起，与系统相册同窗口类型）

## 其他

- **版本号** — versionCode 10 / versionName 0.4.6

# v0.4.5 — 「未分类」分组 + 局域网直连 + 存储位置 SAF 全量支持

## 新增

- **「未分类」虚拟分组（`id == -4`）** — 顶栏分组胶囊新增「未分类」，列出未加入任何分组的表情，对齐桌面端 `webui.py`（`search_memes` 的 `collection_id == -4` + `get_collections` 系统分组）；只在 `count(uncategorized_only=True) > 0` 时显示，清零后自动隐藏并退出该视图，行为与收藏夹/最近使用一致
- **查询支持** — `MemeDb.search`/`count` 新增 `uncategorizedOnly` 参数，对应桌面端 `database.py` 的 `uncategorized_only`（`NOT EXISTS (SELECT 1 FROM meme_collections mc WHERE mc.meme_id = m.id)`）
- **设置开关** — 设置页新增「分组」区块 + 「显示『未分类』分组」开关（`show_uncategorized`，默认开，对齐桌面端 `config.py` 默认 + `settings.html` 勾选框）
- 虚拟分组不落库，`CloudSync` 清单（仅遍历真实 `collections` 表）天然不受影响；负数 id 使拖拽排序/长按分组菜单自动禁用，与收藏夹/最近使用一致
- **局域网互联 IP:端口 直连** — 设置页「局域网互联」区块新增 IP:端口 输入框与「直连」按钮，填写电脑 IP 与端口（如 `192.168.1.100:17852`）即可跳过 UDP 扫描直接建立加密会话；协议与扫描连接完全一致（复用 `LanClient.connect`，TCP 握手 + 设备确认 + AES-GCM），连接状态显示「已连接 IP（直连）」，适用于同一局域网内扫描不到的电脑（如手动指定端口/跨网段路由可达）

## 修复

- **修改存储位置后重启闪退 / 所选位置提示无法写入** — 根因：作用域存储（targetSdk 36）下 `ACTION_OPEN_DOCUMENT_TREE` 只授予 content URI 权限，旧实现把 SAF 树解析成原始共享路径（如 `/storage/emulated/0/Download/...`）并持久化，导致 `memes.db` 打不开崩溃、目录被误判不可写。改为 **SAF 全量支持**：`cache`/`thumbnails` 经 content URI（DocumentFile）读写，`memes.db` 始终留在应用可真实写入的 `files/` 目录
- **`StorFile`（新文件）** — 统一文件句柄，真实路径 / SAF content URI 双模式（`child`/`childOrCreateDir`/`createFile`/`listFiles`/`listFilesRecursive`/`sibling`/流读写/`delete`/`copyTo(File)`/`copyFrom`/`writeFrom(File)`），`cache`/`thumbnails` 的所有读写经其抽象
- **`StoragePaths` 改造** — 新增 `KEY_DATA_TREE` 持久化 SAF 树 URI、`persistDataTree`（`takePersistableUriPermission` + 探针校验，失败即拒绝）、`dataRoot/cacheDir/thumbnailDir` 返回 `StorFile`、`describeDataLocation`（树 URI 解析为真实路径仅作展示，失败显示 URI）；`resetDataDir` 同时清理真实路径与树 URI 并释放持久化权限
- **消费方迁移** — `MemeImporter`（`createFile`+`writeBytes`）、`CacheScanner`（`listFilesRecursive`）、`Thumbnailer`（`findMemeFile`→`StorFile?`、`getThumbBitmap`）、`MemeGridAdapter`（动图经 `ImageDecoder.createSource(contentResolver, uri)` 解码）、`MemeCopyProcessor`（入参改 `StorFile`）、`LanClient.push`（`stor.readBytes()`）、`CloudSync`（Backend 仍只接受真实 `File`：push 上传前 `copyTo` 物化到 `context.cacheDir` 临时文件，pull 下载到临时文件再 `createFile(fname, mime).writeFrom(tmp)` 落入 cache，用完删除）、`MainActivity`（分享/删除物理文件/首次运行选树改存 tree URI）
- **设置页** — `onStorageDirPicked` 先 `persistDataTree` 校验再询问是否转移；`moveDataToTree` 用 `StorFile` 只拷贝 `cache`/`thumbnails` 两个子目录（绝不拷贝 `memes.db`）到新树，成功后删除源子树

## 其他

- **版本号** — versionCode 9 / versionName 0.4.5

# v0.4.4 — 复制处理落地（WebP 缩放 / GIF 转换 / 隐写 GIF 编码）

## 新增

- **复制处理（对应桌面端 `clipboard_util.py` 的 `convert_image_mode_1/2/3`）** — 分享/点击表情时按设置页「复制处理」模式处理超过 `copy_resize_max`（默认 200）上限的静态图：
  - **mode 1**：缩放为 WebP（q90，对齐桌面端 `_resize_static_to_webp`）
  - **mode 2**：转为普通 GIF（256 色，对齐桌面端 `_static_to_gif`）
  - **mode 3**：转为隐写 GIF —— 生成与原始图同分辨率的基座 GIF 后，用 `GifStego.encode` 把原图信息（FULL 全图 / RGB/L/RGBA 差值，候选取最小）写入 GIF 尾部，接收方可运行解码工具无损还原（对齐桌面端 `make_stego_gif` + `_candidates`）
  - 动图 / 未超限 / 处理失败一律回退原图直发，对齐桌面端行为
- **`GifEncoder`（新文件）** — 自研最小 GIF 编码器：median cut 量化到 256 色 + LZW 压缩，输出单帧 GIF89a；LZW 码长升位时机与 `GifFrameDecoder` 严格对应（已用 Python + Pillow 逐字节验证跨 512/1024/2048 边界与表满场景）
- **`GifStego.encode`（新增）** — 设备端隐写写入：`FULL`/`DELTA_LZMA`/`RGBA_LZMA`/`L_LZMA` 候选恒生成，WebP 候选在 API 30+ 用 `WEBP_LOSSLESS` 生成（低版本跳过，纯 LZMA 亦可还原）；LZMA 用 `XZOutputStream`（preset 6）
- **`MemeCopyProcessor`（新文件）** — 复制处理的 Android 入口：读 `copy_resize_mode`/`copy_resize_max` 配置、`isAnimatedFile` 判动图、`BitmapFactory` 解码（反预乘 alpha，对齐 Pillow 像素）、按模式转换

## 修复

- 修复签名未统一问题导致无法更新

## 其他

- **版本号** — versionCode 8 / versionName 0.4.4
- 新增单测：`GifEncoderTest`（256 色内逐字节精确 + 跨码长边界稳定性）、`GifStegoEncodeTest`（RGB/L/RGBA 差值模式与 FULL 全图模式 encode→decode 逐字节还原）

# v0.4.3 — WebDAV 手写协议修复

## 修复

- **WebDAV 网络不可达（`ProtocolException: Expected one of [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH]`）** — 根因是 Android `HttpURLConnection` 的方法白名单拒绝 WebDAV 的 `PROPFIND`/`MKCOL` 自定义方法（与连接池无关）；改为**原始 socket HTTP/1.1**（`davHttp`）手写协议层，与 FTP 后端同思路，任意方法直发、HTTPS 走 SSLSocket、每次独立建连 `Connection: close`，三种响应体读取（Content-Length/chunked/读到关闭），下载流式写盘不撑内存

## 变更

- **版本号** — versionCode 7 / versionName 0.4.3

# v0.4.2 — 局域网密钥同步

## 新增

- **密钥同步（随开关动态显示）** — 电脑端恢复「允许密钥传输」开关；手机连接时 `device_info` 确认响应携带 `allow_secret_config`，为 true 则设置页动态显示「拉取密钥」/「推送密钥」按钮（`lan_key_row`），点击先弹「请勿在公共网络或不信任的网络进行此操作！」警告，确认后走 `pullConfig`/`pushConfig` 的 `includeSecrets=true`（不过滤 `SECRET_KEYS`，拉取后经 `ConfigStore.save` 用本机 Keystore 重新加密）；开关关闭或未连接时按钮隐藏

## 变更

- **配置同步恢复双向（电脑 ↔ 手机）** — 重新启用 `pushConfig`/`send_config`，同步配置拆为「拉取配置」「推送配置」两个独立按钮（不再弹窗二选一），两端均剔除 `SECRET_KEYS`；密钥同步仅经独立「拉取/推送密钥」入口
- **版本号** — versionCode 6 / versionName 0.4.2

# v0.4.1 — 局域网安全加固 / 配置单向同步

## 变更

- **拉取内容校验（防非表情包文件）** — LAN 拉取逐文件四重检查，任一不过即跳过并计入失败、不落盘：
  - 文件名安全（`isSafeRemoteFname`，防路径穿越）
  - 单文件大小上限 64MB（`MAX_FILE_SIZE`，清单 `file_size` 与实际字节双重校验）
  - 哈希一致性（清单 `sha256` 存在时校验拉取字节，防篡改/错文件）
  - 内容可解码（`MemeImporter.isValidImageContent`：魔数可识别 + `BitmapFactory inJustDecodeBounds` 宽高 > 0）
- **先校验后落盘（杜绝孤儿文件）** — `importBytes` 改为先 `decodeBounds` 校验宽高 > 0，通过后才写缓存 + 入库；此前非图片字节会先写盘再在 mime 计算处抛异常留下孤儿缓存文件
- **配置同步改为单向（电脑 → 手机）** — 移除 `pushConfig`/`send_config`（手机不再推送配置覆盖电脑），「同步配置」按钮改为「拉取配置」从电脑拉取，电脑为权威源，密钥字段依旧两端剔除
- **版本号** — versionCode 5 / versionName 0.4.1

# v0.4.0 — 局域网互联

## 新增

- **局域网互联（客户端）** — 连接电脑端 `lan.py` 服务，协议逐字节对齐：UDP 广播发现电脑（`{"t":"discover"}` → `{"t":"hello","name","os","ver","need_secret"}`），TCP 握手（有密钥走 `challenge`/`proof` HMAC-SHA256 挑战应答，无密钥直接 `ok`），会话密钥 `PBKDF2-HMAC-SHA256`（100000 迭代），帧格式 `[4B 长度][12B IV][AES-GCM 密文+16B tag]`
- **设置页「局域网互联」区块** — 端口/密钥输入、扫描电脑（列出局域网内发现的主机，标注是否需密钥）、连接/断开、状态显示已连接电脑；连接生命周期跟随设置页（`onDestroy` 自动断开）
- **拉取表情（电脑 → 手机）** — `pull_manifest` 拿远端清单 → `getByFilename` 去重 → `pull_file` 逐文件（base64）→ `MemeImporter.importBytes` 入库（哈希去重 + 魔数识别 + 隐写解码）→ `applyRemoteOrder` 回写本地排序
- **上传表情（手机 → 电脑）** — 本地 `getAll` 逐个 `push_file`（电脑端 `_import_bytes` 哈希去重幂等）→ `push_manifest` 同步顺序/分组
- **配置同步** — `get_config`/`send_config` 双向，两端均剔除 `SECRET_KEYS` 密钥字段（对齐桌面端 `allow_secret_config` 默认关）
- **配置键** — `lan_port`（默认 17852）/`lan_secret`（进入 `SECRET_KEYS` 加密存储，对齐桌面端 `_SECRET_KEYS`）

## 变更

- **版本号** — versionCode 4 / versionName 0.4.0

## 修复

- 无（功能新增）

# v0.3.0 — 点击分享 / 接收分享导入 / 同步顺序修复

## 新增

- **点击分享** — 点击表情卡片不再复制（手机端无意义），改为经系统分享面板把原图分享到微信/QQ 等应用：后台把原图复制到内部 cache → `FileProvider`（`file_paths.xml` 缓存路径）生成 content:// URI → `ACTION_SEND` + `Intent.createChooser`；分享同时 `recordUse` 记入最近使用，与桌面端 webui 点击动作对齐
- **接收分享导入** — 从任意应用（微信/QQ/浏览器等）分享图片到 OhMyMeme 即可直接导入：`MainActivity` 声明 `ACTION_SEND`/`ACTION_SEND_MULTIPLE`（image/*）intent-filter + `launchMode="singleTop"`，`onCreate`/`onNewIntent` 提取 `EXTRA_STREAM` URI 列表直接 `doImport`，复用去重/魔数识别/隐写解码全链路
- **同步顺序闭环** — pull 后按远端 manifest 的 `memes` 顺序重排本地 `sort_order`（`applyRemoteOrder` → `reorderMemes`，`isSafeRemoteFname` 校验文件名防路径穿越），保留云端排序，避免本地乱序再 push 覆盖远端顺序（对齐桌面端 `_apply_remote_order`，`sync_remove_local` 分支也执行）

## 变更

- **版本号** — versionCode 3 / versionName 0.3.0

## 修复

- **云端无法保存表情包顺序** — 对齐桌面端 3c62ed8：Android 此前 pull 不写回远端顺序，跨设备再 push 会用本地插入顺序覆盖云端排序，现 pull 无条件应用远端 manifest 顺序

# v0.2.0 — 云端同步增强 / 拖拽排序 / 小分组 / 隐写 GIF 导入

## 新增

- **导入菜单** — 点击标题栏「导入」弹 `menu_import.xml` 菜单：从文件导入（SAF 多选）/ 从手机相册导入（Photo Picker 免权限，不支持时回退系统相册 `ACTION_GET_CONTENT`）/ 从手机QQ缓存导入（占位，后续用 Shizuku 实现）
- **隐写 GIF 解码导入** — `GifStego` + `GifFrameDecoder` + `AndroidGifDecoder`：STG3 检测 + 7 种模式还原（LZW / WebP / LZMA），与桌面端 `gif_stego.py` 逐字节一致，GIF 本身不入库只导入还原结果（`fromStego=1`）；LZMA 用 `org.tukaani:xz`，WebP 用 BitmapFactory 解码并反预乘 alpha，附 fixture 单测逐字节对齐 Pillow
- **小分组（子分组）** — 顶栏分组胶囊长按新建小分组（仅 1 层限制，对齐桌面端 `create_subcollection`）；子分组胶囊在父分组激活时平铺展开（对齐 `renderCollections` 的 `parentActive || activePath` 逻辑）；表情长按「加入小分组」可选目标子分组或新建后直接加入
- **分组管理** — 长按顶栏分组胶囊弹菜单：新建小分组 / 重命名分组 / 删除分组（成员移回上层，对齐桌面端 `rename_collection`/`delete_collection`）；最近使用分组长按可清空最近使用（对齐 `clear_recent`）
- **拖拽排序** — 标题栏「排序」开关进入排序模式，长按表情拖拽换位（ItemTouchHelper），开启时禁用右键菜单避免手势冲突；全局视图落库 `reorderMemes`，分组视图内落库 `reorderCollectionMembers`，搜索中/收藏夹/最近使用禁用
- **最近使用记录** — 点击表情卡片 `recordUse` 记入 `recent_uses`，最近使用分组实时刷新，清空后自动退出该视图
- **云端同步多线程** — push/pull 分块并发（`sync_threads` 默认 3，范围 1-8），每块 worker 独立后端连接（对齐桌面端 `_push_worker`/`_pull_worker`），`SyncProgress` 线程安全计数，单文件失败不影响其余，pull 下载后统一主线程写 DB
- **清理云端孤儿** — 设置页新增「清理云端孤儿文件」按钮，扫描远端 `memes/` 中未被清单记录的文件并删除
- **启动自动同步** — 启动读 `sync_auto_sync`/`sync_auto_fetch_index` 配置，后台执行 pull / checkSyncStatus
- **快捷同步** — 主界面标题栏上传/下载图标一键 push/pull（独立 `syncExecutor`，不占共享 executor）；按 `show_upload_progress`/`show_download_progress` 显示进度弹窗（进度条/百分比/速度/当前文件/「后台运行」按钮），`show_upload_done`/`show_download_done` 控制完成弹窗，后台运行后仅 Toast 摘要
- **日志导出** — 设置页 `ACTION_CREATE_DOCUMENT` 选保存位置，后台 logcat `--pid` 过滤写入文本文件
- **修改存储位置** — 设置页 `ACTION_OPEN_DOCUMENT_TREE` 选新 localdata 目录，弹窗询问是否转移现有文件（数据库/缓存/缩略图），config.json 保持不变
- **更新检查镜像回退** — 新增 `GITHUB_LIST`（releases?per_page=5）回退取最高版本；`fetchFirst` 并发尝试 4 个镜像 + 直连共 5 个 URL（`invokeAny` 取首个真正成功），下载地址按桌面端 `_GH_MIRRORS` 逐前缀探测

## 变更

- **主题固定暗色** — `DayNight` → `Theme.MaterialComponents.NoActionBar`，新增 `AlertDialog.OhMyMeme`/`PopupMenu.OhMyMeme` 样式强制 `card` 背景 + `fg` 文字，修复浅色模式下弹窗/菜单白底白字
- **标题栏图标化** — 刷新/设置改为图标（`ic_refresh`/`ic_settings`），新增排序（`ic_sort`）/上传（`ic_upload`）/下载（`ic_download`）图标按钮
- **布局适配 edge-to-edge** — `activity_main.xml`/`activity_settings.xml` 根布局加 `fitsSystemWindows`，修复 Android 15+ 下内容被状态栏遮挡
- **移除标签胶囊** — 删除 `rv_tags`、`item_tag.xml`、`bg_chip(_active)`，`ChipAdapter` 泛型化仅保留 COLLECTION 样式
- **网格交互** — 卡片点击记录最近使用（`onItemClick`），与长按菜单分离
- **版本号** — versionCode 2 / versionName 0.2.0；新增 `xz` 依赖（LZMA 解压）；CI 新增 `merge-dev-to-main.bat`
- **代码日志** — 各模块补充 `TAG` Log，方便导出排查

## 修复

- **菜单/弹窗白底白字** — DayNight 浅色模式下 PopupMenu/AlertDialog 白底而文字仍为浅色，改为固定暗色主题 + 显式样式
- **通知栏遮挡** — targetSdk 36 强制 edge-to-edge，`statusBarColor` 失效，内容画到状态栏下面，加 `fitsSystemWindows` 规避
- **相册导入走错选择器** — 官方 `PickMultipleVisualMedia` 在 Photo Picker 不可用时静默回退文件选择器，改为 `isPhotoPickerAvailable()` 判断 + `ACTION_GET_CONTENT` 相册回退

# v0.1.0 — 最初版本

版本仍存在Bug，功能也不完善。
