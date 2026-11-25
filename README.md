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
- **Tide:** the app picks the nearest tide station within 100 km, from Casio's own station table. With a
  station selected, the watch also takes sunrise/sunset from the station's coordinates, so a station is used
  only when that moves sunrise/sunset by less than 3 minutes. Otherwise the watch goes back to its built-in
  tide mode (USER-1) and sunrise/sunset follows your real location.
- **Repair:** every sync reads the watch first and writes only what differs. A sync also runs 30 s after the
  watch reconnects, because G-SHOCK MOVE rewrites the location on reconnect (as "no position" when it lacks
  background location permission).

Location comes from Android's fused provider at balanced power (Wi-Fi and cell, no GPS chip): at most every
15 minutes and only after a 20 km move. Failed syncs retry every 15 minutes.

## Requirements

- Android 13 or later.
- A Casio GBX-100 paired with the **G-SHOCK MOVE** app. The tide station table is read from that app's
  installed APK; this repo does not include Casio's data. Without G-SHOCK MOVE, tide is left alone.
- Tested on a GBX-100 only. Other models that use G-SHOCK MOVE may differ.

## Install

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app once and allow location **all the time** and nearby devices. For reliable background syncs, set
its battery usage to Unrestricted.

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
```

To test travel without travelling, fake the phone's location:

```
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location providers add-test-provider fused
adb shell cmd location providers set-test-provider-enabled fused true
adb shell cmd location providers set-test-provider-location fused --location 16.0544,108.2022
adb shell cmd location providers remove-test-provider fused
```

Not affiliated with Casio. G-SHOCK and G-SHOCK MOVE are trademarks of Casio Computer Co., Ltd.
