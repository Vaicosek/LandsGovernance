#!/usr/bin/env bash
# Linux host: clone/update and build. Minecraft deployment is explicit.
set -euo pipefail
umask 077
repo="${1:?Usage: bash install.sh git@github.com:OWNER/REPO.git /absolute/checkout [branch]}"
app="${2:?Pass an absolute checkout directory}"
branch="${3:-main}"
[[ "$repo" =~ ^git@github.com:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.git$ || "$repo" =~ ^https://github.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.git$ ]] || { echo 'Use a credential-free GitHub SSH or HTTPS URL.' >&2; exit 1; }
[[ "$app" == /* && "$app" != / && "$app" != *$'\n'* ]] || { echo 'Use an absolute checkout directory, not /.' >&2; exit 1; }
[[ "$branch" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] || exit 1
for cmd in git java javac flock; do command -v "$cmd" >/dev/null || { echo "Missing prerequisite: $cmd" >&2; exit 1; }; done
java -version 2>&1 | head -n 1 | grep -Eq 'version "25([."]|$)' || { echo 'Select JDK 25 with JAVA_HOME and PATH.' >&2; exit 1; }
exec 9>"/tmp/cities-install-$(id -u).lock"
flock -n 9 || { echo 'Another installation is running.' >&2; exit 1; }
if [[ -d "$app/.git" ]]; then
  [[ "$(git -C "$app" remote get-url origin)" == "$repo" ]] || { echo 'Origin differs.' >&2; exit 1; }
  [[ "$(git -C "$app" branch --show-current)" == "$branch" ]] || { echo 'Branch differs.' >&2; exit 1; }
  [[ -z "$(git -C "$app" status --porcelain)" ]] || { echo 'Resolve local changes first.' >&2; exit 1; }
  git -C "$app" pull --ff-only origin "$branch"
else
  [[ ! -e "$app" ]] || { echo 'Destination exists without a checkout.' >&2; exit 1; }
  git clone --branch "$branch" --single-branch "$repo" "$app"
fi
cd "$app"
if [[ ! -e .env ]]; then install -m 0600 .env.example .env; fi
wrapper_mode=$(stat -c %a gradlew)
trap 'chmod "$wrapper_mode" gradlew' EXIT
chmod u+x gradlew
./gradlew build
test -s build/libs/cities-0.2.0.jar
printf '%s\n' "Built: $app/build/libs/cities-0.2.0.jar" 'Back up and stop Minecraft, replace its old Cities jar in plugins/, then start Minecraft.'
