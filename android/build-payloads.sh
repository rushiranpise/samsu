#!/bin/sh
#
# Builds the three bundled exploit payloads from the Root My Galaxy Payloads
# source instead of copying the committed binaries in android/prebuilt/.
#
# The payloads are the per-firmware part of the exploit: each one embeds the
# offsets and identity of exactly one firmware build, which is why upstream
# ports them one at a time (docs/PORTING.md in that repository) and why they
# cannot be swapped between models.
#
# Two upstream paths produce the same file; this script uses whichever is
# available so it works on a Linux runner and on a Windows checkout:
#
#   make TARGET=<target> release            the documented build (Linux)
#   aarch64-linux-android35-clang ... -Oz   the build_*.cmd recipe (Windows)
#
# Both compile the release payload at -Oz and pad/truncate it to exactly
# 104128 bytes, the size the app's payload loader and the upstream scripts
# expect.
#
# Usage:
#   sh android/build-payloads.sh [output-dir]
#
# Environment:
#   RMG_REPO       source repository        (default: mitschud's fork, which
#                                           carries the ZZI4 targets)
#   RMG_REF        commit or branch to build from
#   RMG_DIR        reuse an existing checkout instead of cloning
#   ANDROID_NDK_HOME  NDK to compile with; NDK 30 is what upstream used for
#                     these payloads
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

# The S25 family ZZI4 targets. pa1q also exists on the default branch; the
# pa2q/pa3q targets live only on this branch, so the commit is pinned rather
# than tracking a moving branch tip.
rmg_repo=${RMG_REPO:-https://github.com/mitschud/Root-My-Galaxy-Payloads.git}
rmg_ref=${RMG_REF:-3cb57348d3c6a5227d9fec625049acac77de17a3}
rmg_dir=${RMG_DIR:-$repo_root/android/build/rmg-payloads}
out_dir=${1:-$repo_root/android/build/payloads}

# <upstream target> <shipped file name> <sha256 of the binary upstream ships>
targets='pa1q-S931BXXUCZZI4 payload-pa1q-S931BXXUCZZI4.so f6158d21ea432c8b7a258bf991f87ef30367fac628fc56fffc883e0757ef28a7
pa2q-S936BXXUCZZI4 payload-pa2q-S936BXXUCZZI4.so 28b739b01f4e67aed7b17bfafb27cc72c3bc1a7d9eac4d41834d99a3d10e9a28
pa3q-S938BXXUCZZI4 payload-pa3q-S938BXXUCZZI4.so e41a2073cba1b4e5da8df19716943681dff9a8a49455f9faed48bf82909f8e20'

expected_size=104128
api=35

# --- source tree ---------------------------------------------------------
# Git is invoked from inside the checkout rather than with a path argument: a
# Windows build of git cannot read an MSYS-style path such as /c/Users/... .
if [ -d "$rmg_dir/.git" ]; then
    echo "Reusing $rmg_dir"
    ( cd "$rmg_dir" && git fetch --depth 1 origin "$rmg_ref" >/dev/null 2>&1 || true
      cd "$rmg_dir" && { git checkout -q FETCH_HEAD 2>/dev/null || git checkout -q "$rmg_ref"; } )
else
    echo "Cloning $rmg_repo at $rmg_ref"
    rm -rf "$rmg_dir"
    git clone -q --depth 1 --filter=blob:none --sparse "$rmg_repo" "$rmg_dir"
    ( cd "$rmg_dir" && git fetch --depth 1 origin "$rmg_ref" >/dev/null 2>&1 || true
      cd "$rmg_dir" && { git checkout -q FETCH_HEAD 2>/dev/null || git checkout -q "$rmg_ref"; }
      cd "$rmg_dir" && git sparse-checkout set --no-cone /src /Makefile )
fi
echo "Payload source: $(cd "$rmg_dir" && git rev-parse HEAD)"

# --- toolchain -----------------------------------------------------------
host_tag() {
    case "$(uname -s)" in
        Linux*)  echo linux-x86_64 ;;
        Darwin*) echo darwin-x86_64 ;;
        MINGW*|MSYS*|CYGWIN*) echo windows-x86_64 ;;
        *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
    esac
}

ndk_root=''
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME:-}" ]; then
    ndk_root="$ANDROID_NDK_HOME"
