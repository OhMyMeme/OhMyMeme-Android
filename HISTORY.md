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
