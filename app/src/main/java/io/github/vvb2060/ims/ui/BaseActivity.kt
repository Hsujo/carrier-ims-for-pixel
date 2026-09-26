package io.github.vvb2060.ims.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import io.github.vvb2060.ims.ui.theme.TurbolImsTheme

abstract class BaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TurbolImsTheme {
                Content()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manifest 声明了 configChanges（含 uiMode），深浅色切换时 Activity 不重建，
        // 需要按新配置重新设置系统栏图标颜色
        enableEdgeToEdge()
    }

    @Composable
    abstract fun Content()
}
