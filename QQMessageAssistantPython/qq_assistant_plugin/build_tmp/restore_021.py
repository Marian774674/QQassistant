"""把工作区源码回退到 0.2.21 打包时的状态（从 dist 的 lbpkg 里取出源码比对）。

只处理代码相关路径：manifest.yaml / main.py / requirements.txt / core / components / tests。
-py 先只报告差异；加 --apply 才真写盘。
"""
import pathlib
import shutil
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
pkg = root / "dist" / "Marian-qq_assistant_plugin-0.2.21.lbpkg"
tmp = root / "build_tmp" / "restore_021"

if tmp.exists():
    shutil.rmtree(tmp)
with zipfile.ZipFile(pkg) as z:
    z.extractall(tmp)

# manifest.yaml 不从这里回退：lbpkg 里的是 build 重新 dump 过的 YAML
# （缩进被重写，`Off` 还会被 YAML 1.1 当成布尔 false），版本号手工改。
pref = ("main.py", "requirements.txt", "core/", "components/", "tests/")
apply = "--apply" in sys.argv
same, diff, missing = [], [], []
for f in sorted(tmp.rglob("*")):
    if not f.is_file():
        continue
    rel = f.relative_to(tmp).as_posix()
    if not rel.startswith(pref):
        continue
    cur = root / rel
    if not cur.exists():
        missing.append(rel)
    elif cur.read_bytes() == f.read_bytes():
        same.append(rel)
    else:
        diff.append((rel, cur.stat().st_size, f.stat().st_size))

print("same:", len(same))
print("MISSING (in pkg, not on disk):", missing)
print("DIFF:")
for rel, a, b in diff:
    print(f"  {rel}: disk={a}B pkg={b}B")

if apply:
    for rel in missing:
        src = tmp / rel
        dst = root / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    for rel, _, _ in diff:
        shutil.copy2(tmp / rel, root / rel)
    print(f"\nrestored {len(missing) + len(diff)} file(s)")
