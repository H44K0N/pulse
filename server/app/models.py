"""Validation models for the session contract in docs/session-format.md.

These models only *check* an upload. The server stores and returns the raw JSON
document exactly as received, so fields not listed here (forward compatibility)
are kept untouched. Every model therefore allows extra fields.

Rule of thumb: every field shown in the contract is required. Fields the
contract shows as nullable accept null, and so do HR statistics (avg/max/peak
HR, % in zone), because a session or interval spent entirely in a sensor gap
has no HR data to summarise. Enum-like strings (mode, phase kind,
event type) are deliberately free-form so a new workout mode or cue type in the
app does not need a server release.
"""

from typing import Annotated
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator

# Strict: "92" is not a heart rate. In JSON mode strict still accepts ISO
# strings for datetimes and UUIDs, and ints where floats are expected.
_CONFIG = ConfigDict(extra="allow", strict=True)

NonNegInt = Annotated[int, Field(ge=0)]
Bpm = Annotated[int, Field(ge=0, le=300)]


class Device(BaseModel):
    model_config = _CONFIG
    kind: str
    id: str | None


class Summary(BaseModel):
    model_config = _CONFIG
    duration_ms: NonNegInt
    avg_hr: Bpm | None
    max_hr: Bpm | None
    time_in_target_ms: NonNegInt
    new_hr_max: Bpm | None
    drift_pct: float | None


class Sample(BaseModel):
    model_config = _CONFIG
    t_ms: NonNegInt
    hr: Bpm | None  # null = no valid reading (sensor gap)
    rr_ms: list[NonNegInt]


class Gap(BaseModel):
    model_config = _CONFIG
    start_ms: NonNegInt
    end_ms: NonNegInt


class Phase(BaseModel):
    model_config = _CONFIG
    index: NonNegInt
    kind: str
    start_ms: NonNegInt
    end_ms: NonNegInt
    target_low: Bpm | None
    target_high: Bpm | None
    interval_number: int | None


class Interval(BaseModel):
    model_config = _CONFIG
    number: int
    avg_hr: Bpm | None
    peak_hr: Bpm | None
    pct_in_zone: float | None
    hrr60: int | None


class Event(BaseModel):
    model_config = _CONFIG
    t_ms: NonNegInt
    type: str
    text: str


class SessionIn(BaseModel):
    model_config = _CONFIG
    schema_version: Annotated[int, Field(ge=1)]
    id: UUID
    mode: Annotated[str, Field(pattern=r"^[a-z0-9_]{1,64}$")]
    started_at: AwareDatetime
    ended_at: AwareDatetime
    hr_max_used: Bpm
    device: Device
    settings: dict
    summary: Summary
    samples: list[Sample]
    gaps: list[Gap]
    phases: list[Phase]
    intervals: list[Interval]
    events: list[Event]

    @model_validator(mode="after")
    def _ended_after_started(self) -> "SessionIn":
        if self.ended_at < self.started_at:
            raise ValueError("ended_at must not be before started_at")
        return self
