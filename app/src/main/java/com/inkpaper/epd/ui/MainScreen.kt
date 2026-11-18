package com.inkpaper.epd.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkpaper.epd.R
import com.inkpaper.epd.nfc.PrivilegeTier
import com.inkpaper.epd.protocol.PanelProtocol
import com.inkpaper.epd.protocol.PanelType
import com.inkpaper.epd.protocol.RasterStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onPhotoPicked(uri)
    }

    val openGallery = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HeaderBar(
                tier = ui.privilege?.tier,
                nfcEnabled = ui.privilege?.nfcEnabled ?: true,
                onStatusClick = { viewModel.toggleStatusSheet(true) },
            )
        },
        bottomBar = {
            BottomControlDock(
                ui = ui,
                onSelectPanel = viewModel::selectPanelType,
                onPrimaryFlash = {
                    val tier = ui.privilege?.tier
                    when {
                        ui.prepared == null -> openGallery()
                        tier == PrivilegeTier.SHIZUKU_PENDING -> viewModel.requestShizukuGrant()
                        else -> viewModel.startFlash(activity)
                    }
                },
                onStopFlash = { viewModel.stopFlash() },
            )
        },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
        ) {
            // Reserve space for StyleThumbnailStrip (~76dp) + balanced gaps above/below (~32dp)
            val cardHeight = minOf(440.dp, (maxHeight - 108.dp).coerceAtLeast(240.dp))
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(4.dp))

                HeroPreviewCard(
                    ui = ui,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight),
                    onPickPhoto = openGallery,
                    onOpenCrop = viewModel::openCropEditor,
                    onRotate = viewModel::rotateCurrent,
                    onOpenZoom = { viewModel.togglePixelZoom(true) },
                )

                Spacer(Modifier.weight(1f))

                StyleThumbnailStrip(
                    selected = ui.style,
                    thumbnails = ui.thumbnails,
                    enabled = !ui.flashState.isActive && ui.prepared != null,
                    onSelect = viewModel::selectStyle,
                )

                Spacer(Modifier.weight(1f))
            }
        }
    }

    // Full-screen interactive crop overlay
    ui.cropSource?.let { sourceBmp ->
        CropOverlay(
            source = sourceBmp,
            onCancel = viewModel::dismissCropEditor,
            onConfirm = viewModel::applyCroppedBitmap,
        )
    }

    // Full-screen 1:1 pixel inspector
    val preparedPreview = ui.prepared?.preview
    if (ui.showPixelZoom && preparedPreview != null) {
        PixelZoomDialog(
            bitmap = preparedPreview,
            onDismiss = { viewModel.togglePixelZoom(false) },
        )
    }

    // Auto-privilege diagnostic & log bottom sheet
    if (ui.showStatusSheet) {
        StatusAndLogSheet(
            ui = ui,
            deviceLine = viewModel.deviceLine(),
            onRequestShizuku = viewModel::requestShizukuGrant,
            onOpenLicense = { viewModel.toggleLicenseDialog(true) },
            onDismiss = { viewModel.toggleStatusSheet(false) },
        )
    }

    if (ui.showLicenseDialog) {
        val text = remember { viewModel.readLicenseText() }
        AlertDialog(
            onDismissRequest = { viewModel.toggleLicenseDialog(false) },
            title = { Text("开源许可 · PolyForm Noncommercial 1.0.0", fontSize = 16.sp) },
            text = {
                Text(
                    text = text,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .height(340.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.toggleLicenseDialog(false) }) {
                    Text("关闭")
                }
            },
        )
    }
}

