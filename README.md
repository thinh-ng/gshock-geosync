# G-Shock GeoSync

Android app that keeps a Casio G-Shock GBX-100's sunrise/sunset location and tide graph port in sync with
the phone's location, in the background. Travel to another city and the watch follows, with no trip into
the watch or app settings.

It runs next to Casio's G-SHOCK MOVE app and shares its Bluetooth connection, so the watch stays paired with
the official app.

## How it decides

- **Sunrise/sunset:** the watch computes them from a latitude/longitude. The app writes your location when
  sunrise or sunset would change by 2 minutes or more (about 55 km east-west at 21°N). Short trips change
  nothing.
- **Station:** the watch always holds a tide station uploaded by this app instead of its built-in USER-1
  point. The station is named after the largest city nearby, weighted by distance, from a bundled offline
  list (GeoNames, cities of 15,000+ people), in upper-case ASCII: Gia Lam becomes `HANOI`, Đà Lạt becomes
  `DA LAT`. The watch holds 18 characters; longer names are cut. Regenerate the list with `tools/cities.py`.
- **Sunrise/sunset from the station:** the station carries your location, not the tide station's, so
  sunrise/sunset stays exact.
- **Tide:** the station carries the tide of the nearest station within 100 km in Casio's table. Farther
  inland, the tide is flat.
- **Station ids:** a station's id is derived from its contents (name, position, tide, time zone), above
  Casio's id range, so any change shows up as a new id and is uploaded again. An upload takes about 10 s.
- **Repair:** every sync reads the watch first and writes only what differs. A sync also runs 30 s after the
  watch reconnects, because G-SHOCK MOVE rewrites the location and tide on reconnect (as "no position" when it lacks
  background location permission).

Location comes from Android's fused provider at balanced power (Wi-Fi and cell, no GPS chip): at most every
15 minutes and only after a 20 km move. Failed syncs retry every 15 minutes.

## Requirements

- Android 13 or later.
- A Casio GBX-100 paired with the **G-SHOCK MOVE** app, which must keep the watch connected. This app syncs
  over that connection; while the watch is not connected, syncs wait for the next reconnect (retried every
  15 minutes). Connecting on its own failed on the test phone: Android routes the connection through the
  watch's bonded record, whose address type differs from the one that connects.
- The tide station table is read from G-SHOCK MOVE's installed APK, found by file name, and a private copy
  is kept on the phone, so a later Casio update or uninstall does not remove tides. This repo does not
  include Casio's data.
- Tested on a GBX-100 only. Other models that use G-SHOCK MOVE may differ.

## Install

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The app has no launcher entry and no screen. Start its setup once after the first install, and allow location
**all the time** and nearby devices:

```
adb shell am start -n com.thinh.gshockgeo/.MainActivity
```

For reliable background syncs, set its battery usage to Unrestricted (Settings > Apps > G-Shock GeoSync).

Android 10 and later still show a placeholder icon, which opens the app's settings, for any app that asks for
permissions. To hide it, on phones that honour the setting:

```
adb shell settings put global show_hidden_icon_apps_enabled 0
```

## Limits

- Only Casio's 60-component station table is used (Asia, Americas, Japan, Oceania). European stations use a
  different record format that is not implemented.
- The time zone sent with a tide station has no daylight-saving rule. Untested in countries with DST.

## Protocol notes

The watch protocol was worked out from the official app and confirmed on a GBX-100. All frames go to the
Casio service `26eb000d-b012-49a8-b1f8-394fb2032b0f`; see `WatchProtocol.kt`, `WatchSync.kt` and
`TideTransfer.kt`.

- Location (`0x24`): latitude and longitude as big-endian doubles. The watch reads them back little-endian,
  and drops a lone `0x24` write: it applies only inside the official sequence `1D`, `1E`, `24`, `1F`, time.
- Tide settings (`0x2E 02`): `04` selects an uploaded station, followed by the station id at bytes 5-6. `01`
  is the built-in USER-1 point.
- Tide station upload: a 1009-byte record sent through the Convoy characteristic (`26eb0024`) inside a
  data-request transaction (`26eb0023`, category `0x23`).

## Debug hooks

Debug builds include an adb-only receiver (requires the `DUMP` permission that `adb shell` holds):

```
adb shell am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd sync
adb shell am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd raw --es hex "'2e 02'"
adb shell am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd tide --ei id 2504
adb shell am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd city --ed lat 13.75 --ed lon 100.50
adb shell am broadcast -n com.thinh.gshockgeo/.DebugReceiver --es cmd custom --es name TEST --ed lat 21.0 --ed lon 105.9 --ei port 2503
```

To test travel without travelling, fake the phone's location:

```
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location providers add-test-provider fused
adb shell cmd location providers set-test-provider-enabled fused true
adb shell cmd location providers set-test-provider-location fused --location 16.0544,108.2022
adb shell cmd location providers remove-test-provider fused
```

City names: [GeoNames](https://www.geonames.org/), CC BY 4.0.

Not affiliated with Casio. G-SHOCK and G-SHOCK MOVE are trademarks of Casio Computer Co., Ltd.
