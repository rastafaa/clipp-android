cmake_minimum_required(VERSION 3.22)
project(clipp_android CXX C)

# libclipp.so: the shared Clipp core (../../../../../src) plus the Android glue
# in this directory. Same translation units the iOS app compiles through its
# Bridge/*.mm wrappers, minus the Apple-only pieces.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(CLIPP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(CLIPP_SRC "${CLIPP_ROOT}/src")

# Version header (src/version.h.in), read from the Gradle-provided version name.
if (NOT DEFINED CLIPP_VERSION)
    set(CLIPP_VERSION "0.0.0.0")
endif()
string(REPLACE "." ";" _clipp_version_parts "${CLIPP_VERSION}.0.0.0")
list(GET _clipp_version_parts 0 CLIPP_VERSION_MAJOR)
list(GET _clipp_version_parts 1 CLIPP_VERSION_MINOR)
list(GET _clipp_version_parts 2 CLIPP_VERSION_PATCH)
list(GET _clipp_version_parts 3 CLIPP_VERSION_BUILD)
set(CLIPP_VERSION_3PART "${CLIPP_VERSION_MAJOR}.${CLIPP_VERSION_MINOR}.${CLIPP_VERSION_PATCH}")
configure_file("${CLIPP_SRC}/version.h.in" "${CMAKE_CURRENT_BINARY_DIR}/generated/version.h" @ONLY)

add_library(clipp SHARED
    # Android glue
    AndroidPlatform.cpp
    ClippJni.cpp
    MDNSDiscovery_Android.cpp
    # Shared core (daemon profile)
    ${CLIPP_SRC}/ClipboardActivityDisplay.cpp
    ${CLIPP_SRC}/ClipboardActivityStore.cpp
    ${CLIPP_SRC}/ClipboardHashGuard.cpp
    ${CLIPP_SRC}/ClipboardWire.cpp
    ${CLIPP_SRC}/CryptoChannel.cpp
    ${CLIPP_SRC}/Hlc.cpp
    ${CLIPP_SRC}/KeyManager.cpp
    ${CLIPP_SRC}/Listener.cpp
    ${CLIPP_SRC}/Logger.cpp
    ${CLIPP_SRC}/MDNSProtocol.cpp
    ${CLIPP_SRC}/NetworkInterfaces.cpp
    ${CLIPP_SRC}/NetworkRuntime.cpp
    ${CLIPP_SRC}/Peer.cpp
    ${CLIPP_SRC}/PeerDisplay.cpp
    ${CLIPP_SRC}/PeerManager.cpp
    ${CLIPP_SRC}/RegisterPersistence.cpp
    ${CLIPP_SRC}/RegisterPersistenceRuntime.cpp
    ${CLIPP_SRC}/RegisterStore.cpp
    ${CLIPP_SRC}/RegisterWire.cpp
    ${CLIPP_SRC}/SealedSnapshot.cpp
    ${CLIPP_SRC}/Settings.cpp
    ${CLIPP_SRC}/platform/linux/Settings.cpp
)

target_include_directories(clipp PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CLIPP_SRC}"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
)

# Third-party static libraries (libsodium, zstd, xxHash, utf8proc). In CI these
# are built by vcpkg for the Android ABI (android/native-deps) and passed in as
# CLIPP_DEPS_DIR=<vcpkg_installed>/<triplet>. Without it (host syntax checks),
# fall back to the system libraries.
if (DEFINED CLIPP_DEPS_DIR)
    target_include_directories(clipp PRIVATE "${CLIPP_DEPS_DIR}/include")
    foreach(lib sodium zstd xxhash utf8proc)
        find_library(CLIPP_LIB_${lib} NAMES ${lib} PATHS "${CLIPP_DEPS_DIR}/lib" NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH REQUIRED)
        target_link_libraries(clipp PRIVATE "${CLIPP_LIB_${lib}}")
    endforeach()
    target_compile_definitions(clipp PRIVATE UTF8PROC_STATIC SODIUM_STATIC)
else()
    target_link_libraries(clipp PRIVATE sodium zstd xxhash utf8proc)
endif()

if (ANDROID)
    target_link_libraries(clipp PRIVATE log)
endif()

target_compile_options(clipp PRIVATE -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_options(clipp PRIVATE -Wl,--gc-sections -Wl,--no-undefined)
