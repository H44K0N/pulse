import json
import sqlite3

import pytest

from .conftest import AUTH, EXAMPLE_SESSION, make_session


def post(client, doc):
    return client.post("/api/sessions", json=doc, headers=AUTH)


def count_rows(settings) -> int:
    with sqlite3.connect(settings.db_path) as conn:
        return conn.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]


# --- round trip -----------------------------------------------------------


def test_round_trip_preserves_document_including_unknown_fields(client):
    r = post(client, EXAMPLE_SESSION)
    assert r.status_code == 201
    assert r.json() == {"id": EXAMPLE_SESSION["id"], "created": True}
    assert r.headers["Location"] == f"/api/sessions/{EXAMPLE_SESSION['id']}"

    got = client.get(f"/api/sessions/{EXAMPLE_SESSION['id']}", headers=AUTH)
    assert got.status_code == 200
    assert got.headers["content-type"] == "application/json"
    assert got.json() == EXAMPLE_SESSION


def test_returns_the_exact_bytes_uploaded(client):
    raw = json.dumps(make_session(), indent=3, ensure_ascii=False).encode()
    sid = json.loads(raw)["id"]
    r = client.post(
        "/api/sessions", content=raw, headers={**AUTH, "Content-Type": "application/json"}
    )
    assert r.status_code == 201
    assert client.get(f"/api/sessions/{sid}", headers=AUTH).content == raw


def test_uppercase_id_is_the_same_session(client):
    doc = make_session()
    assert post(client, {**doc, "id": doc["id"].upper()}).status_code == 201
    assert client.get(f"/api/sessions/{doc['id']}", headers=AUTH).status_code == 200
    assert post(client, doc).status_code == 200


def test_wal_mode(client, settings):
    with sqlite3.connect(settings.db_path) as conn:
        assert conn.execute("PRAGMA journal_mode").fetchone()[0] == "wal"


# --- idempotency ----------------------------------------------------------


def test_same_id_twice_is_stored_once_and_first_write_wins(client, settings):
    first = make_session()
    second = {**first, "mode": "easy", "summary": {**first["summary"], "avg_hr": 99}}

    r1 = post(client, first)
    r2 = post(client, second)
    assert (r1.status_code, r1.json()) == (201, {"id": first["id"], "created": True})
    assert (r2.status_code, r2.json()) == (200, {"id": first["id"], "created": False})

    assert count_rows(settings) == 1
    assert client.get(f"/api/sessions/{first['id']}", headers=AUTH).json() == first
    [item] = client.get("/api/sessions", headers=AUTH).json()["items"]
    assert (item["mode"], item["avg_hr"]) == ("interval_4x4", 151)


# --- 404 ------------------------------------------------------------------


def test_unknown_session_is_404(client):
    r = client.get("/api/sessions/00000000-0000-4000-8000-000000000000", headers=AUTH)
    assert r.status_code == 404


def test_non_uuid_path_is_422(client):
    assert client.get("/api/sessions/not-a-uuid", headers=AUTH).status_code == 422


# --- listing --------------------------------------------------------------


def seed(client, n: int) -> list[str]:
    """Post n sessions one day apart, in shuffled order; return ids newest first."""
    docs = [
        make_session(
            started_at=f"2026-11-{day:02d}T07:00:00Z", ended_at=f"2026-11-{day:02d}T08:00:00Z"
        )
        for day in range(1, n + 1)
    ]
    for doc in docs[1::2] + docs[0::2]:
        assert post(client, doc).status_code == 201
    return [d["id"] for d in reversed(docs)]


def test_list_is_newest_first_without_samples(client):
    ids = seed(client, 3)
    body = client.get("/api/sessions", headers=AUTH).json()
    assert [i["id"] for i in body["items"]] == ids
    assert body["next_before"] is None
    item = body["items"][0]
    assert set(item) == {
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
    }
    assert item["started_at"] == "2026-11-03T07:00:00Z"
    assert item["duration_ms"] == EXAMPLE_SESSION["summary"]["duration_ms"]


def test_list_orders_by_instant_not_by_string(client):
    # 18:00+02:00 is 16:00Z: earlier than 17:00Z even though it sorts later as text.
    a = make_session(started_at="2026-11-05T18:00:00+02:00", ended_at="2026-11-05T19:00:00+02:00")
    b = make_session(started_at="2026-11-05T17:00:00Z", ended_at="2026-11-05T18:00:00Z")
    post(client, a)
    post(client, b)
    items = client.get("/api/sessions", headers=AUTH).json()["items"]
    assert [i["id"] for i in items] == [b["id"], a["id"]]
    assert items[1]["started_at"] == "2026-11-05T16:00:00Z"


def test_pagination_walks_every_session_once(client):
    ids = seed(client, 7)
    seen, before = [], None
    for _ in range(10):
        params = {"limit": 3, **({"before": before} if before else {})}
        body = client.get("/api/sessions", params=params, headers=AUTH).json()
        seen += [i["id"] for i in body["items"]]
        before = body["next_before"]
        if before is None:
            break
    assert seen == ids


