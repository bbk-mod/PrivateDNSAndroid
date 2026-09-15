# Changelog

What this fork changes on top of [upstream](https://github.com/karasevm/PrivateDNSAndroid), listed
on top of upstream **1.11.0**. Everything not listed here came from upstream, so when a merge from
upstream conflicts with an entry below, that entry is deliberate — re-apply it rather than taking
upstream's side.

`versionCode` / `versionName` (19 / `1.11.0`) are inherited from upstream and are not bumped per
change.

## Added

- **Auto-revert** — optionally restores Private DNS after a delay; off by default, enabled under
  **Options → Restore Private DNS automatically** (see the [README](README.md#auto-revert)).
  - `util/AutoRevertManager.kt` (scheduling, countdown notification, restore) and
    `service/AutoRevertReceiver.kt` (alarm and notification actions), with the restore targets
    `PrivateDNSUtils.AUTO_REVERT_TARGET_*`: previous setting, off, automatic, first server in the
    list.
  - The switch, the delay (10 s, 30 s, 1 min, 5 min default, 15 min, 30 min, 1 h) and the target
    in `OptionsDialogFragment`.
  - An ongoing notification with a live countdown and **Keep** / **Restore now** actions, opening
    the app when tapped. A manual change to the state the restore was about to apply cancels it.
  - Two triggers: an in-process timer, plus an `AlarmManager` alarm as a backstop if the app
    process is killed.
  - `POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM`, requested when the feature is switched on.
  - Auto-revert settings in the JSON backup export/import. The new fields are optional, so backups
    written before this change still import.

## Changed

- `applicationId` is now `ru.karasevm.privatednstoggle.bbk_mod` (debug builds append `.dev`), so
  the fork installs alongside upstream. The shortcut action and the adb grant command in the
  README use the same ID.
- Release builds are signed with a repo-local keystore when `.signing/keystore.properties` is
  present — that file is gitignored and optional — and fall back to an unsigned APK otherwise.

## Removed

- `com.google.guava:guava`. Its only use, server hostname validation, is now a local validator in
  `AddServerDialogFragment` with the same syntactic rules.
