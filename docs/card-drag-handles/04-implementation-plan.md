# 卡片拖拽手柄实施计划

## 文档关系

本计划执行[产品需求](01-product-requirements.md)、[交互规格](02-ux-interaction-spec.md)和[技术设计](03-technical-design.md)。它是开发前计划，不表示任何任务、测试或设备验证已经完成。

## 实施任务

| ID | 工作项 | 涉及位置 | 完成条件 |
|---|---|---|---|
| TASK-CDH-001 | 删除标题栏排序入口及模式状态 | `activity_main.xml`、`MainActivity.kt` | `btn_sort`、`sortEnabled`、切换方法和相关提示不再存在。 |
| TASK-CDH-002 | 增加可单测的排序资格策略并接入数据刷新 | 新增或现有纯 Kotlin 位置、`MainActivity.kt` | 策略只在空搜索、全局或正数真实分组、至少 2 张卡片时允许排序。 |
| TASK-CDH-003 | 将拖拽改为由手柄触发 | `MainActivity.kt`、`MemeGridAdapter.kt` | `isLongPressDragEnabled()` 返回 false，手柄 `ACTION_DOWN` 经适配器回调调用 `startDrag`，根卡片长按菜单保持启用。 |
| TASK-CDH-004 | 保留既有排序移动与落盘分支 | `MainActivity.kt`、`MemeGridAdapter.kt` | 全局仍调用 `reorderMemes`，正数真实分组仍调用 `reorderCollectionMembers`，虚拟分组不进入排序。 |
| TASK-CDH-005 | 增加卡片手柄布局和本地化资源 | `item_meme.xml`、相关 drawable 与 `strings.xml` | 手柄位于左上，约 48dp 触摸区域，拥有本地化描述，不遮挡角标和名称。 |
| TASK-CDH-006 | 同步项目说明 | `README.md`、`AGENTS.md` | 实现完成后将排序入口与交互规则更新为手柄拖拽，符合项目“增改同步”要求。 |

## 自动化验证

| ID | 覆盖项 | 方法与通过条件 |
|---|---|---|
| TEST-CDH-001 | REQ-CDH-001, TECH-CDH-001 | 静态代码检查或针对性测试确认不存在排序模式状态和标题栏排序入口。 |
| TEST-CDH-002 | REQ-CDH-002, TECH-CDH-002 | 对排序资格策略做 JVM 单元测试：空搜索加全局视图加 2 张卡片为 true；正数真实分组加 2 张卡片为 true；少于 2 张卡片为 false。 |
| TEST-CDH-003 | REQ-CDH-003, TECH-CDH-002 | 对排序资格策略做 JVM 单元测试：非空搜索、`-2`、`-3`、`-4` 分别为 false。 |
| TEST-CDH-004 | REQ-CDH-004, TECH-CDH-003 | 代码级或 Robolectric 级测试确认长按拖拽关闭，手柄回调是唯一 `startDrag` 入口，根卡片点击和长按回调仍可绑定。 |
| TEST-CDH-005 | REQ-CDH-005, REQ-CDH-006, TECH-CDH-004 | 适配器移动后 `currentIds()` 保持移动顺序；对 `clearView()` 路径做集成或设备验证，确认全局与分组落入正确的现有数据库方法。 |
| TEST-CDH-006 | REQ-CDH-007, TECH-CDH-003, TECH-CDH-005 | 布局和设备验证确认本地化描述、约 48dp 触摸区域、左上布局及角标和名称不遮挡。 |

实现后应按仓库既有命令运行：

```text
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

本次仅创建开发前文档，未运行以上命令。

## 手工验证

| ID | 场景 | 通过条件 |
|---|---|---|
| TEST-CDH-M01 | 全局视图，至少 2 张卡片，无搜索 | 手柄显示；从手柄开始拖拽可换位；刷新后顺序保留。 |
| TEST-CDH-M02 | 正数真实分组，至少 2 张成员，无搜索 | 手柄显示；拖拽后刷新该分组，成员顺序保留；全局顺序不作为本项断言。 |
| TEST-CDH-M03 | 搜索、收藏夹、最近使用和未分类 | 手柄都隐藏，长按根卡片打开原菜单，不会排序。 |
| TEST-CDH-M04 | 只有 0 或 1 张卡片的任意允许视图 | 手柄隐藏，不能拖拽。 |
| TEST-CDH-M05 | 可排序全局或真实分组 | 点击手柄外的卡片区域进入分享流程并记录最近使用；长按根卡片打开原上下文菜单。 |
| TEST-CDH-M06 | 普通、动图和隐写导入卡片 | 手柄、右上角标和底部名称同时显示时无重叠；读屏能读取手柄本地化描述。 |

这些手工项依赖 Android 模拟器或真实设备，以及足以构造全局、真实分组和虚拟分组场景的本地数据。开发前阶段尚未具备或执行该验证，不得将其视为通过。

## 变更后文档影响

实现会改变已记录的排序入口和操作方式。完成代码与验证后，必须执行 TASK-CDH-006，更新 `README.md` 与 `AGENTS.md` 的拖拽排序说明。本阶段依照限定范围不修改它们。

## 追踪总表

| 需求 | 验收 | 交互 | 技术 | 任务 | 测试 |
|---|---|---|---|---|---|
| REQ-CDH-001 | AC-CDH-001 | UX-CDH-001 | TECH-CDH-001 | TASK-CDH-001 | TEST-CDH-001 |
| REQ-CDH-002 | AC-CDH-002, AC-CDH-003 | UX-CDH-001, UX-CDH-002 | TECH-CDH-002 | TASK-CDH-002 | TEST-CDH-002 |
| REQ-CDH-003 | AC-CDH-004 | UX-CDH-002 | TECH-CDH-002 | TASK-CDH-002 | TEST-CDH-003 |
| REQ-CDH-004 | AC-CDH-005 | UX-CDH-004 | TECH-CDH-003 | TASK-CDH-003 | TEST-CDH-004, TEST-CDH-M05 |
| REQ-CDH-005 | AC-CDH-006 | UX-CDH-005 | TECH-CDH-004 | TASK-CDH-004 | TEST-CDH-005, TEST-CDH-M01 |
| REQ-CDH-006 | AC-CDH-007 | UX-CDH-005 | TECH-CDH-004 | TASK-CDH-004 | TEST-CDH-005, TEST-CDH-M02 |
| REQ-CDH-007 | AC-CDH-008 | UX-CDH-003, UX-CDH-006 | TECH-CDH-005 | TASK-CDH-005 | TEST-CDH-006, TEST-CDH-M06 |
