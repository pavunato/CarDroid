package com.cardroid.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.cardroid.lib.airplay.RemoteTouch
import java.util.ArrayDeque

/** Gesture-only accessibility bridge. Receiver input is gated by an active capture. */
class RemoteControlService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteControlService?=null
        @Volatile private var sharing=false
        @Volatile private var hidRegistry: com.cardroid.lib.airplay.HidDeviceRegistry? = null
        @Volatile private var hidGeneration=0L
        val enabled=kotlinx.coroutines.flow.MutableStateFlow(false)
        fun setSharing(active: Boolean) {
            sharing=active
            if (!active) instance?.handler?.post { instance?.releaseTouches() }
        }
        /** Replaced for every media session; never retain a prior receiver's HID UUIDs. */
        fun setHidRegistry(registry: com.cardroid.lib.airplay.HidDeviceRegistry?) {
            hidRegistry = registry
            hidGeneration++
        }
        fun receive(command: Map<String,Any?>, receiverWidth: Int, receiverHeight: Int, canvasWidth: Int, canvasHeight: Int) {
            if (!sharing || command["type"] != "hidSendReport") return
            val service=instance ?: return
            val report=command["hidReport"] as? ByteArray ?: return
            val registry=hidRegistry
            val generation=hidGeneration
            service.handler.post {
                if (!sharing) return@post
                // Do not let a queued callback from a prior media session use
                // its old registry after a reconnect.
                if (generation != hidGeneration || registry !== hidRegistry) return@post
                if (registry != null) {
                    val (w,h)=sourceSize(service)
                    val result=registry.decodeMirror(command["uuid"], report, receiverWidth, receiverHeight, canvasWidth, canvasHeight, w, h)
                    registry.note("decoded", result, report.size)
                    when (result) {
                        is com.cardroid.lib.airplay.HidDeviceRegistry.DecodeResult.Touch -> {
                            service.enqueue(result.contacts)
                            registry.note("injected", result, report.size)
                        }
                        is com.cardroid.lib.airplay.HidDeviceRegistry.DecodeResult.Button -> {
                            if (result.bits and 2 != 0) {
                                service.performGlobalAction(GLOBAL_ACTION_HOME)
                                registry.note("injected", result, report.size)
                            } else if (result.bits and 4 != 0) {
                                service.performGlobalAction(GLOBAL_ACTION_BACK)
                                registry.note("injected", result, report.size)
                            }
                        }
                        else -> Unit
                    }
                } else {
                    // Legacy TestReceiver aliases are retained only when no /info
                    // registry was decoded. Receiver UUIDs are not lowercased.
                    val uuid=command["uuid"] as? String
                    if (uuid?.equals("2a2a2a2a", ignoreCase=true)==true) {
                        val (w,h)=sourceSize(service)
                        runCatching { RemoteTouch.decode(report,receiverWidth,receiverHeight,canvasWidth,canvasHeight,w,h) }
                            .onSuccess { service.enqueue(it) }.onFailure { CarDroidLog.log("Touch: invalid report: ${it.message}") }
                    } else if (uuid?.equals("2a2a2a2b", ignoreCase=true)==true && report.size>=4) {
                        val bits=report[0].toInt()
                        if (bits and 2 != 0) service.performGlobalAction(GLOBAL_ACTION_HOME)
                        else if (bits and 4 != 0) service.performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
        }
        private fun sourceSize(context: android.content.Context): Pair<Int,Int> {
            val wm=context.getSystemService(android.view.WindowManager::class.java)
            if (android.os.Build.VERSION.SDK_INT>=30) return wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            val m=android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            return m.widthPixels to m.heightPixels
        }
    }
    private val handler=Handler(Looper.getMainLooper())
    private data class Held(val stroke: GestureDescription.StrokeDescription,val x: Float,val y: Float)
    private data class Sample(val contacts: List<RemoteTouch.Contact>,val edge: Boolean)
    private val pending=ArrayDeque<Sample>()
    private var active=mapOf<Int,Held>()
    private var lastIds=setOf<Int>()
    private var busy=false
    // Legacy mirroring bridge only. The helper launcher owns its persistent navigation rail.
    override fun onServiceConnected() { instance=this; enabled.value=true; CarDroidLog.log("Touch: accessibility bridge connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { releaseTouches() }
    override fun onDestroy() { releaseTouches(); instance=null; hidRegistry=null; enabled.value=false; super.onDestroy() }
    private fun enqueue(contacts: List<RemoteTouch.Contact>) {
        val ids=contacts.filter { it.down }.map { it.id }.toSet()
        val edge=ids!=lastIds
        lastIds=ids
        if (!edge && pending.peekLast()?.edge==false) pending.removeLast()
        pending.addLast(Sample(contacts,edge))
        if (pending.size>128) { pending.clear(); releaseTouches(); return }
        pump()
    }
    private fun releaseTouches() {
        pending.clear();lastIds=emptySet()
        pending.addLast(Sample(emptyList(),true));pump()
    }
    private fun pump() {
        if (busy || pending.isEmpty()) return
        val contacts=pending.removeFirst().contacts.filter { it.down }.associateBy { it.id }
        val ids=active.keys+contacts.keys
        if (ids.isEmpty()) { pump();return }
        val (sw,sh)=sourceSize(this)
        val gesture=GestureDescription.Builder()
        val next=mutableMapOf<Int,Held>()
        val kinds=mutableListOf<String>()
        for (id in ids) {
            val old=active[id];val contact=contacts[id]
            val sx=old?.x ?: contact!!.x;val sy=old?.y ?: contact!!.y
            var x=contact?.x ?: sx;var y=contact?.y ?: sy
            // Zero-length segments get cancelled by MotionEventInjector on some builds; nudge 1px, keeping the start exactly at the previous end.
            if (x==sx && y==sy) { if (sx+1f<=sw-1f) x=sx+1f else if (sx-1f>=0f) x=sx-1f else if (sy+1f<=sh-1f) y=sy+1f else y=sy-1f }
            val path=Path().apply { moveTo(sx,sy);lineTo(x,y) }
            val continuing=contact!=null
            val stroke=old?.stroke?.continueStroke(path,0,16,continuing)
                ?: GestureDescription.StrokeDescription(path,0,16,continuing)
            gesture.addStroke(stroke)
            if (continuing) next[id]=Held(stroke,x,y)
            kinds+="$id:${if (!continuing) "end" else if (old==null) "new" else "cont"} len=${"%.1f".format(Math.hypot((x-sx).toDouble(),(y-sy).toDouble()))}"
        }
        val summary=kinds.joinToString(",")
        busy=true
        val sent=dispatchGesture(gesture.build(),object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                active=next;busy=false
                if (pending.isEmpty() && next.isEmpty()) CarDroidLog.log("Touch: gesture completed")
                pump()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                active=emptyMap();pending.clear();lastIds=emptySet();busy=false
                CarDroidLog.log("Touch: gesture cancelled (strokes=[$summary])")
            }
        },handler)
        if (!sent) { active=emptyMap();pending.clear();busy=false;CarDroidLog.log("Touch: Android rejected gesture (strokes=[$summary])") }
    }
}
