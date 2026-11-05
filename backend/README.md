# CampusRelay REST API

ASP.NET Core 8 / EF Core service for the Android application. It includes persistent delivery requests and courier routes, route/time/capacity matching, booking and QR handoffs, idempotent offline sync, ride offers and seat booking, marketplace reservations, transaction chat, reviews, eco history, and a durable delivery-notification outbox. Money is represented in integer South African cents. **No payment gateway, wallet settlement, fake SSO, or unrestricted development identity is enabled.**

## Deploy on Render

The repository root contains `render.yaml`. In Render, choose **New → Blueprint**, connect this GitHub repository, and select the `main` branch. The blueprint builds `backend/Dockerfile`, checks `/health`, and attaches a 1 GB persistent disk at `/var/data`. A persistent disk requires a paid Render instance; do not remove it because the SQLite database and submitted registration proofs would otherwise be lost on restart or redeploy.

When Render prompts for `Firebase__ProjectId`, enter the Firebase **Project ID** used by the Android app. It is not the project name, number, API key, or the contents of `google-services.json`. Leave `Notifications__EnableFcm=false` until server-side Firebase Admin credentials are configured. After deployment, open `https://<your-render-service>.onrender.com/health`; the expected response is `{"status":"ok","service":"CampusRelay"}`. Enter the service's HTTPS root URL in the Android app under **Google & API connection**.

The blueprint sets `AllowedHosts=*` because Render assigns the public hostname after creating the service. TLS terminates at Render's proxy and the container listens only on Render's supplied `PORT`. Use a single instance with this SQLite deployment. For horizontal scaling or larger production use, add a supported shared database provider before increasing the instance count.

If the service is created manually on Render's Free plan, the Docker image initializes SQLite under `/tmp` and accepts the assigned Render hostname. This is suitable only for a deployment check: Free instances have no persistent disk, so accounts, proof documents, and all other database records can disappear after a restart or redeploy. Set `AllowedHosts=*`, keep the `/health` health-check path, and upgrade to the Blueprint's persistent-disk plan before storing real user data.

## Run locally

Requires .NET SDK 8.0.427 (pinned in `global.json`). This cloud workspace has a checksum-verified SDK at `/workspace/toolchains/dotnet8`.

```bash
cd /workspace/campus/backend
export PATH="/workspace/toolchains/dotnet8:$PATH"
export DOTNET_ROOT=/workspace/toolchains/dotnet8
export DOTNET_CLI_HOME=/workspace/toolchains/dotnet-home
export NUGET_PACKAGES=/workspace/toolchains/nuget
export ASPNETCORE_ENVIRONMENT=Development
# Use the same Firebase project as app/google-services.json:
export Firebase__ProjectId='<firebase-project-id>'
dotnet run --project CampusRelay.Api --urls http://127.0.0.1:5080
```

Development creates a local `campusrelay.db` SQLite database automatically. `GET /health` is public. Every `/api/v1` route requires a valid, unexpired **Firebase ID token issued by the configured project through the Google provider**. The token must contain a subject, verified email, and `firebase.sign_in_provider = google.com`. Without a project ID the development server starts for health checks, but protected routes remain inaccessible. Production startup fails if `Firebase:ProjectId` is missing. Microsoft/Entra and other SSO providers are not accepted.

Local HTTP is for development probes only. Deploy behind a trusted TLS endpoint supporting HTTP/2, configure trusted hostnames using `AllowedHosts`, and use private SQL connectivity as appropriate. Do not expose the local HTTP service publicly. Never place signing keys, access tokens, SQL passwords, or Firebase credentials in source control or Android resources.

## SQL Server / Azure SQL

Set `Database__Provider=SqlServer`, `ConnectionStrings__Campus` securely to the SQL Server connection string (TLS encryption enabled), `Firebase__ProjectId`, and the deployment's `AllowedHosts`. The included migrations target SQL Server; local SQLite uses a separate, disposable development schema. Do not point local SQLite at a production database.

Review and apply the initial migration during controlled deployment:

```bash
cd backend
dotnet tool restore
dotnet ef migrations script --idempotent --project CampusRelay.Api --output schema.sql
# Review schema.sql, then apply with your normal database deployment process.
# Alternatively, with the production connection supplied securely:
dotnet ef database update --project CampusRelay.Api
```

`DesignTimeDb` reads `ConnectionStrings__Campus` and creates migrations for SQL Server without depending on a live SSO connection. It never stores credentials. Production intentionally does not auto-create or auto-migrate the database unless the deployment explicitly enables it.

The included Render blueprint runs one SQLite instance with a persistent disk and `Database__AutoMigrate=true`. On a new disk the service creates the current schema with `EnsureCreated`; SQL Server continues to use reviewed EF migrations. Future SQLite schema changes require a reviewed data migration or a new disk, because `EnsureCreated` does not alter an existing database.