@Composable
private fun HeaderBar(
    tier: PrivilegeTier?,
    nfcEnabled: Boolean,
    onStatusClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_nfc),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column {
                Text(
                    text = "墨纸 · 3.98\"",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "552×768 · 官方查电",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val dotColor = when {
            !nfcEnabled -> MaterialTheme.colorScheme.error
            tier?.readyToFlash == true -> MaterialTheme.colorScheme.primary
            else -> Color(0xFFD98200)
        }
        val badgeText = when {
            !nfcEnabled -> "NFC 未开启"
            tier != null -> tier.shortBadge
            else -> "检测中…"
        }

        Surface(
            onClick = onStatusClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = badgeText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HeroPreviewCard(
    ui: MainUiState,
    modifier: Modifier = Modifier,
    onPickPhoto: () -> Unit,
    onOpenCrop: () -> Unit,
    onRotate: () -> Unit,
    onOpenZoom: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val matReservePx = with(density) { 18.dp.roundToPx() }
                val availW = (constraints.maxWidth - matReservePx).coerceAtLeast(1)
                val availH = (constraints.maxHeight - matReservePx).coerceAtLeast(1)
                val fitsPixelForPixel = availW >= PanelProtocol.WIDTH && availH >= PanelProtocol.HEIGHT
                val frameSizeModifier = if (fitsPixelForPixel) {
                    with(density) {
                        Modifier.size(PanelProtocol.WIDTH.toDp(), PanelProtocol.HEIGHT.toDp())
                    }
                } else {
                    val ratio = PanelProtocol.WIDTH.toFloat() / PanelProtocol.HEIGHT.toFloat()
                    val targetH = minOf(availH / density.density, (availW / density.density) / ratio).dp
                    val targetW = targetH * ratio
                    Modifier.size(targetW, targetH)
                }

                // Soft gallery mat frame so even pure-white e-ink images stand out crisply
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                ) {
                    val frame = ui.prepared
                    Box(modifier = Modifier.padding(8.dp)) {
                        if (frame != null) {
                            val img = remember(frame.preview) { frame.preview.asImageBitmap() }
                            Box(
                                modifier = frameSizeModifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable(enabled = !ui.flashState.isActive) { onOpenZoom() },
                            ) {
                                Image(
                                    bitmap = img,
                                    contentDescription = "1:1 墨水屏预览，点按放大查看",
                                    contentScale = ContentScale.FillBounds,
                                    filterQuality = FilterQuality.None,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            Box(
                                modifier = frameSizeModifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable(enabled = !ui.isPreparingImage) { onPickPhoto() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(16.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_photo),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(30.dp),
                                    )
                                    Text(
                                        text = if (ui.isPreparingImage) "正在处理图片…" else "轻触选择图片",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "552×768 四色",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val frame = ui.prepared
            Text(
                text = when {
                    ui.isPreparingImage -> "正在出图…"
                    frame != null -> "${frame.style.label} · 非白 ${frame.nonWhitePercent}% · 点击预览放大"
                    else -> "选择一张照片开始制作"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPill(
                    iconRes = R.drawable.ic_photo,
                    label = if (frame == null) "选图" else "换图",
                    enabled = !ui.flashState.isActive && !ui.isPreparingImage,
                    modifier = Modifier.weight(1f),
                    onClick = onPickPhoto,
                )
                ActionPill(
                    iconRes = R.drawable.ic_crop,
                    label = "裁切",
                    enabled = frame != null && !ui.flashState.isActive && !ui.isPreparingImage,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCrop,
                )
                ActionPill(
                    iconRes = R.drawable.ic_rotate,
                    label = "旋转",
                    enabled = frame != null && !ui.flashState.isActive && !ui.isPreparingImage,
                    modifier = Modifier.weight(1f),
                    onClick = onRotate,
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.72f else 0.35f),
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun StyleThumbnailStrip(
    selected: RasterStyle,
    thumbnails: Map<RasterStyle, android.graphics.Bitmap>,
    enabled: Boolean,
    onSelect: (RasterStyle) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (style in RasterStyle.entries) {
            val isSelected = style == selected
            val thumb = thumbnails[style]
            val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface

            Surface(
                onClick = { onSelect(style) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = bgColor,
                border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 26.dp, height = 36.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)),
                    ) {
                        if (thumb != null) {
                            val bmp = remember(thumb) { thumb.asImageBitmap() }
                            Image(
                                bitmap = bmp,
                                contentDescription = style.label,
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = style.label,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControlDock(
    ui: MainUiState,
    onSelectPanel: (PanelType) -> Unit,
    onPrimaryFlash: () -> Unit,
    onStopFlash: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ONLY setting kept on UI: Screen Type A1 / A0 / A0X
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "屏幕类型",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = ui.panelType.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SingleChoiceSegmentedButtonRow {
                    PanelType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = ui.panelType == type,
                            onClick = { onSelectPanel(type) },
                            enabled = !ui.flashState.isActive,
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = PanelType.entries.size,
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                                inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                            label = {
                                Text(
                                    text = type.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                        )
                    }
                }
            }

            // Dynamic NFC feedback banner or primary action button.
            // Key active states under a single bucket so per-second countdown and per-packet
            // progress updates never re-trigger AnimatedContent fade/reset transitions.
            AnimatedContent(
                targetState = ui.flashState,
                contentKey = { if (it.isActive) "ACTIVE" else it },
                label = "flash-state",
            ) { flash ->
                when (flash) {
                    is FlashUiState.Preparing -> ActiveProgressBox(
                        title = "正在初始化 NFC 权限与兼容层…",
                        subtitle = "请稍候",
                        progress = null,
                        canCancel = true,
                        onCancel = onStopFlash,
                    )
                    is FlashUiState.Scanning -> ActiveProgressBox(
                        title = "请将手机背面紧贴屏幕线圈（${flash.secondsLeft}s）",
                        subtitle = "贴稳保持不动 · 识别到即自动传图",
                        progress = null,
                        canCancel = true,
                        onCancel = onStopFlash,
                    )
                    is FlashUiState.Transferring -> ActiveProgressBox(
                        title = flash.title,
                        subtitle = if (flash.total > 1) "${flash.done} / ${flash.total} 包 (${flash.done * 100 / flash.total}%)" else "请勿移动手机",
                        progress = if (flash.total > 1) flash.done.toFloat() / flash.total else null,
                        canCancel = true,
                        onCancel = onStopFlash,
                    )
                    is FlashUiState.Refreshing -> ActiveProgressBox(
                        title = flash.message,
                        subtitle = "墨水屏正在升压刷新波形，完成后会自动提示，请勿移开",
                        progress = null,
                        canCancel = false,
                        onCancel = {},
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (flash) {
                            is FlashUiState.Success -> FeedbackBanner(
                                isError = false,
                                title = "刷新完成",
                                message = flash.message,
                            )
                            is FlashUiState.Error -> FeedbackBanner(
                                isError = true,
                                title = flash.headline,
                                message = flash.detail,
                            )
                            is FlashUiState.Cancelled -> FeedbackBanner(
                                isError = false,
                                title = "已停止",
                                message = flash.message,
                            )
                            else -> Unit
                        }

                        val tier = ui.privilege?.tier
                        val buttonText = when {
                            ui.prepared == null -> "选择图片并裁切"
                            tier == PrivilegeTier.SHIZUKU_PENDING -> "授权 Shizuku 后刷图"
                            else -> "贴近屏幕并刷图"
                        }
                        Button(
                            onClick = onPrimaryFlash,
                            enabled = !ui.isPreparingImage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nfc),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(buttonText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveProgressBox(
    title: String,
    subtitle: String,
    progress: Float?,
    canCancel: Boolean,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canCancel) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("停止", fontSize = 12.sp)
                    }
                }
            }
            if (progress != null) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 180),
                    label = "upload-progress",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun FeedbackBanner(
    isError: Boolean,
    title: String,
    message: String,
) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    val fg = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusAndLogSheet(
    ui: MainUiState,
    deviceLine: String,
    onRequestShizuku: () -> Unit,
    onOpenLicense: () -> Unit,
    onDismiss: () -> Unit,
) {
    val priv = ui.privilege
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = priv?.tier?.title ?: "检查权限状态…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "自动选用最高权限：Root > Shizuku > 系统直连",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(priv?.tier?.detail ?: "", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("设备：$deviceLine", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("芯片：${priv?.chipSummary ?: "-"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "查电模式：官方查电 (D4 0x00) · 上电等待 500ms",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (priv?.tier == PrivilegeTier.SHIZUKU_PENDING) {
                Button(
                    onClick = onRequestShizuku,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("授予 Shizuku 权限")
                }
            }

            Text("最近运行日志", style = MaterialTheme.typography.labelLarge)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF131816),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
            ) {
                val logText = if (ui.logs.isEmpty()) "尚无传输日志" else ui.logs.joinToString("\n")
                Text(
                    text = logText,
                    color = Color(0xFFCDE0D8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenLicense) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("协议与开源声明")
                }
                FilledTonalButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    }
}
