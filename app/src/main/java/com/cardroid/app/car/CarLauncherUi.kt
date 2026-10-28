package com.cardroid.app.car

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * CarDroid car launcher UI (Phase 4 Part B). Landscape, density 1.0 (1 dp = 1 px), must fit 1280x720 and 1280x672, no system bars.
 * Layout: 96 dp dock + Home web panels, customizable Widgets, then a paged app grid. Overlays (long-press menu, Recents,
 * hidden apps) live over the grid area only, so the dock stays usable; Home / Back / the scrim / hardware BACK close them.
 */

internal object Car {
    val Bg = com.cardroid.app.CarDroidPalette.Background
    val Dock = com.cardroid.app.CarDroidPalette.Dock
    val Surface = com.cardroid.app.CarDroidPalette.Surface
    val SurfaceHi = com.cardroid.app.CarDroidPalette.SurfaceHigh
    val Hairline = com.cardroid.app.CarDroidPalette.OutlineVariant.copy(alpha = 0.5f)
    val Text = com.cardroid.app.CarDroidPalette.Text
    val TextDim = com.cardroid.app.CarDroidPalette.Muted
    val Accent = com.cardroid.app.CarDroidPalette.Mint       // shared brand accent
    val Scrim = com.cardroid.app.CarDroidPalette.Scrim
    val DockWidth = 96.dp
    val IconSize = 80.dp
    val Tnum = "tnum"
}

private sealed interface Overlay {
    data object None : Overlay
    data class Menu(val app: CarApp, val anchor: Rect) : Overlay
    data object Recents : Overlay
    data object Hidden : Overlay
}

