"""运行 DAO 原始 SQL 验证退款候选、撤销顺序及待处理隔离（无需 Android 设备）。"""
import json
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAO = (ROOT / "app/src/main/java/com/controleco/app/data/db/Daos.kt").read_text(encoding="utf-8")


def query(method):
    before = DAO[:DAO.index("fun " + method + "(")]
    annotation = before[before.rindex("@Query("):]
    return re.search(r'"""([\s\S]*?)"""|"([^"\n]*)"', annotation).group(0).strip('"')


db = sqlite3.connect(":memory:")
schema = json.loads((ROOT / "app/schemas/com.controleco.app.data.db.AccBookDatabase/14.json").read_text())
for entity in schema["database"]["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))


def insert(table, **values):
    # 根据实际 schema 填充无默认值的必需字段，再覆盖测试值。
    for _, name, kind, required, default, primary in db.execute(f"PRAGMA table_info({table})"):
        if name not in values and required and default is None and not primary:
            values[name] = "" if kind == "TEXT" else 0
    db.execute(f"INSERT INTO {table} ({','.join(values)}) VALUES ({','.join('?' for _ in values)})", list(values.values()))


insert("accounts", id=1, name="现金")
insert("accounts", id=2, name="另一账户")
insert("categories", id=1, type="EXPENSE", name="其他")
insert("categories", id=2, type="INCOME", name="退款")
insert("categories", id=3, type="INCOME", name="其他")
for i in range(1, 81):
    insert("transactions", id=i, type="EXPENSE", amountCents=100, categoryId=1,
           accountId=1, timestamp=i, refundMark="NONE", counterparty="历史商户")
insert("transactions", id=81, type="EXPENSE", amountCents=200, categoryId=1, accountId=1, timestamp=81, refundMark="NONE")
insert("transactions", id=82, type="EXPENSE", amountCents=100, categoryId=1, accountId=1, timestamp=82, refundMark="REFUNDED_EXPENSE")
assert len(db.execute(query("refundCandidates"), {"amountCents": None}).fetchall()) == 81
assert len(db.execute(query("refundCandidates"), {"amountCents": 100}).fetchall()) == 80

# 顺序特意与插入 ID 不同：按业务时间选第一笔，不跨账户/商户匹配；普通收入和不同额退款不能被删。
for ident, timestamp, amount, category, account in [(90, 50, 100, 2, 1), (91, 110, 100, 2, 1),
        (92, 100, 100, 2, 2), (93, 100, 100, 2, 1), (94, 90, 100, 3, 1), (95, 90, 200, 2, 1)]:
    insert("transactions", id=ident, type="INCOME", amountCents=amount, categoryId=category,
           accountId=account, timestamp=timestamp, refundMark="REFUND_INCOME")
params = {"amountCents": 100, "afterTimestamp": 80}
refund = db.execute(query("firstRefundIncomeAfter"), params).fetchone()
assert refund[0] == 92
db.execute(query("deleteById"), {"id": refund[0]})
assert db.execute(query("firstRefundIncomeAfter"), params).fetchone()[0] == 93
assert db.execute(query("firstRefundIncomeAfter"), {"amountCents": 300, "afterTimestamp": 80}).fetchone() is None
db.execute(query("clearExpenseRefund"), {"expenseId": 82})
assert db.execute("SELECT refundMark FROM transactions WHERE id=82").fetchone()[0] == "NONE"

# 普通缺金额条目清理不得误删无金额退款。
for ident, mark in [(1, "NONE"), (2, "REFUND_INCOME")]:
    insert("pending_transactions", id=ident, type="INCOME", amountCents=0, timestamp=100,
           sourcePackage="test", refundMark=mark, status="PENDING", dedupKey=str(ident))
db.execute(query("deleteAmountStubs"), {"sourcePackage": "test", "type": "INCOME", "from": 0, "to": 200})
assert db.execute("SELECT id FROM pending_transactions").fetchall() == [(2,)]
print("PASS: actual DAO queries; unlimited historical candidates, exact amounts, undo order, refund stub isolation")
