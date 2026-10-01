#!/usr/bin/env sh
# Runs enSSP-J's own unit and end-to-end tests without Maven.
# Requires JUnit 4.13.2 and Hamcrest 1.3 jars: JUNIT_CP=/path/junit-4.13.2.jar:/path/hamcrest-core-1.3.jar ./test.sh
# (Maven users: mvn test)
set -e
cd "$(dirname "$0")"
: "${JUNIT_CP:?set JUNIT_CP to the JUnit 4 and Hamcrest jars}"
./build.sh
rm -rf target/test-classes && mkdir -p target/test-classes
javac -nowarn --release 11 -cp "target/classes:$JUNIT_CP" -d target/test-classes $(find src/test/java -name "*.java")
java -cp "target/classes:target/test-classes:$JUNIT_CP" org.junit.runner.JUnitCore enssp.CoreTest enssp.EndToEndTest
