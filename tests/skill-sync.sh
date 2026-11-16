#!/usr/bin/env bash
# Keeps the agent skill in step with the rules. Fails when a rule id from the Error Prone service file or the detekt
# rule set has no `### \`Rule\`` section in the skill's rule reference, when a section names a rule that doesn't exist,
# or when any skill file mentions a Truffle* identifier that is neither a rule id nor a known Truffle API name.
set -euo pipefail
export LC_ALL=C

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
skill="$root/plugins/trufflecheck/skills/truffle-language"
reference="$skill/references/rules.md"

# Truffle API names the skill may mention that aren't rule ids.
api="TruffleBoundary TruffleObject TruffleSafepoint TruffleLanguage TruffleContext TruffleFeature"

rules="$(
  {
    sed 's/.*\.//' "$root/errorprone/src/main/resources/META-INF/services/com.google.errorprone.bugpatterns.BugChecker"
    sed -n '/override fun instance()/,/^  *)$/p' "$root/detekt/src/main/kotlin/dev/elide/trufflecheck/detekt/TruffleRuleSet.kt" |
      sed -n 's/^ *::\([A-Za-z]*\),.*/\1/p'
  } | grep . | sort -u
)"
[[ -n "$rules" ]] || { echo "FAIL skill sync: found no rule ids" >&2; exit 1; }

failed=0
sections="$(sed -n 's/^### `\([A-Za-z]*\)`$/\1/p' "$reference" | sort)"
duplicates="$(uniq -d <<<"$sections")"
missing="$(comm -23 <(echo "$rules") <(sort -u <<<"$sections"))"
unknown="$(comm -13 <(echo "$rules") <(sort -u <<<"$sections"))"
mentioned="$(grep -rhoE --include='*.md' '\bTruffle[A-Z][A-Za-z]+' "$skill" | sort -u)"
stray="$(comm -23 <(echo "$mentioned") <({ echo "$rules"; tr ' ' '\n' <<<"$api"; } | sort -u))"

report() {
  [[ -z "$2" ]] && return
  echo "FAIL skill sync: $1"
  sed 's/^/       /' <<<"$2"
  failed=1
}
report "rules with no section in ${reference#"$root"/}" "$missing"
report "sections for rules that don't exist" "$unknown"
report "rules with more than one section" "$duplicates"
report "unknown Truffle* names in the skill (not a rule id; add real API names to \`api\` in tests/skill-sync.sh)" "$stray"

# Agent Skills format: the frontmatter name must match the directory.
name="$(sed -n '2,/^---$/s/^name: *//p' "$skill/SKILL.md")"
[[ "$name" == "$(basename "$skill")" ]] || report "SKILL.md name doesn't match its directory" "${name:-<none>}"

[[ "$failed" == 0 ]] && echo "ok   skill sync ($(wc -l <<<"$rules") rules)"
exit "$failed"
