import argparse
from pathlib import Path
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.metrics import mean_absolute_error


def generate_sales():
    rng = np.random.default_rng(42)
    days = pd.date_range("2025-01-01", periods=280)
    frames = []
    for sku, base in enumerate(np.linspace(14, 120, 12)):
        promo = rng.random(len(days)) < 0.19
        weekend = days.dayofweek >= 5
        trend = 1 + np.arange(len(days)) * (sku - 3) / 4500
        rate = base * trend * (1 + 0.22 * weekend + 0.65 * promo)
        frames.append(pd.DataFrame({
            "date": days, "sku": sku, "weekday": days.dayofweek,
            "promo": promo.astype(int), "units": rng.poisson(rate),
        }))
    return pd.concat(frames, ignore_index=True)


def prepare_features(sales):
    sales = sales.sort_values(["sku", "date"]).copy()
    history = sales.groupby("sku")["units"]
    sales["lag_1"] = history.shift(1)
    sales["lag_7"] = history.shift(7)
    sales["mean_28"] = history.transform(lambda values: values.shift(1).rolling(28).mean())
    return sales.dropna().reset_index(drop=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("output"))
    args = parser.parse_args()
    data = prepare_features(generate_sales())
    features = ["sku", "weekday", "promo", "lag_1", "lag_7", "mean_28"]
    dates = sorted(data["date"].unique())
    validation_start, test_start = dates[-56], dates[-28]
    train = data[data["date"] < validation_start]
    validation = data[(data["date"] >= validation_start) & (data["date"] < test_start)]
    test = data[data["date"] >= test_start].copy()

    scores = {}
    for leaves in (7, 15, 31):
        estimator = HistGradientBoostingRegressor(max_leaf_nodes=leaves, random_state=42)
        estimator.fit(train[features], train["units"])
        scores[leaves] = mean_absolute_error(validation["units"], estimator.predict(validation[features]))

    best = min(scores, key=scores.get)
    estimator = HistGradientBoostingRegressor(max_leaf_nodes=best, random_state=42)
    history = data[data["date"] < test_start]
    estimator.fit(history[features], history["units"])
    test["prediction"] = estimator.predict(test[features]).clip(min=0)
    test["baseline"] = test["lag_7"]
    group_keys = ["sku", "weekday", "promo"]
    medians = history.groupby(group_keys)["units"].median().rename("group_median")
    test = test.join(medians, on=group_keys)
    test["group_median"] = test["group_median"].fillna(test["baseline"])
    for name in ("baseline", "group_median", "prediction"):
        test[f"{name}_error"] = (test["units"] - test[name]).abs()
        mae = test[f"{name}_error"].mean()
        wape = test[f"{name}_error"].sum() / test["units"].sum()
        print(f"{name}: MAE={mae:.2f}, WAPE={wape:.2%}")

    args.output.mkdir(parents=True, exist_ok=True)
    test[["date", "sku", "promo", "units", "baseline", "group_median", "prediction"]].to_csv(
        args.output / "predictions.csv", index=False
    )
    test.groupby("sku").agg(
        baseline_mae=("baseline_error", "mean"), median_mae=("group_median_error", "mean"),
        model_mae=("prediction_error", "mean")
    ).to_csv(args.output / "sku_errors.csv")
    print(f"Validation MAE: {scores}; selected leaves: {best}; test rows: {len(test)}")


if __name__ == "__main__":
    main()