@Composable
fun CarLauncherScreen(apps: List<CarApp>, prefs: CarLauncherPrefs, homeSignal: Int, onLaunch: (CarApp) -> Unit) {

    val pinned by prefs.pinned.collectAsState()
    val hidden by prefs.hidden.collectAsState()
    val tasksAvailable = true // launcher-owned app history does not need shell task enumeration
    val arranged = remember(apps, pinned, hidden) { CarLauncherLogic.arrange(apps, { it.target }, pinned, hidden) }
    val hiddenApps = remember(apps, hidden) { apps.filter { it.target in hidden } }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    val appTarget by CarDisplayState.appTarget.collectAsState()
    val selectedApp = remember(apps, appTarget) { apps.firstOrNull { it.target == appTarget || it.packageName == appTarget } }
    var embeddedDisplayId by remember { mutableIntStateOf(-1) }
    val launchEmbedded: (CarApp) -> Unit = { overlay = Overlay.None; onLaunch(it) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(appTarget) { if (appTarget != null) overlay = Overlay.None }
    LaunchedEffect(apps) { prefs.prune(apps.map { it.target }.toSet()) }
    LaunchedEffect(hiddenApps.isEmpty()) { if (hiddenApps.isEmpty() && overlay == Overlay.Hidden) overlay = Overlay.None }
    LaunchedEffect(tasksAvailable) { if (!tasksAvailable && overlay == Overlay.Recents) overlay = Overlay.None }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    // Re-created per page count; kept here so Home and onNewIntent can reach it.
    val pager = remember { mutableStateOf<androidx.compose.foundation.pager.PagerState?>(null) }
    fun goHome(animate: Boolean) {
        overlay = Overlay.None
        CarDisplayState.showDashboard()
        pager.value?.let { p -> scope.launch { if (animate) p.animateScrollToPage(0) else p.scrollToPage(0) } }
    }
    LaunchedEffect(homeSignal) { if (homeSignal > 0) goHome(animate = false) }
    // Corner Home button long-press: the launcher opens its Recents panel once it is back on top (ignored when Recents is unavailable).
    val onTop by CarDisplayState.launcherOnTop.collectAsState()
    val recentsRequested by CarDisplayState.recentsRequest.collectAsState()
    LaunchedEffect(onTop, recentsRequested, tasksAvailable) {
        if (onTop && recentsRequested) {
            kotlinx.coroutines.delay(60) // let the GO_HOME homeSignal reset land first
            if (CarDisplayState.consumeRecentsRequest() && tasksAvailable) overlay = Overlay.Recents
        }
    }

    Row(
        Modifier.fillMaxSize().background(Car.Bg).drawBehind {
            drawRect(Brush.radialGradient(listOf(Car.Accent.copy(alpha = 0.045f), Color.Transparent),
                center = Offset(size.width * 0.92f, -size.height * 0.15f), radius = size.width * 0.55f))
        }
    ) {
        val atHome = selectedApp == null && overlay == Overlay.None && (pager.value?.currentPage ?: 0) == 0
        fun back() {
            if (overlay != Overlay.None) overlay = Overlay.None
            else if (selectedApp != null) { if (embeddedDisplayId >= 0) CarDisplayState.panelBack(embeddedDisplayId) }
            else goHome(animate = true)
        }
        BackHandler(enabled = selectedApp != null && overlay == Overlay.None) { back() }
        Dock(
            homeSelected = atHome,
            recentsVisible = tasksAvailable,
            recentsSelected = overlay == Overlay.Recents,
            backEnabled = overlay != Overlay.None || !atHome,
            hiddenCount = hiddenApps.size,
            hiddenSelected = overlay == Overlay.Hidden,
            onHome = { goHome(animate = true) },
            onRecents = { overlay = if (overlay == Overlay.Recents) Overlay.None else Overlay.Recents },
            onBack = { back() },
            onHidden = { overlay = if (overlay == Overlay.Hidden) Overlay.None else Overlay.Hidden },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            var origin by remember { mutableStateOf(Offset.Zero) }
            val panelOpen = overlay == Overlay.Recents || overlay == Overlay.Hidden
            val blur by animateDpAsState(if (panelOpen) 18.dp else 0.dp, tween(220), label = "gridBlur")
            Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }.then(if (blur > 0.dp) Modifier.blur(blur) else Modifier)) {
                AppPages(arranged, pinned.toSet(), focusKey = (overlay as? Overlay.Menu)?.app?.target,
                    onPager = { pager.value = it }, onLaunch = launchEmbedded, prefs = prefs,
                    contentVisible = selectedApp == null && overlay == Overlay.None && onTop,
                    onLongPress = { app, rect -> overlay = Overlay.Menu(app, rect.translate(-origin)) })
            }
            if (selectedApp != null) key(selectedApp.target) {
                // Block hits in rounded corners / loading gaps without intercepting app touches.
                Box(Modifier.fillMaxSize().background(Car.Bg).clickable(onClickLabel = "Embedded app background") {})
                NativeAppPanel(selectedApp, Modifier.fillMaxSize(), visible = overlay == Overlay.None && onTop,
                    showChooser = false, onDisplayChanged = { embeddedDisplayId = it }, onChoose = {})
            }
            // Scrim + overlays
            // Panels get a dark scrim; the long-press menu dims the other cells instead (so the pressed app stays in context).
            androidx.compose.animation.AnimatedVisibility(panelOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
                Box(Modifier.fillMaxSize().background(Car.Scrim))
            }
            if (overlay != Overlay.None) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { overlay = Overlay.None })
            val menu = overlay as? Overlay.Menu
            var lastMenu by remember { mutableStateOf<Overlay.Menu?>(null) }
            if (menu != null) lastMenu = menu
            androidx.compose.animation.AnimatedVisibility(menu != null, enter = fadeIn(tween(140)) + scaleIn(tween(160), 0.94f), exit = fadeOut(tween(120)) + scaleOut(tween(120), 0.96f)) {
                lastMenu?.let { m ->
                    val isPinned = m.app.target in pinned
                    AnchoredMenu(m.anchor) {
                        AppMenu(m.app, isPinned, canPin = CarLauncherLogic.canPin(pinned, m.app.target),
                            onTogglePin = { prefs.togglePin(m.app.target); overlay = Overlay.None },
                            onHide = { prefs.hide(m.app.target); overlay = Overlay.None })
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(overlay == Overlay.Recents, enter = fadeIn(tween(160)) + scaleIn(tween(180), 0.97f), exit = fadeOut(tween(120))) {
                RecentsPanel(apps, onPick = launchEmbedded)
            }
            androidx.compose.animation.AnimatedVisibility(overlay == Overlay.Hidden, enter = fadeIn(tween(160)) + scaleIn(tween(180), 0.97f), exit = fadeOut(tween(120))) {
                HiddenPanel(hiddenApps, onUnhide = { prefs.unhide(it.target) })
            }
        }
    }
}

// ---------------------------------------------------------------- Dock

@Composable
private fun Dock(
    homeSelected: Boolean, recentsVisible: Boolean, recentsSelected: Boolean, backEnabled: Boolean, hiddenCount: Int, hiddenSelected: Boolean,
    onHome: () -> Unit, onRecents: () -> Unit, onBack: () -> Unit, onHidden: () -> Unit,
) {
    Column(
        Modifier.width(Car.DockWidth).fillMaxHeight().background(Car.Dock)
            .drawBehind { drawLine(Car.Hairline, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f) }
            .padding(top = 22.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Clock()
        Spacer(Modifier.height(22.dp))
        DockButton("Home", selected = homeSelected, onClick = onHome) { c -> glyphHome(c) }
        if (recentsVisible) {
            Spacer(Modifier.height(8.dp))
            DockButton("Recent apps", selected = recentsSelected, onClick = onRecents) { c -> glyphRecents(c) }
        }
        Spacer(Modifier.height(8.dp))
        DockButton("Back", enabled = backEnabled, onClick = onBack) { c -> glyphBack(c) }
        Spacer(Modifier.weight(1f))
        if (hiddenCount > 0) DockButton("Hidden apps", selected = hiddenSelected, badge = hiddenCount, onClick = onHidden) { c -> glyphEyeOff(c) }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) { val t = System.currentTimeMillis(); now = t; delay(60_000 - t % 60_000 + 25) }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) { SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(now)).uppercase(Locale.getDefault()) }
    Text(time, color = Car.Text, style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Car.Tnum, letterSpacing = (-0.5).sp))
    Spacer(Modifier.height(2.dp))
    Text(date, color = Car.TextDim, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp))
}

