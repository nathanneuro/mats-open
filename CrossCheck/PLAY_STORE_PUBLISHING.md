# Google Play Store Publishing Guide

Here's a concise, up-to-date checklist you can follow to get your Android app back onto the Google Play Store. I've kept it to the key steps you're most likely to forget, plus a few 2025-era gotchas.

---

## 1. Gather the must-have artifacts

- **Signed Android App Bundle (.aab)** – APKs are no longer accepted for new apps as of 2025.

  Build → Generate Signed Bundle / APK in Android Studio; create or reuse your keystore.
- **Icon (512 × 512), feature graphic (1024 × 500), 2–7 phone screenshots**
- **Short (≤ 80 chars) & full description, privacy-policy URL**

---

## 2. Console prep

1. Sign in to [Google Play Console](https://play.google.com/console) (same $25 account you paid before).
2. Create app → choose default language → enter provisional title.
3. Left-hand menu – fill every section that shows a grey ☐ check-mark:
   - **App content** → Content rating (fill the questionnaire; mis-answers risk removal)
   - **Data safety** (mandatory 2025) – list every data type/permission you actually collect.
   - **Pricing & distribution** – free vs paid, countries, ads, age target.

---

## 3. Upload the bundle & roll-out

1. **Release → Production → Create new release**
   - Upload `.aab`
   - Turn on **Play App Signing** (required) – Google keeps the upload key; you keep the "app-signing key" in your keystore.
   - Release name auto-fills; "What's new" can be blank for v1.
2. **Save → Review release → Start rollout to Production**
   - First-time apps publish immediately after review (no manual publish button).

---

## 4. Testing: Sideload the release-ready .aab/.apk yourself

You only need this if you want to test the exact artefact you will upload to Play.

1. **Build → Generate Signed Bundle / APK → choose APK** → fill keystore → give it a versionCode.
2. Copy the resulting `app-release.apk` to the phone (Google Drive, USB file transfer, etc.).
3. On the phone open Files, tap the APK, allow "Install unknown apps" for Files, install.

(You can uninstall later; the signed release sits beside any debug build you already have.)

---

## 5. Wait for review

Google's stated turnaround is ≤ 2 days, but allow up to 7. Watch the Console inbox for policy questions.

---

## 6. Post-publish reminders

- **VersionCode** must increment with every new upload.
- **Target SDK ≥ 35** (Android 15) for any update submitted after 31 Aug 2025.
- If you ever want to switch from Free → Paid, you must create a brand-new app listing (package name change).
