#!/usr/bin/env python3
"""Helper of scripts/lib/temp-hygiene.sh (#983, DEVELOPMENT.md section 9.7): scan and remove.

scan   --base B --deadline T [--candidates-in DIR ...]
    Reads every process once: /proc/<pid>/stat, cwd, fd and cmdline. Writes NUL-terminated
    records: "P\t<pid>\t<rc>\t<name>" per process (rc 0 read, 2 live but cwd or fd unreadable,
    3 gone, 4 zombie, 5 kernel thread), followed by its references: "R\tc\t<cwd>" and
    "R\tf\t<fd target>" when the kernel's path is under B, "R\tk\t<candidate>" for each
    directory of a --candidates-in DIR that a reference RESOLVES to or below (Resolver), and
    "R\td\t<n>" when n of its references were in doubt (each of those named every candidate).
    A final "#budget" record means the deadline ended the scan: the caller removes nothing.
    The scan can only KEEP a directory; nothing it reads can make a file removable.

remove --mode real|dry-run --lease try|off --uid N --deadline T [--stop-after N] [--stop-after-visits N]
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
    kept. The ONE deadline is checked before each candidate, for every entry reached (kept or
    removed, whatever keeps it), for every name read while listing a directory, and before
    every removal. A final "#budget" record: the deadline ended; nothing more was visited, no
    later candidate was reached (a candidate the deadline met before it was reached has no
    record) and the last one may be partly removed (a later run finishes it). dry-run takes and
    releases the same leases and removes nothing. --stop-after N stops after N removals, and
    --stop-after-visits N once N entries have been visited, exactly as the deadline does (the
    self-test's ways to stop part-way). No argument removes a file without the lease.
"""
import argparse
import errno
import fcntl
import os
import stat
import sys
import time
from typing import BinaryIO, Callable, Optional, TypeVar

F_SETLEASE: int = getattr(fcntl, "F_SETLEASE", 1024)
F_WRLCK: int = getattr(fcntl, "F_WRLCK", 1)
F_UNLCK: int = getattr(fcntl, "F_UNLCK", 2)
PF_KTHREAD = 0x00200000
OPEN_FILE = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC | getattr(os, "O_NOCTTY", 0)
OPEN_DIR = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
REFUSED = (errno.EAGAIN, errno.EBUSY, errno.EWOULDBLOCK)


def emit(out: BinaryIO, *fields: bytes) -> None:
    out.write(b"\t".join(fields) + b"\0")


class Budget(Exception):
    """The budget ended: nothing more is removed (remove) or nothing is kept for certain (scan)."""


# ---- scan --------------------------------------------------------------------------------
T = TypeVar("T")
Ident = tuple[int, int]  # (st_dev, st_ino): what the filesystem says a directory IS
O_PATH: int = getattr(os, "O_PATH", 0o10000000)
PATH_ANY = O_PATH | os.O_CLOEXEC
PATH_DIR = PATH_ANY | os.O_DIRECTORY
NAME_MAX = 255  # a longer prefix of a component can name nothing
MAX_HOPS = 40  # the kernel's limit of symbolic links in one lookup; one more is doubt, not "nothing"


class Nothing(Exception):
    """The kernel's answer: the path names nothing (no such entry, not a directory, a name too long, a dead process)."""


class Doubt(Exception):
    """A reference that cannot be resolved for certain: it keeps every candidate (D2, doubt keeps)."""


NOTHING_ERRNOS = frozenset((errno.ENOENT, errno.ENOTDIR, errno.ENAMETOOLONG, errno.ESRCH))


def kernel(call: "Callable[..., T]", *arguments: object, **options: object) -> "T":
    """One system call of a resolution: its failure is either the kernel's "nothing" or doubt."""
    try:
        return call(*arguments, **options)
    except OSError as error:
        raise (Nothing() if error.errno in NOTHING_ERRNOS else Doubt()) from error


def under(path: bytes, base: bytes) -> bool:
    return path == base or path.startswith(base + b"/")


def candidate_map(directories: list[str]) -> dict[Ident, list[str]]:
    """Every real directory directly inside each given directory, by identity (a bind mount can show one twice)."""
    found: dict[Ident, list[str]] = {}
    for directory in directories:
        try:
            with os.scandir(directory) as entries:
                for entry in entries:
                    if entry.is_dir(follow_symlinks=False):
                        st = entry.stat(follow_symlinks=False)
                        found.setdefault((st.st_dev, st.st_ino), []).append(os.path.join(directory, entry.name))
        except OSError:
            continue
    return found