@Composable
private fun DockButton(
    description: String, selected: Boolean = false, enabled: Boolean = true, badge: Int = 0, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(
        when { selected -> Car.Accent.copy(alpha = 0.14f); pressed && enabled -> Color(0x1AFFFFFF); else -> Color.Transparent }, tween(150), label = "dockBg")
    val fg by animateColorAsState(
        when { selected -> Car.Accent; !enabled -> Car.Text.copy(alpha = 0.28f); else -> Car.Text }, tween(150), label = "dockFg")
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "dockScale")
    Box(
        Modifier.size(72.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(22.dp)).background(bg)
            .clickable(source, null, enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(28.dp)) { glyph(fg) }
            Spacer(Modifier.height(4.dp))
            Text(description, color = fg, fontSize = 11.sp, maxLines = 1)
        }
        if (badge > 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(19.dp).clip(CircleShape).background(Car.SurfaceHi),
                contentAlignment = Alignment.Center) {
                Text(badge.coerceAtMost(99).toString(), color = Car.Text, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Car.Tnum))
            }
        }
    }
}

// ---------------------------------------------------------------- Grid

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppPages(
    apps: List<CarApp>, pinned: Set<String>, focusKey: String?, onPager: (androidx.compose.foundation.pager.PagerState) -> Unit,
    onLaunch: (CarApp) -> Unit, onLongPress: (CarApp, Rect) -> Unit, prefs: CarLauncherPrefs, contentVisible: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = if (maxWidth >= 1000.dp) 7 else 6
        val rows = 3
        val pages = remember(apps, cols) { CarLauncherLogic.pages(apps, cols * rows).ifEmpty { listOf(emptyList()) } }
        val state = rememberPagerState(pageCount = { pages.size + 2 })
        SideEffect { onPager(state) }
        Column(Modifier.fillMaxSize()) {
            val scope = rememberCoroutineScope()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Home", "Widgets", "Apps").forEachIndexed { index, title ->
                    DashboardButton(title, selected = if (index == 2) state.currentPage >= 2 else state.currentPage == index) {
                        scope.launch { state.animateScrollToPage(index.coerceAtMost(state.pageCount - 1)) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Keep native dashboard displays mounted outside the lazy pager.
                // Visibility changes disable input without discarding their tasks.
                Box(Modifier.fillMaxSize().zIndex(if (state.currentPage == 0) 1f else -1f)) {
                    DashboardHome(apps, prefs, visible = contentVisible && state.currentPage == 0)
                }
                HorizontalPager(state, Modifier.fillMaxSize(), beyondViewportPageCount = 0, key = { it },
                    userScrollEnabled = state.currentPage != 0) { page ->
                    when (page) {
                        0 -> Box(Modifier.fillMaxSize())
                        1 -> Box(Modifier.fillMaxSize().background(Car.Bg)) { DashboardWidgets(apps, pinned, prefs, onLaunch, onLongPress) }
                        else -> Box(Modifier.fillMaxSize().background(Car.Bg)) {
                            if (apps.isEmpty()) EmptyState("No visible apps", "Use the hidden-apps button in the dock to restore hidden apps.")
                            else PageGrid(pages[page - 2], cols, rows, pinned, focusKey, onLaunch, onLongPress)
                        }
                    }
                }
            }
            PageIndicator(pages.size + 2, state.currentPage) { i -> scope.launch { state.animateScrollToPage(i) } }
        }
    }
}

