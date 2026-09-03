#!/usr/bin/env python3
"""Re-runnable test-gap audit: cross-references every main Kotlin class against test sources.

Method: a main class counts as REFERENCED-IN-TESTS only if some test file names it
(string contains its simple name). Coverage-by-name-reference is a PROXY — it says
"a test knows this type exists", not "behavior is fully exercised". Files with zero
name references are the hard gaps; files with references need judgment (call the
classification a floor, not a ceiling).
"""
import os, re, json, collections

ROOT = "/Users/msabir/development/projects/datapipelines"

def kfiles(base, sub):
    out = []
    for dirpath, _, names in os.walk(os.path.join(base, sub)):
        for n in names:
            if n.endswith(".kt"):
                out.append(os.path.join(dirpath, n))
    return out

modules = sorted(os.listdir(os.path.join(ROOT, "modules")))
report = {}
all_tests_by_module = {}
for m in modules:
    mdir = os.path.join(ROOT, "modules", m)
    if not os.path.isdir(mdir):
        continue
    main_files = kfiles(mdir, "src/main")
    test_files = kfiles(mdir, "src/test")
    all_tests_by_module[m] = test_files
    # also cross-module suite can reference module classes
    it_dir = os.path.join(ROOT, "tests/integration-tests")
    it_files = kfiles(it_dir, "src") if os.path.isdir(it_dir) else []

    test_blobs = []
    for tf in test_files + it_files:
        try:
            test_blobs.append(open(tf, encoding="utf-8", errors="replace").read())
        except OSError:
            pass

    entries = []
    for mf in main_files:
        src = open(mf, encoding="utf-8", errors="replace").read()
        # top-level declarations with a name
        decls = re.findall(r'^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |abstract |open |sealed |final |data |enum |value |annotation |fun |class |interface |object |)*\b(?:class|interface|object|enum class)\s+(\w+)', src, re.M)
        # simpler: any class/interface/object name
        decls2 = re.findall(r'\b(?:class|interface|object)\s+(\w+)', src)
        names = set(decls2)
        rel = os.path.relpath(mf, mdir)
        pkg_path = os.path.dirname(rel)
        loc = len(src.splitlines())
        # which tests reference any declared name
        refs = set()
        for n in names:
            for tb in test_blobs:
                if n in tb:
                    refs.add(n)
                    break
        entries.append({
            "file": rel, "pkg": pkg_path, "loc": loc, "decls": sorted(names),
            "uncovered_decls": sorted(names - refs),
            "fully_unreferenced": len(refs) == 0 and len(names) > 0,
            "no_decls": len(names) == 0,
        })
    report[m] = entries

# classification by package/layer keyword
def layer_of(entry, module):
    p = entry["pkg"]
    f = entry["file"]
    if re.search(r'(controller|/ui/|web)', p, re.I) or re.search(r'Controller', f):
        return "controller"
    if re.search(r'(repository|repo)', p, re.I) or re.search(r'Repository', f):
        return "persistence"
    if module == "mcp-server" or re.search(r'Tool|mcp', p, re.I):
        return "mcp-tool"
    return "domain"

summary = {}
detail_rows = []
for m, entries in report.items():
    total = len(entries)
    gap = [e for e in entries if e["fully_unreferenced"] and not e["no_decls"]]
    partial = [e for e in entries if not e["fully_unreferenced"] and e["uncovered_decls"] and not e["no_decls"]]
    by_layer = collections.Counter(layer_of(e, m) for e in entries)
    gap_by_layer = collections.Counter(layer_of(e, m) for e in gap)
    summary[m] = {"main_files": total, "zero_test_reference": len(gap), "partial": len(partial),
                  "by_layer": dict(by_layer), "gap_by_layer": dict(gap_by_layer),
                  "test_files": len(all_tests_by_module[m])}
    for e in gap:
        detail_rows.append((m, layer_of(e, m), e["loc"], e["file"], ",".join(e["uncovered_decls"][:8])))

print("=== SUMMARY (per module) ===")
for m, s in summary.items():
    print(f"{m:20s} main={s['main_files']:3d} tests={s['test_files']:3d} zero-ref={s['zero_test_reference']:3d} partial={s['partial']:3d} layers={s['by_layer']} gaps={s['gap_by_layer']}")

print("\n=== ZERO-REFERENCE MAIN FILES (sorted by LOC desc) ===")
detail_rows.sort(key=lambda r: -r[2])
for m, layer, loc, f, decls in detail_rows:
    print(f"{loc:5d}  {m:18s} {layer:12s} {f:75s} {decls}")

print(f"\nTOTAL zero-reference main files: {len(detail_rows)}")
