"""GET /api/app and /api/app/apk: the published app build for the in-app update."""

import hashlib
import json
import os
import tracemalloc
from pathlib import Path

import pytest

from app import release

from .conftest import AUTH


def publish(tmp_path: Path, data: bytes, meta: dict | str | None = None, *, code: int = 6) -> Path:
    apk_dir = tmp_path / "apk"
    apk_dir.mkdir(exist_ok=True)
    (apk_dir / "pulse.apk").write_bytes(data)
    if meta is None:
        meta = {"version_code": code, "version_name": "0.5.1", "notes": "One tap to update."}
    (apk_dir / "version.json").write_text(meta if isinstance(meta, str) else json.dumps(meta))
    return apk_dir


def test_nothing_published(client, tmp_path):
    assert not (tmp_path / "apk").exists()
    r = client.get("/api/app", headers=AUTH)
    assert r.status_code == 200
    assert r.json() == {"published": False}
    assert client.get("/api/app/apk", headers=AUTH).status_code == 404
    # Answering created nothing.
    assert not (tmp_path / "apk").exists()


def test_published_build_with_size_and_hash_from_the_file(client, tmp_path):
    data = os.urandom(300_000)
    publish(tmp_path, data)
    r = client.get("/api/app", headers=AUTH)
    assert r.status_code == 200
    assert r.json() == {
        "published": True,
        "version_code": 6,
        "version_name": "0.5.1",
        "notes": "One tap to update.",
        "size": 300_000,
        "sha256": hashlib.sha256(data).hexdigest(),
        "apk": "/api/app/apk?v=6",
    }


def test_apk_download(client, tmp_path):
    data = os.urandom(200_000)
    publish(tmp_path, data)
    r = client.get("/api/app/apk?v=6", headers=AUTH)
    assert r.status_code == 200
    assert r.content == data
    assert r.headers["content-type"] == "application/vnd.android.package-archive"
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["content-length"] == str(len(data))


def test_hash_follows_a_replaced_file(client, tmp_path):
    apk_dir = publish(tmp_path, b"first build" * 1000)
    first = client.get("/api/app", headers=AUTH).json()
    # Replaced the way a publish step does it: write next to it, then rename over it.
    new = os.urandom(123_456)
    (apk_dir / "pulse.apk.tmp").write_bytes(new)
    (apk_dir / "pulse.apk.tmp").replace(apk_dir / "pulse.apk")
    second = client.get("/api/app", headers=AUTH).json()
    assert second["sha256"] == hashlib.sha256(new).hexdigest() != first["sha256"]
    assert second["size"] == 123_456
    assert client.get("/api/app/apk", headers=AUTH).content == new


def test_hash_follows_a_file_rewritten_in_place(client, tmp_path):
    apk_dir = publish(tmp_path, b"a" * 5000)
    first = client.get("/api/app", headers=AUTH).json()["sha256"]
    with (apk_dir / "pulse.apk").open("r+b") as f:  # same inode, same size
        f.write(b"b")
    assert (
        client.get("/api/app", headers=AUTH).json()["sha256"]
        == hashlib.sha256(b"b" + b"a" * 4999).hexdigest()
        != first
    )


def test_hash_is_cached_until_the_file_changes(tmp_path, monkeypatch):
    apk_dir = publish(tmp_path, b"x" * 10_000)
    digest = release.FileDigest()
    opened = []
    real_open = Path.open

    def counting_open(self, *args, **kwargs):
        opened.append(self.name)
        return real_open(self, *args, **kwargs)

    monkeypatch.setattr(Path, "open", counting_open)
    for _ in range(3):
        assert release.current_release(apk_dir, digest) is not None
    assert opened.count("pulse.apk") == 1


@pytest.mark.parametrize(
    "meta",
    [
        "not json",
        "[]",
        "{}",
        '{"version_code": "7", "version_name": "0.5.1"}',
        '{"version_code": true, "version_name": "0.5.1"}',
        '{"version_code": 0, "version_name": "0.5.1"}',
        '{"version_code": -3, "version_name": "0.5.1"}',
        '{"version_code": 7.5, "version_name": "0.5.1"}',
        '{"version_code": 7}',
        '{"version_code": 7, "version_name": "  "}',
        '{"version_code": 7, "version_name": 51}',
        '{"version_code": 7, "version_name": "0.5.1", "notes": ["a"]}',
        "",
    ],
)
def test_broken_version_json_offers_nothing(client, tmp_path, meta):
    publish(tmp_path, b"apk bytes", meta)
    r = client.get("/api/app", headers=AUTH)
    assert r.status_code == 200
    assert r.json() == {"published": False}
    assert client.get("/api/app/apk", headers=AUTH).status_code == 404


def test_missing_apk_or_version_offers_nothing(client, tmp_path):
    apk_dir = publish(tmp_path, b"apk bytes")
    (apk_dir / "pulse.apk").unlink()
    assert client.get("/api/app", headers=AUTH).json() == {"published": False}
    publish(tmp_path, b"apk bytes")
    (apk_dir / "version.json").unlink()
    assert client.get("/api/app", headers=AUTH).json() == {"published": False}


def test_empty_apk_offers_nothing(client, tmp_path):
    publish(tmp_path, b"")
    assert client.get("/api/app", headers=AUTH).json() == {"published": False}


def test_notes_are_optional(client, tmp_path):
    publish(tmp_path, b"apk", {"version_code": 9, "version_name": "0.6.0"})
    body = client.get("/api/app", headers=AUTH).json()
    assert body["notes"] == ""
    assert body["apk"] == "/api/app/apk?v=9"


@pytest.mark.parametrize("path", ["/api/app", "/api/app/apk", "/api/app/apk?v=6"])
@pytest.mark.parametrize(
    "headers", [{}, {"Authorization": "Bearer wrong-token-wrong-token-wrong-token-xx"}]
)
def test_both_need_the_token(client, tmp_path, path, headers):
    publish(tmp_path, b"secret build")
    r = client.get(path, headers=headers)
    assert r.status_code == 401
    assert r.headers["WWW-Authenticate"] == "Bearer"
    assert b"secret build" not in r.content


def test_hashing_reads_in_chunks(tmp_path):
    """A 45 MB build must never be read whole (256 MB container)."""
    apk_dir = tmp_path / "apk"
    apk_dir.mkdir()
    with (apk_dir / "pulse.apk").open("wb") as f:
        for _ in range(24):
            f.write(os.urandom(1024 * 1024))
    tracemalloc.start()
    try:
        size, _ = release.FileDigest().of(apk_dir / "pulse.apk")
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert size == 24 * 1024 * 1024
    assert peak < 4 * 1024 * 1024
