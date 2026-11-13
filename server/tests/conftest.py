import copy
import uuid

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

TOKEN = "t" * 40
AUTH = {"Authorization": f"Bearer {TOKEN}"}

# Mirrors the example in docs/session-format.md, plus fields the server does
# not know about, to prove they are preserved.
EXAMPLE_SESSION = {
    "schema_version": 1,
    "id": "6f1c1e8a-2d6b-4a8e-9a37-1c0d3f8e2b11",
    "mode": "interval_4x4",
    "started_at": "2026-11-14T17:02:11Z",
    "ended_at": "2026-11-14T17:46:40Z",
    "hr_max_used": 190,
    "device": {"kind": "polar_h10", "id": "A1B2C3D4", "firmware": "3.3.1"},
    "settings": {"warmup_ms": 600000, "work_zone_pct": [85, 95]},
    "summary": {
        "duration_ms": 2669000,
        "avg_hr": 151,
        "max_hr": 184,
        "time_in_target_ms": 812000,
        "new_hr_max": None,
        "drift_pct": None,
        "future_metric": 1.5,
    },
    "samples": [
        {"t_ms": 0, "hr": 92, "rr_ms": [652, 648]},
        {"t_ms": 1000, "hr": None, "rr_ms": []},
    ],
    "gaps": [{"start_ms": 1000, "end_ms": 14000}],
    "phases": [
        {
            "index": 0,
            "kind": "warmup",
            "start_ms": 0,
            "end_ms": 600000,
            "target_low": 114,
            "target_high": 133,
            "interval_number": None,
        }
    ],
    "intervals": [{"number": 1, "avg_hr": 170, "peak_hr": 181, "pct_in_zone": 84.0, "hrr60": None}],
    "events": [{"t_ms": 600000, "type": "phase_start", "text": "Interval 1 of 4. Go."}],
    "unknown_top_level": {"nested": [1, "two", None, True]},
}


def make_session(**overrides) -> dict:
    doc = copy.deepcopy(EXAMPLE_SESSION)
    doc["id"] = str(uuid.uuid4())
    doc.update(overrides)
    return doc


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(api_token=TOKEN, db_path=str(tmp_path / "pulse.db"), max_body_bytes=64_000)


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as c:  # `with` runs the lifespan (schema setup)
        yield c
