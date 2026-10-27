package com.controleco.app.capture

import com.controleco.app.util.AppLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 悬浮确认卡里"用户改过哪些字段"的登记簿（纯内存，按条目去重键索引）。
 *
 * 为什么需要它：捕获是"先入账、后富化"——用户在卡片上确认/修改后，同一笔往往还会被另一条
 * 通道再抓一次（微信付款 → 返回电商结果页），合并逻辑会把页面的商户/备注写回去。
 * 用户口径（2026-10-27）：**用户动过的字段不许再被自动改写；没动过的字段照旧按原逻辑富化。**
 *
 * 与数据库列的关系：刻意不落库。
 * - 用户明确选择"只存内存"（改库要加迁移、收益与改动不成正比）；
 * - 代价已知并接受：进程在这个窗口内被杀掉后登记丢失，后续富化会重新覆盖用户值；
 * - 升级前已置位的 `pending_transactions.userEdited` 仍按"整条锁定"生效，见 [GuardedField]。
 *
 * 生命周期：卡片保存/确认时登记，条目被忽略/撤销/确认消失时清理。
 */
internal object OverlayFieldGuards {

    private val guards = ConcurrentHashMap<String, MutableSet<GuardedField>>()

    /** 登记：同一去重键多次编辑取并集（用户改过的字段只会越来越多） */
    fun mark(dedupKey: String, fields: Set<GuardedField>) {
        if (dedupKey.isBlank() || fields.isEmpty()) return
        guards.computeIfAbsent(dedupKey) { ConcurrentHashMap.newKeySet() }.addAll(fields)
        AppLog.i("Overlay", "guarded fields $fields key=$dedupKey")
    }

    /** 该条目当前受保护的字段（无登记 = 空集 = 全部照常富化） */
    fun fieldsOf(dedupKey: String): Set<GuardedField> {
        val set = guards[dedupKey] ?: return emptySet()
        return if (set.isEmpty()) emptySet() else set.toSet()
    }

    fun isGuarded(dedupKey: String, field: GuardedField): Boolean = guards[dedupKey]?.contains(field) == true

    /** 条目被丢弃/撤销/已入账确认后清理，避免去重键复用时误锁 */
    fun clear(dedupKey: String) {
        if (dedupKey.isBlank()) return
        if (guards.remove(dedupKey) != null) {
            AppLog.d("Overlay", "guards cleared key=$dedupKey")
        }
    }

    /** 仅测试用：清空全部登记 */
    internal fun clearAll() = guards.clear()
}
