package com.controleco.app.data.db

/**
 * 退款收入统一归入的收入分类（退款永远显示、永远按收入记账）。
 *
 * 为什么单独一个分类：退款不是"挣到的钱"，混在工资/理财里会让收入结构失真；
 * 也不该按商户名猜成购物/餐饮——退款行的商户名与原支出相同，猜出来的分类毫无意义。
 * 因此三条入库路径（账单导入、通知/页面/短信捕获、退款关联页）都直接把退款收入归到 [NAME]。
 *
 * 取不到「退款」时按"收入「其他」→ 第一个收入分类"兜底：用户删掉该分类也不能让入账失败。
 */
object RefundCategory {

    /** 收入分类名（建档种子、数据库迁移、入账三处共用同一个字面量） */
    const val NAME = "退款"

    fun incomeCategoryId(categories: List<CategoryEntity>): Long? =
        categories.firstOrNull { it.type == TxType.INCOME && it.name == NAME }?.id
            ?: categories.firstOrNull { it.type == TxType.INCOME && it.name == "其他" }?.id
            ?: categories.firstOrNull { it.type == TxType.INCOME }?.id
}