def proc_devices() -> set[int]:
    """The devices of procfs: a link there (cwd, root, exe, fd/N) is the kernel's own, opened, never read as text."""
    devices: set[int] = set()
    try:
        with open("/proc/self/mountinfo", "rb") as handle:
            for line in handle:
                fields = line.split()
                if b"-" in fields and fields[fields.index(b"-") + 1] == b"proc":
                    major, minor = fields[2].split(b":")
                    devices.add(os.makedev(int(major), int(minor)))
    except (OSError, ValueError, IndexError):
        pass
    return devices


class Resolver:
    """Which candidate directories a process's references name, by the kernel's resolution, never by spelling.

    A reference is a command-line argument, the cwd or an open handle. An argument is text: where a
    path in it starts and ends cannot be known (any byte but NUL and '/' is legal in a name), so EVERY
    '/' starts an absolute path (from the process's own root), every place a name of the process's cwd
    (or "..") occurs starts a relative one, and every place a path could end is an end: each proper
    prefix of a component, the empty one included, except inside a component that is exactly ".."
    (the kernel's step out: "live/../other" never ends at "live/" or "live/.", while "live/.x" and
    "live/..." may end at "live/"), and the whole text. Each component is looked up through descriptors with O_NOFOLLOW; a symbolic link is
    followed by reading it (an absolute target restarts at the process's root), ".." at the process's
    root stays there, a procfs link is opened by the kernel. An end's identity (device, inode) is then
    walked up through ".." to the top of its mount tree (another mount namespace, a bind mount, a
    chroot included), and every candidate on that chain is kept. The kernel's "nothing" (Nothing)
    ends one reading; anything else — a listed name gone, a refused lookup, too many links — is doubt
    (Doubt), and doubt keeps every candidate.
    """

    def __init__(self, candidates: dict[Ident, list[str]], deadline: float) -> None:
        self.candidates = candidates
        self.everything: frozenset[str] = frozenset(path for paths in candidates.values() for path in paths)
        self.deadline = deadline
        self.proc_devs = proc_devices()
        self.listings: dict[Ident, Optional[frozenset[str]]] = {}
        self.chains: dict[tuple[int, int, int], frozenset[str]] = {}
        self.own_root = os.open("/", PATH_DIR)
        self.root = self.own_root
        self.root_id: Ident = self.ident(self.own_root)
        self.pid = "self"  # the process whose references are read: /proc/self in ITS argument is itself
        self.hops = 0
        self.doubts = 0

    @staticmethod
    def ident(fd: int) -> Ident:
        st = kernel(os.fstat, fd)
        return st.st_dev, st.st_ino

    @staticmethod
    def mount_of(fd: int) -> int:
        try:
            with open("/proc/self/fdinfo/%d" % fd, "rb") as handle:
                for line in handle:
                    if line.startswith(b"mnt_id:"):
                        return int(line.split()[1])
        except (OSError, ValueError) as error:
            raise Doubt() from error
        raise Doubt()

    def check(self) -> None:
        if time.time() >= self.deadline:
            raise Budget()

    def listing(self, fd: int) -> Optional[frozenset[str]]:
        """The names in a directory; None when it cannot be listed (each prefix is then looked up instead)."""
        key = self.ident(fd)
        if key not in self.listings:
            try:
                handle = os.open(".", os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC, dir_fd=fd)
                try:
                    self.listings[key] = frozenset(os.listdir(handle))
                finally:
                    os.close(handle)
            except OSError as error:
                self.listings[key] = frozenset() if error.errno in NOTHING_ERRNOS else None
        return self.listings[key]

    def kept_by(self, fd: int, parent: int = -1) -> frozenset[str]:
        """The candidates a directory is, or lies below: its ".." chain to the top of its mount tree.

        `parent` is the directory it was looked up in: the same ".." for the kernel, used only when
        this directory refuses us the ".." lookup (no search permission on it).
        """
        walked: list[tuple[tuple[int, int, int], Ident]] = []
        opened: list[int] = []
        found: frozenset[str] = frozenset()
        current = fd
        try:
            while True:
                identity = self.ident(current)
                key = (self.mount_of(current), *identity)
                if key in self.chains:
                    found = self.chains[key]
                    break
                walked.append((key, identity))
                if len(walked) > 4096:
                    raise Doubt()
                try:
                    up = os.open("..", PATH_DIR, dir_fd=current)
                except PermissionError as error:
                    if len(walked) == 1 and parent >= 0:
                        current = parent
                        continue
                    raise Doubt() from error
                except OSError as error:
                    if error.errno in NOTHING_ERRNOS:
                        break  # removed from the tree (rmdir'd, a dead process's /proc entry): below nothing
                    raise Doubt() from error
                opened.append(up)
                if self.ident(up) == identity and self.mount_of(up) == key[0]:
                    break
                current = up
        finally:
            for handle in opened:
                os.close(handle)
        for key, identity in reversed(walked):
            found = found | frozenset(self.candidates.get(identity, ()))
            self.chains[key] = found
        return found

    def lookup(self, at: int, name: str) -> tuple[int, int]:
        """(object, the directory it was found in or -1) for one component, links followed; both the caller's to close."""
        if name == "..":
            return (os.dup(at) if self.ident(at) == self.root_id else kernel(os.open, "..", PATH_DIR, dir_fd=at)), -1
        try:
            fd = kernel(os.open, name, PATH_ANY | os.O_NOFOLLOW, dir_fd=at)
        except Nothing:
            if name not in (self.listing(at) or ()):
                raise
            # Listed earlier in this scan, gone now: read the directory again. Absent there too, the
            # kernel's two answers agree (it names nothing now); listed but still not openable, the
            # component vanished while it was read: doubt.
            self.listings.pop(self.ident(at), None)
            if name not in (self.listing(at) or ()):
                raise
            try:
                fd = kernel(os.open, name, PATH_ANY | os.O_NOFOLLOW, dir_fd=at)
            except Nothing as error:
                raise Doubt() from error
        try:
            st = kernel(os.fstat, fd)
            if not stat.S_ISLNK(st.st_mode):
                fd, kept = -1, fd
                return kept, os.dup(at)
        finally:
            if fd >= 0:
                os.close(fd)
        self.hops += 1
        if self.hops > MAX_HOPS:
            raise Doubt()
        if st.st_dev in self.proc_devs:
            return self.magic(at, name)
        try:
            target = kernel(os.readlink, name, dir_fd=at)
        except Nothing as error:
            raise Doubt() from error  # it was a link a moment ago
        return self.exact(self.root if target.startswith("/") else at, target)

    def magic(self, at: int, name: str) -> tuple[int, int]:
        """A procfs link (/proc/<pid>/cwd, root, fd/N, exe): the kernel opens it; a file's directory is its path's."""
        if name in ("self", "thread-self"):  # names the process that reads it: the holder, not this scan
            name = self.pid if name == "self" else "%s/task/%s" % (self.pid, self.pid)
        fd = kernel(os.open, name, PATH_ANY, dir_fd=at)
        try:
            if stat.S_ISDIR(kernel(os.fstat, fd).st_mode):
                fd, kept = -1, fd
                return kept, -1
            text = kernel(os.readlink, name, dir_fd=at)
            if text.endswith(" (deleted)"):
                text = text[: -len(" (deleted)")]
            if not text.startswith("/"):
                return os.dup(fd), -1  # a socket, pipe or anonymous inode: no directory holds it
            held, holder = self.exact(self.own_root, os.path.dirname(text))
            if holder >= 0:
                os.close(holder)
            fd, kept = -1, fd
            return kept, held
        finally:
            if fd >= 0:
                os.close(fd)

    def exact(self, start: int, text: str) -> tuple[int, int]:
        """A link's target: every component exact (a target, unlike an argument, has known ends)."""
        current, holder = os.dup(start), -1
        try:
            for name in text.split("/"):
                if name in ("", "."):
                    continue
                following, holding = self.lookup(current, name)
                os.close(current)
                current = following
                if holder >= 0:
                    os.close(holder)
                holder = holding
            current, kept = -1, current
            return kept, holder
        except BaseException:
            if holder >= 0:
                os.close(holder)
            raise
        finally:
            if current >= 0:
                os.close(current)

    def end(self, at: int, name: str) -> frozenset[str]:
        """The candidates kept when a path ends with `name` in directory `at` (Nothing: no such end)."""
        if name in ("", "."):
            return self.kept_by(at)
        self.hops = 0
        fd, holder = self.lookup(at, name)
        try:
            if stat.S_ISDIR(kernel(os.fstat, fd).st_mode):
                return self.kept_by(fd, holder)
            return self.kept_by(holder) if holder >= 0 else frozenset()
        finally:
            os.close(fd)
            if holder >= 0:
                os.close(holder)

    def prefix_ends(self, at: int, component: str, empty: bool) -> frozenset[str]:
        kept: frozenset[str] = frozenset()
        names = self.listing(at)
        for k in range(min(len(component), NAME_MAX + 1)):
            prefix = component[:k]
            if component == "..":
                continue  # the kernel's ".." leaves: "live/../other" never ends at "live/" or "live/."
            if k == 0:
                kept |= self.kept_by(at) if empty else frozenset()
            elif prefix in (".", "..") or names is None or prefix in names:
                try:
                    kept |= self.end(at, prefix)
                except Nothing:
                    pass
        return kept

    def walk(self, start: int, text: str, position: int, relative: bool) -> frozenset[str]:
        """Every end of the path that starts at `position` of `text`, resolved from directory `start`."""
        kept: frozenset[str] = frozenset()
        current, first = os.dup(start), True
        try:
            while True:
                self.check()
                slash = text.find("/", position)
                component = text[position:] if slash < 0 else text[position:slash]
                kept |= self.prefix_ends(current, component, not (relative and first))
                if slash < 0:
                    return kept | self.end(current, component)
                if component not in ("", "."):
                    self.hops = 0
                    following, holder = self.lookup(current, component)
                    if holder >= 0:
                        os.close(holder)
                    os.close(current)
                    current = following
                    if not stat.S_ISDIR(kernel(os.fstat, current).st_mode):
                        return kept  # "file/..." names nothing
                position, first = slash + 1, False
        except Nothing:
            return kept
        except Doubt:
            self.doubts += 1
            return self.everything
        finally:
            os.close(current)

    def argument(self, text: str, cwd: int) -> frozenset[str]:
        """The candidates one command-line argument names, from every possible start."""
        kept: frozenset[str] = frozenset()
        for index, char in enumerate(text):
            if char == "/":
                kept |= self.walk(self.root, text, index + 1, False)
        if cwd < 0:
            return kept  # its cwd cannot be read: the owner's ruling, no relative start
        same_root = self.ident(cwd) == self.root_id
        names = self.listing(cwd)
        starts: set[int] = {0}
        if names is None:
            starts = set(range(len(text)))  # an unlistable cwd: a relative path may start anywhere
        else:
            for name in names | {".."}:
                found = text.find(name)
                while found >= 0:
                    starts.add(found)
                    found = text.find(name, found + 1)
        for index in sorted(starts):
            if index < len(text) and text[index] != "/" and not (same_root and index and text[index - 1] == "/"):
                kept |= self.walk(cwd, text, index, True)
        return kept

    def guarded(self, read: "Callable[[], frozenset[str]]") -> frozenset[str]:
        try:
            return read()
        except Nothing:
            return frozenset()
        except Doubt:
            self.doubts += 1
            return self.everything

    def process(self, proc: str, arguments: list[bytes]) -> frozenset[str]:
        """Everything one process's references resolve to: its arguments, its cwd and its open handles."""
        if not self.candidates:
            return frozenset()
        try:
            self.root = os.open(proc + "/root", PATH_DIR)
        except OSError:
            self.root = os.dup(self.own_root)  # its root cannot be opened: resolved as if it were ours
        try:
            cwd = os.open(proc + "/cwd", PATH_DIR)
        except OSError:
            cwd = -1  # unreadable: the owner's ruling (it keeps no directory); no relative start
        try:
            self.root_id = self.ident(self.root)
            kept = self.guarded(lambda: self.kept_by(cwd)) if cwd >= 0 else frozenset()
            for argument in arguments:
                if argument:
                    kept |= self.guarded(lambda: self.argument(os.fsdecode(argument), cwd))
            return kept | self.handles(proc)
        finally:
            if cwd >= 0:
                os.close(cwd)
            os.close(self.root)
            self.root, self.root_id = self.own_root, self.ident(self.own_root)

    def handles(self, proc: str) -> frozenset[str]:
        """An open directory by identity; an open file by the directory its kernel path names."""
        kept: frozenset[str] = frozenset()
        try:
            fds = os.listdir(proc + "/fd")
        except OSError:
            return kept  # unreadable: the owner's ruling
        for name in fds:
            self.check()
            self.hops = 0
            kept |= self.guarded(lambda: self.handle(proc, name))
        return kept

    def handle(self, proc: str, name: str) -> frozenset[str]:
        try:
            fd = os.open("%s/fd/%s" % (proc, name), PATH_ANY)
        except OSError:
            return frozenset()  # closed meanwhile, or a socket, pipe or anonymous inode: no directory
        try:
            if stat.S_ISDIR(kernel(os.fstat, fd).st_mode):
                return self.kept_by(fd)
        finally:
            os.close(fd)
        try:
            text = os.readlink("%s/fd/%s" % (proc, name))
        except OSError:
            return frozenset()
        if not text.startswith("/"):
            return frozenset()
        directory = os.path.dirname(text[: -len(" (deleted)")] if text.endswith(" (deleted)") else text)
        kept: frozenset[str] = frozenset()
        for start in {self.own_root, self.root}:  # the kernel's path is ours, or (another namespace) its own
            self.hops = 0
            try:
                held, holder = self.exact(start, directory)
            except Nothing:
                continue
            try:
                kept |= self.kept_by(held, holder)
            finally:
                os.close(held)
                if holder >= 0:
                    os.close(holder)
        return kept


