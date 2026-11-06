# Live services

The app's local demo never impersonates SSO, a remote courier, a payment service or a push delivery. These integration classes are available for configured deployments; they do not activate a backend automatically.

- `ServiceConfig(context).configure(apiBaseUrl)` accepts only the verified HTTPS CampusRelay API URL. Firebase identifiers come from `google-services.json`; no Microsoft tenant, OIDC client, or alternate SSO setting remains.
- Construct `GoogleSso(activity)` during Activity creation before STARTED. `start { success, message -> ... }` opens the Google account selector, exchanges the Google token with Firebase Authentication, then verifies the Firebase ID token against `GET /api/v1/profile` before opening connected data.
- `LiveApiClient.request` is synchronous and belongs on a worker thread. It asks Firebase Authentication for a current ID token and sends it as a bearer token over verified TLS. The API validates the Firebase issuer, project audience, signature, lifetime, and provider. Verified Google identities are approved automatically; email/password identities require approved registration proof. Requests do not follow redirects carrying credentials.
- `BiometricGate.authenticate(activity, title, onSuccess, onError)` uses the system's strong biometric prompt. A missing biometric enrollment causes an error, never a silent bypass. Server-side authorization still applies to sensitive actions.
- `OfflineQueue.enqueue(context, deliveryId, qrCode, courierId)` stores real remote handoffs in Room, then uses network-constrained WorkManager with exponential backoff. It never sends demo delivery IDs. Its DAO supports pending counts off the main thread. Accepted rows become SYNCED; rejected rows remain available as REJECTED. Authentication failures remain pending and require sign-in plus `OfflineQueue.schedule`.
- Place the Firebase Android configuration at `app/google-services.json` and sync Gradle. The Google Services plugin is pinned in the version catalog and applied when this file is present, generating the default Firebase resources. `PushSupport.initialize` uses the default Firebase app first; `configureFirebase` remains an optional manual fallback. No Firebase identifiers are invented. FCM service handles data messages; the university workspace requests Android 13+ notification permission once, honoring the user's notification preference. If denied, enable notifications later in Android Settings. The backend must register device tokens at `POST /api/v1/profile/device-token` and hold its Firebase Admin service credential securely on the server. No server credential belongs in the APK.

No API/Firebase project configuration was provided in the cloud workspace, so live Google authentication, synchronization and push could not be end-to-end verified.

## Connected university workspace

After verified Google sign-in, `LiveCampusActivity` provides API-backed delivery requests and route offers, matched-courier bookings, pickup and QR handoff, carpool and marketplace actions, private transaction conversations/reviews, profile editing and eco history. Its locations are user-entered names and coordinates; no sample campus is treated as a live location. Start it only after `GoogleSso` reports server verification success.

`LiveApiClient.requestArray` handles list responses. `LiveSnapshotStore` persists profile, delivery/job and conversation GET snapshots in Room, partitioned by API origin and server-verified account ID. Stale responses display an explicit offline timestamp; normal mutations require network verification. Room schema version 2 migrates the queue-only version 1 without deleting pending handoffs. A configuration change invalidates the previous session and cache binding. Sign-out removes the binding; rows for another account are never shown. Queue synchronization checks the current server profile and submits only that courier's rows.

The connected workspace is implemented against the backend contracts, but no real Firebase configuration or deployed API endpoint is stored in the repository. These live flows are therefore compile/infrastructure-tested rather than verified against an actual deployment. No real wallet or escrow/payment integration is implemented; the UI makes participant-arranged payment explicit.

## Android Studio: connect your downloaded Firebase file

1. Pull the latest repository changes in Android Studio.
2. Change the left Project panel from **Android** to **Project**, expand `campus/app`, and copy the file beside `app/build.gradle.kts`. The exact filename is `google-services.json` (plural *services*), not `google-services (1).json`.
3. The Firebase Android registration must use package name `com.example.campus`, matching `applicationId` in the app module. Download the configuration for that Android registration.
4. In Firebase Console, open **Authentication → Sign-in method** and enable both **Google** and **Email/Password**. Google accounts need no proof in CampusRelay; new email/password accounts do.
5. Download `google-services.json` again after enabling Google so it contains an OAuth web client (`client_type: 3`) used to generate `default_web_client_id`.
6. Run `gradlew signingReport` (Windows: `gradlew.bat signingReport`), copy the debug variant's SHA-1, and add it under Firebase **Project settings → Your apps → Android app → SHA certificate fingerprints**. Download the JSON again if Firebase asks you to.
7. Select **File → Sync Project with Gradle Files**, then run the app on a physical device with Google Play services or an emulator image with Google APIs/Google Play.
8. After Google sign-in, allow notifications when prompted. If you previously denied access, enable it in **Android Settings → Apps → CampusRelay → Notifications**.

The configuration file is ignored by Git and must be copied onto each developer's checkout. A checkout without it still builds the offline demo. No real Firebase configuration was supplied in the cloud workspace, so project-specific initialization and message delivery have not been verified.

This file configures the Android Firebase project, not the university identity provider or the ASP.NET server. Real delivery-status pushes also require the university API setup and the server's Firebase Admin credentials/project configuration described in `backend/README.md`. The existing production notification receiver only displays messages for an authenticated university session; the local demo does not generate remote delivery notifications.
