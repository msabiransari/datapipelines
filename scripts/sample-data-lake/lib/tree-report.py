"""Print a rendered publish tree: one line per top-level table, then the total.

    python3 lib/tree-report.py <dir> [1]        # 1 = also list every object

Used by publish-layout.sh and quoted in the family README and the release record.
A per-object listing of the partitioned table is 700+ lines, which is a listing
nobody reads, so the default is the summary and the detail is opt-in.
"""

import os
import sys


def main() -> int:
    root = sys.argv[1]
    show_tree = len(sys.argv) > 2 and sys.argv[2] == "1"
    total_files = total_bytes = 0
    for entry in sorted(os.listdir(root)):
        path = os.path.join(root, entry)
        if os.path.isfile(path):
            n, b = 1, os.path.getsize(path)
        else:
            n = b = 0
            for directory, _, files in os.walk(path):
                for name in files:
                    n += 1
                    b += os.path.getsize(os.path.join(directory, name))
        total_files += n
        total_bytes += b
        print(f"  {entry:<24} {n:>7} object(s)  {b:>16,} bytes")
    print(f"  {'TOTAL':<24} {total_files:>7} object(s)  {total_bytes:>16,} bytes")
    if show_tree:
        print()
        for directory, _, files in os.walk(root):
            for name in sorted(files):
                p = os.path.join(directory, name)
                print(f"  {os.path.getsize(p):>13,}  {os.path.relpath(p, root)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
