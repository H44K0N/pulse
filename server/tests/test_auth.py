import pytest

from app.config import load_settings
from app.main import create_app

from .conftest import AUTH, TOKEN, make_session


def test_healthz_needs_no_auth(client):
    r = client.get("/healthz")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_healthz_reports_unreachable_db(client, settings, tmp_path):
    (tmp_path / "pulse.db").unlink()
    (tmp_path / "pulse.db").mkdir()  # a directory cannot be opened as a database
    assert client.get("/healthz").status_code == 503


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"Authorization": ""},
        {"Authorization": f"Basic {TOKEN}"},
        {"Authorization": f"Token {TOKEN}"},
        {"Authorization": "Bearer"},
        {"Authorization": "Bearer wrong-token-wrong-token-wrong-token-xx"},
        {"Authorization": f"Bearer {TOKEN}x"},
        {"Authorization": f"Bearer {TOKEN[:-1]}"},
    ],
    ids=["missing", "empty", "basic", "other-scheme", "no-token", "wrong", "longer", "shorter"],
)
@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/api/sessions"),
        ("GET", "/api/sessions/6f1c1e8a-2d6b-4a8e-9a37-1c0d3f8e2b11"),
        ("POST", "/api/sessions"),
    ],
)
def test_rejects_bad_credentials(client, headers, method, path):
    r = client.request(method, path, headers=headers, json={})
    assert r.status_code == 401
    assert r.headers["WWW-Authenticate"] == "Bearer"


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json", "/"])
def test_nothing_else_is_public(client, path):
    assert client.get(path).status_code == 404


def test_accepts_right_token(client):
    assert client.get("/api/sessions", headers=AUTH).status_code == 200


def test_scheme_is_case_insensitive(client):
    r = client.get("/api/sessions", headers={"Authorization": f"bearer {TOKEN}"})
    assert r.status_code == 200


def test_unauthenticated_post_is_not_stored(client):
    doc = make_session()
    assert client.post("/api/sessions", json=doc).status_code == 401
    assert client.get(f"/api/sessions/{doc['id']}", headers=AUTH).status_code == 404


@pytest.mark.parametrize("token", [None, "", "short", "x" * 31, " " * 40])
def test_refuses_to_start_without_strong_token(monkeypatch, token):
    if token is None:
        monkeypatch.delenv("PULSE_API_TOKEN", raising=False)
    else:
        monkeypatch.setenv("PULSE_API_TOKEN", token)
    with pytest.raises(RuntimeError, match="PULSE_API_TOKEN"):
        load_settings()
    with pytest.raises(RuntimeError, match="PULSE_API_TOKEN"):
        create_app()


def test_loads_settings_from_env(monkeypatch, tmp_path):
    monkeypatch.setenv("PULSE_API_TOKEN", "a" * 32)
    monkeypatch.setenv("PULSE_DB_PATH", str(tmp_path / "x.db"))
    s = load_settings()
    assert s.api_token == "a" * 32
    assert s.db_path == str(tmp_path / "x.db")
    assert s.max_body_bytes == 10 * 1024 * 1024
