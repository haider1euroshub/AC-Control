# Setup guide

## 1. Network requirements

- The phone and the AC must be on the **same home Wi-Fi network** (same subnet). Mobile data and
  remote access cannot reach the AC.
- These block discovery and control: a **guest** Wi-Fi network, **client/AP isolation** on the
  router, **separate VLANs/SSIDs** that do not route to each other (for example, a separate 2.4 GHz
  IoT network), and a **VPN** running on the phone.
- Mobile data can stay on, and the Wi-Fi does not need internet for everyday control: every AC socket is
  bound to the Wi-Fi network. Only the one-time Haier sign-in needs internet, and it uses whichever network
  has it.
- You do **not** need to open the HaiSmart app first. RoomAC holds the Wi-Fi multicast lock itself while it
  searches and talks to the AC (Haier modules answer with a broadcast, which phones drop in Wi-Fi power
  saving unless some app holds that lock).

## 2. Before every search: the "Ready to search" checklist

The Add air conditioner screen starts with a checklist. When every required row is green the search starts
by itself; coming back from Settings re-checks automatically.

| Row | Required | What the app does |
|---|---|---|
| Wi-Fi | Yes | Off → **Turn on Wi-Fi** opens the Wi-Fi panel (Android 10+ does not let apps switch Wi-Fi on). On but not joined → **Choose Wi-Fi network**. |
| Nearby devices | Yes | Android 13+: the *Nearby devices* permission (`NEARBY_WIFI_DEVICES`, declared "never for location"). Android 12 and older: *Location* (`ACCESS_FINE_LOCATION`), and Location must be switched on (**Turn on Location**). Refused for good → **Open app settings**. The app never reads a location. |
| Same network as the AC | No (warning) | Compares the phone's subnet with the AC's last known address. |
| Bluetooth | No (optional) | Only setting up a brand-new AC uses it; **Turn on Bluetooth** asks Android to switch it on. |

If a required row cannot be fixed, **Search anyway** still runs the Wi-Fi search (it works without the
permission on most phones). If nothing is found while Battery Saver is on, the app says so and offers
**Battery settings**.

## 3. Add an AC

1. Open the app. On first launch it goes straight to **Add air conditioner** and searches the Wi-Fi
   (about 5 seconds). Each AC found is listed by brand, with the name and model it reports.
2. Tap **Connect** next to yours.
   - **Haier:** the first time, the app asks you to sign in with the email or phone number and
     password of the HaiSmart app account the AC was set up with, and the country of that account
     (Pakistan by default), and leave **Stay signed in** on so you are not asked again. The app then
     finds the AC in your account, fetches its key, and proves
     the key by reading the AC's settings over your Wi-Fi. Later Haier ACs on the same account need
     no sign-in.
     Accounts created with Google or Facebook have no password: share the AC in the HaiSmart app
     with a second account that has an email and password, and sign in with that one.
   - **Gree and Gree-module brands:** nothing to sign in to. The AC hands the app its key over Wi-Fi.
   - **If sign-in fails**, the message names the failed step (sign-in, device list, key fetch) and Haier's
     reason code, for example "(Step: sign-in · code 30032)": the account was created under another
     country; choose that country. "This AC is not in the signed-in HaiSmart account" is an account
     binding issue: add the AC in HaiSmart with that account. "Haier sign-in is not configured in this
     build" means the APK was built without `haier.appId`/`haier.appKey`.
3. When the AC's current power, mode and temperature appear, tap **Continue**, pick the room (the
   name fills in from the room or from the name in the HaiSmart app), and **Save**.

Nothing technical is ever asked for: no IP address, MAC, serial number, port or key.

## 4. Managing ACs and accounts

- Switch between ACs from the name at the top of the remote screen.
- **Settings → Rename, choose rooms, remove**, or the slider icon on the remote screen, opens an
  AC's settings: name, room, **Reconnect this AC** (fetches a fresh key; use it if the AC was reset
  or set up again in its own app) and **Remove** (also deletes its key).
- **Settings → Manufacturer accounts** shows the signed-in HaiSmart account and lets you sign out.
  ACs already connected keep working after sign-out. If an AC's key changes, the app fetches a new
  one by itself ("Refreshing AC connection…"); you are asked to sign in again only if Haier refuses
  the saved sign-in and "Stay signed in" was off or the password was changed. **Add another Haier
  account** links a second account for ACs set up under a different one. You only need to sign in again to add a Haier
  AC or if Haier issues an AC a new key.

## 5. Everyday use

- On launch the app checks Wi-Fi, finds the selected AC by its device ID (even if its IP address
  changed), authenticates, and shows **Connected locally**.
- Status refreshes every 10 s while the app is open (adjustable in Settings), and after every
  command. Changes made with the remote show up on the next refresh.
- A command shows "Sending…" until the AC reports the new state. If the AC refuses, the dashboard
  keeps showing what the AC actually reports, and a message explains why.
- The AC accepts **one local session at a time**. If Home Assistant or another local controller
  polls it constantly, this app may report "Another app or hub may be connected"; it retries
  automatically.
