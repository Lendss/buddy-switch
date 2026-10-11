#!/data/data/com.termux/files/usr/bin/bash
# Builds libbuddypty.so (the terminal's pty helper) for arm64 Android with clang (Termux: pkg install clang).
set -euo pipefail
cd "$(dirname "$0")"
JNI=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include
OUT=../app/src/main/jniLibs/arm64-v8a/libbuddypty.so
clang --target=aarch64-linux-android30 -shared -fPIC -O2 -Wall -Wl,-z,max-page-size=16384 \
  -I"$JNI" -I"$JNI/linux" -o "$OUT" pty.c
env -u LD_PRELOAD "$PREFIX/glibc/bin/patchelf" --remove-rpath "$OUT" 2>/dev/null || patchelf --remove-rpath "$OUT"
llvm-strip --strip-unneeded "$OUT" 2>/dev/null || true
echo "Built $OUT"
