# DL Manager for Android: multi-thread download manager

Paste a link, browse to the file and tap **Download**. Each file is split into parts that
download at the same time over separate connections, like IDM.

## Features
- **Multi-thread downloads:** 1–32 threads per file (default 8; change it per download or in Settings).
  - **Dynamic splitting:** when a thread finishes early, it takes half of the biggest part still downloading, so every thread stays busy until the end.
  - **Segment bar:** each card shows one block per thread so you can watch the parts fill.
  - **Fallback:** if a server doesn't allow split downloads, the app switches to a single connection and says so.
  - **Fast writes:** network data is collected into 256 KB chunks before it's written, because every write to the shared Downloads folder goes through Android's slow storage layer.
  - **Connection limits:** if a server answers *429/503* to extra connections, those threads step back and the rest finish the file. A connection that drops after making progress reconnects right away.
- **Built-in browser:**
  - **Tabs** with a tab switcher; open tabs are restored when you reopen the app.
  - One compact toolbar (Back, Forward, Home, Tabs, Downloads, ⋮) so the page gets most of the screen.
  - Address bar shows the site and a lock (or a red ⓘ for non-HTTPS pages); while typing it offers **Open**, **Download this link directly**, **Paste & go**, **Paste & download** and matching bookmarks.
  - **Download** button appears when the open page is a file (PDF, video, image…); also in ⋮ → *Download this page*.
  - Files the site sends as downloads are caught automatically.
  - Long-press any link or image: **Download**, *Open in new tab*, *Open here*, *Copy link*.
  - Bookmarks on the start page, find in page, share, copy link, desktop site, full-screen video, file uploads, choice of search engine (Google, DuckDuckGo, Bing, Brave).
- **Pop-up blocker that lets downloads through:**
  - A new window (window.open, target=_blank) is followed invisibly, with its scripts off and through any redirects, just far enough to see what it is. If it's a **file, it goes to the downloader**; if it's a page on the **same site** (e.g. a "your download is ready" page) it opens as a normal new tab; if it's a page on **another site**, it's **blocked** before it shows, with an **Open** button in case you wanted it.
  - Blocks the "tab-under" trick (a click opens a pop-up and sends the current tab to an ad), redirects into the Play Store or other apps without a tap, and requests to known pop-under ad networks.
  - The shield next to the address bar shows how many things were blocked on the page and lets you **allow pop-ups on that site** (needed for some "Sign in with…" windows).
- **Over-the-air updates:** the app checks GitHub Releases, downloads a new version with its own engine, checks its SHA-256 and opens Android's installer. See *Updates* below.
- **Add link** on the Downloads screen, or **share a link** to DL Manager from Chrome, Messenger, Telegram or any other app, or pick it under *Open with*.
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
1. Push this folder to a GitHub repo.
2. The **Build APK** workflow runs on every push to `main`. You can also start it from **Actions → Build APK → Run workflow**.
3. Download `DLManager.apk` from the newest **Release**, or open the finished run and download the **DLManager-apk** artifact (a zip containing `DLManager.apk`).

### Option B: Android Studio
Open the folder, then choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

## Updates (OTA)
Every push to `main` builds the APK with a higher version number (`100 + run number`, shown as `1.1.<run number>`) and publishes it as a **GitHub Release** together with an `update.json` (version, download link, SHA-256, size, commit message). Builds of other branches are not published.

The app checks `releases/latest/download/update.json` when it opens (at most every 6 hours; turn it off in **Settings → Updates**, or tap **Check now**). When a newer version is there it offers **Update**, downloads it, checks the SHA-256 and opens Android's installer. The first time, Android asks you to allow DL Manager to install apps.

- This needs the repository to be **public** (or the releases to be published somewhere public), because the app downloads them without a GitHub login. To use another repo, build with `-PupdateRepo=owner/name`.
- Versions installed before OTA was added (version 1.0) have to be updated by hand once.

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
  browser/Browser.kt       tabs, pop-up probe, other-app links, full-screen video
  browser/BrowserTab.kt    one tab's WebView + download detection + long-press
  browser/Shields.kt       ad-network list, site/file-type helpers
  browser/BrowserPrefs.kt  search engine, blocker settings, bookmarks, saved tabs
  update/Updater.kt        OTA: update.json, download, SHA-256 check, installer
  ui/BrowserScreen.kt      address bar, toolbar, menu, start page, find in page
  ui/TabsScreen.kt         tab switcher
  ui/DownloadsScreen.kt    list, segment bar, open/share, battery prompt, add link
  ui/Dialogs.kt            Download / Settings / Add link / Update / Delete dialogs
  ui/MainActivity.kt       screens, shared links, file uploads, full screen, permissions
.github/workflows/build-apk.yml   build, sign, publish the OTA release
```
