# Privacy Policy

**Dashline**

Last updated: 30 July 2026

## Summary

This app does not collect, store, or transmit personal data to its developer.
There are no accounts, no analytics, no advertising, and no tracking. The only
data that ever leaves your device is an approximate location sent to a weather
service so the app can show local weather, and that is optional.

## Information the app uses

### Location

- **What:** your device's approximate or precise coordinates, obtained from the
  Android location service.
- **Why:** solely to fetch the current weather and forecast for where you are.
- **Where it goes:** the coordinates are sent to [Open-Meteo](https://open-meteo.com),
  a free weather API, as part of a weather request. Nothing else is sent with
  them — no device identifiers, no account information.
- **Retention:** the app does not store your location. Coordinates are used for
  the request and discarded.
- **Optional:** you may decline the location permission. The weather panel then
  shows "Weather unavailable" and every other feature continues to work.

Open-Meteo's own privacy terms are at https://open-meteo.com/en/terms.

### Media playback information

If you enable Notification access, the app reads the currently playing track's
title, artist, and artwork from whichever media app is playing, so it can display
them and offer playback controls.

- This information is read on the device and displayed on screen only.
- It is never transmitted anywhere and never stored.
- This access is optional and off by default. You can revoke it at any time in
  your device's system settings.

### Installed applications

The app lists the applications installed on your device so it can show them in
the app drawer and let you launch them. This is inherent to being a launcher.
The list is read on the device, is never transmitted, and is never stored.

## Information the app stores on your device

Your settings — chosen language, theme, colour scheme, default apps, favourites,
hidden apps, and how often you open each app — are saved locally in the app's
private storage. They never leave your device and are deleted when you uninstall
the app.

## Data sharing

No personal data is sold, rented, or shared with third parties. The only outbound
network request the app makes is the weather lookup described above.

## Children's privacy

The app is not directed at children and collects no personal information from
anyone.

## Permissions

| Permission | Purpose |
|---|---|
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | Fetch local weather. Optional. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Request weather data; show the Wi-Fi indicator. |
| `QUERY_ALL_PACKAGES` | List installed apps for the app drawer. Required for a launcher. |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read now-playing media info. Optional, off by default. |

## Open source

This app is open source under the MIT licence. You can review exactly what it
does at https://github.com/metehankaygsz/dashline.

## Changes

Any changes to this policy will be published on this page with an updated date.

## Contact

Questions about this policy: open an issue at
https://github.com/metehankaygsz/dashline/issues
