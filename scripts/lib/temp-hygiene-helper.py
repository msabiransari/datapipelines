#!/usr/bin/env python3
"""Helper of scripts/lib/temp-hygiene.sh (#983, DEVELOPMENT.md section 9.7): scan and remove.

scan   --base B --deadline T [--candidates-in DIR ...]
    Reads every process once: /proc/<pid>/stat, cwd, fd and cmdline. Writes NUL-terminated
    records: "P\t<pid>\t<rc>\t<name>" per process (rc 0 read, 2 live but cwd or fd unreadable,
    3 gone, 4 zombie, 5 kernel thread), followed by its references: "R\tc\t<cwd>" and
    "R\tf\t<fd target>" when the kernel's path is under B, "R\tk\t<candidate>" for each
    directory of a --candidates-in DIR that a reference RESOLVES to or below (Resolver), and
    "R\td\t<n>" when n of its references were in doubt (each of those named every candidate), and
    "R\tu\t<n>" when n of its references resolved onto FUSE (judged in that filesystem's own tree
    by the owner's ruling of 2026-11-15, D8: they keep no candidate and are not doubt; counted).
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
import ctypes
import errno
import fcntl
import os
import re
import stat
import struct
import sys
import time
from dataclasses import dataclass
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


class DoubtLog:
    """Best-effort, opt-in cause events; never arguments, environment values or exception text."""

    def __init__(self) -> None:
        self.path = os.environ.get("TH_DOUBT_LOG", "")
        self.fd: Optional[int] = None
        self.opened = False
        self.pid = "-"
        self.events: dict[tuple[str, str], int] = {}

    def write(self, line: str) -> None:
        if not self.path:
            return
        try:
            if not self.opened:
                self.opened = True
                fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW |
                             os.O_CLOEXEC | os.O_NONBLOCK, 0o600)
                try:
                    if stat.S_ISREG(os.fstat(fd).st_mode):
                        self.fd = fd
                finally:
                    if self.fd is None:
                        os.close(fd)
            if self.fd is not None:
                os.write(self.fd, (line + "\n").encode("utf-8", "backslashreplace"))
        except OSError:
            pass  # A failed diagnostic must never change a resolution or its output.

    def event(self, origin: str, why: str, site: str, **detail: object) -> None:
        if not self.path:
            return
        key = (site, why)
        self.events[key] = self.events.get(key, 0) + 1
        if self.events[key] == 1:
            fields = " ".join("%s=%s" % (key, ascii(value)) for key, value in sorted(detail.items()))
            self.write("doubt pid=%s origin=%s why=%s site=%s%s" %
                       (self.pid, origin, why, site, " " + fields if fields else ""))

    def total(self, affected: int) -> None:
        fields = " ".join("%s/%s=%s" % (site, why, count)
                          for (site, why), count in sorted(self.events.items()))
        self.write("doubt-total affected-references=%s%s" % (affected, " " + fields if fields else ""))


_log = DoubtLog()


def errno_token(code: Optional[int]) -> str:
    return "errno-" + errno.errorcode.get(code, "UNKNOWN")


class Doubt(Exception):
    """A reference that cannot be resolved for certain: it keeps every candidate (D2, doubt keeps)."""

    def __init__(self, why: str, *, origin: str, site: str, **detail: object) -> None:
        super().__init__(why)
        _log.event(origin, why, site, **detail)


NOTHING_ERRNOS = frozenset((errno.ENOENT, errno.ENOTDIR, errno.ENAMETOOLONG, errno.ESRCH))


def kernel(call: "Callable[..., T]", *arguments: object, **options: object) -> "T":
    """One system call of a resolution: its failure is either the kernel's "nothing" or doubt."""
    try:
        return call(*arguments, **options)
    except OSError as error:
        raise (Nothing() if error.errno in NOTHING_ERRNOS else
               Doubt(errno_token(error.errno), origin="kernel", site="kernel-call", call=call.__name__)) from error


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


