# Setting up trufflecheck

The authoritative instructions, with current versions, are in the
[trufflecheck README](https://github.com/elide-dev/trufflecheck#use-the-rules). This file is the short version; if
it disagrees with the README, the README wins.

## Artifacts

Maven repository `https://maven.elide.dev`:

- `dev.elide:trufflecheck-errorprone`: Error Prone checks for Java;
- `dev.elide:trufflecheck-detekt`: detekt rule set `Truffle` for Kotlin;
- `dev.elide:trufflecheck-policy`: the shared policy (included in both of the above).

The same JARs are attached to GitHub releases. Projects that vendor the repository as a git submodule
(conventionally `third_party/trufflecheck`) also get `profiles/` to extend from.

## Wiring

- **Error Prone:** put `trufflecheck-errorprone` on the annotation-processor path beside Error Prone and enable
  checks with `-Xep:<Rule>:ERROR` (or the Gradle plugin's `error(…)`). The full flag list, one per line, is
  `profiles/jvm/errorprone.args`.
- **detekt:** pass the JAR with `--plugins` (or Gradle `detektPlugins(…)`), use `profiles/jvm/detekt.yaml` as the
  config, and run with `--analysis-mode full` and a `--classpath`. The rules need resolved types; without a classpath
  they report `TruffleAnalysisError` instead of silently missing things.

## Policy file

Some rules need project facts and stay inactive without them:

```properties
# trufflecheck.properties (paths are relative to this file)
# Packages holding guest code; activates TruffleHostInterop.
hostinterop.scope=com.example.mylang
# Which classes initialize at build time; activates TruffleHostValueInImageHeap.
classinit.native-image=src/main/resources
# or explicit lists, one class or package prefix per line:
classinit.buildtime=tools/classinit/buildtime.txt
# `key+=` appends to a default list instead of replacing it:
collections+=java.util.concurrent.CopyOnWriteArrayList
```

The rules find the file through, in order: the checker's own option (`-XepOpt:Trufflecheck:Policy=…` for Error
Prone, `policy:` under `Truffle:` for detekt), the `trufflecheck.policy` system property, then the
`TRUFFLECHECK_POLICY` environment variable.

Every default, including the call lists behind each rule, is in `policy.properties` inside the policy JAR
(`META-INF/trufflecheck/policy.properties`). Any rule can skip paths with `<rule>.exclude=<regex>`, where `<rule>`
is the policy key prefix used in that file (for example `stackful.exclude`).
