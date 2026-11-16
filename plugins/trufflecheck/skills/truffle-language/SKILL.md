---
name: truffle-language
description: How to write and review Truffle language implementation code (Java or Kotlin) that ships as a GraalVM Native Image (SubstrateVM) binary - nodes and @Child/@Children, Truffle DSL @Specialization nodes, @ExportLibrary/@ExportMessage interop, VirtualFrame use, profiles and assumptions, @CompilationFinal, @TruffleBoundary, deoptimization, loops and OSR, and Native Image build-time class initialization. Use this whenever code imports com.oracle.truffle.*, whenever you add, change, or review an AST or bytecode interpreter node, a guest-language builtin, or an interop object, and whenever a native-image build fails on runtime-compiled code or a trufflecheck, Error Prone, or detekt Truffle* finding needs fixing - even if the user doesn't say "Truffle".
license: Apache-2.0 OR MIT
metadata:
  source: https://github.com/elide-dev/trufflecheck
---

# Writing Truffle language code for Native Image

Truffle code is ordinary Java or Kotlin that compiles cleanly while being wrong in ways only partial evaluation (PE)
or the Native Image build reveals: a slow interpreter, a deoptimization loop, a failed image build, or build-machine
state frozen into the binary. This skill is the set of habits that avoid those outcomes. It mirrors the
[trufflecheck](https://github.com/elide-dev/trufflecheck) rules (Error Prone for Java, detekt for Kotlin), which
target GraalVM 25.5.

The rules are static heuristics. They see syntax and types in one file, not what PE actually does, so a clean report
is not proof of good compiled code and a finding is occasionally a false positive. Reason from the principle first.

## The one idea: compiled code

Most guidance applies to **compiled code**: code partial evaluation reaches. That is methods of a `Node` (static or
not, including Kotlin companions), `@ExportMessage` methods, and DSL `@Specialization`/`@Fallback` methods. Everything
PE reaches must be something it can fold to constants and inline, and on SubstrateVM it must also stay off the
runtime-compilation blocklist or the image build fails.

Not compiled code, so the fast-path restrictions don't apply:

- `@TruffleBoundary` methods, and methods opening with `CompilerAsserts.neverPartOfCompilation()`;
- code after `transferToInterpreter()` / `transferToInterpreterAndInvalidate()`;
- branches guarded by `inInterpreter()` or `inInterpreterFastPath()`, and the `else` of `inCompiledCode()`;
- `shouldNotReachHere(…)` arguments and `interpreterOnly(…)` lambdas;
- Java lambdas, and DSL-generated (`@GeneratedBy`) classes.

Before writing a line in a node, ask: is this on the fast path? If yes, the checklist below applies. If it's
slow-path work (error messages, formatting, collections, reflection), move it into a separate `@TruffleBoundary`
method rather than loosening the fast path.

## Checklist while writing

Each item names the rule that enforces it; look the rule up in [references/rules.md](references/rules.md) for the
reasoning, the fix and good/bad snippets.

**Node trees**
- Every node field is `@Child` (non-final; in Kotlin `@Child var`), `@Children final T[]`, or a `final` reference to
  a node owned elsewhere. No collections of nodes. `TruffleNodeChildren`, `TruffleKotlinNodeHazards`
- Writing a child after construction goes through `insert(…)`. `TruffleChildWriteWithoutInsert`
- Loops over `@Children` are `@ExplodeLoop` with PE-constant bounds. `TruffleChildrenLoop`
- `Assumption`, profile, `CallTarget`, `Shape`, `FrameDescriptor` fields are `final` or `@CompilationFinal`, never
  static profiles. `TruffleNonConstantNodeState`

**Speculation and deoptimization**
- `@CompilationFinal` arrays state `dimensions`. `TruffleCompilationFinalDimensions`
- Writes to `@CompilationFinal` state come after `transferToInterpreterAndInvalidate()`.
  `TruffleCompilationFinalWrite`
- Every invalidation records new state so the recompiled code doesn't take the same exit. `TruffleDeoptLoop`
- Don't throw JDK runtime exceptions on the fast path; throw guest errors. `TruffleSkippedException`,
  `TruffleStackfulException`
- Broad `catch` blocks rethrow `ControlFlowException` and `AbstractTruffleException`. `TruffleGenericCatch`

**Fast-path contents**
- Collections, strings, regex, streams, reflection, printing, `BigInteger`: behind a boundary.
  `TruffleMissingBoundary`, `TruffleForbiddenCollection`
- Locks, `synchronized`, thread-locals, blocking: behind a boundary. `TruffleBlockingInNode`
- `VirtualFrame` stays on the stack: no fields, lambdas or inner classes. `TruffleFrameEscape`
- Guest loops use `LoopNode`/`RepeatingNode` or poll safepoints. `TruffleGuestLoop`
- No direct self-recursion; recurse through the AST. `TruffleFastPathRecursion`
- Use `@Cached` nodes/libraries and `DirectCallNode`/`IndirectCallNode`, not `getUncached()` or `CallTarget.call`.
  `TruffleUncachedOnFastPath`
- In DSL nodes use inlined profiles. `TruffleLegacyProfile`
- Boundaries that throw guest errors set `transferToInterpreterOnException = false`; slow-path message building
  lives in its own method; bytecode dispatch loops are annotated. `TruffleBoundaryExceptionPolicy`,
  `TruffleSlowPathInline`, `TruffleBytecodeSwitch`

**Native Image**
- No context, env, language, call target, node, thread or thread-local in statics (or Kotlin `object`s, companions,
  top-level properties). `TruffleStaticContextState`
- Static initializers of build-time-initialized classes don't read the build machine (env, properties, clocks,
  locale, randomness). `TruffleHostValueInImageHeap`
