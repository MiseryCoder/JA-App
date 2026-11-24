# Google Drive sync setup

This build can keep your library, reading progress, settings and extensions in step across your devices through a
backup file in your own Google Drive. Google only lets an app into Drive if the app is registered with a
Google Cloud project, so before the sync switch works you have to register your build once. It is free and
takes about 15 minutes. Nothing from Google has to be pasted into the code: Google recognises the app by its
package name and the certificate it was signed with.

Menu names in Google Cloud Console change from time to time, so a label may differ slightly from what is
written here.

## 1. Find the two values Google asks for

**Package name.** For the debug build this is `app.mihon.dev`. (A release build would be `app.mihon`.)

**SHA-1 of your signing certificate.** Build the debug APK once first, so the debug keystore exists, then run
this in a terminal in the project folder:

```
gradlew.bat signingReport
```

Look for the `debug` variant in the output and copy the line that starts with `SHA1:`. If you prefer, this
gives the same value:

```
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

The certificate belongs to the PC that builds the APK. Install the same APK on your phone and tablet and both
are covered. If you later build on another PC, add a second Android client (step 5) with that PC's SHA-1.

## 2. Create a Google Cloud project

1. Open <https://console.cloud.google.com> and sign in with the Google account whose Drive you want to use.
2. Create a new project. Any name works, for example "Mihon sync".

## 3. Enable the Drive API

1. With the project selected, go to **APIs & Services → Library**.
2. Search for **Google Drive API** and press **Enable**.

## 4. Set up the consent screen

1. Go to **Google Auth Platform** (older consoles call it **APIs & Services → OAuth consent screen**) and
   press **Get started**.
2. Enter an app name and your email address, choose **External** as the audience, and finish the form.
3. Under **Data access**, add the scope `https://www.googleapis.com/auth/drive.file`. It only lets the app
   see files the app itself created, not the rest of your Drive.
4. Under **Audience**, either add your own Google address as a **test user**, or press **Publish app**.
   Publishing is the better choice: while an app is in Testing, Google can ask you to grant access again every
   7 days. This scope is classed as non-sensitive, so publishing should not need a review by Google.

## 5. Create the Android client

1. Under **Clients**, press **Create client** and choose **Android**.
2. Enter the package name and the SHA-1 from step 1 and save.

## 6. Turn sync on in the app

On each device, with the same Google account:

1. Open **Settings → Data and storage → Google Drive sync**.
2. Turn on **Sync with Google Drive** and approve the Google screen that appears.

The first device creates a folder called **Mihon sync** in your Drive with one file, `mihon_sync.tachibk`,
inside it. You can move that folder anywhere in your Drive, including into another folder; the app finds
the file by name. Do not rename the file.

If turning the switch on shows an error, the usual cause is that the package name or SHA-1 in step 5 does not
match the APK you installed, or that your account is not listed as a test user. Changes in the console can
take a few minutes to take effect.

## How the sync behaves

- It runs when the app is opened, when you leave it, and from **Sync now**. It needs an internet connection
  and Google Play services on the device.
- Synced: library entries, categories, read chapters, bookmarks, history, tracking links, app settings,
  source settings, extension repos and which extensions are installed. Not synced: downloaded chapters, the
  storage location and tracker logins.
- Extensions: when you open the app, extensions installed on another device are downloaded from your repos
  and installed here, and extensions uninstalled on another device are removed here. With the default
  installer Android asks you to confirm each one; set **Settings → Advanced → Installer** to **Private** to
  have it happen without prompts. An install you turn down is not offered again. An uninstall you turn down
  keeps the extension, and it then returns to the other device. Nothing is installed or removed while the
  app is in the background.
- Reading progress merges: a chapter read on either device ends up read on both. Marking a chapter as
  unread on one device does not carry over.
- Removing an entry from the library on one device removes it on the others at their next sync. As a
  safety net, if the copy in Drive suddenly lacks most of a library (more than 10 entries and more than
  half), those removals are ignored and the entries are uploaded again.
- For settings and for the categories an entry is in, the device that synced last wins.
- Deleted or renamed categories come back from the other device, because categories are only ever merged.
- If you delete the file in Drive, the next device to sync uploads its own library as the new copy.