# ---- where an object lives in its filesystem's own tree (#983 round 4, D4-D7) ------------------
# A process may reach a directory through a mount whose root is a directory INSIDE its filesystem
# (a bind mount of a candidate's descendant, a root inside a candidate): walking ".." from the
# object then leaves that filesystem's tree at the mount's root and never meets the candidate.
# So every mount root a ".." chain meets is mapped into the sweeper's own view through the mount
# tables (/proc/<pid>/mountinfo: field 4 is the mount's root inside its filesystem), proved by
# device and inode, and walked on from there. What cannot be proved is doubt, scoped to the
# candidates on the object's filesystem (D7); "absent" is only ever a proved answer (D4).
STATX_MNT_ID = 0x1000
STATX_ATTR_MOUNT_ROOT = 0x2000
AT_EMPTY_PATH = 0x1000
NO_DIRECTORY = -2  # a socket, pipe or other kernel-internal object: in no directory of any filesystem
# Filesystems that hold their own data (a block or image device, memory, a kernel view): an object
# on one lies in that filesystem's tree only. Any other type (overlay, FUSE, network, 9p, an unknown
# name) may keep its data in a directory of another filesystem, so an object on it is doubt.
# One ruled exception (D8, owner ruling 2026-11-15): FUSE ("fuse", "fuse.<subtype>": the kernel's
# own type name, set by the FUSE driver, never by a path) is judged in its own tree as if it held
# its own data, and every reference onto it is counted (fuse-processes= on the summary line). The
# accepted cost: a FUSE program that takes its source from a configuration file (not its command
# line) and serves a file from inside a test temp directory would have that directory's closed
# files swept. A FUSE daemon's own command line, cwd and handles are judged like any process's.
OWN_DATA = frozenset((
    "ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "jfs", "vfat", "msdos", "exfat", "ntfs3",
    "hfsplus", "iso9660", "udf", "squashfs", "erofs", "tmpfs", "ramfs", "devtmpfs", "proc", "sysfs",
    "devpts", "cgroup", "cgroup2", "mqueue", "hugetlbfs", "debugfs", "tracefs", "securityfs",
    "pstore", "bpf", "configfs", "fusectl", "binfmt_misc", "autofs", "efivarfs", "nsfs",
))
# The same, by statfs(2) magic (linux/magic.h), for a mount no table names: a kernel-internal one
# (dma-buf, memfd's shmem, sockets, pipes) or one detached by a lazy unmount (a refreshed snap).
OWN_DATA_MAGIC = frozenset((
    0xEF53, 0x58465342, 0x9123683E, 0xF2F52010, 0x01021994, 0x858458F6, 0x73717368, 0xE0F5E1E2,
    0x9660, 0x4D44, 0x2011BAB0, 0x15013346, 0x9FA0, 0x62656572, 0x1CD1, 0x63677270, 0x27E0EB,
    0x444D4142, 0x09041934, 0x534F434B, 0x50495045, 0x6E736673, 0xCAFE4A11, 0x958458F6, 0x64626720,
    0x74726163, 0x73636673, 0x454D444D, 0x5345434D,
))


def is_fuse(fstype: str) -> bool:
    """A FUSE filesystem by the mount table's type field: "fuse" or "fuse.<subtype>" (not fusectl, not fuseblk)."""
    return fstype == "fuse" or fstype.startswith("fuse.")


OCTAL_ESCAPE = re.compile(rb"\\([0-7]{3})")


@dataclass(frozen=True)
class Mount:
    """One line of a mount table: the mount's id, its filesystem's device, root and type, its mount point."""

    ident: int
    dev: str  # "major:minor" of the filesystem (the same for every mount of it)
    root: str  # the directory of the filesystem that is this mount's root, in the filesystem's own tree
    point: str  # where the mount is, in the view of the process whose table was read
    fstype: str


