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
