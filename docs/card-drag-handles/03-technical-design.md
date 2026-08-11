# 卡片拖拽手柄技术设计

## 文档关系

本文将[产品需求](01-product-requirements.md)和[交互规格](02-ux-interaction-spec.md)映射到当前 Android 代码。实施顺序与测试证据见[实施计划](04-implementation-plan.md)。

## 当前实现依据

- `MainActivity.kt` 的 `sortEnabled`、`toggleSort()` 和 `btn_sort` 共同管理标题栏排序模式。
- `reloadData()` 根据关键词和 `activeCollectionId` 构造网格，并在排序模式下停用 `MemeGridAdapter.onLongClick`。
- `SortCallback` 支持四向移动，`clearView()` 已按正数分组调用 `reorderCollectionMembers`，其他视图调用 `reorderMemes`。
- `MemeGridAdapter.kt` 已维护可移动的 `items`，并提供 `move()` 与 `currentIds()`；根卡片的点击和长按分别经 `onItemClick`、`onLongClick` 回调。
- `item_meme.xml` 的右上角为 `tv_meme_badge`，底部为 `tv_meme_name`，左上区域可放置手柄。`activity_main.xml` 当前包含 `btn_sort`。

## TECH-CDH-001：移除模式入口

从 `activity_main.xml` 移除 `btn_sort`。从 `MainActivity.kt` 移除 `sortEnabled`、`toggleSort()` 和对 `btn_sort` 的监听、着色与提示。排序资格不再由用户切换状态决定。

## TECH-CDH-002：集中排序资格策略

在便于 JVM 单元测试的 Kotlin 代码中定义纯策略，例如 `canReorder(keyword, collectionId, itemCount)`。仅当以下表达式为真时返回 true：

```kotlin
keyword.isEmpty() &&
itemCount >= 2 &&
(collectionId == null || collectionId > 0)
```

该策略同时驱动手柄可见性、手柄触摸监听和 `ItemTouchHelper` 的连接。负数虚拟分组会自然被拒绝，涵盖 `-2`、`-3` 和 `-4`。策略集中后，数据刷新与 UI 绑定不会产生互相矛盾的排序状态。

## TECH-CDH-003：仅由手柄启动拖拽

`SortCallback.isLongPressDragEnabled()` 必须返回 `false`。`MainActivity` 创建并附加一个 `ItemTouchHelper`，将其 `startDrag(viewHolder)` 能力通过适配器回调交给 `MemeGridAdapter`。

适配器在绑定时将回调仅安装到手柄。手柄收到 `ACTION_DOWN` 且当前卡片可排序时，调用该回调开始拖拽，并消费该手势。根卡片不调用 `startDrag`，继续绑定既有点击分享与长按菜单回调。

适配器需要显式接收“允许重排”状态。状态为 false 时，隐藏手柄并清除或忽略其触摸监听，避免 RecyclerView 复用导致不可排序卡片保留旧行为。

## TECH-CDH-004：保留移动与持久化语义

保留现有四向 `ItemTouchHelper` movement flags 和 `MemeGridAdapter.move()`，使拖动期间只调整适配器内的可变列表。`clearView()` 在拖拽结束时读取 `currentIds()` 并交给现有单线程 `executor`。

持久化分支保持不变：

```kotlin
if (collectionId != null && collectionId > 0) {
    reorderCollectionMembers(collectionId, ids)
} else {
    reorderMemes(ids)
}
```

排序资格确保该回调只在全局视图或正数真实分组中进入。不得在收藏夹、最近使用或未分类视图写入排序。

## TECH-CDH-005：布局与资源

在 `item_meme.xml` 的现有 `FrameLayout` 中增加手柄视图，放在 `top|start`。视图应使用本地化字符串资源作为 `contentDescription`，有约 48dp 的触摸边界，并避免覆盖 `tv_meme_badge` 与 `tv_meme_name`。按仓库当前资源组织方式补充所需图标和字符串，不引入依赖。

## 风险与约束

- 适配器会在 `reloadData()` 时重建，新的 `ItemTouchHelper` 必须与当前 RecyclerView 和新适配器一致，旧 helper 需要解除，避免重复附加。
- 拖拽开始回调依赖当前 ViewHolder，必须避免在 `NO_POSITION` 时开始拖拽。
- `clearView()` 可能在非实际移动后调用。实现应沿用当前持久化时机，测试至少确认一次实际换位后的顺序。
- 本功能不修改数据库、同步、分享或上下文菜单契约。

## 追踪

| 技术设计 | 需求和交互 | 实施任务 | 测试 |
|---|---|---|---|
| TECH-CDH-001 | REQ-CDH-001, UX-CDH-001 | TASK-CDH-001 | TEST-CDH-001 |
| TECH-CDH-002 | REQ-CDH-002, REQ-CDH-003, UX-CDH-002 | TASK-CDH-002 | TEST-CDH-002, TEST-CDH-003 |
| TECH-CDH-003 | REQ-CDH-004, UX-CDH-003, UX-CDH-004 | TASK-CDH-003 | TEST-CDH-004, TEST-CDH-006 |
| TECH-CDH-004 | REQ-CDH-005, REQ-CDH-006, UX-CDH-005 | TASK-CDH-004 | TEST-CDH-005 |
| TECH-CDH-005 | REQ-CDH-007, UX-CDH-003 | TASK-CDH-005 | TEST-CDH-006 |
