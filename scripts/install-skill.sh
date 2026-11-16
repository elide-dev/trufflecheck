#!/usr/bin/env bash
# Links the truffle-language agent skill from this checkout into a project, for projects that vendor trufflecheck as a
# git submodule (e.g. third_party/trufflecheck). The link is relative, so it's safe to commit, and the skill updates
# whenever the submodule does. Safe to run repeatedly.
#
#   third_party/trufflecheck/scripts/install-skill.sh [--agents] [project-root]
#
# project-root defaults to the git superproject containing this checkout, else the current git repository. Links into
# .claude/skills/ (Claude Code); --agents also links into .agents/skills/, which other Agent Skills clients read.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
name=truffle-language
source="$here/plugins/trufflecheck/skills/$name"
[[ -f "$source/SKILL.md" ]] || { echo "install-skill: $source/SKILL.md not found" >&2; exit 1; }

targets=(.claude/skills)
project=""
for arg in "$@"; do
  case "$arg" in
    --agents) targets+=(.agents/skills) ;;
    -h | --help) sed -n '2,10s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) echo "install-skill: unknown option $arg" >&2; exit 2 ;;
    *) project="$arg" ;;
  esac
done
if [[ -z "$project" ]]; then
  project="$(git -C "$here" rev-parse --show-superproject-working-tree 2>/dev/null || true)"
  [[ -n "$project" ]] || project="$(git rev-parse --show-toplevel 2>/dev/null || true)"
  [[ -n "$project" ]] || { echo "install-skill: pass the project root" >&2; exit 2; }
fi
project="$(cd "$project" && pwd -P)"
[[ "$project" != "$here" ]] || { echo "install-skill: run this for a project that vendors trufflecheck, not trufflecheck itself" >&2; exit 2; }

for target in "${targets[@]}"; do
  dir="$project/$target"
  link="$dir/$name"
  mkdir -p "$dir"
  relative="$(realpath --relative-to="$(cd "$dir" && pwd -P)" "$source" 2>/dev/null ||
    python3 -I -c 'import os, sys; print(os.path.relpath(sys.argv[1], os.path.realpath(sys.argv[2])))' "$source" "$dir")"
  if [[ -L "$link" ]]; then
    if [[ "$(readlink "$link")" == "$relative" ]]; then
      echo "ok      $target/$name"
      continue
    fi
    rm "$link"
  elif [[ -e "$link" ]]; then
    echo "install-skill: $link exists and isn't a symlink; move it away first" >&2
    exit 1
  fi
  ln -s "$relative" "$link"
  echo "linked  $target/$name -> $relative"
done
