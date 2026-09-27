# Location Timeline

A personal replacement for Google Maps Timeline:

- **Android app** records where your phone goes. It saves battery by not tracking when you're still, checking occasionally when you're moving a little, and tracking most often when you're moving a lot.
- **Supabase** (a free hosted database) stores your points. Only you can read them.
- **Viewer** (`viewer/index.html`) is a web page you open on your laptop to see each day on a map: where you stayed, how you travelled, and how far.

You don't need to install anything on your computer. GitHub builds the app for you, and you download it on your phone.

---

## Part 1: Set up Supabase (the database)

1. Go to <https://supabase.com>, sign up, and click **New project**. Pick any name, a strong database password (you won't need it again), and the region closest to you. Wait a minute or two while it starts.
2. **Create your login.** In the left menu open **Authentication → Users**, click **Add user → Create new user**, enter your email and a password, tick **Auto Confirm User**, and click **Create user**. You'll use this email and password in the app and the viewer.
3. **Create the table.** In the left menu open **SQL Editor**, click **New query**, paste the whole contents of [`supabase/schema.sql`](supabase/schema.sql), and click **Run**. It should say "Success. No rows returned".
4. **Copy two values.** Open **Project Settings → API** (on some accounts it's **Project Settings → Data API** plus **API Keys**) and copy:
   - the **Project URL**, which looks like `https://abcdefgh.supabase.co`
   - the **anon public** key: a long text starting with `eyJ...` (or the newer **publishable** key starting with `sb_publishable_`)

   ⚠️ **Never** use the `service_role` or **secret** key. It bypasses all security. The anon key is safe to use in the app, because the database only lets a signed-in user see their own points.

---

## Part 2: Build the app on GitHub

You do this once. After that, every change pushed to `main` builds a new version automatically.

### 2a. Make a signing key (once)

Android only installs an update if it's signed with the same key as the installed version, so we make one permanent key and store it as a GitHub secret.

1. In this repository on GitHub, open the **Actions** tab. If asked, click **I understand my workflows, go ahead and enable them**.
2. In the left list click **Make signing key**, then **Run workflow → Run workflow**.
3. Wait about a minute until the run has a green tick, then click on it. At the bottom under **Artifacts**, download **signing-key**. It's a zip containing two text files.
4. Open the zip. Then in the repository go to **Settings → Secrets and variables → Actions → New repository secret** and add two secrets:
   - Name `KEYSTORE_BASE64`: paste the entire contents of `KEYSTORE_BASE64.txt` as the value
   - Name `KEYSTORE_PASSWORD`: paste the contents of `KEYSTORE_PASSWORD.txt` as the value
5. Delete the downloaded zip. The artifact is also deleted from GitHub automatically after 1 day (you can delete it sooner from the run page).

> Keep the secrets. If you lose them or replace them, the next app version won't install as an update. You'd have to uninstall the app (losing any points that haven't been uploaded yet) and install the new one.

### 2b. Build the APK

1. Go to **Actions → Build APK → Run workflow → Run workflow**. (It also runs by itself whenever something is pushed to `main`.)
2. Wait for the green tick. This takes about 5 minutes.
3. The app is now on the repository's **Releases** page (on the right of the repository's main page) as **location-timeline.apk**.

If the run shows a yellow warning about a *throwaway key*, the secrets from step 2a weren't found. Don't install that build. Add the secrets and run the workflow again.

### 2c. Install it on your phone

1. On your phone, open the repository on github.com in your browser **while signed in to GitHub** (needed if the repository is private). Tap **Releases**, open the newest release, and tap **location-timeline.apk** to download it.
2. Open the downloaded file. If Android asks, allow your browser to **Install unknown apps** (Settings opens, flip the switch, go back).
3. Tap **Install**. If Google Play Protect warns that it doesn't recognise the app, tap **More details → Install anyway**. That warning appears because the app didn't come from the Play Store.

To update later, download and install a newer release the same way. Your settings and any points that haven't been uploaded are kept.

---

## Part 3: Set up the app

Open **Location Timeline** on the phone. The screen has three sections.

1. **Supabase account.** Enter the Project URL, the anon key, and the email and password from Part 1, then tap **Sign in**. It then shows "Signed in as …". Your password is not stored; only a login token is kept.
2. **Permissions.** Tap **Grant permissions** and accept each request in turn:
   - **Location**: choose *While using the app* and keep **Precise** on.
   - **Physical activity**: *Allow*. This lets the app notice when you start walking, cycling or driving.
   - **Notifications**: *Allow*. A quiet notification shows while tracking is on.
   - **Location all the time**: the app explains, then Android opens a settings page. Choose **Allow all the time**. Without this, tracking stops when the screen is off.
   - **Battery**: allow the app to run in the background (choose *Allow*, or set battery usage to **Unrestricted**). On Samsung, Xiaomi and similar phones, also check Settings → Apps → Location Timeline → Battery → **Unrestricted**.

   Every line of the checklist should show ✓.
3. **Tracking.** Tap **Start tracking**. The status shows the current mode (Walking, Cycling, Driving or Still), how many points are waiting to upload, and when the last upload happened.

Points are saved on the phone first and uploaded about every 15 minutes whenever there's internet. **Upload now** sends them straight away. Tracking restarts on its own after the phone reboots or the app updates.

### How the tracking adapts

| Mode | When | Location checked | Point saved when moved at least |
|---|---|---|---|
| Still | no movement for 3 min (5 min in a vehicle), or the phone reports "still" | every 10 min, low power | 150 m |
| Walking | walking/running, or 1–3 m/s | every 30 s | 20 m |
| Cycling | cycling, or 3–7 m/s | every 15 s | 30 m |
| Driving | in a vehicle, or over 7 m/s | every 10 s | 50 m |

The app starts in Walking mode. When it's still and a check shows you've moved, it switches back up straight away.

---

## Part 4: Use the viewer on your laptop

The viewer is published as a website by GitHub Pages at
**https://denhamsteynor.github.io/phone-tracker/**

One-time setup (GitHub Pages is free only for public repositories):

1. Make the repository public: **Settings → General**, scroll to **Danger Zone → Change repository visibility → Change to public**. The code contains no passwords or keys; your location data stays in Supabase behind your login.
2. Turn on Pages: **Settings → Pages**, and under **Build and deployment → Source** choose **GitHub Actions**.
3. Go to **Actions → Publish viewer website → Run workflow → Run workflow**. After about a minute the site is live. It also republishes automatically whenever `viewer/index.html` changes.
4. Stop strangers creating accounts in your Supabase project: in Supabase open **Authentication → Sign In / Providers** (or **Authentication → Settings**) and switch off **Allow new users to sign up**. Your own user keeps working.

Using it:

1. Open the website and bookmark it.
2. The first time, enter the **Project URL** and **anon/publishable key**, then sign in with your email and password. The browser remembers them. Use **Sign out** on a shared computer.
3. Choose a day with the date picker, or step through days with ‹ and ›. **Today** jumps back to today.
   - The route is coloured by how you travelled: green = walking, orange = cycling, blue = driving.
   - Purple numbered pins are **stays**: places where you spent at least 10 minutes within 150 m.
   - Small dots are individual recorded points. Click one to see its time, speed and accuracy.
   - The side panel shows the day's totals and a list of stays and trips. Click an entry to zoom to it.

### Importing your old Google Timeline

Click **Import** in the viewer and choose your Google export file. The file is read in your browser and saved straight to your own Supabase database. Points you already have are skipped, so importing twice is safe.

- **Newer phones:** on Android open **Settings → Location → Location services → Timeline → Export Timeline data** (or Google Maps → your picture → **Your Timeline** → ⋮ → **Location & privacy settings → Export Timeline data**). This saves `Timeline.json`. You can open the viewer website on the phone and import it there, or copy it to your laptop.
- **Older Google Takeout exports:** `Records.json` and the monthly files in `Semantic Location History` also work. Select several files at once.

The viewer also works on a phone (it stacks the map above the list), and it follows your system's light or dark mode. You can still open `viewer/index.html` straight from disk if you prefer.

---

## Troubleshooting

- **"Points waiting" keeps growing:** check the *Last error* line in the app. "Session expired" means you should sign out and sign in again. "HTTP 401/403" usually means a wrong key or that the SQL in Part 1 wasn't run.
- **Tracking stops overnight:** make sure location is set to *Allow all the time* and battery usage to *Unrestricted*. Some phone makers (Samsung, Xiaomi, Huawei, OnePlus) have extra "sleeping apps" lists. Keep Location Timeline off them.
- **Viewer says sign-in failed:** check the Project URL and key (Change project), and that the user was created with *Auto Confirm User* ticked.
- **Delete data:** in Supabase open **Table Editor → locations** to delete rows. Deleting your user under Authentication also deletes all their points.

## Repository layout

```
.github/workflows/build-apk.yml     builds, tests, signs and releases the APK
.github/workflows/make-keystore.yml makes the permanent signing key (run once)
.github/workflows/pages.yml         publishes the viewer as a website (GitHub Pages)
supabase/schema.sql                 database table and security rules
android/                            the Android app (Kotlin)
viewer/index.html                   the map viewer (single file)
```
