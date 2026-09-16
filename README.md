# SamSU

A KernelSU tracefs injector with Root My Galaxy Payloads (RMG) support, forked from [M3Q Root for Galaxy S26 Ultra](https://github.com/monovibe/s26u-m3q-temp-root) (monovibe) and retargeted to the Galaxy S25 (`SM-S931B`, kernel `6.6.127-android15-8`).

> **Exact-target kernel exploit.** Each payload only matches one firmware build. A failed kernel attempt can panic or reboot the device. One root run is allowed per boot; a reboot clears root and the attempt counter.

## Upstream M3Q Root changes

M3Q Root was a single-device launcher for the Korean Galaxy S26 Ultra (`SM-S948N`, kernel 6.12.30). SamSU keeps the fail-closed security architecture and changes everything around it:

- **Retargeted payload**: the CVE-2026-43499 route was re-derived for the Galaxy S25 (`pa1q-S931BXXUCZZI4`, kernel `6.6.127-android15-8-p33f4ffe`): new text offsets, self-validating derivations (boot-id `.data` slot, `nfnetlink_log` name check), and a bounded stack-writer retry budget.
- **RMG payload compatibility**: the app matches this device against the [Root My Galaxy Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) `targets-v3.json` registry (model + kernel version), downloads the matching exploit binary **and its own KernelSU daemon (ksud)**, and falls back to the bundled payload when nothing matches or the device is offline.
- **KernelSU 3.2.5 gate**: the installed KernelSU Manager version is checked against the bundled `ksud` (3.2.5) and maintenance actions are locked on mismatch.
  
## Payload system and RMG compatibility

SamSU uses the Root My Galaxy Payloads registry format (`support/targets-v3.json`):

1. On startup, refresh, and before a root run, the app fetches the registry once per session.
2. `Build.MODEL` must appear in a profile's `models[]` **and** the running kernel must start with one of its `kernelVersions[]`.
3. On a match, the profile's exploit binary is downloaded once (size-verified against the registry), staged into `/data/local/tmp` for the shell-uid helper, and used for every run.
4. With no match — or offline — the bundled `pa1q-S931BXXUCZZI4` payload is used, so the app stays fully offline-capable.

### Manual payload selection

Tap the **Payload** button in the status card to override the automatic match:

- The selector lists the bundled payload plus registry profiles whose `models[]` contain your device (kernel-matching profiles are sorted first).
- Profiles whose kernel differs from yours are labeled **(kernel mismatch)** but can still be selected — useful when a payload is known to work across firmware builds that share a kernel.
- The button shows **Downloading** while a payload or its ksud is being fetched; downloads are size-validated against the registry and re-fetched automatically when the upstream binary changes.
- Hold a downloaded payload row for 1.5 seconds to remove it from the cache.
- The choice is remembered across sessions and can be reset back to the bundled payload at any time.

The tracefs versus physical-P0 slide route is selected at runtime per run (Shizuku tracefs fast path first, physical fallback), so one binary per device profile covers both. The active payload id is shown on the payload button in the status card.

## Supported devices

| Source | Devices |
| --- | --- |
| Bundled payloads | Galaxy S25 `SM-S931B`, Galaxy S25+ `SM-S936B` and Galaxy S25 Ultra `SM-S938B` on One UI 9 beta 2 (`S931BXXUCZZI4` / `S936BXXUCZZI4` / `S938BXXUCZZI4`), kernel `6.6.127-android15-8-p33f4ffe` — auto-selected per device |
| Downloaded (RMG registry) | Whatever [Root My Galaxy Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) currently publishes, matched by model + kernel version — each registry payload ships with its own matching KernelSU daemon, so other devices get a complete root flow |

KernelSU Manager **3.2.5** must be installed (newer managers are flagged in the status card).

## Install and use

1. Install the APK and KernelSU Manager **3.2.5** (`me.weishu.kernelsu`).
2. Start Shizuku through wireless ADB and approve this app once — the tracefs fast path makes runs far more reliable.
3. Reboot once before the first run, then wait until kernel uptime reaches 180 seconds.
4. Hold root button. Do not retry an uncertain kernel run in the same boot.
5. If modules or LSPosed are inactive, hold **Hold to reload KernelSU**, then **Hold to soft reboot**.
<p align="center" width="50%">
<video src="https://github.com/user-attachments/assets/9b75cee0-a08c-4b02-be1b-b49bdb224141" width="20%" controls></video>
</p>

## Root process

The app uses a fail-closed, per-boot flow:

1. model, fingerprint, and kernel gate (bundled target) or RMG registry match (downloaded payload);
2. 180-second boot-settle gate and one fresh kernel-write claim per boot;
3. Shizuku tracefs KASLR route when authorized, otherwise the physical-P0 oracle;
4. bounded carrier validation and kernel R/W setup;
5. UID-restricted bootstrap helper;
6. hash-verified KernelSU late-load handoff.

See [Root process](docs/ROOT_PROCESS.md) and [Technical reference](docs/REFERENCE.md) for the implementation boundaries.

## Build

Requirements: JDK 17 (JDK 21 or newer for release builds, whose lint task needs it),
Android SDK 37, and an Android NDK.

```sh
# native: the helper and the KASLR oracle, from exploit/
sh android/build-native.sh

# native: the three per-model payloads, compiled from the Root My Galaxy Payloads
# tree at the commit pinned inside the script
sh android/build-payloads.sh

cd android
./gradlew --no-daemon :app:assembleRelease \
  -PnativeFromSource=true -PpayloadDir=build/payloads
```

APK output: `android/app/build/outputs/apk/release/app-release.apk`.

Without those two properties the build falls back to the binaries committed in
`android/prebuilt/`, so a plain assemble needs no NDK and no network. `ksud` is
always taken from there: it is a KernelSU late-load daemon published as a
versioned artifact and pinned by SHA-256 in the engine, so replacing it is a
change to the root flow rather than a build detail.

[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) runs the
from-source path on every tagged commit and publishes both APKs as a GitHub
release; a manual run publishes too when `publish` is checked. It installs NDK
30.0.16138531 for the payloads because that is what upstream compiled them with,
so the binaries it ships can be compared against the ones upstream validated.

## Repository layout

```text
android/                         Android app and build scripts
exploit/src/                     shared native components (M3Q base)
exploit/src/targets/m3q-.../     exact AZG3 target (upstream, unused by SamSU payloads)
exploit/vendor/root-my-galaxy/   vendored Root My Galaxy source and provenance
docs/                            runtime and technical documentation
```

The SamSU payload itself is built from the Root My Galaxy Payloads tree (targets/pa1q-S931BXXUCZZI4) via build scripts in that checkout, and the ZZI4-exact KernelSU module/ksud pair (no-LTO build for the `p33f4ffe` kernel) lives under kernelsu/ in the same tree.

Generated APKs, JNI outputs, device logs, screenshots, local paths, and research scratch files are intentionally excluded from Git history.

## Attribution and license

SamSU is a fork of [M3Q Root](https://github.com/monovibe/s26u-m3q-temp-root) by monovibe, which derives from [Root My Galaxy Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads) by BuSung-dev. The Galaxy S25 target was ported and hardware-validated by mitschud. Shizuku integration uses [Shizuku API](https://github.com/RikkaApps/Shizuku-API).

See [LICENSE](LICENSE) and [NOTICE](android/NOTICE).
