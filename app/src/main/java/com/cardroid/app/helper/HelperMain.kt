package com.cardroid.app.helper

import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import com.cardroid.app.helper.activity.ActivityLauncher
import com.cardroid.app.helper.activity.TaskManager
import com.cardroid.app.helper.display.DisplayEncoder
import com.cardroid.app.helper.display.HelperDisplay
import com.cardroid.app.helper.display.SelfTest
import com.cardroid.app.helper.input.InputInjector
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Car-display helper entry. Runs under app_process as the shell UID, no Context:
 *   CLASSPATH=<base.apk> app_process /system/bin --nice-name=cardroid-helper com.cardroid.app.helper.HelperMain --port=27186 --app-uid=<uid>
 * Protocol v6 (v3 input injection onto the car display; v4 LAUNCH/GO_HOME/LIST_TASKS/MOVE_TASK_TO_FRONT through helper/activity): one CONTROL slot (HELLO/PING/display commands) and one VIDEO slot (accepted only while CONTROL is connected, UID-checked).
 * Diagnostic: `--self-test=<w>x<h>@<fps>:<dpi>` runs the display + encoder without sockets (see SelfTest).
 */
object HelperMain {
    private const val TAG = "CarDroidHelper"
    private const val SLOW_WRITE_MS = 200L
    private const val VIDEO_QUEUE = 4
    private val lock = Any()
    private var controlSocket: Socket? = null
    private var controlOut: OutputStream? = null
    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var display: HelperDisplay? = null
    private var sink: DisplaySink? = null
    private val injector = InputInjector(::log)
    private val launcher = ActivityLauncher(::log)
    private val taskManager = TaskManager(::log)
    private var touchLogs = 0
    private var keyLogs = 0
    private var lastInputError = 0L

    private fun log(msg: String) { println("CarDroidHelper: $msg"); Log.i(TAG, msg) }

    @JvmStatic
    fun main(args: Array<String>) {
        var port = HelperProtocol.PORT
        var appUid = -1
        var selfTest: String? = null
        var selfTestInput: String? = null
        for (a in args) when {
            a.startsWith("--self-test-input=") -> selfTestInput = a.substring(18)
            a.startsWith("--self-test=") -> selfTest = a.substring(12)
            a.startsWith("--port=") -> port = a.substring(7).toIntOrNull() ?: port
            a.startsWith("--app-uid=") -> appUid = a.substring(10).toIntOrNull() ?: -1
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        if (selfTestInput != null) { SelfTest.runInput(selfTestInput, ::log); return }
        if (selfTest != null) { SelfTest.run(selfTest, ::log); return }
        if (args.contains("--self-test-media")) {
            com.cardroid.app.helper.display.FakeContext.get(::log)
            log("MediaState: " + HelperMediaState.snapshot().toString(Charsets.UTF_8))
            return
        }
        args.firstOrNull { it.startsWith("--media-key=") }?.let {
            com.cardroid.app.helper.display.FakeContext.get(::log)
            val key = it.substringAfter('=').toInt()
            require(key in intArrayOf(85, 86, 87, 88, 89, 90, 126, 127))
            injector.key(0, key)
            return
        }
        if (args.contains("--self-test-audio")) { HelperAudioCapture.selfTest(::log); return }
        if (appUid < 0) { log("missing or invalid --app-uid, exiting"); exitProcess(2) }
        appUidForPanels = appUid
        panelHost = PanelHost(appUid, ::log) { name, w, h, dpi, surface -> synchronized(lock) { display }?.createPanel(name, w, h, dpi, surface) }
        // ActivityThread/system context must be built on the looper thread; failures only disable displays (reported on START_DISPLAY).
        runCatching { com.cardroid.app.helper.display.FakeContext.get(::log) }.onFailure { log("display bootstrap failed: ${it.javaClass.simpleName}: ${it.message}") }
        val server = try { ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) {
            log("cannot bind 127.0.0.1:$port (${e.javaClass.simpleName}: ${e.message}); another helper running? exiting"); exitProcess(3)
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            log("shutdown (signal)")
            runCatching { stopDisplay(HelperProtocol.STOP_CONTROL_LOST, notify = false) }
            runCatching { controlSocket?.close() }; runCatching { videoSocket?.close() }
        })
        log("started pid=${Process.myPid()} uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} port=$port appUid=$appUid proto=${HelperProtocol.PROTOCOL_VERSION}")
        while (true) {
            val s = try { server.accept() } catch (e: Exception) { log("accept failed: ${e.message}"); exitProcess(4) }
            Thread({ handle(s, port, appUid) }, "helper-conn").apply { isDaemon = true }.start()
        }
    }

