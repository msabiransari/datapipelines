#!/usr/bin/env python3
"""Helper of scripts/lib/temp-hygiene.sh (#983, DEVELOPMENT.md section 9.7): scan and remove.

scan   --base B --deadline T
    Reads every process once: /proc/<pid>/stat, cwd, fd and cmdline. Writes NUL-terminated
    records: "P\t<pid>\t<rc>\t<name>" per process (rc 0 read, 2 live but cwd or fd unreadable,
    3 gone, 4 zombie, 5 kernel thread), followed by its references under B:
    "R\tc\t<cwd>", "R\tf\t<fd target>", "R\ta\t<command-line argument naming B>". A final
    "#budget" record means the deadline ended the scan: the caller removes nothing.
    The scan can only KEEP a directory; nothing it reads can make a file removable.

remove --mode real|dry-run --lease try|off --uid N --deadline T [--stop-after N]
    Reads NUL-separated absolute candidate paths on stdin. The caller has already decided that
    each may go: on the allowlist, owned by the uid, old enough, and not referenced by a live
    process it could read. Removal happens only on the kernel's answer:
      * a regular file goes only while this process holds a write lease on it
        (fcntl F_SETLEASE F_WRLCK), which Linux refuses while any other process has the file
        open OR mapped (a loaded .so is mapped, not held open). Refused, or not tryable (no
        lease support, any error, --lease off): the file is KEPT;
      * a symbolic link is removed as a link; its target is never followed;
      * a directory is walked through descriptors (O_NOFOLLOW, dir_fd), never across a
        filesystem boundary, and removed once empty; one still holding a kept entry stays;
      * anything owned by another uid, and any other file type (socket, fifo, device), is kept.
    Writes one NUL-terminated record per candidate reached:
        <status>\t<entries>\t<bytes>\t<refused>\t<untried>\t<other>\t<path>
    status: removed (gone; dry-run: would be), partial (something below it went or would),
    kept. A final "#budget" record: the budget ended; no later candidate was reached and the
    last one may be partly removed (a later run finishes it). dry-run takes and releases the
    same leases and removes nothing. --stop-after N stops after N removals exactly as the
    deadline does (the self-test's way to stop part-way). No argument removes a file without
    the lease.
"""
import argparse
import errno
import fcntl
import os
import stat
import sys
import time
from typing import BinaryIO, Optional

F_SETLEASE: int = getattr(fcntl, "F_SETLEASE", 1024)
F_WRLCK: int = getattr(fcntl, "F_WRLCK", 1)
F_UNLCK: int = getattr(fcntl, "F_UNLCK", 2)
PF_KTHREAD = 0x00200000
OPEN_FILE = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC | getattr(os, "O_NOCTTY", 0)
OPEN_DIR = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
REFUSED = (errno.EAGAIN, errno.EBUSY, errno.EWOULDBLOCK)


def emit(out: BinaryIO, *fields: bytes) -> None:
    out.write(b"\t".join(fields) + b"\0")


# ---- scan --------------------------------------------------------------------------------
def under(path: bytes, base: bytes) -> bool:
    return path == base or path.startswith(base + b"/")


def scan_process(pid: str, base: bytes) -> tuple[int, bytes, list[tuple[bytes, bytes]]]:
    """(rc, name, references under base) for one process."""
    proc = "/proc/" + pid
    try:
        with open(proc + "/stat", "rb") as handle:
            raw = handle.read()
    except OSError:
        return 3, b"", []
    close = raw.rfind(b")")
    name = raw[raw.find(b"(") + 1 : close]
    fields = raw[close + 2 :].split()
    if not fields or fields[0] in (b"X", b"x"):
        return 3, name, []
    if fields[0] == b"Z":
        return 4, name, []
    if len(fields) > 6 and int(fields[6]) & PF_KTHREAD:
        return 5, name, []
    refs: list[tuple[bytes, bytes]] = []
    unreadable = False
    try:
        cwd = os.readlink(os.fsencode(proc + "/cwd"))
        if under(cwd, base):
            refs.append((b"c", cwd))
    except FileNotFoundError:
        pass
    except OSError:
        unreadable = True
    try:
        for fd in os.listdir(proc + "/fd"):
            try:
                target = os.readlink(os.fsencode(proc + "/fd/" + fd))
            except FileNotFoundError:
                continue
            if under(target, base):
                refs.append((b"f", target))
    except FileNotFoundError:
        pass
    except OSError:
        unreadable = True
    try:
        with open(proc + "/cmdline", "rb") as handle:
            for argument in handle.read().split(b"\0"):
                if base in argument:
                    refs.append((b"a", argument))
    except OSError:
        pass
    if not os.path.isdir(proc):
        return 3, name, []
    return (2 if unreadable else 0), name, refs


def scan(arguments: argparse.Namespace) -> int:
    base = os.fsencode(arguments.base)
    out = sys.stdout.buffer
    pids = sorted((entry for entry in os.listdir("/proc") if entry.isdigit()), key=int)
    for pid in pids:
        if time.time() >= arguments.deadline:
            emit(out, b"#budget")
            break
        rc, name, refs = scan_process(pid, base)
        emit(out, b"P", pid.encode(), str(rc).encode(), name.replace(b"\t", b" ").replace(b"\0", b" "))
        for kind, value in refs:
            emit(out, b"R", kind, value)
    out.flush()
    return 0


# ---- remove ------------------------------------------------------------------------------
class Budget(Exception):
    """The budget ended: nothing more is removed."""