@Composable
private fun PageGrid(items: List<CarApp>, cols: Int, rows: Int, pinned: Set<String>, focusKey: String?, onLaunch: (CarApp) -> Unit, onLongPress: (CarApp, Rect) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 28.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until cols) {
                    val app = items.getOrNull(r * cols + c)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (app != null) key(app.target) {
                            AppCell(app, app.target in pinned, dimmed = focusKey != null && focusKey != app.target, onLaunch, onLongPress)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppCell(app: CarApp, pinned: Boolean, dimmed: Boolean, onLaunch: (CarApp) -> Unit, onLongPress: (CarApp, Rect) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), label = "cellScale")
    val plate by animateColorAsState(if (pressed) Color(0x12FFFFFF) else Color.Transparent, tween(120), label = "cellPlate")
    val cellAlpha by animateFloatAsState(if (dimmed) 0.22f else 1f, tween(180), label = "cellAlpha")
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.fillMaxSize().alpha(cellAlpha).padding(5.dp).onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(26.dp)).background(plate)
            .combinedClickable(source, null, onClickLabel = "Open ${app.label}", onLongClickLabel = "App options",
                onLongClick = { onLongPress(app, bounds) }, onClick = { onLaunch(app) }),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                AppIcon(app.target, Car.IconSize)
                if (pinned) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(14.dp).clip(CircleShape).background(Car.Bg).padding(3.dp)
                        .clip(CircleShape).background(Car.Accent))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(app.label, color = Car.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun AppIcon(key: String, size: Dp, dim: Boolean = false) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by rememberAppIcon(key, px)
    val alpha by animateFloatAsState(if (icon != null) 1f else 0f, tween(180), label = "iconAlpha")
    Box(Modifier.size(size)) {
        if (icon == null || alpha < 1f) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.28f)).background(Car.Surface))
        icon?.let {
            Image(it, contentDescription = null, filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().alpha(alpha * if (dim) 0.55f else 1f))
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
        if (count > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i ->
                val w by animateDpAsState(if (i == current) 22.dp else 6.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "dotW")
                val c by animateColorAsState(if (i == current) Car.Accent else Color(0x55FFFFFF), tween(200), label = "dotC")
                Box(Modifier.height(28.dp).clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }.padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = w, height = 6.dp).clip(CircleShape).background(c))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Car.Text, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
        Text(body, color = Car.TextDim, textAlign = TextAlign.Center, style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp))
    }
}

