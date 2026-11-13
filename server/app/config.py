"""Settings read from environment variables once, at startup."""

import os
from dataclasses import dataclass

MIN_TOKEN_LENGTH = 32

# A 2 h session at 1 Hz with RR intervals is roughly 1 MB of JSON, so 10 MiB
# leaves plenty of headroom. Keep nginx's client_max_body_size in step.
DEFAULT_MAX_BODY_BYTES = 10 * 1024 * 1024


@dataclass(frozen=True)
class Settings:
    api_token: str
    db_path: str
    max_body_bytes: int


def load_settings() -> Settings:
    """Read settings from the environment; refuse to start on a weak or missing token."""
    token = os.environ.get("PULSE_API_TOKEN", "").strip()
    if len(token) < MIN_TOKEN_LENGTH:
        raise RuntimeError(
            f"PULSE_API_TOKEN must be set to at least {MIN_TOKEN_LENGTH} characters "
            "(generate one with `openssl rand -hex 32`)"
        )
    return Settings(
        api_token=token,
        db_path=os.environ.get("PULSE_DB_PATH", "/data/pulse.db"),
        max_body_bytes=int(os.environ.get("PULSE_MAX_BODY_BYTES", DEFAULT_MAX_BODY_BYTES)),
    )
