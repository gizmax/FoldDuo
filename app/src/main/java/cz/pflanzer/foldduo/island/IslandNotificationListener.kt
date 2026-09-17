package cz.pflanzer.foldduo.island

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
import cz.pflanzer.foldduo.WallpaperPalette
import cz.pflanzer.foldduo.cachedLauncherBackground
import cz.pflanzer.foldduo.dominantColorArgb
import cz.pflanzer.foldduo.notifications.BadgeCandidate
import cz.pflanzer.foldduo.notifications.HubAction
import cz.pflanzer.foldduo.notifications.HubNotification
import cz.pflanzer.foldduo.notifications.HUB_ACTIONS_PER_CARD
import cz.pflanzer.foldduo.notifications.NotificationBadges
import cz.pflanzer.foldduo.notifications.NotificationHub
import cz.pflanzer.foldduo.notifications.NotificationPulse
import cz.pflanzer.foldduo.notifications.Pulse
import cz.pflanzer.foldduo.notifications.PULSE_COLOR_UNSET
import cz.pflanzer.foldduo.notifications.choosePulseColorArgb
import cz.pflanzer.foldduo.notifications.countBadgesByPackage
import cz.pflanzer.foldduo.notifications.isHubEligible
import cz.pflanzer.foldduo.notifications.shouldEmitPulse
import cz.pflanzer.foldduo.notifications.showSilentNotifications
import cz.pflanzer.foldduo.notifications.writePulseToPreferences
import cz.pflanzer.foldduo.wallpaperPaletteFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Feeds the rail island (PLAN.md Phase 2 step 5b, IDEAS.md B4). Publishes only promoted /
 * ongoing notifications ([islandKindFor]) plus the active media sessions the same listener
 * grant unlocks through [MediaSessionManager]. Nothing is stored; the flows live in process.
 * Bound by the system once the user allows notification access ([accessSettingsIntent]).
 */
