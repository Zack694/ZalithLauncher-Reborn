# ModInj — companion app for ZalithLauncher-Reborn

> **TEMPORARY SOURCE HOSTING.** ModInj's source lives OUTSIDE this repo and is
> included here **only** so GitHub Actions can build a release APK signed with
> the same MOVTERY keystore as the launcher (the ModSync bridge is guarded by a
> signature-level permission, so launcher + ModInj must share one signing key).
> **After the release APK is confirmed working, this folder AND the
> `android-modinj.yml` workflow will be deleted.** No ModInj code is meant to
> be maintained inside this repository.

ModInj lets you pick files (mods, configs — across multiple directories) per
Minecraft instance and:

- **Instantly injects** them the moment "Launch Game" is pressed — before the
  launcher scans mods (ordered `ACTION_GAME_STARTING` broadcast → ack → JVM).
- **Backs up mid-game changes** to selected files while you play
  (`ACTION_FILE_CHANGED`), and can restore a backup back into the vault.
- **Wipes exactly the injected files** when the game exits or crashes — the
  launcher-side session manifest defines what gets deleted, so a hard native
  crash still cleans up (`GameLivenessService` binding death in the `:game`
  process).

## Build

```bash
./gradlew -p ModInj assembleRelease
```

## Signing

- **CI ("Android CI ModInj" workflow):** decodes `RELEASE_KEYSTORE`
  (movtery-key.jks) and signs with `MOVTERY_KEYSTORE_PASSWORD` +
  `RELEASE_ALIAS` via the same standard AGP injected-signing properties the
  launcher's release builds use.
- **Local / PR builds:** fall back to `ZalithLauncher/debug.keystore`, so PR
  builds still produce a signed (debug-cert) APK. Install only ONE variant —
  Android refuses to update an app with a different signature (uninstall
  first when switching debug ↔ release pairing).

## Requirements

- A ZalithLauncher build containing the ModSync bridge (merged PR #49).
- Launcher and ModInj must be signed with the same keystore.
