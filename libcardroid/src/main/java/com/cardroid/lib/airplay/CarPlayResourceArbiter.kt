package com.cardroid.lib.airplay

/**
 * Small, protocol-only model of the two CarPlay resources reported by `/info`.
 *
 * The accessory owns resources through a `changeModes` request; the controller
 * reports the resulting owners with `modesChanged`.  Keeping this state here
 * makes the event-channel handler deterministic and, in particular, prevents
 * a delayed phone reassertion from taking a resource back after the accessory
 * has requested it.
 */
class CarPlayResourceArbiter(
    private val log: (String) -> Unit = {},
) {
    data class AppState(
        val id: Int,
        val state: Boolean? = null,
        val speechMode: Int? = null,
    )

    data class ResourceState(
        val id: Int,
        val entity: Int,
        val permanentEntity: Int,
        /** True while the accessory holds a temporary (borrow) ownership. */
        val borrowed: Boolean = false,
    )

    data class Snapshot(
        val appStates: List<AppState>,
        val resources: List<ResourceState>,
    )

    data class ApplyResult(
        val snapshot: Snapshot,
        val changed: Boolean,
        val accepted: Int,
        val ignored: Int,
    )

    private val lock = Any()
    private var appStates = initialAppStates()
    private var resources = initialResources()
    /** Invalidates a delayed phone reassertion whenever the accessory changes modes. */
    private var generation = 0L
    private var screenGeneration = 0L

    fun reset() = synchronized(lock) {
        appStates = initialAppStates()
        resources = initialResources()
        generation++
        screenGeneration++
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    /** Token used by a delayed reassertion; a receiver request makes it stale. */
    fun reassertToken(): Long = synchronized(lock) { generation }

    fun isCurrent(token: Long): Boolean = synchronized(lock) { token == generation }

    /** Screen-return work must survive unrelated audio and app-state updates. */
    fun screenReturnToken(): Long = synchronized(lock) { screenGeneration }

    fun isScreenReturnCurrent(token: Long): Boolean = synchronized(lock) {
        token == screenGeneration && resources.first { it.id == RESOURCE_SCREEN }.entity == ENTITY_PHONE
    }

    fun accessoryOwnsScreen(): Boolean = synchronized(lock) {
        resources.first { it.id == RESOURCE_SCREEN }.entity == ENTITY_ACCESSORY
    }

    /**
     * Applies the accessory's `changeModes` request.
     *
     * Resource 1 is mainScreen and resource 2 is mainAudio.  Values follow
     * Apple's AirPlayCommon.h: take (1) moves the permanent owner to the
     * accessory (entity 2), untake (2) returns permanent ownership to the
     * controller, borrow (3) moves only the current owner, and unborrow (4)
     * restores the permanent owner.  The accessory omits priority and
     * constraints that are NotApplicable (untake/unborrow carry none), so
     * those keys are optional and only validated when present.  Unknown
     * resource/app-state IDs and malformed transfer requests are logged and
     * ignored so a new receiver extension cannot silently corrupt the session.
     */
    fun applyChangeModes(command: Map<String, Any?>): ApplyResult = synchronized(lock) {
        // A request invalidates any queued post-auth full-phone declaration,
        // even if every individual item turns out to be unsupported.
        generation++
        val params = command["params"] as? Map<*, *> ?: run {
            log("Modes: changeModes missing params; preserving current ownership")
            return@synchronized ApplyResult(snapshotLocked(), changed = false, accepted = 0, ignored = 1)
        }
        var accepted = 0
        var ignored = 0
        var changed = false

        val requestedStates = params["appStates"] as? List<*>
        if (requestedStates != null) {
            requestedStates.forEach { raw ->
                val item = raw as? Map<*, *> ?: run {
                    ignored++
                    log("Modes: ignored malformed appState=$raw")
                    return@forEach
                }
                val id = number(item["appStateID"])?.toInt()
                if (id == null || id !in SUPPORTED_APP_STATES) {
                    ignored++
                    log("Modes: ignored unsupported appStateID=${item["appStateID"]}")
                    return@forEach
                }
                val old = appStates.first { it.id == id }
                val next = when (id) {
                    APP_STATE_SPEECH -> {
                        val mode = number(item["speechMode"])?.toInt()
                        if (mode == null) {
                            ignored++
                            log("Modes: appStateID=$id missing numeric speechMode")
                            return@forEach
                        }
                        old.copy(speechMode = mode)
                    }
                    else -> {
                        val state = item["state"] as? Boolean
                        if (state == null) {
                            ignored++
                            log("Modes: appStateID=$id missing boolean state")
                            return@forEach
                        }
                        old.copy(state = state)
                    }
                }
                if (next != old) {
                    appStates = appStates.map { if (it.id == id) next else it }
                    changed = true
                }
                accepted++
            }
        }

        val requestedResources = params["resources"] as? List<*>
        if (requestedResources != null) {
            requestedResources.forEach { raw ->
                val item = raw as? Map<*, *> ?: run {
                    ignored++
                    log("Modes: ignored malformed resource=$raw")
                    return@forEach
                }
                val id = number(item["resourceID"])?.toInt()
                if (id == null || id !in SUPPORTED_RESOURCES) {
                    ignored++
                    log("Modes: ignored unsupported resourceID=${item["resourceID"]}")
                    return@forEach
                }
                val transferType = number(item["transferType"])?.toInt()
                if (transferType == null || transferType !in SUPPORTED_TRANSFER_TYPES) {
                    ignored++
                    log("Modes: ignored resourceID=$id transferType=${item["transferType"]}")
                    return@forEach
                }
                if (!validOptional(item["transferPriority"], PRIORITIES)) {
                    ignored++
                    log("Modes: ignored resourceID=$id invalid transferPriority=${item["transferPriority"]}")
                    return@forEach
                }
                if (!validOptional(item["takeConstraint"], CONSTRAINTS) ||
                    !validOptional(item["borrowConstraint"], CONSTRAINTS) ||
                    !validOptional(item["unborrowConstraint"], CONSTRAINTS)
                ) {
                    ignored++
                    log("Modes: ignored resourceID=$id invalid constraint in $item")
                    return@forEach
                }
                val current = resources.first { it.id == id }
                val next = when (transferType) {
                    // A take is permanent until an untake; it also ends any borrow.
                    TRANSFER_TAKE -> current.copy(entity = ENTITY_ACCESSORY, permanentEntity = ENTITY_ACCESSORY, borrowed = false)
                    // Untake hands permanent ownership back to the phone; an active borrow keeps the accessory current.
                    TRANSFER_UNTAKE -> current.copy(
                        entity = if (current.borrowed) ENTITY_ACCESSORY else ENTITY_PHONE,
                        permanentEntity = ENTITY_PHONE,
                    )
                    TRANSFER_BORROW -> current.copy(entity = ENTITY_ACCESSORY, borrowed = true)
                    TRANSFER_UNBORROW -> current.copy(entity = current.permanentEntity, borrowed = false)
                    else -> error("unreachable")
                }
                if (next != current) {
                    resources = resources.map { if (it.id == id) next else it }
                    if (id == RESOURCE_SCREEN) screenGeneration++
                    changed = true
                }
                accepted++
                log("Modes: resourceID=$id transfer=${transferName(transferType)} -> entity=${next.entity} permanent=${next.permanentEntity}")
            }
        }

        log("Modes: changeModes accepted=$accepted ignored=$ignored changed=$changed")
        ApplyResult(snapshotLocked(), changed, accepted, ignored)
    }

    /**
     * Starts [resourceId] owned by the accessory, matching a receiver whose
     * `/info.modes.initialPermanentEntity` gives it to the car (e.g. the radio
     * owns main audio until the phone starts playing).
     */
    fun startAccessoryOwned(resourceId: Int) = synchronized(lock) {
        if (resourceId == RESOURCE_SCREEN) screenGeneration++
        resources = resources.map {
            if (it.id == resourceId) it.copy(entity = ENTITY_ACCESSORY, permanentEntity = ENTITY_ACCESSORY, borrowed = false) else it
        }
        log("Modes: resourceID=$resourceId starts accessory-owned")
    }

    /**
     * The phone (controller, the resource manager) takes [resourceId] back, as an
     * iPhone does when the accessory sends `requestUI` because the user tapped the
     * CarPlay icon.  Clears any borrow and invalidates queued reassertions.
     */
    fun controllerTake(resourceId: Int, reason: String): ApplyResult = synchronized(lock) {
        generation++
        val current = resources.firstOrNull { it.id == resourceId } ?: run {
            log("Modes: controller take ignored unsupported resourceID=$resourceId ($reason)")
            return@synchronized ApplyResult(snapshotLocked(), changed = false, accepted = 0, ignored = 1)
        }
        val next = current.copy(entity = ENTITY_PHONE, permanentEntity = ENTITY_PHONE, borrowed = false)
        val changed = next != current
        if (changed) {
            resources = resources.map { if (it.id == resourceId) next else it }
            if (resourceId == RESOURCE_SCREEN) screenGeneration++
        }
        log("Modes: controller take resourceID=$resourceId ($reason) -> entity=${next.entity} permanent=${next.permanentEntity} changed=$changed")
        ApplyResult(snapshotLocked(), changed, accepted = 1, ignored = 0)
    }

    /** Builds the controller-to-accessory `modesChanged` command for a state snapshot. */
    fun modesChangedCommand(snapshot: Snapshot = snapshot()): AirPlayBplist.Value =
        AirPlayBplist.dict(
            "type" to AirPlayBplist.str("modesChanged"),
            "params" to AirPlayBplist.dict(
                // Order as a real iPhone sends it (DiPlay trace): appStates 2,1,3; resources 2,1.
                "appStates" to AirPlayBplist.array(
                    appStateValue(snapshot, APP_STATE_PHONE_CALL),
                    appStateValue(snapshot, APP_STATE_SPEECH),
                    appStateValue(snapshot, APP_STATE_TURN_BY_TURN),
                ),
                "resources" to AirPlayBplist.ArrayV(snapshot.resources.sortedByDescending { it.id }.map {
                    AirPlayBplist.dict(
                        "resourceID" to AirPlayBplist.int(it.id),
                        "entity" to AirPlayBplist.int(it.entity),
                        "permanentEntity" to AirPlayBplist.int(it.permanentEntity),
                    )
                }),
            ),
        )

    private fun appStateValue(snapshot: Snapshot, id: Int): AirPlayBplist.Value {
        val state = snapshot.appStates.first { it.id == id }
        return if (id == APP_STATE_SPEECH) {
            // Key order as a real iPhone: entity, speechMode, appStateID.
            AirPlayBplist.dict(
                // modesChanged reports the current owner.  A speech mode of
                // -1 means neither side is speaking; an active request is an
                // accessory-owned app state (entity 2).
                "entity" to AirPlayBplist.int(if (state.speechMode == SPEECH_NONE) ENTITY_NONE else ENTITY_ACCESSORY),
                "speechMode" to AirPlayBplist.int(state.speechMode ?: SPEECH_NONE),
                "appStateID" to AirPlayBplist.int(id),
            )
        } else {
            AirPlayBplist.dict(
                // `state` is the accessory's request form; the response uses
                // the simplified owner form used by modesChanged.
                "entity" to AirPlayBplist.int(if (state.state == true) ENTITY_ACCESSORY else ENTITY_NONE),
                "appStateID" to AirPlayBplist.int(id),
            )
        }
    }

    private fun snapshotLocked() = Snapshot(appStates.toList(), resources.toList())

    private fun number(value: Any?): Number? = value as? Number

    /** Absent means NotApplicable; a present value must be one of [allowed]. */
    private fun validOptional(value: Any?, allowed: Set<Int>): Boolean =
        value == null || number(value)?.toInt()?.let { it in allowed } == true

    private fun transferName(type: Int) = when (type) {
        TRANSFER_TAKE -> "take"
        TRANSFER_UNTAKE -> "untake"
        TRANSFER_BORROW -> "borrow"
        TRANSFER_UNBORROW -> "unborrow"
        else -> "unknown"
    }

    companion object {
        const val RESOURCE_SCREEN = 1
        const val RESOURCE_AUDIO = 2
        const val ENTITY_NONE = 0
        const val ENTITY_PHONE = 1
        const val ENTITY_ACCESSORY = 2

        // AirPlayCommon.h kAirPlayTransferType_*.
        const val TRANSFER_TAKE = 1
        const val TRANSFER_UNTAKE = 2
        const val TRANSFER_BORROW = 3
        const val TRANSFER_UNBORROW = 4

        const val APP_STATE_PHONE_CALL = 2
        const val APP_STATE_SPEECH = 1
        const val APP_STATE_TURN_BY_TURN = 3
        const val SPEECH_NONE = -1

        private val SUPPORTED_RESOURCES = setOf(RESOURCE_SCREEN, RESOURCE_AUDIO)
        private val SUPPORTED_APP_STATES = setOf(APP_STATE_PHONE_CALL, APP_STATE_SPEECH, APP_STATE_TURN_BY_TURN)
        private val SUPPORTED_TRANSFER_TYPES = setOf(TRANSFER_TAKE, TRANSFER_UNTAKE, TRANSFER_BORROW, TRANSFER_UNBORROW)
        // kAirPlayTransferPriority_NiceToHave / _UserInitiated.
        private val PRIORITIES = setOf(100, 500)
        // kAirPlayConstraint_Anytime / _UserInitiated / _Never.
        private val CONSTRAINTS = setOf(100, 500, 1000)

        private fun initialAppStates() = listOf(
            AppState(APP_STATE_PHONE_CALL, state = false),
            AppState(APP_STATE_SPEECH, speechMode = SPEECH_NONE),
            AppState(APP_STATE_TURN_BY_TURN, state = false),
        )

        private fun initialResources() = listOf(
            ResourceState(RESOURCE_SCREEN, ENTITY_PHONE, ENTITY_PHONE),
            ResourceState(RESOURCE_AUDIO, ENTITY_PHONE, ENTITY_PHONE),
        )

        fun initialSnapshot(): Snapshot = Snapshot(initialAppStates(), initialResources())

        fun initialModesChangedCommand(): AirPlayBplist.Value =
            CarPlayResourceArbiter().modesChangedCommand(initialSnapshot())
    }
}
