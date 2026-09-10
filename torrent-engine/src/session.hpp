#pragma once

#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <libtorrent/session.hpp>
#include <libtorrent/sha1_hash.hpp>

#include "protocol.hpp"

namespace aztcast {

class Download;

// Settings that arrive with a start request, mapped from aztcast.streaming.torrent.network.
//
// They come over the wire rather than from this process's own flags so that application.yml stays
// the single place the swarm is configured. The cost is stated plainly in the ADR and here: the
// session is shared, so only the *first* start request's settings take effect. Later ones are
// logged and ignored rather than silently applied to somebody else's download.
struct NetworkSettings {
    std::string encryption = "PREFER_ENCRYPTED";
    bool disable_local_service_discovery = true;
    bool disable_peer_exchange = false;
    std::string acceptor_address;
    int acceptor_port = 6891;
    int max_peer_connections_per_torrent = 200;
    int max_active_peer_connections_per_torrent = 60;
    int max_peer_connections = 600;
    int peers_per_tracker_request = 200;
    int tracker_timeout_seconds = 8;
};

// The one libtorrent session, shared by every download.
//
// Shared for the same reason ADR-0023 gives for the runtime it replaces: a session owns the listen
// socket, the DHT node and the peer pool, and standing up one per download would mean a new DHT
// bootstrap and a new port for every magnet. It outlives every download, and is torn down only when
// the process ends.
class Session {
public:
    Session();
    ~Session();

    // Applies settings if this is the first caller to bring any, and says whether it did. Later
    // callers get false and a log line, never a silently reconfigured session.
    bool configure(const NetworkSettings& settings);

    lt::session& handle() { return *session_; }

    // Registers a download so the alert pump can find it by info hash. Alerts are session-wide and
    // carry a torrent handle, not a connection, so this map is how a peer event reaches the one
    // client that asked for that torrent.
    void attach(const lt::sha1_hash& hash, Download* download);
    void detach(const lt::sha1_hash& hash);

    // The address remote peers report seeing us as, or empty until one says. Session-wide because
    // it is a fact about this host rather than about any torrent, and it is the only direct evidence
    // that outbound traffic is masked.
    std::string address_peers_see();

private:
    void pump_alerts();

    std::unique_ptr<lt::session> session_;
    std::thread alert_thread_;
    std::atomic<bool> stopping_{false};

    std::mutex downloads_mutex_;
    std::map<lt::sha1_hash, Download*> downloads_;

    std::mutex configured_mutex_;
    bool configured_ = false;

    std::mutex self_mutex_;
    std::string address_peers_see_;
};

}  // namespace aztcast