def parse_mountinfo(data: bytes) -> tuple[dict[int, Mount], bool]:
    """The mounts of one table, and whether every line parsed (a line that does not is never "absent")."""
    mounts: dict[int, Mount] = {}
    complete = True
    for line in data.split(b"\n"):
        if not line:
            continue
        fields = line.split(b" ")
        try:
            separator = fields.index(b"-", 6)
            unescape = lambda field: os.fsdecode(OCTAL_ESCAPE.sub(lambda m: bytes([int(m.group(1), 8)]), field))
            mount = Mount(int(fields[0]), os.fsdecode(fields[2]), unescape(fields[3]), unescape(fields[4]),
                          os.fsdecode(fields[separator + 1]))
            mounts[mount.ident] = mount
        except (ValueError, IndexError):
            complete = False
    return mounts, complete


def strictly_above(top: str, path: str) -> bool:
    """`top` is a proper ancestor of `path` (both absolute paths inside one filesystem)."""
    return top != path and (top == "/" or path.startswith(top + "/"))


def device(st_dev: int) -> str:
    return "%d:%d" % (os.major(st_dev), os.minor(st_dev))


class Statx:
    """statx(2) of a descriptor: its mount id and whether it is the root of that mount (the kernel's own bit)."""

    def __init__(self) -> None:
        try:
            libc = ctypes.CDLL(None, use_errno=True)
            self.call: Optional[Callable[..., int]] = libc.statx
            self.call.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_uint, ctypes.c_char_p)
            self.call.restype = ctypes.c_int
        except (OSError, AttributeError):
            self.call = None  # no statx: every judgement that needs it is doubt
        try:
            self.fstatfs: Optional[Callable[..., int]] = libc.fstatfs
            self.fstatfs.argtypes = (ctypes.c_int, ctypes.c_char_p)
            self.fstatfs.restype = ctypes.c_int
        except (OSError, AttributeError, NameError):
            self.fstatfs = None
        self.buffer = ctypes.create_string_buffer(256)

    def magic(self, fd: int) -> int:
        """statfs(2) f_type of a descriptor's filesystem (the first field, a C long)."""
        if self.fstatfs is None or self.fstatfs(fd, self.buffer) != 0:
            raise Doubt(errno_token(ctypes.get_errno()) if self.fstatfs else "statfs-unavailable",
                        origin="magic", site="statfs-call", call="fstatfs")
        return ctypes.c_long.from_buffer(self.buffer).value & 0xFFFFFFFF

    def mount(self, fd: int) -> tuple[int, bool]:
        if self.call is None:
            raise Doubt("statx-unavailable", origin="mount", site="statx-unavailable")
        if self.call(fd, b"", AT_EMPTY_PATH, STATX_MNT_ID, self.buffer) != 0:
            code = ctypes.get_errno()
            raise (Nothing() if code in NOTHING_ERRNOS else
                   Doubt(errno_token(code), origin="mount", site="statx-call", call="statx"))
        mask, attributes = struct.unpack_from("=I4xQ", self.buffer, 0)
        (supported,) = struct.unpack_from("=Q", self.buffer, 56)
        (mount_id,) = struct.unpack_from("=Q", self.buffer, 144)
        if not mask & STATX_MNT_ID or not supported & STATX_ATTR_MOUNT_ROOT:
            raise Doubt("statx-unsupported", origin="mount", site="statx-mask")  # an old kernel: neither the mount nor its root can be named
        return mount_id, bool(attributes & STATX_ATTR_MOUNT_ROOT)


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
        self.chains: dict[tuple[int, int, int], tuple[frozenset[str], bool]] = {}
        self.statx = Statx()
        self.own_root = os.open("/", PATH_DIR)
        self.root = self.own_root
        self.root_id: Ident = self.ident(self.own_root)
        self.pid = "self"  # the process whose references are read: /proc/self in ITS argument is itself
        self.hops = 0
        self.doubts = 0
        self.extra: frozenset[str] = frozenset()  # keeps found beside a judgement (file_parent): only adds
        self.unreadable = False  # a handle of the process refused us (D3): its rc becomes 2
        self.fuse = 0  # references of the process that resolved onto FUSE (D8): counted, keep nothing
        # Mount tables: the sweeper's own (fresh at the start), every other namespace's on demand.
        self.own_mounts = self.read_table("/proc/self/mountinfo")[0]
        self.tables_read = 0
        self.namespaces: set[str] = set()
        self.superblocks: dict[tuple[int, int], tuple[bool, str, bool]] = {}
        self.swept: Optional[dict[int, Mount]] = None
        self.by_dev: dict[str, frozenset[str]] = {}
        self.unscoped: frozenset[str] = frozenset()
        self.fs_paths: dict[str, str] = {}  # a candidate's path inside its filesystem
        self.scope_candidates()

    @staticmethod
    def ident(fd: int) -> Ident:
        st = kernel(os.fstat, fd)
        return st.st_dev, st.st_ino

    def mount_of(self, fd: int) -> int:
        return self.statx.mount(fd)[0]

    # ---- mount tables ----------------------------------------------------------------------
    def read_table(self, path: str) -> tuple[dict[int, Mount], bool]:
        try:
            with open(path, "rb") as handle:
                data = handle.read()
        except OSError:
            return {}, False
        return parse_mountinfo(data)

    def scope_candidates(self) -> None:
        """Each candidate's filesystem (its mount's device, from the sweeper's own table): doubt's scope (D7)."""
        groups: dict[str, set[str]] = {}
        unscoped: set[str] = set()
        for paths in self.candidates.values():
            for path in paths:
                try:
                    fd = os.open(path, PATH_DIR | os.O_NOFOLLOW)
                except OSError:
                    unscoped.add(path)  # gone or unopenable: in every scope
                    continue
                try:
                    mount = self.own_mounts.get(self.mount_of(fd))
                except (Nothing, Doubt):
                    mount = None
                finally:
                    os.close(fd)
                if mount is None:
                    unscoped.add(path)
                    continue
                groups.setdefault(mount.dev, set()).add(path)
                # Its path inside the filesystem: its real path in our view, the mount point replaced by the mount's root.
                real = os.path.realpath(path)
                point = mount.point.rstrip("/")
                if mount.root.startswith("/") and "//deleted" not in mount.root and (real == point or real.startswith(point + "/")):
                    self.fs_paths[path] = mount.root.rstrip("/") + real[len(point):] or "/"
        self.by_dev = {dev: frozenset(paths) for dev, paths in groups.items()}
        self.unscoped = frozenset(unscoped)

    def scope(self, dev: Optional[str]) -> frozenset[str]:
        """The candidates an object on filesystem `dev` could be in (None: unknown, every candidate)."""
        return self.everything if dev is None else self.by_dev.get(dev, frozenset()) | self.unscoped

    def fresh_tables(self, pids: list[str]) -> dict[int, Mount]:
        """The tables of the namespaces of `pids`, read NOW (each mount we hold a descriptor on is pinned)."""
        mounts: dict[int, Mount] = {}
        seen: set[str] = set()
        for pid in pids:
            try:
                namespace = os.readlink("/proc/%s/ns/mnt" % pid)
            except OSError:
                namespace = ""
            if namespace and namespace in seen:
                continue
            seen.add(namespace)
            self.tables_read += 1
            if namespace:
                self.namespaces.add(namespace)
            mounts.update(self.read_table("/proc/%s/mountinfo" % pid)[0])
        return mounts

    def find_mount(self, mount_id: int, fresh: bool = False) -> Optional[Mount]:
        """The mount: the holder's namespace and ours read now, then (once a scan, or now when `fresh`) every namespace."""
        mount = self.fresh_tables([self.pid, "self"]).get(mount_id)
        if mount is None and (fresh or self.swept is None):
            pids = [entry for entry in os.listdir("/proc") if entry.isdigit()]
            readable = []
            for pid in pids:
                try:
                    if os.readlink("/proc/%s/ns/mnt" % pid):
                        readable.append(pid)
                except OSError:
                    continue  # its namespace cannot be named: none of its descriptors can be ours either
            self.swept = self.fresh_tables(readable)
        if mount is None and self.swept is not None:
            mount = self.swept.get(mount_id)
        return mount

    def superblock(self, fd: int) -> tuple[bool, str, bool]:
        """(holds its own data, device, FUSE) of the filesystem a descriptor is on (OWN_DATA, OWN_DATA_MAGIC, D8).

        From the table that names its mount, when that line agrees with the descriptor's st_dev
        (btrfs reports a subvolume's own st_dev, so its line is taken as it is). A mount no table
        names, or a line that disagrees (a reused mount id), is judged by statfs magic and st_dev.
        Cached by (mount id, st_dev): another mount with the same id and st_dev is the same filesystem.
        FUSE is known from a table line only (its type field); a mount no table names is never FUSE.
        """
        mount_id = self.mount_of(fd)
        st_dev = kernel(os.fstat, fd).st_dev
        key = (mount_id, st_dev)
        if key not in self.superblocks:
            mount = self.find_mount(mount_id)
            if mount is not None and (mount.dev == device(st_dev) or mount.fstype == "btrfs"):
                fuse = is_fuse(mount.fstype)
                self.superblocks[key] = (mount.fstype in OWN_DATA or fuse, mount.dev, fuse)
            else:
                self.superblocks[key] = (self.statx.magic(fd) in OWN_DATA_MAGIC, device(st_dev), False)
        return self.superblocks[key]

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

    def counted(self, judged: tuple[frozenset[str], bool]) -> frozenset[str]:
        """A judgement's candidates; one more reference in doubt when doubt kept any of them."""
        if judged[1]:
            self.doubts += 1
        return judged[0]

    def doubt(self, dev: Optional[str], why: str, *, origin: str, site: str,
              **detail: object) -> tuple[frozenset[str], bool]:
        """DOUBT (D4): keep what the object could be in. A doubt that can keep nothing is not counted."""
        kept = self.scope(dev)
        if kept:
            _log.event(origin, why, site, **detail)
        return kept, bool(kept)

    def kept_by(self, fd: int, parent: int = -1) -> frozenset[str]:
        """The candidates a directory is, or lies below, in its filesystem's own tree (D4-D6).

        `parent` is the directory it was looked up in: the same ".." for the kernel, used only when
        this directory refuses us the ".." lookup (no search permission on it). Every reference —
        an argument's end, the cwd, an open handle — is judged here or in kept_by_file.
        """
        return self.counted(self.judge_directory(fd, parent))

    def judge_directory(self, fd: int, parent: int = -1) -> tuple[frozenset[str], bool]:
        own_data, _, fuse = self.superblock(fd)
        if not own_data:
            # Its data may live in a directory of another filesystem.
            return self.doubt(None, "not-own-data", origin="judge_directory", site="directory-data")
        if fuse:
            self.fuse += 1  # D8: judged in its own tree, counted
        return self.chain(fd, parent)

    def chain(self, fd: int, parent: int) -> tuple[frozenset[str], bool]:
        """The ".." chain to the top of the mount tree, every mount root on it mapped (above_root).

        Below a mount root, ".." is the filesystem's own parent. At a mount root the kernel's ".."
        leaves for the mount point's parent, so the filesystem's directories above that root are
        found through the mount tables instead (above_root); the walk still goes on (it can only add).
        A ".." that lands in another mount from a directory that is NOT a mount root (a mount over
        its parent hides the parent) is doubt, scoped to its filesystem.
        """
        walked: list[tuple[tuple[int, int, int], Ident, frozenset[str], bool]] = []
        opened: list[int] = []
        found: frozenset[str] = frozenset()
        doubted = False
        current = fd
        try:
            while True:
                identity = self.ident(current)
                mount_id, is_root = self.statx.mount(current)
                key = (mount_id, *identity)
                if key in self.chains:
                    found, doubted = self.chains[key]
                    break
                extra, extra_doubt = self.above_root(current, mount_id) if is_root else (frozenset(), False)
                walked.append((key, identity, extra, extra_doubt))
                if len(walked) > 4096:
                    raise Doubt("chain-too-long", origin="chain", site="chain-length")
                try:
                    up = os.open("..", PATH_DIR, dir_fd=current)
                except PermissionError as error:
                    if len(walked) == 1 and parent >= 0:
                        current = parent
                        continue
                    raise Doubt(errno_token(error.errno), origin="chain", site="chain-parent-permission",
                                call="open-parent") from error
                except OSError as error:
                    if error.errno in NOTHING_ERRNOS:
                        break  # removed from the tree (rmdir'd, a dead process's /proc entry): below nothing
                    raise Doubt(errno_token(error.errno), origin="chain", site="chain-parent-other",
                                call="open-parent") from error
                opened.append(up)
                up_mount = self.mount_of(up)
                if self.ident(up) == identity and up_mount == mount_id:
                    break  # the top of the mount tree (or the sweeper's own root): nothing above
                if up_mount != mount_id and not is_root:
                    hidden, hidden_doubt = self.doubt(self.superblock(current)[1], "hidden-parent",
                                                      origin="chain", site="chain-hidden")
                    key_, identity_, extra_, doubt_ = walked[-1]
                    walked[-1] = (key_, identity_, extra_ | hidden, doubt_ or hidden_doubt)
                current = up
        finally:
            for handle in opened:
                os.close(handle)
        for key, identity, extra, extra_doubt in reversed(walked):
            found = found | frozenset(self.candidates.get(identity, ())) | extra
            doubted = doubted or extra_doubt
            self.chains[key] = (found, doubted)
        return found, doubted

    def above_root(self, root: int, mount_id: int) -> tuple[frozenset[str], bool]:
        """The candidates at or above a mount's root directory in its filesystem's own tree.

        The mount table read NOW (we hold the root, so its id names this mount) gives the root's
        path inside its filesystem (field 4, the kernel's own dentry path); each candidate's path
        inside its filesystem is known from the sweeper's table (scope_candidates). FOUND: every
        candidate on the same filesystem whose path is the root's or above it. PROVED ABSENT: no
        candidate is on this filesystem, or none has such a path. DOUBT, scoped to the filesystem:
        no table line for the mount, a line that disagrees on the device, a root that is not a
        path (a deleted directory's "//deleted"), or a candidate whose own path is unknown.
        """
        dev = self.superblock(root)[1]
        scope = self.scope(dev)
        if not scope:
            return frozenset(), False  # no candidate is on this filesystem
        mount = self.find_mount(mount_id, fresh=True)
        if mount is None or mount.dev != dev or not mount.root.startswith("/") or "//deleted" in mount.root:
            why = "no-table-line" if mount is None else "table-dev-differs" if mount.dev != dev else "root-not-a-path"
            return self.doubt(dev, why, origin="above_root", site="above-root-table", mount_id=mount_id, filesystem_dev=dev)
        return self.containing(scope, mount.root, True)

    def containing(self, scope: frozenset[str], path: str, itself: bool) -> tuple[frozenset[str], bool]:
        """The candidates of `scope` whose path inside the filesystem is above `path` (or is it, when `itself`)."""
        found: set[str] = set()
        unknown = False
        for candidate in scope:
            inside = self.fs_paths.get(candidate)
            if inside is None:
                found.add(candidate)  # its own path is unknown: kept, as doubt
                unknown = True
            elif strictly_above(inside, path) or (itself and inside == path):
                found.add(candidate)
        if unknown:
            _log.event("containing", "candidate-path-unknown", "containing-path")
        return frozenset(found), unknown

    def kept_by_file(self, fd: int, parent: int) -> frozenset[str]:
        """The candidates a non-directory lies in (`parent`: the directory its name was found in, or -1).

        PROVED: a mount root that is a file (a bind of one file) is mapped as above_root maps a
        directory; otherwise `parent` holds it when the lookup found it there in the same mount.
        A file with more names (st_nlink > 1) may lie in other directories too, and one whose
        directory is not proved (and still has a name) is doubt, both scoped to its filesystem;
        a file with no name (st_nlink 0) is in no directory. `parent` is always judged as well.
        """
        if parent == NO_DIRECTORY:
            return frozenset()
        st = kernel(os.fstat, fd)
        found, doubted = self.judge_directory(parent) if parent >= 0 else (frozenset(), False)
        if st.st_nlink == 0:
            return self.counted((found, doubted))  # no name: in no directory (memfd, an unlinked file)
        own_data, dev, fuse = self.superblock(fd)
        if not own_data:
            _log.event("kept_by_file", "not-own-data", "file-data")
            return self.counted((found | self.scope(None), True))
        if fuse:
            self.fuse += 1  # D8: judged in its own tree, counted
        mount_id, is_root = self.statx.mount(fd)
        if is_root:
            more, more_doubt = self.file_root(fd, mount_id)
            found, doubted = found | more, doubted or more_doubt
            proved = True
        else:
            proved = parent >= 0 and self.mount_of(parent) == mount_id
        if not proved or st.st_nlink > 1:
            more, more_doubt = self.doubt(dev, "parent-unproved-or-linked", origin="kept_by_file", site="file-parent")
            found, doubted = found | more, doubted or more_doubt
        return self.counted((found, doubted))

    def file_root(self, fd: int, mount_id: int) -> tuple[frozenset[str], bool]:
        """A file that is a mount's root (a bind of one file): the candidates above its path in its filesystem."""
        dev = self.superblock(fd)[1]
        scope = self.scope(dev)
        if not scope:
            return frozenset(), False
        mount = self.find_mount(mount_id, fresh=True)
        if mount is None or mount.dev != dev or not mount.root.startswith("/") or "//deleted" in mount.root:
            why = "no-table-line" if mount is None else "table-dev-differs" if mount.dev != dev else "root-not-a-path"
            return self.doubt(dev, why, origin="file_root", site="file-root-table", mount_id=mount_id, filesystem_dev=dev)
        return self.containing(scope, mount.root, False)

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
                raise Doubt("listed-not-openable", origin="lookup", site="lookup-listed") from error
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
            raise Doubt("too-many-links", origin="lookup", site="lookup-hops")
        if st.st_dev in self.proc_devs:
            return self.magic(at, name)
        try:
            target = kernel(os.readlink, name, dir_fd=at)
        except Nothing as error:
            raise Doubt("link-vanished", origin="lookup", site="lookup-readlink") from error  # it was a link a moment ago
        return self.exact(self.root if target.startswith("/") else at, target)

    def magic(self, at: int, name: str) -> tuple[int, int]:
        """A procfs link (/proc/<pid>/cwd, root, fd/N, exe): the kernel opens it; a file's directory is proved (file_parent)."""
        if name in ("self", "thread-self"):  # names the process that reads it: the holder, not this scan
            name = self.pid if name == "self" else "%s/task/%s" % (self.pid, self.pid)
        fd = kernel(os.open, name, PATH_ANY, dir_fd=at)
        try:
            if stat.S_ISDIR(kernel(os.fstat, fd).st_mode):
                fd, kept = -1, fd
                return kept, -1
            text = kernel(os.readlink, name, dir_fd=at)
            if not text.startswith("/"):
                return os.dup(fd), NO_DIRECTORY  # a socket, pipe or anonymous inode: no directory holds it
            held = self.file_parent(fd, text)
            fd, kept = -1, fd
            return kept, held
        finally:
            if fd >= 0:
                os.close(fd)

    def file_parent(self, fd: int, text: str) -> int:
        """The directory that holds the open file `fd` whose kernel path is `text`, or -1 when none is proved.

        The path is resolved from the sweeper's root and from the process's root (the kernel prints a
        path of another namespace from that namespace's root); a directory is proved when the name
        found in it is the very file (device, inode and mount). Unproved, the directory the path
        names is still judged (round 3's keep), into `self.extra`: it can only add.
        """
        if text.endswith(" (deleted)"):
            text = text[: -len(" (deleted)")]
        target = self.ident(fd)
        mount = self.mount_of(fd)
        for start in dict.fromkeys((self.root, self.own_root)):
            self.hops = 0
            try:
                held, holder = self.exact(start, text)
            except Nothing:
                continue
            try:
                if holder >= 0 and self.ident(held) == target and self.mount_of(held) == mount:
                    holder, proved = -1, holder
                    return proved
            finally:
                os.close(held)
                if holder >= 0:
                    os.close(holder)
        for start in dict.fromkeys((self.own_root, self.root)):
            self.hops = 0
            try:
                held, holder = self.exact(start, os.path.dirname(text))
            except Nothing:
                continue
            try:
                self.extra |= self.kept_by(held, holder)
            finally:
                os.close(held)
                if holder >= 0:
                    os.close(holder)
        return -1

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
            return self.kept_by_file(fd, holder)
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
        # D8 counts FUSE processes even when there are no candidate directories to keep.
        self.extra = frozenset()
        self.unreadable = False
        self.fuse = 0
        try:
            cwd = os.open(proc + "/cwd", PATH_DIR)
        except OSError:
            cwd = -1  # unreadable: the owner's ruling (it keeps no directory); no relative start
        try:
            self.root = os.open(proc + "/root", PATH_DIR)
            root_doubt: frozenset[str] = frozenset()
        except OSError:
            self.root = os.dup(self.own_root)  # its root cannot be opened: resolved as if it were ours
            # A process whose cwd is readable but whose root is not: its absolute paths are not proved.
            root_doubt = (self.counted(self.doubt(None, "root-unopenable", origin="process", site="process-root"))
                          if cwd >= 0 else frozenset())
        try:
            self.root_id = self.ident(self.root)
            kept = root_doubt | (self.guarded(lambda: self.kept_by(cwd)) if cwd >= 0 else frozenset())
            for argument in arguments:
                if argument:
                    kept |= self.guarded(lambda: self.argument(os.fsdecode(argument), cwd))
            return kept | self.handles(proc) | self.extra
        finally:
            if cwd >= 0:
                os.close(cwd)
            os.close(self.root)
            self.root, self.root_id = self.own_root, self.ident(self.own_root)

    def handles(self, proc: str) -> frozenset[str]:
        """An open directory by identity; an open file by the directory proved to hold it (file_parent)."""
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
        link = "%s/fd/%s" % (proc, name)
        try:
            fd = os.open(link, PATH_ANY)
        except OSError as error:
            if error.errno in NOTHING_ERRNOS:
                return frozenset()  # closed meanwhile
            if error.errno in (errno.EACCES, errno.EPERM):
                self.unreadable = True  # a handle we may not read: the owner's ruling (D3), counted unreadable
                return frozenset()
            text = kernel(os.readlink, link)
            if not text.startswith("/"):
                return frozenset()  # a socket, pipe or anonymous inode: in no directory
            raise Doubt(errno_token(error.errno), origin="handle", site="handle-open", call="open") from error  # a path the kernel would not let us open: not proved
        try:
            if stat.S_ISDIR(kernel(os.fstat, fd).st_mode):
                return self.kept_by(fd)
            text = kernel(os.readlink, link)
            if not text.startswith("/"):
                return frozenset()  # a socket, pipe or anonymous inode: in no directory
            parent = self.file_parent(fd, text)
            try:
                return self.kept_by_file(fd, parent)
            finally:
                if parent >= 0:
                    os.close(parent)
        finally:
            os.close(fd)


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
    _log.pid = resolver.pid
    for kept in sorted(resolver.process(proc, arguments)):
        refs.append((b"k", os.fsencode(kept)))
    if resolver.candidates and resolver.doubts > before:
        refs.append((b"d", str(resolver.doubts - before).encode()))
    if resolver.fuse:
        refs.append((b"u", str(resolver.fuse).encode()))
    if not os.path.isdir(proc):
        return 3, name, []
    return (2 if unreadable or resolver.unreadable else 0), name, refs


def scan(arguments: argparse.Namespace) -> int:
    global _log
    _log = DoubtLog()  # Fresh event counts and a fresh lazy sink for each scan.
    base = os.fsencode(arguments.base)
    out = sys.stdout.buffer
    resolver = Resolver(candidate_map(arguments.candidates_in or []), arguments.deadline)
    _log.write("scan-start own-table-lines=%s candidates=%s unscoped=%s paths-unknown=%s" %
               (len(resolver.own_mounts), len(resolver.everything), len(resolver.unscoped),
                len(resolver.everything - resolver.fs_paths.keys())))
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
    finally:
        _log.total(resolver.doubts)
        if _log.fd is not None:
            os.close(_log.fd)
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
