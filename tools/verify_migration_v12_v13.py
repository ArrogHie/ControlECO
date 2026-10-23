"""校验 v12 → v13 迁移（删除 transactions.refundLinkId）的 SQL 是否正确。

用法：在仓库根目录跑 `python tools/verify_migration_v12_v13.py`。
（中文 Windows 控制台先设 `PYTHONIOENCODING=utf-8`，否则输出里的 ✓ 会 UnicodeEncodeError。）

思路：不靠人眼比对，而是让 SQLite 自己比——
1. 用 app/schemas 里导出的 12.json 建一个 v12 库，灌入真实形态的数据（含 refundLinkId 有值的退款行）；
2. 跑 AccBookDatabase.MIGRATION_12_13 里的同一批 SQL；
3. 另建一个「Room 期望的 v13 库」（直接用 13.json 的 createSql）；
4. 比对两张 transactions 表的 PRAGMA（列名/类型/可空/默认值、外键、索引），并断言数据原样保留。

Room 打开库时校验的正是这些 PRAGMA，所以「迁移后的表 == 13.json 建出来的表」= Room 不会报
"Room cannot verify the data integrity"。

注意：MIGRATION_SQL 必须与 AccBookDatabase.MIGRATION_12_13 保持逐字一致，改迁移时同步改这里。
"""

import json
import os
import sqlite3
import sys

SCHEMA_DIR = os.path.join("app", "schemas", "com.controleco.app.data.db.AccBookDatabase")