## API contract

Requests and responses use camelCase JSON. IDs are UUID strings. UTC date-times use ISO 8601 with `Z`. Prices are integer cents. Successful creates return 201; normal reads/transitions 200; validation 400; invalid/missing Firebase identity 401; non-Google provider or private transaction access 403; missing entity 404; conflicting state 409.

| Method | Path under `/api/v1` | Purpose |
| --- | --- | --- |
| GET / PUT | `/profile` | Provision from verified SSO claims / update own profile (`fullName`, `bio`, `campusLocation`, `language`: `en`, `zu`, `af`) |
| GET | `/account/status` | Return `unverified`, `pending`, or `approved` for the signed-in Google identity |
| POST | `/account/registration` | Submit multipart `fullName`, `studentNumber`, and required PDF/JPG/PNG `proof` (maximum 5 MB) for institutional review |
| POST | `/profile/device-token` | Register an Android FCM token: `{ "token": "...", "platform": "android" }` |

Apply the registration schema before deploying with `dotnet ef database update`. Reviewers approve a validated submission by changing that user's `RegistrationStatus` from `PENDING` to `APPROVED` through the institution's restricted database administration process. Do not expose proof files or approval updates through a public client endpoint.
| GET | `/eco` | Own cumulative estimated CO2 savings and action history |
| GET / POST | `/deliveries` | List campus deliveries / create request |
| GET | `/deliveries/{id}` | Read a delivery, without exposing the QR secret |
| GET | `/deliveries/{id}/handoff-code` | Requester-only QR verification secret |
| POST | `/delivery-offers` | Create a courier route/capacity/window offer |
| GET | `/deliveries/{id}/matches` | Requester-only list of suitable route offers |
| POST | `/deliveries/{id}/book` | Requester selects `{ "offerId": "..." }` |
| POST | `/deliveries/{id}/pickup` | Assigned courier starts the delivery |
| POST | `/deliveries/{id}/handoff` | Assigned courier completes with `{ "scannedQrHash": "..." }` |
| POST | `/deliveries/{id}/cancel` | Requester cancels an active or matched request |
| POST | `/deliveries/sync-offline` | Idempotent offline QR handoffs (contract below) |
| GET / POST | `/rides` | Search by `origin`, `destination`, `date` / offer a ride |
| POST | `/rides/{id}/book` | Book one seat, with duplicate/overbooking protection |
| GET / POST | `/marketplace` | List (optional `category`) / post a listing |
| POST | `/marketplace/{id}/reserve` | Reserve an available listing |
| GET | `/transactions` | Only transactions involving the current member |
| POST | `/transactions/{id}/complete` | Provider confirms a ride/purchase; deliveries must use QR |
| GET / POST | `/transactions/{id}/messages` | Private member chat; post `{ "content": "..." }` |
| POST | `/transactions/{id}/review` | One review per member after completion: `{ "rating": 5, "comment": "..." }` |
| GET | `/notifications` | Own persisted delivery status events |

### Create delivery

```json
{
  "itemDescription": "Textbook and notes",
  "pickupBuilding": "Engineering Block B",
  "dropoffBuilding": "Merensky Library",
  "pickupLat": -25.755,
  "pickupLng": 28.231,
  "dropoffLat": -25.756,
  "dropoffLng": 28.234,
  "weightKg": 0.5,
  "rewardCents": 3500,
  "windowStart": "2026-11-05T10:00:00Z",
  "windowEnd": "2026-11-05T11:00:00Z"
}
```

The verified caller becomes requester; a client-supplied `senderId` never selects another user's identity. Delivery offers use `startBuilding`, `endBuilding`, `startLat`, `startLng`, `endLat`, `endLng`, `capacityKg`, `windowStart`, and `windowEnd`. Matches require both delivery waypoints within 1 km of the route segment, overlapping windows, and sufficient capacity. Segment proximity uses a local campus-scale projection; replace it with SQL Server geography queries for regional routing or large datasets.

### Offline sync

```json
{
  "offlineTransactions": [
    {
      "localTransactionId": "local-uuid",
      "deliveryId": "delivery-uuid",
      "scannedQrHash": "requester-provided-QR-secret",
      "completedAt": "2026-11-04T15:00:00Z"
    }
  ]
}
```

Response:

```json
{
  "syncedCount": 1,
  "syncedTransactionIds": ["local-uuid"],
  "failedTransactions": []
}
```

Failed items contain `localTransactionId`, `error`, and `status`. Batches are limited to 100. Successful receipt keys are unique per authenticated user and local ID. Retrying does not complete or credit the transaction twice. `courierId` is always derived from the bearer token. The assigned courier must have recorded pickup before handoff. Completion updates the delivery, trade, eco ledger, and notification outbox together; receipts are committed in the same transaction during sync.

