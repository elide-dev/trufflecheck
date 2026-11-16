# `trufflecheck`

Static analysis rules and strictness profiles for [Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/)
languages that ship as **GraalVM Native Image** (SubstrateVM) binaries. Used across Elide's repositories.

- **Rule JARs:** Error Prone checks for Java and a detekt rule set for Kotlin, aimed at partial-evaluation and image-heap
  hazards that compile cleanly but break or slow down a native image.
- **Profiles:** shared Error Prone/NullAway, detekt, CPD, cargo-deny, clippy, rustfmt, Biome and tsconfig settings.

Licensed under [Apache-2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your option.

## Get it

| Channel | What you get |
|---|---|
| Maven, at `https://maven.elide.dev` | `dev.elide:trufflecheck-errorprone`, `dev.elide:trufflecheck-detekt`, `dev.elide:trufflecheck-policy` |
| [GitHub releases](https://github.com/elide-dev/trufflecheck/releases) | the same JARs, plus `trufflecheck-profiles-<version>.tar.gz` |
| git submodule | everything, including `profiles/` to `extends` from |

```sh
git submodule add https://github.com/elide-dev/trufflecheck third_party/trufflecheck
```

## Use the rules

### Error Prone (Java)

Put `trufflecheck-errorprone` on the annotation-processor path next to Error Prone, then enable the checks. The
full profile we use is in [`profiles/jvm/errorprone.args`](profiles/jvm/errorprone.args), one flag per line.

**Gradle** (`net.ltgt.errorprone`):

```kotlin
repositories { maven("https://maven.elide.dev") }

dependencies {
  errorprone("com.google.errorprone:error_prone_core:2.51.0")
  errorprone("dev.elide:trufflecheck-errorprone:0.1.0")
}

tasks.withType<JavaCompile>().configureEach {
  options.errorprone {
    error("TruffleNodeChildren", "TruffleCompilationFinalDimensions", "TruffleBlockingInNode",
          "TruffleHostValueInImageHeap", "TruffleHostInterop", "TruffleStackfulException")
    option("Trufflecheck:Policy", "$rootDir/trufflecheck.properties")
  }
}
```

**Maven:**

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <compilerArgs>
      <arg>-XDcompilePolicy=simple</arg>
      <arg>--should-stop=ifError=FLOW</arg>
      <arg>-Xplugin:ErrorProne -Xep:TruffleNodeChildren:ERROR -XepOpt:Trufflecheck:Policy=${maven.multiModuleProjectDirectory}/trufflecheck.properties</arg>
    </compilerArgs>
    <annotationProcessorPaths>
      <path><groupId>com.google.errorprone</groupId><artifactId>error_prone_core</artifactId><version>2.51.0</version></path>
      <path><groupId>dev.elide</groupId><artifactId>trufflecheck-errorprone</artifactId><version>0.1.0</version></path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

Add `https://maven.elide.dev` under `<repositories>`. Error Prone also needs its `--add-exports`/`--add-opens` lines
in `.mvn/jvm.config`; see the [Error Prone install docs](https://errorprone.info/docs/installation).

**Bazel** (`rules_jvm_external`):

```starlark
maven.install(
    artifacts = ["dev.elide:trufflecheck-errorprone:0.1.0"],
    repositories = ["https://maven.elide.dev", "https://repo1.maven.org/maven2"],
)
```

```starlark
java_plugin(name = "trufflecheck", deps = ["@maven//:dev_elide_trufflecheck_errorprone"])
# then list ":trufflecheck" in `plugins` and add `-Xep:Truffle…:ERROR` flags to `javacopts`
```

### detekt (Kotlin)

```sh
detekt --plugins trufflecheck-detekt-0.1.0.jar \
  --config third_party/trufflecheck/profiles/jvm/detekt.yaml \
  --analysis-mode full --classpath "$CLASSPATH" --input src/main/kotlin
```

With Gradle, use `detektPlugins("dev.elide:trufflecheck-detekt:0.1.0")`. Rules live in the `Truffle` rule set; point
them at your policy with `policy:` under `Truffle:`, or with `-Dtrufflecheck.policy=…`. Targets detekt 2.0.0-alpha.6.

### Policy: tell the rules about your project

Two rules need to know your project's layout before they do anything: which packages hold guest code, and which
classes Native Image initializes at build time. Put that in a properties file:

```properties
# trufflecheck.properties. Paths are relative to this file.
hostinterop.scope=dev.elide.komodo
classinit.buildtime=tools/classinit/buildtime.txt
classinit.runtime=tools/classinit/runtime.txt
# `key+=` appends to a default list instead of replacing it
collections+=java.util.concurrent.CopyOnWriteArrayList
```

Class-init lists hold one class or package prefix per line, as passed to `--initialize-at-build-time` /
`--initialize-at-run-time`; the most specific match wins.

The rules find the file through, in order:
1. the checker option: `-XepOpt:Trufflecheck:Policy=…` for Error Prone, `policy:` for detekt;
2. the `trufflecheck.policy` system property;
3. the `TRUFFLECHECK_POLICY` environment variable.

All defaults are in
[`policy.properties`](policy/src/main/resources/META-INF/trufflecheck/policy.properties).

## Rule catalogue

Rule names in `elide-dev/runtime` started as `Elide*`. Error Prone still accepts those names, including in
`@SuppressWarnings`.

| Rule | Java | Kotlin | Flags | Why |
|---|:-:|:-:|---|---|
| `TruffleNodeChildren` | ✓ | ✓ | Non-final node fields (or node-interface fields) without `@Child`, node arrays without `@Children`, collections or nested arrays of nodes, nodes captured by anonymous/local node classes | An unadopted node isn't part of the tree: partial evaluation can't treat it as a constant, calls through it stay virtual, and passing the frame into one aborts compilation. `final` fields are references to nodes owned elsewhere and are left alone. |
| `TruffleCompilationFinalDimensions` | ✓ | ✓ | `@CompilationFinal` on an array without `dimensions` | Omitted, `dimensions` defaults to the array's full depth, so every element is folded as a constant; elements written later without invalidation leave stale compiled code. State the depth you mean. Honors the DSL's `VerifyCompilationFinal` suppression key. |
| `TruffleBlockingInNode` | ✓ | ✓ | `synchronized`, locks, `withLock`, `ThreadLocal`, `sleep`/`join`/`park`/`await`/`Future.get` in code partial evaluation reaches | Lock methods are on Native Image's runtime-compilation blocklist (the image build fails), and blocking in compiled guest code stalls safepoints. Put it behind `@TruffleBoundary` or a `transferToInterpreter`; use `TruffleSafepoint.setBlocked` for guest-visible blocking. |
| `TruffleHostValueInImageHeap` | ✓ | ✓ | Build-host reads (env, properties, clocks, locale, CPU count, randomness, `new Random()`/`Thread`/streams) in the static initializer of a build-time-initialized class, including one hop into constructors and methods of the same file | Native Image runs that initializer on the build machine and freezes the result into the image heap: every user sees the builder's environment, time and seed. |
| `TruffleHostInterop` | ✓ | ✓ | `org.graalvm.polyglot` imports or qualified references in guest-implementation packages | The embedding API is for hosts. Guest code using it skips Truffle interop and can't be partially evaluated. Use `InteropLibrary` and `@ExportLibrary`. `SandboxPolicy` and `ByteSequence` are allowed. |
| `TruffleStackfulException` | ✓ | ✓ | JDK exceptions created with a stack trace; project exception types extending one without opting out; Kotlin `require`/`check`/`error`/`TODO` | Filling in a stack trace walks the stack, and `Throwable.fillInStackTrace` is on Native Image's runtime-compilation blocklist. Override `fillInStackTrace`, pass `writableStackTrace = false`, or extend a Truffle exception type. |
| `TruffleForbiddenCollection` | ✓ | ✓ | `LinkedList`, `Vector`, `Hashtable`, `Stack`, `StringBuffer` | Pointer chasing or a lock on every operation, and on Native Image's runtime-compilation blocklist. Use array-backed collections and `StringBuilder`. |
| `TruffleAnalysisError` | | ✓ | Kotlin sources detekt couldn't fully resolve | Unresolved code hides findings. Fix the classpath rather than trust a clean report. |

"Code partial evaluation reaches" means a method of a `Node` (static or not, including Kotlin companions), an
`@ExportMessage`, or a DSL `@Specialization`/`@Fallback`. It excludes `@TruffleBoundary` methods, methods opening with
`CompilerAsserts.neverPartOfCompilation()`, code after `transferToInterpreter*()`, branches guarded by
`inInterpreter()`, `inInterpreterFastPath()` or the `else` of `inCompiledCode()`, `shouldNotReachHere(…)` arguments,
`interpreterOnly(…)` lambdas, Java lambdas, and DSL-generated (`@GeneratedBy`) classes.

## Profiles

Under [`profiles/`](profiles):

- **`jvm/`**: `errorprone.args` (Error Prone + NullAway in JSpecify mode), `detekt.yaml` (bug-finding rules only, no
  style or naming), `cpd.args` (PMD copy-paste detection).
- **`rust/`**: `deny.toml`, `clippy.toml`, `rustfmt.toml`, plus `lints.toml`, a `[workspace.lints]` block to paste into
  your workspace `Cargo.toml`.
- **`typescript/`**: `biome.json` and `tsconfig.strict.json`.

Biome and TypeScript configs can extend the profiles directly:

```jsonc
// biome.json
{ "extends": ["./third_party/trufflecheck/profiles/typescript/biome.json"] }
```

```jsonc
// tsconfig.json
{ "extends": "./third_party/trufflecheck/profiles/typescript/tsconfig.strict.json" }
```

Cargo and cargo-deny can't include other files. Copy the Rust profiles into your workspace and add only
repo-specific entries (git forks, advisory ignores, duplicate `skip`s), each with a reason. To check a workspace
against the baseline as-is:

```sh
cargo deny --config third_party/trufflecheck/profiles/rust/deny.toml check
```

## Develop

```sh
mise install     # pinned Elide
mise run build   # elide build → <member>/.dev/artifacts/jar/*.jar
mise run test    # build, then run javac + Error Prone and detekt over tests/ fixtures
```

Fixtures mark each expected finding with `// expect: RuleName`. The test fails on any missing or extra finding.
`tests/smoke.sh` needs JDK 21+ and curl, and caches its pinned analyzer JARs in `$TRUFFLECHECK_CACHE`.

To release, bump `version` in every `elide.pkl` and push a `vX.Y.Z` tag. CI then publishes to maven.elide.dev and
creates the GitHub release.
