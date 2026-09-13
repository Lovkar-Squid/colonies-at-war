#!/usr/bin/env bash
# Colonist Thieves - plain javac against the real mod jars, then jar. No Gradle.
set -e
cd "$(dirname "$0")"
VERSION=$(grep -m1 '^version=' resources/META-INF/neoforge.mods.toml | cut -d'"' -f2)
rm -rf build && mkdir -p build
javac -encoding UTF-8 --release 21 -proc:none -nowarn \
      -cp "stubs:libs/*" -d build $(find src -name '*.java')
jar cf "colonies_at_war-${VERSION}.jar" -C build . -C resources .
echo "built colonies_at_war-${VERSION}.jar"