// ---------------------------------------------------------------- Long-press menu

/** Places its single child below [anchor] (or above it when there is no room), horizontally centred, clamped to the container. */
@Composable
private fun AnchoredMenu(anchor: Rect, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val m = 14.dp.roundToPx()
        layout(w, h) {
            val x = (anchor.center.x - p.width / 2f).roundToInt().coerceIn(m, (w - p.width - m).coerceAtLeast(m))
            val below = (anchor.top + anchor.height * 0.62f).roundToInt()
            val y = if (below + p.height <= h - m) below else (anchor.top + anchor.height * 0.12f - p.height).roundToInt()
            p.place(x, y.coerceIn(m, (h - p.height - m).coerceAtLeast(m)))
        }
    }
}

@Composable
private fun AppMenu(app: CarApp, pinned: Boolean, canPin: Boolean, onTogglePin: () -> Unit, onHide: () -> Unit) {
    Column(
        Modifier.width(312.dp).clip(RoundedCornerShape(24.dp)).background(Car.Surface).border(1.dp, Car.Hairline, RoundedCornerShape(24.dp))
            .clickable(remember { MutableInteractionSource() }, null) {}.padding(vertical = 6.dp)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.target, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(app.label, color = Car.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                Text(if (pinned) "In favorites" else app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (pinned) Car.Accent else Car.TextDim, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(Car.Hairline))
        MenuItem(
            text = if (pinned) "Remove from favorites" else "Add to favorites",
            sub = if (!canPin) "Favorites are full (${CarLauncherLogic.MAX_PINNED})" else null,
            enabled = canPin, onClick = onTogglePin,
        ) { c -> glyphStar(c, filled = pinned) }
        MenuItem(text = "Hide from car", onClick = onHide) { c -> glyphEyeOff(c) }
    }
}

@Composable
private fun MenuItem(text: String, sub: String? = null, enabled: Boolean = true, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed && enabled) com.cardroid.app.CarDroidPalette.OutlineVariant.copy(alpha = 0.5f) else Color.Transparent, tween(100), label = "menuBg")
    val fg = if (enabled) Car.Text else Car.Text.copy(alpha = 0.35f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(source, null, enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(26.dp)) { glyph(if (enabled) Car.Text else Car.Text.copy(alpha = 0.35f)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text, color = fg, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium))
            if (sub != null) Text(sub, color = Car.TextDim, style = TextStyle(fontSize = 13.sp))
        }
    }
}

// ---------------------------------------------------------------- Recents / hidden panels

@Composable
private fun PanelFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Car.Text, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp))
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Car.TextDim, style = TextStyle(fontSize = 15.sp))
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun RecentsPanel(apps: List<CarApp>, onPick: (CarApp) -> Unit) {
    val targets by CarDisplayState.recentAppTargets.collectAsState()
    val recents = remember(apps, targets) { targets.mapNotNull { target -> apps.firstOrNull { it.target == target || it.packageName == target } }.distinctBy { it.target } }
    PanelFrame("Recent apps", "Open beside the sidebar") {
        when {
            recents.isNotEmpty() -> LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(recents, key = { it.target }) { app ->
                    PanelCard(iconKey = app.target, label = app.label, onClick = { onPick(app) })
                }
            }
            else -> PanelNote("No apps have been opened yet.")
        }
    }
}

@Composable
private fun HiddenPanel(apps: List<CarApp>, onUnhide: (CarApp) -> Unit) {
    PanelFrame("Hidden from car", "Tap an app to show it again") {
        LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(apps, key = { it.target }) { app -> PanelCard(iconKey = app.target, label = app.label, dim = true, action = "Show", onClick = { onUnhide(app) }) }
        }
    }
}

