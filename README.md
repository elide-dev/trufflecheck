# `trufflecheck`

Static analysis checks and strictness profiles for Truffle implementations on GraalVM, shared across Elide's
repositories (`runtime`, `bali`, `komodo`, `myna`, `toolchain`, `truffledata`, `trufflecel`).

The rules started in [elide-dev/runtime#3006](https://github.com/elide-dev/runtime/pull/3006). This repository
generalizes them so any project can enable them with its own policy.

## Layout

| Path | What |
|---|---|
| `policy/` | `trufflecheck-policy.jar`: rule policy (owners, scopes, messages, class-init lists), shared by both rule JARs |
| `errorprone/` | `trufflecheck-errorprone.jar`: Error Prone BugCheckers, with the policy packaged in |
| `detekt/` | `trufflecheck-detekt.jar`: detekt rule set `Truffle`, with the policy packaged in |
| `profiles/jvm/` | `errorprone.args` (Error Prone + NullAway flags), `detekt.yaml`, `cpd.args` (PMD CPD) |
| `profiles/rust/` | baseline `deny.toml`, `clippy.toml`, `rustfmt.toml`, and `lints.toml` (`[workspace.lints]` to paste) |
| `profiles/typescript/` | `biome.json` (for `extends`) and `tsconfig.strict.json` (for `extends`) |
| `tests/` | fixtures and `smoke.sh`, which runs real javac + Error Prone and the detekt CLI over them |

## Build and test

```sh
mise install      # pinned Elide
mise run build    # elide build: all three JARs under <member>/.dev/artifacts/jar/
mise run test     # tests/smoke.sh: every `// expect: Rule` marker must fire, and nothing else
```

`smoke.sh` needs JDK 21+ and curl. It downloads pinned analyzer JARs (the same pins `runtime` uses) into
`$TRUFFLECHECK_CACHE` (default `~/.cache/trufflecheck`).

## Rules

| Rule | Error Prone | detekt | Catches |
|---|:-:|:-:|---|
| `TruffleNodeChildren` | ✓ | | Node fields/arrays (or nodes captured by anonymous nodes) without `@Child`/`@Children` |
| `TruffleBlockingInNode` | ✓ | ✓ | locks, `synchronized`, thread-locals, blocking calls in a `Node` outside `@TruffleBoundary` |
| `TruffleCompilationFinalDimensions` | ✓ | | `@CompilationFinal` array fields without `dimensions` |
| `TruffleHostInterop` | ✓ | ✓ | `org.graalvm.polyglot` imports in guest-implementation packages (`hostinterop.scope`) |
| `TruffleHostValueInImageHeap` | ✓ | ✓ | build-host values (env, clock, locale, …) read by build-time static initializers |
| `TruffleStackfulException` | ✓ | ✓ | stack-capturing exception constructors |
| `TruffleForbiddenCollection` | ✓ | ✓ | `LinkedList`, `Vector`, `Hashtable`, `Stack` |
| `TruffleAnalysisError` | | ✓ | Kotlin sources detekt couldn't fully resolve (so other findings may be missing) |

In runtime#3006 these were named `Elide*`. Each Error Prone check keeps its old name as an `altName`, so existing
`@SuppressWarnings("ElideNodeChildren")` still works.

## Policy

Defaults ship in `META-INF/trufflecheck/policy.properties`. A project points at its own overlay file, where
`key=value` replaces a default and `key+=value` appends to a default list:

```properties
# trufflecheck.properties
hostinterop.scope=dev.elide.komodo
classinit.buildtime=tools/classinit/buildtime.txt   # relative to this file
classinit.runtime=tools/classinit/runtime.txt
collections+=java.util.concurrent.CopyOnWriteArrayList
```

`TruffleHostInterop` stays inactive until `hostinterop.scope` is set, and `TruffleHostValueInImageHeap` until a
build-time class-init list is.

Tools find the overlay in this order: the checker's own option, the `trufflecheck.policy` system property, then the
`TRUFFLECHECK_POLICY` environment variable.

## Using the JARs

**Error Prone (javac)**: put the JAR on the processor path next to Error Prone, then enable the rules:

```
-Xplugin:ErrorProne -Xep:TruffleNodeChildren:ERROR … -XepOpt:Trufflecheck:Policy=trufflecheck.properties
```

`profiles/jvm/errorprone.args` is the full runtime profile, one flag per line. Under Bazel, add the JAR as a
`java_plugin` dependency alongside `//bazel:nullaway` and append the flags to `javacopts`.

**detekt**: pass the JAR and the shared profile:

```sh
detekt --plugins trufflecheck-detekt.jar --config profiles/jvm/detekt.yaml --analysis-mode full …
```

Set the overlay as a `policy:` key under `Truffle:` (or on one rule), or as `-Dtrufflecheck.policy=…`. The rule JAR
targets detekt 2.0.0-alpha.6.

## Rust and TypeScript profiles

cargo-deny and Cargo lints can't include other files. Copy `profiles/rust/*` to the workspace root, add
repository-specific entries (git forks, advisory ignores, `skip`s) with justifications, and diff the shared sections
against this repository in CI. You can run the baseline as-is with `cargo deny --config <path>/deny.toml check`.

Biome and TypeScript support `extends`, from a vendored copy or a checkout (e.g. a submodule):

```jsonc
// biome.json
{ "extends": ["./third_party/trufflecheck/profiles/typescript/biome.json"] }
// tsconfig.json
{ "extends": "./third_party/trufflecheck/profiles/typescript/tsconfig.strict.json" }
```
