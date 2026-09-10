#pragma once

#include <chrono>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include <libtorrent/torrent_handle.hpp>

#include "protocol.hpp"
#include "session.hpp"

namespace aztcast {

// What a client asks for on the first line of a connection.
struct StartRequest {
    std::string video_id;
    std::string magnet;
    std::string target_dir;
    bool video_only = true;
    bool peer_events = true;
    std::vector<std::string> video_extensions;
    int timeout_seconds = 7200;
    NetworkSettings network;
};

// One torrent, from magnet to a file on disk, reporting as it goes.
//
// Owned by the thread serving its connection, and touched by the alert pump for peer events — so
// everything the pump reaches is behind a lock, and everything else stays on the serving thread.
class Download {
public:
    Download(Session& session, Conn& conn, StartRequest request);
    ~Download();

    // Runs to completion on the calling thread: adds the torrent, polls it, emits events, and sends
    // exactly one terminal message. Returns when the download is finished, failed, timed out, or the
    // client hung up.
    void run();

    // Called by the alert pump, on its thread. These are the four peer edges libtorrent reports;
    // everything else about a peer is sampled by the poller below.
    void on_peer_connect(const std::string& ip, int port);
    void on_peer_disconnect(const std::string& ip, int port);
    void on_metadata();
    void on_finished();
    void on_error(const std::string& message);

private:
    // A peer we have already told the client about, and what we last said, so that a poll every two
    // seconds does not become a peer event every two seconds per peer.
    struct Seen {
        std::chrono::steady_clock::time_point first;
        long long downloaded = 0;
        long long uploaded = 0;
        int pieces_complete = -1;
        bool announced_connected = false;
    };

    void emit_peer(const std::string& kind, const std::string& ip, int port, const json& extra);
    static void append_peer(
            std::string& batch, const std::string& kind, const std::string& ip, int port, const json& extra);
    void poll_peers();
    void poll_progress();
    void choose_files();
    std::string locate_largest_video() const;

    Session& session_;
    Conn& conn_;
    StartRequest request_;
    lt::torrent_handle handle_;
    lt::sha1_hash hash_;

    std::mutex state_mutex_;
    std::map<std::string, Seen> seen_;
    bool finished_ = false;
    bool failed_ = false;
    std::string failure_;
    bool metadata_ready_ = false;

    int last_percent_ = -1;
    bool self_reported_ = false;
};

}  // namespace aztcast
