#!/usr/bin/env bash
#
# Cross-compile mosh-client for Android (arm64-v8a, x86_64).
#
# Prerequisites:
#   - Android NDK r27+ (set ANDROID_NDK_ROOT)
#   - Standard build tools: autoconf, automake, libtool, pkg-config, cmake
#
# Output:
#   core/mosh/src/main/jniLibs/{abi}/libmoshclient.so
#
# Named libmoshclient.so so Android packaging includes them in lib/.
# At runtime: context.applicationInfo.nativeLibraryDir + "/libmoshclient.so"
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_ROOT="${BUILD_ROOT:-$PROJECT_ROOT/build-mosh}"
JNILIBS="$PROJECT_ROOT/core/mosh/src/main/jniLibs"

: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to your NDK path (r27+)}"
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
API=26

OPENSSL_VERSION=3.3.2
NCURSES_VERSION=6.5
PROTOBUF_VERSION=25.5
ABSEIL_VERSION=20230802.2
MOSH_VERSION=1.4.0

# ABI → (triple, cmake_arch, cmake_processor)
declare -A ABI_TRIPLE=(
    [arm64-v8a]=aarch64-linux-android
    [x86_64]=x86_64-linux-android
)
declare -A ABI_OSSL=(
    [arm64-v8a]=android-arm64
    [x86_64]=android-x86_64
)
declare -A ABI_PROCESSOR=(
    [arm64-v8a]=aarch64
    [x86_64]=x86_64
)

fetch() {
    local url=$1 dest=$2
    if [ ! -f "$dest" ]; then
        echo "Downloading $url"
        curl -fSL "$url" -o "$dest"
    fi
}

build_openssl() {
    local abi=$1 prefix=$2
    [ -f "$prefix/lib/libcrypto.a" ] && return 0

    local src="$BUILD_ROOT/openssl-$OPENSSL_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/openssl-$OPENSSL_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/openssl-$OPENSSL_VERSION" "$src"
    cd "$src"

    export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
    export PATH="$TOOLCHAIN/bin:$PATH"

    ./Configure "${ABI_OSSL[$abi]}" \
        -D__ANDROID_API__=$API \
        --prefix="$prefix" \
        no-shared no-tests no-ui-console
    make -j"$(nproc)"
    make install_sw
}

build_ncurses() {
    local abi=$1 prefix=$2
    [ -f "$prefix/lib/libncurses.a" ] && return 0

    local triple="${ABI_TRIPLE[$abi]}"
    local src="$BUILD_ROOT/ncurses-$NCURSES_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/ncurses-$NCURSES_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/ncurses-$NCURSES_VERSION" "$src"
    cd "$src"

    export CC="$TOOLCHAIN/bin/${triple}${API}-clang"
    export CXX="$TOOLCHAIN/bin/${triple}${API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"

    ./configure \
        --host="$triple" \
        --prefix="$prefix" \
        --without-shared \
        --without-debug \
        --without-ada \
        --without-tests \
        --without-progs \
        --enable-widec \
        --with-terminfo-dirs=/etc/terminfo:/usr/share/terminfo
    make -j"$(nproc)"
    make install

    # mosh configure looks for -ltinfo and -lncurses separately;
    # our static build bundles tinfo into ncursesw
    ln -sf libncursesw.a "$prefix/lib/libtinfo.a"
    ln -sf libncursesw.a "$prefix/lib/libncurses.a"

    unset CC CXX AR RANLIB
}

build_protobuf() {
    local abi=$1 prefix=$2
    [ -f "$prefix/lib/libprotobuf.a" ] && return 0

    local triple="${ABI_TRIPLE[$abi]}"
    local src="$BUILD_ROOT/protobuf-$PROTOBUF_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/protobuf-$PROTOBUF_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/protobuf-$PROTOBUF_VERSION" "$src"

    # Populate submodules that the tarball doesn't include
    if [ ! -f "$src/third_party/abseil-cpp/CMakeLists.txt" ]; then
        rm -rf "$src/third_party/abseil-cpp"
        tar xf "$BUILD_ROOT/dl/abseil-cpp-$ABSEIL_VERSION.tar.gz" -C "$src/third_party"
        mv "$src/third_party/abseil-cpp-$ABSEIL_VERSION" "$src/third_party/abseil-cpp"
    fi

    # Fix: abseil detects host arch (x86_64) instead of target when cross-compiling,
    # then passes -maes/-msse4.1 which fail on ARM. Patch to use ANDROID_ABI detection
    # before any host-arch checks.
    local _copts="$src/third_party/abseil-cpp/absl/copts/AbseilConfigureCopts.cmake"
    if [ -f "$_copts" ] && ! grep -q "ANDROID_ABI" "$_copts"; then
        sed -i 's/^if(APPLE AND CMAKE_CXX_COMPILER_ID MATCHES \[\[Clang\]\])/if(ANDROID_ABI STREQUAL "arm64-v8a")\n  set(ABSL_RANDOM_RANDEN_COPTS "${ABSL_RANDOM_HWAES_ARM64_FLAGS}")\nelseif(ANDROID_ABI STREQUAL "x86_64")\n  set(ABSL_RANDOM_RANDEN_COPTS "${ABSL_RANDOM_HWAES_X64_FLAGS}")\nelseif(APPLE AND CMAKE_CXX_COMPILER_ID MATCHES [[Clang]])/' "$_copts"
    fi

    cd "$src"

    # Get host protoc (needed for cross-compilation)
    if [ ! -f "$BUILD_ROOT/host-protoc/bin/protoc" ]; then
        fetch "https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protoc-$PROTOBUF_VERSION-linux-x86_64.zip" \
            "$BUILD_ROOT/dl/protoc-$PROTOBUF_VERSION-linux-x86_64.zip"
        mkdir -p "$BUILD_ROOT/host-protoc"
        cd "$BUILD_ROOT/host-protoc"
        unzip -o "$BUILD_ROOT/dl/protoc-$PROTOBUF_VERSION-linux-x86_64.zip" bin/protoc
        chmod +x bin/protoc
    fi

    # Cross-compile protobuf for Android
    rm -rf "$BUILD_ROOT/protobuf-build-$abi"
    mkdir -p "$BUILD_ROOT/protobuf-build-$abi"
    cd "$BUILD_ROOT/protobuf-build-$abi"
    cmake "$src" \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-$API \
        -DCMAKE_SYSTEM_PROCESSOR="${ABI_PROCESSOR[$abi]}" \
        -DCMAKE_INSTALL_PREFIX="$prefix" \
        -Dprotobuf_BUILD_TESTS=OFF \
        -Dprotobuf_BUILD_SHARED_LIBS=OFF \
        -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
        -DABSL_PROPAGATE_CXX_STD=ON
    make -j"$(nproc)"
    make install
}

