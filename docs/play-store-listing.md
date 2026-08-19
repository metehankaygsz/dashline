# Play Store listing — copy & paste

Reference for filling in the Play Console. Not shipped with the app.

---

## App name (max 30 characters)

```
Dashline
```

## Short description (max 80 characters)

```
A minimal launcher for car head units. Clock, weather, media and app shortcuts.
```

## Full description (max 4000 characters)

```
Dashline is a clean, minimal home-screen launcher built for Android car head
units — the aftermarket double-DIN "Android radio" stereos.

Most stock launchers on these units are cluttered, slow, and full of apps you
will never open. Dashline replaces all of that with a single glanceable screen
designed to be read at a glance while driving.

WHAT YOU GET

• A large, legible clock and date
• Current weather plus a three-day forecast
• A media panel showing what is actually playing — album art, title and artist —
  with play, pause, next and previous controls, volume and a seek bar
• Five quick-launch shortcuts along the top bar for the apps you use most
• One-tap tabs for Audio, Radio, Phone, Navigation, Apps and Settings
• A layout of its own for upright screens, with a paged app drawer on the home
  screen itself

A DASHBOARD YOU LAY OUT YOURSELF

• Resize the two panels by dragging the bar between them, or swap their order
• Fill either panel with the media player, a phone shortcut, your own app
  shortcuts, or up to three standard Android home-screen widgets
• The Customize screen shows the real dashboard while you edit it, so what you
  see is what you get
• Reorder the bottom tabs, or hide the ones your unit does not need
• Digital, analog or minimal clock face, with an optional stopwatch

APP DRAWER THAT STAYS OUT OF THE WAY

• Search as you type
• A "frequently used" row built from your own habits
• Long-press to hide apps you never touch
• Updates itself when apps are installed or removed

MAKE IT YOURS

• Seven colour schemes — Midnight, Ocean, Sunset, Forest, Violet, Crimson and
  Graphite — each applying a gradient and matching accent across the whole app
• Light and dark themes, with an automatic mode that follows the time of day
• Choose which app opens for phone, navigation, radio and media
• Ten languages: English, Turkish, Spanish, German, French, Italian, Portuguese,
  Russian, Simplified Chinese and Arabic

BUILT FOR REAL HEAD UNITS

• Works on Android 4.4 and newer, so old units are supported too
• No Google Play Services required
• No account, no ads, no analytics, no tracking
• Small download and light on memory

PERMISSIONS, EXPLAINED

• Location — used only to fetch local weather. Decline it and everything else
  still works.
• Notification access — used only to read what is currently playing so the media
  panel can show it. Optional and off by default.
• Query all packages — required for any launcher, so it can list and open the
  apps installed on your device.

Dashline is free and open source under the GNU GPL v3. You can read every line
of it at https://github.com/metehankaygsz/dashline
```

---

## Category and tags

- **App category:** Personalization
- **Tags:** launcher, home screen, car, customization, widgets

## Contact details

- **Email:** your email address
- **Website:** https://github.com/metehankaygsz/dashline
- **Privacy policy:** https://metehankaygsz.github.io/dashline/privacy-policy

---

## Data safety form answers

**Does your app collect or share any of the required user data types?** → Yes

**Location → Approximate location**
- Collected: **Yes**
- Shared: **Yes** (sent to Open-Meteo to fetch the forecast)
- Processed ephemerally: **Yes** (not stored)
- Required or optional: **Optional** — users can decline
- Purpose: **App functionality**
- Linked to identity: **No**
- Used for tracking: **No**

Nothing else is collected. No personal info, no financial info, no messages, no
photos, no contacts, no app activity analytics, no device identifiers.

**Data encrypted in transit:** Yes (HTTPS)
**Users can request data deletion:** Not applicable — no data is retained

---

## Content rating questionnaire

Category: **Utility, Productivity, Communication or Other**

Answer **No** to every content question — no violence, no sexual content, no
profanity, no drugs, no gambling, no user-generated content, no user
communication, no sharing of location with other users.

Expected result: rated for everyone (PEGI 3 / ESRB Everyone).

---

## Sensitive permission declarations

### QUERY_ALL_PACKAGES

Play will ask why the app needs broad package visibility.

- **Core functionality:** the app is a device launcher / home-screen replacement.
- **Declaration to select:** *App launcher* (a permitted use case).
- **Justification text:**

```
Dashline is a home-screen launcher and replaces the device's home screen. It
must enumerate the installed applications in order to display them in its app
drawer and launch them when the user taps them, which is the app's core
function. The list of applications is read on the device, is never transmitted
off the device, and is never stored.
```

### Notification listener (BIND_NOTIFICATION_LISTENER_SERVICE)

- **Justification text:**

```
Dashline shows a media panel on the home screen displaying the currently playing
track and offering play, pause, next and previous controls. Reading the active
media session requires notification access. The app does not read, store or
transmit any notification content; it uses the media session only to display
playback information on screen. The permission is optional and disabled by
default — the launcher works fully without it.
```

---

## Graphics checklist

| Asset | Requirement | Status |
|---|---|---|
| App icon | 512×512 PNG | `docs/play-store-icon.png` |
| Feature graphic | 1024×500 PNG/JPG | **needed** |
| Phone screenshots | min 2, 16:9 or 9:16 | use `docs/screenshots/*` |
| 7" / 10" tablet screenshots | min 1 each, landscape | use `docs/screenshots/*` |

The existing screenshots are 1280×720 landscape, which satisfies the tablet
slots. Play requires at least two, and landscape screenshots are the right
choice here since the app is landscape-only.
