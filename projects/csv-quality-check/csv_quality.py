"""Check the shape, completeness, and optional key uniqueness of a CSV file."""

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path


def inspect_csv(path, delimiter, key_columns):
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.reader(source, delimiter=delimiter, strict=True)
        header = next(reader, None)
        if not header or any(not name.strip() for name in header):
            raise ValueError("CSV must start with a header with non-empty column names")
        if len(header) != len(set(header)):
            raise ValueError("CSV header contains duplicate column names")

        unknown_columns = set(key_columns) - set(header)
        if unknown_columns:
            raise ValueError(f"Unknown key columns: {', '.join(sorted(unknown_columns))}")
        key_indexes = [header.index(name) for name in key_columns]
        missing = dict.fromkeys(header, 0)
        keys = Counter()
        rows = malformed_rows = missing_key_rows = 0

        for row in reader:
            rows += 1
            if len(row) != len(header):
                malformed_rows += 1
                continue

            values = [value.strip() for value in row]
            for name, value in zip(header, values):
                missing[name] += not value

            if key_indexes:
                key = tuple(values[index] for index in key_indexes)
                if all(key):
                    keys[key] += 1
                else:
                    missing_key_rows += 1

    duplicates = sum(count - 1 for count in keys.values() if count > 1)
    return {
        "rows": rows,
        "malformed_rows": malformed_rows,
        "missing_values": missing,
        "key_columns": key_columns,
        "missing_key_rows": missing_key_rows,
        "duplicate_key_rows": duplicates,
        "passed": rows > 0 and not (malformed_rows or any(missing.values()) or duplicates),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path", type=Path)
    parser.add_argument("--delimiter", default=",", help="single-character delimiter")
    parser.add_argument(
        "--key", action="append", default=[], metavar="COLUMN",
        help="check uniqueness of this column, repeat to form a composite key",
    )
    args = parser.parse_args()
    if len(args.delimiter) != 1 or args.delimiter in '\r\n"\0':
        parser.error("--delimiter must be one character distinct from quote, NUL and newline")
    if len(args.key) != len(set(args.key)):
        parser.error("--key cannot name the same column twice")

    try:
        report = inspect_csv(args.csv_path, args.delimiter, args.key)
    except (OSError, ValueError, csv.Error) as error:
        print(f"CSV error: {error}", file=sys.stderr)
        return 2
    # ASCII escapes keep JSON readable by tools across Windows console encodings.
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
