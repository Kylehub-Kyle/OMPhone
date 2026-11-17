package com.omphone.capture

import android.Manifest
import android.content.Intent
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omphone.capture.AppBus.state
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 主题色板：跟随系统深浅色。属性读的是 mutableState，切换时自动触发重组，
 * 因此各组件里的 `Bg` / `CardBg` 等名字无需改动。
 */
private object Pal {
    var dark: Boolean by mutableStateOf(true)

    val bg: Color get() = if (dark) Color(0xFF0A0A0B) else Color(0xFFF4F5F7)
    val bgTop: Color get() = if (dark) Color(0xFF121214) else Color(0xFFEDEFF3)
    val cardBg: Color get() = if (dark) Color(0xFF151517) else Color(0xFFFFFFFF)
    val chipBg: Color get() = if (dark) Color(0xFF1E1E22) else Color(0xFFEBECF0)
    val chipBg2: Color get() = if (dark) Color(0xFF1C1C20) else Color(0xFFF0F1F4)
    val inputBg: Color get() = if (dark) Color(0xFF0F0F11) else Color(0xFFF7F8FA)
    val stroke: Color get() = if (dark) Color(0xFF26262B) else Color(0xFFE0E2E8)
    val accent: Color get() = if (dark) Color(0xFFFFC53D) else Color(0xFFE9A013)
    val onAccent: Color get() = if (dark) Color(0xFF141414) else Color(0xFF211700)
    val textMain: Color get() = if (dark) Color(0xFFEDEDEE) else Color(0xFF16171A)
    val textDim: Color get() = if (dark) Color(0xFF86868D) else Color(0xFF6C6D76)
    val textFaint: Color get() = if (dark) Color(0xFF55555C) else Color(0xFF9A9CA4)
    val danger: Color get() = if (dark) Color(0xFFFF7A7A) else Color(0xFFD24545)
    val barBg: Color get() = if (dark) Color(0xCC141417) else Color(0xD9FFFFFF)
    val onlineBg: Color get() = if (dark) Color(0xFF16301F) else Color(0xFFE6F8ED)
    val onlineFg: Color get() = if (dark) Color(0xFF9AE6B4) else Color(0xFF14713C)
    val onlineStroke: Color get() = if (dark) Color(0xFF2C5A3A) else Color(0xFFB3E2C5)

    /** 预览底色跟随主题：暗色近黑、白天纯白，不再是一律的黑。 */
    val previewBg: Color get() = if (dark) Color(0xFF000000) else Color(0xFFFFFFFF)

    // 浮在照片上的文字/遮罩，按主题取反色，保证任何照片上都读得清
    val overText: Color get() = if (dark) Color(0xFFF2F2F3) else Color(0xFF141519)
    val overSub: Color get() = if (dark) Color(0xB3F2F2F3) else Color(0xB3141519)
    val overScrimTop: Color get() = if (dark) Color(0xD9000000) else Color(0xF2FFFFFF)
    val overScrimMid: Color get() = if (dark) Color(0x73000000) else Color(0xB3FFFFFF)
    val overChip: Color get() = if (dark) Color(0x59000000) else Color(0x14000000)
    val overChipStroke: Color get() = if (dark) Color(0x33FFFFFF) else Color(0x1F000000)
    val rawColor: Color get() = if (dark) Color(0xFF8FB8FF) else Color(0xFF3D6FD1)
}

