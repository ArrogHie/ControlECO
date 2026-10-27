package com.controleco.app.data.db

/**
 * 分类的**保留名与查重规则**（纯函数，可单测；VM 与界面共用同一套判断）。
 *
 * 两类分类被「锁」住，不允许改名 / 改类型 / 删除：
 * 1. 兜底分类「其他」：删除别的分类时，其流水要改挂到同类型的「其他」，改名/改类型会让兜底失效；
 * 2. 收入分类「退款」：退款收入入账的固定去向（见 [RefundCategory]），改名/改类型/删除会让
 *    「退款」这个收入项在统计里消失，历史与后续退款也没法归位。
 *
 * 查重只比**同一类型**：支出与收入各自可以有一个「餐饮」，互不影响；名称按 `trim()` 后比较。
 */
object CategoryRules {

    /** 兜底分类名（支出、收入各一个） */
    const val FALLBACK_NAME = "其他"

    fun isFallbackName(name: String): Boolean = name.trim() == FALLBACK_NAME

    /** 收入「退款」分类（退款收入入账的固定去向） */
    fun isRefundIncome(type: TxType, name: String): Boolean =
        type == TxType.INCOME && name.trim() == RefundCategory.NAME

    /** 界面上锁：不渲染名称输入框、类型分段与删除按钮置灰 */
    fun isLocked(category: CategoryEntity): Boolean =
        isFallbackName(category.name) || isRefundIncome(category.type, category.name)

    /**
     * 改名 / 改类型校验：返回错误文案，`null` = 允许。
     * [current] 为 null（新增）时永远允许。
     */
    fun editError(current: CategoryEntity?, newName: String, newType: TxType): String? {
        if (current == null) return null
        val name = newName.trim()
        if (isFallbackName(current.name) && (name != FALLBACK_NAME || newType != current.type)) {
            return "「$FALLBACK_NAME」不能改名或修改类型"
        }
        if (isRefundIncome(current.type, current.name) &&
            (name != current.name.trim() || newType != current.type)
        ) {
            return "「${RefundCategory.NAME}」不能改名或修改类型"
        }
        return null
    }

    /** 删除校验：返回错误文案，`null` = 允许 */
    fun deleteError(category: CategoryEntity): String? = when {
        isFallbackName(category.name) -> "「$FALLBACK_NAME」不能删除"
        isRefundIncome(category.type, category.name) -> "「${RefundCategory.NAME}」不能删除"
        else -> null
    }

    /** 同类型重名校验：返回错误文案，`null` = 允许（[id] 是正在编辑的分类，新增传 0） */
    fun duplicateNameError(
        all: List<CategoryEntity>,
        id: Long,
        newName: String,
        newType: TxType,
    ): String? =
        if (all.any { it.id != id && it.type == newType && it.name.trim() == newName.trim() }) {
            "已存在同名分类"
        } else {
            null
        }
}
