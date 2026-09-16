#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output="$script_dir/build/safety-policy-test"

mkdir -p "$output"
javac -encoding UTF-8 -d "$output" \
    "$script_dir/app/src/main/java/dev/indevelopment/m3qroot/RootSafetyPolicy.java" \
    "$script_dir/app/src/main/java/dev/indevelopment/m3qroot/LogRedactor.java" \
    "$script_dir/app/src/main/java/dev/indevelopment/m3qroot/VersionCompare.java" \
    "$script_dir/tests/RootSafetyPolicyTest.java" \
    "$script_dir/tests/LogRedactorTest.java" \
    "$script_dir/tests/VersionCompareTest.java"

java -cp "$output" dev.indevelopment.m3qroot.RootSafetyPolicyTest
java -cp "$output" dev.indevelopment.m3qroot.LogRedactorTest
java -cp "$output" dev.indevelopment.m3qroot.VersionCompareTest
