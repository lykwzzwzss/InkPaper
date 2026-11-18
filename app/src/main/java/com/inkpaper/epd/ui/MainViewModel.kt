package com.inkpaper.epd.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkpaper.epd.imaging.ImageProcessor
import com.inkpaper.epd.imaging.PreparedFrame
import com.inkpaper.epd.nfc.NfcFlashSession
import com.inkpaper.epd.nfc.PrivilegeManager
import com.inkpaper.epd.nfc.PrivilegeSnapshot
import com.inkpaper.epd.nfc.PrivilegeTier
import com.inkpaper.epd.nfc.SessionEvent
import com.inkpaper.epd.nfc.ShizukuChannel
import com.inkpaper.epd.protocol.PanelProtocol
import com.inkpaper.epd.protocol.PanelType
import com.inkpaper.epd.protocol.RasterStyle
import com.inkpaper.epd.protocol.ReinkRaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface FlashUiState {
    data object Idle : FlashUiState
    data object Preparing : FlashUiState
    data class Scanning(val secondsLeft: Int) : FlashUiState
    data class Transferring(
        val phase: String,
        val title: String,
        val done: Int,
        val total: Int,
    ) : FlashUiState
    data class Refreshing(val message: String) : FlashUiState
    data class Success(val message: String) : FlashUiState
    data class Error(val headline: String, val detail: String) : FlashUiState
    data class Cancelled(val message: String) : FlashUiState

    val isActive: Boolean
        get() = this is Preparing || this is Scanning || this is Transferring || this is Refreshing
}