private val Bg: Color get() = Pal.bg
private val BgTop: Color get() = Pal.bgTop
private val CardBg: Color get() = Pal.cardBg
private val CardStroke: Color get() = Pal.stroke
private val Accent: Color get() = Pal.accent
private val TextMain: Color get() = Pal.textMain
private val TextDim: Color get() = Pal.textDim
private val TextFaint: Color get() = Pal.textFaint
private val Danger: Color get() = Pal.danger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 诊断入口：adb shell am start -n com.omphone.capture/.MainActivity --es selftest 1
        if (intent?.getStringExtra("selftest") != null) {
            val keep = intent.getStringExtra("keep") != null
            Thread {
                val report = if (intent.getStringExtra("nametest") != null) {
                    MediaSaver(this).nametest()
                } else {
                    MediaSaver(this).selftest(keep)
                }
                android.util.Log.i("OmPhone", "SELFTEST 结果: $report")
            }.start()
        }

        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect { Pal.dark = dark }
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(
                        primary = Accent,
                        onPrimary = Color.Black,
                        background = Bg,
                        surface = CardBg,
                        onSurface = TextMain,
                        surfaceTint = Color.Transparent
                    )
                } else {
                    lightColorScheme(
                        primary = Accent,
                        onPrimary = Pal.onAccent,
                        background = Bg,
                        surface = CardBg,
                        onSurface = TextMain,
                        surfaceTint = Color.Transparent
                    )
                }
            ) { HomeScreen() }
        }
    }
}

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val st by state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 相机地址不落盘：打开时自动发现，每次点连接前再发现一次（相机换热点/换网就变了）
    var ip by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var formats by remember { mutableStateOf(Prefs.enabled(ctx)) }
    var showFormats by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    // 选中的照片按「落盘文件名」记身份：同一个文件在 Images / Files 两个集合里的 uri 不同，
    // 用 uri 当身份会让选中态在列表刷新后丢失，也会把同一张照片算成两张
    var pickedName by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(Naming.rule(ctx)) }
    var showNaming by remember { mutableStateOf(false) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 横屏全屏看图：隐藏系统栏，让照片铺满整屏
    val activity = ctx as? ComponentActivity
    LaunchedEffect(landscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 画廊：本 App 写入 DCIM/OM Phone 的全部照片（含历史存量），新→旧
    var savedAll by remember { mutableStateOf<List<TransferUi>>(emptyList()) }
    LaunchedEffect(Unit) {
        savedAll = withContext(Dispatchers.IO) { queryAllSaved(ctx) }
    }

    // 有新照片落盘时刷新目录，并自动回到最新一张
    val latestUri = st.transfers.firstOrNull { it.state == "saved" && it.uri != null }?.uri
    LaunchedEffect(st.savedCount, latestUri) {
        savedAll = withContext(Dispatchers.IO) { queryAllSaved(ctx) }
        if (latestUri != null) pickedName = null
    }

    // 本次会话 + 目录存量，按「新→旧」合并。
    // 去重键必须用落盘文件名：同一文件在 MediaStore 里既能查到
    // content://media/external/files/<id>，也能查到 .../images/media/<id>，
    // 用 uri 判重会把最新收到的几张算两遍——总数虚高、翻到第 5 张又开始重复。
    val gallery = remember(st.transfers, savedAll) {
        val inDir = savedAll.mapTo(HashSet()) { it.name }
        val sessionOnly = st.transfers.filter {
            it.state == "saved" && it.uri != null && it.name !in inDir
        }
        // 目录里的记录优先（uri 跨会话稳定），只把还没进目录的补在最前
        (sessionOnly + savedAll).distinctBy { it.name }
    }
    val curIdx = gallery.indexOfFirst { it.name == pickedName }
    val previewItem = gallery.getOrNull(if (curIdx >= 0) curIdx else 0)
    val showIdx = if (curIdx >= 0) curIdx else 0
    // 编号按拍摄顺序：最早的一张是 1，最新的一张是 N（画廊本身是「新→旧」排列）
    val posLabel = if (gallery.isEmpty()) "" else "${gallery.size - showIdx} / ${gallery.size}"
    // 相邻页：拖动时跟着手指进入屏幕的那两张
    val prevItem = gallery.getOrNull(showIdx + 1)
    val nextItem = gallery.getOrNull(showIdx - 1)
    val hasPrev = showIdx + 1 < gallery.size   // 更旧的一张
    val hasNext = showIdx > 0                  // 更新的一张
    val onPrev: () -> Unit = {
        val i = if (curIdx >= 0) curIdx else 0
        if (i + 1 < gallery.size) pickedName = gallery[i + 1].name
    }
    val onNext: () -> Unit = {
        val i = if (curIdx >= 0) curIdx else 0
        if (i > 0) pickedName = gallery[i - 1].name
    }

    /** 发现相机：找到就写回输入框。返回 null 表示这一轮没找到。 */
    suspend fun discover(): String? {
        scanning = true
        val found = Discovery.find(ctx)
        scanning = false
        if (found != null) ip = found
        return found
    }

    // 打开即自动发现。找不到就静默留空，用户点「连接」时会再发现一次并给出可操作提示
    LaunchedEffect(Unit) {
        if (!st.running) discover()
    }

    val onScan: () -> Unit = { scope.launch { discover() } }
    val onToggle: () -> Unit = {
        if (st.running || st.connected) {
            CaptureService.disconnect(ctx)
        } else {
            scope.launch {
                // 每次连接前都重新发现：相机地址随热点/网络变化，用上次的地址连是「偶发连不上」的主因
                val found = discover()
                val target = found ?: ip.trim()
                if (target.isEmpty()) {
                    AppBus.edit {
                        it.copy(
                            error = "没有发现相机。确认相机已开机并进入「连接至 PC」模式，" +
                                "且手机连的是这台相机的 WiFi；也可以在上面手动填 IP 后重试"
                        )
                    }
                } else {
                    CaptureService.connect(ctx, target)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Pal.bgTop, Bg, Bg)))
    ) {
        if (landscape) {
            LandscapeScreen(
                st = st,
                item = previewItem,
                posLabel = posLabel,
                prevItem = prevItem,
                nextItem = nextItem,
                pool = gallery,
                hasPrev = hasPrev,
                hasNext = hasNext,
                formats = formats,
                naming = naming,
                onPrev = onPrev,
                onNext = onNext,
                onClear = { pickedName = null },
                onConnectClick = onToggle,
                onOpenConnect = { showConnect = true },
                onFormats = { showFormats = true },
                onNaming = { showNaming = true }
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp)
            ) {
                Header(st)
                if (st.error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ErrorBanner(st.error)
                }
                Spacer(Modifier.height(14.dp))
                ConnectionCard(
                    ip = ip, onIp = { ip = it }, st = st, scanning = scanning,
                    compact = false, onScan = onScan, onToggle = onToggle
                )
                Spacer(Modifier.height(12.dp))
                FormatCard(formats) { slot, kind ->
                    Prefs.toggle(ctx, slot, kind)
                    formats = Prefs.enabled(ctx)
                }
                Spacer(Modifier.height(12.dp))
                SavePathCard(naming) { showNaming = true }
                Spacer(Modifier.height(12.dp))
                TransferCard(st, Modifier.weight(1f), pickedName) { pickedName = it.name }
                Footer(st)
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (showNaming) {
        NamingDialog(
            rule = naming,
            onSave = {
                naming = it
                Naming.save(ctx, it)
                showNaming = false
            },
            onDismiss = { showNaming = false }
        )
    }

    if (showFormats) {
        FormatDialog(
            enabled = formats,
            onToggle = { slot, kind ->
                Prefs.toggle(ctx, slot, kind)
                formats = Prefs.enabled(ctx)
            },
            onDismiss = { showFormats = false }
        )
    }

    if (showConnect) {
        ConnectDialog(
            ip = ip,
            onIp = { ip = it },
            scanning = scanning,
            onScan = onScan,
            onConnect = {
                val h = ip.trim()
                if (h.isNotEmpty()) {
                    CaptureService.connect(ctx, h)
                    showConnect = false
                }
            },
            onDismiss = { showConnect = false }
        )
    }
}

private fun summary(formats: Set<String>): String {
    fun of(slot: Int) = FileKind.entries
        .filter { formats.contains(Prefs.key(slot, it)) }
        .joinToString("/") { if (it == FileKind.RAW) "RAW" else it.name }
        .ifEmpty { "无" }
    return "格式  ${of(1)} · ${of(2)}"
}

/** 状态栏用的短格式摘要：JPG/RAW·MOV */
private fun summaryShort(formats: Set<String>): String {
    fun of(slot: Int) = FileKind.entries
        .filter { formats.contains(Prefs.key(slot, it)) }
        .joinToString("/") { if (it == FileKind.RAW) "RAW" else it.name }
        .ifEmpty { "—" }
    return "${of(1)} · ${of(2)}"
}

// ---------------- 横屏：顶部信息条 + 全屏预览 ----------------

/**
 * 横屏布局：顶部一条常驻信息条把「连接状态 / 文件信息 / 收图统计 / 设置 / 打开 / 连接按钮」
 * 全部合在一起，下面整块留给预览图。信息条不再隐藏，入口始终可见。
 */
@Composable
private fun LandscapeScreen(
    st: UiState,
    item: TransferUi?,
    posLabel: String,
    prevItem: TransferUi?,
    nextItem: TransferUi?,
    pool: List<TransferUi>,
    hasPrev: Boolean,
    hasNext: Boolean,
    formats: Set<String>,
    naming: NamingRule,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClear: () -> Unit,
    onConnectClick: () -> Unit,
    onOpenConnect: () -> Unit,
    onFormats: () -> Unit,
    onNaming: () -> Unit
) {
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(Pal.previewBg)) {
        LandscapeTopBar(
            st = st,
            item = item,
            posLabel = posLabel,
            formats = formats,
            naming = naming,
            onConnectClick = onConnectClick,
            onOpen = { item?.let { openItem(ctx, it) } },
            onFormats = onFormats,
            onNaming = onNaming,
            onOpenConnect = onOpenConnect
        )

        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            PreviewPane(
                st = st,
                item = item,
                modifier = Modifier.fillMaxSize(),
                posLabel = posLabel,
                prevItem = prevItem,
                nextItem = nextItem,
                pool = pool,
                hasPrev = hasPrev,
                hasNext = hasNext,
                onPrev = onPrev,
                onNext = onNext,
                onClear = onClear,
                showInfo = false,
                showArrows = true
            )
        }
    }
}

/** 信息条用的短体积：55 MB / 1.2 GB。 */
private fun fmtSizeShort(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 横屏安全边距：挖孔在哪一侧都按两侧最大值对称留白，再让出屏幕圆弧边角。 */
@Composable
private fun landscapeSide(): Dp {
    val d = LocalDensity.current
    val safe = WindowInsets.safeDrawing
    val inset = with(d) {
        maxOf(
            safe.getLeft(this, LayoutDirection.Ltr),
            safe.getRight(this, LayoutDirection.Ltr)
        ).toDp()
    }
    return inset + 16.dp
}

/**
 * 横屏顶部唯一的一条信息栏，从左到右：连接状态（点它调出连接设置）→ 文件信息（类型徽章 ·
 * 文件名 · 大小徽章 · 序号）→ 收图统计 → 打开原图 → 设置 → 连接/断开。
 * 背景铺满屏幕顶边（挖孔与圆弧边角只影响内容），照片紧贴这条的下沿开始。
 */
@Composable
private fun LandscapeTopBar(
    st: UiState,
    item: TransferUi?,
    posLabel: String,
    formats: Set<String>,
    naming: NamingRule,
    onConnectClick: () -> Unit,
    onOpen: () -> Unit,
    onFormats: () -> Unit,
    onNaming: () -> Unit,
    onOpenConnect: () -> Unit
) {
    val d = LocalDensity.current
    val side = landscapeSide()
    val topInset = with(d) { WindowInsets.safeDrawing.getTop(this).toDp() }

    val active = st.running || st.connected
    val connected = st.connected
    val downloading = st.transfers.firstOrNull { it.state == "downloading" }
    val pct = if (downloading != null && downloading.size > 0) {
        (downloading.done * 100 / downloading.size).toInt().coerceIn(0, 100)
    } else 0
    val status = when {
        connected && st.device.isNotEmpty() -> st.device
        st.stage.isNotEmpty() -> st.stage
        else -> "未连接"
    }

    Column(Modifier.fillMaxWidth().background(Pal.barBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .padding(start = side, end = side, top = topInset + 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 连接状态：点它弹出「连接相机」设置
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenConnect() }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (connected) Color(0xFF34C46B) else Pal.textFaint)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status,
                    color = if (connected) TextMain else TextDim,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 132.dp)
                )
                Icon(
                    Icons.Rounded.ArrowDropDown, null,
                    tint = TextFaint, modifier = Modifier.size(15.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // 文件信息：类型徽章 · 文件名 · 大小徽章（紧跟名字）
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item == null) {
                    Text(
                        "等待相机推送…",
                        color = TextFaint, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Badge(
                        text = when (item.kind) {
                            FileKind.RAW -> "RAW"
                            FileKind.MOV -> "MOV"
                            else -> "JPG"
                        },
                        color = if (item.kind == FileKind.RAW) Pal.rawColor else Accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.name, color = TextMain, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        // fill=false：名字短的时候不撑开，大小徽章紧跟在名字后面
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Badge(text = fmtSize(item.size), color = TextDim)
                }
            }

            // 收图统计 / 下载进度；浏览历史照片时先显示「第几张 / 共几张」
            Spacer(Modifier.width(12.dp))
            if (posLabel.isNotEmpty() && downloading == null) {
                Text(posLabel, color = TextDim, fontSize = 10.sp, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (downloading != null) "下载 $pct%" else "已收 ${st.savedCount} · ${fmtSizeShort(st.savedBytes)}",
                color = if (downloading != null) Accent else TextFaint,
                fontSize = 10.sp, maxLines = 1
            )

            Spacer(Modifier.width(10.dp))
            if (item != null) {
                BarIconButton(Icons.Rounded.OpenInNew, Accent, "用其他应用打开", onOpen)
                Spacer(Modifier.width(8.dp))
            }
            InfoMenuButton(
                formats = formats,
                naming = naming,
                active = active,
                onFormats = onFormats,
                onNaming = onNaming,
                onDisconnect = onConnectClick
            )

            Spacer(Modifier.width(10.dp))
            ActionButton(
                text = if (active) "断开" else "连接",
                primary = !active,
                danger = active,
                modifier = Modifier.width(72.dp),
                h = 32,
                enabled = true,
                onClick = onConnectClick
            )
        }
        // 细分割线，照片紧贴这条线开始
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.stroke.copy(alpha = 0.55f)))
    }
}

