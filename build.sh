#!/usr/bin/env sh
# Builds enSSP-J without Maven (JDK 11+). Maven users can run: mvn package
set -e
cd "$(dirname "$0")"
rm -rf target/classes && mkdir -p target/classes
javac -Xlint:all -Xlint:-serial --release 11 -d target/classes $(find src/main/java -name "*.java")
printf "Main-Class: enssp.Main\n" > target/MANIFEST.MF
jar cfm target/enssp-j-1.0.0.jar target/MANIFEST.MF -C target/classes .
echo "Built target/enssp-j-1.0.0.jar"