# --- 与 AccBookDatabase.MIGRATION_12_13 逐字一致（只把 transactions_new 的最终名当参数） ---
MIGRATION_SQL = [
    "CREATE TABLE IF NOT EXISTS `transactions_new` ("
    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
    "`type` TEXT NOT NULL, "
    "`amountCents` INTEGER NOT NULL, "
    "`categoryId` INTEGER NOT NULL, "
    "`accountId` INTEGER NOT NULL, "
    "`timestamp` INTEGER NOT NULL, "
    "`note` TEXT NOT NULL, "
    "`counterparty` TEXT NOT NULL, "
    "`source` TEXT NOT NULL, "
    "`externalId` TEXT, "
    "`refundMark` TEXT NOT NULL, "
    "`refundPending` INTEGER NOT NULL DEFAULT 0, "
    "`flowId` TEXT NOT NULL DEFAULT '', "
    "`createdAt` INTEGER NOT NULL, "
    "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) "
    "ON UPDATE NO ACTION ON DELETE RESTRICT , "
    "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) "
    "ON UPDATE NO ACTION ON DELETE RESTRICT )",
    "INSERT INTO `transactions_new` (`id`, `type`, `amountCents`, `categoryId`, `accountId`, "
    "`timestamp`, `note`, `counterparty`, `source`, `externalId`, `refundMark`, "
    "`refundPending`, `flowId`, `createdAt`) "
    "SELECT `id`, `type`, `amountCents`, `categoryId`, `accountId`, `timestamp`, `note`, "
    "`counterparty`, `source`, `externalId`, `refundMark`, `refundPending`, `flowId`, "
    "`createdAt` FROM `transactions`",
    "DROP TABLE `transactions`",
    "ALTER TABLE `transactions_new` RENAME TO `transactions`",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_externalId` "
    "ON `transactions` (`externalId`)",
]


def load_schema(version):
    with open(os.path.join(SCHEMA_DIR, f"{version}.json"), encoding="utf-8") as f:
        return json.load(f)["database"]


def create_tables(con, db, table_names):
    """按导出 schema 建表（createSql 里的 ${TABLE_NAME} 换成真实表名）"""
    for entity in db["entities"]:
        if entity["tableName"] not in table_names:
            continue
        con.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            con.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))


def schema_of(con, table):
    """Room TableInfo 关心的三件事：列、外键、索引"""
    columns = {
        r[1]: (r[2].upper(), bool(r[3]), r[4] if r[4] is not None else None, bool(r[5]))
        for r in con.execute(f"PRAGMA table_info(`{table}`)")
    }
    foreign_keys = sorted(
        (r[2], r[3], r[4], r[5], r[6])  # table, from, to, on_update, on_delete
        for r in con.execute(f"PRAGMA foreign_key_list(`{table}`)")
    )
    indices = []
    for r in con.execute(f"PRAGMA index_list(`{table}`)"):
        name, unique = r[1], bool(r[2])
        if name.startswith("sqlite_autoindex"):
            continue
        cols = [c[2] for c in con.execute(f"PRAGMA index_info(`{name}`)")]
        indices.append((name, unique, tuple(cols)))
    return columns, foreign_keys, sorted(indices)


def main():
    v12, v13 = load_schema(12), load_schema(13)
    all_tables = [e["tableName"] for e in v12["entities"]]

    # --- 1) v12 老库 + 数据 ---
    old = sqlite3.connect(":memory:")
    old.execute("PRAGMA foreign_keys = ON")
    create_tables(old, v12, all_tables)
    old.executemany(
        "INSERT INTO accounts (id, name, initialBalanceCents, note, createdAt) VALUES (?,?,?,?,?)",
        [(1, "微信零钱", 0, "", 1), (2, "中国银行储蓄卡(8920)", 100000, "", 1)],
    )
    old.executemany(
        "INSERT INTO categories (id, name, type, sortOrder, icon) VALUES (?,?,?,?,?)",
        [(1, "餐饮", "EXPENSE", 0, ""), (2, "退款", "INCOME", 3, "")],
    )
    # id, type, amountCents, categoryId, accountId, timestamp, note, counterparty, source,
    # externalId, refundMark, refundLinkId, refundPending, flowId, createdAt
    rows = [
        # 被退款的原支出（已标已退款）
        (1, "EXPENSE", 5000, 1, 1, 1000, "", "姜胖胖", "IMPORT", "W1", "REFUNDED_EXPENSE", None, 0, "", 10),
        # 退款收入，v12 里 refundLinkId 指着上面那笔
        (2, "INCOME", 5000, 2, 1, 2000, "", "姜胖胖", "IMPORT", "W2", "REFUND_INCOME", 1, 0, "", 11),
        # 通知捕获的退款：refundPending = 1（还在待处理）
        (3, "INCOME", 854, 2, 1, 3000, "", "饿了么", "NOTIFICATION", None, "REFUND_INCOME", None, 1, "flow-x", 12),
        # 普通支出 / 普通收入，字段要一字不差地活下来
        (4, "EXPENSE", 3179, 1, 2, 4000, "外卖", "饿了么", "PAGE", "A9", "NONE", None, 0, "", 13),
        (5, "INCOME", 100000, 2, 2, 5000, "工资", "公司", "MANUAL", None, "NONE", None, 0, "", 14),
    ]
    old.executemany(
        "INSERT INTO transactions (id, type, amountCents, categoryId, accountId, timestamp, note, "
        "counterparty, source, externalId, refundMark, refundLinkId, refundPending, flowId, createdAt) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        rows,
    )
    old.commit()
    before = old.execute(
        "SELECT id, type, amountCents, categoryId, accountId, timestamp, note, counterparty, "
        "source, externalId, refundMark, refundPending, flowId, createdAt "
        "FROM transactions ORDER BY id"
    ).fetchall()
    assert len(before) == 5, before
    assert old.execute("PRAGMA table_info(`transactions`)").fetchall()[11][1] == "refundLinkId"

    # --- 2) 跑迁移 ---
    for sql in MIGRATION_SQL:
        old.execute(sql)
    old.commit()

    # --- 3) Room 期望的 v13 库 ---
    fresh = sqlite3.connect(":memory:")
    create_tables(fresh, v13, ["transactions", "accounts", "categories"])

    # --- 4) 比对 schema ---
    got, want = schema_of(old, "transactions"), schema_of(fresh, "transactions")
    labels = ("列（名/类型/非空/默认值/主键）", "外键", "索引")
    for label, g, w in zip(labels, got, want):
        if g != w:
            print(f"✗ {label} 不一致\n  迁移后: {g}\n  期望值: {w}")
            return 1
        print(f"✓ {label} 与 13.json 一致（{len(g)} 项）")
    assert "refundLinkId" not in got[0], "refundLinkId 没删掉"
    print("✓ refundLinkId 已从 transactions 删除")

    # --- 5) 数据原样保留 ---
    after = old.execute(
        "SELECT id, type, amountCents, categoryId, accountId, timestamp, note, counterparty, "
        "source, externalId, refundMark, refundPending, flowId, createdAt "
        "FROM transactions ORDER BY id"
    ).fetchall()
    assert after == before, f"数据被改动\n before={before}\n after={after}"
    print(f"✓ {len(after)} 行数据（含 id / refundPending / flowId / externalId）逐字保留")

    # --- 6) AUTOINCREMENT 序列没丢：新插入的 id 继续往后走 ---
    old.execute(
        "INSERT INTO transactions (type, amountCents, categoryId, accountId, timestamp, note, "
        "counterparty, source, refundMark, refundPending, createdAt) "
        "VALUES ('EXPENSE', 100, 1, 1, 6000, '', '', 'MANUAL', 'NONE', 0, 15)"
    )
    new_id = old.execute("SELECT MAX(id) FROM transactions").fetchone()[0]
    assert new_id == 6, new_id
    print("✓ 自增序列延续（新行 id = 6）")

    # --- 7) 唯一索引仍然拦重复单号 ---
    try:
        old.execute(
            "INSERT INTO transactions (type, amountCents, categoryId, accountId, timestamp, note, "
            "counterparty, source, externalId, refundMark, refundPending, createdAt) "
            "VALUES ('EXPENSE', 100, 1, 1, 7000, '', '', 'MANUAL', 'W1', 'NONE', 0, 16)"
        )
        print("✗ 唯一索引没生效：重复 externalId 竟然插入成功")
        return 1
    except sqlite3.IntegrityError:
        print("✓ externalId 唯一索引仍然拦重复单号")

    print("\nALL V13 MIGRATION CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
