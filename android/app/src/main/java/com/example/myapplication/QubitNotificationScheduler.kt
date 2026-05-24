package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max

data class QubitNotificationEvent(
    val id: String,
    val title: String,
    val body: String,
    val daysUntil: Int?
)

data class QubitJudgmentHistoryItem(
    val id: String,
    val title: String,
    val body: String,
    val recordedAtMillis: Long,
    val source: String
)

object QubitNotificationScheduler {
    private const val PREFS_NAME = "qubit_notifications"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WATCH_IDS = "watch_ids"
    private const val KEY_LAST_SUMMARY = "last_summary"
    private const val KEY_HISTORY = "judgment_history"
    private const val CHANNEL_ID = "qubit_watch_alerts"
    private const val DAILY_ID = "daily-briefing"
    private const val ACTION_NOTIFY = "com.example.myapplication.action.QUBIT_NOTIFY"
    private const val EXTRA_ID = "extra_id"
    private const val EXTRA_TITLE = "extra_title"
    private const val EXTRA_BODY = "extra_body"
    private const val EXTRA_DAILY = "extra_daily"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "관심종목 판단 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "관심종목 투자 가설, 실적 리스크, 과열 신호 판단 알림"
        }
        manager.createNotificationChannel(channel)
    }

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        createChannel(context)
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            cancelAll(context)
            setLastSummary(context, "알림 꺼짐")
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun statusTitle(context: Context): String {
        return when {
            isEnabled(context) && canPostNotifications(context) -> "알림 켜짐"
            isEnabled(context) -> "권한 확인 필요"
            else -> "알림 꺼짐"
        }
    }

    fun statusDetail(context: Context): String {
        return when {
            isEnabled(context) && canPostNotifications(context) -> lastSummary(context)
            isEnabled(context) -> "Android 설정에서 큐빗 알림을 허용해야 받을 수 있습니다."
            else -> "관심종목 투자 가설, 실적 리스크, 과열 신호를 판단 알림으로 받을 수 있습니다."
        }
    }

    fun sync(context: Context, events: List<QubitNotificationEvent>, dailySummary: String) {
        createChannel(context)
        recordHistory(context, events, source = "판단 알림")
        cancelWatchNotifications(context)
        if (!isEnabled(context) || !canPostNotifications(context)) {
            setLastSummary(context, if (isEnabled(context)) "알림 권한 대기" else "알림 대기")
            return
        }

        scheduleDailyBriefing(context, dailySummary)
        val scheduledIds = events
            .distinctBy { it.id }
            .take(8)
            .mapNotNull { event ->
                if (scheduleWatchEvent(context, event)) event.id else null
            }
            .toSet()

        prefs(context).edit().putStringSet(KEY_WATCH_IDS, scheduledIds).apply()
        setLastSummary(
            context,
            if (scheduledIds.isEmpty()) "매일 08:30 브리핑 예약" else "판단 알림 ${scheduledIds.size}개와 매일 브리핑 예약"
        )
    }

    fun history(context: Context): List<QubitJudgmentHistoryItem> {
        val raw = prefs(context).getString(KEY_HISTORY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        QubitJudgmentHistoryItem(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            body = item.optString("body"),
                            recordedAtMillis = item.optLong("recordedAtMillis"),
                            source = item.optString("source", "판단 알림")
                        )
                    )
                }
            }.filter { it.id.isNotBlank() && it.title.isNotBlank() && it.body.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun recordDelivered(context: Context, id: String, title: String, body: String) {
        if (id.isBlank() || title.isBlank() || body.isBlank()) return
        recordHistory(
            context = context,
            items = listOf(
                QubitJudgmentHistoryItem(
                    id = id,
                    title = title,
                    body = body,
                    recordedAtMillis = System.currentTimeMillis(),
                    source = "수신됨"
                )
            )
        )
    }

    private fun scheduleDailyBriefing(context: Context, summary: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = nextDailyMillis(hour = 8, minute = 30)
        val pendingIntent = notificationPendingIntent(
            context = context,
            id = DAILY_ID,
            title = "큐빗 오늘의 브리핑",
            body = summary,
            isDaily = true
        ) ?: return
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    private fun scheduleWatchEvent(context: Context, event: QubitNotificationEvent): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = triggerMillis(event.daysUntil)
        val pendingIntent = notificationPendingIntent(
            context = context,
            id = event.id,
            title = event.title,
            body = event.body,
            isDaily = false
        ) ?: return false
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent
        )
        return true
    }

    private fun triggerMillis(daysUntil: Int?): Long {
        val now = System.currentTimeMillis()
        if (daysUntil == null) return now + THIRTY_MINUTES_MS

        val zone = ZoneId.systemDefault()
        val dayOffset = max(if (daysUntil <= 1) 0 else daysUntil - 1, 0)
        val target = LocalDate.now(zone)
            .plusDays(dayOffset.toLong())
            .atTime(LocalTime.of(9, 0))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        return if (target > now + TEN_MINUTES_MS) target else now + FIFTEEN_MINUTES_MS
    }

    private fun nextDailyMillis(hour: Int, minute: Int): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = LocalDate.now(zone).atTime(hour, minute)
        if (!target.isAfter(now.plusMinutes(10))) {
            target = target.plusDays(1)
        }
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    private fun cancelAll(context: Context) {
        cancelWatchNotifications(context)
        cancelPendingIntent(context, DAILY_ID, isDaily = true)
    }

    private fun cancelWatchNotifications(context: Context) {
        val storedIds = prefs(context).getStringSet(KEY_WATCH_IDS, emptySet()).orEmpty()
        storedIds.forEach { cancelPendingIntent(context, it, isDaily = false) }
        prefs(context).edit().putStringSet(KEY_WATCH_IDS, emptySet()).apply()
    }

    private fun cancelPendingIntent(context: Context, id: String, isDaily: Boolean) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = notificationPendingIntent(
            context = context,
            id = id,
            title = "",
            body = "",
            isDaily = isDaily,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun notificationPendingIntent(
        context: Context,
        id: String,
        title: String,
        body: String,
        isDaily: Boolean,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    ): PendingIntent? {
        val intent = Intent(context, QubitNotificationReceiver::class.java)
            .setAction(ACTION_NOTIFY)
            .setData(Uri.parse("qubit://notification/$id"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_BODY, body)
            .putExtra(EXTRA_DAILY, isDaily)
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun lastSummary(context: Context): String {
        return prefs(context).getString(KEY_LAST_SUMMARY, null) ?: "관심종목 판단 알림을 동기화합니다."
    }

    private fun setLastSummary(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_SUMMARY, value).apply()
    }

    private fun recordHistory(context: Context, events: List<QubitNotificationEvent>, source: String) {
        val now = System.currentTimeMillis()
        val items = events
            .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.body.isNotBlank() }
            .map {
                QubitJudgmentHistoryItem(
                    id = it.id,
                    title = it.title,
                    body = it.body,
                    recordedAtMillis = now,
                    source = source
                )
            }
        recordHistory(context, items)
    }

    private fun recordHistory(context: Context, items: List<QubitJudgmentHistoryItem>) {
        if (items.isEmpty()) return
        val merged = history(context).toMutableList()
        items.forEach { item ->
            merged.removeAll { it.id == item.id }
            merged.add(0, item)
        }
        val array = JSONArray()
        merged
            .sortedByDescending { it.recordedAtMillis }
            .take(40)
            .forEach { item ->
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("body", item.body)
                        .put("recordedAtMillis", item.recordedAtMillis)
                        .put("source", item.source)
                )
            }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    internal fun receiverChannelId(): String = CHANNEL_ID
    internal fun receiverAction(): String = ACTION_NOTIFY
    internal fun extraId(): String = EXTRA_ID
    internal fun extraTitle(): String = EXTRA_TITLE
    internal fun extraBody(): String = EXTRA_BODY
    internal fun extraDaily(): String = EXTRA_DAILY

    private const val TEN_MINUTES_MS = 10 * 60 * 1_000L
    private const val FIFTEEN_MINUTES_MS = 15 * 60 * 1_000L
    private const val THIRTY_MINUTES_MS = 30 * 60 * 1_000L
}

class QubitNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QubitNotificationScheduler.receiverAction()) return
        QubitNotificationScheduler.createChannel(context)
        if (!QubitNotificationScheduler.canPostNotifications(context)) return

        val id = intent.getStringExtra(QubitNotificationScheduler.extraId()).orEmpty()
        val title = intent.getStringExtra(QubitNotificationScheduler.extraTitle()).orEmpty()
        val body = intent.getStringExtra(QubitNotificationScheduler.extraBody()).orEmpty()
        val isDaily = intent.getBooleanExtra(QubitNotificationScheduler.extraDaily(), false)
        if (id.isBlank() || title.isBlank() || body.isBlank()) return

        if (!isDaily) {
            QubitNotificationScheduler.recordDelivered(context, id, title, body)
        }
        showNotification(context, id, title, body, isDaily)
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(context: Context, id: String, title: String, body: String, isDaily: Boolean) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            "open-$id".hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, QubitNotificationScheduler.receiverChannelId())
            .setSmallIcon(R.drawable.quantbridge_system_splash_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (isDaily) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .build()

        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}
