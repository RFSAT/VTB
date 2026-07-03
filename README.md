# VTB — Vapor-Trail Ballistic Calculator

Android app that estimates crosswind along a bullet's entire flight path
from vapor-trail video, then computes the scope windage/elevation
adjustment needed for the next shot.

Kotlin/Android, built exclusively via GitHub Actions CI (matches the DBM/
TinyRAD workflow — no local ADB/Android Studio needed). Target: Samsung
Galaxy S24, API 34; minSdk 26.

## Defaults pre-loaded

- **Rifle:** Ruger Precision Rimfire (18" barrel, 1:16" twist, 50 yd zero — edit sight height/zero to match your actual mount)
- **Bullet:** Federal Champion Solid 40gr .22 LR — 1240 fps muzzle velocity, G1 BC 0.139 (manufacturer spec)
- **Scope:** generic 1/4-MOA placeholder — replace with your real scope's click value once you share its specs

All three are editable in-app (Profiles screen) and persist between sessions.

## How it works

1. **Capture** — record the shot on video with the phone positioned at/near
   the muzzle, aimed roughly down the line of fire (a "trace cam" position),
   so the whole vapor trail is visible snaking toward the target. Tap the
   point of aim before firing to mark the boresight reference pixel.
2. **Extract** — `TrailExtractor` diffs each frame after the shot against a
   pre-shot reference frame and tracks the brightest new blob (the trail)
   frame-by-frame.
3. **Calibrate** — `TrailCalibration` converts tracked pixels into real-world
   lateral/vertical angles using the camera's horizontal FOV, referenced to
   the marked boresight pixel. Downrange distance at each timestamp comes
   from a zero-wind baseline trajectory (`BallisticsEngine`), since a
   single 2D video can't directly measure depth.
4. **Invert wind** — `WindEstimator` fits local quadratic curves to the
   tracked lateral/vertical position vs. time, differentiates to get
   velocity and acceleration, and inverts the equations of motion
   (`a = -K·(v − wind)`) to recover an estimated crosswind (and vertical
   wind) at every point along the flight — not just an average.
5. **Adjust** — `AdjustmentCalculator` re-simulates the trajectory forward
   through that estimated wind field, compares the predicted impact point
   to the point of aim, and reports the windage/elevation correction in
   MOA or MRAD (and turret clicks) for the *next* shot.

## Camera geometry — read this before shooting

The wind-inversion math assumes the camera is at (or very close to) the
**muzzle**, boresighted along the line of fire. A crosswind pushes the
bullet sideways relative to that line — from a camera positioned like this,
that sideways drift shows up directly as horizontal pixel displacement
across the frame, growing as the trail recedes.

A camera positioned **off to the side**, looking across the line of fire,
sees gravity drop (vertical) clearly but crosswind drift mostly as motion
*toward/away from the camera* — which a single 2D video can't resolve.
Side-on footage will not give a usable crosswind estimate with this app as
built.

## Known limitations (read before trusting the numbers)

- **Drag model is approximate.** `BallisticsEngine` uses a hand-built
  Cd-vs-Mach curve (subsonic plateau → transonic rise → supersonic decay),
  not a digitized standard G1 table. It captures the qualitative shape,
  including .22LR's transonic behavior, but isn't drop-table-grade out of
  the box. Use `BulletProfile.dragCalibrationFactor` to tune it against
  your own chronograph and known-distance drop data.
- **No image stabilization.** `TrailExtractor` assumes a static, tripod-
  mounted phone. Handheld footage will corrupt the pixel track.
- **Shot-break timing is manual.** You enter the time offset (seconds into
  the clip) where the shot breaks; there's no automatic muzzle-flash/report
  detection yet.
- **Frame resolution assumption.** The capture screen approximates the
  recorded video's pixel resolution from the live preview view size rather
  than reading it back from the saved file — fine on most phones where
  MediaStore video capture preserves the preview's aspect ratio, but worth
  checking if your results look off.
- **Range-condition inputs (temperature/pressure/altitude/humidity) aren't
  wired into a UI screen yet** — `Atmosphere()` currently uses sea-level
  standard defaults in `CaptureActivity`. Enter your actual range
  conditions there for anything beyond a rough estimate.
- **Head/tail wind isn't derived from video** (a straight-on trail doesn't
  show it) — the engine supports it as a wind-profile component, but no UI
  exists yet to enter a manually-read range-wind value.
- This is a field-estimation tool for target shooting, not a certified
  ballistic solver — validate against real impacts before trusting it at
  distance.

## Version history

Convention: major version increments when a feature is added; minor
increments on corrections/fixes with no new capability. Shown as
`versionName` in `app/build.gradle.kts` and in the delivered ZIP filename
(`VTB_v<major>.<minor>.zip`).

- **v1.0** — initial feature-complete build: profiles, capture, trail
  extraction, wind inversion, scope adjustment.
- **v1.1** — renamed project to VTB / app to "Vapor-Trail Ballistic
  Calculator" (package `com.rfsat.vtb`); no functional changes.

## Build

Builds automatically on push to `main` via
`.github/workflows/android-ci.yml` (debug APK always; signed release
APK + AAB if the usual `ANDROID_KEYSTORE_BASE64` /
`ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
secrets are set, same convention as the DBM app).

## Next steps

- Wire up a range-conditions (temperature/pressure/altitude/humidity) input screen
- Swap the placeholder scope profile for your actual scope's specs once you send them over
- Optional: automatic shot-break detection from muzzle flash/report in the audio track
- Optional: multi-shot log / history (currently one shot's results live in memory only)
