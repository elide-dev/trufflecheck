# trufflecheck rule reference

One section per rule: default severity, languages, the principle, why it matters on Native Image (SVM), and the fix.
Snippets are Java unless marked; the Kotlin rules behave the same. This file is checked against the rule
implementations by `tests/skill-sync.sh` in the trufflecheck repository: every rule has a `### \`RuleName\`` heading
here, and no heading names a rule that doesn't exist.

The rules are heuristics over source and types. Where a rule's detection is narrower or broader than the principle,
the entry says so.

"Compiled code" below means code partial evaluation (PE) reaches; see the definition in `SKILL.md`.

## Contents

- [Node trees](#node-trees): `TruffleNodeChildren`, `TruffleChildWriteWithoutInsert`, `TruffleChildrenLoop`,
  `TruffleNonConstantNodeState`
- [Compilation and deoptimization](#compilation-and-deoptimization): `TruffleCompilationFinalDimensions`,
  `TruffleCompilationFinalWrite`, `TruffleMissingBoundary`, `TruffleSkippedException`, `TruffleDeoptLoop`,
  `TruffleGenericCatch`, `TruffleFrameEscape`, `TruffleGuestLoop`, `TruffleFastPathRecursion`,
  `TruffleUncachedOnFastPath`, `TruffleBoundaryExceptionPolicy`, `TruffleSlowPathInline`, `TruffleBytecodeSwitch`
- [Native Image](#native-image): `TruffleHostValueInImageHeap`, `TruffleStaticContextState`,
  `TruffleBlockingInNode`, `TruffleFieldUpdaterConstant`
- [API hygiene](#api-hygiene): `TruffleHostInterop`, `TruffleStackfulException`, `TruffleForbiddenCollection`,
  `TruffleDeclaredType`, `TruffleDeprecatedApi`, `TruffleDslSuppressions`, `TruffleLegacyProfile`
- [Kotlin only](#kotlin-only): `TruffleKotlinNodeHazards`, `TruffleAnalysisError`

## Node trees

### `TruffleNodeChildren`

*Error · Java, Kotlin.*

**Principle.** A node that executes as part of this node must be adopted into the tree: `@Child` on a non-final
field, `@Children` on a node array. Flagged: a non-final node (or node-interface) field without `@Child`, a node
array without `@Children`, collections or nested arrays of nodes, and nodes captured by anonymous or local node
classes.

**Why.** An unadopted node isn't part of the tree. PE can't treat it as a constant, and passing the frame into it
aborts compilation.

**Fix.** Annotate the field. If it's a reference to a node owned elsewhere, make it `final`; `final` and
`RootNode`-typed fields are exempt. Replace a `List<Node>` with a `@Children final T[]`.

```java
// bad
Leaf child;
Leaf[] children;
List<Leaf> list;

// good
@Child Leaf adopted;
@Children Leaf[] adoptedAll;
final Leaf reference;          // owned by another node
```

### `TruffleChildWriteWithoutInsert`

*Error · Java, Kotlin.*

**Principle.** Assigning a node to a `@Child` field or `@Children` element after construction must go through
`insert(…)`. Constructor assignments and assigning `null` are fine.

**Why.** A bare assignment leaves the node without a parent, so `getRootNode()`, `replace` and DSL-generated code
misbehave.

**Fix.**

```java
void swap(Leaf next)  { child = next; }           // bad
void adopt(Leaf next) { child = insert(next); }   // good
children[1] = insert(next);                        // good
```

### `TruffleChildrenLoop`

*Warning · Java, Kotlin.*

**Principle.** A loop over `@Children` needs `@ExplodeLoop`, and an exploded loop needs a bound PE can prove
constant. Flagged: a loop over `@Children` without `@ExplodeLoop`; an `@ExplodeLoop` loop bounded by a parameter or
local that isn't asserted with `CompilerAsserts.partialEvaluationConstant`; `MERGE_EXPLODE` without
`CompilerDirectives.mergeExplodeKey`, which 25.5 requires.

**Why.** Unexploded, every `children[i].execute` stays a virtual call. Exploded over a non-constant bound, the loop
can unroll without limit.

**Fix.**

```java
@ExplodeLoop
Object[] exploded(VirtualFrame frame) {
  var values = new Object[arguments.length];        // @Children final: length is constant
  for (int i = 0; i < arguments.length; i++) {
    values[i] = arguments[i].execute(frame);
  }
  return values;
}

@ExplodeLoop
int asserted(int[] values) {
  CompilerAsserts.partialEvaluationConstant(values.length);
  …
}
```

### `TruffleNonConstantNodeState`

*Error · Java, Kotlin.*

**Principle.** An `Assumption`, profile, `CallTarget`, `Shape` or `FrameDescriptor` field in a node is `final` or
`@CompilationFinal`, and profiles are never static.

**Why.** PE folds only `final`, `@CompilationFinal`, `@Child` and `@Children` fields. Anything else is a real load
and its guards are real branches. A static profile is shared by every node and profiles nothing.

**Fix.** Make the field `final`, or `@CompilationFinal` if it's set lazily (then see
`TruffleCompilationFinalWrite`). Give each node its own profile.

## Compilation and deoptimization

### `TruffleCompilationFinalDimensions`

*Error · Java, Kotlin.*

**Principle.** `@CompilationFinal` on an array states `dimensions`.

**Why.** Omitted, it defaults to the array's full depth: every element is folded as a constant, and elements written
later without invalidation leave stale compiled code.

**Fix.** State the depth you mean: `0` makes only the reference constant, `1` the elements of a one-dimensional
array. The rule honors the DSL's own `VerifyCompilationFinal` suppression key.

```java
@CompilationFinal int[] table;                    // bad
@CompilationFinal(dimensions = 1) int[] table;    // good: elements constant
@CompilationFinal(dimensions = 0) int[] buffer;   // good: only the reference
```

### `TruffleCompilationFinalWrite`

*Error · Java, Kotlin.*

**Principle.** A write to a `@CompilationFinal` field (or an element within its `dimensions`) outside construction
is preceded by `transferToInterpreterAndInvalidate()` or `Assumption.invalidate()`.

**Why.** Compiled code folded the old value. A plain `transferToInterpreter()` deoptimizes this activation but
doesn't invalidate the code, so other activations keep the stale constant.

**Fix.**

```java
void unguarded(int value) { cached = value; }     // bad

void transferOnly(int value) {                    // bad: no invalidation
  CompilerDirectives.transferToInterpreter();
  cached = value;
}

void invalidated(int value) {                     // good
  CompilerDirectives.transferToInterpreterAndInvalidate();
  cached = value;
}
```

Writes inside a `@TruffleBoundary` method aren't flagged, since the rule only looks at compiled code. That is a
limit of the heuristic: the stale-constant problem still exists if compiled code read the field, so invalidate
there too.

### `TruffleMissingBoundary`

*Error · Java, Kotlin.*

**Principle.** Compiled code doesn't call into the JDK's large, virtual, allocation-heavy APIs. Flagged in compiled
code: calls on Native Image's runtime-compilation blocklist (collection interfaces, `HashMap`, `BigInteger`,
functional interfaces, `Object.toString`, `String.format`, `valueOf`, `indexOf` and others); regex, streams,
reflection and printing; non-constant string concatenation or interpolation.

**Why.** The image build fails when blocklisted methods are reachable from runtime-compiled code, and PE can't
inline them usefully anyway.

**Fix.** Move the call into a `@TruffleBoundary` method (or after `transferToInterpreter`). Keep the boundary
method small and pass it plain values, not the frame. The rule's list mirrors the blocklist less its temporary
allowlist, so calls such as `List.get` and `Iterator.next` are not flagged; that reflects what the build accepts,
not that they're fast.

```java
@Specialization
Object lookup(String key) { return table.get(key); }          // bad: Map.get in compiled code

@Specialization
Object lookup(String key) { return slowLookup(key); }         // good

@TruffleBoundary
private Object slowLookup(String key) { return table.get(key); }
```

### `TruffleSkippedException`

*Error · Java, Kotlin.*

**Principle.** Compiled code doesn't throw `IllegalArgumentException`, `IllegalStateException`,
`ArithmeticException`, `IndexOutOfBoundsException`, `ClassCastException` and the like, including through Kotlin's
`require`, `check` and `error`.

**Why.** Graal never handles these in compiled code: throwing one always deoptimizes and invalidates, and catching
it doesn't help.

**Fix.** Throw a guest error (a subclass of `AbstractTruffleException`), use a `ControlFlowException` for control
flow, or `transferToInterpreter` first when it really is an internal error.

### `TruffleDeoptLoop`

*Warning · Java, Kotlin.*

**Principle.** Every `transferToInterpreterAndInvalidate()` is followed by a state change that makes the recompiled
code take a different path. Flagged: an invalidation followed only by a `throw` or a plain `return`, and an
unbounded `@CompilationFinal` value cache (`if (cached != v) { invalidate; cached = v; }`).

**Why.** Nothing changes, so the recompiled code invalidates again. Since 25.0 the runtime detects these cycles and
stops compiling the call target, leaving it in the interpreter.

**Fix.** Record the new state (specialize, replace the node, set a generic flag) before leaving, and bound caches.

```java
Object unbounded(Object value) {                  // bad: invalidates for every new value
  if (cached != value) {
    CompilerDirectives.transferToInterpreterAndInvalidate();
    cached = value;
  }
  return cached;
}

Object bounded(Object value) {                    // good: one cached value, then generic
  if (!generic && cached != value) {
    CompilerDirectives.transferToInterpreterAndInvalidate();
    if (cached == null) {
      cached = value;
    } else {
      generic = true;
    }
  }
  return generic ? value : cached;
}
```

### `TruffleGenericCatch`

*Error · Java, Kotlin.*

**Principle.** A `catch` of `Throwable`, `Exception`, `RuntimeException`, `Error` or `StackOverflowError` in
compiled code lets `ControlFlowException` and `AbstractTruffleException` through.

**Why.** A broad catch swallows guest returns, breaks, guest errors and cancellation.

**Fix.** Catch those two first and rethrow, rethrow the caught exception, or catch a specific type.

```java
try {
  body.run();
} catch (ControlFlowException | AbstractTruffleException e) {
  throw e;
} catch (RuntimeException e) {
  return null;
}
```

### `TruffleFrameEscape`

*Error · Java, Kotlin.*

**Principle.** A `VirtualFrame` stays on the stack. Flagged: storing it in a field, capturing it in a lambda (in
Kotlin, a non-inline one) or an inner class, and `getFrame(READ_WRITE | MATERIALIZE)` stack walks in nodes.

**Why.** An escaping frame aborts partial evaluation. Materializing stack walks force materialization and pin
virtual threads in Elide's GraalVM.

**Fix.** Pass the frame down to child nodes as a parameter. Where a frame must outlive the call (closures,
generators), call `frame.materialize()` deliberately and store the `MaterializedFrame`.

### `TruffleGuestLoop`

*Warning · Java, Kotlin.*

**Principle.** A loop in a node that executes child nodes runs through `LoopNode`/`RepeatingNode`, or polls
`TruffleSafepoint.poll` or OSR.

**Why.** A hand-written loop gets no on-stack replacement and no loop-count profiling, and safepoints can't
interrupt it, so a long guest loop stays in the interpreter and can't be cancelled.

**Fix.** Use a `LoopNode` with a `RepeatingNode`. If a hand-written loop is unavoidable, poll
`TruffleSafepoint.poll(this)` in the body and report the count with `LoopNode.reportLoopCount`. Loops that don't
execute child nodes aren't flagged.

```java
while (condition.executeBoolean(frame)) {         // bad
  body.executeBoolean(frame);
}

while (condition.executeBoolean(frame)) {         // accepted
  body.executeBoolean(frame);
  TruffleSafepoint.poll(this);
}
```

### `TruffleFastPathRecursion`

*Warning · Java, Kotlin.*

**Principle.** No direct self-recursion in compiled code.

**Why.** PE can't inline recursion, and host inlining only takes the first level.

**Fix.** Recurse through the AST (child nodes or call targets), or put the recursive helper behind
`@TruffleBoundary`. Only direct self-calls are detected; mutual recursion has the same problem.

### `TruffleUncachedOnFastPath`

*Warning · Java, Kotlin.*

**Principle.** Compiled code doesn't use `getUncached()` or call `CallTarget.call` directly.

**Why.** Uncached variants never specialize, and a direct call gets no inlining or splitting.

**Fix.** Use `@Cached` nodes and libraries, and `DirectCallNode`/`IndirectCallNode` children.

```java
if (InteropLibrary.getUncached().isNull(value)) { … }   // bad, in a @Specialization
return target.call();                                   // bad, in a node method
```

### `TruffleBoundaryExceptionPolicy`

*Suggestion · Java, Kotlin.*

**Principle.** A `@TruffleBoundary` method that throws guest exceptions sets
`transferToInterpreterOnException = false`.

**Why.** By default the first exception out of a boundary deoptimizes the caller, which is wasteful when exceptions
are an expected outcome.

**Fix.**

```java
@TruffleBoundary(transferToInterpreterOnException = false)
static Object parse(String text) {
  if (text.isEmpty()) {
    throw new GuestError("empty");
  }
  return text;
}
```

### `TruffleSlowPathInline`

*Suggestion · Java, Kotlin.*

**Principle.** Message building after `transferToInterpreter*()` in a node method lives in a separate method.

**Why.** It never runs compiled, but host compilation still inlines it into the interpreter and grows the image.

**Fix.**

```java
if (value < 0) {
  CompilerDirectives.transferToInterpreter();
  throw new Failure("negative: " + value);        // flagged
}

if (value < 0) {
  CompilerDirectives.transferToInterpreter();
  throw failure(value);                           // good
}

@TruffleBoundary
static Failure failure(int value) { return new Failure("negative: " + value); }
```

### `TruffleBytecodeSwitch`

*Suggestion · Java, Kotlin.*

**Principle.** A loop that switches over elements of a `@CompilationFinal` array (a bytecode dispatch loop) is in a
method annotated `@HostCompilerDirectives.BytecodeInterpreterSwitch`.

**Why.** Host compilation optimizes annotated dispatch loops for the native image's interpreter.

**Fix.** Annotate the dispatch method. The rule recognizes the loop by shape, so an unusual dispatch loop may not
be flagged.

## Native Image

### `TruffleHostValueInImageHeap`

*Error · Java, Kotlin.* Active only when the policy says which classes initialize at build time
(`classinit.native-image` or `classinit.buildtime`).

**Principle.** A static initializer of a build-time-initialized class doesn't read build-host state: environment
variables, system properties, clocks, locale, time zone, CPU count or randomness. Creating a `Random`, a `Thread`
or a file stream there is flagged too.

**Why.** Native Image runs that initializer on the build machine and freezes the result into the image heap, so
every user gets the build machine's value.

**Fix.** Read the value lazily at run time, or initialize the class at run time. The check follows one hop into
constructors and methods in the same file, so a host read hidden deeper is not reported; trace those by hand.

```java
static final String HOME = System.getenv("HOME");                 // bad in a build-time class
static String home() { return System.getenv("HOME"); }            // good: read at run time
```

### `TruffleStaticContextState`

*Error · Java, Kotlin.*

**Principle.** No static field, Kotlin `object`, companion or top-level property holds an `Env`, `TruffleContext`,
the language instance, a `CallTarget`, an adoptable node, a `Thread` or a `ThreadLocal`.

**Why.** Statics are shared across contexts and engines, frozen into the image heap under build-time
initialization, and break context pre-initialization.

**Fix.** Use `ContextReference`, `LanguageReference`, `ContextLocal`/`ContextThreadLocal`, or an `UnadoptableNode`
for a shared node.

### `TruffleBlockingInNode`

*Error · Java, Kotlin.*

**Principle.** No `synchronized`, locks, `withLock`, `ThreadLocal`, `sleep`, `join`, `park`, `await` or
`Future.get` in compiled code.

**Why.** Lock methods are on the runtime-compilation blocklist, and blocking stalls safepoints.

**Fix.** Put it behind a `@TruffleBoundary`, and use `TruffleSafepoint.setBlocked` for guest-visible blocking.

### `TruffleFieldUpdaterConstant`

*Warning · Java, Kotlin.*

**Principle.** `Atomic*FieldUpdater.newUpdater` or an `Unsafe` offset directly initializes a `static final` field.

**Why.** Native Image recomputes offsets only in that shape; anything else bakes in the build JVM's offset. PE also
needs the updater to be a constant.

**Fix.**

```java
static final AtomicIntegerFieldUpdater<Counter> COUNT =
    AtomicIntegerFieldUpdater.newUpdater(Counter.class, "count");            // good

var updater = AtomicIntegerFieldUpdater.newUpdater(Counter.class, "count");  // bad: local
```

## API hygiene

### `TruffleHostInterop`

*Error · Java, Kotlin.* Active only when the policy sets `hostinterop.scope` to the guest-implementation packages.

**Principle.** Guest-implementation code doesn't reference `org.graalvm.polyglot` types (`Value`, `Context`,
`Proxy*`).

**Why.** The embedding API is for hosts. Guest code that goes through it leaves the Truffle interop protocol.

**Fix.** Use `InteropLibrary`, and export a `TruffleObject` with `@ExportLibrary`; or move the call to the embedding
boundary. Imports aren't reported, so a reviewed crossing can be suppressed on its own method. `SandboxPolicy` and
`ByteSequence` are allowed.

### `TruffleStackfulException`

*Error · Java, Kotlin.* Test sources are excluded by default.

**Principle.** Exceptions on runtime paths don't fill in a stack trace. Flagged: a JDK exception created with a
stack trace, a project exception type that extends one without opting out, and Kotlin's `require`, `check`, `error`
or `TODO`.

**Why.** Filling in a stack trace walks the stack, and `Throwable.fillInStackTrace` is blocklisted for compiled
code.

**Fix.** Extend a Truffle exception type (`AbstractTruffleException`, `ControlFlowException`), override
`fillInStackTrace`, or pass `writableStackTrace = false`. Suppress on a genuine diagnostic path where the trace is
the point.

```java
static final class Mine extends RuntimeException {          // bad
  Mine() { super("x"); }
}

static final class Stackless extends RuntimeException {     // good
  Stackless() { super("x", null, false, false); }
}
```

### `TruffleForbiddenCollection`

*Error · Java, Kotlin.*

**Principle.** Don't use `LinkedList`, `Vector`, `Hashtable`, `Stack` or `StringBuffer`.

**Why.** Each chases pointers or takes a lock on every operation, and all are blocklisted for compiled code.

**Fix.** Use an array-backed collection (`ArrayList`, `ArrayDeque`) or `StringBuilder`. Projects can extend the list
in the policy (`collections+=…`).

### `TruffleDeclaredType`

*Warning · Java, Kotlin.*

**Principle.** Don't use `TruffleObject` as the declared type of a field, parameter, local or return value.

**Why.** Interop dispatches through libraries on `Object`, so the marker type only adds casts and type checks in
compiled code.

**Fix.** Declare `Object`, or the concrete class. Implementing `TruffleObject` on a class is fine.

### `TruffleDeprecatedApi`

*Warning · Java, Kotlin.*

**Principle.** Don't use deprecated Truffle and GraalVM API.

**Why.** Deprecated Truffle API is removed in later releases; migrating early keeps upgrades small.

**Fix.** The finding carries the migration when the policy has one, for example `DynamicObjectLibrary` to the
`DynamicObject.GetNode`/`PutNode` nodes, `Env.createThread` to `newTruffleThreadBuilder`, and `Env.isHostObject` to
`InteropLibrary.isHostObject`.

### `TruffleDslSuppressions`

*Error · Java, Kotlin.*

**Principle.** No `@SuppressWarnings("truffle")` or `("all")` in Truffle code.

**Why.** The DSL's warnings are performance and footprint findings; a blanket suppression hides future ones.

**Fix.** Fix the warning, or suppress the specific key (`truffle-sharing`, `truffle-inlining`, `truffle-neverdefault`,
`truffle-limit`, `truffle-guard`, …).

### `TruffleLegacyProfile`

*Warning · Java, Kotlin.*

**Principle.** A DSL node doesn't hold `BranchProfile`, `ConditionProfile` or similar as fields.

**Why.** Inlined profiles live in the node's state bits rather than separate objects, cutting footprint and image
size.

**Fix.** Take the profile as a `@Cached Inlined*Profile` parameter of the specialization, with a `@Bind Node`
parameter to pass as the inlining target. Profile fields in hand-written (non-DSL) nodes aren't flagged.

## Kotlin only

### `TruffleKotlinNodeHazards`

*Error · Kotlin.*

**Principle.** Keep node state in plain fields Truffle understands, and keep fast paths free of hidden allocation.
Flagged in nodes: delegated properties such as `by lazy`; `@Child val`; spread arguments in compiled code.

**Why.** A delegate adds an object, a lock and a lambda to each read. `@Child val` is a final field, which Truffle
rejects. A spread copies an array per call.

**Fix.**

```kotlin
class Bad(@Child val fixed: Leaf) : Node() {       // flagged: @Child val
  val computed: Int by lazy { 42 }                 // flagged: delegate
  fun spread(values: IntArray) = sum(*values)      // flagged: spread
}

class Good : Node() {
  @Child var adopted: Leaf? = null
}
```

### `TruffleAnalysisError`

*Error · Kotlin.*

**Principle.** detekt must fully resolve the sources it analyzes.

**Why.** Unresolved code hides findings, so a clean report over unresolved sources means nothing.

**Fix.** Fix the analysis classpath (`--classpath`, `--analysis-mode full`) rather than suppressing the rule.
