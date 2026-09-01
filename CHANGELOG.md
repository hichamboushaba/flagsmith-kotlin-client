# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - Unreleased

### Fixed

- **Cold-start flags are now available synchronously.** `flagUpdateFlow` is primed from an on-disk
  snapshot of the most recently fetched flags on first access, before any network call, so reading
  it at startup returns the last known flags instead of an empty list — even offline.
- **In-memory TTL gate.** Within `cacheTTL` of the last successful fetch, `getFeatureFlags()`,
  `hasFeatureFlag()` and `getValueForFeature()` are answered from memory and issue no HTTP request
  at all (previously every call entered Ktor and re-read the HTTP cache from disk). The clock
  survives process death: an app restarted repeatedly within the TTL makes zero flag requests.
- On a failed fetch, `acceptStaleCache = true` now returns `Result.success` with the last-known
  flags instead of degrading to `defaultFlags` (an empty environment is served as an empty list);
  with `acceptStaleCache = false` the `defaultFlags` fallback applies as before.
- `Flagsmith.close()` now stops the analytics periodic flush (`FlagsmithAnalytics.stop()`); it
  previously kept a `while(true)` coroutine running.

### Changed — BREAKING

- **`identity` is the second parameter of the `Flagsmith` factories**, before `baseUrl`. Positional
  calls therefore change meaning silently rather than failing to compile: `Flagsmith("key", "https://self-hosted/api/v1/")`
  now reads that URL as an identity and falls back to the default endpoint. Use named arguments.

- **`identity` moves to the constructor.** Every instance reads exactly one document, always within
  its environment (`environmentKey` is part of every scope): with `identity` set, the environment's
  flags evaluated for that identity; with `identity = null`, the environment's own default flags. An
  instance never mixes the two — switching identity (or also reading the environment defaults) means
  constructing a new instance; call `close()` on the old one. Instances sharing a
  `cacheDirectoryPath` write distinct snapshot files, though only the 4 most recently written are
  kept (see README); give a pre-login instance `enableAnalytics = false`.
- **All methods lose their `identity` parameter**: `getFeatureFlags()`, `getTrait()`, `getTraits()`,
  `setTrait()`, `setTraits()`, `getIdentity()`, `hasFeatureFlag()`, `getValueForFeature()`, and the
  callback extensions.
- `getTrait()`, `getTraits()`, `setTrait()`, `setTraits()`, `getIdentity()` throw
  `IllegalStateException` on an instance created without an `identity`.
- `flagUpdateFlow` is now a read-only `StateFlow<List<Flag>>` (was `MutableStateFlow`), seeded with
  `defaultFlags` when no snapshot exists.
- `FlagsmithCacheConfig.cacheTTLSeconds: Long` → `cacheTTL: Duration` (`kotlin.time`).
- `FlagsmithCacheConfig.cacheSize` (HTTP cache total budget) → `maxSnapshotSizeBytes` (maximum size
  in bytes of a single cached flags snapshot; larger snapshots are skipped; default 1 MB).

### Removed — BREAKING

- **The Ktor HTTP cache** (`KtorFileCacheStorage`, `ForceCacheResponse`, the `HttpCache` plugin and
  `Cache-Control` request logic). The flags cache and its TTL gate replace it; `forceRefresh` now
  means exactly "bypass the TTL gate". 0.1.x installations' `flagsmith/` cache directory is
  reclaimed automatically on the first write.
- **Caching for `getTrait()`, `getTraits()` and `getIdentity()`.** These reads hit the same
  `identities/` URL as `getFeatureFlags()`, so they were previously served from the HTTP cache
  within the TTL; they are now unconditional network calls (they are cold-path reads, and caching
  them would require persisting the full `IdentityFlagsAndTraits` document). Call them sparingly.
  `setTrait()`/`setTraits()` are unaffected — they always were POSTs and were never cached.

## [0.1.3] and earlier

See the [GitHub releases page](https://github.com/hichamboushaba/flagsmith-kotlin-client/releases).
