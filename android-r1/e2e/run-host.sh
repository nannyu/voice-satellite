#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?JAVA_HOME must point to a JDK}"
BUILD="$ROOT/android-r1/jvm-tests/target/host-e2e"
mkdir -p "$BUILD"
mvn --batch-mode --no-transfer-progress -f android-r1/jvm-tests/pom.xml test \
  org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath \
  -Dmdep.outputFile=target/runtime-classpath.txt
CP="$ROOT/android-r1/jvm-tests/target/classes:$(cat android-r1/jvm-tests/target/runtime-classpath.txt)"
g++ -std=c++11 -O2 -fPIC -shared -Wall -Wextra \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" $(pkg-config --cflags opus) \
  android-r1/app/src/main/cpp/opus_jni.cpp $(pkg-config --libs opus) -o "$BUILD/libvoice_sat_native.so"
javac --release 8 -cp "$CP" -d "$BUILD" android-r1/e2e/OpusGatewayE2E.java
SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT
for mode in echo tone; do
  python -u gateway/server.py --host 127.0.0.1 --port 8765 --mode "$mode" > "$BUILD/gateway-$mode.log" 2>&1 &
  SERVER_PID=$!
  for attempt in $(seq 1 100); do
    grep -q 'LISTENING' "$BUILD/gateway-$mode.log" && break
    kill -0 "$SERVER_PID" 2>/dev/null || { cat "$BUILD/gateway-$mode.log"; exit 1; }
    sleep 0.1
  done
  grep -q 'LISTENING' "$BUILD/gateway-$mode.log" || { cat "$BUILD/gateway-$mode.log"; exit 1; }
  java -Djava.library.path="$BUILD" -cp "$BUILD:$CP" OpusGatewayE2E "ws://127.0.0.1:8765" "$mode" \
    | tee "$BUILD/result-$mode.txt"
  cleanup
  SERVER_PID=""
done
