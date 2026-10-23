package com.controleco.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 列表页一行：交易 + 关联分类/账户名（categoryIcon = 分类自定义图标规格） */
data class TransactionRow(
    val id: Long,
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val note: String,
    val counterparty: String,
    val source: TxSource,
    val categoryId: Long,
    val categoryName: String,
    val categoryIcon: String = "",
    val accountId: Long,
    val accountName: String,
    val refundMark: RefundMark,
)

data class TypeTotalRow(
    val type: TxType,
    val totalCents: Long,
)

data class CategorySumRow(
    val categoryId: Long,
    val categoryName: String,
    val totalCents: Long,
)

/** 账户余额行：初始金额 + 该账户交易净额（支出为负） */
data class AccountBalanceRow(
    val accountId: Long,
    val initialBalanceCents: Long,
    val signedSumCents: Long,
)

@Dao
interface TransactionDao {

    /**
     * 区间内的流水。退款记录（`REFUNDED_EXPENSE` / `REFUND_INCOME`）始终参与查询与统计——
     * "显示退款记录"开关已删除，退款永远作为收入显示，被退款的原支出照常作为支出显示。
     */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis
           ORDER BY t.timestamp DESC, t.id DESC"""
    )
    fun observeRows(startMillis: Long, endMillis: Long): Flow<List<TransactionRow>>

    @Query(
        """SELECT type AS type, SUM(amountCents) AS totalCents FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
           GROUP BY type"""
    )
    fun observeTypeTotals(startMillis: Long, endMillis: Long): Flow<List<TypeTotalRow>>

    @Query(
        """SELECT c.id AS categoryId, c.name AS categoryName, SUM(t.amountCents) AS totalCents
           FROM transactions t JOIN categories c ON t.categoryId = c.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis AND t.type = :type
           GROUP BY c.id, c.name ORDER BY totalCents DESC"""
    )
    fun observeCategorySums(startMillis: Long, endMillis: Long, type: TxType): Flow<List<CategorySumRow>>

    /** 退款到账金额合计（预算/统计口径：支出合计 − 本合计 = 现金净流出） */
    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
             AND type = 'INCOME' AND refundMark = 'REFUND_INCOME'"""
    )
    fun observeRefundIncomeTotal(startMillis: Long, endMillis: Long): Flow<Long>

    @Query(
        """SELECT a.id AS accountId, a.initialBalanceCents AS initialBalanceCents,
           COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN -t.amountCents ELSE t.amountCents END), 0) AS signedSumCents
           FROM accounts a LEFT JOIN transactions t ON t.accountId = a.id
           GROUP BY a.id"""
    )
    fun observeBalances(): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /** 自动入账后页面通道捕获同笔：按捕获去重键（externalId = "NTF:" + dedupKey）回填更全的页面信息 */
    @Query(
        """UPDATE transactions
           SET amountCents = :amountCents, timestamp = :timestamp,
               counterparty = :counterparty, note = :note
           WHERE externalId = :externalId"""
    )
    suspend fun updateCapturedByExternalId(externalId: String, amountCents: Long, timestamp: Long, counterparty: String, note: String)

    /** 悬浮卡"先入账、后修改"：按捕获去重键找回该笔交易（externalId = "NTF:" + dedupKey） */
    @Query("SELECT id FROM transactions WHERE externalId = :externalId LIMIT 1")
    suspend fun findIdByExternalId(externalId: String): Long?

    /**
     * 页面补充了可信支付方式后重新匹配账户：只更新账户，不动金额/分类/商户（用户编辑保护由调用方判断）。
     * 账户余额与统计由 Room 的 Flow 自动刷新。
     */
    @Query("UPDATE transactions SET accountId = :accountId WHERE externalId = :externalId")
    suspend fun updateAccountByExternalId(externalId: String, accountId: Long)

    /** 页面通道回填时把支付流程标识写到正式交易（可追踪"这笔账属于哪次支付流程"） */
    @Query("UPDATE transactions SET flowId = :flowId WHERE externalId = :externalId AND flowId = ''")
    suspend fun updateFlowIdByExternalId(externalId: String, flowId: String)

    /** 悬浮卡编辑同步：金额/分类/账户/商户回写已入账的交易（「忽略」与「撤销」走删除） */
    @Query(
        """UPDATE transactions
           SET amountCents = :amountCents, categoryId = :categoryId,
               accountId = :accountId, counterparty = :counterparty
           WHERE id = :id"""
    )
    suspend fun updateEnteredFields(id: Long, amountCents: Long, categoryId: Long, accountId: Long, counterparty: String)

    /** 退款关联候选：近 60 天未退款的支出（供"无金额退款通知→手动关联"使用） */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.type = 'EXPENSE' AND t.refundMark = 'NONE' AND t.timestamp >= :since
           ORDER BY t.timestamp DESC LIMIT 50"""
    )
    suspend fun recentUnrefundedExpenses(since: Long): List<TransactionRow>

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: Long): Int

    /** 删除分类时把其下流水改挂到兜底分类 */
    @Query("UPDATE transactions SET categoryId = :toCategoryId WHERE categoryId = :fromCategoryId")
    suspend fun reassignCategory(fromCategoryId: Long, toCategoryId: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
    suspend fun countByAccount(accountId: Long): Int

    // ---------- 批量删除（账单页） ----------

    /** 范围内记录数（含退款标记记录，批量删除不区分显示开关） */
    @Query(
        """SELECT COUNT(*) FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis"""
    )
    suspend fun countByRange(startMillis: Long, endMillis: Long): Int

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countAll(): Int

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM transactions WHERE timestamp >= :startMillis AND timestamp < :endMillis")
    suspend fun deleteByRange(startMillis: Long, endMillis: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(txs: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Insert
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CaptureDao {

    @Insert
    suspend fun insert(notification: CapturedNotificationEntity): Long

    @Query("UPDATE captured_notifications SET matchedRule = :rule, queued = :queued WHERE id = :id")
    suspend fun updateMatch(id: Long, rule: String, queued: Boolean)

    @Query("SELECT * FROM captured_notifications ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CapturedNotificationEntity>>

    @Query("SELECT * FROM captured_notifications WHERE source = :source ORDER BY id DESC LIMIT :limit")
    fun observeRecentBySource(source: CaptureChannel, limit: Int): Flow<List<CapturedNotificationEntity>>

    @Query("DELETE FROM captured_notifications WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM captured_notifications WHERE id NOT IN (SELECT id FROM captured_notifications ORDER BY id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM captured_notifications")
    suspend fun clearAll()
}

@Dao
interface PendingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pending: PendingTransactionEntity): Long

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE dedupKey = :dedupKey")
    suspend fun countByDedupKey(dedupKey: String): Int

    @Query("SELECT * FROM pending_transactions WHERE dedupKey = :dedupKey LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): PendingTransactionEntity?

    @Query("SELECT * FROM pending_transactions WHERE id = :id")
    suspend fun getById(id: Long): PendingTransactionEntity?

    @Query("SELECT * FROM pending_transactions WHERE status IN ('PENDING', 'ASK') ORDER BY createdAt DESC")
    fun observePending(): Flow<List<PendingTransactionEntity>>

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE status IN ('PENDING', 'ASK')")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_transactions WHERE status IN ('PENDING', 'ASK')")
    suspend fun getAllPending(): List<PendingTransactionEntity>

    /** 跨通道合并池：待确认 + 自动入账条目（AUTO 只用于页面信息回填，不进列表）；ASK 必须由用户决定 */
    @Query("SELECT * FROM pending_transactions WHERE status IN ('PENDING', 'AUTO') ORDER BY createdAt DESC LIMIT 200")
    suspend fun getMergeable(): List<PendingTransactionEntity>

    /** 页面信息优先回填：覆盖金额/时间/商户/备注/规则（跨通道同一笔，页面侧信息更全） */
    @Query(
        """UPDATE pending_transactions
           SET amountCents = :amountCents, timestamp = :timestamp, counterparty = :counterparty,
               note = :note, ruleName = :ruleName
           WHERE id = :id"""
    )
    suspend fun updateFromPage(id: Long, amountCents: Long, timestamp: Long, counterparty: String, note: String, ruleName: String)

    /** 页面捕获到的支付工具（账户匹配用；只在非空时写入，见 CaptureCenter） */
    @Query("UPDATE pending_transactions SET paymentInstrument = :instrument WHERE id = :id")
    suspend fun updatePaymentInstrument(id: Long, instrument: String)

    /** 用户手改过该笔：后续自动富化不再覆盖金额/商户/时间（用户输入优先） */
    @Query("UPDATE pending_transactions SET userEdited = 1 WHERE id = :id")
    suspend fun markUserEdited(id: Long)

    /** 页面融合时补记支付流程标识（已有值不覆盖；跨渠道条目允许同时保留通知实例标识） */
    @Query("UPDATE pending_transactions SET flowId = :flowId WHERE id = :id AND flowId = ''")
    suspend fun updateFlowIdIfBlank(id: Long, flowId: String)

    /** 通知融合时补记通知实例标识（已有值不覆盖） */
    @Query("UPDATE pending_transactions SET notificationInstanceId = :instanceId WHERE id = :id AND notificationInstanceId = ''")
    suspend fun updateNotificationInstanceIfBlank(id: Long, instanceId: String)

    /** 银行侧融合时补记银行事件标识（已有值不覆盖） */
    @Query("UPDATE pending_transactions SET bankInstanceId = :instanceId WHERE id = :id AND bankInstanceId = ''")
    suspend fun updateBankInstanceIfBlank(id: Long, instanceId: String)

    /** 页面融合时补记订单号（已有值不覆盖） */
    @Query("UPDATE pending_transactions SET orderId = :orderId WHERE id = :id AND orderId = ''")
    suspend fun updateOrderIdIfBlank(id: Long, orderId: String)

    /** 同一通知实例的既有条目：跨过时间窗口也能确认是"同一条通知的更新"，避免重复入队 */
    @Query(
        """SELECT * FROM pending_transactions
           WHERE notificationInstanceId = :instanceId AND status IN ('PENDING', 'AUTO')
           ORDER BY createdAt DESC LIMIT 1""",
    )
    suspend fun findByNotificationInstance(instanceId: String): PendingTransactionEntity?

    /** 同一银行事件（同一条短信/推送）的既有条目：跨过时间窗口也能确认是重复投递 */
    @Query(
        """SELECT * FROM pending_transactions
           WHERE bankInstanceId = :instanceId AND status IN ('PENDING', 'AUTO')
           ORDER BY createdAt DESC LIMIT 1""",
    )
    suspend fun findByBankInstance(instanceId: String): PendingTransactionEntity?

    /** 金额待补录回填：写金额并重算去重键（来源段/方向段/分钟桶不变） */
    @Query("UPDATE pending_transactions SET amountCents = :amountCents, dedupKey = :dedupKey WHERE id = :id")
    suspend fun updateAmountAndKey(id: Long, amountCents: Long, dedupKey: String)

    /** 完整捕获到达时清掉同笔的 0 元占位条（返回删除条数） */
    @Query(
        """DELETE FROM pending_transactions
           WHERE status = 'PENDING' AND amountCents = 0
             AND sourcePackage = :sourcePackage AND type = :type
             AND timestamp BETWEEN :from AND :to"""
    )
    suspend fun deleteAmountStubs(sourcePackage: String, type: TxType, from: Long, to: Long): Int

    /** 自动入账条目的合并窗口很短，次日清理（dedupKey 仍是交易的 externalId，删除不影响去重） */
    @Query("DELETE FROM pending_transactions WHERE status = 'AUTO' AND createdAt < :cutoff")
    suspend fun deleteAutoOlderThan(cutoff: Long)

    @Query("DELETE FROM pending_transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_transactions WHERE dedupKey = :dedupKey")
    suspend fun deleteByDedupKey(dedupKey: String)
}

@Dao
interface MerchantRuleDao {

    @Query("SELECT * FROM merchant_rules")
    suspend fun getAll(): List<MerchantRuleEntity>

    /** 管理页列表（最近学习的在前） */
    @Query("SELECT * FROM merchant_rules ORDER BY updatedAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT COUNT(*) FROM merchant_rules")
    suspend fun countAll(): Int

    @Query("SELECT * FROM merchant_rules WHERE matchKey = :matchKey AND type = :type LIMIT 1")
    suspend fun find(matchKey: String, type: TxType): MerchantRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRuleEntity): Long

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM merchant_rules")
    suspend fun clearAll()

    /** 条数封顶：按最近学习保留 */
    @Query("DELETE FROM merchant_rules WHERE id NOT IN (SELECT id FROM merchant_rules ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)
}

/**
 * 溯源列表行：原始快照 + 该笔入账的当前状态（联查解析，不在溯源表里冗余存状态）。 * - [linkedTxId] 非空 = 已入账的账单 id（externalId = "NTF:" + dedupKey，唯一索引保证最多一条；
 *   账单被删除后解析为空，溯源记录本身仍保留作证据）；
 * - [pendingCount] > 0 = 仍在待确认队列里（只数 PENDING 条目；自动入账留下的 AUTO 记录不算，它在合并池里只是回填用）。
 */
data class CaptureTraceRow(
    @Embedded val trace: CaptureTraceEntity,
    val linkedTxId: Long? = null,
    val pendingCount: Int = 0,
)

@Dao
interface TraceDao {

    @Insert
    suspend fun insert(trace: CaptureTraceEntity): Long

    @Query(
        """SELECT tr.*,
           (SELECT t.id FROM transactions t WHERE t.externalId = 'NTF:' || tr.dedupKey LIMIT 1) AS linkedTxId,
           (SELECT COUNT(*) FROM pending_transactions p
              WHERE p.dedupKey = tr.dedupKey AND p.status = 'PENDING') AS pendingCount
           FROM capture_traces tr ORDER BY tr.id DESC LIMIT :limit"""
    )
    fun observeRecent(limit: Int): Flow<List<CaptureTraceRow>>

    @Query("SELECT COUNT(*) FROM capture_traces")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM capture_traces WHERE id NOT IN (SELECT id FROM capture_traces ORDER BY id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM capture_traces")
    suspend fun clearAll()
}
