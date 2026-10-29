# Executable reference — MW-M0

Analysis date: 2026-10-14. Local copy `Need for Speed Most Wanted 2005/speed.exe`, 6,029,312 bytes.

SHA-256: `80774c2e5d619b4f120b48d4462896fd504c263399d203a238769cffde1d253c`.

PE32 x86 (`Machine=0x014c`); base `0x00400000`, entry `0x007c4040`, SizeOfImage `0x00678e4e`. No version resource or TLS directory was found. The version/patch and region are not inferred from the folder name: this reference is identified by its hash. The local data includes Spanish and English videos; that alone does not establish the executable's region.

| Section | RVA | Virtual size | Size on disk |
|---|---:|---:|---:|
| .text | 0x1000 | 0x48e2a5 | 4,780,032 |
| .rdata | 0x490000 | 0x5971d | 368,640 |
| .data | 0x4ea000 | 0xdce10 | 147,456 |
| .rsrc | 0x5c7000 | 0x7064e | 462,848 |
| Unnamed | 0x638000 | 0x40e4e | 266,240 |

The entry point decodes a normal CRT prologue with SEH registration through `fs:[0]`. The code section has an entropy of 6.514. No decompression stub is seen at the entry; these observations do not prove the absence of every kind of protection.

The unnamed section keeps a validated relocation chain: 240,976 bytes and 114,755 HIGHLOW entries. The original directory was empty. `scripts/analyze_binary.py` restores it only in `work/speed.analysis.exe`; SHA-256 of that copy: `53e21e6ad5b4243b29d776fc33b7d84b805be24bb176f9716c1888646966d8de`.

300 imports from 14 DLLs: KERNEL32 (157), d3d9 (1), d3dx9_26 (13), DINPUT8 (1), USER32 (41), ADVAPI32 (6), SHFOLDER (1), SHELL32 (1), DSOUND (2), WINMM (24), GDI32 (12), TAPI32 (9), NETAPI32 (1), WS2_32 (31). The full JSON of symbols and headers is generated in `work/binary.json`.

Graphics: `Direct3DCreate9` and D3DX matrix operations, effects from resources and an effect pool. Audio: DirectSound and WinMM. Input: DirectInput 8. The runtime must preserve callbacks, synchronization, threads and SEH. The folder also contains `server.dll`; its loading and code will be validated if execution needs them.

First lift: 41,261 entries, 138 C units, 5,556,158 lines, zero generation failures and 72 pending sites, among them `lock xadd` and `pmuludq`. A heuristic catalog can include aliases, misread data and unreached entries; these figures do not express functional coverage.

First host: it compiles and links, binds all 300 imports with none pending and runs the original entry point. It stopped during vector initialization at `0x007c90a0`, when calling the constructor `0x00505f80`, which the heuristic had not cataloged. The disassembly and the crash support its explicit root in `config/forced_seeds.json`; its execution is not skipped.
