package com.controleco.app.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.controleco.app.BuildConfig
import com.controleco.app.util.AppLog

/**
 * 通知监听服务：用户在系统设置授予"通知使用权"后由系统绑定。
 * 只做两件事：提取通知文本原文（含折叠明细行与副标题），以及**折叠摘要的金额补全**——
 * 摘要自身无金额时，从 `activeNotifications` 找同组子通知，用子通知的文本与时间替代
 * （微信"退款到账通知"等折叠通知实测摘要不带金额，只读摘要必然漏记）。
 */
class NotificationCaptureService : NotificationListenerService() {

    /**
     * 分级重连的延迟档位：0 / 1.5s / 6s——进程被杀后系统未必自动重连，
     * 多打几拍把监听拉回来（`requestRebind` 是官方允许的软重连方式，不做禁用/启用组件的硬重启）。
     */
    private val rebindHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var rebindAttempt = 0

    override fun onListenerConnected() {
        super.onListenerConnected()
        rebindAttempt = 0
        CaptureCenter.markConnected(true)
        CaptureCenter.onListenerHealthy()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        CaptureCenter.markConnected(false)
        AppLog.w("Ntf", "listener disconnected, requesting rebind (attempt=${rebindAttempt + 1})")
        scheduleStaggeredRebind()
    }

    private fun scheduleStaggeredRebind() {
        val delays = REBIND_DELAYS
        if (rebindAttempt >= delays.size) {
            AppLog.w("Ntf", "rebind attempts exhausted (${delays.size})")
            return
        }
        val delay = delays[rebindAttempt]
        rebindAttempt++
        rebindHandler.postDelayed({
            if (CaptureCenter.connected.value) return@postDelayed
            AppLog.i("Ntf", "rebind attempt #$rebindAttempt delay=$delay")
            runCatching { requestRebind(ComponentName(this, NotificationCaptureService::class.java)) }
            scheduleStaggeredRebind()
        }, delay)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // 收到通知本身就是"监听活着"的铁证（状态显示已连接但实际假死的情况由此自愈）
        CaptureCenter.markAliveFromNotification()
        val snapshot = snapshotOf(sbn)
        var title = snapshot.title
        var text = snapshot.mergedText()
        var bigText = snapshot.bigText
        var postTime = sbn.notification.`when`
        var systemPostTime = sbn.postTime
        var enriched = false

        // 折叠摘要补全：摘要无金额 → 同组子通知里找那条真正的账务明细
        if (GroupNotificationResolver.needsEnrichment(snapshot)) {
            val children = runCatching {
                activeNotifications?.map { snapshotOf(it) }.orEmpty()
            }.getOrElse {
                AppLog.w("Ntf", "activeNotifications unavailable", it)
                emptyList()
            }
            GroupNotificationResolver.pickChild(snapshot, children)?.let { child ->
                title = child.title.ifBlank { title }
                text = child.mergedText()
                bigText = ""
                postTime = child.postTime
                systemPostTime = child.systemPostTime
                enriched = true
                AppLog.i("Ntf", "grouped summary enriched from child: ${text.take(160)}")
            }
        }

        CaptureCenter.onNotificationPosted(
            packageName = sbn.packageName ?: return,
            title = title,
            text = text,
            bigText = bigText,
            postTime = postTime,
            isDebugBuild = BuildConfig.DEBUG,
            groupEnriched = enriched,
            notificationKey = sbn.key,
            systemPostTime = systemPostTime,
        )
    }

    /** 一条通知的文本快照（与 GroupNotificationResolver 的纯数据类共用同一形态） */
    private fun snapshotOf(sbn: StatusBarNotification): NotificationText {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        // 副标题类（EXTRA_SUB_TEXT / EXTRA_SUMMARY_TEXT）也常带金额，一并纳入
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim().orEmpty()
        // 折叠通知（"[2条]"）的明细行：InboxStyle 在 EXTRA_TEXT_LINES、消息样式在 EXTRA_MESSAGES
        val detailLines = buildList {
            runCatching {
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.forEach { line -> line?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) } }
            }
            runCatching {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { parcels ->
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcels)
                        .forEach { message ->
                            message.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                }
            }
        }
        return NotificationText(
            key = sbn.key ?: "${sbn.packageName}|${sbn.id}",
            packageName = sbn.packageName.orEmpty(),
            groupKey = sbn.groupKey,
            group = null,
            postTime = sbn.notification.`when`,
            systemPostTime = sbn.postTime,
            title = title,
            text = NotificationText.joinDistinct(listOf(text, subText, summaryText) + detailLines),
            bigText = bigText,
            ticker = sbn.notification.tickerText?.toString()?.trim().orEmpty(),
        )
    }

    private companion object {
        /** 分级重连延迟档位（毫秒）：立刻 / 1.5s / 6s */
        val REBIND_DELAYS = longArrayOf(0L, 1_500L, 6_000L)
    }
}
