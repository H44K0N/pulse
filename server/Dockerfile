# Pulse session server. Runs as an unprivileged user and works with a
# read-only root filesystem: the only writable paths it needs are /data (the
# SQLite database, a volume) and /tmp (tmpfs in docker-compose.yml).

FROM python:3.13.18-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN python -m venv /opt/venv
COPY requirements.txt /tmp/requirements.txt
RUN /opt/venv/bin/pip install -r /tmp/requirements.txt && /opt/venv/bin/pip check
COPY app /srv/app
# Compile bytecode now, since the read-only filesystem prevents it at runtime.
RUN /opt/venv/bin/python -m compileall -q /opt/venv /srv/app

FROM python:3.13.18-slim
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PULSE_DB_PATH=/data/pulse.db
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin pulse \
    && mkdir /data && chown pulse:pulse /data
COPY --from=build /opt/venv /opt/venv
COPY --from=build /srv/app /srv/app
WORKDIR /srv
USER 10001:10001
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=4)"]
# One worker is plenty for one user, and keeps SQLite writes in one process.
CMD ["uvicorn", "--factory", "app.main:create_app", "--host", "0.0.0.0", "--port", "8000", "--no-server-header"]