// ---------------- 预览 ----------------

/** 预览图缓存：切回上一张时瞬间出图，也供相邻页预加载。 */
private object PreviewCache {
    private val limit = (Runtime.getRuntime().maxMemory() / 6).toInt()
        .coerceIn(24 * 1024 * 1024, 96 * 1024 * 1024)

    private val lru = object : android.util.LruCache<String, Bitmap>(limit) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String?): Bitmap? = key?.let { lru.get(it) }

    fun put(key: String?, value: Bitmap?) {
        if (key != null && value != null) lru.put(key, value)
    }
}

/** 按文件类型取一张可显示的位图（RAW 优先用同名 JPG 代理）。 */
/**
 * 同一张照片的 RAW / JPG 配对键。
 * 命名规则把「日期-序号」放在前面，末尾一段是相机原始文件名（如 `20261117-0010-PB170519`），
 * 所以取最后一段来配对：目录里读出来的记录拿不到相机原始名，只能这样还原。
 */
private fun shotKey(t: TransferUi): String {
    val base = t.orig.ifEmpty { t.name }.substringBeforeLast('.')
    val tail = base.substringAfterLast('-')
    return if (tail.length >= 4) tail else base
}

/** RAW 优先用同一次快门的 JPG 做预览（质量与速度都比内嵌缩略图好）。 */
private fun jpegProxyOf(item: TransferUi, pool: List<TransferUi>): TransferUi? {
    if (item.kind != FileKind.RAW) return null
    val key = shotKey(item)
    return pool.firstOrNull {
        it.kind == FileKind.JPG && it.uri != null && it.name != item.name && shotKey(it) == key
    }
}

/**
 * 估算屏幕上某块矩形区域的感知亮度（0 = 纯黑，1 = 纯白）。
 * 图片先按 ContentScale.Fit 居中摆放，再被缩放 / 平移 / 旋转（和 graphicsLayer 的顺序一致），
 * 所以采样点要按逆向变换映射回位图坐标；落在留白处的点按预览底色计。
 */
private fun lumaBehind(
    bmp: Bitmap?,
    boxW: Int,
    boxH: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    scale: Float,
    offset: Offset,
    rotationDeg: Float,
    bg: Color
): Float {
    fun lumOf(r: Int, g: Int, b: Int) = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    val bgLum = lumOf((bg.red * 255).toInt(), (bg.green * 255).toInt(), (bg.blue * 255).toInt())
    if (bmp == null || boxW <= 0 || boxH <= 0) return bgLum

    val bmpAspect = bmp.width.toFloat() / bmp.height
    val boxAspect = boxW.toFloat() / boxH
    val dw: Float
    val dh: Float
    if (bmpAspect > boxAspect) {
        dw = boxW.toFloat(); dh = dw / bmpAspect
    } else {
        dh = boxH.toFloat(); dw = dh * bmpAspect
    }
    val dx = (boxW - dw) / 2f
    val dy = (boxH - dh) / 2f

    val cx = boxW / 2f
    val cy = boxH / 2f
    val rad = Math.toRadians(rotationDeg.toDouble())
    val cosR = kotlin.math.cos(rad).toFloat()
    val sinR = kotlin.math.sin(rad).toFloat()
    val s = if (scale <= 0.01f) 1f else scale

    val cols = 9
    val rows = 4
    var sum = 0f
    for (r in 0 until rows) for (c in 0 until cols) {
        val x = left + (right - left) * (c + 0.5f) / cols
        val y = top + (bottom - top) * (r + 0.5f) / rows
        // 逆变换：屏幕点 -> 未变换的 Fit 矩形坐标
        val vx = x - cx
        val vy = y - cy
        val ux = vx * cosR + vy * sinR
        val uy = -vx * sinR + vy * cosR
        val fx = cx + (ux - offset.x) / s
        val fy = cy + (uy - offset.y) / s
        val px = ((fx - dx) / dw * bmp.width).toInt()
        val py = ((fy - dy) / dh * bmp.height).toInt()
        sum += if (px in 0 until bmp.width && py in 0 until bmp.height) {
            val p = bmp.getPixel(px, py)
            lumOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        } else bgLum
    }
    return sum / (cols * rows)
}

