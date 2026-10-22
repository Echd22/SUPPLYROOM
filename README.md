# Supply Room

A simple Android app for running a supply room. It scans QR codes and barcodes, checks equipment in and out, receives new stock and counts shelves. Everything is stored in a Google Sheet you own.

- **`app/`** — the Android app (Kotlin + Jetpack Compose, with Google's ML Kit scanner)
- **`backend/`** — the Google Apps Script that turns a Google Sheet into the database. It also serves a desktop page for admin tasks: charts, label printing and voids.
- Every push to `main` builds a signed APK and publishes it under **Releases**.

**Download link for phones (always the newest version):**
`https://github.com/<owner>/<repo>/releases/latest/download/SupplyRoom.apk`

---

## Admin setup (one time, about 20 minutes)

### 1. The Google Sheet
1. In Google Drive, make a folder (for example `Supply Room`). Create a new Google Sheet inside it.
2. In the Sheet, open **Extensions → Apps Script**. Add the four files from `backend/`:
   - `Code.gs`
   - HTML files named `Index`, `JavaScript` and `Stylesheet`

   Paste in their contents and save.
3. Reload the Sheet. A **Supply Room** menu appears. Run **1. Set up sheets** and approve the permissions.
4. Open the **Users** tab and add one row per person. Fill in their name as it should appear in the app, their email (optional), `user` or `admin` for the role, and `TRUE` for active. Admins can also add new items from the phone.
5. Open the **Settings** tab. Set `ORG_NAME`, `TAG_PREFIX` and `SUMMARY_RECIPIENTS` (your email, which gets low-stock alerts and a daily summary).
6. Run **3. Install daily summary (7am)**.

### 2. Deploy the backend
1. In Apps Script, go to **Deploy → New deployment → Web app**.
   - **Execute as:** Me
   - **Who has access:** Anyone
2. Copy the `/exec` URL and paste it into **Settings → APP_URL**.

"Anyone" just means the phone app can reach the script without each person signing in to Google. Every request still has to carry the team key from the setup code. Without it, the script refuses.

> When you change `Code.gs` later, go to **Deploy → Manage deployments → ✏️ → Version: New version**. That keeps the same URL.

### 3. Set up each phone
1. Send the person the download link above. They tap it, download the file, and open it.
   - Android asks them to allow installs from their browser. They tap **Settings → Allow**, then **Install**.
   - If **Play Protect** warns about an unknown app, they tap **More details → Install anyway**.
2. They open **Supply Room** and tap **Scan setup code**.
3. On your computer, go to **Sheet → Supply Room → 4. Show phone setup code**. Let them scan it.
4. They tap their name. That's it.

**If a phone is lost:** run **Change app key**. Then have everyone else scan the new setup code.

---

## Using the app

| Button | What happens |
|---|---|
| **Check Out** | Scan items, type who they're for, pick a loaner period if it's a loan, have them sign, and optionally email the form. |
| **Check In** | Scan items coming back and mark each one *Good* or *Needs a look*. |
| **Receive New** | Scan the box barcode, then the serial number. Serialized items get a control number. Print their labels from the desktop page (**More → Print QR labels**). |
| **Shelf Count** | Scan everything on a shelf. The app lists what's missing, what's in the wrong place, and any count differences. |
| **What's in stock** | Shows on-hand counts, with low stock at the top. Tap an item to see each serial. |
| **Recent** | Shows the latest activity. Tap **Email the form** to resend a copy. |

If there's no signal, the app saves the list on the phone and uploads it automatically next time.

When a new version is published, the home screen shows an **Update available** banner. Tapping it downloads the new APK, which installs over the old one.

### Desktop admin page
Open the same `/exec` URL in a browser while signed in to *your* Google account to get the full admin page. It has charts, history with voids, QR label PDFs and adding catalog items. Your coworkers only need the phone app.

---

## Building

GitHub Actions builds the APK on every push to `main`. Signing uses these repository secrets:

| Secret | What it is |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | The release keystore, base64-encoded |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | `supplyroom` |
| `SIGNING_KEY_PASSWORD` | Key password |

**Keep a backup of the keystore.** Without it, future builds can't install over the existing app. People would have to uninstall first, and they'd lose any unsent saved lists.
