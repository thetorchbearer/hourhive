# 🐝 HourHive

**A community time bank.** Teach what you know, learn what you want, and pay in *hours* instead of money.
Everyone starts with 2 free hours. Help someone for an hour, earn an hour.

Runs **100% on free tiers** - no credit card required.

| Piece | Tech | Free host |
|---|---|---|
| API | Java 21, Spring Boot 3.3, Spring JDBC, Flyway, OpenAPI/Swagger | **Render** (Docker web service) |
| Database | PostgreSQL | **Neon** |
| Frontend | Vanilla JS single-page app (no build step) | **Vercel** |
| Keep-warm + CI | GitHub Actions | **GitHub** (public repo) |

## What makes it different

- **Escrowed time ledger** - every minute is an immutable ledger row; balance = `SUM(delta)`.
  Booking holds the learner's minutes, completion pays the provider, decline/cancel refunds exactly once
  (row-level locks prevent double-spend and double-refund).
- **Trust without money** - reviews, ratings and a "Top helpers" leaderboard.
- **Free-tier aware by design** - tiny Hikari pool that lets Neon auto-suspend, a DB-free `/api/ping` for
  keep-alive, cold-start banner + retry logic in the UI, JVM flags tuned for 512 MB.
- **No JWT library** - small HMAC-signed stateless tokens; BCrypt passwords; per-IP rate limit on auth routes.

## Features

- Skill listings with search, category chips, sort (newest / top rated / shortest) and max-length filter
- Escrowed time-credit bookings: request, accept, decline, cancel, complete
- Per-booking message thread so learner and provider can agree on a time
- Public member profiles with bio, reputation, active listings and reviews; editable "Your profile" page
- Edit or remove your own listings
- Pending-request badge on Sessions, community stats on the home page, Top helpers leaderboard

### Phase 1 (platform basics)

- **Skill requests board** with **helper matching** (category + keyword + rating scoring; top matches get notified)
- **Weekly availability** slots (UTC) and **scheduled bookings** with **rescheduling** and **overlap prevention**
- **In-app notifications** (bell + inbox) for bookings, messages, reviews and matching requests
- **RBAC** (USER / MODERATOR / ADMIN), **admin dashboard**, **user & listing reports** with moderation actions, **audit log**
- First admin: set `ADMIN_BOOTSTRAP_SECRET` on Render, sign in, open *Your profile → Admin access*. Works only while no admin exists.

## Deploy (about 10 minutes)

### 1. Neon (database)
1. Create a project at <https://neon.tech>.
2. Copy the connection string. Turn **Connection pooling OFF** in the dropdown (Flyway migrations prefer a direct connection).
   It looks like `postgresql://user:pass@ep-xxxx.region.aws.neon.tech/neondb?sslmode=require`.

### 2. Render (API)
1. Push this repo to GitHub (keep it **public** so the keep-warm workflow is free).
2. Render > **New > Blueprint** > select the repo (`render.yaml` is picked up automatically).
3. Set env vars: `DATABASE_URL` = the Neon string, `ALLOWED_ORIGINS` = your Vercel URL (add after step 3; you can start with `*`).
   `JWT_SECRET` is generated for you.
4. Wait for deploy, then open `https://<your-service>.onrender.com/swagger-ui.html`.

### 3. Vercel (frontend)
1. Edit `web/config.js` and set `RENDER_API` to your Render URL. Commit and push.
2. Vercel > **Add New Project** > import the repo > set **Root Directory** to `web` > Deploy. (Framework preset: *Other*.)
3. Put the Vercel URL into Render's `ALLOWED_ORIGINS` (e.g. `https://hourhive.vercel.app`).

### 4. Keep the API warm (optional)
GitHub repo > Settings > Secrets and variables > Actions > **Variables** > add `API_URL` = your Render URL.
The `Keep API warm` workflow pings `/api/ping` every 10 minutes.

## Run locally
```bash
docker compose up -d            # local Postgres (or set DATABASE_URL to your Neon string)
cd api && mvn spring-boot:run   # http://localhost:8080/swagger-ui.html
cd web && npx serve .           # http://localhost:3000  (config.js auto-targets localhost:8080)
```

## API at a glance
`POST /api/auth/register|login` · `GET|PUT /api/me` · `GET /api/me/ledger` ·
`GET|POST /api/listings` · `GET /api/listings/mine` · `DELETE /api/listings/{id}` ·
`POST /api/bookings` · `GET /api/bookings/mine` · `POST /api/bookings/{id}/accept|decline|cancel|complete|review` ·
`PUT /api/listings/{id}` · `GET /api/listings?q=&category=&sort=newest|rating|shortest&maxMinutes=` ·
`GET /api/users/{id}` (public profile + reviews) · `GET|POST /api/bookings/{id}/messages` ·
`GET /api/me/summary` · `GET /api/stats` ·
`GET /api/leaderboard` · `GET /api/categories` · `GET /api/ping` · `GET /api/health`

## Free-tier notes
- Render free services sleep after ~15 min idle (first request takes ~30-60 s); the UI shows a banner and retries.
- Neon auto-suspends when idle; the first query after a nap adds a second or two.
- Ideas to extend: skill requests board, availability slots, email notifications (Resend free tier), groups/circles.