private fun loadPreviewBitmap(
    ctx: android.content.Context,
    item: TransferUi,
    pool: List<TransferUi>
): Bitmap? {
    val selfUri = item.uri ?: return null
    val proxy = jpegProxyOf(item, pool)
    return runCatching {
        val uri = Uri.parse(proxy?.uri ?: selfUri)
        when {
            proxy != null -> decodeImage(ctx, uri)
            item.kind == FileKind.MOV -> videoFrame(ctx, uri)
            item.kind == FileKind.RAW -> orfPreview(ctx, uri)
            else -> decodeImage(ctx, uri)
        }
    }.getOrNull()
}

@Composable
private fun PreviewPane(
    st: UiState,
    item: TransferUi?,
    modifier: Modifier = Modifier,
    posLabel: String = "",
    prevItem: TransferUi? = null,
    nextItem: TransferUi? = null,
    pool: List<TransferUi> = st.transfers,
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    showInfo: Boolean = true,
    showArrows: Boolean = true,
    onClear: () -> Unit
) {
    val ctx = LocalContext.current
    val shown = item

    // ORF 无法直接解码：优先用同一次快门的 JPG 做代理预览
    val proxy = remember(shown, pool) { shown?.let { jpegProxyOf(it, pool) } }
    val sourceUri = proxy?.uri ?: shown?.uri

    var bmp by remember(shown?.name) { mutableStateOf(PreviewCache.get(sourceUri)) }
    var loading by remember(shown?.name) { mutableStateOf(false) }
    // 同一张照片的 uri 会从「会话里的 Images uri」换成「目录里的 Files uri」，
    // 换 uri 重解码时不要清空旧位图，否则刚收到一张照片会白闪一下
    var loadedName by remember { mutableStateOf("") }

    LaunchedEffect(sourceUri, shown?.name) {
        val cur = shown
        if (cur == null || sourceUri == null) {
            bmp = null
            loadedName = ""
            return@LaunchedEffect
        }
        val cached = PreviewCache.get(sourceUri)
        if (cached != null) {
            bmp = cached
            loading = false
            loadedName = cur.name
            return@LaunchedEffect
        }
        if (loadedName != cur.name) {
            bmp = null
            loading = true
        }
        val loaded = withContext(Dispatchers.IO) { loadPreviewBitmap(ctx, cur, pool) }
        PreviewCache.put(sourceUri, loaded)
        bmp = loaded
        loadedName = cur.name
        loading = false
    }

    // 相邻两张提前解码进缓存：切换瞬间就有图，但界面上不并排预览。
    // 缓存 key 要和主流程一致（RAW 用代理 JPG 的 uri），否则切过去还要再解一次
    LaunchedEffect(prevItem?.name) {
        val p = prevItem ?: return@LaunchedEffect
        val key = jpegProxyOf(p, pool)?.uri ?: p.uri
        if (PreviewCache.get(key) == null) {
            PreviewCache.put(key, withContext(Dispatchers.IO) { loadPreviewBitmap(ctx, p, pool) })
        }
    }
    LaunchedEffect(nextItem?.name) {
        val n = nextItem ?: return@LaunchedEffect
        val key = jpegProxyOf(n, pool)?.uri ?: n.uri
        if (PreviewCache.get(key) == null) {
            PreviewCache.put(key, withContext(Dispatchers.IO) { loadPreviewBitmap(ctx, n, pool) })
        }
    }

    val item2 = shown
    val aspect = remember(bmp) { bmp?.let { it.width.toFloat() / it.height.toFloat() } ?: 1f }
    // 每张图独立的手势状态；换图时自动回到 1x。用文件名做 key：
    // 同一张照片在会话列表与目录列表里的 uri 不一样，用 uri 会把状态来回重置
    val zoom = rememberZoomState(item2?.name)
    val scope = rememberCoroutineScope()

    // 滑到两端时在底部弹一条小提示
    var edgeHint by remember { mutableStateOf("") }
    var hintSeq by remember { mutableStateOf(0) }
    LaunchedEffect(hintSeq) {
        if (edgeHint.isNotEmpty()) {
            delay(1500)
            edgeHint = ""
        }
    }

    BoxWithConstraints(
        modifier.fillMaxSize().background(Pal.previewBg),
        contentAlignment = Alignment.Center
    ) {
        // 复位胶囊浮在照片上，照片可能是白雪也可能是夜景，固定用黑字或白字都会翻车。
        // 胶囊出现的那一刻量一下身后的平均亮度，再决定用浅底黑字还是深底白字；
        // 只在「开始有变换」时量一次，缩放/平移过程中不重复计算，避免每帧读像素。
        val density = LocalDensity.current
        var capsuleOnLight by remember(item2?.name) { mutableStateOf(false) }
        LaunchedEffect(item2?.name, zoom.transformed, bmp, constraints) {
            if (!zoom.transformed || bmp == null) {
                capsuleOnLight = false
                return@LaunchedEffect
            }
            val bandW = with(density) { 200.dp.toPx() }
            val bandH = with(density) { 56.dp.toPx() }
            val bottomPad = with(density) { 16.dp.toPx() }
            val cx = constraints.maxWidth / 2f
            val bottom = constraints.maxHeight - bottomPad
            capsuleOnLight = lumaBehind(
                bmp = bmp,
                boxW = constraints.maxWidth,
                boxH = constraints.maxHeight,
                left = cx - bandW / 2f,
                top = bottom - bandH,
                right = cx + bandW / 2f,
                bottom = bottom,
                scale = zoom.scale,
                offset = zoom.offset,
                rotationDeg = zoom.rotation,
                bg = Pal.previewBg
            ) > 0.58f
        }

        when {
            item2 == null -> EmptyPreview()
            loading -> CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            bmp != null -> Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = item2.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .photoGestures(
                        state = zoom,
                        aspect = aspect,
                        scope = scope,
                        onSwipeLeft = onNext,
                        onSwipeRight = onPrev,
                        canGoLeft = hasNext,
                        canGoRight = hasPrev,
                        gestureKey = item2.name,
                        onEdgeLeft = { edgeHint = "已经是最后一张了"; hintSeq++ },
                        onEdgeRight = { edgeHint = "已经是第一张了"; hintSeq++ }
                    )
                    .pointerInput(item2.name, aspect) {
                        detectTapGestures(
                            onDoubleTap = { p ->
                                scope.launch {
                                    if (zoom.transformed) {
                                        zoom.animateTo(1f, Offset.Zero, 0f)
                                    } else {
                                        zoom.animateTo(
                                            DOUBLE_TAP_SCALE,
                                            doubleTapOffset(p, zoom, size, aspect)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        rotationZ = zoom.rotation
                        translationX = zoom.offset.x + zoom.drag
                        translationY = zoom.offset.y
                    }
            )
            else -> UnsupportedPreview(item2, proxy != null)
        }

        // 左右切换（放大状态下隐藏，避免误触）
        // 排列是「左旧右新」：左箭头看更旧的一张，右箭头看更新的一张
        if (showArrows && !zoom.transformed && item2 != null) {
            if (hasPrev) EdgeArrow(Modifier.align(Alignment.CenterStart), true) { onPrev() }
            if (hasNext) EdgeArrow(Modifier.align(Alignment.CenterEnd), false) { onNext() }
        }

        // 滑到两端的小提示
        androidx.compose.animation.AnimatedVisibility(
            visible = edgeHint.isNotEmpty(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
        ) {
            Text(
                edgeHint,
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    // 照片明暗不定，这里固定用深色半透明底，保证看得清
                    .background(Color(0xB3000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 有任何变换（放大 / 旋转 / 平移）就浮出「复位」胶囊，纯看图时不打扰。
        // 配色看身后亮度：浅底黑字 / 深底白字二选一，任何照片上都读得清
        if (item2 != null && zoom.transformed) {
            val chipBg = if (capsuleOnLight) Color(0xF2FFFFFF) else Color(0xC4000000)
            val chipStroke = if (capsuleOnLight) Color(0x26000000) else Color(0x3DFFFFFF)
            val chipText = if (capsuleOnLight) Color(0xFF15161A) else Color(0xFFF5F5F7)
            val chipAction = if (capsuleOnLight) Color(0xFF8A5A00) else Color(0xFFFFC53D)
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(chipBg)
                    .border(1.dp, chipStroke, RoundedCornerShape(50))
                    .clickable {
                        scope.launch { zoom.animateTo(1f, Offset.Zero, 0f) }
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildString {
                        append("%.1fx".format(zoom.scale))
                        val d = zoom.tilt
                        if (abs(d) > 1f) append(" · %d°".format(d.toInt()))
                    },
                    color = chipText, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(10.dp))
                Text("复位", color = chipAction, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 顶部信息浮层（渐变遮罩，不占图片空间）
        if (showInfo) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Pal.overScrimTop, Pal.overScrimMid, Color.Transparent)
                        )
                    )
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item2 != null) {
                    Badge(
                        text = when (item2.kind) {
                            FileKind.RAW -> "RAW"
                            FileKind.MOV -> "MOV"
                            else -> "JPG"
                        },
                        color = if (item2.kind == FileKind.RAW) Pal.rawColor else Accent
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item2?.name ?: "等待照片",
                        color = Pal.overText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (item2 != null) {
                        Text(
                            buildString {
                                append(fmtSize(item2.size))
                                if (posLabel.isNotEmpty()) append(" · $posLabel")
                                append(" · ")
                                append(
                                    when {
                                        proxy != null -> "ORF 用同名 JPG 预览"
                                        item2.kind == FileKind.RAW && bmp != null -> "ORF 内嵌预览"
                                        item2.kind == FileKind.RAW -> "点右侧用其他应用打开"
                                        item2.kind == FileKind.MOV -> "点右侧播放"
                                        else -> "双指缩放 · 左右滑动切换"
                                    }
                                )
                            },
                            color = Pal.overSub, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (item2 != null) {
                    iconAction(Icons.Rounded.OpenInNew) { openItem(ctx, item2) }
                }
            }
        }
    }
}

/** 预览左右的切图箭头（半透明浮在图片上）。 */
@Composable
private fun EdgeArrow(modifier: Modifier, back: Boolean, onClick: () -> Unit) {
    Box(
        modifier
            .padding(horizontal = 10.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x4D000000))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (back) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
            null, tint = Color(0xE6FFFFFF), modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 查询 DCIM/OM Phone 目录下的全部媒体（新→旧），供左右滑动的画廊使用。
 * 只查本 App 自己写入的目录，Android 10+ 无需存储权限。
 */
private fun queryAllSaved(ctx: android.content.Context): List<TransferUi> {
    val dir = MediaSaver.DIR + "/"
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE
    )
    val base = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun q(selection: String, args: Array<String>): List<TransferUi> = runCatching {
        ctx.contentResolver.query(
            base, projection, selection, args, "date_added DESC, _id DESC"
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2)
                    val mime = c.getString(3).orEmpty()
                    val kind = when {
                        mime.startsWith("video") -> FileKind.MOV
                        name.uppercase().endsWith(".ORF") -> FileKind.RAW
                        else -> FileKind.JPG
                    }
                    add(
                        TransferUi(
                            name = name, kind = kind, size = size, done = size,
                            state = "saved",
                            uri = ContentUris.withAppendedId(base, id).toString(),
                            orig = name
                        )
                    )
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    // 个别 ROM 的相对路径写法不一致（有无结尾斜杠），精确匹配不到时退回前缀匹配
    val exact = q("relative_path=?", arrayOf(dir))
    return exact.ifEmpty { q("relative_path LIKE ?", arrayOf("${MediaSaver.DIR}%")) }
}

@Composable
private fun EmptyPreview() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(Pal.chipBg2),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PhotoCamera, null, tint = Pal.textFaint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("等待照片", color = Pal.textFaint, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text("相机推送后自动显示最新一张", color = Pal.textFaint, fontSize = 10.sp)
    }
}

@Composable
private fun UnsupportedPreview(item: TransferUi, hasProxy: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (item.kind == FileKind.RAW) "ORF 原片" else "MOV 视频",
            color = Pal.textDim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasProxy) "正在等待同名 JPG 预览" else "系统不支持直接解码，点右下角用其他应用打开",
            color = Pal.textFaint, fontSize = 11.sp
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun iconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Pal.chipBg)
            .border(1.dp, CardStroke, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(16.dp))
    }
}

private fun openItem(ctx: android.content.Context, item: TransferUi) {
    val uri = item.uri ?: return
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), item.kind.mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}

private fun decodeImage(ctx: android.content.Context, uri: Uri): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            if (w > 1600) {
                val h = (info.size.height * (1600f / w)).toInt()
                decoder.setTargetSize(1600, h)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        // API 26/27 的 BitmapFactory 不会自动应用 EXIF 方向，这里手动纠正
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val raw = BitmapFactory.decodeStream(input)
            if (raw == null) null else applyOrientation(raw, exifOrientation(ctx, uri))
        }
    }

/** 从 MediaStore Uri 读 EXIF 方向（失败按正常方向处理）。 */
private fun exifOrientation(ctx: android.content.Context, uri: Uri): Int = runCatching {
    ctx.contentResolver.openInputStream(uri)?.use {
        android.media.ExifInterface(it).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    } ?: 1
}.getOrDefault(1)

/** 按 EXIF orientation 把像素转正（1 为正常，直接返回原图）。 */
private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        2 -> m.setScale(-1f, 1f)
        3 -> m.setRotate(180f)
        4 -> m.setScale(1f, -1f)
        5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
        6 -> m.setRotate(90f)
        7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
        8 -> m.setRotate(-90f)
        else -> return src
    }
    return runCatching {
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        out
    }.getOrDefault(src)
}

private fun videoFrame(ctx: android.content.Context, uri: Uri): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(ctx, uri)
        mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { mmr.release() }
    }
}