### Other create bodies

Ride: `origin`, `destination`, `departureTime` (future UTC), `availableSeats` (1–12), `vehicleType` (`CAR`, `SUV`, `VAN`), `pricePerSeatCents`, `distanceKm`.

Listing: `title`, `description`, `category` (`BOOKS`, `FURNITURE`, `ELECTRONICS`, `CLOTHING`, `OTHER`), `condition` (`NEW`, `LIKE_NEW`, `GOOD`, `FAIR`), `priceCents`.

## Validation and limitations

Run:

```bash
cd /workspace/campus/backend
dotnet test CampusRelay.Api.Tests/CampusRelay.Api.Tests.csproj
```

The focused suite exercises database persistence, route proximity/time/capacity, handoff ownership and code, offline idempotency, seat availability, duplicate marketplace reservations, private chat, reviews, and anonymous HTTP rejection. It uses isolated in-memory SQLite and a test HTTP host; it neither disables authentication in the application nor requires university credentials.

- Google must be enabled under Firebase Authentication → Sign-in method before real authenticated API use. No cloud infrastructure or Firebase project credentials were provisioned here.
- FCM device registration and a durable outbox are implemented. **FCM delivery is not implemented/configured**; an authenticated server-side Firebase Admin dispatcher with credentials, retry/dead-letter handling and device-token invalidation remains required. Pending outbox events are not presented as delivered push notifications.
- No funds are moved. All trades retain `NOT_FUNDED` escrow until a real payment integration is implemented; no wallet withdrawal endpoint is exposed.
- Eco values are illustrative estimates, split equally across the two participants, with an action ledger preventing double credit. They are not certified offsets.
- The route matcher is a campus-scale straight-segment matcher, not a lecture timetable importer or external road-navigation service.
- Photo upload/moderation, user blocking/report moderation, cancellation/refund policies for rides/purchases, administrative operations, and live database scale/performance/security testing remain follow-up work.
- Android hardware biometrics and QR scanning are enforced locally by the client where available; the server verifies identity, assigned participants, transaction state and QR secret. It never trusts a client boolean as proof of biometric authentication.

## Optional Firebase push dispatcher

The server uses Firebase Admin SDK Application Default Credentials (ADC), never Android credentials or a hard-coded service account. Configure a suitable workload identity on the host or mount a service-account credential securely and point `GOOGLE_APPLICATION_CREDENTIALS` at its file path. Do not commit that file. The identity needs Firebase Cloud Messaging send permission for the configured project.

Enable only when that server identity and Firebase project are ready:

```bash
export Notifications__EnableFcm=true
export Notifications__FirebaseProjectId='<Firebase project ID matching the Android app>'
# Optional: secure file path when workload identity is not available
export GOOGLE_APPLICATION_CREDENTIALS='<secure server-mounted credential file path>'
```

The default `Notifications:EnableFcm=false` registers no background worker, performs no sends and leaves status events pending. The enabled worker polls every 15 seconds (`Notifications:PollSeconds`, clamped 5–300), conditionally claims up to 25 pending events with a two-minute lease, and sends high-priority Android FCM data messages. Payload keys are `type=delivery_status`, `deliveryId`, `status`, `notificationId`, `title`, and `body`. The Firebase SDK validates TLS and loads credentials normally. No SDK error text, device token or credential content is logged.

Known successful per-device sends receive durable receipts, so retrying another failed device does not resend those successes. A transient send failure keeps the event `PENDING`, increments `attemptCount`, and records exponential backoff (30 seconds to one hour). After `Notifications:MaxAttempts` (default 8, clamped 1–20), the event becomes `FAILED` for operator inspection. Expired processing leases are recovered automatically after worker restarts. Operators can inspect pending/failed event rows, diagnose Firebase configuration externally, and reset `dispatchStatus=PENDING`, `nextAttemptAt` and attempt count through a controlled database operation; there is no public administrative reset endpoint.

Unregistered device tokens are pruned. Events with no registered devices stay `PENDING` for a later registration, with no counted send attempt. Events whose registered tokens are all invalid become `UNDELIVERABLE`. `SENT` and `sentAt` mean **FCM accepted the message**, not proof that a device displayed or a user read it. Crash recovery is at-least-once: a process can die after FCM acceptance but before its receipt commit, so clients should use `notificationId` to suppress a repeated alert. Hardware notification delivery requires real Firebase configuration and was not claimed by the sender-fake tests.

Outbox schema changes are included in the initial SQL Server migration. For an existing disposable local SQLite development database created before this migration, recreate it after preserving any local data; `EnsureCreated` intentionally does not migrate an existing SQLite schema. Production uses the reviewed SQL Server migrations.
