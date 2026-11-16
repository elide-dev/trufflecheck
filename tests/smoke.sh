#!/usr/bin/env bash
# End-to-end check of the built rule JARs: runs real javac + Error Prone and the detekt CLI over the fixtures and
# compares every finding against the `// expect: Rule` markers. Exits non-zero on any missing or unexpected finding.
#
# Requires: a JDK 21+ `java`/`javac` on PATH (or JAVA_HOME), curl, and `elide build` having produced the JARs.
# Analyzer JARs are pinned below and cached in $TRUFFLECHECK_CACHE (default: $XDG_CACHE_HOME/trufflecheck).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tests="$root/tests"
cache="${TRUFFLECHECK_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/trufflecheck}"
bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# coordinate|sha256[|repository]: analyzers use the same pins as elide-dev/runtime
# (tools/toolchains/jvm-checkers.mts); Truffle is Elide's GraalVM fork, as the consuming projects pin it.
central=https://repo1.maven.org/maven2
graalvm=https://maven.elide.dev/gvm-elide/25.5.5-elide.28
pins=(
  "com.google.errorprone:error_prone_core:2.51.0:with-dependencies|9a6aee2aa413500ac9314bfb22a30b6a4992aeb1156ddfd5f8d5c3dc280f1af1"
  "io.github.eisop:dataflow-errorprone:3.41.0-eisop1|10434fba4e53f55fa9c76904cde414b918932548c9dfc4e2d634ac05ff7a7d10"
  "dev.detekt:detekt-cli:2.0.0-alpha.6:all|d46ca62ea4d62769b5d5c3ba94d49fa9b80ba11c7dba74ddb6df7fcc2c19c5fd"
  # The fixtures' analysis classpath needs a stdlib; matches the Kotlin the rules build with.
  "org.jetbrains.kotlin:kotlin-stdlib:2.5.0-RC|aa94e6f417847322aa58d9fbec93278b74010f3cf593cf2038bd865e1ef42c3f"
)
truffle_pins=(
  "org.graalvm.truffle:truffle-api:25.5.5-elide.28|8d4a1366a2fa828bd1bc9dc7222b21d49e78961bb115417fd347cd29c0186442|$graalvm"
  "org.graalvm.polyglot:polyglot:25.5.5-elide.28|c47e0a861d8a045db7c4116d7bd62b8bc97fee5d1f0a4dbcdd02b3330094f4c4|$graalvm"
  "org.graalvm.sdk:collections:25.5.5-elide.28|a86ac0d32318395b4af7a4e445afd7af0111194c4a12fc40ae75fc56023c0164|$graalvm"
  "org.graalvm.sdk:nativeimage:25.5.5-elide.28|461477057c380d3551d004641708733722ed7e09535d75043c8122a6aa683cdd|$graalvm"
  "org.graalvm.sdk:word:25.5.5-elide.28|05261758e6a95c7e9820558fd2ac26c68c63caab844dab639140606cf4f78a4d|$graalvm"
)

fetch() {
  local coordinate sha repository group artifact version classifier
  IFS='|' read -r coordinate sha repository <<<"$1"
  repository="${repository:-$central}"
  IFS=: read -r group artifact version classifier <<<"$coordinate"
  local file="$artifact-$version${classifier:+-$classifier}.jar"
  local path="$cache/$file"
  if [[ ! -f "$path" ]]; then
    mkdir -p "$cache"
    curl -fsSL -o "$path.part" \
      "$repository/${group//.//}/$artifact/$version/$file"
    mv "$path.part" "$path"
  fi
  if ! echo "$sha  $path" | sha256sum -c --status; then
    echo "checksum mismatch: $path" >&2
    rm -f "$path"
    return 1
  fi
  echo "$path"
}

errorprone="$(fetch "${pins[0]}")"
dataflow="$(fetch "${pins[1]}")"
detekt="$(fetch "${pins[2]}")"
stdlib="$(fetch "${pins[3]}")"
truffle=""
for pin in "${truffle_pins[@]}"; do truffle+="${truffle:+:}$(fetch "$pin")"; done

