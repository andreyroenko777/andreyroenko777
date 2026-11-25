import numpy as np
from sklearn.datasets import load_digits
from sklearn.dummy import DummyClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score
from sklearn.model_selection import GridSearchCV, StratifiedKFold, train_test_split
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC


def main():
    features, target = load_digits(return_X_y=True)
    x_train, x_test, y_train, y_test = train_test_split(
        features, target, test_size=0.25, stratify=target, random_state=42
    )
    cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    candidates = {
        "Dummy": (DummyClassifier(strategy="most_frequent"), {}),
        "LogisticRegression": (
            LogisticRegression(max_iter=2000), {"logisticregression__C": [0.1, 1, 10]}
        ),
        "SVC": (SVC(), {"svc__C": [1, 3, 10], "svc__gamma": ["scale", 0.01]}),
    }

    searches = {}
    for name, (model, params) in candidates.items():
        search = GridSearchCV(
            make_pipeline(StandardScaler(), model), params, cv=cv, scoring="f1_macro"
        ).fit(x_train, y_train)
        searches[name] = search
        print(f"{name}: CV macro-F1={search.best_score_:.4f}, {search.best_params_}")

    winner = max(searches, key=lambda name: searches[name].best_score_)
    predictions = searches[winner].predict(x_test)
    print(f"Winner: {winner}")
    print(f"Holdout accuracy: {accuracy_score(y_test, predictions):.4f}")
    print(f"Holdout macro-F1: {f1_score(y_test, predictions, average='macro'):.4f}")

    errors = confusion_matrix(y_test, predictions)
    np.fill_diagonal(errors, 0)
    actual, predicted = np.unravel_index(errors.argmax(), errors.shape)
    print(f"Most common error: {actual} -> {predicted} ({errors[actual, predicted]})")


if __name__ == "__main__":
    main()
