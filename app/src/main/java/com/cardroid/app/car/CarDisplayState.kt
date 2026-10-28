package com.cardroid.app.car

import com.cardroid.app.helper.HelperClient
import com.cardroid.app.helper.HelperProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide car display state shared between CarDroidService (owner of the HelperClient) and the UI (CarLauncherActivity, MainActivity).
 * CarDroidService calls [attach] with its HelperClient and mirrors HelperClient.status into [displayId]; everything here is a no-op / false
 * while no display is active. Normal opens select embedded content; task commands are retained for diagnostics.
 */
object CarDisplayState {
    const val DISPLAY_NAME = "CarDroid Car"

    // A shell helper can outlive an app update and still use the previous name.
    fun isCarDisplay(display: android.view.Display): Boolean =
        display.displayId != android.view.Display.DEFAULT_DISPLAY &&
            (display.name == DISPLAY_NAME || display.name == "CarDroid Car")

    private val _displayId = MutableStateFlow(-1)
    private val _tasks = MutableStateFlow<List<HelperProtocol.TaskEntry>>(emptyList())
    private val _tasksAvailable = MutableStateFlow(true)
    private val _lastError = MutableStateFlow<String?>(null)

    /** Id of the helper's car display, -1 while none is active. */
    val displayId: StateFlow<Int> get() = _displayId
    /** Latest TASKS reply (car display tasks, most recent first). */
    val tasks: StateFlow<List<HelperProtocol.TaskEntry>> get() = _tasks
    /** False after the helper reported TASKS_FAILED (Recents should be hidden); reset when a new display starts. */
    val tasksAvailable: StateFlow<Boolean> get() = _tasksAvailable
    /** Last LAUNCH/TASKS error text from the helper, for diagnostics. */
    val lastError: StateFlow<String?> get() = _lastError

    private val _launcherOnTop = MutableStateFlow(false)
    private val _recentsRequest = MutableStateFlow(false)
    /** True while CarLauncherActivity is resumed (the corner Home button is hidden then). */
    val launcherOnTop: StateFlow<Boolean> get() = _launcherOnTop
    fun setLauncherOnTop(v: Boolean) { _launcherOnTop.value = v }
    /** One-shot: set by the corner button's long-press, consumed (via [consumeRecentsRequest]) by the launcher on resume. */
    val recentsRequest: StateFlow<Boolean> = _recentsRequest.asStateFlow()
    fun requestRecents() { _recentsRequest.value = true }
    fun consumeRecentsRequest(): Boolean = _recentsRequest.compareAndSet(true, false)

    val active: Boolean get() = _displayId.value >= 0

    @Volatile private var client: HelperClient? = null

    fun attach(c: HelperClient) {
        client = c
        c.onTasks = { _tasks.value = it }
        c.onTasksFailed = { _tasksAvailable.value = false; _lastError.value = it }
        c.onLaunchFailed = { _lastError.value = it }
    }

    fun detach(c: HelperClient) {
        if (client === c) { client = null; c.onTasks = null; c.onTasksFailed = null; c.onLaunchFailed = null }
        update(HelperClient.State.NotRunning)
    }

    /** Called by CarDroidService for every HelperClient.status change. */
    fun update(s: HelperClient.State) {
        val id = (s as? HelperClient.State.DisplayActive)?.displayId ?: -1
        if (id != _displayId.value) {
            _displayId.value = id
            _tasks.value = emptyList(); _tasksAvailable.value = true; _lastError.value = null
            showDashboard()
            if (id < 0) { _launcherOnTop.value = false; _recentsRequest.value = false }
        }
    }

    private val _appTarget = MutableStateFlow<String?>(null)
    val appTarget: StateFlow<String?> = _appTarget.asStateFlow()
    private val _recentAppTargets = MutableStateFlow<List<String>>(emptyList())
    val recentAppTargets: StateFlow<List<String>> = _recentAppTargets.asStateFlow()
    fun openApp(target: String): Boolean {
        if (!active || target.isBlank()) return false
        _appTarget.value = target
        _recentAppTargets.value = CarLauncherLogic.pushRecent(_recentAppTargets.value, target)
        return true
    }
    fun showDashboard() { _appTarget.value = null }
    /** Normal launches are selections, not helper LAUNCH on the outer display. */
    fun launch(target: String): Boolean = openApp(target)
    fun panelTouch(id: Int, contacts: List<HelperProtocol.TouchContact>): Boolean = client?.panelTouch(id, contacts) ?: false
    fun panelLaunch(id: Int, target: String): Boolean = client?.panelLaunch(id, target) ?: false
    fun panelBack(id: Int): Boolean = client?.panelBack(id) ?: false
    fun goHome(): Boolean = client?.goHome() ?: false
    fun listTasks(max: Int = 12): Boolean = client?.listTasks(max) ?: false
    fun moveTaskToFront(taskId: Int): Boolean = client?.moveTaskToFront(taskId) ?: false
}
