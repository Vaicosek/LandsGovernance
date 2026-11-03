#!/usr/bin/env bash
# Build an existing source checkout and copy its jar to an explicit destination.
# Stop Minecraft and back up its plugin data before copying into plugins/.
set -euo pipefail
[[ $# -eq 1 ]] || { echo 'Usage: bash install.sh /path/to/plugins-or-upload-directory' >&2; exit 1; }
destination=$(cd -- "$1" && pwd)
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
for cmd in java javac; do
  command -v "$cmd" >/dev/null || { echo "Missing prerequisite: $cmd (JDK 25 required)" >&2; exit 1; }
done
# Gradle's configured Java toolchain requires JDK 25.
bash ./gradlew --no-daemon build
jar=build/libs/cities-0.2.0.jar
[[ -s "$jar" ]] || { echo "Missing build output: $jar" >&2; exit 1; }
cp -- "$jar" "$destination/"
printf 'Copied %s to %s/\n' "$jar" "$destination"
