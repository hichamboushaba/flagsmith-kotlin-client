# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - Unreleased

Fetch and read are now separate. `refresh()` is the only call that touches the network for flags.
`hasFeatureFlag()` and `getValueForFeature()` are synchronous reads; `observeHasFeatureFlag()` and
`observeValueForFeature()` provide reactive reads through flows. All read the flags already held
in memory from the last successful `refresh()`, the on-disk snapshot, or `defaultFlags`.
This replaces `getFeatureFlags()`, which fetched and read in one call and so tied every read
site to a fetch policy (traits, TTL, stale fallback, transient). See the migration hazards below
before upgrading — most apps need to add a `refresh()` call somewhere, and nothing enforces that.

### Changed — BREAKING, migration hazards in rough order of how likely they are to bite

1. **`hasFeatureFlag()` and `getValueForFeature()` no longer fetch.** They are now synchronous,
   return a plain `Boolean` / `Any?` (not `Result`), take no `traits` argument, and read whatever
   the last successful `refresh()` put in memory. `getValueForFeature()` also no longer hides the
   value of a flag whose `enabled` is false: `enabled` is a separate switch, read with
   `hasFeatureFlag()`. For reactive reads, `observeHasFeatureFlag(id)` / `observeValueForFeature(id)`
   emit on collection and whenever the observed enabled state or value changes, respectively.
   The return-type change breaks call sites at compile time, but the new obligation to call
   `refresh()` somewhere is enforced by nothing: an app that upgrades and adds no `refresh()` call
   gets snapshot-primed flags that never update.
2. **`setTraits(x)`/`setTrait(x)` keep compiling but no longer touch the network.** They upsert into
   this instance's in-memory trait state (by `Trait.key`; never persisted to disk) and return
   nothing; the traits are sent only when `refresh()` makes a network request. `getIdentity()`,
   `getTraits()` and `getTrait()` never send them (they always POST an empty list; see below). A
   statement-position `setTraits(x)` that used to fetch now silently sends nothing until something
   else calls `refresh()`. New: `removeTrait(key)` stops sending a previously-set trait (local
   only — the server keeps its last stored value for it).
3. **`FlagsmithCacheConfig.acceptStaleCache` default flips `false` → `true`**, and its meaning
   narrows to one thing: whether an expired on-disk snapshot may still prime the flags reads on a
   cold start. It no longer affects what `refresh()` returns — see point 4. Apps relying on the old
   default now prime reads from an expired snapshot instead of from `defaultFlags`.
4. **`refresh()` failure is a failure — `Result.failure`, always.** There is no more stale-document
   or `defaultFlags` fallback baked into the fetch call itself: the reads simply keep serving
   whatever they already had (the last successful `refresh()`, the primed snapshot, or
   `defaultFlags` if neither exists yet), which *is* the fallback now. `getFeatureFlags(traits,
   transient, forceRefresh)` is removed outright — both the suspend form and the callback overload
   fail to compile; migrate the call to `setTraits(...)` + `refresh()` + a sync read.
   `getIdentity(transient = true)` no longer compiles: transience is now
   `transientIdentity`, a constructor argument (`IllegalArgumentException` if `true` without an
   `identity`) — there is no more per-call `transient`. Java callers of the removed callback
   overloads break at compile time too.

### Changed — BREAKING, the rest

- **`refresh(force: Boolean = false): Result<Unit>`** replaces `getFeatureFlags()` as the only flags
  fetch. Within `cacheConfig.cacheTTL` of the last document produced by the *same* request — the
  current trait state, canonicalised (ordering, `identifier` and `Int`/`Double` differences ignored;
  `transientIdentity` is part of the identity) — it returns without a request, so it is cheap to call
  on whatever cadence an app needs. `force = true` bypasses the gate outright. One dominance rule: a
  `refresh()` called with no traits at all (before the app has called `setTraits`, e.g. on a cold
  start) reuses whichever document is already cached for that identity, rather than transiently
  fetching — and caching — a trait-less one.
- **`getIdentity()`, `getTraits()`, `getTrait(id)` are read-only diagnostics of the server's stored
  view**, not part of the fetch path: they always POST an empty trait list (never the current
  `setTraits` state — that would make `getTraits()` a getter that writes, and defeat its own purpose
  of confirming what the server has), and never touch the flags state, the TTL gate or the on-disk
  snapshot. `getIdentity()` no longer takes a `transient` parameter; construct a `transientIdentity`
  instance instead.