def scan_process(pid: str, base: bytes, resolver: Resolver) -> tuple[int, bytes, list[tuple[bytes, bytes]]]:
    """(rc, name, references) for one process."""
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
            arguments = handle.read().split(b"\0")
    except OSError:
        arguments = []
    resolver.pid, before = pid, resolver.doubts
    for kept in sorted(resolver.process(proc, arguments)):
        refs.append((b"k", os.fsencode(kept)))
    if resolver.doubts > before:
        refs.append((b"d", str(resolver.doubts - before).encode()))
    if not os.path.isdir(proc):
        return 3, name, []
    return (2 if unreadable else 0), name, refs


def scan(arguments: argparse.Namespace) -> int:
    base = os.fsencode(arguments.base)
    out = sys.stdout.buffer
    resolver = Resolver(candidate_map(arguments.candidates_in or []), arguments.deadline)
    pids = sorted((entry for entry in os.listdir("/proc") if entry.isdigit()), key=int)
    try:
        for pid in pids:
            resolver.check()
            rc, name, refs = scan_process(pid, base, resolver)
            emit(out, b"P", pid.encode(), str(rc).encode(), name.replace(b"\t", b" ").replace(b"\0", b" "))
            for kind, value in refs:
                emit(out, b"R", kind, value)
    except Budget:
        emit(out, b"#budget")
    out.flush()
    return 0


