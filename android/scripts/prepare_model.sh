#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

cache="$PWD/build/llm-upstream"
build="$PWD/build/offline-model"
generated="$PWD/app/build/generated"
ndk_version=28.2.13676358
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Définir ANDROID_HOME}/ndk/$ndk_version}"
cmake_bin="${CMAKE_BIN:-cmake}"
llama_commit=2943210c1eaa3fc9cc4f0ac6f0ae5f2ce2350f98
llama="$cache/llama.cpp-$llama_commit"
archive="$cache/llama.cpp.tar.gz"
model_name=smollm2-135m-instruct-q4_0.gguf
model="$cache/$model_name"

if [[ ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
    printf 'Installer le NDK Android %s ou définir ANDROID_NDK_HOME.\n' "$ndk_version" >&2
    exit 1
fi
if ! command -v "$cmake_bin" >/dev/null || ! command -v ninja >/dev/null; then
    printf 'Installer CMake (3.22+) et Ninja, puis les ajouter au PATH.\n' >&2
    exit 1
fi
strip=$(find "$ndk/toolchains/llvm/prebuilt" -path '*/bin/llvm-strip' -print -quit)
mkdir -p "$cache" "$generated/lettrineAssets"

verified() {
    [[ -f "$1" ]] && printf '%s  %s\n' "$2" "$1" | shasum -a 256 -c --status
}

download() {
    local file=$1 digest=$2 url=$3
    if verified "$file" "$digest"; then return; fi
    if [[ "${LETTRINE_GRADLE_OFFLINE:-0}" == 1 ]]; then
        printf 'Le cache hors ligne ne contient pas une copie vérifiée de %s.\n' "$(basename "$file")" >&2
        exit 1
    fi
    if ! curl --fail --location --retry 2 --silent --show-error "$url" -o "$file.part" ||
        ! verified "$file.part" "$digest"; then
        rm -f "$file.part"
        printf 'Impossible de vérifier %s.\n' "$(basename "$file")" >&2
        exit 1
    fi
    mv "$file.part" "$file"
}

download "$archive" 29ee9a1e28b53427914f9ad0f95f782c218c4d96459f4a1e592ae887d481b636 \
    "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/$llama_commit"
download "$model" bcc3af2849ad6095af57e9b5cd43775256efdc66e306acb529172f92d0c04b03 \
    'https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/09816acd5d99df7be770d85ea30822623dab342c/SmolLM2-135M-Instruct-Q4_0.gguf'

if [[ ! -f "$llama/.lettrine-extracted" ]]; then
    tar -xf "$archive" -C "$cache"
    touch "$llama/.lettrine-extracted"
fi
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    "$cmake_bin" -S app/src/main/cpp -B "$build/$abi" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release -DLETTRINE_LLAMA_SOURCE="$llama" \
        -DLLAMA_BUILD_COMMIT="${llama_commit:0:7}"
    "$cmake_bin" --build "$build/$abi" --target lettrine_offline --parallel "${LETTRINE_BUILD_JOBS:-6}"
    mkdir -p "$generated/lettrineJni/$abi"
    "$strip" --strip-unneeded -o "$generated/lettrineJni/$abi/liblettrine_offline.so" \
        "$build/$abi/liblettrine_offline.so"
done
cp "$model" "$generated/lettrineAssets/$model_name"
cp "$llama/LICENSE" "$generated/lettrineAssets/llama.cpp-LICENSE.txt"
cp "$ndk/NOTICE" "$generated/lettrineAssets/Android-NDK-NOTICE.txt"