- **Always POST in identity mode**, with or without traits — an empty-trait POST returns the
  identity's stored view. This removes the GET branch of the old fetch path (and its
  transient-identity workaround for [flagsmith/flagsmith#5260](https://github.com/Flagsmith/flagsmith/issues/5260):
  the old `GET ?transient=true` ignored stored state entirely, where POST does not).
- **`TraitWithIdentity` and `entities.Identity` are removed.** Nothing in the new surface returns
  them — traits are set with `setTraits`/`setTrait` (no return value) and read back with
  `getTraits()`/`getTrait()` (plain `Trait`, no wrapper).
- Callback layer, complete list: `refresh(force, result)`, `getIdentity(result)`,
  `getTraits(result)`, `getTrait(id, result)`. Deleted: the callback forms of `getFeatureFlags`,
  `hasFeatureFlag`, `getValueForFeature`, `setTraits`, `setTrait` (the last two are synchronous now
  and need no callback).
- **`identity` moves to the constructor**, alongside a new `transientIdentity: Boolean = false`.
  Every instance reads exactly one document, always within its environment (`environmentKey` is part
  of every scope): with `identity` set, the environment's flags evaluated for that identity; with
  `identity = null`, the environment's own default flags. An instance never mixes the two —
  switching identity (or also reading the environment defaults) means constructing a new instance;
  call `close()` on the old one. Instances sharing a `cacheDirectoryPath` write distinct snapshot
  files (transient and non-transient instances for the same identity deliberately share one — the
  transient marker lives in the request key, not the file), though only the 4 most recently written
  are kept (see README); give a pre-login instance `enableAnalytics = false`.
- **`close()` is now terminal.** It releases the underlying HTTP clients as well as the real-time
  subscription and the analytics flush loop, so `refresh()`/`getIdentity()`/`getTraits()`/
  `getTrait()` throw `IllegalStateException` afterwards (the callback wrappers surface that as
  `Result.failure`); reads and `setTraits`/`setTrait`/`removeTrait` keep working. Build a new
  instance instead — which is also how you switch identity. `clearCache()` still works after
  `close()` and does not touch trait state.
- The flags the reads serve are primed from the snapshot (or `defaultFlags` if none) synchronously
  on first access — a small disk read on the calling thread; on Android's main thread it registers
  as a StrictMode `DiskReadViolation`. Nothing auto-refreshes.
- `FlagsmithCacheConfig.cacheTTLSeconds: Long` → `cacheTTL: Duration` (`kotlin.time`).
- `FlagsmithCacheConfig.cacheSize` (HTTP cache total budget) → `maxSnapshotSizeBytes` (maximum size
  in bytes of a single cached flags snapshot; larger snapshots are skipped; default 1 MB).
- The snapshot format stays version 2, but a v2 file whose request key is one of the now-deleted
  `"get"`/`"get:transient"` forms (written by earlier 0.2.0 dev builds) is treated the same as a
  foreign/corrupt one: it still primes the flags reads, but can never satisfy the TTL gate, and the
  first `refresh()` after it fetches unconditionally.

### Fixed

- **Cold-start flags are now available synchronously.** The flags reads are primed from an on-disk
  snapshot of the most recently fetched flags on first access, before any network call, so reading
  at startup returns the last known flags instead of an empty list — even offline.
- **In-memory TTL gate.** Within `cacheTTL` of the last successful `refresh()`, a repeat `refresh()`
  for the same request is answered from memory and issues no HTTP request at all (previously every
  `getFeatureFlags()` call entered Ktor and re-read the HTTP cache from disk). The clock survives
  process death: an app restarted repeatedly within the TTL makes zero flag requests.
- `Flagsmith.close()` now stops the analytics periodic flush (`FlagsmithAnalytics.stop()`); it
  previously kept a `while(true)` coroutine running.
- Cached flags documents now record the canonical identity of the request that produced them (an
  environment fetch or a POST digested from its traits). This fixes a defect where a POST-derived
  document could be served by the TTL gate to a later trait-less call, or vice versa.

### Removed — BREAKING

- **The Ktor HTTP cache** (`KtorFileCacheStorage`, `ForceCacheResponse`, the `HttpCache` plugin and
  `Cache-Control` request logic). The flags cache and its TTL gate replace it; `force` on `refresh()`
  now means exactly "bypass the TTL gate". 0.1.x installations' `flagsmith/` cache directory is
  reclaimed automatically on the first write.

## [0.1.3] and earlier

See the [GitHub releases page](https://github.com/hichamboushaba/flagsmith-kotlin-client/releases).