class IslandNotificationListener : NotificationListenerService() {
    /**
     * Výkon 2 (17. 9. noc): `NotificationListenerService` callbacks (`onNotificationPosted`,
     * media session callbacks) arrive on the main thread by default. `publish()`/`publishHub()`/
     * `maybeEmitPulse()` do per-notification `PackageManager` icon/label loads, `RemoteViews`
     * inflation (Samsung chronometer), bitmap allocation + dominant-colour sampling and a
     * `WallpaperManager` binder call — all of that used to run inline on the callback, i.e. on
     * the main thread, on every notification post/update and every media playback tick. Moved to
     * a dedicated worker thread; `registerCallback`/`addOnActiveSessionsChangedListener` also use
     * this handler so playback-state callbacks land here directly instead of bouncing through
     * main first. `itemsState`/`NotificationHub`/`NotificationBadges`/`NotificationPulse` are
     * plain `StateFlow`/prefs writes, safe to update off the main thread.
     */
    private val workerThread = HandlerThread("IslandNotificationWorker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private var sessions: MediaSessionManager? = null
    private val controllers = LinkedHashMap<MediaSession.Token, TrackedSession>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { attachSessions(it.orEmpty()) }

    private class TrackedSession(val controller: MediaController, val callback: MediaController.Callback, val since: Long)

    /** Last logged summary of the published items (kind:package[:chronometer source]); logged only on change. */
    private var lastTrace: String? = null

    /** Keys posted as of the last [publish] — [onNotificationPosted] checks this (read before [publish] updates it) to tell a genuinely new notification from a repost/update of one already showing, for B50's [maybeEmitPulse]. */
    private var seenKeys: Set<String> = emptySet()

    /** [PULSE_MIN_INTERVAL_MS]-rate-limiter memory for B50 "Tapeta dýchá s oznámením" ([shouldEmitPulse]). */
    private var lastPulseAtMs: Long? = null

    override fun onListenerConnected() {
        activeInstance = java.lang.ref.WeakReference(this)
        enabledState.value = true
        sessions = getSystemService(MediaSessionManager::class.java)
        // Off main thread: getActiveSessions()/addOnActiveSessionsChangedListener() are binder
        // calls, and attachSessions()/publish() below do the heavy per-item mapping.
        workerHandler.post {
            runCatching {
                sessions?.addOnActiveSessionsChangedListener(sessionsListener, component(this), workerHandler)
                attachSessions(sessions?.getActiveSessions(component(this)).orEmpty())
            }
            publish()
        }
    }

    override fun onListenerDisconnected() {
        activeInstance = null
        detachSessions()
        itemsState.value = emptyList()
        enabledState.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // seenKeys is only ever read/written on workerHandler's single thread (both here and in
        // publish()), so posting the whole check-then-publish sequence keeps it race-free even
        // though the system calls this method itself on the main thread.
        workerHandler.post {
            sbn?.let { if (it.key !in seenKeys) maybeEmitPulse(it) }
            publish()
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { workerHandler.post { publish() } }

    override fun onDestroy() {
        detachSessions()
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun attachSessions(active: List<MediaController>) {
        val now = System.currentTimeMillis()
        val keep = active.associateBy { it.sessionToken }
        controllers.keys.filterNot { it in keep }.forEach { token ->
            controllers.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }
        active.forEach { controller ->
            if (controller.sessionToken in controllers) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() {
                    controllers.remove(controller.sessionToken)?.let { it.controller.unregisterCallback(it.callback) }
                    publish()
                }
            }
            controller.registerCallback(callback, workerHandler)
            controllers[controller.sessionToken] = TrackedSession(controller, callback, now)
        }
        publish()
    }

    private fun detachSessions() {
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        controllers.clear()
        sessions = null
    }

    private fun publish() {
        val posted = runCatching { activeNotifications }.getOrNull().orEmpty()
        seenKeys = posted.map { it.key }.toSet()
        val trace = ArrayList<String>()
        val sessionPackages = controllers.values.mapTo(HashSet()) { it.controller.packageName }
        val notifications = posted.mapNotNull { islandItem(it, trace, sessionPackages) }
        val media = controllers.values.mapNotNull(::mediaItem)
        media.forEach { trace += "MEDIA:${it.packageName}" }
        val summary = trace.joinToString(" ")
        if (summary != lastTrace) {
            lastTrace = summary
            Log.d(TAG, "island items (${trace.size}): ${summary.ifEmpty { "none" }}")
        }
        // B61 phase 2: mergeIslandItems already dedupes (session vs. transport notification,
        // Now Bar media dedupe upstream in islandItem()) and ranks by the base call/nav/timer/
        // media order; re-rank with the live-activity-aware order so an imminent ride/delivery
        // (etaMs inside LIVE_ACTIVITY_ALERT_WINDOW_MS) jumps ahead of a running timer.
        itemsState.value = rankIslandItemsWithLiveActivities(mergeIslandItems(notifications, media), System.currentTimeMillis())
        publishHub(posted)
    }

    /**
     * B34's notification hub (Today canvas) and the redesign's app-icon badges (Badges.kt):
     * privacy first — while the keyguard is locked the launcher is not on screen anyway, so this
     * does nothing rather than build icons/bitmaps (or counts) for cards no one can see. Otherwise
     * every posted notification not already claimed by the island ([islandKindFor], via
     * [isHubEligible]'s ongoing/media check) becomes a [HubNotification], and the same
     * eligibility rule (independent of the "Notifications on Today" setting) feeds
     * [NotificationBadges] per package.
     */
    private fun publishHub(posted: Array<out StatusBarNotification>) {
        val locked = runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)
        if (locked) { NotificationHub.publish(emptyList()); NotificationBadges.publish(emptyMap()); return }
        val showSilent = showSilentNotifications(this)
        NotificationHub.publish(posted.mapNotNull { hubNotification(it, showSilent) })
        NotificationBadges.publish(countBadgesByPackage(posted.mapNotNull(::badgeCandidate), showSilent))
    }

    /** Shared by [hubNotification] and [badgeCandidate]: the ranking importance the system currently has for [key], or default if unranked/unreadable. */
    private fun rankingImportance(key: String): Int = runCatching {
        val ranking = android.service.notification.NotificationListenerService.Ranking()
        currentRanking?.getRanking(key, ranking)
        ranking.importance
    }.getOrDefault(NotificationManager.IMPORTANCE_DEFAULT)

    private fun badgeCandidate(sbn: StatusBarNotification): BadgeCandidate? {
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        val mediaStyle = notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        return BadgeCandidate(sbn.packageName, isOngoing, mediaStyle, rankingImportance(sbn.key))
    }

    /**
     * IDEAS.md B50 "Tapeta dýchá s oznámením": [sbn] is a genuinely new arrival (the caller,
     * [onNotificationPosted], already checked it against [seenKeys]) — if it clears the same
     * eligibility bar as a badge ([badgeCandidate]/[isHubEligible]), derive that app's accent
     * colour and publish a [Pulse] for the wallpaper, rate-limited by [shouldEmitPulse] so a burst
     * of notifications only pulses once. Colour and timestamp only ever leave this method — no
     * text, no package name (see NotificationPulse.kt's header for why).
     */
    private fun maybeEmitPulse(sbn: StatusBarNotification) {
        val candidate = badgeCandidate(sbn) ?: return
        if (!isHubEligible(candidate.isOngoing, candidate.isMediaStyle, candidate.importance, showSilentNotifications(this))) return
        val now = System.currentTimeMillis()
        if (!shouldEmitPulse(lastPulseAtMs, now)) return
        lastPulseAtMs = now
        val notificationColor = sbn.notification?.color ?: PULSE_COLOR_UNSET
        val icon = runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
        val colorArgb = choosePulseColorArgb(notificationColor, iconDominantColorArgb(icon), currentPaletteAccentArgb())
        val pulse = Pulse(colorArgb, now)
        NotificationPulse.publish(pulse)
        writePulseToPreferences(this, pulse)
    }

    /** Dominant colour of [icon]'s own pixels ([dominantColorArgb], the same reducer WallpaperPalette.kt uses for the wallpaper itself); null without an icon or on a sampling failure. */
    private fun iconDominantColorArgb(icon: Drawable?): Int? {
        icon ?: return null
        return runCatching {
            val size = 12
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            bitmap.recycle()
            dominantColorArgb(pixels)
        }.getOrNull()
    }

    /** The wallpaper's own derived accent ([wallpaperPaletteFrom]) — a pulse's last-resort colour; the fixed fallback accent on any read failure. */
    private fun currentPaletteAccentArgb(): Int = runCatching {
        val manager = getSystemService(WallpaperManager::class.java)
        val colors = runCatching { manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        wallpaperPaletteFrom(colors, cachedLauncherBackground(this)).accent
    }.getOrDefault(WallpaperPalette.FALLBACK_ACCENT)

    private fun hubNotification(sbn: StatusBarNotification, showSilent: Boolean): HubNotification? {
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras
        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        val mediaStyle = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val importance = rankingImportance(sbn.key)
        if (!isHubEligible(isOngoing, mediaStyle, importance, showSilent)) return null
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val clearable = !sbn.isOngoing && notification.flags and Notification.FLAG_NO_CLEAR == 0
        return HubNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label,
            appIcon = runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull(),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            whenTime = notification.`when`.takeIf { it > 0L } ?: sbn.postTime,
            actions = notification.actions.orEmpty().take(HUB_ACTIONS_PER_CARD)
                .map { HubAction(it.title?.toString().orEmpty(), it.actionIntent) },
            contentIntent = notification.contentIntent,
            groupKey = sbn.groupKey ?: sbn.packageName,
            isClearable = clearable,
        )
    }

    private fun islandItem(sbn: StatusBarNotification, trace: MutableList<String>, sessionPackages: Set<String>): IslandItem? {
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras
        val showsChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val samsung = if (isSamsungOngoingActivity(extras.keySet()))
            samsungOngoingKind(extras.getString(SAMSUNG_ONGOING_PRIMARY), sbn.packageName) else null
        // B61 phase 2: Uber/Bolt/Rohlík/Wolt/Mapy/Google Maps/Foodora post a plain ongoing
        // notification with no android.category and often no progress bar — appOngoingKindFor
        // (LiveActivityAdapters.kt) recognises the package itself, the same way samsungOngoing does.
        val appOngoingKind = appOngoingKindFor(sbn.packageName)
        val kind = islandKindFor(notification.flags, notification.category, showsChronometer,
            hasProgress = progressMax > 0 || indeterminate,
            mediaStyle = extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            samsungOngoing = samsung, appOngoingKind = appOngoingKind) ?: return null
        if (samsung != null) {
            // Bug: after starting a track, Samsung's Now Bar posts its own ongoing-activity
            // notification ("MediaOngoingActivity") for the same playback our MediaController
            // session item already shows — drop it rather than show a redundant, often
            // unreadable second pill (isDuplicateNowBarMediaItem, IslandItem.kt).
            val extraPackageCandidates = extras.keySet().mapNotNull { key -> runCatching { extras.get(key) }.getOrNull() as? String }
            val templateHints = listOfNotNull(
                extras.getString(SAMSUNG_ONGOING_PRIMARY),
                runCatching { extras.getString(Notification.EXTRA_TEMPLATE) }.getOrNull(),
                sbn.tag,
            )
            if (isDuplicateNowBarMediaItem(sbn.packageName, extraPackageCandidates, sessionPackages,
                    hasMediaSessionExtra = extras.containsKey(Notification.EXTRA_MEDIA_SESSION), templateHints = templateHints)) {
                trace += "DEDUPE_NOWBAR_MEDIA:${sbn.packageName}"
                return null
            }
        }
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        var chronometerBase = if (showsChronometer) notification.`when` else null
        var countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        var smallIcon = notification.smallIcon
        var accent: Int? = null
        var source = ""
        if (samsung != null) {
            // Samsung Now Bar: the headline / detail line replace the (often generic) title and
            // text; the chip icon and colour are what the Now Bar itself draws. A headline that is
            // itself a leaked class/template name (or missing) falls back to the app's own label
            // instead of ever showing a bare class name (resolveNowBarTitle, IslandItem.kt).
            val appLabel = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName)
            val primary = extras.getString(SAMSUNG_ONGOING_PRIMARY).orEmpty()
            val secondary = extras.getString(SAMSUNG_ONGOING_SECONDARY)
            title = resolveNowBarTitle(primary.ifBlank { title }, appLabel)
            samsungOngoingLabel(secondary).takeIf { it.isNotBlank() }?.let { text = it }
            runCatching { extras.getParcelable(SAMSUNG_ONGOING_CHIP_ICON, Icon::class.java) }.getOrNull()?.let { smallIcon = it }
            if (extras.containsKey(SAMSUNG_ONGOING_CHIP_BG)) accent = extras.getInt(SAMSUNG_ONGOING_CHIP_BG)
            if (chronometerBase == null) {
                val fromViews = samsungChronometer(extras)
                when {
                    fromViews != null -> { chronometerBase = fromViews.first; countDown = fromViews.second; source = "remoteviews" }
                    kind == IslandKind.TIMER -> samsungOngoingEndTime(secondary, System.currentTimeMillis())?.let {
                        chronometerBase = it; countDown = true; source = "endtime"
                    }
                }
                if (source.isEmpty()) source = "label"
            }
        }
        trace += "$kind:${sbn.packageName}" + if (source.isNotEmpty()) ":$source" else ""
        // B61 phase 2: enrich with ETA/segments/stage/keyValue/pictureKey from whichever adapter
        // recognises this notification (LiveActivityAdapters.kt) — built off the already-resolved
        // title/text so a Samsung Now Bar delivery/ride template parses the same way as the app's
        // own notification would. Never overrides kind/qualification, only adds fields; a
        // notification no adapter recognises keeps behaving exactly as before this feature.
        val liveMatch = matchLiveActivity(
            notificationFacts(sbn).copy(title = title, text = text),
            System.currentTimeMillis(),
        )
        return IslandItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appIcon = runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull(),
            smallIcon = smallIcon,
            title = title,
            text = text,
            progress = if (progressMax > 0 && !indeterminate)
                (extras.getInt(Notification.EXTRA_PROGRESS) / progressMax.toFloat()).coerceIn(0f, 1f) else null,
            progressIndeterminate = indeterminate,
            chronometerBase = chronometerBase,
            countDown = countDown,
            contentIntent = notification.contentIntent,
            actions = notification.actions.orEmpty().take(2).map { IslandAction(it.title?.toString().orEmpty(), it.actionIntent) },
            kind = kind,
            postedAt = sbn.postTime,
            accentColor = accent,
            isNowBar = samsung != null,
            etaMs = liveMatch?.etaMs,
            segments = liveMatch?.segments.orEmpty(),
            stageIndex = liveMatch?.stageIndex,
            keyValue = liveMatch?.keyValue,
            pictureKey = liveMatch?.pictureKey,
        )
    }