/**
 * ORF 是 TIFF 容器，内部嵌了整幅 JPEG 预览。
 * 这里在字节流中找出最大的 JPEG（FFD8FF…FFD9）段解码，失败则返回 null。
 */
private fun orfPreview(ctx: android.content.Context, uri: Uri): Bitmap? {
    // 内嵌 JPEG 一般就在文件前几百 KB，只读头部即可；
    // 整份读进来是 17MB+，每切一张都要分配这么大一块，滑动时会明显顿。
    var bytes = readPrefix(ctx, uri, 4 * 1024 * 1024) ?: return null
    var found = findLargestJpeg(bytes)
    if (found == null) {
        bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        found = findLargestJpeg(bytes)
    }
    if (found == null || found.second < 4096) return null
    val (bestStart, bestLen) = found

    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val raw = BitmapFactory.decodeByteArray(bytes, bestStart, bestLen, opts) ?: return null
    // ORF 是 TIFF 容器：方向标记在 IFD0 的 0x0112 里（相机竖拍时为 8），
    // 内嵌 JPEG 的 EXIF 只作兜底；不处理的话 RAW 会一直侧躺。
    val orientation = tiffOrientation(bytes).let {
        if (it != 1) it else jpegExifOrientation(bytes, bestStart, bestLen)
    }
    return applyOrientation(raw, orientation)
}