def test_pagination_with_identical_start_times(client):
    docs = [make_session() for _ in range(5)]  # all share started_at
    for d in docs:
        post(client, d)
    page1 = client.get("/api/sessions", params={"limit": 2}, headers=AUTH).json()
    page2 = client.get(
        "/api/sessions", params={"limit": 10, "before": page1["next_before"]}, headers=AUTH
    ).json()
    got = [i["id"] for i in page1["items"] + page2["items"]]
    assert sorted(got) == sorted(d["id"] for d in docs)
    assert len(set(got)) == 5


def test_exact_page_has_no_next(client):
    seed(client, 2)
    body = client.get("/api/sessions", params={"limit": 2}, headers=AUTH).json()
    assert len(body["items"]) == 2
    assert body["next_before"] is None


@pytest.mark.parametrize(
    "params",
    [{"limit": 0}, {"limit": 501}, {"limit": "x"}, {"before": "nope"}],
)
def test_list_rejects_bad_params(client, params):
    assert client.get("/api/sessions", params=params, headers=AUTH).status_code == 422


def test_list_unknown_before_is_400(client):
    r = client.get(
        "/api/sessions", params={"before": "00000000-0000-4000-8000-000000000000"}, headers=AUTH
    )
    assert r.status_code == 400


# --- validation -----------------------------------------------------------


def without(key: str) -> dict:
    doc = make_session()
    del doc[key]
    return doc


def with_summary(**changes) -> dict:
    doc = make_session()
    doc["summary"] = {**doc["summary"], **changes}
    return doc


@pytest.mark.parametrize(
    "doc",
    [
        without("id"),
        without("mode"),
        without("started_at"),
        without("samples"),
        without("summary"),
        make_session(id="not-a-uuid"),
        make_session(id=12345),
        make_session(mode=""),
        make_session(mode="Interval 4x4"),
        make_session(schema_version=0),
        make_session(started_at="yesterday"),
        make_session(started_at="2026-11-14T17:02:11"),  # no timezone
        make_session(ended_at="2026-11-14T16:00:00Z"),  # before started_at
        make_session(hr_max_used="190"),  # strings are not numbers
        make_session(samples=[{"t_ms": 0, "hr": 92.5, "rr_ms": []}]),
        make_session(samples=[{"t_ms": -1, "hr": 92, "rr_ms": []}]),
        make_session(samples=[{"t_ms": 0, "hr": 92}]),
        make_session(events=[{"t_ms": 0, "type": "phase_start"}]),
        with_summary(duration_ms=None),
        [EXAMPLE_SESSION],
        "just a string",
    ],
)
def test_invalid_documents_are_422(client, settings, doc):
    r = post(client, doc)
    assert r.status_code == 422, r.text
    assert count_rows(settings) == 0


@pytest.mark.parametrize("raw", [b"", b"{", b"\xff\xfe", b"null", b'{"id": NaN}'])
def test_unparseable_bodies_are_422(client, raw):
    r = client.post("/api/sessions", content=raw, headers=AUTH)
    assert r.status_code == 422


def test_validation_errors_do_not_echo_the_document(client):
    doc = make_session(mode="BAD MODE")
    r = post(client, doc)
    assert r.status_code == 422
    assert "samples" not in r.text
    assert any(e["loc"][-1] == "mode" for e in r.json()["detail"])


def test_nullable_hr_statistics_are_accepted(client):
    doc = with_summary(avg_hr=None, max_hr=None)
    doc["intervals"] = [
        {"number": 1, "avg_hr": None, "peak_hr": None, "pct_in_zone": None, "hrr60": None}
    ]
    assert post(client, doc).status_code == 201


def test_unknown_mode_and_event_types_are_accepted(client):
    doc = make_session(mode="tempo_run")
    doc["events"] = [{"t_ms": 0, "type": "brand_new_cue", "text": "Hi"}]
    assert post(client, doc).status_code == 201


# --- body size ------------------------------------------------------------


def big_session(min_bytes: int) -> dict:
    n = min_bytes // 30 + 1
    return make_session(samples=[{"t_ms": i, "hr": 150, "rr_ms": [400]} for i in range(n)])


def test_body_over_limit_is_413(client, settings):
    doc = big_session(settings.max_body_bytes)
    r = post(client, doc)
    assert r.status_code == 413
    assert count_rows(settings) == 0


def test_chunked_body_over_limit_is_413(client, settings):
    raw = json.dumps(big_session(settings.max_body_bytes)).encode()

    def chunks():  # a generator body is sent chunked, without Content-Length
        for i in range(0, len(raw), 4096):
            yield raw[i : i + 4096]

    r = client.post("/api/sessions", content=chunks(), headers=AUTH)
    assert "content-length" not in r.request.headers
    assert r.status_code == 413
    assert count_rows(settings) == 0


def test_body_under_limit_is_accepted(client, settings):
    doc = big_session(settings.max_body_bytes // 2)
    assert len(json.dumps(doc)) < settings.max_body_bytes
    assert post(client, doc).status_code == 201
