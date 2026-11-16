package dev.bucket.launcherslogan

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope

internal enum class Page { MAIN, EDIT, ABOUT }

internal data class GateState(
    val checking: Boolean = true,
    val hookReady: Boolean = false,
    val rootReady: Boolean = false,
    val hookMessage: String = "等待 Launcher 作用域检测完成",
    val rootMessage: String = "等待 Root 授权检测完成",
)

internal sealed interface UiEvent {
    data class Message(val text: String, val long: Boolean = false) : UiEvent
    data object ReturnHome : UiEvent
}

/** Owns persisted settings and in-flight commands across Activity configuration changes. */
internal class LauncherAppViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private enum class Operation { RULE, SETTING, REFRESH, RELOAD }

    private val context get() = getApplication<Application>()
    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    var gateState by mutableStateOf(GateState())
        private set
    var rules: List<SloganRule> by mutableStateOf(emptyList())
        private set
    var page by mutableStateOf(
        Page.entries.firstOrNull { it.name == savedState.get<String>(PAGE_KEY) } ?: Page.MAIN,
    )
        private set
    var editingPackageName by mutableStateOf(savedState.get<String>(EDITING_KEY))
        private set
    var translucentPanelEnabled by mutableStateOf(false)
        private set
    var compactMenuEnabled by mutableStateOf(false)
        private set
    var visualComfortEnabled by mutableStateOf(false)
        private set
    var disableBackdropBlurEnabled by mutableStateOf(false)
        private set
    var menuContentEnabled: Map<String, Boolean> by mutableStateOf(emptyMap())
        private set
    var reloadSourcePage: Int? by mutableStateOf(null)
        private set

    private var operation by mutableStateOf<Operation?>(null)
    val saving get() = operation == Operation.RULE
    val busy get() = operation != null

    private var dataLoaded = false
    private var verificationJob: Job? = null

    init {
        recheck()
    }

    fun recheck() {
        if (busy || verificationJob?.isActive == true) return
        gateState = GateState()
        verificationJob = viewModelScope.launch {
            try {
                applySettingsSnapshot(runInterruptible(Dispatchers.IO, ::readSettingsSnapshot))

                val (root, hookReady) = supervisorScope {
                    val rootResult = async {
                        runInterruptible(Dispatchers.IO) { RootAccess.check() }
                    }
                    val hookReady = runInterruptible(Dispatchers.IO) {
                        waitForLauncherVerification(context)
                    }
                    rootResult.await() to hookReady
                }
                gateState = GateState(
                    checking = false,
                    hookReady = hookReady,
                    rootReady = root.granted,
                    hookMessage = if (hookReady) {
                        "Launcher 已对本次检查回执"
                    } else {
                        "未收到 Launcher 当前回执。请确认已在 LSPosed 启用 LauSo 的 Launcher 作用域，并重启 Launcher。"
                    },
                    rootMessage = if (root.granted) "已获得 uid=0"
                        else root.message.ifBlank { "Root 授权未通过" },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                gateState = GateState(
                    checking = false,
                    hookMessage = "环境检测失败，请重新检查",
                    rootMessage = "设置读取或 Root 检测未完成",
                )
            }
        }
    }

    /** Reloads stored values without restarting the launcher or repeating environment checks. */
    fun reloadSnapshot(sourcePage: Int) {
        if (sourcePage !in 0..1 || !dataLoaded || gateState.checking || busy) return
        operation = Operation.RELOAD
        reloadSourcePage = sourcePage
        viewModelScope.launch {
            try {
                applySettingsSnapshot(runInterruptible(Dispatchers.IO, ::readSettingsSnapshot))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message("设置读取失败，请重试", long = true)
            } finally {
                reloadSourcePage = null
                operation = null
            }
        }
    }

    private fun readSettingsSnapshot() = SettingsSnapshot(
        RuleStore.read(context).toList(),
        MenuMaterialSettings.readPanel(context),
        MenuMaterialSettings.readCompact(context),
        MenuMaterialSettings.readVisualComfort(context),
        MenuMaterialSettings.readDisableBackdrop(context),
        MenuContentOptions.associate { it.key to MenuContentSettings.read(context, it.key) },
    )

    private fun applySettingsSnapshot(snapshot: SettingsSnapshot) {
        rules = snapshot.rules
        translucentPanelEnabled = snapshot.translucentPanelEnabled
        compactMenuEnabled = snapshot.compactMenuEnabled
        visualComfortEnabled = snapshot.visualComfortEnabled
        disableBackdropBlurEnabled = snapshot.disableBackdropBlurEnabled
        menuContentEnabled = snapshot.menuContentEnabled
        dataLoaded = true
    }

    fun openEditor(rule: SloganRule?) {
        if (!canWrite()) return
        navigate(Page.EDIT, rule?.packageName)
    }

    fun openAbout() {
        if (!saving) navigate(Page.ABOUT)
    }

    fun backToMain() {
        if (!saving) navigate(Page.MAIN)
    }

    fun saveRule(packageName: String, title: String, subtitle: String, toastMessage: String) {
        if (!canWrite()) return
        val cleanPackage = packageName.trim()
        val cleanTitle = RuleStore.cleanTitle(title)
        val cleanSubtitle = RuleStore.cleanSubtitle(subtitle)
        val cleanToastMessage = RuleStore.cleanToastMessage(toastMessage)
        if (!RuleStore.isValid(cleanPackage, cleanTitle, cleanSubtitle, cleanToastMessage)) {
            message("请输入正确的应用包名和主标语")
            return
        }
        val oldPackage = editingPackageName?.takeIf { name -> rules.any { it.packageName == name } }
        val replacesExisting = rules.any { it.packageName == cleanPackage && it.packageName != oldPackage }
        if (oldPackage == null && !replacesExisting && rules.size >= RuleStore.maxRules()) {
            message("最多保存 ${RuleStore.maxRules()} 条规则")
            return
        }
        val next = rules.filterNot { it.packageName == oldPackage || it.packageName == cleanPackage }
            .toMutableList()
            .apply { add(SloganRule(cleanPackage, cleanTitle, cleanSubtitle, cleanToastMessage)) }
            .sortedBy { it.packageName }
        runRootOperation(
            Operation.RULE,
            command = { RootAccess.saveRules(next) },
            failureMessage = "保存失败，请确认 Root",
            onSuccess = {
                rules = next
                navigate(Page.MAIN)
                message("已保存，点击刷新应用到桌面")
            },
        )
    }

    fun deleteRule(rule: SloganRule) {
        if (!canWrite() || rules.none { it.packageName == rule.packageName }) return
        val next = rules.filterNot { it.packageName == rule.packageName }
        runRootOperation(
            Operation.RULE,
            command = { RootAccess.saveRules(next) },
            failureMessage = "删除失败，请确认 Root",
            onSuccess = {
                rules = next
                navigate(Page.MAIN)
                message("已删除")
            },
        )
    }

    fun savePopupSetting(kind: Int, enabled: Boolean) {
        if (!canWrite() || kind !in 0..3) return
        val previous = when (kind) {
            0 -> translucentPanelEnabled
            1 -> compactMenuEnabled
            2 -> visualComfortEnabled
            else -> disableBackdropBlurEnabled
        }
        if (previous == enabled) return
        setPopupSetting(kind, enabled)
        runRootOperation(
            Operation.SETTING,
            command = {
                when (kind) {
                    0 -> RootAccess.saveMaterial(enabled)
                    1 -> RootAccess.saveCompact(enabled)
                    2 -> RootAccess.saveVisualComfort(enabled)
                    else -> RootAccess.saveDisableBackdrop(enabled)
                }
            },
            failureMessage = "菜单外观保存失败，请确认 Root",
            onSuccess = { message("菜单外观偏好已保存，重新打开菜单后生效") },
            onFailure = { setPopupSetting(kind, previous) },
        )
    }

    fun saveMenuContent(option: String, enabled: Boolean) {
        if (!canWrite() || !MenuContentSettings.isKnown(option)) return
        val previous = menuContentEnabled[option] == true
        if (previous == enabled) return
        menuContentEnabled = menuContentEnabled + (option to enabled)
        runRootOperation(
            Operation.SETTING,
            command = { RootAccess.saveMenuContent(option, enabled) },
            failureMessage = "菜单内容保存失败，请确认 Root",
            onSuccess = { message("菜单内容偏好已保存，重新打开菜单后生效") },
            onFailure = { menuContentEnabled = menuContentEnabled + (option to previous) },
        )
    }

    fun refreshLauncher() {
        if (!canWrite()) return
        runRootOperation(
            Operation.REFRESH,
            command = { RootAccess.refreshLauncher() },
            failureMessage = "桌面刷新失败",
            onSuccess = {
                message("桌面正在热刷新")
                eventChannel.trySend(UiEvent.ReturnHome)
            },
        )
    }

    private fun canWrite() = dataLoaded && !gateState.checking
        && gateState.hookReady && gateState.rootReady && !busy

    private fun runRootOperation(
        kind: Operation,
        command: () -> RootAccess.Result,
        failureMessage: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
    ) {
        operation = kind
        viewModelScope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO, command)
                if (result.granted) {
                    onSuccess()
                } else {
                    onFailure()
                    message(result.message.ifBlank { failureMessage }, long = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
                message(failureMessage, long = true)
            } finally {
                operation = null
            }
        }
    }

    private fun setPopupSetting(kind: Int, enabled: Boolean) {
        when (kind) {
            0 -> translucentPanelEnabled = enabled
            1 -> compactMenuEnabled = enabled
            2 -> visualComfortEnabled = enabled
            else -> disableBackdropBlurEnabled = enabled
        }
    }

    private fun navigate(nextPage: Page, nextEditingPackage: String? = null) {
        editingPackageName = nextEditingPackage
        savedState[EDITING_KEY] = nextEditingPackage
        page = nextPage
        savedState[PAGE_KEY] = nextPage.name
    }

    private fun message(value: String, long: Boolean = false) {
        eventChannel.trySend(UiEvent.Message(value, long))
    }

    private data class SettingsSnapshot(
        val rules: List<SloganRule>,
        val translucentPanelEnabled: Boolean,
        val compactMenuEnabled: Boolean,
        val visualComfortEnabled: Boolean,
        val disableBackdropBlurEnabled: Boolean,
        val menuContentEnabled: Map<String, Boolean>,
    )

    private companion object {
        const val PAGE_KEY = "lauso.page"
        const val EDITING_KEY = "lauso.editingPackageName"
    }
}

private fun waitForLauncherVerification(context: Context): Boolean {
    val requestId = ModuleStatusProvider.beginLauncherVerification(context) ?: return false
    val intent = Intent(ModuleStatusProvider.ACTION_VERIFY_LAUNCHER)
        .setPackage(ModuleStatusProvider.LAUNCHER_PACKAGE)
        .putExtra(ModuleStatusProvider.EXTRA_REQUEST_ID, requestId)
    val deadline = SystemClock.elapsedRealtime() + 900L
    try {
        repeat(4) {
            if (Thread.currentThread().isInterrupted) return false
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return false
            context.sendBroadcast(intent)
            if (ModuleStatusProvider.awaitLauncherVerification(requestId, minOf(remaining, 225L))) {
                return true
            }
        }
    } catch (_: Exception) {
        return false
    }
    return false
}
