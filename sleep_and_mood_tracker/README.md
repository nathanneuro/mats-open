# Sleep & Mood

A small Android app for logging sleep, naps, mood and energy, made to take a few taps a day.

- **Mood** and **energy**: tap 1 to 5 whenever you like, as often as you like. Each tap is timestamped. Undo is on the snackbar.
- **Strong emotions**: pick any number from an emotion wheel's families (joy, peace, connection, interest, surprise, sadness, fear, anger, disgust, shame, withdrawal; about 180 emotions), shown as coloured chips. The diary counts them per day.
- Scales: mood 1 awful to 5 great; energy 1 dysfunctional, 2 exhausted, 3 tired, 4 okay, 5 energetic.
- **Last night**: bedtime, wake time and how well you slept (1 to 5). A bedtime later in the day than the wake time counts as the evening before.
- **Naps**: "Start nap now" and "End nap", or add a past nap with its times.
- **Notes**: a line of free text, timestamped.
- **History**: one card per day, newest first. Tap a card to see its entries; tap an entry to delete it.
- **Export**: shares two CSV files through the Android share sheet.
  - `sleep_diary_<date>.csv` has one row per day: bedtime, wake time, hours slept, quality, naps, nap minutes, mean mood and energy with their counts, and notes. This is the shape of the sleep diary sleep clinics ask for.
  - `all_entries_<date>.csv` has every entry.

## Privacy

The app has no network permission and Android backup is turned off, so nothing leaves the phone unless you export it. Entries live in the app's private storage as JSON lines (`files/entries.jsonl`). Uninstalling the app deletes them, so export first.

## Build and install

Built with Kotlin and Jetpack Compose, minSdk 26. On macOS the JDK bundled with Android Studio works:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME=~/Library/Android/sdk
./gradlew testDebugUnitTest assembleDebug
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (gitignored) needs `sdk.dir=<your SDK path>`.

## Layout

- `Model.kt`: the entry type, the JSON format, and the pure logic (which day a night belongs to, daily summaries, the CSV exports). Unit-tested in `app/src/test`.
- `Store.kt`: the append-only file, with atomic rewrites for edits and deletes.
- `MainActivity.kt`: the Today and History screens, the dialogs, and export.
