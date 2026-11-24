# Making a new release

Every installed copy of JA checks this repository's latest release. When its version number is higher than
the installed one, the app shows the release notes and offers to download and install it. Do these steps
each time you want to ship a change.

## 1. Raise the version

In `app/build.gradle.kts`, near the top:

```
versionCode = 40
versionName = "JA 1.1"
```

Raise both, for example to `41` and `"JA 1.2"`. `versionCode` must always go up by at least one, or Android
refuses the update. The numbers in `versionName` decide whether the app sees a new version, so 1.2 must be
higher than 1.1. Click **Sync Now** when Android Studio asks.

## 2. Build the release APK

1. In the **Build Variants** panel, set **:app** to **release**.
2. Choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. When it finishes, click **locate** in the popup. Use the file `app-universal-release.apk` from
   `app/build/outputs/apk/release/`.

Always build on the PC that built the version already on your phone. The APK is signed with that PC's key
(`%USERPROFILE%\.android\debug.keystore`), and Android only installs an update signed with the same key.
Keep a backup copy of that file somewhere safe, and never upload it to GitHub.

## 3. Upload the code

In Android Studio, press **Ctrl + K**, write a short message such as `Version 1.2: faster auto scroll`, then
click **Commit and Push** (or **Commit**, then **Ctrl + Shift + K** to push).

## 4. Publish the release on GitHub

1. Open the repository on GitHub and go to **Releases → Draft a new release**.
2. Under **Choose a tag**, type the version with a `v` in front, for example `v1.2`, and pick
   **Create new tag**.
3. Set the title, for example `JA 1.2`.
4. Write what changed. The app shows this text on its update screen.
5. Drag `app-universal-release.apk` into the attachments box and wait for it to upload.
6. Leave **Set as a pre-release** unticked, then click **Publish release**.

## 5. Update on your devices

Open JA. It checks for updates when it starts and every few hours while it is open, or straight away from
**More → About → Check for updates**. Tap **Download**, then **Install**. Android asks you to confirm the update.
Your library and settings are kept.

## If something goes wrong

* **The app doesn't see the update.** Check that the release is published (not a draft or pre-release), that
  its tag number is higher than the installed version, and that the APK is attached.
* **"App not installed" when updating.** The APK was signed with a different key, or `versionCode` didn't go
  up. Build it again on the original PC with a higher `versionCode`.
* **The update check never runs.** `ja.githubRepo` in `gradle.properties` is empty or wrong, or the repository
  is private. Fix it, then build and install the new version by hand once.
