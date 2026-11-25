from pathlib import Path
import sqlite3

import numpy as np
import pandas as pd


def simulate(seed=42, users=12_000):
    rng = np.random.default_rng(seed)
    assignments = []
    events = []
    for user in range(users):
        variant = "A" if rng.random() < 0.5 else "B"
        assignments.append((user, variant))
        if rng.random() >= 0.82:
            continue
        events.append((user, "checkout_opened", 1))
        if rng.random() >= (0.59 if variant == "A" else 0.64):
            continue
        events.append((user, "details_submitted", 2))
        if rng.random() < 0.71:
            events.append((user, "paid", 3))
            if rng.random() < 0.2:
                events.append((user, "paid", 3))
    return (
        pd.DataFrame(assignments, columns=["user_id", "variant"]),
        pd.DataFrame(events, columns=["user_id", "event", "event_time"]),
    )


def main():
    assignments, events = simulate()
    sql = Path(__file__).with_name("funnel.sql").read_text(encoding="utf-8")
    with sqlite3.connect(":memory:") as database:
        assignments.to_sql("assignments", database, index=False)
        events.to_sql("events", database, index=False)
        funnel = pd.read_sql_query(sql, database)

    if len(funnel) != len(assignments) or not funnel["user_id"].is_unique:
        raise ValueError("Expected one row per assigned user")
    if (funnel["paid"] > funnel["submitted"]).any() or (funnel["submitted"] > funnel["opened"]).any():
        raise ValueError("Unexpected event order")

    summary = funnel.groupby("variant").agg(
        users=("user_id", "size"), opened=("opened", "sum"),
        submitted=("submitted", "sum"), paid=("paid", "sum"),
    )
    summary["conversion"] = summary["paid"] / summary["users"]
    a, b = summary.loc["A"], summary.loc["B"]
    rng = np.random.default_rng(7)
    boot_a = rng.binomial(int(a.users), a.conversion, 20_000) / a.users
    boot_b = rng.binomial(int(b.users), b.conversion, 20_000) / b.users
    lower, upper = np.quantile(boot_b - boot_a, [0.025, 0.975])
    difference = b.conversion - a.conversion
    print(summary.to_string(float_format=lambda value: f"{value:.4f}"))
    print(f"B - A: {difference * 100:.2f} pp; 95% CI [{lower * 100:.2f}, {upper * 100:.2f}] pp")
    summary.to_csv("funnel_summary.csv")


if __name__ == "__main__":
    main()
