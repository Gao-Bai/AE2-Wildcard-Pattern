#!/usr/bin/env python3
"""Fast compile check for individual sources during refactors.

Compiles only the given files (javac pulls in whatever else they need via -sourcepath) against the
same reconstructed classpath the offline build uses, so errors show up before the whole project is
consistent again.

Usage:  python tools/dev/check_compile.py src/main/java/com/ae2wildcard/encoder/rule/WildcardRules.java
        python tools/dev/check_compile.py --all
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_offline as b  # noqa: E402  (module import is side-effect free)

TMP = b.OUT / "check"


def main() -> None:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        raise SystemExit(2)
    if args == ["--all"]:
        files = sorted((b.PROJ / "src/main/java").rglob("*.java"))
    else:
        files = [Path(a).resolve() for a in args]

    if TMP.exists():
        for old in TMP.rglob("*.class"):
            old.unlink()
    TMP.mkdir(parents=True, exist_ok=True)

    command = [
        str(b.JAVAC), "-encoding", "UTF-8", "-nowarn", "-proc:none",
        "-sourcepath", str(b.PROJ / "src/main/java"),
        "-d", str(TMP), "-cp", b.compile_classpath(),
    ] + [str(f) for f in files]
    result = subprocess.run(command, text=True, capture_output=True)
    if result.stdout:
        print(result.stdout[:20000])
    if result.stderr:
        print(result.stderr[:4000])
    status = "OK" if result.returncode == 0 else f"FAILED ({result.returncode})"
    print(f"\ncheck_compile: {len(files)} file(s) -> {status}")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