# ---- remove ------------------------------------------------------------------------------
class Remover:
    def __init__(self, arguments: argparse.Namespace) -> None:
        self.real: bool = arguments.mode == "real"
        self.lease: bool = arguments.lease == "try"
        self.uid: int = arguments.uid
        self.deadline: float = arguments.deadline
        self.stop_after: Optional[int] = arguments.stop_after
        self.stop_after_visits: Optional[int] = arguments.stop_after_visits
        self.removals = self.visits = 0
        self.entries = self.bytes = self.refused = self.untried = self.other = 0

    def check(self) -> None:
        """The ONE budget: raises Budget once the deadline (or the self-test's visit limit) is reached."""
        if time.time() >= self.deadline or (
            self.stop_after_visits is not None and self.visits >= self.stop_after_visits
        ):
            raise Budget()

    def visit(self) -> None:
        """Called for every entry reached, before anything is read of it, whether it is then kept or removed."""
        self.check()
        self.visits += 1

    def spend(self) -> None:
        """Called before every removal: at the budget, nothing more is removed."""
        self.check()
        if self.stop_after is not None and self.removals >= self.stop_after:
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
            names: list[str] = []
            with os.scandir(fd) as children:
                for child in children:
                    self.check()
                    names.append(child.name)
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
        self.visit()
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
        self.check()  # past the budget, the candidate is not reached and gets no record
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
    scanner.add_argument("--candidates-in", action="append")
    remover = commands.add_parser("remove")
    remover.add_argument("--mode", choices=("real", "dry-run"), required=True)
    remover.add_argument("--lease", choices=("try", "off"), required=True)
    remover.add_argument("--uid", type=int, required=True)
    remover.add_argument("--deadline", type=float, required=True)
    remover.add_argument("--stop-after", type=int)
    remover.add_argument("--stop-after-visits", type=int)
    arguments = parser.parse_args()
    return scan(arguments) if arguments.command == "scan" else remove(arguments)


if __name__ == "__main__":
    sys.exit(main())
