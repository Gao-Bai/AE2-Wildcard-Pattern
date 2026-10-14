"""Keeps the README's jar size/hash in sync with whatever is actually in dist/ (idempotent)."""

import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"


def main() -> None:
    jars = sorted((ROOT / "dist").glob("wildcardencoder-*.jar"))
    if not jars:
        raise SystemExit("no jar in dist/")
    jar = jars[-1]
    data = jar.read_bytes()
    size = f"{len(data):,}"
    digest = hashlib.sha256(data).hexdigest().upper()
    print(f"dist jar: {jar.name}  {size} bytes  sha256={digest}")

    text = README.read_text(encoding="utf-8")
    before = text
    text = re.sub(r"(`dist/wildcardencoder-[0-9.]+\.jar`（)[^）]*( 字节）)", rf"\g<1>{size}\g<2>", text)
    text = re.sub(r"(\| SHA-256 \| `)[0-9A-Fa-f]{64}(` \|)", rf"\g<1>{digest}\g<2>", text)
    README.write_text(text, encoding="utf-8")
    print("README size/hash updated" if text != before else "README already in sync")


if __name__ == "__main__":
    main()