    /**
     * Samsung ships a timer's countdown as a `RemoteViews` with a running [Chronometer] rather than
     * as `Notification.when` + `showChronometer`. Inflate it against the sender's resources and
     * read the chronometer's base (elapsed-realtime) back as a wall-clock base and its direction.
     * Null when the views are absent, cannot be inflated here, or hold no chronometer.
     */
    private fun samsungChronometer(extras: Bundle): Pair<Long, Boolean>? {
        val views = runCatching { extras.getParcelable(SAMSUNG_ONGOING_CHRONOMETER, RemoteViews::class.java) }.getOrNull()
            ?: return null
        val root = runCatching { views.apply(this, FrameLayout(this)) }
            .onFailure { Log.w(TAG, "Samsung chronometer RemoteViews did not inflate: $it") }
            .getOrNull() ?: return null
        val chronometer = findChronometer(root) ?: return null
        val wallBase = chronometer.base - SystemClock.elapsedRealtime() + System.currentTimeMillis()
        return wallBase to chronometer.isCountDown
    }

    private fun findChronometer(view: View): Chronometer? {
        if (view is Chronometer) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findChronometer(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun mediaItem(tracked: TrackedSession): IslandItem? {
        val controller = tracked.controller
        val playback = controller.playbackState ?: return null
        val metadata = controller.metadata
        val playing = when (playback.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
            PlaybackState.STATE_PAUSED -> false
            // 17. 9.: YouTube (no background play) leaves its session ACTIVE but STOPPED when the
            // app is left, position kept — a resumable pill, as long as it is recent and titled.
            PlaybackState.STATE_STOPPED ->
                if (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
                    android.os.SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime > STOPPED_SESSION_TTL_MS) return null
                else false
            else -> return null
        }
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrDefault(controller.packageName)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = playback.position.coerceAtLeast(0L)
        // "Apple Music karta a morph": lastPositionUpdateTime is SystemClock.elapsedRealtime(),
        // the base the expanded card's 1 Hz progress bar extrapolates from while playing.
        val positionUpdatedAt = playback.lastPositionUpdateTime
        // "Ostrůvek do plochy": the expanded card's 64 dp artwork thumbnail, tried in the order the
        // MediaMetadata docs recommend (a full album-art bitmap first, the smaller icon last).
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val accent = artwork?.let { bitmapDominantColorArgb(it) }
        return IslandItem(
            key = "media:${controller.packageName}",
            packageName = controller.packageName,
            appIcon = runCatching { packageManager.getApplicationIcon(controller.packageName) }.getOrNull(),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: label,
            text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: label,
            progress = if (duration > 0L) (position / duration.toFloat()).coerceIn(0f, 1f) else null,
            contentIntent = controller.sessionActivity,
            kind = IslandKind.MEDIA,
            postedAt = tracked.since,
            media = IslandMedia(playing, controller, artwork, position, duration, positionUpdatedAt, playback.playbackSpeed),
            accentColor = accent,
        )
    }

    /** Dominant colour of [bitmap]'s own pixels (a small scaled-down copy, same reducer [dominantColorArgb] uses for the wallpaper and app icons); null without a bitmap or on a sampling failure — the expanded media card's progress-bar accent (task spec item 2). */
    private fun bitmapDominantColorArgb(bitmap: Bitmap): Int? = runCatching {
        val size = 12
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        dominantColorArgb(pixels)
    }.getOrNull()

    companion object {
        private const val TAG = "IslandNotificationListener"
        private val itemsState = MutableStateFlow<List<IslandItem>>(emptyList())
        /** Ranked island entries; empty until the listener is bound and something is live. */
        val items: StateFlow<List<IslandItem>> = itemsState.asStateFlow()
        private val enabledState = MutableStateFlow(false)
        /** Whether notification access is granted (bound, or granted and about to bind). */
        val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
        /** The bound instance, for [dismiss]; weak so an unbound service is still free to be collected. */
        private var activeInstance: java.lang.ref.WeakReference<IslandNotificationListener>? = null

        fun component(context: Context) = ComponentName(context, IslandNotificationListener::class.java)

        /**
         * Swipe-to-dismiss (B30 item 2, [islandItemDismissible]): cancel the notification behind
         * [key] the same way the shade's own dismiss would. A no-op without a bound listener, for
         * a media-session key (nothing to cancel), or once the notification is already gone.
         */
        fun dismiss(key: String) {
            runCatching { activeInstance?.get()?.cancelNotification(key) }
        }

        fun isAccessGranted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
        }.getOrDefault(false)

        /** Re-read the grant (Settings round trip); a revoked grant also empties the island. */
        fun refreshEnabled(context: Context) {
            val granted = isAccessGranted(context)
            enabledState.value = granted
            if (!granted) itemsState.value = emptyList()
        }

        /** The listener's own detail page; the caller falls back to the list if it does not resolve. */
        fun accessSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())

        fun accessSettingsFallbackIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        fun openAccessSettings(context: Context) {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK
            runCatching { context.startActivity(accessSettingsIntent(context).addFlags(flags)) }
                .recoverCatching { context.startActivity(accessSettingsFallbackIntent().addFlags(flags)) }
        }
    }
}

/** A STOPPED media session (YouTube after leaving the app) stays a pill for this long since its last position update. */
private const val STOPPED_SESSION_TTL_MS = 10 * 60_000L