class Remover:
    def __init__(self, arguments: argparse.Namespace) -> None:
        self.real: bool = arguments.mode == "real"
        self.lease: bool = arguments.lease == "try"
        self.uid: int = arguments.uid
        self.deadline: float = arguments.deadline
        self.stop_after: Optional[int] = arguments.stop_after
        self.removals = 0
        self.entries = self.bytes = self.refused = self.untried = self.other = 0

    def spend(self) -> None:
        """Called before every removal: at the budget, nothing more is removed."""
        if time.time() >= self.deadline or (self.stop_after is not None and self.removals >= self.stop_after):
            raise Budget()
        self.removals += 1

    def file(self, dirfd: int, name: str, st: os.stat_result) -> bool:
        """True when the file is gone (dry-run: would be). Only under the write lease."""
        if not self.lease:
            self.untried += 1
            return False
        try:
            fd = os.open(name, OPEN_FILE, dir_fd=dirfd)
        except OSError:
            self.untried += 1
            return False
        try:
            opened = os.fstat(fd)
            if not stat.S_ISREG(opened.st_mode) or (opened.st_dev, opened.st_ino) != (st.st_dev, st.st_ino):
                self.untried += 1
                return False
            try:
                fcntl.fcntl(fd, F_SETLEASE, F_WRLCK)
            except OSError as error:
                if error.errno in REFUSED:
                    self.refused += 1
                else:
                    self.untried += 1
                return False
            try:
                again = os.stat(name, dir_fd=dirfd, follow_symlinks=False)
                if (again.st_dev, again.st_ino) != (st.st_dev, st.st_ino):
                    self.untried += 1
                    return False
                self.spend()
                if self.real:
                    os.unlink(name, dir_fd=dirfd)
                self.entries += 1
                self.bytes += st.st_blocks * 512
                return True
            finally:
                try:
                    fcntl.fcntl(fd, F_SETLEASE, F_UNLCK)
                except OSError:
                    pass
        finally:
            os.close(fd)

    def directory(self, dirfd: int, name: str, st: os.stat_result) -> bool:
        fd = os.open(name, OPEN_DIR, dir_fd=dirfd)
        try:
            opened = os.fstat(fd)
            if (opened.st_dev, opened.st_ino) != (st.st_dev, st.st_ino):
                self.other += 1
                return False
            with os.scandir(fd) as children:
                names = [child.name for child in children]
            gone = True
            for child in names:
                if not self.entry(fd, child, st.st_dev):
                    gone = False
        finally:
            os.close(fd)
        if not gone:
            return False
        self.spend()
        if self.real:
            try:
                os.rmdir(name, dir_fd=dirfd)
            except OSError:
                self.other += 1
                return False
        self.entries += 1
        return True

    def entry(self, dirfd: int, name: str, top_dev: Optional[int]) -> bool:
        """True when the entry is gone (dry-run: would be). Raises Budget at the budget."""
        try:
            st = os.stat(name, dir_fd=dirfd, follow_symlinks=False)
        except FileNotFoundError:
            return True
        if st.st_uid != self.uid:
            self.other += 1
            return False
        if stat.S_ISLNK(st.st_mode):
            self.spend()
            if self.real:
                os.unlink(name, dir_fd=dirfd)
            self.entries += 1
            return True
        if stat.S_ISREG(st.st_mode):
            return self.file(dirfd, name, st)
        if stat.S_ISDIR(st.st_mode) and (top_dev is None or st.st_dev == top_dev):
            return self.directory(dirfd, name, st)
        self.other += 1
        return False

    def candidate(self, out: BinaryIO, path: str) -> None:
        parent, name = os.path.split(path)
        self.entries = self.bytes = self.refused = self.untried = self.other = 0
        try:
            parent_fd = os.open(parent, OPEN_DIR)
        except OSError:
            self.record(out, "kept", path)
            return
        try:
            gone = self.entry(parent_fd, name, None)
            self.record(out, "removed" if gone else ("partial" if self.entries else "kept"), path)
        except Budget:
            self.record(out, "partial" if self.entries else "kept", path)
            raise
        except OSError:
            self.record(out, "partial" if self.entries else "kept", path)
        finally:
            os.close(parent_fd)

    def record(self, out: BinaryIO, status: str, path: str) -> None:
        counts = (self.entries, self.bytes, self.refused, self.untried, self.other)
        emit(out, status.encode(), *(str(count).encode() for count in counts), os.fsencode(path))
        out.flush()


def remove(arguments: argparse.Namespace) -> int:
    remover = Remover(arguments)
    out = sys.stdout.buffer
    paths = [os.fsdecode(raw) for raw in sys.stdin.buffer.read().split(b"\0") if raw]
    try:
        for path in paths:
            if os.path.isabs(path):
                remover.candidate(out, path)
    except Budget:
        emit(out, b"#budget")
        out.flush()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="scan and remove for scripts/lib/temp-hygiene.sh (#983)")
    commands = parser.add_subparsers(dest="command", required=True)
    scanner = commands.add_parser("scan")
    scanner.add_argument("--base", required=True)
    scanner.add_argument("--deadline", type=float, required=True)
    remover = commands.add_parser("remove")
    remover.add_argument("--mode", choices=("real", "dry-run"), required=True)
    remover.add_argument("--lease", choices=("try", "off"), required=True)
    remover.add_argument("--uid", type=int, required=True)
    remover.add_argument("--deadline", type=float, required=True)
    remover.add_argument("--stop-after", type=int)
    arguments = parser.parse_args()
    return scan(arguments) if arguments.command == "scan" else remove(arguments)


if __name__ == "__main__":
    sys.exit(main())