    private fun bye(out: OutputStream, reason: Int) { runCatching { HelperProtocol.write(out, HelperProtocol.T_BYE, HelperProtocol.bye(reason)) } }

    private fun handle(s: Socket, listenPort: Int, appUid: Int) {
        var role = -1
        var owner = false
        try {
            s.tcpNoDelay = true
            s.soTimeout = HelperProtocol.TIMEOUT_MS
            val out = s.getOutputStream()
            val uid = PeerUid.lookup(s.port, listenPort)
            if (uid != appUid) {
                log("rejected peer port=${s.port} uid=$uid (expected $appUid)")
                bye(out, HelperProtocol.BYE_UID)
                return
            }
            val input = s.getInputStream()
            val first = HelperProtocol.read(input)
            if (first.type != HelperProtocol.T_HELLO) { bye(out, HelperProtocol.BYE_PROTOCOL); return }
            val hello = HelperProtocol.parseHello(first.payload)
            HelperProtocol.checkHello(hello)?.let {
                log("rejected hello: proto=${hello.protoVersion} (helper ${HelperProtocol.PROTOCOL_VERSION})")
                bye(out, it); return
            }
            role = hello.role
            when (role) {
                HelperProtocol.ROLE_CONTROL -> {
                    synchronized(lock) {
                        if (controlSocket != null) { bye(out, HelperProtocol.BYE_BUSY); return }
                        controlSocket = s; controlOut = out; owner = true
                    }
                    controlWrite(HelperProtocol.T_HELLO_ACK, HelperProtocol.helloAck(Process.myPid(), Build.VERSION.SDK_INT))
                    log("client connected uid=$uid apkVersion=${hello.apkVersionCode} role=CONTROL")
                    panelHost?.let { host -> Thread({ host.attachToApp() }, "helper-panel-attach").apply { isDaemon = true }.start() }
                    controlLoop(input)
                }
                HelperProtocol.ROLE_AUDIO -> {
                    synchronized(lock) {
                        if (controlSocket == null || audioSocket != null) { bye(out, HelperProtocol.BYE_BUSY); return }
                        audioSocket = s; owner = true
                    }
                    HelperProtocol.write(out, HelperProtocol.T_HELLO_ACK, HelperProtocol.helloAck(Process.myPid(), Build.VERSION.SDK_INT))
                    HelperAudioCapture.stream(s, ::log)
                }
                HelperProtocol.ROLE_VIDEO -> {
                    synchronized(lock) {
                        if (controlSocket == null || videoSocket != null) { bye(out, HelperProtocol.BYE_BUSY); return }
                        videoSocket = s; owner = true
                    }
                    HelperProtocol.write(out, HelperProtocol.T_HELLO_ACK, HelperProtocol.helloAck(Process.myPid(), Build.VERSION.SDK_INT))
                    log("client connected uid=$uid apkVersion=${hello.apkVersionCode} role=VIDEO")
                    s.soTimeout = 0 // VIDEO is exempt from the heartbeat timeout; loss is detected by EOF/reset
                    synchronized(lock) { sink?.attach(s) }
                    while (true) {
                        val f = HelperProtocol.read(input)
                        if (f.type == HelperProtocol.T_BYE) { log("video client said bye"); return }
                    }
                }
                else -> bye(out, HelperProtocol.BYE_PROTOCOL)
            }
        } catch (e: SocketTimeoutException) { log("client heartbeat timeout (role=$role)")
        } catch (e: Exception) { log("client ended (role=$role): ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { s.close() }
            if (owner) {
                if (role == HelperProtocol.ROLE_CONTROL) {
                    synchronized(lock) { if (controlSocket === s) { controlSocket = null; controlOut = null } }
                    synchronized(lock) { runCatching { audioSocket?.close() } }
                    synchronized(lock) {
                        panelInjectors.forEach { (id, input) -> input.releaseAll(id) }
                        panelInjectors.clear()
                    }
                    stopDisplay(HelperProtocol.STOP_CONTROL_LOST, notify = false)
                    synchronized(lock) { runCatching { videoSocket?.close() } }
                    log("client disconnected (CONTROL)")
                } else if (role == HelperProtocol.ROLE_AUDIO) {
                    synchronized(lock) { if (audioSocket === s) audioSocket = null }
                } else if (role == HelperProtocol.ROLE_VIDEO) {
                    val wasCurrent = synchronized(lock) { if (videoSocket === s) { videoSocket = null; true } else false }
                    if (wasCurrent) { stopDisplay(HelperProtocol.STOP_VIDEO_LOST, notify = true) }
                    log("client disconnected (VIDEO)")
                }
            }
        }
    }

    private fun controlWrite(type: Int, payload: ByteArray = ByteArray(0)) {
        synchronized(lock) {
            val out = controlOut ?: return
            try { HelperProtocol.write(out, type, payload) } catch (e: Exception) { runCatching { controlSocket?.close() } }
        }
    }

    private fun controlLoop(input: InputStream) {
        while (true) {
            val f = HelperProtocol.read(input)
            when (f.type) {
                HelperProtocol.T_PING -> {
                    controlWrite(HelperProtocol.T_PONG)
                    runCatching { HelperMediaState.snapshot() }
                        .onSuccess { controlWrite(HelperProtocol.T_MEDIA_STATE, it) }
                        .onFailure { log("MediaState: unavailable ${it.javaClass.simpleName}: ${it.message}") }
                }
                HelperProtocol.T_BYE -> { log("client said bye"); return }
                HelperProtocol.T_START_DISPLAY -> startDisplay(f.payload)
                HelperProtocol.T_STOP_DISPLAY -> stopDisplay(HelperProtocol.STOP_REQUESTED, notify = true)
                HelperProtocol.T_REQUEST_KEYFRAME -> synchronized(lock) { display?.requestKeyFrame() }
                HelperProtocol.T_TOUCH_STATE -> handleTouch(f.payload)
                HelperProtocol.T_KEY -> handleKey(f.payload)
                HelperProtocol.T_PANEL_TOUCH, HelperProtocol.T_PANEL_KEY, HelperProtocol.T_PANEL_LAUNCH -> handlePanel(f.type, f.payload)
                HelperProtocol.T_LAUNCH -> handleLaunch(f.payload)
                HelperProtocol.T_GO_HOME -> handleGoHome()
                HelperProtocol.T_LIST_TASKS -> handleListTasks(f.payload)
                HelperProtocol.T_MOVE_TASK_TO_FRONT -> handleMoveTask(f.payload)
                else -> {
                    log("unknown CONTROL type ${f.type}")
                    controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_UNKNOWN_TYPE, "unknown type ${f.type}"))
                }
            }
        }
    }

    private val panelInjectors = mutableMapOf<Int, InputInjector>()
    private val panelPackages = mutableMapOf<Int, String>()

    private fun refreshPanels() {
        // dumpsys is available to shell and includes app-owned private displays that
        // DisplayManager.getDisplay filters out. Run only for launches, never per touch.
        val process = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "display").redirectErrorStream(true).start()
        val dump = try { process.inputStream.bufferedReader().use { it.readText() } }
            finally { process.destroy() }
        // App-owned panels (fallback) plus the helper's own virtual-device panels.
        val owned = PanelDisplayInfo.ownedPanels(dump, appUidForPanels) + (panelHost?.packages() ?: emptyMap())
        panelPackages.clear()
        panelPackages.putAll(owned)
        panelInjectors.keys.retainAll(owned.keys)
    }
    private var appUidForPanels = -1
    private var panelHost: PanelHost? = null
    private fun handlePanel(type: Int, payload: ByteArray) {
        try {
            val (id, body) = HelperProtocol.parsePanelCommand(payload)
            val context = com.cardroid.app.helper.display.FakeContext.get(::log)
            if (type == HelperProtocol.T_PANEL_LAUNCH) refreshPanels()
            val expected = panelPackages[id]
            if (expected == null && type == HelperProtocol.T_PANEL_TOUCH && HelperProtocol.parseTouchState(body).isEmpty()) return
            check(expected != null) { "panel is unavailable or not owned by CarDroid" }
            val input = panelInjectors.getOrPut(id) { InputInjector(::log) }
            if (type == HelperProtocol.T_PANEL_TOUCH) {
                val contacts = HelperProtocol.parseTouchState(body)
                input.touch(id, contacts)
            } else if (type == HelperProtocol.T_PANEL_LAUNCH) {
                val request = HelperProtocol.parseLaunch(body)
                check(request.target.substringBefore('/') == expected && request.flags == 0) { "panel app mismatch" }
                val component = android.content.ComponentName.unflattenFromString(request.target) ?: error("invalid panel app")
                val launchable = context.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(expected), 0)
                check(expected != "com.cardroid.app" && launchable.any { it.activityInfo.name == component.className }) { "not a launchable panel app" }
                launcher.launch(id, request.target, separateTask = true)
            } else {
                val code = HelperProtocol.parseKey(body)
                check(code == 4) { "panel key must be Back" }
                input.key(id, code)
            }
        } catch (t: Throwable) { inputError(HelperProtocol.E_INPUT_FAILED, "Native panel: ${t.message}") }
    }

    /** Rate-limited (one per 2 s) ERROR for input problems so a flooding sender cannot flood the CONTROL socket back. */
    private fun inputError(code: Int, msg: String) {
        val now = System.nanoTime()
        if (now - lastInputError < 2_000_000_000L && lastInputError != 0L) return
        lastInputError = now
        controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(code, msg))
    }

    private fun handleTouch(payload: ByteArray) {
        val contacts = try { HelperProtocol.parseTouchState(payload) } catch (e: HelperProtocol.ProtocolException) { inputError(HelperProtocol.E_BAD_REQUEST, e.message ?: "bad TOUCH_STATE"); return }
        // held under `lock` so a concurrent stopDisplay cannot release the display between the check and the injection
        synchronized(lock) {
            val id = display?.displayId ?: -1
            if (id < 0) { inputError(HelperProtocol.E_NO_DISPLAY, "TOUCH_STATE without active display"); return }
            try {
                injector.touch(id, contacts)
                if (touchLogs++ < 5 || touchLogs % 100 == 0) log("Input: touch ${contacts.size} contacts on display $id (#$touchLogs)")
            } catch (t: Throwable) {
                log("Input: touch failed ${t.javaClass.simpleName}: ${t.message}")
                runCatching { injector.releaseAll(id) }
                inputError(HelperProtocol.E_INPUT_FAILED, "touch: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun handleKey(payload: ByteArray) {
        val key = try { HelperProtocol.parseKey(payload) } catch (e: HelperProtocol.ProtocolException) { inputError(HelperProtocol.E_BAD_REQUEST, e.message ?: "bad KEY"); return }
        synchronized(lock) {
            val id = display?.displayId ?: -1
            if (id < 0) { inputError(HelperProtocol.E_NO_DISPLAY, "KEY without active display"); return }
            try {
                injector.key(id, key)
                if (keyLogs++ < 5 || keyLogs % 100 == 0) log("Input: key $key on display $id (#$keyLogs)")
            } catch (t: Throwable) {
                log("Input: key $key failed ${t.javaClass.simpleName}: ${t.message}")
                inputError(HelperProtocol.E_INPUT_FAILED, "key $key: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun activeDisplayId(what: String): Int {
        val id = synchronized(lock) { display?.displayId ?: -1 }
        if (id < 0) controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_NO_DISPLAY, "$what without active display"))
        return id
    }

    private fun handleLaunch(payload: ByteArray) {
        val req = try { HelperProtocol.parseLaunch(payload) } catch (e: HelperProtocol.ProtocolException) {
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_BAD_REQUEST, e.message ?: "bad LAUNCH")); return
        }
        val id = activeDisplayId("LAUNCH"); if (id < 0) return
        try { launcher.launch(id, req.target, req.flags) } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            log("Activity: launch '${req.target}' failed ${c.javaClass.simpleName}: ${c.message}")
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_LAUNCH_FAILED, "launch ${req.target}: ${c.javaClass.simpleName}: ${c.message}"))
        }
    }

    private fun handleGoHome() {
        val id = activeDisplayId("GO_HOME"); if (id < 0) return
        goHome(id)
    }

    private fun goHome(id: Int) {
        try { launcher.goHome(id) } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            log("Activity: goHome failed ${c.javaClass.simpleName}: ${c.message}")
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_LAUNCH_FAILED, "goHome: ${c.javaClass.simpleName}: ${c.message}"))
        }
    }

    private fun handleListTasks(payload: ByteArray) {
        val max = try { HelperProtocol.parseListTasks(payload) } catch (e: HelperProtocol.ProtocolException) {
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_BAD_REQUEST, e.message ?: "bad LIST_TASKS")); return
        }
        val id = activeDisplayId("LIST_TASKS"); if (id < 0) return
        try { controlWrite(HelperProtocol.T_TASKS, HelperProtocol.tasks(taskManager.list(id, max))) } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            log("Tasks: list failed ${c.javaClass.simpleName}: ${c.message}")
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_TASKS_FAILED, "list: ${c.javaClass.simpleName}: ${c.message}"))
        }
    }

    private fun handleMoveTask(payload: ByteArray) {
        val taskId = try { HelperProtocol.parseMoveTaskToFront(payload) } catch (e: HelperProtocol.ProtocolException) {
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_BAD_REQUEST, e.message ?: "bad MOVE_TASK_TO_FRONT")); return
        }
        if (activeDisplayId("MOVE_TASK_TO_FRONT") < 0) return
        try { taskManager.moveToFront(taskId) } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            log("Tasks: move $taskId failed ${c.javaClass.simpleName}: ${c.message}")
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_TASKS_FAILED, "move $taskId: ${c.javaClass.simpleName}: ${c.message}"))
        }
    }

    private fun startDisplay(payload: ByteArray) {
        val req = try { HelperProtocol.parseStartDisplay(payload) } catch (e: HelperProtocol.ProtocolException) {
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_BAD_REQUEST, e.message ?: "bad START_DISPLAY")); return
        }
        synchronized(lock) { if (display != null) { controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_BUSY, "display already active")); return } }
        log("START_DISPLAY ${req.width}x${req.height}/${req.dpi} fps=${req.fps} bitrate=${req.bitrate}")
        val sk = DisplaySink()
        val d = HelperDisplay(req.width, req.height, req.dpi, req.fps, req.bitrate, ::log, sk)
        sk.requestKey = { d.requestKeyFrame() }
        try {
            synchronized(lock) { display = d; sink = sk }
            val id = d.start()
            synchronized(lock) { videoSocket?.let { sk.attach(it) } }
            controlWrite(HelperProtocol.T_DISPLAY_STARTED, HelperProtocol.displayStarted(id, req.width, req.height, d.activeFps))
            goHome(id) // v4: the CarDroid car launcher is the first thing on the car display
        } catch (t: Throwable) {
            log("display start failed ${t.javaClass.name}: ${t.message}")
            synchronized(lock) { if (display === d) { display = null; sink = null } }
            runCatching { d.release() }; sk.close()
            controlWrite(HelperProtocol.T_ERROR, HelperProtocol.error(HelperProtocol.E_DISPLAY_FAILED, "${t.javaClass.simpleName}: ${t.message}"))
        }
    }

    private fun stopDisplay(reason: Int, notify: Boolean) {
        synchronized(lock) { runCatching { audioSocket?.close() } }
        val (d, sk) = synchronized(lock) { val pair = display to sink; display = null; sink = null; pair }
        if (d == null) return
        runCatching { injector.releaseAll(d.displayId) } // lift active pointers before the display disappears
        panelHost?.releaseAll("display stopped") // panels live on the display's virtual device
        runCatching { d.release() }; sk?.close()
        log("display stopped reason=$reason")
        if (notify) controlWrite(HelperProtocol.T_DISPLAY_STOPPED, HelperProtocol.displayStopped(reason))
    }

    /** Bridges encoder output to the VIDEO connection; caches the config so a late-attaching VIDEO socket still gets it first. */
    private class DisplaySink : DisplayEncoder.Sink {
        @Volatile var requestKey: () -> Unit = {}
        @Volatile private var sender: VideoSender? = null
        @Volatile private var config: ByteArray? = null
        @Volatile private var closed = false

        fun attach(sock: Socket) {
            if (closed) return
            sender?.close()
            val vs = VideoSender(sock, requestKey)
            sender = vs
            config?.let { vs.setConfig(it) }
            requestKey() // new viewer needs an IDR; frames before it are dropped
        }

        fun close() { closed = true; sender?.close(); sender = null }

        override fun onConfig(sps: ByteArray, pps: ByteArray) {
            val c = HelperProtocol.encode(HelperProtocol.T_VIDEO_CONFIG, HelperProtocol.videoConfig(sps, pps), HelperProtocol.MAX_PAYLOAD_VIDEO)
            config = c; sender?.setConfig(c)
        }

        override fun onFrame(data: ByteArray, ptsUs: Long, keyFrame: Boolean) { sender?.frame(data, ptsUs, keyFrame) }

        override fun onFailure(reason: String) {
            log("encoder failure: $reason")
            stopDisplay(HelperProtocol.STOP_ENCODER_FAILED, notify = true)
        }
    }

    /** Writer thread + bounded queue: if a write stalls beyond SLOW_WRITE_MS or the queue fills, drop frames until the next keyframe. */
    private class VideoSender(private val sock: Socket, private val requestKey: () -> Unit) {
        private val queue = ArrayBlockingQueue<ByteArray>(VIDEO_QUEUE)
        private val pendingConfig = AtomicReference<ByteArray?>(null)
        @Volatile private var writeStart = 0L
        @Volatile private var dropUntilKey = true
        @Volatile private var closed = false
        private var dropped = 0
        private var lastDropLog = 0L
        private val thread = Thread({ run() }, "helper-video-tx").apply { isDaemon = true; start() }

        fun setConfig(encoded: ByteArray) { pendingConfig.set(encoded) }

        fun frame(data: ByteArray, ptsUs: Long, key: Boolean) {
            if (closed) return
            if (dropUntilKey) { if (!key) return; dropUntilKey = false }
            val ws = writeStart
            val stalled = ws != 0L && System.nanoTime() - ws > SLOW_WRITE_MS * 1_000_000L
            val bytes = HelperProtocol.encode(HelperProtocol.T_VIDEO_FRAME, HelperProtocol.videoFrame(ptsUs, key, data), HelperProtocol.MAX_PAYLOAD_VIDEO)
            if (stalled || !queue.offer(bytes)) {
                queue.clear(); dropUntilKey = true; dropped++
                val now = System.nanoTime()
                if (now - lastDropLog > 2_000_000_000L) { lastDropLog = now; log("video write stalled/backlog: dropping until next keyframe (dropped=$dropped)") }
                requestKey()
            }
        }

        private fun run() {
            try {
                val out = sock.getOutputStream()
                while (!closed) {
                    val f = queue.poll(50, TimeUnit.MILLISECONDS)
                    // config is re-checked after the poll so it always precedes the first frame
                    val cfg = pendingConfig.getAndSet(null)
                    if (cfg != null) timed { out.write(cfg); out.flush() }
                    if (f != null) timed { out.write(f); out.flush() }
                }
            } catch (e: Exception) { if (!closed) { log("video writer ended: ${e.javaClass.simpleName}: ${e.message}"); runCatching { sock.close() } } }
        }

        private inline fun timed(block: () -> Unit) { writeStart = System.nanoTime(); try { block() } finally { writeStart = 0L } }

        fun close() { closed = true; queue.clear() }
    }
}

/** Maps a loopback client port to the owning UID via /proc/net/tcp{,6} (readable by shell). */
object PeerUid {
    fun lookup(peerPort: Int, listenPort: Int): Int {
        for (attempt in 0 until 3) {
            for (f in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
                val uid = runCatching { parse(File(f).inputStream(), peerPort, listenPort) }.getOrNull()
                if (uid != null) return uid
            }
            Thread.sleep(50)
        }
        return -1
    }

    /** Finds the client-side row: local 127.0.0.1:peerPort -> remote 127.0.0.1:listenPort. */
    fun parse(input: InputStream, peerPort: Int, listenPort: Int): Int? = input.bufferedReader().useLines { lines ->
        for (line in lines.drop(1)) {
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 8) continue
            val l = c[1].split(':'); val r = c[2].split(':')
            if (l.size != 2 || r.size != 2) continue
            if (l[1].toIntOrNull(16) == peerPort && r[1].toIntOrNull(16) == listenPort &&
                l[0].endsWith("0100007F") && r[0].endsWith("0100007F")) return@useLines c[7].toIntOrNull()
        }
        null
    }
}
