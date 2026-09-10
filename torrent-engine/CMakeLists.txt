cmake_minimum_required(VERSION 3.20)
project(aztcast-torrent-engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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
        -D_FORTIFY_SOURCE=2
        -fstack-protector-strong
        -fstack-clash-protection)
target_link_options(aztcast-torrent-engine PRIVATE -Wl,-z,relro,-z,now -Wl,-z,noexecstack)

install(TARGETS aztcast-torrent-engine RUNTIME DESTINATION bin)
