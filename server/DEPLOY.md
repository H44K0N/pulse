# Deploying the Pulse server

One container, one SQLite file. The container listens on port 8000 and
`docker-compose.yml` publishes it on `127.0.0.1:8200` only; something on the
host (a Cloudflare Tunnel or nginx) provides the public HTTPS endpoint.

## 1. Image

CI pushes `ghcr.io/h44k0n/pulse-server:<tag>`. To build locally instead:

```sh
docker build -t ghcr.io/h44k0n/pulse-server:latest server/
```

## 2. Install and run

```sh
sudo mkdir -p /opt/pulse/data
sudo cp server/docker-compose.yml server/.env.example /opt/pulse/
cd /opt/pulse
sudo cp .env.example .env && sudo chmod 600 .env
openssl rand -hex 32          # paste as PULSE_API_TOKEN in .env, and into the app
sudo chown 10001:10001 data   # the container runs as uid 10001
sudo docker compose up -d     # PULSE_SERVER_IMAGE_TAG=<tag> to pin a version
curl -s http://127.0.0.1:8200/healthz   # {"status":"ok"}
```

The server refuses to start if `PULSE_API_TOKEN` is missing or shorter than 32
characters (`docker compose logs app` shows why). To rotate the token, change
`.env`, `docker compose up -d`, and update the app's settings.

The container runs read-only, as non-root, with all capabilities dropped; it
only writes to `./data` and a small `/tmp` tmpfs.

## 3. Public HTTPS endpoint (pick one)

**a) Cloudflare Tunnel** (how this VPS exposes apps; its nginx only listens on
localhost). Add a public hostname to the existing tunnel, e.g.
`pulse.example.com` → `http://localhost:8200`, in the Zero Trust dashboard or
in cloudflared's `ingress:` config:

```yaml
- hostname: pulse.example.com
  service: http://localhost:8200
```

Cloudflare terminates TLS. Cloudflare's free plan caps request bodies at
100 MB, well above the app's 10 MiB limit. Do not put Cloudflare Access in
front of `/api`, since the app authenticates with its own bearer token, not
with Access.

**b) nginx with TLS** on a conventional host: use
[`nginx/pulse.conf.example`](nginx/pulse.conf.example) (redirects HTTP to
HTTPS, `client_max_body_size 10m` to match the app), get a certificate with
`certbot`, then `nginx -t && systemctl reload nginx`.

## 4. Backups

Use SQLite's online backup, which is safe while the app is running (copying
`pulse.db` alone is not, because recent writes may still be in `pulse.db-wal`):

```sh
sqlite3 /opt/pulse/data/pulse.db ".backup '/var/backups/pulse/pulse-$(date +%F).db'"
```

Run it from cron or a systemd timer and copy the files off the host. To
restore: stop the container, replace `data/pulse.db` (and delete any
`pulse.db-wal` / `pulse.db-shm`), `chown 10001:10001`, start it again.