data class MainUiState(
    val panelType: PanelType = PanelType.A1,
    val style: RasterStyle = RasterStyle.TEXTURE,
    val prepared: PreparedFrame? = null,
    val thumbnails: Map<RasterStyle, Bitmap> = emptyMap(),
    val imageTitle: String = "",
    val isPreparingImage: Boolean = false,
    val privilege: PrivilegeSnapshot? = null,
    val flashState: FlashUiState = FlashUiState.Idle,
    val logs: List<String> = emptyList(),
    val cropSource: Bitmap? = null,
    val showPixelZoom: Boolean = false,
    val showStatusSheet: Boolean = false,
    val showLicenseDialog: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        MainUiState(
            panelType = PanelType.fromName(prefs.getString(KEY_PANEL_TYPE, PanelType.A1.name)),
            style = RasterStyle.fromIndex(prefs.getInt(KEY_STYLE, RasterStyle.TEXTURE.modeIndex)),
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var originalBitmap: Bitmap? = null
    private var sourceBitmap: Bitmap? = null
    private var fittedPixels: IntArray? = null
    private val styleCodesCache = mutableMapOf<RasterStyle, ByteArray>()
    private val stylePreviewCache = mutableMapOf<RasterStyle, Bitmap>()
    private var rotationDegrees = 0
    private var renderRevision = 0
    private var activeSession: NfcFlashSession? = null

    init {
        restoreCachedImage()
        refreshPrivilege(autoConnectShizuku = true)
    }

    fun refreshPrivilege(autoConnectShizuku: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val snapshot = PrivilegeManager.inspect(ctx)
            _uiState.update { it.copy(privilege = snapshot) }

            if (autoConnectShizuku && !snapshot.hasRoot && snapshot.shizukuAlive) {
                if (snapshot.shizukuGranted) {
                    ShizukuChannel.bind(ctx)
                } else {
                    withContext(Dispatchers.Main) {
                        ShizukuChannel.requestPermission()
                    }
                }
            }
        }
    }

    fun onShizukuPermissionChanged() {
        val ctx = getApplication<Application>()
        if (ShizukuChannel.hasPermission()) {
            ShizukuChannel.bind(ctx)
        }
        refreshPrivilege(autoConnectShizuku = false)
    }

    fun requestShizukuGrant() {
        if (ShizukuChannel.isBinderAlive()) {
            ShizukuChannel.requestPermission()
        }
    }

    fun selectPanelType(panelType: PanelType) {
        if (_uiState.value.flashState.isActive || _uiState.value.panelType == panelType) return
        prefs.edit().putString(KEY_PANEL_TYPE, panelType.name).apply()
        val style = _uiState.value.style
        val cachedCodes = styleCodesCache[style]
        val cachedPreview = stylePreviewCache[style]
        if (cachedCodes != null && cachedPreview != null) {
            val updated = ImageProcessor.prepareFromCodes(cachedCodes, panelType, style, cachedPreview)
            _uiState.update { it.copy(panelType = panelType, prepared = updated) }
            return
        }
        _uiState.update { it.copy(panelType = panelType) }
        rerenderQuantization()
    }

    fun selectStyle(style: RasterStyle) {
        if (_uiState.value.flashState.isActive || _uiState.value.style == style) return
        prefs.edit().putInt(KEY_STYLE, style.modeIndex).apply()
        val panelType = _uiState.value.panelType
        val cachedCodes = styleCodesCache[style]
        val cachedPreview = stylePreviewCache[style]
        if (cachedCodes != null && cachedPreview != null) {
            val updated = ImageProcessor.prepareFromCodes(cachedCodes, panelType, style, cachedPreview)
            _uiState.update { it.copy(style = style, prepared = updated) }
            return
        }
        _uiState.update { it.copy(style = style) }
        rerenderQuantization()
    }

    fun onPhotoPicked(uri: Uri) {
        if (_uiState.value.flashState.isActive) return
        _uiState.update { it.copy(isPreparingImage = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ctx = getApplication<Application>()
                val loaded = ImageProcessor.loadFromUri(ctx.contentResolver, uri)
                ImageProcessor.saveLastOriginal(ctx, loaded)
                loaded
            }.onSuccess { bmp ->
                originalBitmap = bmp
                _uiState.update { it.copy(isPreparingImage = false, cropSource = bmp) }
            }.onFailure { err ->
                appendLog("选图失败: ${err.message}")
                _uiState.update {
                    it.copy(
                        isPreparingImage = false,
                        flashState = FlashUiState.Error("无法读取图片", err.message ?: "未知错误"),
                    )
                }
            }
        }
    }

    fun openCropEditor() {
        val src = originalBitmap ?: sourceBitmap ?: return
        if (_uiState.value.flashState.isActive) return
        _uiState.update { it.copy(cropSource = src) }
    }

    fun dismissCropEditor() {
        _uiState.update { it.copy(cropSource = null) }
    }

    fun applyCroppedBitmap(cropped: Bitmap) {
        rotationDegrees = 0
        sourceBitmap = cropped
        _uiState.update { it.copy(cropSource = null, imageTitle = "当前图片", flashState = FlashUiState.Idle) }
        viewModelScope.launch(Dispatchers.IO) {
            ImageProcessor.saveLastCrop(getApplication(), cropped)
            fitAndRender(cropped)
        }
    }

    fun rotateCurrent() {
        val src = sourceBitmap ?: return
        if (_uiState.value.flashState.isActive || _uiState.value.isPreparingImage) return
        rotationDegrees = (rotationDegrees + 90) % 360
        _uiState.update { it.copy(flashState = FlashUiState.Idle) }
        fitAndRender(src)
    }

    fun togglePixelZoom(show: Boolean) {
        if (show && (_uiState.value.prepared == null || _uiState.value.flashState.isActive)) return
        _uiState.update { it.copy(showPixelZoom = show) }
    }

    fun toggleStatusSheet(show: Boolean) {
        if (show) refreshPrivilege(autoConnectShizuku = false)
        _uiState.update { it.copy(showStatusSheet = show) }
    }

    fun toggleLicenseDialog(show: Boolean) {
        _uiState.update { it.copy(showLicenseDialog = show) }
    }

    fun startFlash(activity: Activity) {
        val current = _uiState.value
        if (current.flashState.isActive) return

        // If Shizuku is running cleanly but ungranted (and no Root), trigger permission directly.
        val priv = current.privilege
        if (priv != null && !priv.hasRoot && priv.tier == PrivilegeTier.SHIZUKU_PENDING) {
            ShizukuChannel.requestPermission()
            appendLog("正在请求 Shizuku 授权")
            return
        }

        val frame = current.prepared ?: return
        appendLog("开始刷屏 · 屏型=${frame.panelType} · 风格=${frame.style.label} · 官方查电=开启")
        _uiState.update { it.copy(flashState = FlashUiState.Preparing) }

        val session = NfcFlashSession(
            activity = activity,
            packedFrame = frame.packed.copyOf(),
            officialCheck = true,
        ) { event ->
            handleSessionEvent(event)
        }
        activeSession = session
        session.start()
    }

    fun stopFlash(reason: String = "用户取消") {
        val session = activeSession ?: return
        appendLog("停止请求: $reason")
        session.cancel()
    }

    fun readLicenseText(): String {
        val assets = getApplication<Application>().assets
        return buildString {
            appendLine("InkPaper（墨纸）基于以下开源项目与社区研究重整构建，本项目遵循 PolyForm Noncommercial License 1.0.0（非商业许可）：")
            appendLine("- yhl88890/qpoll-lite & yhl88890/huawei-nfc-eink-myron (PolyForm Noncommercial 1.0.0)")
            appendLine("- ZhaoGY-N/huawei-nfc-eink-android-app (QPollLab) & CLF184 / luzamm / HBWuChang / linckr")
            appendLine("- wiwiwei/ink-nfc-lab (PolyForm Noncommercial 1.0.0，四色抖动与冷色抑制算法)")
            appendLine("- kur0neko/huawei-epd-pn532 (PolyForm Noncommercial 1.0.0，APDU 协议与扫行打包)")
            appendLine("- RikkaApps/Shizuku-API (MIT License)")
            appendLine("- AndroidX / Jetpack Compose / Kotlin / Gradle (Apache-2.0)")
            appendLine()
            runCatching {
                assets.open("licenses/kur0neko-NOTICE.txt").bufferedReader().use {
                    appendLine(it.readText().trim())
                }
            }
            appendLine()
            runCatching {
                assets.open("licenses/kur0neko-LICENSE.txt").bufferedReader().use {
                    appendLine(it.readText().trim())
                }
            }
            appendLine()
            appendLine("=== Shizuku-API License (MIT) ===")
            runCatching {
                assets.open("licenses/Shizuku-LICENSE.txt").bufferedReader().use {
                    appendLine(it.readText().trim())
                }
            }
        }
    }

    private fun restoreCachedImage() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val restored = ImageProcessor.restoreLastImage(ctx) ?: return@launch
            val (bmp, isCropped) = restored
            originalBitmap = if (isCropped) ImageProcessor.restoreLastOriginal(ctx) else bmp
            sourceBitmap = bmp
            _uiState.update { it.copy(imageTitle = "上次图片") }
            fitAndRender(bmp)
        }
    }

    private fun fitAndRender(bmp: Bitmap) {
        val rev = ++renderRevision
        val angle = rotationDegrees
        styleCodesCache.clear()
        stylePreviewCache.clear()
        _uiState.update { it.copy(isPreparingImage = true) }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val fitted = ImageProcessor.fittedPixels(bmp, angle, cover = true)
                val thumbs = ImageProcessor.thumbnails(fitted)
                fitted to thumbs
            }.onSuccess { (fitted, thumbs) ->
                withContext(Dispatchers.Main) {
                    if (rev != renderRevision) return@withContext
                    fittedPixels = fitted
                    _uiState.update { it.copy(thumbnails = thumbs) }
                }
                renderWithFitted(fitted, rev)
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    if (rev != renderRevision) return@withContext
                    appendLog("图片缩放失败: ${err.message}")
                    _uiState.update { it.copy(isPreparingImage = false) }
                }
            }
        }
    }

    private fun rerenderQuantization() {
        val fitted = fittedPixels ?: return
        val rev = ++renderRevision
        // Keep existing preview and buttons active during quick mode/panel switches so the UI never flashes.
        viewModelScope.launch(Dispatchers.Default) {
            renderWithFitted(fitted, rev)
        }
    }

    private suspend fun renderWithFitted(fitted: IntArray, rev: Int) {
        val targetState = withContext(Dispatchers.Main) { _uiState.value }
        val targetStyle = targetState.style
        val existingCodes = withContext(Dispatchers.Main) { styleCodesCache[targetStyle] }
        val existingPreview = withContext(Dispatchers.Main) { stylePreviewCache[targetStyle] }

        val codes = existingCodes
            ?: ReinkRaster.quantize(fitted, PanelProtocol.WIDTH, PanelProtocol.HEIGHT, targetStyle)

        val previewBitmap = existingPreview ?: android.graphics.Bitmap.createBitmap(
            targetState.panelType.preview(targetState.panelType.pack(codes)),
            PanelProtocol.WIDTH,
            PanelProtocol.HEIGHT,
            android.graphics.Bitmap.Config.ARGB_8888,
        )

        withContext(Dispatchers.Main) {
            if (rev != renderRevision) return@withContext
            styleCodesCache[targetStyle] = codes
            stylePreviewCache[targetStyle] = previewBitmap
            val latestPanel = _uiState.value.panelType
            val frame = ImageProcessor.prepareFromCodes(
                codes = codes,
                panelType = latestPanel,
                style = targetStyle,
                existingPreview = previewBitmap,
            )
            _uiState.update {
                it.copy(
                    prepared = frame,
                    isPreparingImage = false,
                )
            }
        }
    }

    private fun handleSessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Scanning -> _uiState.update {
                it.copy(flashState = FlashUiState.Scanning(event.secondsLeft))
            }
            is SessionEvent.TagFound -> _uiState.update {
                it.copy(flashState = FlashUiState.Transferring("connected", "已识别屏幕 (${event.uidHex.take(8)})", 0, 1))
            }
            is SessionEvent.Stage -> _uiState.update { current ->
                if (event.phase == "refreshing") {
                    current.copy(flashState = FlashUiState.Refreshing(event.title))
                } else {
                    val prev = current.flashState as? FlashUiState.Transferring
                    current.copy(
                        flashState = FlashUiState.Transferring(
                            phase = event.phase,
                            title = event.title,
                            done = prev?.done ?: 0,
                            total = prev?.total ?: 1,
                        )
                    )
                }
            }
            is SessionEvent.Progress -> _uiState.update { current ->
                current.copy(
                    flashState = FlashUiState.Transferring(
                        phase = "uploading",
                        title = "正在写入屏幕",
                        done = event.done,
                        total = event.total,
                    )
                )
            }
            is SessionEvent.RefreshSuccess -> _uiState.update {
                it.copy(flashState = FlashUiState.Success(event.message))
            }
            is SessionEvent.SessionError -> _uiState.update {
                it.copy(flashState = FlashUiState.Error(event.headline, event.detail))
            }
            is SessionEvent.Cancelled -> _uiState.update {
                it.copy(flashState = FlashUiState.Cancelled(event.message))
            }
            is SessionEvent.LogLine -> appendLog(event.line)
            is SessionEvent.Finished -> {
                activeSession = null
                refreshPrivilege(autoConnectShizuku = false)
            }
        }
    }

    private fun appendLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formatted = "$stamp  $line"
        _uiState.update { state ->
            val updated = (state.logs + formatted).takeLast(120)
            state.copy(logs = updated)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = getApplication<Application>().getExternalFilesDir(null)
                    ?: getApplication<Application>().filesDir
                val logFile = File(dir, "latest.log")
                FileWriter(logFile, true).use { it.appendLine(formatted) }
            }
        }
    }

    fun deviceLine(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    companion object {
        private const val PREFS_NAME = "inkpaper_prefs"
        private const val KEY_PANEL_TYPE = "panel_type"
        private const val KEY_STYLE = "raster_style"
    }
}