jar() {
  local path="$root/$1/.dev/artifacts/jar/trufflecheck-$1/trufflecheck-$1.jar"
  [[ -f "$path" ]] || { echo "missing $path; run \`elide build\` first" >&2; exit 1; }
  echo "$path"
}
ep_rules="$(jar errorprone)"
detekt_rules="$(jar detekt)"


# Expected findings: "<file>:<line>:<rule>" for each marker.
expected() {
  (cd "$tests" && grep -rn --include="*.$1" -o 'expect: [A-Za-z]*' "$2") |
    sed -E 's/^([^:]*):([0-9]+):expect: (.*)$/\1:\2:\3/' | sort
}

compare() {
  local label="$1" want="$2" got="$3"
  if diff -u "$want" "$got" >"$work/diff"; then
    echo "ok   $label ($(wc -l <"$got") findings)"
  else
    echo "FAIL $label (- expected, + actual)"
    sed '1,2d' "$work/diff"
    failed=1
  fi
}
failed=0

# Error Prone: only trufflecheck rules, with the overlay passed as an Error Prone option.
exports=()
for part in api code comp file main model parser processing tree util; do
  exports+=("-J--add-exports=jdk.compiler/com.sun.tools.javac.$part=ALL-UNNAMED")
done
exports+=("-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"
  "-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED")
checks="-XepDisableAllChecks"
for rule in $(sed 's/.*\.//' "$root/errorprone/src/main/resources/META-INF/services/com.google.errorprone.bugpatterns.BugChecker"); do
  checks+=" -Xep:$rule:ERROR"
done
(
  cd "$tests"
  "${bin}javac" "${exports[@]}" -XDcompilePolicy=simple --should-stop=ifError=FLOW \
    -processorpath "$errorprone:$dataflow:$ep_rules" \
    "-Xplugin:ErrorProne $checks -XepOpt:Trufflecheck:Policy=$tests/policy.properties" \
    -proc:none -cp "$truffle" -d "$work/java" -Xmaxerrs 1000 $(find java -name '*.java' | sort) \
    >"$work/errorprone.log" 2>&1 || true
)
grep -oE '^[^ :]+\.java:[0-9]+: (error|warning): \[[A-Za-z]+\]' "$work/errorprone.log" |
  sed -E 's/^([^:]+):([0-9]+): (error|warning): \[(.*)\]$/\1:\2:\4/' | sort >"$work/ep.got" || true
expected java java >"$work/ep.want"
if [[ ! -s "$work/ep.got" ]]; then
  cat "$work/errorprone.log" >&2
fi
compare "error prone" "$work/ep.want" "$work/ep.got"

# detekt: the shipped profile, with the overlay passed as a system property.
(
  cd "$tests"
  "${bin}java" -Dtrufflecheck.policy="$tests/policy.properties" \
    --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
    -jar "$detekt" --analysis-mode full --base-path "$tests" \
    --config "$root/profiles/jvm/detekt.yaml" --plugins "$detekt_rules" \
    --input kotlin --classpath "$truffle:$stdlib" --jvm-target 21 \
    --report "sarif:$work/detekt.sarif" >"$work/detekt.log" 2>&1 || true
)
if [[ ! -f "$work/detekt.sarif" ]]; then
  cat "$work/detekt.log" >&2
  echo "FAIL detekt produced no report" >&2
  exit 1
fi
# SARIF results to "<file>:<line>:<rule>", trufflecheck rules only (rule ids are `detekt.<RuleSet>.<Rule>`).
python3 -I -c '
import json, sys
for run in json.load(open(sys.argv[1]))["runs"]:
    for result in run.get("results", []):
        rule = result["ruleId"].rsplit(".", 1)[-1]
        if not rule.startswith("Truffle"):
            continue
        location = result["locations"][0]["physicalLocation"]
        uri = location["artifactLocation"]["uri"].removeprefix("file://").removeprefix(sys.argv[2] + "/")
        line = location["region"]["startLine"]
        print(f"{uri}:{line}:{rule}")
' "$work/detekt.sarif" "$tests" | sort >"$work/detekt.got"
expected kt kotlin >"$work/detekt.want"
compare "detekt" "$work/detekt.want" "$work/detekt.got"

exit "$failed"
