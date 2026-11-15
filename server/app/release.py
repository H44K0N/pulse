"""The published app build: `<data>/apk/pulse.apk` plus `<data>/apk/version.json`.

`<data>` is the directory of PULSE_DB_PATH. Publishing a build means putting those two files
there (see DEPLOY.md); nothing is published by default, and then `GET /api/app` answers
`{"published": false}`.

The app installs an update only after checking the downloaded file against `size` and `sha256`.
Both are computed here from the file itself, never read from `version.json`, so a wrong hash
cannot be published by hand. The file is about 45 MB and the container has 256 MB of memory:
it is hashed in chunks, never read whole, and the result is cached until the file changes
(inode, size, mtime or ctime differ).
"""

import hashlib
import json
import logging
import threading
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("pulse.release")

APK_NAME = "pulse.apk"
VERSION_NAME = "version.json"
APK_MEDIA_TYPE = "application/vnd.android.package-archive"

_CHUNK = 1024 * 1024
_MAX_NOTES = 500
_MAX_VERSION_NAME = 40


def apk_dir_for(db_path: str) -> Path:
    """`apk/` next to the database file."""
    return Path(db_path).parent / "apk"


@dataclass(frozen=True)
class Release:
    version_code: int
    version_name: str
    notes: str
    size: int
    sha256: str
    path: Path

    def as_json(self) -> dict:
        return {
            "published": True,
            "version_code": self.version_code,
            "version_name": self.version_name,
            "notes": self.notes,
            "size": self.size,
            "sha256": self.sha256,
            "apk": f"/api/app/apk?v={self.version_code}",
        }


def _stamp(path: Path) -> tuple[int, int, int, int]:
    st = path.stat()
    return (st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)


class FileDigest:
    """Size and SHA-256 of one file, cached per version of the file. Thread-safe."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cached: tuple[Path, tuple[int, int, int, int], int, str] | None = None

    def of(self, path: Path) -> tuple[int, str] | None:
        """(size, sha256 hex), or None while the file is missing or being replaced."""
        with self._lock:
            try:
                before = _stamp(path)
                if self._cached and self._cached[0] == path and self._cached[1] == before:
                    return self._cached[2], self._cached[3]
                h = hashlib.sha256()
                size = 0
                with path.open("rb") as f:
                    while chunk := f.read(_CHUNK):
                        h.update(chunk)
                        size += len(chunk)
                after = _stamp(path)
            except OSError:
                return None
            if before != after or size != after[1]:
                # Replaced while it was being read: offer nothing now, the next request hashes it.
                log.info("apk changed while it was hashed; not offering it this time")
                return None
            self._cached = (path, after, size, h.hexdigest())
            return size, self._cached[3]


def _version(meta: Path) -> tuple[int, str, str] | None:
    """(version_code, version_name, notes) from version.json, or None when it is not valid."""
    try:
        info = json.loads(meta.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(info, dict):
        return None
    code = info.get("version_code")
    name = info.get("version_name")
    notes = info.get("notes", "")
    # bool is an int in Python: `true` is not a version code.
    if not isinstance(code, int) or isinstance(code, bool) or code <= 0:
        return None
    if not isinstance(name, str) or not name.strip() or len(name.strip()) > _MAX_VERSION_NAME:
        return None
    if notes is None:
        notes = ""
    if not isinstance(notes, str):
        return None
    return code, name.strip(), notes.strip()[:_MAX_NOTES]


def current_release(apk_dir: Path, digest: FileDigest) -> Release | None:
    """The published build, or None when nothing (valid) is published."""
    apk = apk_dir / APK_NAME
    meta = apk_dir / VERSION_NAME
    try:
        if not apk.is_file() or not meta.is_file():
            return None
    except OSError:  # e.g. the directory is not readable by the server
        return None
    version = _version(meta)
    if version is None:
        log.warning("apk/version.json is missing fields or not valid JSON; not offering an update")
        return None
    file = digest.of(apk)
    if file is None or file[0] == 0:
        return None
    code, name, notes = version
    size, sha256 = file
    return Release(code, name, notes, size, sha256, apk)
