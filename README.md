# trufflecheck

Static analysis rules and strictness profiles for [Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/)
languages that ship as **GraalVM Native Image** (SubstrateVM) binaries.

- **Rules:** 28 Error Prone checks for Java and a matching detekt rule set for Kotlin. They catch partial-evaluation,
  deoptimization and image-heap hazards that compile cleanly but break or slow down a native image.
- **Profiles:** shared settings for Error Prone/NullAway, detekt, CPD, cargo-deny, clippy, rustfmt, Biome and tsconfig.

Built against [Elide's GraalVM](https://graalvm.elide.dev) 25.5 line. Licensed under [Apache-2.0](LICENSE-APACHE) or
[MIT](LICENSE-MIT), at your option.

## Get it

- **Maven** at `https://maven.elide.dev`: `dev.elide:trufflecheck-errorprone`, `dev.elide:trufflecheck-detekt` (both
  include the policy) and `dev.elide:trufflecheck-policy`.
- **[GitHub releases](https://github.com/elide-dev/trufflecheck/releases):** the same JARs, plus
  `trufflecheck-profiles-<version>.tar.gz`.
- **git submodule:** everything, including `profiles/` to extend from.

```sh
git submodule add https://github.com/elide-dev/trufflecheck third_party/trufflecheck
```

## Use the rules

### Error Prone (Java)

Put `trufflecheck-errorprone` on the annotation-processor path next to Error Prone, then enable checks. The complete
profile is [`profiles/jvm/errorprone.args`](profiles/jvm/errorprone.args), one flag per line.

**Gradle** with `net.ltgt.errorprone`:

```kotlin
val trufflecheck = "0.1.0" // x-release-please-version

repositories { maven("https://maven.elide.dev") }

dependencies {
  errorprone("com.google.errorprone:error_prone_core:2.51.0")
  errorprone("dev.elide:trufflecheck-errorprone:$trufflecheck")
}

tasks.withType<JavaCompile>().configureEach {
  options.errorprone {
    error("TruffleNodeChildren", "TruffleMissingBoundary", "TruffleCompilationFinalWrite")
    option("Trufflecheck:Policy", "$rootDir/trufflecheck.properties")
  }
}
```

**Maven** (Error Prone also needs its `--add-exports`/`--add-opens` lines in `.mvn/jvm.config`; see the
[Error Prone install docs](https://errorprone.info/docs/installation)):

```xml
<properties>
  <trufflecheck.version>0.1.0</trufflecheck.version> <!-- x-release-please-version -->
</properties>

<repositories>
  <repository><id>elide</id><url>https://maven.elide.dev</url></repository>
</repositories>

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
      <path><groupId>dev.elide</groupId><artifactId>trufflecheck-errorprone</artifactId><version>${trufflecheck.version}</version></path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

**Bazel** with `rules_jvm_external`:

```starlark
TRUFFLECHECK = "0.1.0"  # x-release-please-version

maven.install(
    artifacts = ["dev.elide:trufflecheck-errorprone:" + TRUFFLECHECK],
    repositories = ["https://maven.elide.dev", "https://repo1.maven.org/maven2"],
)

# BUILD: a plugin without a processor class is how JavaBuilder's Error Prone is reached.
java_plugin(name = "trufflecheck", deps = ["@maven//:dev_elide_trufflecheck_errorprone"])
# Then list ":trufflecheck" in `plugins` and add -Xep:Truffle…:ERROR flags to `javacopts`.
```

### detekt (Kotlin)

```sh
version=0.1.0  # x-release-please-version
detekt --plugins "trufflecheck-detekt-$version.jar" \
  --config third_party/trufflecheck/profiles/jvm/detekt.yaml \
  --analysis-mode full --classpath "$CLASSPATH" --input src/main/kotlin
```

With Gradle, add `detektPlugins("dev.elide:trufflecheck-detekt:<version>")`. The rules live in the `Truffle` rule
set. Point them at your policy with a `policy:` key under `Truffle:`, or with `-Dtrufflecheck.policy=…`. The JAR
targets detekt 2.0.0-alpha.6.

### Tell the rules about your project

Some rules need project facts: which packages hold guest code, and which classes Native Image initializes at build
time. Put them in a properties file:

```properties
# trufflecheck.properties (paths are relative to this file)
hostinterop.scope=com.example.mylang
# Read --initialize-at-build-time/--initialize-at-run-time from META-INF/native-image/**/native-image.properties:
classinit.native-image=src/main/resources
# ...or keep explicit lists, one class or package prefix per line:
classinit.buildtime=tools/classinit/buildtime.txt
# `key+=` appends to a default list instead of replacing it:
collections+=java.util.concurrent.CopyOnWriteArrayList
```

The rules find this file through, in order:

1. the checker's option: `-XepOpt:Trufflecheck:Policy=…` for Error Prone, `policy:` for detekt;
2. the `trufflecheck.policy` system property;
3. the `TRUFFLECHECK_POLICY` environment variable.

Every default, including the call lists behind each rule, is in
[`policy.properties`](policy/src/main/resources/META-INF/trufflecheck/policy.properties). Any rule can also skip
paths with `<rule>.exclude=<regex>`.

## Rule catalog

Each rule is listed with its default severity and the languages it covers. Rule names in `elide-dev/runtime` started
as `Elide*`; Error Prone still accepts those names, including in `@SuppressWarnings`.

Many rules apply only to **compiled code**, meaning code partial evaluation reaches: methods of a `Node` (static or
not, including Kotlin companions), `@ExportMessage` methods, and DSL `@Specialization`/`@Fallback` methods. That
excludes:

- `@TruffleBoundary` methods, and methods opening with `CompilerAsserts.neverPartOfCompilation()`;
- code after `transferToInterpreter*()`;
- branches guarded by `inInterpreter()` or `inInterpreterFastPath()`, and the `else` of `inCompiledCode()`;
- `shouldNotReachHere(…)` arguments and `interpreterOnly(…)` lambdas;
- Java lambdas, and DSL-generated (`@GeneratedBy`) classes.

### Node trees

#### `TruffleNodeChildren`

*Error · Java, Kotlin.* Flags node state that isn't adopted:

- a non-final node (or node-interface) field without `@Child`;
- a node array without `@Children`;
- collections or nested arrays of nodes;
- nodes captured by anonymous or local node classes.

An unadopted node isn't part of the tree. Partial evaluation can't treat it as a constant, and passing the frame into
it aborts compilation. `final` and `RootNode`-typed fields are references to nodes owned elsewhere, and are exempt.

#### `TruffleChildWriteWithoutInsert`

*Error · Java, Kotlin.* Assigning a node to a `@Child` field or a `@Children` element after construction without
`insert(…)`. The node gets no parent, so `getRootNode()`, `replace` and DSL-generated code misbehave.

#### `TruffleChildrenLoop`

*Warning · Java, Kotlin.* Flags:

- a loop over `@Children` without `@ExplodeLoop`, which leaves every `children[i].execute` a virtual call;
- an `@ExplodeLoop` loop bounded by a parameter or local that isn't asserted with `partialEvaluationConstant`, which
  can explode without bound;
- `MERGE_EXPLODE` without `CompilerDirectives.mergeExplodeKey`, which 25.5 requires.

#### `TruffleNonConstantNodeState`

*Error · Java, Kotlin.* An `Assumption`, profile, `CallTarget`, `Shape` or `FrameDescriptor` field in a node that's
neither `final` nor `@CompilationFinal`, or a static profile. Partial evaluation only folds final, `@CompilationFinal`,
`@Child` and `@Children` fields, so anything else is a real load and a real branch. A static profile is shared by every
node and profiles nothing.

### Compilation and deoptimization

#### `TruffleCompilationFinalDimensions`

*Error · Java, Kotlin.* `@CompilationFinal` on an array without `dimensions`. Omitted, it defaults to the array's full
depth, so every element is folded as a constant, and elements written later leave stale compiled code. State the depth
you mean. It honors the DSL's own `VerifyCompilationFinal` suppression key.

#### `TruffleCompilationFinalWrite`

*Error · Java, Kotlin.* A write to a `@CompilationFinal` field (or an element within its `dimensions`) outside
construction, not preceded by `transferToInterpreterAndInvalidate()` or `Assumption.invalidate()`. Compiled code
folded the old value. A plain `transferToInterpreter()` deoptimizes but doesn't invalidate.

#### `TruffleMissingBoundary`

*Error · Java, Kotlin.* In compiled code, flags:

- calls on Native Image's runtime-compilation blocklist: collection interfaces, `HashMap`, `BigInteger`, functional
  interfaces, `Object.toString`, `String.format`, `valueOf`, `indexOf` and others;
- regex, streams, reflection and printing;
- non-constant string concatenation or interpolation.

The image build fails when blocklisted methods are reachable from runtime-compiled code. Call them from a
`@TruffleBoundary`. The list mirrors `TruffleFeature`'s blocklist, less its temporary allowlist (`List.get`,
`Iterator.next`, …).

#### `TruffleSkippedException`

*Error · Java, Kotlin.* Throwing `IllegalArgumentException`, `IllegalStateException`, `ArithmeticException`,
`IndexOutOfBoundsException`, `ClassCastException` and the like in compiled code, including through Kotlin's `require`,
`check` and `error`. Graal never handles these in compiled code: throwing one always deoptimizes and invalidates. Throw
a guest error (`AbstractTruffleException`), or transfer to the interpreter first.

#### `TruffleDeoptLoop`

*Warning · Java, Kotlin.* `transferToInterpreterAndInvalidate()` followed only by a `throw` or a plain `return`, or an
unbounded `@CompilationFinal` value cache (`if (cached != v) { invalidate; cached = v; }`). Nothing changes, so the
recompiled code invalidates again. Since 25.0 the runtime detects these cycles and stops compiling the call target (see
[`DeoptCyclePatterns.md`](https://github.com/elide-dev/graal/blob/gvm-elide-25.5.5-elide.28/truffle/docs/DeoptCyclePatterns.md)).

#### `TruffleGenericCatch`

*Error · Java, Kotlin.* `catch (Throwable | Exception | RuntimeException | Error | StackOverflowError)` in compiled
code that doesn't let `ControlFlowException` and `AbstractTruffleException` through. It swallows returns, breaks,
guest errors and cancellation.

#### `TruffleFrameEscape`

*Error · Java, Kotlin.* A `VirtualFrame` stored in a field, or captured by a lambda (in Kotlin, a non-inline one) or
an inner class. An escaping frame aborts partial evaluation. It also flags `getFrame(READ_WRITE | MATERIALIZE)` stack
walks in nodes, which force materialization and pin virtual threads in Elide's GraalVM.

#### `TruffleGuestLoop`

*Warning · Java, Kotlin.* A loop in a node that executes child nodes without `LoopNode`/`RepeatingNode`,
`TruffleSafepoint.poll` or OSR polling. It gets no on-stack replacement and no loop-count profiling, and safepoints
can't interrupt it.

#### `TruffleFastPathRecursion`

*Warning · Java, Kotlin.* Direct self-recursion in compiled code. Partial evaluation can't inline recursion, and host
inlining only takes the first level. Recurse through the AST, or behind a boundary.

#### `TruffleUncachedOnFastPath`

*Warning · Java, Kotlin.* `getUncached()` or a direct `CallTarget.call` in compiled code. Uncached variants never
specialize, and a direct call gets no inlining or splitting. Use `@Cached` nodes and `DirectCallNode`/`IndirectCallNode`.

#### `TruffleBoundaryExceptionPolicy`

*Suggestion · Java, Kotlin.* A `@TruffleBoundary` method that throws guest exceptions without
`transferToInterpreterOnException = false`. By default the first exception out of a boundary deoptimizes the caller.

#### `TruffleSlowPathInline`

*Suggestion · Java, Kotlin.* Message building after `transferToInterpreter*()` in a node method. It never runs
compiled, but host compilation still inlines it into the interpreter and grows the image. Move it to a separate method.

#### `TruffleBytecodeSwitch`

*Suggestion · Java, Kotlin.* A loop that switches over elements of a `@CompilationFinal` array, without
`@HostCompilerDirectives.BytecodeInterpreterSwitch`. Host compilation optimizes annotated dispatch loops for the
native image.

### Native Image

#### `TruffleHostValueInImageHeap`

*Error · Java, Kotlin.* A static initializer of a build-time-initialized class that reads build-host state: environment
variables, system properties, clocks, locale, CPU count or randomness. It also flags creating a `Random`, a `Thread` or
a stream there. The check follows one hop into constructors and methods in the same file. Native Image runs that
initializer on the build machine and freezes the result into the image heap.

#### `TruffleStaticContextState`

*Error · Java, Kotlin.* A static field, or a Kotlin `object`, companion or top-level property, holding `Env`,
`TruffleContext`, the language, a `CallTarget`, an adoptable node, a `Thread` or a `ThreadLocal`. Statics are shared
across contexts and engines, frozen into the image heap, and break context pre-initialization. Use `ContextReference`,
`LanguageReference`, `ContextLocal`/`ContextThreadLocal`, or an `UnadoptableNode`.

#### `TruffleBlockingInNode`

*Error · Java, Kotlin.* `synchronized`, locks, `withLock`, `ThreadLocal`, `sleep`, `join`, `park`, `await` or
`Future.get` in compiled code. Lock methods are on the runtime-compilation blocklist, and blocking stalls safepoints.
Put it behind a boundary, and use `TruffleSafepoint.setBlocked` for guest-visible blocking.

#### `TruffleFieldUpdaterConstant`

*Warning · Java, Kotlin.* `Atomic*FieldUpdater.newUpdater` or an `Unsafe` offset that doesn't directly initialize a
`static final` field. Native Image recomputes offsets only in that shape; anything else bakes in the build JVM's
offset.

### API hygiene

#### `TruffleHostInterop`

*Error · Java, Kotlin.* Each reference to an `org.graalvm.polyglot` type in guest-implementation packages
(`hostinterop.scope`). The embedding API is for hosts; guest code should use `InteropLibrary` and `@ExportLibrary`.
Imports aren't reported, so a reviewed crossing can be suppressed on its own method. `SandboxPolicy` and
`ByteSequence` are allowed.

#### `TruffleStackfulException`

*Error · Java, Kotlin.* A JDK exception created with a stack trace, a project exception type that extends one without
opting out, or Kotlin's `require`, `check`, `error` or `TODO`. Filling in a stack trace walks the stack and is
blocklisted for compiled code. Override `fillInStackTrace`, pass `writableStackTrace = false`, or extend a Truffle
exception type. Test sources are excluded by default.

#### `TruffleForbiddenCollection`

*Error · Java, Kotlin.* `LinkedList`, `Vector`, `Hashtable`, `Stack` or `StringBuffer`. Each chases pointers or takes
a lock on every operation, and all are blocklisted for compiled code.

#### `TruffleDeclaredType`

*Warning · Java, Kotlin.* `TruffleObject` as the declared type of a field, parameter, local or return value. Interop
dispatches on `Object`, so the marker type only adds casts and type checks. GraalPy enforces the same.

#### `TruffleDeprecatedApi`

*Warning · Java, Kotlin.* Uses of deprecated Truffle and GraalVM API, with the migration path when the policy has one:
`DynamicObjectLibrary` → `DynamicObject.GetNode`/`PutNode`, `Env.createThread` → `newTruffleThreadBuilder`, and so
on.

#### `TruffleDslSuppressions`

*Error · Java, Kotlin.* `@SuppressWarnings("truffle")` or `("all")` in Truffle code. The DSL's warnings are
performance and footprint findings; suppress the specific `truffle-*` key instead.

#### `TruffleLegacyProfile`

*Warning · Java, Kotlin.* A `BranchProfile`, `ConditionProfile` or similar field in a DSL node. Inlined profiles
(`@Cached Inlined*Profile` with `@Bind Node`) live in the node's state bits, cutting footprint and image size.

### Kotlin only

#### `TruffleKotlinNodeHazards`

*Error · Kotlin.* In nodes, flags:

- delegated properties such as `by lazy`, which add an object, a lock and a lambda to each read;
- `@Child val`, a final field Truffle rejects;
- spread arguments in compiled code, which copy an array per call.

#### `TruffleAnalysisError`

*Error · Kotlin.* Sources detekt couldn't fully resolve. Unresolved code hides findings, so fix the classpath rather
than trust a clean report.

## Profiles

The shared settings live under [`profiles/`](profiles).

### JVM

- [`errorprone.args`](profiles/jvm/errorprone.args): every trufflecheck check at its default severity, NullAway in
  JSpecify mode, and `DefaultCharset`, `DefaultLocale` and `ClassInitializationDeadlock` as errors for build-time
  initialization.
- [`detekt.yaml`](profiles/jvm/detekt.yaml): bug-finding rules only, with no style or naming rules. `CouldBeSequence`
  is off, since sequences add calls that partial evaluation can't devirtualize.
- [`cpd.args`](profiles/jvm/cpd.args): PMD copy-paste detection.

For Kotlin node modules, also consider kotlinc's `-Xno-param-assertions -Xno-call-assertions
-Xno-receiver-assertions`. The `Intrinsics.check*` calls they remove sit on fast paths and throw stackful exceptions.

### Rust

[`lints.toml`](profiles/rust/lints.toml) (a `[workspace.lints]` block to paste into your workspace `Cargo.toml`),
[`clippy.toml`](profiles/rust/clippy.toml), [`deny.toml`](profiles/rust/deny.toml) and
[`rustfmt.toml`](profiles/rust/rustfmt.toml). They target code behind `extern "C"` or JNI in a native image, where a
panic aborts the whole process. So `unwrap`, `expect` and `panic!` are flagged outside tests, and process-global
operations (`exit`, `set_var`, panic hooks) are disallowed.

Cargo and cargo-deny can't include other files, so copy these into your workspace and add only repo-specific entries
(git forks, advisory ignores, duplicate `skip`s), each with a reason. Every member needs `[lints] workspace = true`,
and CI must run `cargo clippy -- -D warnings` for the `warn` levels to have any effect. To check a workspace against
the baseline as-is:

```sh
cargo deny --config third_party/trufflecheck/profiles/rust/deny.toml check
```

### TypeScript

- [`biome.base.json`](profiles/typescript/biome.base.json): lint-only correctness rules, with noisy ones at `warn`.
- [`biome.guest.json`](profiles/typescript/biome.guest.json): the base plus engine-shape rules for code that runs on
  GraalJS inside the runtime: no `delete`, `arguments`, `eval`, `with`, `__proto__`, sparse arrays or `var`.
- [`tsconfig.strict.json`](profiles/typescript/tsconfig.strict.json): the portable core;
  [`tsconfig.strictest.json`](profiles/typescript/tsconfig.strictest.json) adds `exactOptionalPropertyTypes` and
  `noPropertyAccessFromIndexSignature`; [`tsconfig.runtime.json`](profiles/typescript/tsconfig.runtime.json) adds
  module-emit settings for type-stripping runtimes.

```jsonc
// biome.json for tooling and libraries
{ "root": true, "extends": ["./third_party/trufflecheck/profiles/typescript/biome.base.json"] }
// biome.json in a directory of guest builtins
{ "extends": ["./third_party/trufflecheck/profiles/typescript/biome.guest.json"] }
// tsconfig.json
{ "extends": "./third_party/trufflecheck/profiles/typescript/tsconfig.runtime.json" }
```

Formatting stays per repository. The Biome profiles target Biome 2.6.7.

## Develop

```sh
mise install     # pinned Elide
mise run build   # elide build → <member>/.dev/artifacts/jar/*.jar
mise run test    # build, then run javac + Error Prone and detekt over the tests/ fixtures
```

Fixtures mark each expected finding with `// expect: RuleName`, and the test fails on any missing or extra finding.
It also compiles the fixtures with the complete shipped Error Prone profile to make sure every flag loads.
`tests/smoke.sh` needs JDK 21+ and curl, and caches its pinned analyzer JARs in `$TRUFFLECHECK_CACHE`.

Releases are automated. Commits on `main` follow
[Conventional Commits](https://www.conventionalcommits.org/), and Release Please keeps a release pull request open.
Merging it tags the release, updates `CHANGELOG.md` and the versions above, and publishes to maven.elide.dev and
GitHub releases.
