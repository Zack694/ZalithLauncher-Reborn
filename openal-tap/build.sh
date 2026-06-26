#!/usr/bin/env bash
# Build a custom OpenAL-soft (the version LWJGL 3.3.3 ships, 1.23.1) with the
# RecordZy tap compiled in, then drop libopenal.so into the launcher's native
# component folders. Requires an Android NDK (cmake + ninja). Run by CI.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
NATIVES="$ROOT/ZalithLauncher/src/main"

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "ERROR: Android NDK not found (set ANDROID_NDK_LATEST_HOME)"; exit 1
fi
echo "Using NDK: $NDK"
READELF="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)"
STRIP="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip)"

WORK="$HERE/work"
rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"
git clone --depth 1 --branch 1.23.1 https://github.com/kcat/openal-soft.git
cd openal-soft

cp "$HERE/recordzy_tap.cpp" ./recordzy_tap.cpp
cp "$HERE/recordzy_tap.h" ./recordzy_tap.h

# Inject the tap: include the header in the mixer TU and call the feed function
# at the end of DeviceBase::renderSamples(void*, uint, size_t).
python3 - <<'PY'
import re
p = "alc/alu.cpp"
s = open(p).read()
assert '#include "config.h"' in s, "config.h include not found in alu.cpp"
if "recordzy_tap.h" not in s:
    s = s.replace('#include "config.h"',
                  '#include "config.h"\n#include "recordzy_tap.h"', 1)
# Insert the tap feed at the end of
#   DeviceBase::renderSamples(void*, uint, size_t)
# i.e. after the while loop that follows "#undef HANDLE_WRITE", just before the
# function's closing brace. Whitespace-tolerant.
pat = re.compile(r'(#undef HANDLE_WRITE.*?total \+= samplesToDo;\s*\})\s*\}', re.DOTALL)
feed = ('\n    recordzy_tap_feed(outBuffer, numSamples, frameStep, Frequency, '
        'static_cast<unsigned>(BytesFromDevFmt(FmtType)), '
        'FmtType == DevFmtFloat ? 1 : 0);\n}')
s2, n = pat.subn(lambda m: m.group(1) + feed, s, count=1)
assert n == 1, "renderSamples anchor not found in alu.cpp"
open(p, "w").write(s2)
print("patched alc/alu.cpp")
PY

cat >> CMakeLists.txt <<'CM'

# ---- RecordZy audio tap (added by openal-tap/build.sh) ----
target_sources(OpenAL PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/recordzy_tap.cpp")
target_include_directories(OpenAL PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
CM

build_abi() {
    local ABI="$1"
    echo "=========== building OpenAL for $ABI ==========="
    local BD="$WORK/openal-soft/build-$ABI"
    rm -rf "$BD"; mkdir -p "$BD"; cd "$BD"
    cmake -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-21 \
        -DLIBTYPE=SHARED \
        -DALSOFT_BACKEND_OPENSL=ON \
        -DALSOFT_REQUIRE_OPENSL=ON \
        -DALSOFT_BACKEND_WAVE=OFF \
        -DALSOFT_EXAMPLES=OFF \
        -DALSOFT_UTILS=OFF \
        -DALSOFT_TESTS=OFF \
        -DCMAKE_BUILD_TYPE=Release \
        "$WORK/openal-soft" >/dev/null
    ninja OpenAL
    local SO
    SO="$(find . -name libopenal.so | head -1)"
    if [ -z "$SO" ]; then echo "ERROR: libopenal.so not built for $ABI"; exit 1; fi
    if ! "$READELF" --dyn-syms "$SO" | grep -q recordzy_tap_read; then
        echo "ERROR: recordzy_tap_read not exported in $ABI build"; exit 1
    fi
    "$STRIP" --strip-unneeded "$SO" || true
    mkdir -p "$HERE/out/$ABI"
    cp "$SO" "$HERE/out/$ABI/libopenal.so"
    echo "built $ABI: $(ls -la "$HERE/out/$ABI/libopenal.so" | awk '{print $5}') bytes"
}

rm -rf "$HERE/out"
build_abi arm64-v8a
build_abi armeabi-v7a

# Drop into every native component folder the launcher may load OpenAL from.
copy_to() {
    local dest="$1"
    if [ -f "$dest" ]; then
        cp "$2" "$dest"
        echo "updated $dest"
    fi
}
A64="$HERE/out/arm64-v8a/libopenal.so"
A32="$HERE/out/armeabi-v7a/libopenal.so"

copy_to "$NATIVES/assets/components/lwjglVulkan/natives/arm64-v8a/libopenal.so" "$A64"
copy_to "$NATIVES/assets/components/lwjgl3.3.3/natives/arm64-v8a/libopenal.so" "$A64"
copy_to "$NATIVES/assets/components/lwjgl3.3.3/natives/armeabi-v7a/libopenal.so" "$A32"
copy_to "$NATIVES/jniLibs/arm64-v8a/libopenal.so" "$A64"

echo "Done."
