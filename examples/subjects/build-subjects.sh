#!/usr/bin/env sh
# Builds the subject classes used in the illustrative example into ./classes.
#  - src/: container classes from Symbolic PathFinder (Apache-2.0), JPF-specific code removed
#  - Apache Commons Collections 4.4 (Apache-2.0), unpacked from the official release jar
# Usage: ./build-subjects.sh [path/to/commons-collections4-4.4.jar]
# If no jar is given it is downloaded from Maven Central.
set -e
cd "$(dirname "$0")"
JAR="${1:-lib/commons-collections4-4.4.jar}"
if [ ! -f "$JAR" ]; then
  mkdir -p lib
  curl -fsSL -o "$JAR" https://repo1.maven.org/maven2/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar
fi
rm -rf classes && mkdir -p classes
(cd classes && jar xf "$(cd .. && cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")" && rm -rf META-INF)
javac -nowarn --release 8 -d classes $(find src -name "*.java")
echo "Subjects built in $(pwd)/classes"
