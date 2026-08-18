#!/usr/bin/env bash
# Build indosource.jar — plugin Usagi (tsuki-style) dari src/.
#
# Pipeline: Kotlin (kotlinc 2.2.10) -> fat jar -> d8 (min API 24) -> classes.dex -> indosource.jar
#
# Prasyarat: bash, curl, unzip, zip, JDK 17+ (java di PATH).
# Semua dependency diunduh otomatis ke ./deps (cukup sekali).
set -euo pipefail
cd "$(dirname "$0")"

KOTLIN_VERSION=2.2.10
TSUKI_VERSION=1.0.5
DEP_DIR=deps
OUT_DIR=out
ROOT_DIR=jarroot
DEX_DIR=dexout
MIN_API=24

mkdir -p "$DEP_DIR"

download() {
  local url="$1" file="$2"
  if [[ ! -f "$file" ]]; then
    echo "== download $file"
    curl -fsSL -o "$file" "$url"
  fi
}

# --- 1) dependency (Maven Central / Google Maven / Jitpack) ---
download "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" "$DEP_DIR/kotlin-compiler.jar"
download "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar" "$DEP_DIR/kotlin-stdlib.jar"
download "https://dl.google.com/android/maven2/com/android/tools/r8/8.13.22/r8-8.13.22.jar" "$DEP_DIR/r8.jar"
download "https://repo1.maven.org/maven2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar" "$DEP_DIR/annotations.jar"
download "https://jitpack.io/io/github/UsagiApp/Tsuki/$TSUKI_VERSION/Tsuki-$TSUKI_VERSION.jar" "$DEP_DIR/tsuki-$TSUKI_VERSION.jar"
download "https://repo1.maven.org/maven2/org/jsoup/jsoup/1.17.2/jsoup-1.17.2.jar" "$DEP_DIR/jsoup.jar"
download "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-jvm/5.3.2/okhttp-jvm-5.3.2.jar" "$DEP_DIR/okhttp.jar"
download "https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.17.0/okio-jvm-3.17.0.jar" "$DEP_DIR/okio.jar"
download "https://repo1.maven.org/maven2/org/json/json/20251224/json-20251224.jar" "$DEP_DIR/json.jar"
download "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar" "$DEP_DIR/coroutines.jar"
download "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.11.0/kotlinx-serialization-json-jvm-1.11.0.jar" "$DEP_DIR/serialization-json.jar"
download "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.11.0/kotlinx-serialization-core-jvm-1.11.0.jar" "$DEP_DIR/serialization-core.jar"
download "https://dl.google.com/android/maven2/androidx/collection/collection-jvm/1.6.0/collection-jvm-1.6.0.jar" "$DEP_DIR/collection.jar"

COMPILE_CP="$DEP_DIR/kotlin-stdlib.jar:$DEP_DIR/tsuki-$TSUKI_VERSION.jar:$DEP_DIR/jsoup.jar:$DEP_DIR/okhttp.jar:$DEP_DIR/okio.jar:$DEP_DIR/json.jar:$DEP_DIR/coroutines.jar:$DEP_DIR/serialization-json.jar:$DEP_DIR/serialization-core.jar:$DEP_DIR/collection.jar"

# --- 2) compile Kotlin -> kelas JVM (jvm-target 17, cocok dengan app Usagi) ---
rm -rf "$OUT_DIR"
java -Xmx3g -cp "$DEP_DIR/kotlin-compiler.jar:$DEP_DIR/kotlin-stdlib.jar:$DEP_DIR/coroutines.jar:$DEP_DIR/annotations.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -opt-in=tsuki.InternalParsersApi \
  -classpath "$COMPILE_CP" -jvm-target 17 -d "$OUT_DIR" \
  src/*.kt plugin/tsuki/model/MangaParserSource.kt plugin/tsuki/MangaParserFactory.kt

# --- 3) fat jar: parser + tsuki framework + semua dependency ---
rm -rf "$ROOT_DIR"
mkdir -p "$ROOT_DIR"
for j in "$DEP_DIR"/*.jar; do
  case "$j" in
    *kotlin-compiler.jar|*r8.jar|*annotations.jar) continue ;; # tooling, bukan runtime
  esac
  unzip -oq "$j" -d "$ROOT_DIR" 2>/dev/null || true
done
rm -f "$ROOT_DIR"/META-INF/*.SF "$ROOT_DIR"/META-INF/*.DSA "$ROOT_DIR"/META-INF/*.RSA
cp -r "$OUT_DIR"/* "$ROOT_DIR"/
rm -f fatjar.jar
(cd "$ROOT_DIR" && zip -qr ../fatjar.jar .)

# --- 4) dex (d8, min API 24) ---
rm -rf "$DEX_DIR"
mkdir -p "$DEX_DIR"
java -Xmx3g -cp "$DEP_DIR/r8.jar" com.android.tools.r8.D8 \
  --release --min-api "$MIN_API" --output "$DEX_DIR" fatjar.jar

# --- 5) bungkus: indosource.jar = zip berisi classes.dex (format plugin Usagi) ---
rm -f indosource.jar
(cd "$DEX_DIR" && zip -q ../indosource.jar classes.dex)

# --- 6) verifikasi ---
echo "== indosource.jar =="
unzip -l indosource.jar
if unzip -l indosource.jar | grep -qE '^.{1,}META-INF/MANIFEST'; then
  echo "WARNING: jar berisi MANIFEST.MF — plugin loader membutuhkan entry tunggal classes.dex." >&2
fi
echo "Build selesai: $(ls -la indosource.jar | awk '{print $5}') bytes"
