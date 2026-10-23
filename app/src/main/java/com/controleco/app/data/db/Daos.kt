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
    /** 退款收入所指名的原支出（null = 未指定；普通收支恒为 null） */
    val refundLinkId: Long? = null,
    /** 退款收入是否还在等用户指定原支出（决定是否出现在「待确认账单」） */
    val refundPending: Boolean = false,
    /** 入库时间：待确认列表把捕获条目与退款条目按这个排（而不是业务时间） */
    val createdAt: Long = 0,
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
           t.refundMark AS refundMark, t.refundLinkId AS refundLinkId, t.refundPending AS refundPending,
           t.createdAt AS createdAt
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

    /**
     * 本期分类**净支出**：每个分类的「支出 − 已关联的退款」。
     *
     * 退款按**到账时间**归属本期（与首页「本月已花 = 支出 − 退款到账」同口径），
     * 但扣到**原支出所属分类**上——这正是退款关联（[TransactionEntity.refundLinkId]）存在的理由。
     * 因此各分类净额之和 == 总支出 − 退款合计，圆环与清单不会互相对不上。
     *
     * 只有 `refundLinkId` 指向存在的支出时才参与扣减；未关联的退款只在总额里扣（见
     * [observePendingRefundTotal]），不进分类。分类净额可能为负（本期退款大于本期支出）。
     */
    @Query(
        """SELECT c.id AS categoryId, c.name AS categoryName, SUM(v.cents) AS totalCents
           FROM (
             SELECT t.categoryId AS categoryId, t.amountCents AS cents, t.timestamp AS timestamp
             FROM transactions t WHERE t.type = 'EXPENSE'
             UNION ALL
             SELECT e.categoryId AS categoryId, -r.amountCents AS cents, r.timestamp AS timestamp
             FROM transactions r JOIN transactions e ON e.id = r.refundLinkId
             WHERE r.type = 'INCOME' AND r.refundLinkId IS NOT NULL
           ) v
           JOIN categories c ON v.categoryId = c.id
           WHERE v.timestamp >= :startMillis AND v.timestamp < :endMillis
           GROUP BY c.id, c.name
           ORDER BY totalCents DESC"""
    )
    fun observeNetCategorySums(startMillis: Long, endMillis: Long): Flow<List<CategorySumRow>>

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
           t.refundMark AS refundMark, t.refundLinkId AS refundLinkId, t.refundPending AS refundPending,
           t.createdAt AS createdAt
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.type = 'EXPENSE' AND t.refundMark = 'NONE' AND t.timestamp >= :since
           ORDER BY t.timestamp DESC LIMIT 50"""
    )
    suspend fun recentUnrefundedExpenses(since: Long): List<TransactionRow>

    // ---------- 退款关联（待处理的退款收入 → 被退款的原支出） ----------

    /**
     * 「待确认账单」里的退款：新导入/新捕获、还没指定原支出的退款收入。
     * 升级前的历史退款 `refundPending = 0`，不会出现在这里。
     */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark, t.refundLinkId AS refundLinkId, t.refundPending AS refundPending,
           t.createdAt AS createdAt
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.refundPending = 1 AND t.refundLinkId IS NULL
           ORDER BY t.timestamp DESC, t.id DESC"""
    )
    fun observePendingRefunds(): Flow<List<TransactionRow>>

    /** 待处理退款条数（个人页「待确认」胶囊与「全部确认」计数用） */
    @Query("SELECT COUNT(*) FROM transactions WHERE refundPending = 1 AND refundLinkId IS NULL")
    fun observePendingRefundCount(): Flow<Int>

    /** 本期**未关联**的待处理退款合计：统计页提示「X 元退款待关联，未计入分类」 */
    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
             AND refundMark = 'REFUND_INCOME' AND refundPending = 1 AND refundLinkId IS NULL"""
    )
    fun observePendingRefundTotal(startMillis: Long, endMillis: Long): Flow<Long>

    /**
     * 同金额的支出候选（待处理退款 sheet 用）：只列**金额相同**的支出，新的在前。
     * 已经被另一笔退款指名的支出不再列出（一笔支出只允许被一笔退款关联）。
     */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, c.icon AS categoryIcon,
           a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark, t.refundLinkId AS refundLinkId, t.refundPending AS refundPending,
           t.createdAt AS createdAt
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.type = 'EXPENSE' AND t.amountCents = :amountCents
             AND NOT EXISTS (SELECT 1 FROM transactions r WHERE r.refundLinkId = t.id)
           ORDER BY t.timestamp DESC LIMIT 50"""
    )
    suspend fun expenseCandidatesByAmount(amountCents: Long): List<TransactionRow>

    /** 是否已有别的退款指到这笔支出（一笔支出只允许被一笔退款关联） */
    @Query("SELECT COUNT(*) FROM transactions WHERE refundLinkId = :expenseId")
    suspend fun countRefundsLinkedTo(expenseId: Long): Int

    /** 退款收入指名原支出（只对还没关联的退款生效，重复点击/并发不会覆盖） */
    @Query(
        """UPDATE transactions SET refundLinkId = :expenseId, refundPending = 0
           WHERE id = :refundId AND refundLinkId IS NULL"""
    )
    suspend fun linkRefundToExpense(refundId: Long, expenseId: Long)

    /** 原支出补「已退」标记（部分退款时调用方不调；已有标记不覆盖） */
    @Query("UPDATE transactions SET refundMark = 'REFUNDED_EXPENSE' WHERE id = :expenseId AND refundMark = 'NONE'")
    suspend fun markExpenseRefunded(expenseId: Long)

    /** 「记为收入」：去掉退款标记与关联，当成普通收入——预算不再减它 */
    @Query(
        """UPDATE transactions SET refundMark = 'NONE', refundLinkId = NULL, refundPending = 0
           WHERE id = :refundId"""
    )
    suspend fun markRefundAsIncome(refundId: Long)

    /** 删除账单后清理指向已删支出的退款关联（否则那些退款会变成“已关联但没有分类”） */
    @Query(
        """UPDATE transactions SET refundLinkId = NULL
           WHERE refundLinkId IS NOT NULL
             AND refundLinkId NOT IN (SELECT id FROM transactions)"""
    )
    suspend fun clearDanglingRefundLinks()

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