/** 只读文件开头 [max] 字节。 */
private fun readPrefix(ctx: android.content.Context, uri: Uri, max: Int): ByteArray? =
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val n = input.read(buf, off, max - off)
            if (n <= 0) break
            off += n
        }
        if (off == max) buf else buf.copyOf(off)
    }

/** 在字节流里找出最大的 JPEG 段（FFD8FF…FFD9），返回 (起点, 长度)。 */
private fun findLargestJpeg(bytes: ByteArray): Pair<Int, Int>? {
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while (i + 3 < bytes.size) {
        if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
            var j = i + 2
            while (j + 1 < bytes.size) {
                if (bytes[j] == 0xFF.toByte() && bytes[j + 1] == 0xD9.toByte()) break
                j++
            }
            if (j + 1 < bytes.size) {
                val len = j + 2 - i
                if (len > bestLen) {
                    bestStart = i
                    bestLen = len
                }
                i = j + 2
                continue
            }
        }
        i++
    }
    return if (bestStart >= 0) bestStart to bestLen else null
}

/** 读 TIFF/ORF 头 IFD0 的方向标记（tag 0x0112），读不到返回 1。 */
private fun tiffOrientation(b: ByteArray): Int {
    if (b.size < 16) return 1
    val le = when {
        b[0] == 0x49.toByte() && b[1] == 0x49.toByte() -> true    // "II"
        b[0] == 0x4D.toByte() && b[1] == 0x4D.toByte() -> false   // "MM"
        else -> return 1
    }
    fun u16(o: Int): Int =
        if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        else (b[o + 1].toInt() and 0xFF) or ((b[o].toInt() and 0xFF) shl 8)

    fun u32(o: Int): Int =
        if (le) u16(o) or (u16(o + 2) shl 16) else u16(o + 2) or (u16(o) shl 16)

    return runCatching {
        val ifd = u32(4)
        if (ifd <= 0 || ifd + 2 > b.size) return 1
        val n = u16(ifd)
        for (k in 0 until n.coerceAtMost(512)) {
            val p = ifd + 2 + k * 12
            if (p + 12 > b.size) break
            if (u16(p) == 0x0112) {
                val v = u16(p + 8)
                if (v in 1..8) return v
            }
        }
        1
    }.getOrDefault(1)
}

/** 内嵌 JPEG 自带 EXIF 时的方向值（ORF 通常没有，作为兜底）。 */
private fun jpegExifOrientation(b: ByteArray, start: Int, len: Int): Int = runCatching {
    if (len < 4) return 1
    android.media.ExifInterface(
        java.io.ByteArrayInputStream(b, start, len)
    ).getAttributeInt(
        android.media.ExifInterface.TAG_ORIENTATION,
        android.media.ExifInterface.ORIENTATION_NORMAL
    )
}.getOrDefault(1)

// ---------------- 顶部与连接 ----------------

@Composable
private fun Header(st: UiState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "OM PHONE", color = TextMain, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp
            )
            Text("无线自动收图", color = TextDim, fontSize = 11.sp, letterSpacing = 1.sp)
        }
        StatusPill(st.connected, st.stage)
    }
}

/**
 * 失败原因横幅。
 *
 * 状态胶囊只有一行、宽度还受标题挤压，长消息会被省略号吃掉（"未配置客户端 GUID：在 l…"），
 * 而这类消息恰好整句都是"该怎么修"。所以把完整原因单独放到下面这条可换行的横幅里，
 * 超过三行再点开看全文。
 */
@Composable
private fun ErrorBanner(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowed by remember(text) { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (Pal.dark) Color(0x1FFF7A7A) else Color(0x12D24545))
            .border(
                1.dp,
                if (Pal.dark) Color(0x4DFF7A7A) else Color(0x33D24545),
                RoundedCornerShape(12.dp)
            )
            .clickable { expanded = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Rounded.WarningAmber, contentDescription = null,
            tint = Danger, modifier = Modifier.size(15.dp).padding(top = 1.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, color = Danger, fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflowed = it.hasVisualOverflow }
            )
            if (overflowed) {
                Spacer(Modifier.height(4.dp))
                Text("点击查看完整信息", color = TextDim, fontSize = 10.sp)
            }
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            containerColor = CardBg,
            tonalElevation = 0.dp,
            title = {
                Text("失败原因", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(text, color = TextMain, fontSize = 13.sp, lineHeight = 20.sp)
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) {
                    Text("知道了", color = Accent, fontSize = 13.sp)
                }
            }
        )
    }
}

