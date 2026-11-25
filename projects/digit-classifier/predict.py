import argparse
from pathlib import Path

import joblib
import numpy as np
from sklearn.datasets import load_digits


def main():
    parser = argparse.ArgumentParser()
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--image", type=Path, help="8×8 CSV with pixel values from 0 to 16")
    inputs.add_argument("--example", type=int, help="Sample index from the Digits dataset")
    args = parser.parse_args()

    if args.example is not None:
        dataset = load_digits()
        if not 0 <= args.example < len(dataset.images):
            parser.error("example index is outside the dataset")
        pixels = dataset.images[args.example]
        print(f"Expected: {dataset.target[args.example]}")
    else:
        try:
            pixels = np.loadtxt(args.image, delimiter=",")
        except (OSError, ValueError) as error:
            parser.error(str(error))

    if pixels.shape != (8, 8) or not np.all((pixels >= 0) & (pixels <= 16)):
        parser.error("expected an 8×8 matrix of pixel values between 0 and 16")

    model_path = Path("artifacts/model.joblib")
    if not model_path.is_file():
        parser.error("run train.py first")
    model = joblib.load(model_path)
    print(f"Prediction: {model.predict(pixels.reshape(1, -1))[0]}")


if __name__ == "__main__":
    main()
