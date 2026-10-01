#!/usr/bin/env sh
# Downloads the third-party tools used by experiments/run_experiments.py into ./tools
# (none of them is needed to *use* enSSP-J). Requires curl and unzip.
set -e
cd "$(dirname "$0")"
T=tools
mkdir -p "$T"
MC=https://repo1.maven.org/maven2
get() { [ -f "$T/$2" ] || curl -fsSL -o "$T/$2" "$1"; echo "  $2"; }

echo "Downloading PIT 1.17.0 and dependencies"
get $MC/org/pitest/pitest/1.17.0/pitest-1.17.0.jar pitest-1.17.0.jar
get $MC/org/pitest/pitest-entry/1.17.0/pitest-entry-1.17.0.jar pitest-entry-1.17.0.jar
get $MC/org/pitest/pitest-command-line/1.17.0/pitest-command-line-1.17.0.jar pitest-command-line-1.17.0.jar
for a in asm asm-commons asm-tree asm-util asm-analysis; do
  get $MC/org/ow2/asm/$a/9.7/$a-9.7.jar $a-9.7.jar
done
get $MC/org/apache/commons/commons-text/1.11.0/commons-text-1.11.0.jar commons-text-1.11.0.jar
get $MC/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar commons-lang3-3.14.0.jar
get $MC/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar jopt-simple-5.0.4.jar

echo "Downloading JUnit 4.13.2 and Hamcrest 1.3"
get $MC/junit/junit/4.13.2/junit-4.13.2.jar junit-4.13.2.jar
get $MC/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar hamcrest-core-1.3.jar

echo "Downloading JaCoCo 0.8.12"
get https://github.com/jacoco/jacoco/releases/download/v0.8.12/jacoco-0.8.12.zip jacoco-0.8.12.zip
(cd "$T" && unzip -q -o jacoco-0.8.12.zip lib/jacocoagent.jar lib/jacococli.jar)

echo "Downloading EvoSuite 1.2.0 (baseline; runs on Java 8)"
get https://github.com/EvoSuite/evosuite/releases/download/v1.2.0/evosuite-1.2.0.jar evosuite-1.2.0.jar

echo "Done. Tools are in $(pwd)/$T"
