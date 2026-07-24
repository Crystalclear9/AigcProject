# 墨斐贴边半圆能力轨道设计

## 问题证据

真机截图显示应用内 340dp 完整圆环遮挡首页内容和“导入截图”入口，七个常驻文字标签进一步增加视觉噪声。真机状态同时确认悬浮窗权限与功能开关已开启，但 `MascotOverlayService` 不存在；崩溃栈为：

```text
java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from android.widget.FrameLayout
```

现有实现只在内部 `ComposeView` 设置 ViewTree owners，WindowRecomposer 实际从 WindowManager 挂载的根 `FrameLayout` 查找，因此服务创建悬浮 Compose 内容后崩溃。

## 视觉方案

采用贴边半圆轨道，不再显示完整图片圆环：

- 墨斐停靠左侧时，轨道向右侧屏幕内部展开；停靠右侧时自动镜像向左展开。
- 墨斐位于半圆弧的侧边圆心锚点，能力图标沿约 180 度弧线分布。
- 应用内和跨 App 共用同一套半圆几何与图标资源。
- 展开容器约 160×280dp；相比 340×340dp 完整圆环，遮挡面积显著下降。
- 图标缩小到约 42–46dp；移除常驻文字胶囊，通过无障碍描述保留动作名称。
- 权限不足使用小锁印记/角标，不再在底部显示横向权限说明条。
- 保留冰蓝玻璃弧线、节点微光和墨斐本体，不退化成普通卡片或工具栏。

## 交互

- 轻点墨斐：展开或收起半圆能力轨道。
- 拖动墨斐：立即收起轨道，允许上下移动；松手后吸附最近侧边并保存位置。
- 点击动作：先收起轨道，再执行原有动作。
- 点击悬浮窗外部：收起轨道。
- 长按墨斐：保留“隐藏一小时/关闭悬浮墨斐”控制。
- 应用在前台仅显示应用内墨斐；进入后台后启动跨 App 悬浮墨斐。

## 后台悬浮修复

创建 WindowManager 根视图时，在 `FrameLayout` 上同时安装：

- `ViewTreeLifecycleOwner`
- `ViewTreeViewModelStoreOwner`
- `ViewTreeSavedStateRegistryOwner`

内部 `ComposeView` 继承根节点 owners，不再自行形成不完整的 Window tree。服务仍先检查显式开关和 `SYSTEM_ALERT_WINDOW`，随后立即进入前台并挂载侧边墨斐。

## 测试与验收

- JVM 几何测试：左右半圆镜像、全部动作保持在窄容器内、展开窗口不再是方形大圆环。
- Android UI 测试：动作节点数、回调、权限角标语义仍存在。
- ViewTree owner 测试：根节点可解析 lifecycle、ViewModelStore 和 SavedState owners。
- 真机验证：应用切到桌面后服务不崩溃、侧边墨斐可见；拖动并重新停靠；展开半圆；返回应用后系统悬浮窗消失且应用内墨斐正常。
- 完整执行 JVM 单测、APK 与 AndroidTest APK 构建，并覆盖安装到当前 vivo 设备。
