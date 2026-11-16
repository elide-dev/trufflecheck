# `trufflecheck`

Static analysis rules and strictness profiles for [Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/)
languages that ship as **GraalVM Native Image** (SubstrateVM) binaries. Used across Elide's repositories.

- **Rule JARs:** Error Prone checks for Java and a detekt rule set for Kotlin, aimed at partial-evaluation and image-heap
  hazards that compile cleanly but break or slow down a native image.
- **Profiles:** shared Error Prone/NullAway, detekt, CPD, cargo-deny, clippy, rustfmt, Biome and tsconfig settings.

Licensed under [Apache-2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your option.

## Get it

> **Pre-release.** No version is published yet: the first release (0.1.0) will appear on maven.elide.dev and GitHub
> releases when it's tagged. Until then, use a git submodule, or build the JARs with `mise run build`.

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
`--initialize-at-run-time`; the most specific match wins. Or let the rules read your existing Native Image
configuration: `classinit.native-image=src/main/resources` scans for `META-INF/native-image/**/native-image.properties`
and takes the `--initialize-at-*` entries from their `Args`.

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
| `TruffleNodeChildren` | ✓ | ✓ | Non-final node fields (or node-interface fields) without `@Child`, node arrays without `@Children`, collections or nested arrays of nodes, nodes captured by anonymous/local node classes | An unadopted node isn't part of the tree: partial evaluation can't treat it as a constant, calls through it stay virtual, and passing the frame into one aborts compilation. `final` fields and `RootNode`-typed fields are references to nodes owned elsewhere (a root is never adopted) and are left alone. |
| `TruffleCompilationFinalDimensions` | ✓ | ✓ | `@CompilationFinal` on an array without `dimensions` | Omitted, `dimensions` defaults to the array's full depth, so every element is folded as a constant; elements written later without invalidation leave stale compiled code. State the depth you mean. Honors the DSL's `VerifyCompilationFinal` suppression key. |
| `TruffleBlockingInNode` | ✓ | ✓ | `synchronized`, locks, `withLock`, `ThreadLocal`, `sleep`/`join`/`park`/`await`/`Future.get` in code partial evaluation reaches | Lock methods are on Native Image's runtime-compilation blocklist (the image build fails), and blocking in compiled guest code stalls safepoints. Put it behind `@TruffleBoundary` or a `transferToInterpreter`; use `TruffleSafepoint.setBlocked` for guest-visible blocking. |
| `TruffleHostValueInImageHeap` | ✓ | ✓ | Build-host reads (env, properties, clocks, locale, CPU count, randomness, `new Random()`/`Thread`/streams) in the static initializer of a build-time-initialized class, including one hop into constructors and methods of the same file | Native Image runs that initializer on the build machine and freezes the result into the image heap: every user sees the builder's environment, time and seed. |
| `TruffleHostInterop` | ✓ | ✓ | Each reference to an `org.graalvm.polyglot` type (simple or qualified name, not imports) in guest-implementation packages, so a reviewed crossing can be suppressed on its method | The embedding API is for hosts. Guest code using it skips Truffle interop and can't be partially evaluated. Use `InteropLibrary` and `@ExportLibrary`. `SandboxPolicy` and `ByteSequence` are allowed. |
| `TruffleStackfulException` | ✓ | ✓ | JDK exceptions created with a stack trace; project exception types extending one without opting out; Kotlin `require`/`check`/`error`/`TODO` | Filling in a stack trace walks the stack, and `Throwable.fillInStackTrace` is on Native Image's runtime-compilation blocklist. Override `fillInStackTrace`, pass `writableStackTrace = false`, or extend a Truffle exception type. |
| `TruffleForbiddenCollection` | ✓ | ✓ | `LinkedList`, `Vector`, `Hashtable`, `Stack`, `StringBuffer` | Pointer chasing or a lock on every operation, and on Native Image's runtime-compilation blocklist. Use array-backed collections and `StringBuilder`. |
| `TruffleCompilationFinalWrite` | ✓ | ✓ | Writes to `@CompilationFinal` fields (or elements within their `dimensions`) outside constructors, not preceded by `transferToInterpreterAndInvalidate()` or `Assumption.invalidate()` | Compiled code folded the old value; changing it without invalidating leaves compiled code using a stale constant. A plain `transferToInterpreter()` deoptimizes but doesn't invalidate. |
| `TruffleMissingBoundary` | ✓ | ✓ | Calls on Native Image's runtime-compilation blocklist (collection interfaces, `HashMap`, `BigInteger`, functional interfaces, `Object.toString`, `String.format`/`valueOf`/`indexOf`, …), regex, streams, reflection, printing, and non-constant string concatenation or interpolation, in code partial evaluation reaches | The image build fails when blocklisted methods are reachable from runtime-compiled code, and partial evaluation can't usefully inline the rest. Call them from a `@TruffleBoundary`. The list mirrors `TruffleFeature`'s blocklist, less its temporary allowlist (`List.get`, `Iterator.next`, …). |
| `TruffleSkippedException` | ✓ | ✓ | `throw new IllegalArgumentException/IllegalStateException/ArithmeticException/IndexOutOfBounds/ClassCast/…` (and Kotlin `require`/`check`/`error`) in code partial evaluation reaches | Graal never handles these "skipped" exceptions in compiled code: throwing one always deoptimizes and invalidates, and catching it doesn't help. Throw a guest error (`AbstractTruffleException`) or transfer to the interpreter first. |
| `TruffleChildWriteWithoutInsert` | ✓ | ✓ | Assigning a node to a `@Child` field or `@Children` element outside the constructor without `insert(…)` | The node gets no parent: `getRootNode()`, `replace` and DSL-generated code misbehave. |
| `TruffleChildrenLoop` (warning) | ✓ | ✓ | Loops over `@Children` without `@ExplodeLoop`; `@ExplodeLoop` loops bounded by a parameter or local not asserted with `partialEvaluationConstant`; `MERGE_EXPLODE` without `mergeExplodeKey` | Unexploded, each `children[i].execute` stays a virtual call; exploded without a constant bound, the graph can blow up and compilation bails out. `mergeExplodeKey` is required for merge explosion since 25.5. |
| `TruffleDeoptLoop` (warning) | ✓ | ✓ | `transferToInterpreterAndInvalidate()` followed only by a `throw` or plain `return`; an unbounded `@CompilationFinal` value cache (`if (cached != v) { invalidate; cached = v; }`) | Nothing changes, so recompiled code takes the same path and invalidates again. Since 25.0 the runtime detects deoptimization cycles and stops compiling the call target ([`DeoptCyclePatterns.md`](https://github.com/elide-dev/graal/blob/gvm-elide-25.5.5-elide.28/truffle/docs/DeoptCyclePatterns.md)). |
| `TruffleStaticContextState` | ✓ | ✓ | Static fields (Kotlin `object`/companion/top-level properties) holding `Env`, `TruffleContext`, the language, `CallTarget`, adoptable nodes, `Thread` or `ThreadLocal` | Statics are shared across contexts and engines, frozen into the image heap under build-time initialization, and break context pre-initialization. Use `ContextReference`/`LanguageReference`, `ContextLocal`/`ContextThreadLocal`, or an `UnadoptableNode`. |
| `TruffleFrameEscape` | ✓ | ✓ | `VirtualFrame` stored in a field or captured by a lambda (non-inline, in Kotlin) or inner class; `getFrame(READ_WRITE/MATERIALIZE)` stack walks in nodes | An escaping frame aborts partial evaluation. Writable or materialized caller frames force materialization, and pin virtual threads in Elide's GraalVM. |
| `TruffleNonConstantNodeState` | ✓ | ✓ | `Assumption`, profile, `CallTarget`, `Shape` or `FrameDescriptor` fields in nodes or `@ExportLibrary` classes that are neither `final` nor `@CompilationFinal`; static profiles | Partial evaluation only folds final, `@CompilationFinal`, `@Child` and `@Children` fields; anything else is a load, and a guard on it is a real branch. A static profile is shared by every node and profiles nothing. |
| `TruffleGenericCatch` | ✓ | ✓ | `catch (Throwable/Exception/RuntimeException/Error/StackOverflowError)` in code partial evaluation reaches, without letting `ControlFlowException`/`AbstractTruffleException` through | Swallows returns, breaks, guest errors and cancellation. |
| `TruffleGuestLoop` (warning) | ✓ | ✓ | Loops in nodes that execute child nodes without `LoopNode`/`RepeatingNode`, `TruffleSafepoint.poll` or OSR polling | No on-stack replacement and no loop-count profiling, and safepoints (cancellation, thread-local actions) can't interrupt it. |
| `TruffleFastPathRecursion` (warning) | ✓ | ✓ | Direct self-recursion in code partial evaluation reaches | Partial evaluation can't inline recursion and host inlining takes only the first level. Recurse through the AST or behind a boundary. |
| `TruffleDeprecatedApi` (warning) | ✓ | ✓ | Uses of `@Deprecated` Truffle/GraalVM API (`DynamicObjectLibrary`, `Env.createThread`, `Node.getCost`, …), with the migration when the policy knows it | Deprecated Truffle API is removed in later releases; the hint (`DynamicObject.GetNode`/`PutNode`, `newTruffleThreadBuilder`, …) is what javac's lint doesn't give you. |
| `TruffleDslSuppressions` | ✓ | ✓ | `@SuppressWarnings("truffle")` or `("all")` in Truffle code | The DSL's warnings (sharing, inlining, `neverDefault`, cache limits) are performance and footprint findings; suppress the specific `truffle-*` key. |
| `TruffleLegacyProfile` (warning) | ✓ | ✓ | `BranchProfile`/`ConditionProfile`/… fields in DSL nodes | Inlined profiles (`@Cached Inlined*Profile` with `@Bind Node`) live in the node's state bits instead of separate objects, cutting footprint and image size. |
| `TruffleUncachedOnFastPath` (warning) | ✓ | ✓ | `getUncached()` and direct `CallTarget.call` in code partial evaluation reaches | Uncached variants dispatch on every call and never specialize; a direct call gets no inlining or splitting. Use `@Cached` and `DirectCallNode`/`IndirectCallNode`. |
| `TruffleBoundaryExceptionPolicy` (suggestion) | ✓ | ✓ | `@TruffleBoundary` methods that throw guest exceptions without `transferToInterpreterOnException = false` | By default, the first exception out of a boundary deoptimizes the caller, so an expected guest error costs a deoptimization. |
| `TruffleSlowPathInline` (suggestion) | ✓ | ✓ | Message building (concatenation, interpolation) after `transferToInterpreter*()` in a node method | It never runs compiled, but host compilation still inlines it into the interpreter, growing the image. Extract it to a separate method. |
| `TruffleBytecodeSwitch` (suggestion) | ✓ | ✓ | A loop switching over elements of a `@CompilationFinal` array, without `@HostCompilerDirectives.BytecodeInterpreterSwitch` | Host compilation optimizes annotated dispatch loops for the native image. |
| `TruffleFieldUpdaterConstant` (warning) | ✓ | ✓ | `Atomic*FieldUpdater.newUpdater` or `Unsafe` offsets not directly initializing a `static final` field | Native Image recomputes offsets only for that shape (otherwise the build JVM's offset is baked in), and partial evaluation needs the updater to be a constant. |
| `TruffleDeclaredType` (warning) | ✓ | ✓ | `TruffleObject` as the declared type of a field, parameter, local or return value | Interop dispatches through libraries on `Object`; the marker type only adds casts and type checks in compiled code (GraalPy enforces this too). |
| `TruffleKotlinNodeHazards` | | ✓ | In nodes: delegated properties (`by lazy`), `@Child val`, spread arguments on hot paths | Delegates add an object, a lock and a lambda per read; `@Child val` is a final field Truffle rejects; spreads copy arrays per call. |
| `TruffleAnalysisError` | | ✓ | Kotlin sources detekt couldn't fully resolve | Unresolved code hides findings. Fix the classpath rather than trust a clean report. |

"Code partial evaluation reaches" means a method of a `Node` (static or not, including Kotlin companions), an
`@ExportMessage`, or a DSL `@Specialization`/`@Fallback`. It excludes `@TruffleBoundary` methods, methods opening with
`CompilerAsserts.neverPartOfCompilation()`, code after `transferToInterpreter*()`, branches guarded by
`inInterpreter()`, `inInterpreterFastPath()` or the `else` of `inCompiledCode()`, `shouldNotReachHere(…)` arguments,
`interpreterOnly(…)` lambdas, Java lambdas, and DSL-generated (`@GeneratedBy`) classes.

## Profiles

Under [`profiles/`](profiles):

- **`jvm/`**: `errorprone.args` (every trufflecheck check at its default severity, NullAway in JSpecify mode,
  `DefaultCharset`/`DefaultLocale`/`ClassInitializationDeadlock` as errors for build-time initialization), `detekt.yaml`
  (bug-finding rules only, no style or naming; `CouldBeSequence` off, since sequences add calls partial evaluation
  can't devirtualize), `cpd.args` (PMD copy-paste detection). For Kotlin node modules, also consider kotlinc's
  `-Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions`: the `Intrinsics.check*` calls they remove sit
  on fast paths and throw stackful exceptions.
- **`rust/`**: `deny.toml`, `clippy.toml`, `rustfmt.toml`, plus `lints.toml`, a `[workspace.lints]` block to paste into
  your workspace `Cargo.toml`.
- **`typescript/`**:
  - `biome.base.json`: lint-only correctness rules, with noisy ones at `warn`.
  - `biome.guest.json`: the base plus engine-shape rules for JS/TS that runs on GraalJS inside the runtime (no
    `delete`, `arguments`, `eval`, `with`, `__proto__`, sparse arrays or `var`).
  - `tsconfig.strict.json`: the portable core.
  - `tsconfig.strictest.json`: adds `exactOptionalPropertyTypes` and `noPropertyAccessFromIndexSignature`.
  - `tsconfig.runtime.json`: adds module-emit policy for type-stripping runtimes (`verbatimModuleSyntax`,
    `isolatedModules`, `erasableSyntaxOnly`).

Biome and TypeScript configs can extend the profiles directly:

```jsonc
// biome.json: tooling and libraries
{ "root": true, "extends": ["./third_party/trufflecheck/profiles/typescript/biome.base.json"] }
// biome.json in a directory of guest builtins
{ "extends": ["./third_party/trufflecheck/profiles/typescript/biome.guest.json"] }
```

```jsonc
// tsconfig.json
{ "extends": "./third_party/trufflecheck/profiles/typescript/tsconfig.runtime.json" }
```

Formatting stays per repository. The Biome profiles target Biome 2.6.7.

Cargo and cargo-deny can't include other files. Copy the Rust profiles into your workspace and add only
repo-specific entries (git forks, advisory ignores, duplicate `skip`s), each with a reason. Every member needs
`[lints] workspace = true`, and CI must run `cargo clippy -- -D warnings` for the `warn` levels to bite. The lints
target code behind `extern "C"`/JNI in a native image: panics there abort the whole process, so `unwrap`/`expect`/
`panic!` are flagged outside tests, and process-global operations (`exit`, `set_var`, panic hooks) are disallowed. To check a workspace
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
