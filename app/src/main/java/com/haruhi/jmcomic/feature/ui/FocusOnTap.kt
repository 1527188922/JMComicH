package com.haruhi.jmcomic.feature.ui

import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull

/**
 * 「点输入框以外的地方就失焦、收起软键盘」——全应用共用的一条实现。
 *
 * 为什么需要自己写：Compose 里**没有**"点空白处自动清焦点"这回事，官方也没有哪个开关能打开它
 * （平台层没有这个参数，Compose 侧只有 `FocusManager.clearFocus()`）。焦点是输入框内部那个
 * `Modifier.focusable()` 持有的：点空白处既没人请求焦点、也没人清掉旧焦点，于是输入框一直留着
 * 焦点、软键盘一直挂着。挂载点见 `ModeRoot`（根节点一处，覆盖所有页面）。
 *
 * 实现上有两条**必须同时满足**的约束，两条都是本仓库踩过的坑（见 `docs/PROGRESS.md`
 * 第十八~二十轮与第二十三轮）：
 *
 * 1. **只能用 `detectTapGestures` 这种"只观察、不消费"的写法**，绝不能给根节点换成
 *    `Modifier.focusable()`、或自写一个在按下时 `consume()` 掉 down 的检测器。
 *    详情页的 `SelectionContainer` 是靠"按下事件没被消费"才起选的，根节点一旦消费 down，
 *    长按选字就废掉（历史上已经被撤回两次）。`detectTapGestures` 运行在**主通道**
 *    （Main pass），是**最后一个**收到事件的节点：输入框、卡片、选择手势这些子节点早已处理完，
 *    所以"只观察 + 清焦点"抢不到任何子节点的手势。
 *
 * 2. **点进输入框里面时不能清焦点**。这是最容易翻车的地方，而且**不能**用
 *    "down 被消费了"来判断——虽然 `BasicTextField` 内部的 `detectTapAndPress` 确实会消费 down，
 *    但卡片上的 `clickable` 同样会消费，那样判断会把"点卡片收起键盘"一起漏掉。
 *    这里改用**语义树里的实际边界**：语义树中带 `Focused`、同时带 `EditableText` 的节点
 *    就是当前获得焦点的那个输入框（`TextFieldDecoratorModifier.applySemantics()` 把
 *    `editableText` 和 `focusable` 的语义设在同一节点上），它的 `touchBoundsInRoot` 就是该
 *    输入框在窗口里的矩形。抬指位置落在里面 → 是框内操作（重定位光标、选字），什么都不做；
 *    落在外面 → `clearFocus()`，软键盘随之收起。
 *
 * 另外几个实现细节：
 * - `detectTapGestures` 只在"按下—抬起几乎没挪动"时才回调 `onTap`，列表滚动不会误触；
 * - 语义树的 `touchBoundsInRoot` 是**窗口**坐标，而回调拿到的是**本节点内**坐标：
 *   本 modifier 挂在整屏根节点上，两者等价（详见 [clearFocusOnTap] 里的注释）；
 * - 查询的是**未合并**的语义树（`mergingEnabled = false`）：`Focused` / `EditableText`
 *   是叶子节点自己设的属性，合并后的树里可能已被折叠（无障碍那边读的也是 `unmergedConfig`）；
 * - 只在**当前窗口**里收事件，而对话框是独立窗口、独立语义树，所以对话框里的输入框不会
 *   被主窗口的根节点误清焦点；
 * - 清焦点本身是幂等的，所以这里不必先判断"当前到底有没有焦点"。
 */
@Composable
internal fun Modifier.clearFocusOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    // 语义树挂在承载这次组合的 View（AndroidComposeView）上，不在 Composition 里；
    // 它在本次组合内恒定，可以当 pointerInput 的 key。
    val view = LocalView.current
    return this.pointerInput(view) {
        detectTapGestures { position ->
            // 这里的 `position` 是**本节点内**的坐标；而语义树的 `touchBoundsInRoot` 是**窗口**坐标。
            // 两者相等的前提是"本节点就在窗口原点"——本 modifier 挂在 `ModeRoot` 的根 `Scaffold`
            // 上，那个节点就是 `fillMaxSize()` 的整屏根节点，所以恒成立。
            //
            // 注：不要试图在这里做 `localToRoot()` 换算。Compose 1.12 里指针输入作用域
            // （`PointerInputScope` / `AwaitPointerEventScope`）**没有**任何公开的布局坐标可用
            // （`layoutCoordinates` 只是 `PointerEvent` 上的 internal 属性），
            // 为了换算再套一层 `onGloballyPositioned` 记坐标属于纯负债。
            if (!isTapInsideFocusedTextField(view, position)) {
                focusManager.clearFocus()
            }
        }
    }
}

/**
 * 抬指位置是否落在**当前获得焦点的输入框**里。
 *
 * @param position 窗口坐标，与 [androidx.compose.ui.semantics.SemanticsNode.touchBoundsInRoot] 同一坐标系
 */
private fun isTapInsideFocusedTextField(view: View, position: Offset): Boolean {
    // 主窗口里这个 View 就是 AndroidComposeView（实现 ViewRootForTest），语义树挂在它身上
    // （`semanticsOwner` 本身是非空的，这里只为"拿不到就当作不在框内"这一条兜底判断）
    val root = view as? ViewRootForTest ?: return false
    val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
    for (index in nodes.indices) {
        val node = nodes[index]
        // 带 Focused 又带 EditableText 的节点 = 正拿着焦点的那个输入框
        if (node.config.getOrNull(SemanticsProperties.Focused) != true) continue
        if (!node.config.contains(SemanticsProperties.EditableText)) continue
        return node.touchBoundsInRoot.contains(position)
    }
    return false
}
