package dev.bucket.launcherslogan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = AppPalette.Background.toArgb()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val controller = remember {
                ThemeController(ColorSchemeMode.MonetSystem, keyColor = AppPalette.Primary)
            }
            MiuixTheme(controller = controller) {
                LauncherSloganApp(this@MainActivity)
            }
        }
    }
}

private enum class Page { MAIN, EDIT, ABOUT }

private data class GateState(
    val checking: Boolean = true,
    val hookReady: Boolean = false,
    val rootReady: Boolean = false,
    val hookMessage: String = "等待 Launcher 作用域检测完成",
    val rootMessage: String = "等待 Root 授权检测完成",
)

private const val LAUSO_REPOSITORY_URL = "https://github.com/TheKingBucket001/Lauso"
private const val LAUSO_RELEASE_URL = "https://github.com/TheKingBucket001/Lauso/releases/latest"
private const val LAUSO_LICENSE_URL = "https://github.com/TheKingBucket001/Lauso/blob/main/LICENSE"
private val MainPageAnimation = spring(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = 0.5f)
private val DetailPageAnimation = spring(dampingRatio = 1f, stiffness = 650f,
    visibilityThreshold = IntOffset(1, 1))

internal object AppPalette {
    val Background = Color(0xFFFAF8FF)
    val Surface = Color(0xFFEEEDF4)
    val Primary = Color(0xFF465D91)
    val Ink = Color(0xFF1A1B20)
    val Muted = Color(0xFF595A5F)
    val Divider = Color(0xFFDDDCE4)
}

internal object AppIcons {
    val Home: ImageVector = ImageVector.Builder(
        name = "Home",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(AppPalette.Primary)) {
        moveTo(12f, 3.2f)
        lineTo(4f, 9.6f)
        lineTo(4f, 20f)
        lineTo(9.1f, 20f)
        lineTo(9.1f, 13.9f)
        lineTo(14.9f, 13.9f)
        lineTo(14.9f, 20f)
        lineTo(20f, 20f)
        lineTo(20f, 9.6f)
        close()
    }.build()

    val Settings: ImageVector = ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(AppPalette.Primary), pathFillType = PathFillType.EvenOdd) {
        moveTo(9.9f, 3f)
        lineTo(14.1f, 3f)
        lineTo(14.6f, 5.8f)
        lineTo(17f, 7.2f)
        lineTo(19.7f, 6.1f)
        lineTo(21.8f, 9.7f)
        lineTo(19.6f, 11.4f)
        lineTo(19.6f, 14.1f)
        lineTo(21.8f, 15.8f)
        lineTo(19.7f, 19.4f)
        lineTo(17f, 18.3f)
        lineTo(14.6f, 19.7f)
        lineTo(14.1f, 22.5f)
        lineTo(9.9f, 22.5f)
        lineTo(9.4f, 19.7f)
        lineTo(7f, 18.3f)
        lineTo(4.3f, 19.4f)
        lineTo(2.2f, 15.8f)
        lineTo(4.4f, 14.1f)
        lineTo(4.4f, 11.4f)
        lineTo(2.2f, 9.7f)
        lineTo(4.3f, 6.1f)
        lineTo(7f, 7.2f)
        lineTo(9.4f, 5.8f)
        close()
        moveTo(12f, 16.1f)
        curveTo(14.3f, 16.1f, 16.1f, 14.3f, 16.1f, 12f)
        curveTo(16.1f, 9.7f, 14.3f, 7.9f, 12f, 7.9f)
        curveTo(9.7f, 7.9f, 7.9f, 9.7f, 7.9f, 12f)
        curveTo(7.9f, 14.3f, 9.7f, 16.1f, 12f, 16.1f)
        close()
    }.build()
}

