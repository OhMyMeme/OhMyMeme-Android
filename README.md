# OhMyMeme Android

轻量化表情包管理系统的安卓端 — 与桌面端（[OhMyMeme](https://github.com/OhMyMeme/OhMyMeme)）存储结构一致，便于多端同步。

![picture](https://raw.githubusercontent.com/OhMyMeme/OhMyMeme-Android/refs/heads/dev/resource/picture.gif)

## 功能

- **界面复刻桌面端** — 暗色主题、左侧分组树侧栏（滑动手势展开/收起）、3 列表情网格
- **表情导入** — 从文件导入（批量）/ 从手机相册导入（Photo Picker，免权限）/ 从手机 QQ 缓存导入（需 Shizuku 授权，双栏可视化勾选）
- **缓存扫描** — 启动/刷新时自动注册已有文件，文件名 + 哈希双重去重
- **搜索** — 关键词实时筛选（匹配文件名/标签名），分组/收藏夹/最近使用/未分类过滤
- **标签系统** — 长按打标签，标签行点击叠加筛选，标签随同步清单跨设备合并
- **整理模式** — 多选批量删除 / 加入分组 / 打标签
- **小分组** — 长按分组新建小分组（1 层），父分组激活时子分组平铺展开
- **拖拽排序** — 与整理模式共存（点击选中、手柄排序）
- **动图播放** — GIF/WebP 动图网格内直接播放，设置可关闭
- **长按菜单** — 重命名 / 收藏 / 打标签 / 添加分组 / 从分组移除 / 删除
- **点击分享** — 经系统分享面板分享到微信/QQ 等，自动记入最近使用
- **长按拖拽发送** — 直接把表情拖入微信/QQ 等聊天窗口
- **接收分享导入** — 从任意应用分享图片到 OhMyMeme 即可导入
- **云端同步** — FTP / S3 / R2 / WebDAV，进度/速度/后台运行
- **云端直接使用** — 本地未下载的云端表情带云角标显示，点击即下载并使用（默认开启）
- **局域网互联** — 与同局域网的电脑端配对，拉取/上传表情包与配置，密钥同步
- **复制处理** — 分享前处理超限静态图（WebP 缩放 / 转 GIF / 转隐写 GIF），可避免 WebP
- **首次设置向导** — 欢迎 → 存储位置 → 复制处理 → 云同步 → 完成
- **备份与恢复** — 全库导出单 ZIP，校验后一键恢复
- **存储位置** — 可经系统目录选择器（SAF）自定义，支持迁移现有文件
- **版本更新检查** — 24 小时自动检查一次，镜像回退下载
- **控制中心快捷按钮** — 系统快捷设置磁贴一键打开
- **导入上限** — 单文件 >20MiB 或任一边 >2560px 拒绝导入

## 快速开始

### 环境要求

- Android Studio（AGP 9.0.0, Gradle 9.1）
- JDK 17+
- Android SDK 36（compileSdk）、minSdk 28

### 构建

```bash
./gradlew :app:assembleRelease      # 构建已签名 Release APK
./gradlew :app:compileDebugKotlin   # 仅编译（快速验证）
```

产物：`app/build/outputs/apk/release/OhMyMeme-Android-{版本}.apk`。

> **签名说明**：Release 与 Debug 共用一把共享密钥（来自私有仓库 `OhMyMeme/OhMyMeme-Android-keystore`），
> 保证所有 APK 签名一致、可互相覆盖安装。新成员先运行 `scripts/setup-keystore.ps1` 获取密钥
> （详见 `keystore/README.md`）；没有密钥时打包会报错（刻意为之，保证签名一致）。

### 安装

将 Release APK 直接安装到手机（需开启「允许安装未知来源应用」），或通过 Android Studio 连接设备直接运行（Debug 与 Release 同签名，可互相覆盖）。

CI 工作流与签名密钥配置见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 使用

### 基本操作

1. **启动** — 首次运行进入设置向导（存储位置 / 复制处理 / 云同步）
2. **导入** — 点击标题栏「导入」按钮：从文件 / 从相册 / 从手机 QQ 缓存
3. **分享** — 点击表情卡片打开系统分享面板；长按弹出菜单；长按拖拽可直接发入聊天窗口
4. **搜索** — 搜索栏输入关键词实时筛选，点击分组/标签胶囊叠加过滤
5. **设置** — 点击⚙按钮进入设置页，修改后点「保存」持久化

### 路径说明

与桌面端存储结构保持一致，便于互相同步：

| 用途 | 路径 |
|------|------|
| 数据根目录 | `Android/data/com.ohmymeme.app/` |
| 配置文件 | `Android/data/com.ohmymeme.app/config.json` |
| 数据库 | `Android/data/com.ohmymeme.app/files/memes.db` |
| 缓存原图 | 默认 `files/cache/`，SAF 指定目录后位于所选目录的 `cache/` |
| 缩略图 | 默认 `files/thumbnails/`，SAF 指定目录后位于所选目录的 `thumbnails/` |

> 设置页可经 SAF 修改存储位置并选择迁移现有文件；`memes.db` 始终在应用真实路径下。

## 许可证

GPL-3.0
