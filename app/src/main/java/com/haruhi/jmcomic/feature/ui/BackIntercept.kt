package com.haruhi.jmcomic.feature.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * 页面内部的返回键拦截（多选退出、编辑页未保存、注册验证码步骤……）。
 *
 * 为什么不用 Compose 自带的 [BackHandler]：
 * 平板分栏时两个 [androidx.navigation.compose.NavHost] 同时挂在组合里，每个 NavHost
 * 内部都会用 `PredictiveBackHandler` 抢系统返回键，而它**比 ModeRoot 注册得更晚**，
 * 于是返回键会退掉"背后那一栏"的页面（本页却留在屏幕上）。[BackHandler] 的优先级
 * 完全由注册顺序决定，Compile 侧拿不到更晚的注册时机。
 *
 * 所以这里自己做一份"优先级表"：页面用 [PageBackHandler] 注册自己的返回拦截，
 * ModeRoot 的返回逻辑放在两个 NavHost **之后**组合（注册顺序最靠后，必定赢过
 * NavHost 的内部回调），按键时先问这张表，没人接管才走轮播的返回逻辑。
 */
internal class BackInterceptors {

    /** 单个页面的拦截器：`enabled` 与回调都跟着组合刷新。 */
    internal class Entry {
        var onBack: () -> Unit = {}
        var enabled by mutableStateOf(true)
    }

    private val entries = mutableStateListOf<Entry>()

    /** 当前有没有页面要接管返回键（读的是快照状态，值变了会触发重组）。 */
    val hasEnabled: Boolean
        get() = entries.any { it.enabled }

    internal fun register(entry: Entry) {
        entries.add(entry)
    }

    internal fun remove(entry: Entry) {
        entries.remove(entry)
    }

    /**
     * 把返回键交给最后注册、且处于启用状态的那个页面。
     *
     * @return 有页面接手返回 true；没人接管返回 false。
     */
    fun consume(): Boolean {
        val entry = entries.lastOrNull { it.enabled } ?: return false
        entry.onBack()
        return true
    }
}

/** 当前的拦截表；不在 ModeRoot 之下（例如预览 / 测试）时为 null。 */
internal val LocalBackInterceptors = compositionLocalOf<BackInterceptors?> { null }

/**
 * 页面级返回拦截，语义与 [BackHandler] 相同，但优先级是"越晚注册越高"，
 * 并且**一定**高于 ModeRoot 的轮播返回逻辑（页面自己先处理）。
 *
 * 找不到拦截表时退回普通 [BackHandler]，这样单独预览某个页面也能正常工作。
 */
@Composable
internal fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val registry = LocalBackInterceptors.current
    if (registry == null) {
        BackHandler(enabled = enabled, onBack = onBack)
        return
    }
    val currentOnBack by rememberUpdatedState(onBack)
    val entry = remember(registry) { BackInterceptors.Entry() }
    DisposableEffect(registry, entry) {
        // 注册放在 effect 里（组合期只读不写），离开页面时自动摘掉
        registry.register(entry)
        onDispose { registry.remove(entry) }
    }
    SideEffect {
        entry.onBack = { currentOnBack() }
        entry.enabled = enabled
    }
}
