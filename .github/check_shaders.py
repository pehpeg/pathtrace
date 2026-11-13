"""Expand #include lines and run every shader through glslangValidator."""
import pathlib
import re
import subprocess
import sys
import tempfile

SHADERS = pathlib.Path("src/main/resources/assets/pathtrace/shaders")
INCLUDE = re.compile(r'^\s*#include\s+"([^"]+)"\s*$')


def expand(path: pathlib.Path) -> str:
    out = []
    for line in path.read_text().splitlines():
        m = INCLUDE.match(line)
        out.append(expand(SHADERS / m.group(1)) if m else line)
    return "\n".join(out) + "\n"


failed = False
with tempfile.TemporaryDirectory() as tmp:
    for src in sorted(SHADERS.iterdir()):
        stage = {".fsh": "frag", ".vsh": "vert"}.get(src.suffix)
        if stage is None:
            continue
        dst = pathlib.Path(tmp) / f"{src.stem}.{stage}"
        dst.write_text(expand(src))
        r = subprocess.run(["glslangValidator", str(dst)], capture_output=True, text=True)
        status = "ok" if r.returncode == 0 else "FAILED"
        print(f"{src.name}: {status}")
        if r.returncode != 0:
            failed = True
            print(r.stdout, r.stderr)
sys.exit(1 if failed else 0)
