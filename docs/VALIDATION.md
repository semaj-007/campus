# Validation record

Validated in the cloud workspace on 4 November 2026.

| Check | Outcome |
| --- | --- |
| Android debug APK | Built successfully; API 24 minimum, API 37 target |
| Android unit tests | 14 passed, 0 failed, 0 skipped (12 business rules, 2 QR tests) |
| Android lint | Completed with no errors; advisory warnings remain |
| Android device test APK | Compiled; includes repository, Room/service and delivery UI tests |
| ASP.NET / EF backend | Build passed with zero warnings and errors |
| Backend tests | 20 passed, including auth policy, handoff, booking, messaging and FCM retry tests |
| API startup | Local health request succeeded; anonymous profile request returned 401 |
| SQL Server migration | Idempotent SQL script generated and inspected; not applied to a production database |
| Actual Google/Firebase sign-in, remote offline sync, FCM delivery | Not executed; Firebase project and deployed service required |

Commands:

```bash
# Android, with JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME activated as in README:
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --max-workers=4

# API, with the .NET SDK environment activated as in backend/README:
cd backend
dotnet test CampusRelay.Api.Tests/CampusRelay.Api.Tests.csproj
```

A headless Android 35 emulator was installed and boot attempted using software emulation because the host exposes no KVM acceleration. Android's watchdog restarted its framework during a slow first boot, before the app could be installed. The framework restarted again during APK installation inside Android StorageManager, before any app process launched. The owned emulator was stopped. Device tests and visual/camera/biometric verification remain unrun; this is an emulator startup limitation, not an observed app crash.

No environment configuration was republished, no Azure resources were deployed, and no financial transactions were performed.
