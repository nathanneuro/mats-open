# Sleep & Mood: notes for Claude

- Kotlin + Jetpack Compose (Material3), one activity, no ViewModel; state is the entry list held in `App` and mirrored to `Store`.
- **Keep it offline.** No INTERNET permission and `allowBackup="false"` are deliberate: this is personal health data. Do not add analytics, crash reporting, sync or any network library without the owner asking.
- **The repository is public.** Never commit exported CSVs, `entries.jsonl` or screenshots that show real entries.
- Pure logic goes in `Model.kt` with a unit test. `org.json` is a stub in local unit tests, so the tests pull the real one in as a test dependency.
- Build: see README. `./gradlew testDebugUnitTest assembleDebug` must pass before a commit.
- Kept on the build versions of the sibling app `signal-boost` (AGP 8.13, Kotlin 2.1.20, compileSdk 36).
