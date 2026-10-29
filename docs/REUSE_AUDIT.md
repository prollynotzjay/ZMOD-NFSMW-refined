# Reuse audit — 2026-10-14

The real files, reports, generated code and logs of Underground 1 and 2 were inspected. Those projects were not modified.

| Component | Evidence and decision for Most Wanted PC |
|---|---|
| pcrecomp | Use the existing patched checkout. It keeps SIMD, flags, x87, strict dispatch, callbacks and the machine lock. Do not apply another game's addresses. |
| Lifting driver | Adapted from Underground 1: it rebuilds blocks and recovers seeds that discovery missed. Independent configuration. |
| Data seeds | Underground 1 tool reused. First result: 14,881 seeds; add roots confirmed by crashes when the heuristic does not recognize a constructor. |
| Normalization | The executable also keeps relocations without a PE directory. Chain validation was generalized, without assuming SPEED2's RVAs or entry count. Only a copy in `work` is written. |
| Registers, memory and imports on Windows | Reuse `pcrecomp/runtime/native32`, `recomp32` and the callback infrastructure. The host requires MSVC x86; it is not yet the ARM64 runtime. |
| Host and crash report | Local adapter: its own name, directory, image range, entry and code limits. |
| Win32 shims and PE resources | Reuse Underground 2 code in the build, with its own log identity. Validate against Most Wanted's imports and runs. |
| D3D9 observers and profiler | Reuse Underground 2 sources from CMake. Environment variables renamed to `NFSMW_*` in local build files. |
| DirectInput | Start from the Underground 1 observer, which already removed reads of SPEED2 addresses. Action IDs must be measured in Most Wanted. |
| Widescreen and FPS | Do not reuse address patches. The initial D3D9 observer adapter does not write Underground geometry or timing. The game's own profile is still to be implemented. |
| DLLs and audio banks | `server.dll` exists in the copy; do not assume SPEED2's DLL profile or reuse Underground 1's ABK blocks. Analyze whether they run. |
| Android / DXVK | Reuse the prepared backend and the D3D9 COM bridge once Windows has enough evidence. Review shaders, resource lifetime, memory and new imports in particular. |
| Launcher and controls | Reuse components after isolating the package, data folder, profiles and input IDs. Do not produce an APK that advertises gameplay before verifying it. |

## Actual state of the references

Underground 2: the local generated code registers 27,742 functions, zero lifting failures and 2,910 pending sites (mainly 3DNow, disabled through CPUID). The Windows reports document CRT, video, menu, car and the demo race; the Android controls report documents loading Resort Loop and the user's confirmation. This is not a new test of the whole campaign.

Underground 1: the local lifting state registers 22,795 functions, zero lifting failures and 3,293 pending sites. The log `logs/run_ws.txt` records more than 4,000 successful Presents. `logs/android_build.log` ends in BUILD SUCCESSFUL. The README documents menus and the profile; races are not verified there. The adapted Android runtime, its bridges, audio bank and CMake were also inspected; some constants are specific and need a different profile.

## NFS Evolved Core

The first implementation shares generic tools and sources by build reference. Configuration, forced roots, host and diagnostics with their own addresses stay local. A separate library will be decided after Most Wanted's behavior is proven, keeping builds independent.
