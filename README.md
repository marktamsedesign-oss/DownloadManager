# DL Manager for Android: multi-thread download manager

Paste a link, browse to the file and tap **Download**. Each file is split into parts that
download at the same time over separate connections, like IDM.

## Features
- **Multi-thread downloads:** 1–32 threads per file (default 8; change it per download or in Settings).
  - **Dynamic splitting:** when a thread finishes early, it takes half of the biggest part still downloading, so every thread stays busy until the end.
  - **Segment bar:** each card shows one block per thread so you can watch the parts fill.
  - **Fallback:** if a server doesn't allow split downloads, the app switches to a single connection and says so.
- **Built-in browser:**
  - Address bar with a Paste-and-open button.
  - **Download** button for the page that's open (PDF, video, image…).
  - Files the site sends as downloads are caught automatically.
  - Long-press any link or image to get a **Download** option.
  - **⋮ → Download link directly** or **Paste & download** skips the browser.
- **Share a link** to DL Manager from Chrome, Messenger, Telegram or any other app, or pick it under *Open with*.
- **Login links work:** the browser's cookies, user agent and referer are sent with each download.
- **Background downloading:** a foreground service with a progress notification and a *Pause all* button keeps downloads going with the screen off, while you use other apps, or after you swipe the app away.
- **Resume and recovery:**
  - Pause, resume, retry, or restart from 0.
  - Progress of every part is saved.
  - If the network drops, the app waits for it to come back and continues.
  - If the app is closed, unfinished downloads continue the next time you open it.
- **Speed, time left and active thread count** for each download, and you choose how many downloads run at once (1–5).
- **Saved to Downloads/DLManager.** The file is written there directly, so there's no extra copy at the end. Tap a finished file to open it (APKs open the installer) or share it.
- **Unfinished files stay hidden** from Gallery and other apps until they're complete.

## Build the APK
### Option A: GitHub Actions (same as G-eSAA)
1. Create a **private** GitHub repo and push this folder to it.
2. The **Build APK** workflow runs on every push to `main`. You can also start it from **Actions → Build APK → Run workflow**.
3. Open the finished run and download the **DLManager-apk** artifact, which is a zip containing `DLManager.apk`.

### Option B: Android Studio
Open the folder, then choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

## Install on the phone
1. Copy `DLManager.apk` to the phone and tap it.
2. Allow *Install unknown apps* for whichever app you opened it from (Files, Chrome…).
3. Open DL Manager and allow **notifications**.
4. On the Downloads tab, tap **Allow** on the battery card. This lets downloads keep going in the background.
5. **Xiaomi / Oppo / Vivo / Realme / Huawei / Infinix:** also open *App info → Battery* and set it to **No restrictions**. Turn on **Autostart** if your phone has it. These brands kill background apps aggressively.

Every build is signed with the same key (`keystore/dlmanager.jks`), so new versions install over old ones without uninstalling. **Keep the repo private:** anyone with the key could sign an app that installs as an update to yours.

## Background downloads: what to expect
| Situation | Result |
|---|---|
| Screen off / using other apps | ✅ keeps downloading |
| App swiped away from Recents | ✅ keeps downloading on most phones (needs the battery setting on Xiaomi/Oppo/Vivo etc.) |
| Network drops / Wi-Fi ↔ mobile data | ✅ waits for the network and continues from where each part stopped |
| Phone restarts or the app is force-stopped | ⏸ continues when you open the app again |
| Android 15+: more than ~6 hours of background downloading in one day | ⏸ Android's limit; the app pauses cleanly, then open it and tap Resume |
| Server doesn't support split downloads | ▶ downloads with 1 thread; a retry has to start again from 0 |

## Limits
- Streaming sites (YouTube, Facebook video, Netflix…) use protected or segmented streams. This app can't save those, and doing so usually breaks the site's terms.
- Files the page builds with JavaScript (`blob:` links) can't be handed to the downloader.
- Some servers limit connections per user. If a site errors or slows down, use 4–8 threads.

## Code map
```
app/src/main/java/com/markt/dlmanager/
  engine/Downloads.kt      multi-thread engine: split, dynamic re-split, retry, resume, save
  engine/Http.kt           connections, redirects, Range probe, file-name parsing
  engine/Storage.kt        writes into Downloads/DLManager via MediaStore
  engine/Models.kt         download/segment data + JSON persistence
  service/DownloadService  foreground service + wake lock + notification
  ui/BrowserController.kt  WebView + download detection + long-press
  ui/BrowserScreen.kt      browser UI
  ui/DownloadsScreen.kt    list, segment bar, open/share, battery prompt
  ui/Dialogs.kt            Download / Settings / Delete dialogs
  ui/MainActivity.kt       tabs, shared links, notification permission
.github/workflows/build-apk.yml
```
