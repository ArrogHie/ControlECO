package com.controleco.app.capture

import androidx.room.withTransaction
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.MerchantRuleDao
import com.controleco.app.data.db.PendingDao
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.util.AppLog

/**
 * 待确认条目的入账/丢弃动作（采集层与 UI 层共用）。
 *
 * 为什么抽共享层：悬浮确认卡没有 Activity/ViewModel 宿主，确认动作必须在采集层独立可执行；
 * 待确认列表（ViewModel）也要做同一件事。放一处，保证两侧口径一致——
 * externalId 规则、来源标记（TxSource）、退款标记、重复插入的处理方式、**商户归类学习**都只有一份。
 */
internal object PendingActions {

    /**
     * 确认入账：写交易（externalId = "NTF:" + dedupKey，唯一索引兜底防重）+ 删除待确认条目
     * + 学习商户归类（越用越准）。
     * 返回新交易 id；若该笔已经入过账（重复通知/双通道竞态），唯一索引冲突按"已记录"处理，
     * 返回 null 且不抛异常、不重复写账。
     */
    suspend fun confirm(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        merchantRuleDao: MerchantRuleDao? = null,
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String = item.counterparty,
        amountCents: Long = item.amountCents,
        note: String = item.note,
    ): Long? {
        val externalId = "NTF:${item.dedupKey}"
        val counterpartyValue = counterparty.trim().ifBlank { item.counterparty }
        val txId = try {
            db.withTransaction {
                val id = txDao.insert(
                    TransactionEntity(
                        type = item.type,
                        amountCents = amountCents,
                        categoryId = categoryId,
                        accountId = accountId,
                        timestamp = item.timestamp,
                        note = note.ifBlank { item.ruleName },
                        counterparty = counterpartyValue,
                        source = CaptureDefaults.txSource(item.sourcePackage),
                        externalId = externalId,
                        refundMark = item.refundMark,
                        // 捕获来的退款收入同样要用户指认原支出（进「待确认账单」）
                        refundPending = item.refundMark == RefundMark.REFUND_INCOME,
                        flowId = item.flowId,
                    ),
                )
                pendingDao.deleteById(item.id)
                id
            }
        } catch (e: Exception) {
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                // 已经入过账（重复通知/双通道竞态）：清理残留待确认条目即可
                pendingDao.deleteById(item.id)
                AppLog.i("pending", "already recorded, skip duplicate externalId=$externalId")
                null
            } else {
                throw e
            }
        }
        if (txId != null) {
            merchantRuleDao?.let {
                MerchantRules.learn(it, item.type, categoryId, accountId, counterpartyValue, item.note)
            }
            AppLog.i("pending", "confirmed #${item.id} externalId=$externalId tx=$txId")
        }
        return txId
    }

    /** 丢弃（误报纠错）：只删待确认条目，不产生账目 */
    suspend fun discard(pendingDao: PendingDao, id: Long) {
        pendingDao.deleteById(id)
        AppLog.i("pending", "discarded #$id")
    }

    /**
     * 已入账条目的编辑同步（悬浮卡"先入账、后修改"）：卡内当前值写回交易与 AUTO 条目，
     * 并学习商户归类（与 [confirm] 同一口径）。
     *
     * **只写用户真正改过的字段**（[editedFields]）。整行回写的问题是：卡片打开期间页面/银行通道
     * 可能又富化了一轮，而卡片快照是旧的——用户只改了备注，却用旧快照把金额/商户覆盖回去，
     * 等于"一次编辑抹掉一次富化"。因此：
     * - [GuardedField.AMOUNT] → 只写金额；
     * - [GuardedField.COUNTERPARTY] → 只写商户；
     * - [GuardedField.NOTE] → 只写备注；
     * - 分类/账户总能写（待确认条目表里没有这两列，且卡片上的分类/账户始终是当前值）；
     * - 没改过的字段一律不碰，交给富化通道继续维护。
     *
     * **不**给条目打整条 `userEdited`：用户改过哪几个字段由 [OverlayFieldGuards] 逐字段登记，
     * 后续富化只跳过这些字段（2026-10-27 用户口径）。
     * 返回 false = 找不到该笔交易（可能已被撤销），调用方只需静默收场。
     */
    suspend fun updateEntered(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        merchantRuleDao: MerchantRuleDao? = null,
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        amountCents: Long,
        note: String = item.note,
        editedFields: Set<GuardedField> = GuardedField.entries.toSet(),
    ): Boolean {
        val externalId = "NTF:${item.dedupKey}"
        val txId = txDao.findIdByExternalId(externalId) ?: return false
        val counterpartyValue = counterparty.trim().ifBlank { item.counterparty }
        // 用数据库里的最新备注/时间：卡片打开后页面可能又补了信息，用户只改了金额/商户，
        // 不能拿打开卡片时的旧快照把后到的富化结果覆盖回去
        val fresh = pendingDao.getById(item.id) ?: item
        val editAmount = GuardedField.AMOUNT in editedFields
        val editCounterparty = GuardedField.COUNTERPARTY in editedFields
        val editNote = GuardedField.NOTE in editedFields
        // 合并后的最终值：交易与 AUTO 条目必须一致（读者可能从任意一侧看这笔账）
        val finalAmount = if (editAmount) amountCents else fresh.amountCents
        val finalCounterparty = if (editCounterparty) counterpartyValue else fresh.counterparty
        val finalNote = if (editNote) note else fresh.note
        db.withTransaction {
            // 逐字段写交易：没改过的列一律不碰
            if (editAmount) txDao.updateAmount(txId, finalAmount)
            if (editCounterparty) txDao.updateCounterparty(txId, finalCounterparty)
            if (editNote) txDao.updateNote(txId, finalNote)
            txDao.updateCategoryAndAccount(txId, categoryId, accountId)
            // AUTO 条目同步：页面/银行通道后续富化以它为准，不能留旧值
            if (editAmount || editCounterparty || editNote) {
                pendingDao.updateFromPage(
                    item.id, finalAmount, fresh.timestamp, finalCounterparty, finalNote, fresh.ruleName,
                )
            }
        }
        merchantRuleDao?.let {
            MerchantRules.learn(it, item.type, categoryId, accountId, finalCounterparty, finalNote)
        }
        AppLog.i(
            "pending",
            "updated entered tx=#$txId amount=$finalAmount externalId=$externalId edited=$editedFields",
        )
        return true
    }

    /** 撤销已入账条目（悬浮卡「忽略」/ 通知「撤销」）：删除交易与对应 AUTO 条目，不留残影 */
    suspend fun undoEntered(
        db: AccBookDatabase,
        txDao: TransactionDao,
        pendingDao: PendingDao,
        dedupKey: String,
    ): Boolean {
        val externalId = "NTF:$dedupKey"
        val txId = txDao.findIdByExternalId(externalId)
        db.withTransaction {
            if (txId != null) txDao.deleteById(txId)
            pendingDao.deleteByDedupKey(dedupKey)
        }
        AppLog.i("pending", "undo entered tx=#$txId externalId=$externalId")
        return txId != null
    }
}
