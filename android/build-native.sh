#!/bin/sh
#
# Cross-compiles the native payload set SamSU loads at runtime.
#
# Three of the six libraries the engine wants are produced here. Two of them are
# NOT committed (exploit/build/ is a build directory):
#
#   su_daemon_aarch64_pie.app -> libm3qroot.so    the helper the engine probes with
#   slide_oracle.app.so       -> libm3qoracle.so  the KASLR slide oracle
#
# The other four come from android/prebuilt/. This script used to assume a Linux
# host and an NDK at a single hard-coded path, which is how a build shipped with
# no helper at all: android/app/build.gradle now refuses to package an
# incomplete set, so a missing output fails the build instead of the phone.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
exploit_root="$repo_root/exploit"
project='m3q-BP4A.251205.006'
output_dir="$exploit_root/build/$project/bin"
api=35

host_tag() {
    case "$(uname -s)" in
        Linux*)  echo linux-x86_64 ;;
        Darwin*) echo darwin-x86_64 ;;
        MINGW*|MSYS*|CYGWIN*) echo windows-x86_64 ;;
        *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
    esac
}

# --- locate an Android NDK ------------------------------------------------
ndk_roots=''
add_root() {
    [ -n "$1" ] && [ -d "$1/ndk" ] && ndk_roots="$ndk_roots $1/ndk"
    return 0
}
add_root "${ANDROID_HOME:-}"
add_root "${ANDROID_SDK_ROOT:-}"
add_root "$HOME/Android/Sdk"
add_root "$HOME/AppData/Local/Android/Sdk"
[ -n "${USER:-}" ] && add_root "/c/Users/$USER/AppData/Local/Android/Sdk"

ndk_root=''
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME:-}" ]; then
    ndk_root="$ANDROID_NDK_HOME"
else
    for root in $ndk_roots; do
        candidate=$(ls -1 "$root" 2>/dev/null | sort -V | tail -1) || true
        if [ -n "$candidate" ]; then
            ndk_root="$root/$candidate"
            break
        fi
    done
fi
if [ -z "$ndk_root" ] || [ ! -d "$ndk_root" ]; then
    echo "No Android NDK found." >&2
    echo "Set ANDROID_NDK_HOME, or install one under \$ANDROID_HOME/ndk." >&2
    exit 1
fi

toolchain="$ndk_root/toolchains/llvm/prebuilt/$(host_tag)"
compiler=''
for candidate in \
    "$toolchain/bin/aarch64-linux-android$api-clang" \
    "$toolchain/bin/aarch64-linux-android$api-clang.cmd"
do
    if [ -x "$candidate" ]; then
        compiler="$candidate"
        break
    fi
done
if [ -z "$compiler" ]; then
    # Any API level will do; the payloads only call libc and libdl.
    fallback=$(ls -1 "$toolchain/bin" 2>/dev/null \
        | grep -E '^aarch64-linux-android[0-9]+-clang$' | sort -V | tail -1) || true
    if [ -n "$fallback" ]; then
        compiler="$toolchain/bin/$fallback"
    fi
fi
if [ -z "$compiler" ] || [ ! -x "$compiler" ]; then
    echo "No aarch64 clang driver found in $toolchain/bin" >&2
    exit 1
fi

echo "NDK:      $ndk_root"
echo "Compiler: $compiler"
echo "Output:   $output_dir"
echo

mkdir -p "$output_dir"
cd "$exploit_root"

# 1/3 The helper. Target-agnostic: it probes KernelSU and stages work, and is the
# file the engine checks first, so a missing one stops every run before the
# kernel is touched.
"$compiler" \
    -O2 -g0 -Wall -Wextra -Werror -Isrc -fPIE -pie \
    src/su_daemon.c -ldl \
    -o "$output_dir/su_daemon_aarch64_pie.app"

# 2/3 The slide oracle, built against this project's target.
"$compiler" \
    -O2 -g0 -Wall -Wextra -Werror \
    -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function \
    -DAPP_PAYLOAD=1 -fPIC -Ivendor/root-my-galaxy/src \
    '-DTARGET_HEADER="targets/m3q-S948NKSS4AZG3/target.h"' \
    vendor/root-my-galaxy/src/main.c \
    vendor/root-my-galaxy/src/util.c \
    vendor/root-my-galaxy/src/slide_app.c \
    vendor/root-my-galaxy/src/fops.c \
    vendor/root-my-galaxy/src/pipe.c \
    vendor/root-my-galaxy/src/root.c \
    vendor/root-my-galaxy/src/preload.c \
    -shared -pthread \
    -o "$output_dir/slide_oracle.app.so"

# 3/3 The bundled payload for this target. The APK ships the per-model prebuilts
# from android/prebuilt/ instead, so this one is a build check as much as an
# artifact.
"$compiler" \
    -O2 -g0 -Wall -Wextra -Werror \
    -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function \
    -Isrc -fPIC \
    '-DTARGET_CONFIG_H="targets/m3q-BP4A.251205.006/target.h"' \
    src/targets/m3q-BP4A.251205.006/main.c \
    src/targets/m3q-BP4A.251205.006/util.c \
    src/targets/m3q-BP4A.251205.006/slide.c \
    src/targets/m3q-BP4A.251205.006/fops.c \
    src/targets/m3q-BP4A.251205.006/pipe.c \
    src/faketables.c \
    src/stage3.c \
    src/targets/m3q-BP4A.251205.006/root.c \
    src/app_preload.c \
    src/stage3_loop.S \
    src/stage3_poll.S \
    -shared -pthread \
    -o "$output_dir/preload.app.so"

echo
missing=''
for output in su_daemon_aarch64_pie.app slide_oracle.app.so preload.app.so; do
    if [ ! -f "$output_dir/$output" ]; then
        missing="$missing $output"
    fi
done
if [ -n "$missing" ]; then
    echo "These outputs were not produced:$missing" >&2
    exit 1
fi

sha256sum "$output_dir/su_daemon_aarch64_pie.app" \
    "$output_dir/slide_oracle.app.so" \
    "$output_dir/preload.app.so"
