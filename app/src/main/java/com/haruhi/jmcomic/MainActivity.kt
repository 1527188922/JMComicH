package com.haruhi.jmcomic

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.haruhi.jmcomic.feature.ui.ModeRoot
import com.haruhi.jmcomic.ui.theme.JmComicTheme

/**
 * 唯一的 Activity。
 *
 * P0/P1 的占位页已经删除：现在直接进入**漫画模式**。
 * 「开屏选择（漫画 / 视频）+ 模式切换」按计划属于 P6（壳与体验），
 * 而 §9.5 明确要求「未实现的功能不展示空页面或占位开关」，
 * 所以这里不放假的开屏，等 P6 一次做到位。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableCardWhiteEdgeToEdge()
        val container = JmComicApp.from(this)
        setContent {
            JmComicTheme {
                // 根背景用 `surface`（白）而不是内容区的 `background`（#F5F5F5）：
                // 状态栏那条（edge-to-edge 下由根 Scaffold 的 windowInsets 让出来）
                // 露出的就是这个颜色，必须是纯白，才能和底栏 NavigationBar
                // （`surfaceContainer` = #FFFFFF）一致。
                // 各页**滚动内容区**的灰底由各 Screen 自己铺 `background`。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    ModeRoot(container = container)
                }
            }
        }
    }

    /**
     * 边到边 + **真透明**的系统栏 + 固定深色图标。
     *
     * 两步都不能省：
     *
     * 1. **透明**：无参的 `enableEdgeToEdge()` 默认走 `SystemBarStyle.auto(...)`，会把状态栏
     *    预涂成 `#E6FFFFFF`（90% 白）、导航栏预涂成 `#E6000000`。那层半透明白压在内容上，
     *    顶栏就不再是纯白，跟底栏对不上色。这里两个栏都涂成真正的透明，露出页面自己的白。
     * 2. **深色图标**：图标明暗由 `detectDarkMode` 决定，无参版本是按**系统深色模式**判断的。
     *    现在状态栏恒为纯白，系统一旦处于深色模式就会被画上浅色图标 —— 白底白图标直接看不见。
     *    所以这里恒返回 false（= 深色图标）。
     *
     * ⚠ 将来主题做成设置项（含深色）时，这个判断必须跟着 Compose 主题一起改成背景色感知
     * （例如 `Color.luminance() > 0.5f`），否则深色主题下会反过来画错。
     */
    private fun enableCardWhiteEdgeToEdge() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
                detectDarkMode = { false },
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
                detectDarkMode = { false },
            ),
        )
    }

    /**
     * 退出收尾（返回键退出根页面时 `isFinishing == true`）：**下载暂停、导出任务删除**，
     * 具体见 [com.haruhi.jmcomic.core.di.AppContainer.handleAppExit]（用户拍板的行为）。
     *
     * 必须判 [isFinishing]：locale / 主题这类**没有**在 manifest 里声明 `configChanges`
     * 的配置变更仍会销毁重建 Activity，但那是"页面重建"不是"退出应用"，任务要继续跑。
     * 划掉最近任务由 `DownloadForegroundService.onTaskRemoved` 触发，
     * 两边调同一个入口、且幂等。
     */
    override fun onDestroy() {
        if (isFinishing) {
            JmComicApp.from(this).handleAppExit()
        }
        super.onDestroy()
    }
}