build_mosh() {
    local abi=$1 prefix=$2
    local triple="${ABI_TRIPLE[$abi]}"
    local src="$BUILD_ROOT/mosh-$MOSH_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/mosh-$MOSH_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/mosh-$MOSH_VERSION" "$src"
    cd "$src"

    export CC="$TOOLCHAIN/bin/${triple}${API}-clang"
    export CXX="$TOOLCHAIN/bin/${triple}${API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:$prefix/lib64/pkgconfig"
    local sysroot_lib="$TOOLCHAIN/sysroot/usr/lib/${triple}/$API"
    export LDFLAGS="-L$prefix/lib -L$sysroot_lib -llog -static-libstdc++ -Wl,-z,max-page-size=16384"
    export CXXFLAGS="-std=c++17"
    export CPPFLAGS="-I$prefix/include -I$prefix/include/ncursesw"

    # Full static link flags from pkg-config; strip -llog (Android system lib)
    # and -pthread (not needed for bionic), then add -llog via LDFLAGS
    local _absl_libs
    _absl_libs=$(PKG_CONFIG_PATH="$prefix/lib/pkgconfig:$prefix/lib64/pkgconfig" \
        pkg-config --libs --static protobuf 2>/dev/null | sed 's/ -pthread//g; s/ -llog//g')
    export protobuf_LIBS="$_absl_libs"
    export protobuf_CFLAGS="-I$prefix/include"

    ./configure \
        --host="$triple" \
        --prefix="$prefix" \
        --with-crypto-library=openssl \
        --enable-static-libraries \
        --disable-server \
        --disable-hardening \
        PROTOC="$BUILD_ROOT/host-protoc/bin/protoc"

    make -j"$(nproc)"

    # Output the mosh-client binary, named libmoshclient.so for Android packaging
    local outdir="$JNILIBS/$abi"
    mkdir -p "$outdir"
    cp src/frontend/mosh-client "$outdir/libmoshclient.so"
    "$TOOLCHAIN/bin/llvm-strip" "$outdir/libmoshclient.so"

    echo "Built: $outdir/libmoshclient.so ($(du -h "$outdir/libmoshclient.so" | cut -f1))"

    # Clean exported env vars so they don't leak into the next ABI's builds
    unset CC CXX AR RANLIB PKG_CONFIG_PATH LDFLAGS CXXFLAGS CPPFLAGS protobuf_LIBS protobuf_CFLAGS
}

# --- Main ---

mkdir -p "$BUILD_ROOT/dl"

# Download sources
fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/openssl-$OPENSSL_VERSION.tar.gz"
fetch "https://ftp.gnu.org/gnu/ncurses/ncurses-$NCURSES_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/ncurses-$NCURSES_VERSION.tar.gz"
fetch "https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protobuf-$PROTOBUF_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/protobuf-$PROTOBUF_VERSION.tar.gz"
fetch "https://github.com/abseil/abseil-cpp/releases/download/$ABSEIL_VERSION/abseil-cpp-$ABSEIL_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/abseil-cpp-$ABSEIL_VERSION.tar.gz"
fetch "https://github.com/mobile-shell/mosh/releases/download/mosh-$MOSH_VERSION/mosh-$MOSH_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/mosh-$MOSH_VERSION.tar.gz"

for abi in arm64-v8a x86_64; do
    echo "=== Building for $abi ==="
    PREFIX="$BUILD_ROOT/install-$abi"
    mkdir -p "$PREFIX"

    build_openssl "$abi" "$PREFIX"
    build_ncurses "$abi" "$PREFIX"
    build_protobuf "$abi" "$PREFIX"
    build_mosh "$abi" "$PREFIX"
done

echo ""
echo "Done. Binaries are in:"
ls -la "$JNILIBS"/*/libmoshclient.so
