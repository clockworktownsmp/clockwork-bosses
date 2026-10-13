"""Check the exported schema with an independent Draft 2020-12 validator.

First run exportTemplateSchema and exportSchemaFixtures. Install the optional
requirements into build/schema-validator; no server, network or registry is used
by this script. Java's contract suite separately checks lossless JSON round trips.
"""

import json
import sys
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build" / "schema-validator"))
try:
    from jsonschema import Draft202012Validator, validators
except ModuleNotFoundError as missing:
    raise SystemExit(
        "Optional schema-check dependencies are missing. Install them with:\n"
        "python -m pip install --target build/schema-validator -r tools/schema-check-requirements.txt\n"
        "Then run exportTemplateSchema, exportSchemaFixtures and this script."
    ) from missing


def read(path):
    # Keep numbers exact in this validation process too. Python's usual float
    # parser would underflow/round unknown addon values before validation.
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)


def integer(checker, value):
    if isinstance(value, Decimal):
        if not value.is_finite():
            return False
        digits = value.as_tuple()
        # Inspect fractional digits without rounding/expanding a huge exponent.
        return digits.exponent >= 0 or all(digit == 0 for digit in digits.digits[max(0, len(digits.digits) + digits.exponent):])
    return Draft202012Validator.TYPE_CHECKER.is_type(value, "integer")


Validator = validators.extend(
    Draft202012Validator,
    type_checker=Draft202012Validator.TYPE_CHECKER.redefine("integer", integer),
)


def main():
    core = read(ROOT / "build" / "template-schema.json")
    Draft202012Validator.check_schema(core)
    Validator(core).validate(core["default"])
    folder = ROOT / "build" / "template-schema-fixtures"
    schema = read(folder / "schema.json")
    Draft202012Validator.check_schema(schema)
    validator = Validator(schema)
    valid = sorted((folder / "valid").glob("*.json"))
    invalid = sorted((folder / "invalid").glob("*.json"))
    if not valid or not invalid:
        raise AssertionError("Run exportSchemaFixtures before this check")
    for path in valid:
        validator.validate(read(path))
    for path in invalid:
        if not list(validator.iter_errors(read(path))):
            raise AssertionError(f"Invalid fixture accepted: {path.name}")
    print(f"JSON Schema checks passed: both schemas valid; {len(valid)} accepted and {len(invalid)} rejected fixtures.")


if __name__ == "__main__":
    main()