@Composable
private fun PanelCard(iconKey: String, label: String, dim: Boolean = false, action: String? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "cardScale")
    Column(
        Modifier.width(168.dp).height(196.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(26.dp)).background(if (pressed) Car.SurfaceHi else Car.Surface).border(1.dp, Car.Hairline, RoundedCornerShape(26.dp))
            .clickable(source, null, onClick = onClick).padding(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(iconKey, Car.IconSize, dim = dim)
        Spacer(Modifier.height(12.dp))
        Text(label, color = Car.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium))
        if (action != null) {
            Spacer(Modifier.weight(1f))
            Text(action.uppercase(), color = Car.Accent, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp))
        }
    }
}

@Composable
private fun PanelNote(text: String) {
    Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Car.Surface).border(1.dp, Car.Hairline, RoundedCornerShape(22.dp))
        .padding(horizontal = 28.dp, vertical = 22.dp)) {
        Text(text, color = Car.TextDim, style = TextStyle(fontSize = 17.sp))
    }
}

// ---------------------------------------------------------------- Glyphs (drawn, no icon dependency)

private fun DrawScope.stroke() = Stroke(width = size.minDimension * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private fun DrawScope.p(x: Float, y: Float) = Offset(size.width * x, size.height * y)

private fun DrawScope.glyphHome(c: Color) {
    val path = Path().apply {
        moveTo(size.width * 0.14f, size.height * 0.47f); lineTo(size.width * 0.5f, size.height * 0.16f); lineTo(size.width * 0.86f, size.height * 0.47f)
        moveTo(size.width * 0.25f, size.height * 0.38f); lineTo(size.width * 0.25f, size.height * 0.84f); lineTo(size.width * 0.75f, size.height * 0.84f); lineTo(size.width * 0.75f, size.height * 0.38f)
        moveTo(size.width * 0.42f, size.height * 0.84f); lineTo(size.width * 0.42f, size.height * 0.62f); lineTo(size.width * 0.58f, size.height * 0.62f); lineTo(size.width * 0.58f, size.height * 0.84f)
    }
    drawPath(path, c, style = stroke())
}

private fun DrawScope.glyphRecents(c: Color) {
    val r = CornerRadius(size.minDimension * 0.1f)
    drawRoundRect(c.copy(alpha = c.alpha * 0.55f), p(0.32f, 0.14f), Size(size.width * 0.54f, size.height * 0.5f), r, style = stroke())
    drawRoundRect(c, p(0.14f, 0.34f), Size(size.width * 0.54f, size.height * 0.52f), r, style = stroke())
}

private fun DrawScope.glyphBack(c: Color) {
    val path = Path().apply { moveTo(size.width * 0.62f, size.height * 0.18f); lineTo(size.width * 0.3f, size.height * 0.5f); lineTo(size.width * 0.62f, size.height * 0.82f) }
    drawPath(path, c, style = stroke())
}

private fun DrawScope.glyphEyeOff(c: Color) {
    val eye = Path().apply {
        moveTo(size.width * 0.1f, size.height * 0.5f)
        quadraticBezierTo(size.width * 0.5f, size.height * 0.1f, size.width * 0.9f, size.height * 0.5f)
        quadraticBezierTo(size.width * 0.5f, size.height * 0.9f, size.width * 0.1f, size.height * 0.5f)
        close()
    }
    drawPath(eye, c, style = stroke())
    drawCircle(c, size.minDimension * 0.11f, p(0.5f, 0.5f), style = stroke())
    drawLine(c, p(0.18f, 0.16f), p(0.82f, 0.84f), stroke().width, StrokeCap.Round)
}

private fun DrawScope.glyphStar(c: Color, filled: Boolean) {
    val cx = size.width / 2f; val cy = size.height * 0.53f
    val outer = size.minDimension * 0.44f; val inner = outer * 0.45f
    val path = Path()
    for (i in 0 until 10) {
        val a = Math.toRadians(-90.0 + i * 36.0)
        val rr = if (i % 2 == 0) outer else inner
        val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    if (filled) drawPath(path, Car.Accent) else drawPath(path, c, style = stroke())
}
