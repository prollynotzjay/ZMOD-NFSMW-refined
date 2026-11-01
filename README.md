<div align="center">

<img src="extras/icon.png" width="160" alt="NFSMW Refined icon">

# NFSMW Refined

**Need for Speed: Most Wanted (2005, PC) running natively on Android**

English only · offline · Mali support · Peak launcher design

By **[prollynotzjay](https://github.com/prollynotzjay)**, based on
**[codepdbh/nfsmwpc-recompiled](https://github.com/codepdbh/nfsmwpc-recompiled)**

</div>

NFSMW Refined is a fork of codepdbh's static recompilation of the **PC version** of *Need for Speed: Most
Wanted* (2005, `speed.exe`, x86). Every function of the original executable is translated ahead of time from
x86 to C with [pcrecomp](https://github.com/sp00nznet/pcrecomp) and compiled for Android ARM64 / ARMv7.
It is **not** an emulator and **not** a port of the Xbox 360 version.

> No game files are included. You need your own copy of the PC game.

## What Refined changes

| | Original (Android Evolved) | NFSMW Refined |
|---|---|---|
| Language | Launcher in Spanish / English / Portuguese, game in any language | **English only**, launcher and game text |
| Online | EA Nation community server | **Removed**: offline, like the original game today. LAN races still work |
| GPU | Vulkan, custom Adreno drivers | **Mali profile**: detects Mali GPUs and applies tuned settings |
| Launcher | Two-column options screen | **Peak launcher design** in Most Wanted colours |
| App | NFS Most Wanted Android Evolved | **NFSMW Refined**, its own package, installs next to the original |

## Playing

1. Install `NFSMW-Refined-arm64.apk` (Android 10+, Vulkan 1.1+).
2. Copy your PC installation into `Internal storage/nfsmw` so that `nfsmw/Speed.exe` sits next to
   `GLOBAL`, `TRACKS`, `CARS`, `FRONTEND`, `LANGUAGES`, `SOUND`, `MOVIES` and `NIS`.
   `LANGUAGES/English.bin` must be there: the game always runs in English.
   Add `d3dx9_26.dll` (DirectX runtime, from your Windows `SysWOW64`) and, for LAN hosting,
   `server.dll` and `server.cfg` from the game.
3. Open **NFSMW Refined**, allow file access and press **PLAY**.

### Launcher

The menu on the left works with touch or a controller (**A** select, **B** back, **START** play):

- **PLAY**: game file checks and the PLAY button.
- **GAME FILES**: where the files go and what is missing.
- **GRAPHICS & CONTROLS**: performance presets, driving controls, internal resolution, FPS limit, proportional widescreen HUD, minimap position,
  CPU/GPU latency, compressed textures, gamepad mode and, on Mali, the Mali profile.
- **DRIVER**: your GPU, the Vulkan driver test and (Adreno only) custom Turnip driver ZIPs.
- **ABOUT**: credits, updates and bug reports.

In game, pick one of three driving schemes (launcher **Driving controls**, or settings button → Options):

| Scheme | How it works |
|---|---|
| **Touch buttons** | Steering arrows bottom-left, pedals at the right edge, nothing over the HUD |
| **Edge steering** | Hold the left or right screen edge to turn; the accelerator is automatic and braking lifts it |
| **Gyro steering** | Turn the phone like a wheel (game rotation vector, calibrated when a race starts). Output is smooth key pulses, or a PS3-style analog stick with *PS3 pad emulation* |

The controls follow the game: driving controls in a race, menu controls in the front end and over the pause menu,
a compact set on the world map, and nothing on loading screens. The game's own front-end screens are read from memory,
as in [MostWanted-Android](https://github.com/RanggaCasper/MostWanted-Android), to tell the pause menu and the map from the race HUD. Every control can be moved and resized, and each
button has its own icon. A Bluetooth/USB gamepad hides the touch controls while it is used.

### Mali

On the first launch on a Mali GPU the launcher applies settings for it: textures converted on the CPU
(Mali has no BC/DXT compression), the phone's own Vulkan driver, and 720p / 60 FPS on Valhall and newer
(G57, G68, G610, G710, G615, G720, Immortalis) or 480p / 30 FPS on older Bifrost / Midgard (G52, G72, G76,
T8xx). Every option can still be changed, and **Mali profile** applies it again.

## Building

Requirements: Visual Studio with MSVC x86, Python 3 with `capstone` and `pefile`, the patched `pcrecomp` and
`nfsu2-recompiled` checked out next to this folder, and the game in `../Need for Speed Most Wanted 2005`.
The generated C code comes from your own `speed.exe` and is never committed.

```powershell
python scripts/analyze_binary.py            # writes work/speed.analysis.exe and work/binary.json
python scripts/data_seeds.py ...            # see docs/BINARY_REPORT.md for the full sequence
python scripts/run_lift.py catalog work/speed.analysis.exe work/seeds_code.json work/catalog.json
python scripts/run_lift.py lift work/speed.analysis.exe work/catalog.json src/recomp/gen
powershell -File scripts/build_windows.ps1
powershell -File scripts/test_windows.ps1   # windowed test run
```

Before building the APK, prepare the LAN server module from your own PC copy:

```powershell
python scripts/prepare_server.py "../Need for Speed Most Wanted 2005/server.dll"
```

Then build in `android/` with `gradlew assembleRelease` (add `-PnfsAbi=armeabi-v7a` for the 32-bit APK).
The APK is written as `android/app/build/outputs/apk/release/NFSMW-Refined-release.apk`.

The launcher icon, banner and backdrop are original artwork made by `android/tools/make_art.py`.

### Layout

```
config/    reference executable, forced seeds, hooks, overrides
scripts/   binary analysis, seed discovery, lift driver, Windows build/test
src/       Windows host, runtime adapters, Most Wanted shims
docs/      binary report, reuse audit, Android bring-up and audio notes
android/   Android app (launcher, touch controls, D3D9→Vulkan bridge, guest runtime)
work/      (local only) analysis copy, catalog, logs
```

## Credits

- **codepdbh**: the original recompilation, Android runtime and port
  ([nfsmwpc-recompiled](https://github.com/codepdbh/nfsmwpc-recompiled)).
- **prollynotzjay**: NFSMW Refined: English-only, offline, Mali profile, launcher and artwork.
- **RanggaCasper** ([MostWanted-Android](https://github.com/RanggaCasper/MostWanted-Android), MIT): the touch-control layouts and the idea of switching controls by the game's own screens. **bagasromadon**: the button design (art in `assets/touch`).
- **sp00nznet**: pcrecomp. Proportional HUD adaptation follows ThirteenAG's WidescreenFix
  ([notice](THIRD_PARTY_NOTICES.md)).
- Fonts: Nunito (SIL OFL) and Rock Salt (Apache 2.0), in `android/app/src/main/assets/fonts`.

*Need for Speed* is a trademark of Electronic Arts. This project is not affiliated with EA and includes no
game files.

### Hidden options

Tap the version line in the launcher 7 times to show **ADVANCED** rows (gyro PS3 pad emulation, reset control layouts).
The launcher's *Report a problem* saves the app's own log (frame times, game states and front-end screens) in `Downloads/NFSMW Refined`.
The game writes frame times to logcat (`NFSMW`, "Frame time") every 120 frames, with the game state, to help find slow spots such as burnouts.
