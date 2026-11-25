from pathlib import Path
import random
import sqlite3

import pandas as pd


def make_orders():
    rng = random.Random(42)
    rows = []
    for month in range(1, 7):
        for channel in ("Ozon", "Wildberries"):
            return_rate = 0.08 if channel == "Ozon" or month < 5 else 0.22
            for _ in range(80 + 8 * month):
                rows.append({
                    "order_date": f"2025-{month:02d}-{rng.randint(1, 28):02d}",
                    "channel": channel,
                    "gross": rng.randint(500, 5000),
                    "returned": int(rng.random() < return_rate),
                })
    return pd.DataFrame(rows)


def main():
    orders = make_orders()
    query = Path(__file__).with_name("metrics.sql").read_text(encoding="utf-8")
    with sqlite3.connect(":memory:") as connection:
        orders.to_sql("orders", connection, index=False)
        metrics = pd.read_sql_query(query, connection)

    expected = orders.assign(
        month=orders["order_date"].str[:7],
        net_revenue=orders["gross"].where(orders["returned"].eq(0), 0),
    ).groupby(["month", "channel"])["net_revenue"].sum()
    pd.testing.assert_series_equal(
        metrics.set_index(["month", "channel"])["net_revenue"], expected
    )

    output = Path("marketplace_report.csv")
    metrics.to_csv(output, index=False)
    print(metrics.to_string(index=False))
    print(f"Saved {output} ({len(orders)} orders)")


if __name__ == "__main__":
    main()
