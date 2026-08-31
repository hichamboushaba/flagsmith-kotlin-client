<img width="100%" src="https://github.com/Flagsmith/flagsmith/raw/main/static-files/hero.png"/>

# Flagsmith Kotlin Client
This is a fork of the original flagsmith Android client, updated to work with Kotlin multiplatform. It supports Android, iOS, and JVM platforms.

## Usage

The identity is a property of the `Flagsmith` instance. Every instance reads exactly one document,
always within its environment (`environmentKey` is part of every scope): with `identity` set, that
document is the environment's flags **evaluated for that identity**; with `identity = null`, it is
the environment's own default flags. An instance never mixes the two — switching identity, or also
reading the environment defaults, means constructing a new instance (two instances sharing a
`cacheDirectoryPath` don't conflict; give the pre-login one `enableAnalytics = false`). The
identity-scoped methods (`getTrait(s)`, `setTrait(s)`, `getIdentity`) throw
`IllegalStateException` on an instance created without an `identity`.

```kotlin
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

flagsmith.getFeatureFlags { result -> /* ... */ }
```

## Flags cache (offline cold start + TTL gate)

With `enableCache = true`, the library caches the most recently fetched flags in memory **and** in a
small snapshot file next to the cache directory, gated by `cacheTTL` (a `kotlin.time.Duration`):

- **Within the TTL, `getFeatureFlags()` is answered from memory and issues no HTTP request at all.**
  This survives process death: the snapshot seeds both `flagUpdateFlow` and the TTL clock, so an app
  restarted repeatedly within the TTL makes zero flag requests.
- `forceRefresh = true` bypasses the gate. So do `traits != null` (a POST) and `transient = true`.
- On a failed fetch, `acceptStaleCache = true` makes the call return `Result.success` with the
  last-known flags instead of degrading to `defaultFlags` — an empty environment is served as an
  empty list, not as defaults. With `acceptStaleCache = false` (default), the failure falls back to
  `defaultFlags` as before.
- `clearCache()` resets the flow to `defaultFlags`, clears the TTL clock and deletes the snapshot.
- `getTrait()`, `getTraits()` and `getIdentity()` are never cached and always hit the network —
  they are cold-path reads; call them sparingly.
- `maxSnapshotSizeBytes` caps the size of a single cached snapshot; larger snapshots are skipped.
- The snapshot is written on every successful flags fetch or trait update; `transient = true`
  responses are never persisted, and `defaultFlags` never reach the flow or the snapshot.

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