@Composable
private fun StatusPill(connected: Boolean, stage: String) {
    val dot = if (connected) Color(0xFF34C46B) else Pal.textFaint
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (connected) Pal.onlineBg else Pal.chipBg)
            .border(1.dp, if (connected) Pal.onlineStroke else CardStroke, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(
            stage,
            color = if (connected) Pal.onlineFg else TextDim,
            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    pad: Int = 16,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(1.dp, CardStroke, RoundedCornerShape(18.dp))
            .padding(pad.dp)
    ) {
        if (title != null) {
            Text(title, color = TextDim, fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

/** 横屏底部细状态栏：连接状态 + 格式 + 计数 + 连接/断开。 */
/** 信息条上的「设置」按钮：导出格式 / 文件命名 / 相机连接 都收在这里。 */
@Composable
private fun InfoMenuButton(
    formats: Set<String>,
    naming: NamingRule,
    active: Boolean,
    onFormats: () -> Unit,
    onNaming: () -> Unit,
    onDisconnect: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        BarIconButton(Icons.Rounded.Tune, Accent, "设置") { open = true }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(CardBg)
        ) {
            MenuRow("导出格式", summaryShort(formats)) { open = false; onFormats() }
            MenuRow("文件命名", naming.summary()) { open = false; onNaming() }
            if (active) {
                MenuRow("断开连接", "清空当前相机信息") { open = false; onDisconnect() }
            }
        }
    }
}

/** 信息条 / 控制条通用的方形图标按钮。 */
@Composable
private fun BarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    desc: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Pal.chipBg.copy(alpha = 0.75f))
            .border(1.dp, Pal.stroke.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, color = TextMain, fontSize = 13.sp)
                if (subtitle != null) {
                    Text(
                        subtitle, color = TextFaint, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        onClick = onClick
    )
}

/** 连接对话框：IP 输入 + 自动发现（横屏时通过状态栏图标打开）。 */
@Composable
private fun ConnectDialog(
    ip: String,
    onIp: (String) -> Unit,
    scanning: Boolean,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        tonalElevation = 0.dp,
        title = {
            Column {
                Text("连接相机", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("相机连手机热点后，可自动发现或手动填 IP", color = TextDim, fontSize = 11.sp)
            }
        },
        text = {
            Column {
                IpField(ip, onIp, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        text = if (scanning) "搜索中…" else "发现相机",
                        primary = false,
                        icon = { Icon(Icons.Rounded.Search, null, tint = TextMain, modifier = Modifier.size(15.dp)) },
                        modifier = Modifier.weight(1f),
                        enabled = !scanning,
                        onClick = onScan
                    )
                    ActionButton(
                        text = "连接",
                        primary = true,
                        modifier = Modifier.weight(1f),
                        enabled = ip.isNotBlank(),
                        onClick = onConnect
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = TextDim) }
        }
    )
}

@Composable
private fun ConnectionCard(
    ip: String,
    onIp: (String) -> Unit,
    st: UiState,
    scanning: Boolean,
    compact: Boolean,
    onScan: () -> Unit,
    onToggle: () -> Unit
) {
    val active = st.running || st.connected
    SectionCard("相机连接") {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IpField(ip, onIp, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(38.dp).height(44.dp)) {
                    IconButtonSquare(Icons.Rounded.Search, enabled = !scanning && !active) { onScan() }
                }
                Spacer(Modifier.width(8.dp))
                ActionButton(
                    text = if (active) "断开" else "连接",
                    primary = !active,
                    danger = active,
                    icon = null,
                    modifier = Modifier.width(84.dp),
                    enabled = active || ip.isNotBlank(),
                    onClick = onToggle
                )
            }
        } else {
            IpField(ip, onIp, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    text = if (scanning) "搜索中…" else "发现相机",
                    primary = false,
                    icon = { Icon(Icons.Rounded.Search, null, tint = TextMain, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                    enabled = !scanning && !active,
                    onClick = onScan
                )
                ActionButton(
                    text = if (active) "断开" else "连接",
                    primary = !active,
                    danger = active,
                    icon = null,
                    modifier = Modifier.weight(1f),
                    enabled = active || ip.isNotBlank(),
                    onClick = onToggle
                )
            }
        }
        if (st.device.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(st.device, color = TextDim, fontSize = 12.sp)
        }
        if (st.logLine.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                st.logLine, color = Pal.textFaint, fontSize = 11.sp,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun IpField(ip: String, onIp: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = ip,
        onValueChange = onIp,
        singleLine = true,
        placeholder = { Text("相机 IP，如 192.168.43.10", color = Pal.textFaint, fontSize = 13.sp) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextMain, letterSpacing = 1.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = CardStroke,
            cursorColor = Accent,
            focusedContainerColor = Pal.inputBg,
            unfocusedContainerColor = Pal.inputBg
        ),
        modifier = modifier
    )
}

@Composable
private fun IconButtonSquare(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Pal.chipBg)
            .border(1.dp, CardStroke, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (enabled) TextMain else Pal.textFaint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    h: Int = 44,
    onClick: () -> Unit
) {
    val bg = when {
        danger && enabled -> Pal.danger.copy(alpha = 0.12f)
        primary && enabled -> Accent
        primary -> Pal.chipBg
        else -> Pal.chipBg
    }
    val fg = when {
        danger && enabled -> Danger
        primary && enabled -> Pal.onAccent
        primary -> Pal.textDim
        enabled -> TextMain
        else -> Pal.textFaint
    }
    Row(
        modifier
            .height(h.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(
                if (!primary || danger) Modifier.border(
                    1.dp, if (danger) Pal.danger.copy(alpha = 0.5f) else CardStroke, RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable(enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MiniButton(
    text: String,
    icon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Pal.chipBg)
            .border(1.dp, CardStroke, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text, color = TextMain, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------- 格式 ----------------

@Composable
private fun FormatCard(enabled: Set<String>, onToggle: (Int, FileKind) -> Unit) {
    SectionCard("选择导出的文件类型") {
        SlotRow(1, "卡槽1", enabled, onToggle)
        Spacer(Modifier.height(10.dp))
        SlotRow(2, "卡槽2", enabled, onToggle)
    }
}

@Composable
private fun FormatDialog(
    enabled: Set<String>,
    onToggle: (Int, FileKind) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        tonalElevation = 0.dp,
        title = {
            Column {
                Text("导出文件类型", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("按卡槽选择要自动接收的格式", color = TextDim, fontSize = 11.sp)
            }
        },
        text = {
            Column {
                SlotRow(1, "卡槽1", enabled, onToggle)
                Spacer(Modifier.height(12.dp))
                SlotRow(2, "卡槽2", enabled, onToggle)
                Spacer(Modifier.height(10.dp))
                Text(
                    "竖屏时可在主界面直接勾选；横屏从底栏「格式」进入",
                    color = Pal.textFaint, fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成", color = Accent) }
        }
    )
}

@Composable
private fun SlotRow(
    slot: Int,
    label: String,
    enabled: Set<String>,
    onToggle: (Int, FileKind) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, fontSize = 13.sp, modifier = Modifier.widthIn(min = 54.dp))
        Spacer(Modifier.width(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            FileKind.entries.forEach { kind ->
                FormatChip(
                    text = if (kind == FileKind.RAW) "RAW" else kind.name,
                    selected = enabled.contains(Prefs.key(slot, kind)),
                    modifier = Modifier.weight(1f)
                ) { onToggle(slot, kind) }
            }
        }
    }
}

@Composable
private fun FormatChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else Pal.chipBg2)
            .border(1.dp, if (selected) Accent else CardStroke, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Pal.onAccent else Pal.textDim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun SavePathCard(rule: NamingRule, onNaming: () -> Unit) {
    SectionCard("保存位置") {
        // 目录固定，整块不可点；只有下面「文件名规则」是可编辑的二级菜单
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Pal.chipBg2),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.FolderOpen, null, tint = TextDim, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(MediaSaver.DIR, color = TextMain, fontSize = 14.sp, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(2.dp))
                Text("固定目录 · 不可修改", color = TextFaint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(CardStroke))
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onNaming() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("文件名规则", color = TextMain, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    rule.summary(), color = TextDim, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text("自定义", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Icon(
                Icons.Rounded.ChevronRight, null, tint = Accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "示例  ${Naming.sample(rule)}",
            color = Pal.textFaint, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------- 文件命名规则 ----------------

@Composable
private fun NamingDialog(
    rule: NamingRule,
    onSave: (NamingRule) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(rule) { mutableStateOf(rule) }
    val seqShownInBase = draft.base == NamePart.SEQ

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        tonalElevation = 0.dp,
        title = {
            Column {
                Text("文件命名规则", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("前缀 - 基本 - 后缀，留空的一段自动省略", color = TextDim, fontSize = 11.sp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 实时示例
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.inputBg)
                        .border(1.dp, CardStroke, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text("示例", color = TextFaint, fontSize = 10.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        Naming.sample(draft), color = Accent, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(14.dp))

                PartSelector("前缀", draft.prefix) { draft = draft.copy(prefix = it) }
                if (draft.prefix == NamePart.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    TinyField(
                        value = draft.prefixText,
                        placeholder = "前缀文字",
                        onValue = { draft = draft.copy(prefixText = it) }
                    )
                }
                Spacer(Modifier.height(12.dp))

                PartSelector("基本", draft.base) { draft = draft.copy(base = it) }
                if (draft.base == NamePart.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    TinyField(
                        value = draft.baseText,
                        placeholder = "中间文字",
                        onValue = { draft = draft.copy(baseText = it) }
                    )
                }
                if (seqShownInBase) {
                    Spacer(Modifier.height(8.dp))
                    SeqFields(draft) { draft = it }
                }
                Spacer(Modifier.height(12.dp))

                PartSelector("后缀", draft.suffix) { draft = draft.copy(suffix = it) }
                if (draft.suffix == NamePart.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    TinyField(
                        value = draft.suffixText,
                        placeholder = "后缀文字",
                        onValue = { draft = draft.copy(suffixText = it) }
                    )
                }

                if (draft.usesSeq && !seqShownInBase) {
                    Spacer(Modifier.height(12.dp))
                    SeqFields(draft) { draft = it }
                }

                val needsDate = listOf(draft.prefix, draft.base, draft.suffix)
                    .contains(NamePart.DATETIME)
                if (needsDate) {
                    Spacer(Modifier.height(12.dp))
                    LabeledRow("日期格式") {
                        Selector(
                            text = draft.datePreset,
                            options = DATE_PRESETS.map { it.first },
                            onPick = { draft = draft.copy(datePreset = it) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "连续号码会按收图顺序自动累加",
                        color = Pal.textFaint, fontSize = 10.sp, modifier = Modifier.weight(1f)
                    )
                    Text(
                        "恢复默认",
                        color = TextDim, fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { draft = NamingRule() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text("保存", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextDim) }
        }
    )
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun PartSelector(label: String, part: NamePart, onPick: (NamePart) -> Unit) {
    LabeledRow(label) {
        Selector(
            text = part.label,
            options = NamePart.entries.map { it.label },
            onPick = { name ->
                NamePart.entries.firstOrNull { it.label == name }?.let(onPick)
            }
        )
    }
}

/** 通用下拉选择器（Material3 DropdownMenu，跟随主题）。 */
@Composable
private fun Selector(text: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Pal.inputBg)
                .border(1.dp, CardStroke, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text, color = TextMain, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Rounded.ArrowDropDown, null, tint = TextDim,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(CardBg)
        ) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Text(
                            o,
                            color = if (o == text) Accent else TextMain,
                            fontSize = 13.sp,
                            fontWeight = if (o == text) FontWeight.Medium else FontWeight.Normal
                        )
                    },
                    onClick = {
                        open = false
                        onPick(o)
                    }
                )
            }
        }
    }
}

/** 连续号码的两个参数：开始数字、位数。 */
@Composable
private fun SeqFields(rule: NamingRule, onChange: (NamingRule) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("开始数字", color = TextDim, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(8.dp))
        TinyField(
            value = rule.start.toString(),
            placeholder = "1",
            numeric = true,
            modifier = Modifier.weight(1f),
            onValue = { s ->
                val n = s.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0
                onChange(rule.copy(start = n))
            }
        )
        Spacer(Modifier.width(12.dp))
        Text("位数", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        TinyField(
            value = rule.digits.toString(),
            placeholder = "4",
            numeric = true,
            modifier = Modifier.width(74.dp),
            onValue = { s ->
                val n = s.filter { it.isDigit() }.take(1).toIntOrNull() ?: 1
                onChange(rule.copy(digits = n.coerceIn(1, 8)))
            }
        )
    }
}

@Composable
private fun TinyField(
    value: String,
    placeholder: String,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
    onValue: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        placeholder = { Text(placeholder, color = Pal.textFaint, fontSize = 13.sp) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextMain, fontSize = 13.sp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = CardStroke,
            cursorColor = Accent,
            focusedContainerColor = Pal.inputBg,
            unfocusedContainerColor = Pal.inputBg
        ),
        modifier = modifier.fillMaxWidth()
    )
}

// ---------------- 传输列表 ----------------

@Composable
private fun TransferCard(
    st: UiState,
    modifier: Modifier = Modifier,
    pickedName: String?,
    dense: Boolean = false,
    onPick: (TransferUi) -> Unit
) {
    SectionCard(if (dense) null else "传输", modifier, pad = if (dense) 10 else 16) {
        if (st.transfers.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(if (dense) 56.dp else 80.dp), contentAlignment = Alignment.Center) {
                Text("等待相机推送…", color = Pal.textFaint, fontSize = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 10.dp)) {
                items(st.transfers, key = { it.name }) { t ->
                    TransferRow(t, selected = pickedName != null && t.name == pickedName) { onPick(t) }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: TransferUi, selected: Boolean, onClick: () -> Unit) {
    val pct = if (t.size > 0) (t.done.toFloat() / t.size).coerceIn(0f, 1f) else 0f
    val color = when (t.state) {
        "saved" -> Color(0xFF34C46B)
        "failed" -> Pal.danger
        else -> Accent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier
                    .background(Pal.chipBg)
                    .border(1.dp, Pal.stroke, RoundedCornerShape(12.dp))
                    .padding(6.dp)
                else Modifier
            )
            .clickable(enabled = t.uri != null) { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Pal.chipBg2),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when (t.kind) {
                    FileKind.JPG -> "JPG"
                    FileKind.RAW -> "RAW"
                    FileKind.MOV -> "MOV"
                },
                color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name, color = TextMain, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (t.state) {
                        "saved" -> fmtSize(t.size)
                        "failed" -> "失败"
                        else -> "%d%%".format((pct * 100).toInt())
                    },
                    color = if (t.state == "failed") Pal.danger else TextDim,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Pal.stroke)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (t.state == "saved") 1f else pct)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
            if (t.state == "failed" && t.note.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    t.note, color = Pal.danger.copy(alpha = 0.75f), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Footer(st: UiState) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(CardStroke))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (st.waiting > 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp), color = Accent, strokeWidth = 1.5.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("文件等待中", color = TextDim, fontSize = 12.sp, letterSpacing = 0.5.sp)
            Spacer(Modifier.width(10.dp))
            Text("${st.waiting}", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("已收 ${st.savedCount} 个 · ${fmtSize(st.savedBytes)}", color = TextDim, fontSize = 11.sp)
        }
    }
}