@Composable
private fun LauncherSloganApp(activity: MainActivity) {
    var gateState by remember { mutableStateOf(GateState()) }
    var gateRefreshSignal by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf(Page.MAIN) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val navigationScope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val homeListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    var rules: List<SloganRule> by remember { mutableStateOf(RuleStore.read(activity).toList()) }
    var editing by remember { mutableStateOf<SloganRule?>(null) }
    var saving by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var translucentPanelEnabled by remember { mutableStateOf(MenuMaterialSettings.readPanel(activity)) }
    var compactMenuEnabled by remember { mutableStateOf(MenuMaterialSettings.readCompact(activity)) }
    var visualComfortEnabled by remember {
        mutableStateOf(MenuMaterialSettings.readVisualComfort(activity))
    }
    var disableBackdropBlurEnabled by remember {
        mutableStateOf(MenuMaterialSettings.readDisableBackdrop(activity))
    }
    var settingSaveInFlight by remember { mutableStateOf(false) }
    var menuContentEnabled by remember {
        mutableStateOf(MenuContentOptions.associate { it.key to MenuContentSettings.read(activity, it.key) })
    }

    LaunchedEffect(gateRefreshSignal) {
        gateState = GateState()
        val rootDeadline = SystemClock.elapsedRealtime() + 4_250L
        val rootResult = async(Dispatchers.IO) { RootAccess.check() }
        val hookReady = withContext(Dispatchers.IO) {
            waitForLauncherVerification(activity)
        }
        val root = withTimeoutOrNull(
            (rootDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
        ) {
            rootResult.await()
        }
        gateState = GateState(
            checking = false,
            hookReady = hookReady,
            rootReady = root?.granted == true,
            hookMessage = if (hookReady) {
                "Launcher 已对本次检查回执"
            } else {
                "未收到 Launcher 当前回执。请确认已在 LSPosed 启用 LauSo 的 Launcher 作用域，并重启 Launcher。"
            },
            rootMessage = when {
                root == null -> "Root 授权检测超时，请重新检查"
                root.granted -> "已获得 uid=0"
                else -> root.message.ifBlank { "Root 授权未通过" }
            },
        )
    }

    fun openEditor(rule: SloganRule?) {
        editing = rule
        page = Page.EDIT
    }

    fun saveRule(packageName: String, title: String, subtitle: String, toastMessage: String) {
        if (saving) return
        val cleanPackage = packageName.trim()
        val cleanTitle = RuleStore.cleanTitle(title)
        val cleanSubtitle = RuleStore.cleanSubtitle(subtitle)
        val cleanToastMessage = RuleStore.cleanToastMessage(toastMessage)
        if (!RuleStore.isValid(cleanPackage, cleanTitle, cleanSubtitle, cleanToastMessage)) {
            Toast.makeText(activity, "请输入正确的应用包名和主标语", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        val oldPackage = editing?.packageName
        val replacesExisting = rules.any { it.packageName == cleanPackage && it.packageName != oldPackage }
        if (oldPackage == null && !replacesExisting && rules.size >= RuleStore.maxRules()) {
            saving = false
            Toast.makeText(activity, "最多保存 ${RuleStore.maxRules()} 条规则", Toast.LENGTH_SHORT).show()
            return
        }
        val next = rules.filterNot { it.packageName == oldPackage || it.packageName == cleanPackage }
            .toMutableList()
            .apply { add(SloganRule(cleanPackage, cleanTitle, cleanSubtitle, cleanToastMessage)) }
            .sortedBy { it.packageName }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RootAccess.saveRules(next) }
            saving = false
            if (result.granted) {
                rules = next
                editing = null
                page = Page.MAIN
                Toast.makeText(activity, "已保存，点击刷新应用到桌面", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, result.message.ifBlank { "保存失败，请确认 Root" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteRule(rule: SloganRule) {
        if (saving) return
        saving = true
        val next = rules.filterNot { it.packageName == rule.packageName }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RootAccess.saveRules(next) }
            saving = false
            if (result.granted) {
                rules = next
                editing = null
                page = Page.MAIN
                Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, result.message.ifBlank { "删除失败，请确认 Root" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun savePopupSetting(kind: Int, enabled: Boolean) {
        if (settingSaveInFlight) return
        val previous = when (kind) {
            0 -> translucentPanelEnabled
            1 -> compactMenuEnabled
            2 -> visualComfortEnabled
            else -> disableBackdropBlurEnabled
        }
        when (kind) {
            0 -> translucentPanelEnabled = enabled
            1 -> compactMenuEnabled = enabled
            2 -> visualComfortEnabled = enabled
            else -> disableBackdropBlurEnabled = enabled
        }
        settingSaveInFlight = true
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (kind) {
                    0 -> RootAccess.saveMaterial(enabled)
                    1 -> RootAccess.saveCompact(enabled)
                    2 -> RootAccess.saveVisualComfort(enabled)
                    else -> RootAccess.saveDisableBackdrop(enabled)
                }
            }
            settingSaveInFlight = false
            if (result.granted) {
                Toast.makeText(activity, "菜单外观偏好已保存，重新打开菜单后生效", Toast.LENGTH_SHORT).show()
            } else {
                when (kind) {
                    0 -> translucentPanelEnabled = previous
                    1 -> compactMenuEnabled = previous
                    2 -> visualComfortEnabled = previous
                    else -> disableBackdropBlurEnabled = previous
                }
                Toast.makeText(activity, result.message.ifBlank { "菜单外观保存失败，请确认 Root" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun saveMenuContent(option: String, enabled: Boolean) {
        if (settingSaveInFlight) return
        val previous = menuContentEnabled[option] == true
        menuContentEnabled = menuContentEnabled + (option to enabled)
        settingSaveInFlight = true
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RootAccess.saveMenuContent(option, enabled) }
            settingSaveInFlight = false
            if (result.granted) {
                Toast.makeText(activity, "菜单内容偏好已保存，重新打开菜单后生效", Toast.LENGTH_SHORT).show()
            } else {
                menuContentEnabled = menuContentEnabled + (option to previous)
                Toast.makeText(activity, result.message.ifBlank { "菜单内容保存失败，请确认 Root" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun refreshLauncher() {
        if (refreshing) return
        refreshing = true
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { RootAccess.refreshLauncher() }
                if (result.granted) {
                    Toast.makeText(activity, "桌面正在热刷新", Toast.LENGTH_SHORT).show()
                    activity.startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } else {
                    Toast.makeText(activity, result.message.ifBlank { "桌面刷新失败" }, Toast.LENGTH_LONG).show()
                }
            } finally {
                refreshing = false
            }
        }
    }

    fun navigateMain(index: Int) {
        if (index == pagerState.targetPage) return
        navigationJob?.cancel()
        navigationJob = navigationScope.launch {
            pagerState.animateScrollToPage(index, animationSpec = MainPageAnimation)
        }
    }

    BackHandler(enabled = page != Page.MAIN || pagerState.currentPage != 0 || pagerState.targetPage != 0) {
        if (page == Page.EDIT && saving) return@BackHandler
        if (page == Page.MAIN) {
            navigateMain(0)
        } else {
            page = Page.MAIN
            editing = null
        }
    }

    if (gateState.checking || !gateState.hookReady || !gateState.rootReady) {
        EnvironmentGate(gateState) { gateRefreshSignal++ }
    } else {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val returning = targetState == Page.MAIN
                (slideInHorizontally(
                    animationSpec = DetailPageAnimation,
                    initialOffsetX = { if (returning) -it / 4 else it },
                ) + fadeIn(animationSpec = tween(160))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = DetailPageAnimation,
                            targetOffsetX = { if (returning) it else -it / 4 },
                        ) + fadeOut(animationSpec = tween(120)))
            },
            modifier = Modifier.fillMaxSize().background(AppPalette.Background),
            label = "detail-transition",
        ) { targetPage ->
            when (targetPage) {
                Page.MAIN -> Box(
                    modifier = Modifier.fillMaxSize().background(AppPalette.Background),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                        flingBehavior = PagerDefaults.flingBehavior(pagerState,
                            snapAnimationSpec = MainPageAnimation),
                    ) { index ->
                        if (index == 0) {
                            HomeScreen(
                                rules = rules,
                                listState = homeListState,
                                refreshing = refreshing,
                                onRefresh = ::refreshLauncher,
                                onAbout = { page = Page.ABOUT },
                                onAdd = { openEditor(null) },
                                onEdit = { openEditor(it) },
                            )
                        } else {
                            SettingsScreen(
                                listState = settingsListState,
                                isSaving = settingSaveInFlight,
                                onAbout = { page = Page.ABOUT },
                                translucentPanelEnabled = translucentPanelEnabled,
                                compactMenuEnabled = compactMenuEnabled,
                                visualComfortEnabled = visualComfortEnabled,
                                disableBackdropBlurEnabled = disableBackdropBlurEnabled,
                                menuContentEnabled = menuContentEnabled,
                                onPanelChange = { savePopupSetting(0, it) },
                                onCompactChange = { savePopupSetting(1, it) },
                                onVisualComfortChange = { savePopupSetting(2, it) },
                                onDisableBackdropChange = { savePopupSetting(3, it) },
                                onMenuContentChange = ::saveMenuContent,
                            )
                        }
                    }
                    FloatingMainNavigationBar(
                        pagerState = pagerState,
                        onSelect = ::navigateMain,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding().padding(bottom = 20.dp),
                    )
                }
                Page.EDIT -> EditScreen(
                    initial = editing,
                    saving = saving,
                    onBack = { page = Page.MAIN; editing = null },
                    onSave = ::saveRule,
                    onDelete = { editing?.let(::deleteRule) },
                )
                Page.ABOUT -> AboutScreen(activity = activity, onBack = { page = Page.MAIN })
            }
        }
    }
}

@Composable
private fun MainPageHeader(
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = AppPalette.Ink)
            Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = AppPalette.Muted)
        }
        actions()
    }
}

@Composable
private fun HomeScreen(
    rules: List<SloganRule>,
    listState: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SloganRule) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(AppPalette.Background)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            MainPageHeader("LauSo", "桌面长按菜单标语") {
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    Image(painterResource(R.drawable.ic_refresh), "刷新桌面", Modifier.size(21.dp),
                        colorFilter = ColorFilter.tint(AppPalette.Ink))
                }
                IconButton(onClick = onAbout) {
                    Image(painterResource(R.drawable.ic_info), "关于", Modifier.size(21.dp),
                        colorFilter = ColorFilter.tint(AppPalette.Ink))
                }
            }
            if (rules.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(bottom = 128.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text("还没有标语", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppPalette.Ink)
                    Text("点击右下角添加应用规则", fontSize = 13.sp, color = AppPalette.Muted)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "rules-heading") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("应用规则", Modifier.weight(1f), fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, color = AppPalette.Muted)
                            Text("${rules.size} 项", fontSize = 12.sp, color = AppPalette.Muted)
                        }
                    }
                    items(rules, key = { it.packageName }) { rule ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
                            pressFeedbackType = PressFeedbackType.Sink,
                            showIndication = true,
                            onClick = { onEdit(rule) },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(rule.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                        color = AppPalette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (rule.subtitle.isNotBlank()) {
                                        Text(rule.subtitle, fontSize = 13.sp, color = AppPalette.Muted,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(rule.packageName, fontSize = 11.sp, color = AppPalette.Muted,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("›", Modifier.padding(start = 12.dp).clearAndSetSemantics {},
                                    fontSize = 24.sp, color = AppPalette.Muted)
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 96.dp),
        ) {
            Image(painterResource(R.drawable.ic_add), "添加应用", Modifier.size(26.dp))
        }
    }
}


@Composable
private fun SettingsScreen(
    listState: LazyListState,
    isSaving: Boolean,
    onAbout: () -> Unit,
    translucentPanelEnabled: Boolean,
    compactMenuEnabled: Boolean,
    visualComfortEnabled: Boolean,
    disableBackdropBlurEnabled: Boolean,
    menuContentEnabled: Map<String, Boolean>,
    onPanelChange: (Boolean) -> Unit,
    onCompactChange: (Boolean) -> Unit,
    onVisualComfortChange: (Boolean) -> Unit,
    onDisableBackdropChange: (Boolean) -> Unit,
    onMenuContentChange: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppPalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        MainPageHeader("设置", "外观与菜单内容") {
            IconButton(onClick = onAbout) {
                Image(painterResource(R.drawable.ic_info), "关于", Modifier.size(21.dp),
                    colorFilter = ColorFilter.tint(AppPalette.Ink))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item { SectionTitle("菜单外观", topPadding = 4.dp) }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
                ) {
                    SettingSwitch(
                        checked = translucentPanelEnabled,
                        enabled = !isSaving,
                        onCheckedChange = onPanelChange,
                        title = "半透明白菜单",
                        summary = "让桌面背景轻透可见",
                    )
                    SettingsDivider()
                    SettingSwitch(
                        checked = compactMenuEnabled,
                        enabled = !isSaving,
                        onCheckedChange = onCompactChange,
                        title = "紧凑菜单",
                        summary = "收窄面板、文字和菜单行",
                    )
                    SettingsDivider()
                    SettingSwitch(
                        checked = visualComfortEnabled,
                        enabled = compactMenuEnabled && !isSaving,
                        onCheckedChange = onVisualComfortChange,
                        title = "视觉舒适",
                        summary = "紧凑模式下减少空白约 35%",
                    )
                    SettingsDivider()
                    SettingSwitch(
                        checked = disableBackdropBlurEnabled,
                        enabled = !isSaving,
                        onCheckedChange = onDisableBackdropChange,
                        title = "关闭全屏背景模糊",
                        summary = "保持桌面背景清晰",
                    )
                }
            }
            item { SectionTitle("菜单内容") }
            item {
                MenuContentGroup(MenuContentOptions.take(2), menuContentEnabled, !isSaving, onMenuContentChange)
            }
            item { SectionTitle("系统菜单项") }
            item {
                MenuContentGroup(MenuContentOptions.drop(2), menuContentEnabled, !isSaving, onMenuContentChange)
            }
        }
    }
}

private data class MenuContentOption(
    val key: String,
    val title: String,
    val summary: String,
)

private val MenuContentOptions = listOf(
    MenuContentOption(
        MenuContentSettings.DISABLE_LONG_PRESS_MENU,
        "移除“更多”入口",
        "将可用系统项直接放入长按菜单",
    ),
    MenuContentOption(
        MenuContentSettings.HIDE_DEEP_SHORTCUTS,
        "隐藏应用快捷方式",
        "不显示应用提供的深度快捷方式",
    ),
    MenuContentOption(
        MenuContentSettings.HIDE_PRIVACY_LOCK,
        "隐藏隐私锁",
        "隐藏“隐私锁”菜单项",
    ),
    MenuContentOption(
        MenuContentSettings.HIDE_CLOSE_PRIVACY_LOCK,
        "隐藏关闭隐私锁",
        "隐藏“关闭隐私锁”菜单项",
    ),
    MenuContentOption(
        MenuContentSettings.HIDE_APP_INFO,
        "隐藏应用详情",
        "隐藏“应用详情”菜单项",
    ),
    MenuContentOption(
        MenuContentSettings.HIDE_APP_SHARE,
        "隐藏分享",
        "隐藏“分享”菜单项",
    ),
    MenuContentOption(
        MenuContentSettings.HIDE_APP_EDIT,
        "隐藏编辑",
        "隐藏“编辑”菜单项",
    ),
    MenuContentOption(
        MenuContentSettings.HIDE_SERVICE_CARD,
        "隐藏服务卡片",
        "隐藏“添加服务卡片”菜单项",
    ),
)

@Composable
private fun MenuContentGroup(
    options: List<MenuContentOption>,
    values: Map<String, Boolean>,
    enabled: Boolean,
    onChange: (String, Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
    ) {
        options.forEachIndexed { index, option ->
            SettingSwitch(values[option.key] == true, enabled, { onChange(option.key, it) },
                option.title, option.summary)
            if (index < options.lastIndex) SettingsDivider()
        }
    }
}

/** MIUIX setting row whose label area intentionally has no click handler. */
@Composable
private fun SettingSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = AppPalette.Divider,
    )
}

