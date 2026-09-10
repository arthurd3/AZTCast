// The AZTCast torrent engine: everything that talks to strangers, in a process that can do nothing
// else.
//
// The API used to run its BitTorrent client inside the JVM, which meant every byte from an unknown
// peer was parsed by code sharing a process with the media root, the job store and the provider
// database. This is the other half of that: the swarm-facing work moved out, given the smallest set
// of things it needs, and left with no way to reach the rest. See ADR-0032.
//
// It listens on a Unix socket and nothing else. Not a TCP port on loopback — a Unix socket, so
// there is no address for anything on this machine or any network to connect to, only a path with
// filesystem permissions on it. The one port this process opens is the BitTorrent listener, which is
// the port that is supposed to face the world.
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <unistd.h>

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#include "download.hpp"
#include "protocol.hpp"
#include "session.hpp"

namespace aztcast {

bool Conn::send(const json& message) {
    if (broken()) {
        return false;
    }
    // Outside the lock on purpose. Formatting is this call's expensive half and it is per-message
    // private work; only the write has to be exclusive.
    std::string line = message.dump();
    line.push_back('\n');
    return write_all(line);
}

bool Conn::send_lines(const std::string& lines) {
    if (lines.empty() || broken()) {
        return !broken();
    }
    return write_all(lines);
}

bool Conn::write_all(const std::string& payload) {
    std::lock_guard<std::mutex> guard(write_mutex_);
    if (broken_.load(std::memory_order_relaxed)) {
        return false;
    }
    std::size_t written = 0;
    while (written < payload.size()) {
        ssize_t n = ::write(fd_, payload.data() + written, payload.size() - written);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            // EPIPE is the ordinary end of a cancelled download, not an incident: the client closed
            // the socket because the ingestion was abandoned.
            broken_.store(true, std::memory_order_relaxed);
            return false;
        }
        written += static_cast<std::size_t>(n);
    }
    return true;
}

namespace {

std::atomic<bool> running{true};

void stop(int) { running = false; }

// Reads one newline-terminated line. Bounded, because this is the one place an unauthenticated
// writer could otherwise ask the process to allocate without limit.
bool read_line(int fd, std::string& out) {
    constexpr std::size_t MAX_LINE = 64 * 1024;
    out.clear();
    char c;
    while (out.size() < MAX_LINE) {
        ssize_t n = ::read(fd, &c, 1);
        if (n == 0) return false;
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (c == '\n') return true;
        out.push_back(c);
    }
    return false;
}

NetworkSettings parse_network(const json& source) {
    NetworkSettings settings;
    if (!source.is_object()) return settings;
    settings.encryption = source.value("encryption", settings.encryption);
    settings.disable_local_service_discovery =
        source.value("disableLocalServiceDiscovery", settings.disable_local_service_discovery);
    settings.disable_peer_exchange = source.value("disablePeerExchange", settings.disable_peer_exchange);
    settings.acceptor_address = source.value("acceptorAddress", settings.acceptor_address);
    settings.acceptor_port = source.value("acceptorPort", settings.acceptor_port);
    settings.max_peer_connections_per_torrent =
        source.value("maxPeerConnectionsPerTorrent", settings.max_peer_connections_per_torrent);
    settings.max_active_peer_connections_per_torrent =
        source.value("maxActivePeerConnectionsPerTorrent", settings.max_active_peer_connections_per_torrent);
    settings.max_peer_connections = source.value("maxPeerConnections", settings.max_peer_connections);
    settings.peers_per_tracker_request =
        source.value("peersPerTrackerRequest", settings.peers_per_tracker_request);
    settings.max_pending_connection_requests =
        source.value("maxPendingConnectionRequests", settings.max_pending_connection_requests);
    settings.tracker_timeout_seconds =
        source.value("trackerTimeoutSeconds", settings.tracker_timeout_seconds);
    return settings;
}

void serve(int fd, Session& session) {
    Conn conn(fd);
    std::string line;
    if (!read_line(fd, line)) {
        ::close(fd);
        return;
    }

    StartRequest request;
    try {
        json message = json::parse(line);
        if (message.value("type", "") != "start") {
            conn.send({{"type", "error"}, {"message", "first message must be a start"}});
            ::close(fd);
            return;
        }
        request.video_id = message.at("videoId").get<std::string>();
        request.magnet = message.at("magnet").get<std::string>();
        request.target_dir = message.at("targetDir").get<std::string>();
        request.video_only = message.value("videoOnly", true);
        // Defaults true so an older client that does not send it keeps the previous behaviour.
        request.peer_events = message.value("peerEvents", true);
        request.timeout_seconds = message.value("timeoutSeconds", 7200);
        for (const auto& extension : message.value("videoExtensions", json::array())) {
            request.video_extensions.push_back(extension.get<std::string>());
        }
        request.network = parse_network(message.value("network", json::object()));
    } catch (const std::exception& e) {
        conn.send({{"type", "error"}, {"message", std::string("unreadable start request: ") + e.what()}});
        ::close(fd);
        return;
    }

    if (!session.configure(request.network)) {
        // Said once per download rather than swallowed. The session is shared, so only the first
        // request's network settings take effect; a reader of the logs should be able to see that
        // the ones in this request did not.
        std::fprintf(stderr, "engine: session already configured, using its settings for %s\n",
                     request.video_id.c_str());
    }

    std::fprintf(stderr, "engine: starting %s into %s\n", request.video_id.c_str(),
                 request.target_dir.c_str());
    {
        Download download(session, conn, std::move(request));
        download.run();
    }
    ::close(fd);
}

}  // namespace
}  // namespace aztcast

int main(int argc, char** argv) {
    const char* socket_path = argc > 1 ? argv[1] : "/run/aztcast/engine.sock";

    std::signal(SIGPIPE, SIG_IGN);
    std::signal(SIGINT, aztcast::stop);
    std::signal(SIGTERM, aztcast::stop);

    ::unlink(socket_path);
    int listener = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener < 0) {
        std::fprintf(stderr, "engine: cannot create socket: %s\n", std::strerror(errno));
        return 1;
    }

    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    if (std::strlen(socket_path) >= sizeof(address.sun_path)) {
        std::fprintf(stderr, "engine: socket path is too long: %s\n", socket_path);
        return 1;
    }
    std::strncpy(address.sun_path, socket_path, sizeof(address.sun_path) - 1);

    if (::bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) < 0) {
        std::fprintf(stderr, "engine: cannot bind %s: %s\n", socket_path, std::strerror(errno));
        return 1;
    }
    // 0660: the API's group and nobody else. The socket is the entire control surface of this
    // process, so who can open it is the whole access-control story — there is no second check
    // inside, and there is deliberately no password to leak into a compose file.
    ::chmod(socket_path, 0660);

    if (::listen(listener, 16) < 0) {
        std::fprintf(stderr, "engine: cannot listen on %s: %s\n", socket_path, std::strerror(errno));
        return 1;
    }

    aztcast::Session session;
    std::fprintf(stderr, "engine: ready on %s (libtorrent %s)\n", socket_path, LIBTORRENT_VERSION);
    std::fflush(stderr);

    while (aztcast::running) {
        int client = ::accept(listener, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            break;
        }
        // A thread per download rather than a pool. The count is bounded by what the API will run
        // at once, each spends its life asleep between two-second polls, and a pool would add a
        // queue whose only job would be to delay downloads that are already slow.
        std::thread([client, &session] { aztcast::serve(client, session); }).detach();
    }

    ::close(listener);
    ::unlink(socket_path);
    return 0;
}
