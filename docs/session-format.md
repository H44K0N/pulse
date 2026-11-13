# Session format (app <-> server contract)

One finished session is uploaded as a single JSON document to `POST /api/sessions`.
The server stores it and returns it unchanged from `GET /api/sessions/{id}`.
Upload is idempotent on `id`: re-sending the same `id` must not create a duplicate.

All times are milliseconds relative to `started_at` unless the field says otherwise.
Heart rate is beats per minute (integer). RR intervals are milliseconds (integer).

```json
{
  "schema_version": 1,
  "id": "6f1c1e8a-2d6b-4a8e-9a37-1c0d3f8e2b11",
  "mode": "interval_4x4",
  "started_at": "2026-11-14T17:02:11Z",
  "ended_at": "2026-11-14T17:46:40Z",
  "hr_max_used": 190,
  "device": { "kind": "polar_h10", "id": "A1B2C3D4" },
  "settings": { "...": "protocol and zone settings in effect, free-form object" },
  "summary": {
    "duration_ms": 2669000,
    "avg_hr": 151,
    "max_hr": 184,
    "time_in_target_ms": 812000,
    "new_hr_max": null,
    "drift_pct": null
  },
  "samples": [
    { "t_ms": 0, "hr": 92, "rr_ms": [652, 648] },
    { "t_ms": 1000, "hr": null, "rr_ms": [] }
  ],
  "gaps": [ { "start_ms": 1000, "end_ms": 14000 } ],
  "phases": [
    { "index": 0, "kind": "warmup", "start_ms": 0, "end_ms": 600000,
      "target_low": 114, "target_high": 133, "interval_number": null }
  ],
  "intervals": [
    { "number": 1, "avg_hr": 170, "peak_hr": 181, "pct_in_zone": 84.0, "hrr60": null }
  ],
  "events": [
    { "t_ms": 600000, "type": "phase_start", "text": "Interval 1 of 4. Go." }
  ]
}
```

Field notes:

- `mode`: `"interval_4x4"` or `"easy"`.
- `started_at` / `ended_at`: ISO 8601, UTC, `Z` suffix.
- `device.kind`: `"polar_h10"` or `"simulator"`.
- `samples`: roughly one per second as delivered by the sensor. `hr: null` means no
  valid reading (sensor gap). Values are never interpolated.
- `gaps`: periods with no signal longer than the gap threshold, so a viewer can draw them
  without re-deriving them.
- `phases.kind`: `"warmup"`, `"work"`, `"recovery"`, `"cooldown"` or `"easy"`.
  `target_low` / `target_high` are bpm. `interval_number` is set for work and recovery.
- `intervals`: one entry per work interval. `hrr60` is the heart-rate drop in bpm
  60 s after the work interval ended, or null if it could not be measured.
- `summary.new_hr_max`: set when the session raised the learned HRmax, else null.
- `summary.drift_pct`: cardiac drift for easy sessions (second-half vs first-half avg HR,
  percent), else null.
- `events.type`: `"phase_start"`, `"countdown"`, `"ramp_nudge"`, `"correction_high"`,
  `"correction_low"`, `"interval_recap"`, `"midway"`, `"drift"`, `"sensor_lost"`,
  `"sensor_back"`, `"session_summary"`, `"hr_max_learned"`. `text` is what was spoken.
- Unknown extra fields must be accepted and preserved (forward compatibility).
