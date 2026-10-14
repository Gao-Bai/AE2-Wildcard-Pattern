"""Static sanity check for the mod sources (no JDK 17 / Gradle available offline).

- strips comments and string/char literals
- checks bracket balance
- checks that the public top level type matches the file name
- checks that every imported simple name is actually referenced (unused import warning)
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

IMPORT_RE = re.compile(r"^import\s+(?:static\s+)?([\w.]+)\s*;", re.MULTILINE)


def strip_code(text: str) -> str:
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif ch == "/" and nxt == "*":
            i += 2
            while i < n - 1 and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif ch == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
        elif ch == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def check(path: Path) -> list[str]:
    problems = []
    raw = path.read_text(encoding="utf-8")
    code = strip_code(raw)

    for opener, closer, label in (("(", ")", "paren"), ("{", "}", "brace"), ("[", "]", "bracket")):
        if code.count(opener) != code.count(closer):
            problems.append(f"unbalanced {label}: {code.count(opener)} vs {code.count(closer)}")

    public_types = re.findall(r"public\s+(?:final\s+|abstract\s+)?(?:sealed\s+)?(?:class|interface|record|enum)\s+(\w+)", code)
    top_level = [name for name in public_types if name not in ("Values",)]
    if path.name != "package-info.java":
        expected = path.stem
        if expected not in public_types:
            problems.append(f"no public type named {expected} (found {public_types})")

    body = IMPORT_RE.sub("", code)
    for fqn in IMPORT_RE.findall(code):
        simple = fqn.rsplit(".", 1)[-1]
        if simple == "*":
            continue
        if not re.search(rf"\b{re.escape(simple)}\b", body):
            problems.append(f"unused import {fqn}")

    if "package " not in raw:
        problems.append("missing package declaration")

    return problems


def main() -> int:
    files = sorted(ROOT.rglob("*.java"))
    if not files:
        print(f"no java files under {ROOT.resolve()}")
        return 1
    total = 0
    for path in files:
        problems = check(path)
        total += len(problems)
        status = "OK  " if not problems else "FAIL"
        print(f"{status} {path.relative_to(ROOT)}")
        for problem in problems:
            print(f"       - {problem}")
    print(f"\n{len(files)} files, {total} problem(s)")
    return 1 if total else 0


if __name__ == "__main__":
    raise SystemExit(main())
