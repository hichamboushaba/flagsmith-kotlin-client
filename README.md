<img width="100%" src="https://github.com/Flagsmith/flagsmith/raw/main/static-files/hero.png"/>

# Flagsmith Kotlin Client
This is a fork of the original flagsmith Android client, updated to work with Kotlin multiplatform. It supports Android, iOS, and JVM platforms.

## Instance-scoped identity (breaking change in 0.2.0)

The identity is now a property of the `Flagsmith` instance instead of a per-call argument. An instance
is either identity-scoped or environment-scoped — never both:

```kotlin
val flagsmith = Flagsmith(
    environmentKey = "...",
    identity = "device-or-user-id",   // omit for environment-level flags only
    cacheConfig = FlagsmithCacheConfig(
        enableCache = true,
        cacheDirectoryPath = context.cacheDir.absolutePath,
        cacheTTLSeconds = 3600,
        acceptStaleCache = true,
    )
)

flagsmith.getFeatureFlags { result -> /* ... */ }
```

- All methods lost their `identity` parameter: `getFeatureFlags()`, `getTrait(id)`, `getTraits()`,
  `setTrait(trait)`, `setTraits(traits)`, `getIdentity()`, `hasFeatureFlag(id)`, `getValueForFeature(id)`.
- Identity-scoped methods (`getTrait(s)`, `setTrait(s)`, `getIdentity`) throw
  `IllegalStateException` on an instance created without an `identity`.
- Changing identity means creating a new instance; call `close()` on the old one.

## Last-known flags (offline cold start)

When `enableCache = true`, the library persists the flags most recently emitted to
`flagUpdateFlow` (a `StateFlow<List<Flag>>`) in a small snapshot file next to the HTTP cache.
The flow is primed from that snapshot on first access — synchronously, before any network call —
so reading `flagUpdateFlow.value` at startup returns the last known flags instead of an empty list,
even offline.

- The snapshot is written on every successful flags fetch or trait update; `transient = true`
  responses are never persisted, and `defaultFlags` never reach the flow or the snapshot.
- `clearCache()` deletes both the HTTP cache and the snapshot; flags requested before the call are
  discarded. The flow's current value is left untouched.
- Staleness follows `cacheTTLSeconds` unless `acceptStaleCache = true`, in which case the snapshot
  is served regardless of age.

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
