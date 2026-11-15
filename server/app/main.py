"""Pulse session server: stores finished training sessions uploaded by the app.

Run with:  uvicorn --factory app.main:create_app
(A factory, so that importing this module never needs the token to be set.)
"""

import hmac
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from . import db, release
from .config import Settings, load_settings
from .models import SessionIn


class BodySizeLimit:
    """Reject request bodies larger than `max_bytes` with 413.

    Checks Content-Length up front and also counts bytes as they stream in, so a
    chunked upload without Content-Length cannot get past the limit either.
    Pure ASGI (not BaseHTTPMiddleware) so the body is never buffered here.
    """

    def __init__(self, app: ASGIApp, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        too_large = JSONResponse({"detail": "Request body too large"}, status_code=413)
        headers = dict(scope["headers"])
        length = headers.get(b"content-length")
        if length is not None and length.isdigit() and int(length) > self.max_bytes:
            await too_large(scope, receive, send)
            return

        received = 0
        response_started = False

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    raise _BodyTooLarge
            return message

        async def tracking_send(message: Message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, tracking_send)
        except _BodyTooLarge:
            if not response_started:
                await too_large(scope, receive, send)


class _BodyTooLarge(Exception):
    pass


def _require_token(settings: Settings):
    """Build the dependency that enforces `Authorization: Bearer <PULSE_API_TOKEN>`."""
    expected = settings.api_token.encode()

    def require_token(authorization: Annotated[str | None, Header()] = None) -> None:
        scheme, _, token = (authorization or "").partition(" ")
        # compare_digest runs in constant time, so response timing does not leak
        # how much of a guessed token was right.
        if scheme.lower() != "bearer" or not hmac.compare_digest(token.strip().encode(), expected):
            raise HTTPException(
                status_code=401,
                detail="Invalid or missing bearer token",
                headers={"WWW-Authenticate": "Bearer"},
            )

    return require_token


def _utc_iso(value: datetime) -> str:
    """ISO 8601 in UTC with a Z suffix, as the contract specifies."""
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _row_for(session: SessionIn, raw: str) -> dict:
    return {
        "id": str(session.id),
        "mode": session.mode,
        "started_at": _utc_iso(session.started_at),
        "ended_at": _utc_iso(session.ended_at),
        "started_at_ms": int(session.started_at.timestamp() * 1000),
        "hr_max_used": session.hr_max_used,
        "duration_ms": session.summary.duration_ms,
        "avg_hr": session.summary.avg_hr,
        "max_hr": session.summary.max_hr,
        "time_in_target_ms": session.summary.time_in_target_ms,
        "received_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "doc": raw,
    }


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()  # raises if the token is missing or weak
    db_path = settings.db_path
    # The published app build (release.py). Only read on request: nothing is created here.
    apk_dir = release.apk_dir_for(db_path)
    apk_digest = release.FileDigest()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        db.init_db(db_path)
        yield

    # No /docs or /openapi.json: only /healthz may answer without the token.
    # The API is documented in README.md.
    app = FastAPI(
        title="Pulse server",
        lifespan=lifespan,
        openapi_url=None,
        docs_url=None,
        redoc_url=None,
    )
    app.add_middleware(BodySizeLimit, max_bytes=settings.max_body_bytes)

    @app.get("/healthz")
    def healthz() -> JSONResponse:
        try:
            db.ping(db_path)
        except Exception:
            return JSONResponse({"status": "error", "detail": "database unreachable"}, 503)
        return JSONResponse({"status": "ok"})

    api = APIRouter(prefix="/api", dependencies=[Depends(_require_token(settings))])

    @api.post("/sessions", status_code=201)
    async def upload_session(request: Request) -> JSONResponse:
        """Store a session. Idempotent on `id`: re-uploads return 200, first write wins.

        The body is read and validated by hand (rather than as a typed FastAPI
        body parameter) so the exact bytes the app sent can be stored.
        """
        body = await request.body()
        try:
            session = SessionIn.model_validate_json(body)
            raw = body.decode("utf-8")
        except ValidationError as e:
            # include_input=False: never echo a 1 MB document back in the error.
            raise RequestValidationError(e.errors(include_input=False, include_url=False)) from e
        except UnicodeDecodeError as e:
            raise HTTPException(422, "Body must be UTF-8 encoded JSON") from e

        session_id = str(session.id)
        created = await run_in_threadpool(db.insert_session, db_path, _row_for(session, raw))
        return JSONResponse(
            {"id": session_id, "created": created},
            status_code=201 if created else 200,
            headers={"Location": f"/api/sessions/{session_id}"},
        )

    @api.get("/sessions")
    def list_sessions(
        limit: Annotated[int, Query(ge=1, le=500)] = 50,
        before: Annotated[UUID | None, Query()] = None,
    ) -> dict:
        """Session summaries (no samples), newest `started_at` first.

        To page, pass `before=<next_before>` from the previous response;
        `next_before` is null on the last page.
        """
        # Fetch one extra row to know whether another page exists.
        rows = db.list_summaries(db_path, limit + 1, str(before) if before else None)
        if rows is None:
            raise HTTPException(400, "`before` is not a known session id")
        items = rows[:limit]
        next_before = items[-1]["id"] if len(rows) > limit else None
        return {"items": items, "next_before": next_before}

    @api.get("/sessions/{session_id}")
    def get_session(session_id: UUID) -> Response:
        """The session document exactly as it was first uploaded."""
        doc = db.get_document(db_path, str(session_id))
        if doc is None:
            raise HTTPException(404, "Session not found")
        return Response(doc, media_type="application/json")

    @api.get("/app")
    def app_release() -> dict:
        """The published app build for the in-app update, or `{"published": false}`.

        `size` and `sha256` are computed from the file itself (cached until it changes).
        """
        found = release.current_release(apk_dir, apk_digest)
        return found.as_json() if found else {"published": False}

    @api.get("/app/apk")
    def app_apk() -> FileResponse:
        """The published APK, streamed from disk. `no-store`: a CDN must never keep an old build."""
        found = release.current_release(apk_dir, apk_digest)
        if found is None:
            raise HTTPException(
                404, "No app build is published", headers={"Cache-Control": "no-store"}
            )
        return FileResponse(
            found.path,
            media_type=release.APK_MEDIA_TYPE,
            filename=release.APK_NAME,
            headers={"Cache-Control": "no-store"},
        )

    app.include_router(api)
    return app
