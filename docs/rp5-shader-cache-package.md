# RP5 TOTK shader-cache export/import package

## Scope

This feature is for local, on-device export and import of Eden's per-title Vulkan shader/pipeline cache. It does not upload data to a server and must never include game save data.

The cache directory is the per-title directory below Eden's shader cache root:

`<Eden user directory>/cache/shader/<16-digit-lowercase-title-id>/`

The Vulkan backend currently uses:

- `vulkan.bin`: Eden's serialized shader/pipeline cache.
- `vulkan_pipelines.bin`: Vulkan driver's opaque pipeline cache, with an Eden wrapper header.

The driver cache is driver/device-sensitive. The first implementation exports and imports `vulkan.bin` only. The manifest records that the driver cache is excluded and captures installed custom-driver metadata when Eden can read it; a system-default driver may remain unknown. Do not silently move `vulkan_pipelines.bin` between devices or driver versions. It can be added later only with reliable driver identity matching and explicit compatibility rules.

## Package format

A package is a ZIP container with the extension `.eden-cache`:

- `manifest.txt`: UTF-8, human-readable metadata.
- `vulkan.bin`: the per-title Eden Vulkan cache.

Manifest format is line-oriented `key=value`; repeated `addon.N=` entries describe detected add-ons. Values must be escaped so newlines cannot inject extra manifest fields.

Required metadata:

- package format version
- export timestamp in UTC (ISO 8601)
- Eden build/version string
- game title and title ID
- game version/update version when known
- Android device model, hardware identifier, API level and primary ABI when available
- Installed custom Vulkan driver name, version, vendor, author and library name when its metadata is available
- detected add-ons, including type, name, version and enabled state
- Eden cache format version
- Whether the custom-driver metadata was read successfully and whether the driver-specific cache was excluded
- cache filename, byte size and SHA-256 checksum

Unknown or unavailable values must be recorded as `unknown`, not guessed. Add-ons are reported from Eden's detected patch list; do not label arbitrary files as active mods.

## Import validation

Before changing the active cache:

1. Reject malformed archives, duplicate/unexpected entries, path traversal, absolute paths and oversized entries.
2. Require exactly one manifest and one `vulkan.bin`.
3. Verify the package format, cache format, declared size and SHA-256.
4. Require an exact title-ID match. A mismatch is a hard failure.
5. Treat Eden build, game version and add-on differences as visible warnings, not silent assumptions.
6. Stage the cache in a temporary directory on the same filesystem.
7. Back up the existing per-title cache before replacement.
8. Atomically replace the cache file where supported; retain the backup if replacement fails.

Never import while emulation is running or paused. Export must also be blocked while emulation is active so the cache cannot be copied while Eden is serializing it. Never access or modify the game's save directory.

## Android workflow

Use Android's Storage Access Framework:

- Export: user chooses a destination with Create Document; write the archive to the returned URI.
- Import: user chooses a `.eden-cache` file with Open Document; show the manifest and compatibility warnings before confirmation.

The feature must work entirely on the Retroid Pocket 5. No PC, ADB, root, network account or cloud storage is required.

## Test plan

- Export a cache after TOTK has been played and fully exited.
- Confirm the archive contains only `manifest.txt` and `vulkan.bin`.
- Inspect the manifest for UTC date, Eden build, TOTK title ID/version, device identity, detected add-ons, cache format, byte count and SHA-256.
- Import the same package and verify the cache is accepted.
- Attempt a package for another title ID and verify it is rejected without changing the existing cache.
- Corrupt the cache payload or checksum and verify rejection without changing the existing cache.
- Test with a missing cache and with an existing cache to verify safe backup and rollback.
- Confirm export/import are refused while a game is running or paused.
- Confirm game saves are unchanged.
