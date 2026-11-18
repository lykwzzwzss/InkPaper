package com.inkpaper.epd

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.inkpaper.epd.nfc.NfcFlashSession
import com.inkpaper.epd.nfc.ShizukuChannel
import com.inkpaper.epd.ui.MainScreen
import com.inkpaper.epd.ui.MainViewModel
import com.inkpaper.epd.ui.theme.InkPaperTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            viewModel.onShizukuPermissionChanged()
        } else {
            viewModel.refreshPrivilege(autoConnectShizuku = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (state.flashState.isActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            InkPaperTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPrivilege(autoConnectShizuku = false)
    }

    override fun onStop() {
        if (viewModel.uiState.value.flashState.isActive) {
            viewModel.stopFlash("应用切到后台，自动安全停止")
        }
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { NfcFlashSession.teardown(this) }
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        ShizukuChannel.unbind()
        super.onDestroy()
    }
}