@Composable
private fun EditScreen(
    initial: SloganRule?,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var packageName by remember(initial) { mutableStateOf(initial?.packageName.orEmpty()) }
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var subtitle by remember(initial) { mutableStateOf(initial?.subtitle.orEmpty()) }
    var toastMessage by remember(initial) { mutableStateOf(initial?.toastMessage.orEmpty()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(AppPalette.Background)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !saving) {
                Image(painterResource(R.drawable.ic_back), "返回", Modifier.size(22.dp))
            }
            Text(if (initial == null) "添加菜单项" else "编辑菜单项", modifier = Modifier.padding(start = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(
                value = packageName,
                onValueChange = { packageName = it.take(180) },
                modifier = Modifier.fillMaxWidth(),
                label = "应用包名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !saving,
            )
            TextField(
                value = title,
                onValueChange = { title = it.take(RuleStore.maxTitleLength()) },
                modifier = Modifier.fillMaxWidth(),
                label = "主标语",
                useLabelAsPlaceholder = true,
                singleLine = true,
                enabled = !saving,
            )
            TextField(
                value = subtitle,
                onValueChange = { subtitle = it.take(RuleStore.maxSubtitleLength()) },
                modifier = Modifier.fillMaxWidth(),
                label = "副标语（可选）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                enabled = !saving,
            )
            TextField(
                value = toastMessage,
                onValueChange = { toastMessage = it.take(RuleStore.maxToastLength()) },
                modifier = Modifier.fillMaxWidth(),
                label = "点击气泡（可选）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                enabled = !saving,
            )
            Button(onClick = { onSave(packageName, title, subtitle, toastMessage) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "保存中" else "保存")
            }
            if (initial != null) {
                TextButton(text = "删除这条规则", onClick = { showDeleteDialog = true }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (showDeleteDialog) {
        WindowDialog(show = true, onDismissRequest = { showDeleteDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("删除这条规则", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("删除后，桌面长按该应用将恢复原有菜单。", style = MiuixTheme.textStyles.body2)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(text = "取消", onClick = { showDeleteDialog = false }, modifier = Modifier.weight(1f))
                    TextButton(text = "删除", onClick = { showDeleteDialog = false; onDelete() }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private const val LAUNCHER_VERIFY_RETRY_COUNT = 4
private const val LAUNCHER_VERIFY_TOTAL_TIMEOUT_MS = 900L
private const val LAUNCHER_VERIFY_ATTEMPT_TIMEOUT_MS = 225L

private fun waitForLauncherVerification(activity: MainActivity): Boolean {
    val requestId = ModuleStatusProvider.beginLauncherVerification(activity) ?: return false
    val verificationIntent = Intent(ModuleStatusProvider.ACTION_VERIFY_LAUNCHER)
        .setPackage(ModuleStatusProvider.LAUNCHER_PACKAGE)
        .putExtra(ModuleStatusProvider.EXTRA_REQUEST_ID, requestId)
    val deadline = SystemClock.elapsedRealtime() + LAUNCHER_VERIFY_TOTAL_TIMEOUT_MS
    try {
        repeat(LAUNCHER_VERIFY_RETRY_COUNT) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return false
            activity.sendBroadcast(verificationIntent)
            val waitMs = minOf(remaining, LAUNCHER_VERIFY_ATTEMPT_TIMEOUT_MS)
            if (ModuleStatusProvider.awaitLauncherVerification(requestId, waitMs)) return true
        }
    } catch (_: Throwable) {
        return false
    }
    return false
}

@Composable
private fun EnvironmentGate(state: GateState, onRefresh: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppPalette.Background)
            .statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 28.dp),
    ) {
        item {
            Text("LauSo", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppPalette.Ink)
            Text(
                if (state.checking) "正在检查运行环境" else "运行环境未就绪",
                modifier = Modifier.padding(top = 7.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.checking) AppPalette.Primary else Color(0xFFC84A38),
            )
            Text(
                if (state.checking) "正在确认 LSPosed 框架作用域与 Root 授权。"
                else "完成以下检查后，才能管理桌面长按菜单。",
                modifier = Modifier.padding(top = 7.dp),
                style = MiuixTheme.textStyles.body2,
                color = AppPalette.Muted,
                lineHeight = 20.sp,
            )
        }
        item { SectionTitle("运行环境") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
            ) {
                GateRow(
                    title = "LSPosed 框架",
                    passed = state.hookReady,
                    summary = state.hookMessage,
                )
                InfoDivider()
                GateRow(
                    title = "Root 授权",
                    passed = state.rootReady,
                    summary = state.rootMessage,
                )
            }
        }
        item {
            TextButton(
                text = if (state.checking) "正在检查" else "重新检查",
                onClick = onRefresh,
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun GateRow(title: String, passed: Boolean, summary: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape)
                .background(if (passed) Color(0xFF21A366) else Color(0xFFC84A38)),
        )
        Column(modifier = Modifier.padding(start = 13.dp).weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = AppPalette.Ink)
            Text(
                summary,
                modifier = Modifier.padding(top = 4.dp),
                style = MiuixTheme.textStyles.body2,
                color = AppPalette.Muted,
                lineHeight = 20.sp,
            )
        }
        Text(
            if (passed) "已就绪" else "未通过",
            modifier = Modifier.padding(start = 10.dp, top = 1.dp),
            style = MiuixTheme.textStyles.body2,
            color = if (passed) Color(0xFF21A366) else Color(0xFFC84A38),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AboutScreen(activity: MainActivity, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(AppPalette.Background).safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Image(
                    painterResource(R.drawable.ic_back),
                    "返回",
                    Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(AppPalette.Ink),
                )
            }
            Text(
                "关于 LauSo",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = AppPalette.Ink,
            )
            Spacer(Modifier.width(48.dp))
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(24.dp)),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(AppPalette.Surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Image(painterResource(R.drawable.ic_brand), "LauSo 图标", Modifier.size(64.dp))
                    Text("系统桌面长按菜单", color = AppPalette.Primary,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("LauSo", fontWeight = FontWeight.Bold, fontSize = 26.sp,
                        color = AppPalette.Ink)
                    Text(
                        "为桌面长按菜单添加自定义标语。",
                        color = AppPalette.Muted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
            ) {
                InfoRow("当前版本", BuildConfig.VERSION_NAME)
                AboutInfoDivider()
                InfoRow("模块 ID", "LauSo")
                AboutInfoDivider()
                InfoRow("维护者", "Bucket")
            }
            AboutSectionTitle("项目")
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
            ) {
                AboutActionRow("查看源代码", "GitHub · TheKingBucket001/Lauso", "GitHub") {
                    openExternalUrl(activity, LAUSO_REPOSITORY_URL)
                }
                AboutInfoDivider()
                AboutActionRow("开源许可证", "GNU General Public License v3.0", "GPL-3.0") {
                    openExternalUrl(activity, LAUSO_LICENSE_URL)
                }
            }
            AboutSectionTitle("更新")
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppPalette.Surface),
            ) {
                AboutActionRow("检查更新", "查看是否有新版本", "检查") {
                    openExternalUrl(activity, LAUSO_RELEASE_URL)
                }
            }
        }
    }
}

@Composable
private fun AboutSectionTitle(text: String) {
    Text(text, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold,
        color = AppPalette.Ink,
        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun AboutInfoDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppPalette.Divider))
}

private fun openExternalUrl(activity: MainActivity, url: String) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
        Toast.makeText(activity, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SectionTitle(text: String, topPadding: Dp = 16.dp) {
    Text(text, modifier = Modifier.padding(start = 20.dp, top = topPadding, bottom = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppPalette.Muted)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1, color = AppPalette.Primary)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            color = AppPalette.Ink,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun InfoDivider() {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f)))
}

@Composable
private fun AboutActionRow(
    title: String,
    summary: String,
    action: String,
    onClick: (() -> Unit)?,
) {
    val rowModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = rowModifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = AppPalette.Ink)
            Text(
                summary,
                style = MiuixTheme.textStyles.body2,
                color = AppPalette.Primary,
            )
        }
        Text(
            action,
            modifier = Modifier.padding(start = 12.dp),
            color = AppPalette.Primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
