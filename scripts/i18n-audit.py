#!/usr/bin/env python3
"""Auditoria de i18n — primeiro passo bloqueante do ci.sh (espelho do iOS).

Reprova quando:
1. O catálogo está incompleto (chave sem os 10 idiomas) ou os strings.xml
   gerados divergem do catálogo (gen-i18n.py --check).
2. Um R.string.* usado no código não existe no catálogo.
3. Um composable exibe string literal com texto de gente — o equivalente da
   regra do iOS contra LocalizedStringKey interpolada. Literal deliberado
   (wordmark, "20Hz–20kHz") é marcado com `// i18n-verbatim` na linha.
"""

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APP_SRC = REPO / "app" / "src" / "main" / "java"
GEN = REPO / "scripts" / "gen-i18n.py"

import importlib.util

spec = importlib.util.spec_from_file_location("gen_i18n", GEN)
gen_i18n = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen_i18n)

failures: list[str] = []

# 1. Catálogo íntegro + strings.xml em dia.
proc = subprocess.run([sys.executable, str(GEN), "--check"], capture_output=True, text=True)
if proc.returncode != 0:
    failures.append(proc.stdout.strip() or proc.stderr.strip())

catalog = gen_i18n.load_catalog()
known = {gen_i18n.key_to_res(k) for k in catalog} | {"app_name"}

# 2. Todo R.string usado existe.
used: set[str] = set()
kt_files = sorted(APP_SRC.rglob("*.kt")) if APP_SRC.exists() else []
for f in kt_files:
    for m in re.finditer(r"R\.string\.([A-Za-z0-9_]+)", f.read_text()):
        used.add(m.group(1))
unknown = sorted(used - known)
if unknown:
    failures.append("R.string sem chave no catálogo: " + ", ".join(unknown))

# 3. Literal com letras em composable sem marcador i18n-verbatim.
LITERAL = re.compile(r'\bText\(\s*"([^"]*)"|contentDescription\s*=\s*"([^"]*)"')
for f in kt_files:
    for i, line in enumerate(f.read_text().splitlines(), 1):
        if "i18n-verbatim" in line:
            continue
        m = LITERAL.search(line)
        if not m:
            continue
        text = m.group(1) or m.group(2) or ""
        if re.search(r"[A-Za-zÀ-ÿ]{2,}", text):
            failures.append(f"literal exibido sem i18n: {f.relative_to(REPO)}:{i}: {text!r}")

if failures:
    print("i18n-audit REPROVOU:")
    for f in failures:
        print(" -", f)
    sys.exit(1)

print(f"i18n-audit ok: {len(catalog)} chaves × 10 idiomas; {len(used)} usadas no app; sem literal solto.")
