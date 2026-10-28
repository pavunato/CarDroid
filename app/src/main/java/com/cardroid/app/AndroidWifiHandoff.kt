package com.cardroid.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.cardroid.app.net.SubnetMatch
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.cardroid.lib.WifiHandoff

/**
 * WifiHandoff backed by WifiNetworkSpecifier.
 * CarDroid poses as the iPhone, so it joins the accessory/head-unit AP after
 * the AccessoryWiFiConfigurationInformation control message arrives.
 */
class AndroidWifiHandoff(
    context: Context,
    private val log: (String) -> Unit,
    private val onDiagnosticIssue: (String) -> Unit = {},
) : WifiHandoff {

    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var generation = 0
    @Volatile private var joinedNetwork: Network? = null
    @Volatile private var joinedInterfaceName: String? = null
    @Volatile private var networkReadyHook: ((Any?) -> Unit)? = null

    // Join decision state machine. All transitions happen under [lock]; [generation] invalidates stale work.
    private enum class State { IDLE, DETECTING, PENDING_DECISION, TESTING, JOINING, JOINED }
    private val lock = Any()
    private var state = State.IDLE
    private var pendingCreds: WifiHandoff.NetworkInfo? = null
    /** Last car Wi-Fi credentials from 0x5703, for rejoining when a later 0x4301 finds the network gone. */
    @Volatile private var lastAccessory: WifiHandoff.NetworkInfo? = null
    private var pendingSeen: String? = null
    private var earlyOffer: Pair<List<String>, Int>? = null
    private var decisionTimer: Runnable? = null
    private var testSocket: Socket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onNetworkReady(callback: (Any?) -> Unit) {
        networkReadyHook = callback
        joinedNetwork?.let { callback(it) }
    }

    /**
     * Name of the local network interface (e.g. "wlan0", "wlan1", "swlan0") on
     * which the accessory AP was joined. Used to compute the correct IPv6 zone
     * id when connecting to the car's link-local address. Null until we get
     * `onLinkPropertiesChanged`.
     */
    fun getJoinedInterfaceName(): String? = joinedInterfaceName

    /** The accessory `Network` once joined, or null. */
    fun getJoinedNetwork(): Network? = joinedNetwork

    @SuppressLint("MissingPermission")
    override fun connectToAccessory(network: WifiHandoff.NetworkInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onDiagnosticIssue("WIFI_UNAVAILABLE")
            log("WiFi handoff received for '${network.ssid}', but WifiNetworkSpecifier requires Android 10+")
            return
        }

        lastAccessory = network
        tearDown()
        val gen = generation
        synchronized(lock) { state = State.DETECTING }

        // Active-network detection waits up to ~500 ms; never block the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread({ connectOrReuse(network, gen) }, "cardroid-wifi-detect").start()
        } else {
            connectOrReuse(network, gen)
        }
    }

    private fun normSsid(s: String?): String? = s?.trim()?.removeSurrounding("\"")

    /** Records [net] as the joined accessory network (shared by reuse and specifier paths). */
    private fun markJoined(net: Network, ssid: String, iface: String?) {
        connectivity.bindProcessToNetwork(net)
        synchronized(lock) { state = State.JOINED }
        joinedNetwork = net
        if (iface != null) joinedInterfaceName = iface
        log("Connected to accessory WiFi '$ssid' (network=$net)")
        networkReadyHook?.invoke(net)
    }

    /** Finds the active Wi-Fi network already associated to [ssid]; returns it plus the SSID seen. */
    @SuppressLint("MissingPermission")
    private fun findActiveWifi(ssid: String): Pair<Network?, String?> {
        var seen: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // SSID is redacted in plain NetworkCapabilities; needs FLAG_INCLUDE_LOCATION_INFO via a callback.
            val latch = CountDownLatch(1)
            var found: Network? = null
            val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    val cur = normSsid((caps.transportInfo as? WifiInfo)?.ssid)
                    if (cur != null && cur != WifiManager.UNKNOWN_SSID) seen = cur
                    if (cur == ssid && found == null) { found = n; latch.countDown() }
                }
            }
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            runCatching {
                connectivity.registerNetworkCallback(req, cb)
                latch.await(500, TimeUnit.MILLISECONDS)
            }
            runCatching { connectivity.unregisterNetworkCallback(cb) }
            if (found != null) return found to ssid
        }
        // Fallback (any SDK): legacy connectionInfo / transportInfo on the active Wi-Fi network.
        @Suppress("DEPRECATION")
        val legacy = normSsid(runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull())
        @Suppress("DEPRECATION")
        for (n in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val cur = normSsid((caps.transportInfo as? WifiInfo)?.ssid)?.takeIf { it != WifiManager.UNKNOWN_SSID }
                ?: legacy?.takeIf { it != WifiManager.UNKNOWN_SSID }
            if (cur != null) seen = cur
            if (cur == ssid) return n to cur
        }
        return null to seen
    }

    @SuppressLint("MissingPermission")
    private fun connectOrReuse(network: WifiHandoff.NetworkInfo, gen: Int) {
        val ssid = normSsid(network.ssid) ?: network.ssid
        val (active, seen) = runCatching { findActiveWifi(ssid) }
            .onFailure { log("WiFi: active-network check failed: ${it.message}") }
            .getOrDefault(null to null)
        if (gen != generation) return // superseded by tearDown()/newer connect
        if (active != null) {
            val iface = connectivity.getLinkProperties(active)?.interfaceName
            log("WiFi: already connected to '$ssid' (network=$active, iface=$iface); using active network, no join request (requested bssid='${network.bssid}')")
            registerReuseCallback(active, ssid)
            markJoined(active, ssid, iface)
            return
        }
        val early: Pair<List<String>, Int>?
        synchronized(lock) {
            if (gen != generation) return
            state = State.PENDING_DECISION
            pendingCreds = network
            pendingSeen = seen
            early = earlyOffer.also { earlyOffer = null }
            val t = Runnable { onDecisionTimeout(gen) }
            decisionTimer = t
            mainHandler.postDelayed(t, DECISION_MS)
        }
        log("WiFi: not on '$ssid' (current='${seen ?: "unknown"}'); waiting <=1s for receiver address before joining")
        early?.let { offerReceiverAddresses(it.first, it.second) }
    }

    private fun onDecisionTimeout(gen: Int) {
        val creds = synchronized(lock) {
            if (gen != generation || state != State.PENDING_DECISION) return
            state = State.JOINING
            decisionTimer = null
            pendingCreds
        } ?: return
        log("WiFi: no reachable receiver address within ${DECISION_MS}ms; requesting join via WifiNetworkSpecifier (Android may ask for approval once)")
        requestJoin(creds)
    }

    /** Claims PENDING_DECISION (cancelling the timer) and moves to [to]; returns creds or null if not claimable. */
    private fun claim(gen: Int, from: State, to: State): WifiHandoff.NetworkInfo? = synchronized(lock) {
        if (gen != generation || state != from) return null
        decisionTimer?.let { mainHandler.removeCallbacks(it) }
        decisionTimer = null
        state = to
        pendingCreds
    }

    private fun joinNow(gen: Int, from: State, reason: String) {
        val creds = claim(gen, from, State.JOINING) ?: return
        log("WiFi: not reachable on current network: $reason; requesting join via WifiNetworkSpecifier (Android may ask for approval once)")
        requestJoin(creds)
    }

    /**
     * Called when the receiver's addresses are known but the accessory network is not joined yet.
     * If a receiver IPv4 address is reachable over the system default Wi-Fi (internet), stay on it
     * instead of joining via WifiNetworkSpecifier (which would drop the phone's internet).
     */
    @SuppressLint("MissingPermission")
    fun offerReceiverAddresses(addresses: List<String>, port: Int) {
        val gen: Int
        val seen: String?
        synchronized(lock) {
            when (state) {
                State.DETECTING -> { earlyOffer = addresses to port; return }
                State.PENDING_DECISION -> { gen = generation; seen = pendingSeen }
                else -> return
            }
        }
        val dflt = runCatching { connectivity.activeNetwork }.getOrNull()
        val caps = dflt?.let { connectivity.getNetworkCapabilities(it) }
        if (dflt == null || caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            joinNow(gen, State.PENDING_DECISION, "default network is not Wi-Fi with internet"); return
        }
        val lp = connectivity.getLinkProperties(dflt)
        val prefixes = lp?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address }
            .mapNotNull { la -> la.address.hostAddress?.let { it to la.prefixLength } }
        val target = addresses.firstOrNull { SubnetMatch.inAnyPrefix(it, prefixes) }
        if (target == null) {
            joinNow(gen, State.PENDING_DECISION, "no receiver IPv4 address in default network subnet (receiver=$addresses, local=$prefixes)"); return
        }
        if (claim(gen, State.PENDING_DECISION, State.TESTING) == null) return
        val iface = lp?.interfaceName
        Thread({
            var failure: String? = null
            val sock = Socket()
            synchronized(lock) { if (gen == generation && state == State.TESTING) testSocket = sock }
            try {
                dflt.bindSocket(sock)
                sock.connect(InetSocketAddress(target, port), TEST_MS)
            } catch (t: Throwable) {
                failure = "TCP test to $target:$port failed: ${t.message}"
            } finally {
                synchronized(lock) { if (testSocket === sock) testSocket = null }
                runCatching { sock.close() }
            }
            if (failure != null) { joinNow(gen, State.TESTING, failure); return@Thread }
            val ok = synchronized(lock) {
                if (gen != generation || state != State.TESTING) false else { state = State.JOINED; true }
            }
            if (!ok) return@Thread
            val ssid = seen ?: "unknown"
            log("WiFi: receiver $target:$port reachable on current '$ssid' - staying (internet kept)")
            registerReuseCallback(dflt, ssid)
            if (gen != generation) {
                callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }; callback = null
                return@Thread
            }
            markJoined(dflt, ssid, iface)
        }, "cardroid-wifi-reach").start()
    }

    /** Plain (non-requesting) callback for a system Wi-Fi network we reuse; never disconnects it. */
    private fun registerReuseCallback(active: Network, ssid: String) {
        val lossCb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (n == active) joinedInterfaceName = lp.interfaceName
            }

            override fun onLost(lostNetwork: Network) {
                if (callback === this && joinedNetwork == lostNetwork) {
                    onDiagnosticIssue("WIFI_LOST")
                    joinedNetwork = null
                    runCatching { connectivity.bindProcessToNetwork(null) }
                }
                log("Accessory WiFi lost: '$ssid'")
            }
        }
        callback = lossCb
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), lossCb)
    }

    /** Invalid credentials (specifier builder) or a refused request throw; report a typed failure instead of crashing. */
    private fun requestJoin(creds: WifiHandoff.NetworkInfo) {
        runCatching { requestViaSpecifier(creds) }.onFailure { t ->
            onDiagnosticIssue("WIFI_JOIN_FAILED")
            log("WiFi: join request failed: ${t.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestViaSpecifier(network: WifiHandoff.NetworkInfo) {
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(network.ssid)
        if (network.passphrase.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(network.passphrase)
        }

        // The car's CarPlay AP has no general internet. Drop INTERNET/VALIDATED
        // requirements so Android doesn't refuse to match the network. Earlier
        // we also added LOCAL_NETWORK on API 35+ but that prevented matching
        // entirely (no onAvailable callback), so we drop it.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(availableNetwork: Network) {
                markJoined(availableNetwork, network.ssid, null)
            }

            override fun onCapabilitiesChanged(
                availableNetwork: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                log("Accessory WiFi capabilities: network=$availableNetwork $networkCapabilities")
            }

            override fun onLinkPropertiesChanged(
                availableNetwork: Network,
                linkProperties: LinkProperties,
            ) {
                // Capture the local interface name so the AirPlay layer can
                // build correctly-scoped IPv6 link-local addresses. Without
                // the right scope id, connect(fe80::%xxx) returns ECONNREFUSED
                // in <1ms (kernel rejects unroutable scope).
                joinedInterfaceName = linkProperties.interfaceName
                log("Accessory WiFi link: network=$availableNetwork iface=${linkProperties.interfaceName} $linkProperties")
            }

            override fun onLosing(losingNetwork: Network, maxMsToLive: Int) {
                log("Accessory WiFi losing: '${network.ssid}' network=$losingNetwork ttl=${maxMsToLive}ms")
            }

            override fun onUnavailable() {
                // Android calls this when the user rejects the connection prompt or no matching network is found.
                if (callback === this) onDiagnosticIssue("WIFI_JOIN_FAILED")
                log("Accessory WiFi unavailable: '${network.ssid}'")
            }

            override fun onLost(lostNetwork: Network) {
                if (callback === this && joinedNetwork == lostNetwork) {
                    onDiagnosticIssue("WIFI_LOST")
                    joinedNetwork = null
                    runCatching { connectivity.bindProcessToNetwork(null) }
                }
                log("Accessory WiFi lost: '${network.ssid}'")
            }
        }

        callback = cb
        connectivity.requestNetwork(request, cb)
        log("Requested accessory WiFi '${network.ssid}' with request=$request")
    }

    /** Re-requests the last car Wi-Fi when it is no longer joined; returns false if there is nothing to rejoin. */
    fun rejoinLastIfLost(reason: String): Boolean {
        val last = lastAccessory ?: return false
        if (joinedNetwork != null) return false
        // A join still in progress will finish on its own; JOINED with no network means it was lost.
        val busy = synchronized(lock) { state in setOf(State.DETECTING, State.PENDING_DECISION, State.TESTING, State.JOINING) }
        if (busy) return true
        log("WiFi: rejoining '${last.ssid}' ($reason)")
        connectToAccessory(last)
        return true
    }

    override fun tearDown() {
        synchronized(lock) {
            generation++
            state = State.IDLE
            pendingCreds = null; pendingSeen = null; earlyOffer = null
            decisionTimer?.let { mainHandler.removeCallbacks(it) }
            decisionTimer = null
            testSocket?.let { runCatching { it.close() } }
            testSocket = null
        }
        // Only unregisters our callbacks / unbinds the process; never disconnects the system Wi-Fi
        // (in the reuse path the callback is a plain registerNetworkCallback).
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        joinedNetwork = null
        runCatching { connectivity.bindProcessToNetwork(null) }
    }

    private companion object {
        const val DECISION_MS = 1000L
        const val TEST_MS = 1200
    }
}
