<img width="100%" src="https://github.com/Flagsmith/flagsmith/raw/main/static-files/hero.png"/>

# Flagsmith Kotlin Client
This is a fork of the original flagsmith Android client, updated to work with Kotlin multiplatform. It supports Android, iOS, and JVM platforms.

## Usage

Fetch and read are separate. `refresh()` is the only call that touches the network for flags.
`hasFeatureFlag()` and `getValueForFeature()` are synchronous reads; `observeHasFeatureFlag()` and
`observeValueForFeature()` provide reactive reads through flows. All read the flags already held
in memory from the last successful `refresh()`, the on-disk snapshot, or `defaultFlags`.
Call `refresh()` on whatever cadence your app needs — within `cacheTTL` of the last matching fetch
it skips the network request.

The identity is a property of the `Flagsmith` instance. Every instance reads exactly one document,
always within its environment (`environmentKey` is part of every scope): with `identity` set, that
document is the environment's flags **evaluated for that identity**; with `identity = null`, it is
the environment's own default flags. An instance never mixes the two — switching identity, or also
reading the environment defaults, means constructing a new instance (two instances sharing a
`cacheDirectoryPath` don't conflict; give the pre-login one `enableAnalytics = false`). The
identity-scoped methods (`setTraits`/`setTrait`/`removeTrait`, `getIdentity`/`getTraits`/`getTrait`)
throw `IllegalStateException` on an instance created without an `identity`.

```kotlin
import androidx.lifecycle.lifecycleScope
import com.flagsmith.Flagsmith
import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.entities.Trait
import com.flagsmith.refresh
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

// Inside an Android ComponentActivity:
val scope = lifecycleScope // Cancelled automatically when the activity is destroyed.

val flagsmith = Flagsmith(
    environmentKey = "...",
    identity = "device-or-user-id",   // omit to read the environment's own default flags
    cacheConfig = FlagsmithCacheConfig(
        enableCache = true,
        cacheDirectoryPath = context.cacheDir.absolutePath,
        cacheTTL = 1.hours,
        acceptStaleCache = true,
        maxSnapshotSizeBytes = 1L * 1024 * 1024,
    )
)

// Traits are in-memory, per-instance state: upserted by Trait.key, never persisted to disk.
// Sent in full only when refresh() makes a network request. Setting them does not touch the network.
flagsmith.setTraits(listOf(Trait(key = "plan", value = "pro")))

// The only network call for flags. Cheap to call often - see "Flags cache" below.
flagsmith.refresh { result -> /* ... */ }

// Synchronous reads - never touch the network, never suspend.
flagsmith.hasFeatureFlag("my-feature")
flagsmith.getValueForFeature("my-feature")

// Reactive reads - emit on collection and whenever the observed enabled state or value changes.
scope.launch {
    flagsmith.observeHasFeatureFlag("my-feature").collect { enabled -> /* ... */ }
}
scope.launch {
    flagsmith.observeValueForFeature("my-feature").collect { value -> /* ... */ }
}
```

## Flags cache (offline cold start + TTL gate)

With `enableCache = true`, the library caches the most recently fetched flags in memory **and** in a
small snapshot file next to the cache directory, gated by `cacheTTL` (a `kotlin.time.Duration`):

- **Within the TTL, `refresh()` for the same request is answered from memory and issues no HTTP
  request at all.** This survives process death: the snapshot seeds both the in-memory flags and
  the TTL clock, so an app restarted repeatedly within the TTL makes zero flag requests. "The same
  request" means the current trait state (set via `setTraits`/`setTrait`/`removeTrait`) and
  `transientIdentity`, compared canonically — ordering, `identifier` and `Int`/`Double` differences
  don't matter, while duplicate trait keys and changed traits always refetch. One dominance rule: a
  `refresh()` called with no traits at all — typically because the app hasn't called `setTraits` yet
  on a cold start — reuses whichever document is already cached for that identity, rather than
  transiently fetching (and caching) a trait-less one.
- `force = true` bypasses the gate outright, dominance included.
- **A failed `refresh()` is `Result.failure`, always** — there is no fallback built into the call
  itself. `hasFeatureFlag()`, `getValueForFeature()` and the `observe*` flows simply keep serving
  whatever they already had: the last successful `refresh()`, the primed snapshot, or `defaultFlags`
  if neither exists yet. `acceptStaleCache` (default `true`) controls only whether an *expired*
  on-disk snapshot may still prime the flow on a cold start; with it `false`, an expired snapshot is
  discarded at prime time and the flow starts on `defaultFlags` until the first successful
  `refresh()`.
- `clearCache()` resets the flow to `defaultFlags`, clears the TTL clock and deletes the snapshot.
  It does not touch trait state.
- `getIdentity()`, `getTraits()` and `getTrait()` are read-only diagnostics of the server's stored
  view: they always POST an empty trait list (never the current `setTraits` state), are never
  gated, never cached, and always hit the network. They are cold-path reads; call them sparingly.
- `maxSnapshotSizeBytes` caps the size of a single cached snapshot; larger snapshots are skipped
  and the previous snapshot is kept.
- Snapshots are per scope (base URL + environment key + identity) — `transientIdentity` is **not**
  part of the scope, so a transient and a non-transient instance for the same identity deliberately
  share one snapshot file; the transient marker lives in the request key instead, so a non-transient
  `refresh()` never reuses a transient-produced document or vice versa. Instances sharing a
  `cacheDirectoryPath` otherwise write separate files. The directory keeps only the **4 most
  recently written** snapshots, so a device cycling through more than four scopes — several
  identities, or identities plus an environment-scoped instance — loses cold-start priming for the
  least recently used ones. They repopulate on their next successful `refresh()`.
- The first flags read performs a small synchronous file read on the calling thread.
  That is what makes the value available before any network call; on Android's main thread it will
  register as a StrictMode `DiskReadViolation`.

See [CHANGELOG.md](CHANGELOG.md) for the full 0.2.0 change list and migration notes.

---------

Below is the README of the original library

# Flagsmith Kotlin Android Client

> Flagsmith allows you to manage feature flags and remote config across multiple projects, environments and organisations.

The SDK client for Kotlin based Android applications for [https://www.flagsmith.com/](https://www.flagsmith.com/).

## Adding to your project

For full documentation visit [https://docs.flagsmith.com/clients/android](https://docs.flagsmith.com/clients/android)

## Contributing

Please read [CONTRIBUTING.md](https://gist.github.com/kyle-ssg/c36a03aebe492e45cbd3eefb21cb0486) for details on our code of conduct, and the process for submitting pull requests

## Getting Help

If you encounter a bug or feature request we would like to hear about it. Before you submit an issue please search existing issues in order to prevent duplicates.

## Get in touch

If you have any questions about our projects you can email <a href="mailto:support@flagsmith.com">support@flagsmith.com</a>.

## Useful links

[Website](https://www.flagsmith.com/)

[Documentation](https://docs.flagsmith.com/)