- Field updaters and `Unsafe` offsets initialize a `static final` field directly. `TruffleFieldUpdaterConstant`

**API hygiene**
- Guest code uses `InteropLibrary`/`@ExportLibrary`, not `org.graalvm.polyglot`. `TruffleHostInterop`
- Declare interop values as `Object`, not `TruffleObject`. `TruffleDeclaredType`
- Migrate deprecated Truffle API; suppress specific `truffle-*` DSL warnings, never all.
  `TruffleDeprecatedApi`, `TruffleDslSuppressions`
- Kotlin: a clean detekt run only counts if the sources resolved. `TruffleAnalysisError`

## Run trufflecheck

When the project has trufflecheck wired in, run it after editing Truffle code and before declaring the work done;
it is the cheapest feedback available and far faster than a native-image build. Find how the project invokes it
rather than guessing: look for `trufflecheck` in the build files (Gradle `errorprone(…)`/`detektPlugins(…)`
dependencies, Maven `annotationProcessorPaths`, Bazel `java_plugin`), a `trufflecheck.properties` policy file, a
`third_party/trufflecheck` submodule, or a lint/check task in the project's task runner. Error Prone findings appear
in normal `javac` output as `[<RuleName>]`; detekt findings appear under the `Truffle` rule set.

If trufflecheck isn't set up, say so and offer to add it (see [references/setup.md](references/setup.md)); still
apply the checklist by hand.

When a finding appears:

1. Read the rule's entry in [references/rules.md](references/rules.md) and fix the cause. Most fixes are one of:
   add the annotation, move code behind `@TruffleBoundary`, invalidate before writing, or record state before
   deoptimizing.
2. Don't make a finding go away by wrapping fast-path code in `@TruffleBoundary` without thinking: a boundary stops
   inlining and makes every call a real call. It's right for slow-path work and wrong for a hot operation.
3. Suppress only when the code is right and the heuristic is wrong, on the narrowest element
   (`@SuppressWarnings("<RuleName>")` in Java, `@Suppress("<RuleName>")` in Kotlin), with a comment saying
   why. A path-wide exemption goes in the policy file as `<rule>.exclude=<regex>`; check the project's policy for the
   key names.

## Reviewing Truffle code

Walk the diff with the checklist, and report findings by rule name so the author can look them up. Give the
consequence, not just the rule: "this `HashMap.get` in a `@Specialization` fails the image build" is more useful
than "missing boundary". Flag things the rules can't see too, such as a boundary on a hot path or a specialization
that can never be reached, and say when a concern is your judgement rather than a rule.

## Reference files

- [references/rules.md](references/rules.md): every rule with severity, principle, why it matters on SVM, the fix
  and snippets. Read the entries for the area you're working in.
- [references/setup.md](references/setup.md): adding trufflecheck to a build and writing the policy file. Read when
  the project doesn't run trufflecheck yet or a scoped rule seems inactive.
