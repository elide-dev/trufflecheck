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

# coordinate|sha256: the same pins elide-dev/runtime uses (tools/toolchains/jvm-checkers.mts).
pins=(
  "com.google.errorprone:error_prone_core:2.51.0:with-dependencies|9a6aee2aa413500ac9314bfb22a30b6a4992aeb1156ddfd5f8d5c3dc280f1af1"
  "io.github.eisop:dataflow-errorprone:3.41.0-eisop1|10434fba4e53f55fa9c76904cde414b918932548c9dfc4e2d634ac05ff7a7d10"
  "dev.detekt:detekt-cli:2.0.0-alpha.6:all|d46ca62ea4d62769b5d5c3ba94d49fa9b80ba11c7dba74ddb6df7fcc2c19c5fd"
  # detekt's bundled Kotlin; the fixtures' analysis classpath needs its stdlib.
  "org.jetbrains.kotlin:kotlin-stdlib:2.4.10|4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df"
)

fetch() {
  local coordinate="${1%%|*}" sha="${1##*|}" group artifact version classifier
  IFS=: read -r group artifact version classifier <<<"$coordinate"
  local file="$artifact-$version${classifier:+-$classifier}.jar"
  local path="$cache/$file"
  if [[ ! -f "$path" ]]; then
    mkdir -p "$cache"
    curl -fsSL -o "$path.part" \
      "https://repo1.maven.org/maven2/${group//.//}/$artifact/$version/$file"
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

jar() {
  local path="$root/$1/.dev/artifacts/jar/trufflecheck-$1/trufflecheck-$1.jar"
  [[ -f "$path" ]] || { echo "missing $path; run \`elide build\` first" >&2; exit 1; }
  echo "$path"
}
ep_rules="$(jar errorprone)"
detekt_rules="$(jar detekt)"

# detekt loads plugins with its bundled Kotlin 2.4, so the rule set must carry 2.4 metadata. Elide's caches can serve
# output built under other compiler options (elide-dev/runtime#3010); rebuild with `rm -rf detekt/.dev && elide
# build --no-cache` if this trips.
mkdir -p "$work/meta"
unzip -q -o -d "$work/meta" "$detekt_rules" dev/elide/trufflecheck/detekt/TruffleRules.class
metadata="$("${bin}javap" -v "$work/meta/dev/elide/trufflecheck/detekt/TruffleRules.class" | grep -m1 -o 'mv=\[[0-9,]*\]')"
if [[ "$metadata" != "mv=[2,4,0]" ]]; then
  echo "FAIL detekt rule JAR has Kotlin metadata $metadata; detekt needs mv=[2,4,0]" >&2
  exit 1
fi

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

# Stubs: compiled once, on the classpath of both analyzers.
mkdir -p "$work/stubs"
"${bin}javac" -d "$work/stubs" $(find "$tests/stubs" -name '*.java')

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
    -cp "$work/stubs" -d "$work/java" -Xmaxerrs 1000 $(find java -name '*.java' | sort) \
    >"$work/errorprone.log" 2>&1 || true
)
grep -oE '^[^ :]+\.java:[0-9]+: error: \[[A-Za-z]+\]' "$work/errorprone.log" |
  sed -E 's/^([^:]+):([0-9]+): error: \[(.*)\]$/\1:\2:\3/' | sort >"$work/ep.got" || true
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
    --input kotlin --classpath "$work/stubs:$stdlib" --jvm-target 21 \
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
