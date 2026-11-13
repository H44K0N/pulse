"""SQLite storage with the standard library's sqlite3 module.

One table. The full upload is kept verbatim in `doc` (so unknown fields and
formatting survive), plus a few columns copied out of it for listing and
ordering without parsing every document.

A new connection is opened per operation: SQLite connections are cheap, and it
sidesteps sharing a connection across FastAPI's worker threads.
"""

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id                TEXT PRIMARY KEY,      -- canonical lowercase UUID
    mode              TEXT    NOT NULL,
    started_at        TEXT    NOT NULL,      -- ISO 8601, normalised to UTC 'Z'
    ended_at          TEXT    NOT NULL,
    started_at_ms     INTEGER NOT NULL,      -- UTC epoch ms, for ordering
    hr_max_used       INTEGER NOT NULL,
    duration_ms       INTEGER NOT NULL,
    avg_hr            INTEGER,
    max_hr            INTEGER,
    time_in_target_ms INTEGER NOT NULL,
    received_at       TEXT    NOT NULL,      -- server time of first upload
    doc               TEXT    NOT NULL       -- the JSON document exactly as received
);
CREATE INDEX IF NOT EXISTS sessions_by_start ON sessions (started_at_ms DESC, id DESC);
"""

# Columns returned by the list endpoint (everything except the document).
SUMMARY_COLUMNS = (
    "id",
    "mode",
    "started_at",
    "ended_at",
    "hr_max_used",
    "duration_ms",
    "avg_hr",
    "max_hr",
    "time_in_target_ms",
    "received_at",
)


@contextmanager
def connect(db_path: str) -> Iterator[sqlite3.Connection]:
    """Open a connection, commit on success, roll back on error, always close."""
    conn = sqlite3.connect(db_path, timeout=10)
    conn.row_factory = sqlite3.Row
    try:
        with conn:  # transaction scope
            yield conn
    finally:
        conn.close()


def init_db(db_path: str) -> None:
    """Create the database file and schema if needed, and switch to WAL mode."""
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    with connect(db_path) as conn:
        # WAL lets reads continue while an upload is being written. The setting
        # is stored in the database file, so doing it once at startup is enough.
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(SCHEMA)


def ping(db_path: str) -> None:
    with connect(db_path) as conn:
        conn.execute("SELECT 1 FROM sessions LIMIT 1").fetchall()


def insert_session(db_path: str, row: dict) -> bool:
    """Insert a session; return False (and change nothing) if the id already exists.

    ON CONFLICT DO NOTHING makes this atomic, so two concurrent uploads of the
    same session can never both insert: the first write wins.
    """
    columns = ", ".join(row)
    placeholders = ", ".join(f":{c}" for c in row)
    with connect(db_path) as conn:
        cur = conn.execute(
            f"INSERT INTO sessions ({columns}) VALUES ({placeholders}) "  # noqa: S608 (keys are our own)
            "ON CONFLICT(id) DO NOTHING",
            row,
        )
        return cur.rowcount == 1


def get_document(db_path: str, session_id: str) -> str | None:
    with connect(db_path) as conn:
        found = conn.execute("SELECT doc FROM sessions WHERE id = ?", (session_id,)).fetchone()
    return found["doc"] if found else None


def list_summaries(db_path: str, limit: int, before: str | None) -> list[dict] | None:
    """Newest first. `before` is the id of the last session on the previous page.

    Keyset pagination on (started_at_ms, id): stable even when sessions share a
    start time or new ones arrive between page loads. Returns None if `before`
    is not a known id.
    """
    cols = ", ".join(SUMMARY_COLUMNS)
    with connect(db_path) as conn:
        if before is None:
            rows = conn.execute(
                f"SELECT {cols} FROM sessions "  # noqa: S608 (constant column list)
                "ORDER BY started_at_ms DESC, id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        else:
            cursor = conn.execute(
                "SELECT started_at_ms, id FROM sessions WHERE id = ?", (before,)
            ).fetchone()
            if cursor is None:
                return None
            rows = conn.execute(
                f"SELECT {cols} FROM sessions "  # noqa: S608 (constant column list)
                "WHERE (started_at_ms, id) < (?, ?) "
                "ORDER BY started_at_ms DESC, id DESC LIMIT ?",
                (cursor["started_at_ms"], cursor["id"], limit),
            ).fetchall()
    return [dict(r) for r in rows]
