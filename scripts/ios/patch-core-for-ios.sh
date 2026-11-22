#!/bin/bash
set -euo pipefail

# Add `import kotlinx.coroutines.IO` to any commonMain Kotlin file under core/
# that references `Dispatchers.IO` without importing the KMP IO extension.
python3 - <<'PY'
import pathlib

root = pathlib.Path("core")
patched = 0
for path in root.rglob("commonMain/**/*.kt"):
    text = path.read_text(encoding="utf-8")
    if "Dispatchers.IO" in text and "import kotlinx.coroutines.IO" not in text:
        if "import kotlinx.coroutines.Dispatchers" in text:
            text = text.replace(
                "import kotlinx.coroutines.Dispatchers",
                "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.IO",
                1,
            )
            path.write_text(text, encoding="utf-8")
            print(f"Patched Dispatchers.IO import in {path}")
            patched += 1
print(f"Total core files patched: {patched}")
PY
