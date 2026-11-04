# CampusRelay

A native Android app for campus deliveries, shared rides, and a student marketplace, based on **Planning and Designing 2.pdf**. Emeris Pretoria is the selected campus. The cream, forest-green and gold interface follows the supplied wireframes.

## Try the Android app

Open the project in Android Studio, or build in this workspace:

```bash
cd /workspace/campus
export JAVA_HOME=/workspace/toolchains/jdk25
export ANDROID_HOME=/workspace/toolchains/android-sdk
export GRADLE_USER_HOME=/workspace/.gradle
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
bash gradlew :app:assembleDebug :app:testDebugUnitTest --max-workers=4
```

Install `app/build/outputs/apk/debug/app-debug.apk` on Android 7.0/API 24 or later. Select **Explore demo** for a complete offline walkthrough. No account, payment or location permission is needed for the demo. The Gradle wrapper is invoked with `bash` because the original file has no executable bit.

### Local walkthrough

1. Home → Create request. Choose distinct buildings, a delivery window, item/size and tip.
2. Post the request, select a matching courier, and confirm pickup. The demo lets you simulate both sides of the handoff.
3. Scan the recipient QR or enter its six-digit code to complete the delivery. An incorrect code is rejected. CO₂ estimates accrue once per completion.
4. Track contains requests, accepted courier jobs, ride bookings and marketplace reservations.
5. Book a seat in Carpool, then complete the demo ride in Track. Reserve an item in Marketplace, then confirm collection in Track. Reservations alone earn no eco credit.
6. Settings supports profile editing and photos, English/isiZulu/Afrikaans, dark mode, notification preferences and biometric protection when the device has strong biometrics enrolled.

Local profiles, jobs, routes, chats, bookings and preferences survive app restarts in a private SQLite database. Seeded couriers, rides and items are fictional. Demo data never uploads into a real university account. The map is a **sample campus schematic**, using the buildings in the reference brief; it is not live GPS or a verified Emeris campus map. Actual Emeris Pretoria building locations are still to be supplied.

## University service configuration

The app separates its local walkthrough from the university workspace. **Continue with Emeris** requires the actual university Microsoft Entra tenant ID, public mobile client ID, trusted email domain and deployed HTTPS API URL. These public configuration values can be entered under Settings → University & API connection. Do not put passwords or client secrets in the mobile app.

The integration uses OIDC authorization code + PKCE, encrypted token storage, server-verified campus membership, authenticated TLS requests, Room snapshots/queued handoffs and network-constrained WorkManager synchronization. QR handoffs can require Android's strong biometric prompt. FCM support activates only with the real Firebase project configuration and notification permission.

For your downloaded Firebase Android configuration, copy it to **`app/google-services.json`**, then select **File → Sync Project with Gradle Files** in Android Studio. The Google Services plugin and default Firebase initialization are wired automatically when that file exists; the offline demo still builds without it. Use the Firebase registration for package `com.example.campus`. The file is ignored by Git. Real push delivery still requires the authenticated university API and server Firebase Admin setup.

See [live service integration](app/src/main/java/com/example/campus/services/INTEGRATION.md) and [backend setup/API contracts](backend/README.md).

### Server

`backend/` contains ASP.NET Core 8 / EF Core, SQLite for local development and a SQL Server migration for Azure SQL. It implements campus authorization, requests/routes/matching, bookings, delivery handoffs, idempotent offline synchronization, rides, marketplace transactions, private chat, reviews, eco history and notification events. Production startup requires valid university identity configuration; no development bypass is exposed.

## Validation

- Android: `:app:assembleDebug`, `:app:testDebugUnitTest` and `:app:assembleDebugAndroidTest`.
- Android device tests: `:app:connectedDebugAndroidTest` with an attached device/emulator. Repository tests isolate their databases; the UI walkthrough creates a test request on its emulator or test device.
- Backend: `dotnet test CampusRelay.Api.Tests/CampusRelay.Api.Tests.csproj` using the SDK pinned in `backend/global.json` (run from `backend` to apply its pin).

Tests cover route geometry/direction, time windows and capacity, invalid delivery input, CO₂ estimates, QR roundtrips, database persistence, handoff state/credits, seat limits, marketplace collection, Room queue idempotency, HTTPS configuration and server authorization.

## Deployment boundaries

No Azure deployment, environment publication, real campus SSO session, remote handoff sync, FCM delivery, or financial transfer has been performed. Those end-to-end checks require the university's actual registrations and infrastructure. There is no payment gateway or real wallet withdrawal. Route matching is campus-scale straight-line proximity; it does not integrate lecture timetables or turn-by-turn navigation. Translation resources cover the primary navigation and forms; some explanatory demo and administrative text remains English.
