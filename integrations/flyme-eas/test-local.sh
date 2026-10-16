#!/bin/sh
# Run the Android-independent lifecycle and protocol tests without a Gradle daemon.
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cache="${GRADLE_DEPENDENCY_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
junit=$(find "$cache/junit/junit/4.13.2" -name '*.jar' -print -quit)
hamcrest=$(find "$cache/org.hamcrest/hamcrest-core/1.3" -name '*.jar' -print -quit)
output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT
source="$repo/integrations/flyme-eas/src"
javac --release 11 -cp "$junit" -d "$output" \
 "$source/main/java/com/shilapi/flymeeas/SessionGate.java" \
 "$source/main/java/com/shilapi/flymeeas/EasProtocol.java" \
 "$source/main/java/com/shilapi/flymeeas/KeyPairs.java" \
 "$source/test/java/com/shilapi/flymeeas/SessionGateTest.java" \
 "$source/test/java/com/shilapi/flymeeas/EasProtocolTest.java" \
 "$source/test/java/com/shilapi/flymeeas/KeyPairsTest.java"
java -cp "$output:$junit:$hamcrest" org.junit.runner.JUnitCore \
 com.shilapi.flymeeas.SessionGateTest com.shilapi.flymeeas.EasProtocolTest com.shilapi.flymeeas.KeyPairsTest
