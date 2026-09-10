cmake_minimum_required(VERSION 3.20)
project(aztcast-torrent-engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Without this a bare `cmake -B build` produces an unoptimised binary *and* silently disables the
# hardening below: _FORTIFY_SOURCE is a no-op without optimisation, and GCC turns it off rather
# than erroring. The Dockerfile has always passed a build type, so this only ever bit someone
# building by hand -- which is exactly the person least likely to notice.
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "" FORCE)
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBTORRENT REQUIRED IMPORTED_TARGET libtorrent-rasterbar)
find_package(nlohmann_json REQUIRED)
find_package(Threads REQUIRED)

add_executable(aztcast-torrent-engine
        src/main.cpp
        src/session.cpp
        src/download.cpp)

target_link_libraries(aztcast-torrent-engine
        PRIVATE PkgConfig::LIBTORRENT nlohmann_json::nlohmann_json Threads::Threads)

# This binary parses bytes from strangers for a living, so the cheap hardening is not optional.
# The sandbox around it (see deploy/docker-compose.yml and ADR-0032) is the load-bearing part;
# these are what keep an overflow from being useful in the first place.
target_compile_options(aztcast-torrent-engine PRIVATE
        -Wall -Wextra
        -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2
        -fstack-protector-strong
        -fstack-clash-protection)
target_link_options(aztcast-torrent-engine PRIVATE -Wl,-z,relro,-z,now -Wl,-z,noexecstack)

install(TARGETS aztcast-torrent-engine RUNTIME DESTINATION bin)
