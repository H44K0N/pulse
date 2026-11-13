# Pulse server

Small FastAPI + SQLite service that stores finished sessions uploaded by the
app. The JSON contract is [`docs/session-format.md`](../docs/session-format.md).
Deployment: [`DEPLOY.md`](DEPLOY.md).

```
app/config.py   env settings (token, DB path, body limit)
app/models.py   validation of the session contract (pydantic)
app/db.py       SQLite schema and queries (stdlib sqlite3)
app/main.py     routes, bearer auth, body size limit
tests/          pytest
```

## API

Everything under `/api` needs `Authorization: Bearer <PULSE_API_TOKEN>`;
anything else gets `401` with `WWW-Authenticate: Bearer`.

| Request | Responses |
|---|---|
| `GET /healthz` (no auth) | `200 {"status":"ok"}`; `503` if the database is unreachable |
| `POST /api/sessions` | `201 {"id":…,"created":true}` new session stored · `200 {"id":…,"created":false}` id already stored (nothing changed) · `422` invalid document · `413` body over 10 MiB |
| `GET /api/sessions?limit=50&before=<id>` | `200 {"items":[summary…],"next_before":<id or null>}`, newest `started_at` first, no samples. `limit` 1-500. `400` if `before` is unknown |
| `GET /api/sessions/{id}` | `200` the document exactly as first uploaded (same bytes) · `404` · `422` if `id` is not a UUID |

**Idempotency.** Uploads are keyed on the session `id` (a UUID; case does not
matter). The first upload wins: re-sending the same id returns `200` and does
not modify the stored document, even if the body differs. So the app can retry
an upload freely; treat both `200` and `201` as "synced". Sessions are never
updated or deleted over the API.

**Validation.** Every field shown in the contract must be present with the right
JSON type (no `"92"` for numbers); timestamps need a timezone. Unknown fields
are accepted and preserved, and `mode`, phase `kind` and event `type` are free
strings so new app features do not need a server release. Error responses are
FastAPI's usual `{"detail":[…]}` without echoing the upload.

A summary item has `id, mode, started_at, ended_at, hr_max_used, duration_ms,
avg_hr, max_hr, time_in_target_ms, received_at` (timestamps normalised to UTC
`Z`).

## Develop

```sh
cd server
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest -q
.venv/bin/ruff check . && .venv/bin/ruff format --check .
PULSE_API_TOKEN=$(openssl rand -hex 32) PULSE_DB_PATH=./data/pulse.db \
  .venv/bin/uvicorn --factory app.main:create_app --reload
```

(On Debian, `python3 -m venv` needs the `python3-venv` package.)

To bump dependencies: change the direct pins (named at the top of each
requirements file), install into a fresh venv, and copy `pip freeze` back.
