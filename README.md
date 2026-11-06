# CampusRelay

CampusRelay is a native Android application for the Emeris Pretoria campus community. It helps verified students coordinate campus deliveries, share rides, exchange second-hand items, communicate securely, and track estimated environmental impact.

## Main features

- Firebase email/password authentication
- Google Single Sign-On through Firebase Authentication
- Biometric account unlocking and transaction confirmation
- Registration-proof submission for new email/password accounts
- Campus delivery requests and courier route matching
- QR-code delivery handoffs
- Carpool offers and seat reservations
- Student marketplace listings and reservations
- Private transaction messaging and reviews
- Push notifications through Firebase Cloud Messaging
- Offline Room caching and WorkManager synchronization
- English, Afrikaans, and isiZulu resources
- Light and dark themes
- ASP.NET Core REST API hosted on Render

## Technology stack

| Layer | Technology |
| --- | --- |
| Mobile application | Kotlin, Android SDK, Room, WorkManager |
| Authentication | Firebase Authentication and Google Sign-In |
| Notifications | Firebase Cloud Messaging |
| Biometrics | AndroidX Biometric |
| QR codes | ZXing |
| REST client | OkHttp |
| Backend | ASP.NET Core 8 Minimal API |
| Data access | Entity Framework Core |
| Database | SQLite or SQL Server |
| Hosting | Docker and Render |

## Repository structure

```text
app/                     Android application
backend/CampusRelay.Api  ASP.NET Core API
backend/CampusRelay.Api.Tests
                         Backend automated tests
docs/                    Validation documentation
render.yaml              Render Blueprint configuration
```

## Requirements

- Android Studio
- Android SDK 37
- JDK 11 or later
- Android phone or emulator running Android 7.0/API 24 or later
- Firebase project
- .NET 8 SDK for backend development

## Firebase setup

1. Create or open a Firebase project.
2. Register an Android application with package name `com.example.campus`.
3. Run the signing report from the repository root on Windows:

   ```powershell
   .\gradlew.bat signingReport
   ```

4. Add the debug `SHA1` and `SHA-256` values to the Firebase Android application.
5. Enable **Google** and **Email/Password** under **Authentication → Sign-in method**.
6. Download a fresh `google-services.json` and place it at `app/google-services.json`.
7. Do not commit `google-services.json`. It is ignored by Git.

## Build and run the Android app

Open the repository in Android Studio, allow Gradle synchronization to finish, connect a phone with USB debugging enabled, and select **Run**.

From PowerShell, build and test with:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Authentication rules

- Google SSO accounts are treated as approved student-email identities and receive immediate access.
- New email/password accounts must submit a name, student number, and PDF/JPG/PNG proof of registration.
- Email/password accounts remain pending until an administrator changes their registration status to `APPROVED`.
- Firebase passwords, tokens, and service credentials are never stored in the repository.

## Connected API

API root:

```text
https://campus-qci7.onrender.com
```

Health check:

```text
https://campus-qci7.onrender.com/health
```

Expected response:

```json
{
  "status": "ok",
  "service": "CampusRelay"
}
```

Enter the API root address, without `/health`, on the app's **Firebase & API connection** page.

## Run the backend locally

From the `backend` directory:

```powershell
$env:ASPNETCORE_ENVIRONMENT="Development"
$env:Firebase__ProjectId="YOUR_FIREBASE_PROJECT_ID"
dotnet run --project CampusRelay.Api --urls http://127.0.0.1:5080
```

Run backend tests with:

```powershell
dotnet test CampusRelay.Api.Tests/CampusRelay.Api.Tests.csproj
```

## Render deployment

The repository includes `render.yaml` and `backend/Dockerfile`.

1. Create a Render Blueprint or Docker Web Service from this repository.
2. Set the Dockerfile path to `backend/Dockerfile` when creating it manually.
3. Configure:

   ```text
   Firebase__ProjectId=YOUR_FIREBASE_PROJECT_ID
   Database__Provider=Sqlite
   Database__AutoMigrate=true
   AllowedHosts=*
   Notifications__EnableFcm=false
   ```

4. Use `/health` as the health-check path.
5. Use a persistent disk mounted at `/var/data` for production SQLite data.

The Free Render plan uses temporary storage. Accounts, proof documents, tokens, and transaction data can disappear after a restart or redeployment. Use persistent storage before accepting real registrations.

## Enable push notifications on Render

1. In Firebase, open **Project settings → Service accounts** and generate a private key.
2. In Render, create `/etc/secrets/firebase-service-account.json` as a secret file.
3. Add:

   ```text
   Notifications__EnableFcm=true
   Notifications__FirebaseProjectId=YOUR_FIREBASE_PROJECT_ID
   GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-service-account.json
   ```

Never commit the service-account JSON file.

## Offline synchronization

The connected workspace caches account data in Room. Offline data is labelled as a cached snapshot. Verified delivery handoffs can be saved in an account-partitioned queue. WorkManager submits them when connectivity returns, and unique transaction identifiers prevent duplicate completion or eco-impact credit.

## Testing

Android:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Backend:

```powershell
cd backend
dotnet test CampusRelay.Api.Tests/CampusRelay.Api.Tests.csproj
```

Tests cover authentication policy, account verification, delivery validation, route matching, QR handoffs, offline idempotency, ride capacity, marketplace reservations, private messaging, reviews, notifications, and database persistence.

## Security notes

- Connected API traffic uses HTTPS.
- The API validates Firebase token signatures, issuer, audience, lifetime, and provider.
- Registration documents require restricted database access and encrypted backups.
- Biometric data remains inside Android's secure biometric framework.
- QR handoff codes are revealed only to the delivery requester.
- Never commit Firebase private keys, service-account files, bearer tokens, or passwords.

## Current limitations

- The Free Render deployment does not provide durable database storage.
- Payment is arranged between participants; there is no payment gateway or funded escrow.
- Campus routing uses coordinate proximity rather than turn-by-turn navigation.
- Registration approval currently requires controlled backend database administration.
- Environmental-impact values are estimates rather than certified carbon offsets.

## Author

Developed as a PROG7314 portfolio-of-evidence project.