else
    for root in \
        "${ANDROID_HOME:-}/ndk" \
        "${ANDROID_SDK_ROOT:-}/ndk" \
        "$HOME/Android/Sdk/ndk" \
        "$HOME/AppData/Local/Android/Sdk/ndk" \
        "/usr/local/lib/android/sdk/ndk"
    do
        [ -d "$root" ] || continue
        candidate=$(ls -1 "$root" 2>/dev/null | sort -V | tail -1) || true
        if [ -n "$candidate" ]; then
            ndk_root="$root/$candidate"
            break
        fi
    done
fi
if [ -z "$ndk_root" ] || [ ! -d "$ndk_root" ]; then
    echo "No Android NDK found; set ANDROID_NDK_HOME." >&2
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
echo "NDK:      $ndk_root"

mkdir -p "$out_dir"

# --- build ---------------------------------------------------------------
# The flags below are the ones in upstream's build_zzi4.cmd / build_s936b.cmd /
# build_s938b.cmd, which is how the shipped payloads were compiled.
REL_OUT='cve-2026-43499-app.release.so'

# Runs inside the checkout with a relative output path, because a Windows clang
# cannot open an MSYS-style /c/... path any more than git can.
build_direct() {
    target=$1
    mkdir -p "$rmg_dir/build/$target"
    ( cd "$rmg_dir" && "$compiler" -DAPP_PAYLOAD=1 -fPIC -Oz -g0 \
        -fno-unwind-tables -fno-asynchronous-unwind-tables \
        -ffunction-sections -fdata-sections \
        -Wall -Wextra -Wno-unused-parameter -Wno-sign-compare \
        -Isrc -DTARGET_HEADER="\"targets/$target/target.h\"" \
        src/main.c src/util.c src/slide_app.c src/fops.c src/pipe.c \
        src/root.c src/preload.c \
        -shared -pthread \
        -Wl,--gc-sections -Wl,--icf=all -s \
        -o "build/$target/$REL_OUT" )
}

pad_to_expected_size() {
    file=$1
    size=$(wc -c < "$file" | tr -d ' ')
    if [ "$size" -gt "$expected_size" ]; then
        echo "  $file is $size bytes, larger than the $expected_size the loader expects" >&2
        exit 1
    fi
    if [ "$size" -lt "$expected_size" ]; then
        if command -v truncate >/dev/null 2>&1; then
            truncate -s "$expected_size" "$file"
        else
            python3 - "$file" "$expected_size" <<'PY'
import sys
path, want = sys.argv[1], int(sys.argv[2])
data = open(path, 'rb').read()
open(path, 'wb').write(data + b'\x00' * (want - len(data)))
PY
        fi
    fi
}

echo
printf '%-22s %-14s %s\n' 'target' 'size' 'sha256'
drift=0
newline='
'
for entry in $(printf '%s\n' "$targets" | awk '{print $1 ":" $2 ":" $3}'); do
    target=$(printf '%s' "$entry" | cut -d: -f1)
    shipped=$(printf '%s' "$entry" | cut -d: -f2)
    upstream_sha=$(printf '%s' "$entry" | cut -d: -f3)

    if [ ! -f "$rmg_dir/src/targets/$target/target.h" ]; then
        echo "  no target header for $target in $rmg_repo@$rmg_ref" >&2
        exit 1
    fi

    if command -v make >/dev/null 2>&1; then
        ( cd "$rmg_dir" && make TARGET="$target" \
            ANDROID_NDK_HOME="$ndk_root" TARGET_CC="$compiler" release >/dev/null )
    else
        build_direct "$target"
    fi

    out="$out_dir/$shipped"
    cp "$rmg_dir/build/$target/$REL_OUT" "$out"
    pad_to_expected_size "$out"

    size=$(wc -c < "$out" | tr -d ' ')
    sha=$(sha256sum "$out" | cut -d' ' -f1)
    printf '%-22s %-14s %s' "$target" "$size" "$sha"
    if [ "$sha" = "$upstream_sha" ]; then
        printf '  (matches upstream)\n'
    else
        printf '  (differs from upstream %s)\n' "$(printf '%s' "$upstream_sha" | cut -c1-12)"
        drift=$((drift + 1))
    fi
done

echo
if [ "$drift" -ne 0 ]; then
    echo "NOTE: $drift payload(s) do not reproduce upstream's checksum byte for byte."
    echo "      Same source, different compiler build. Upstream's own CI job for"
    echo "      the KernelSU pair pins NDK 30.0.16138531, which is what these were"
    echo "      compiled with; use that NDK for an exact match."
fi
echo "Payloads written to $out_dir"
