#include "download.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <sstream>
#include <thread>

#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/torrent_flags.hpp>
#include <libtorrent/peer_info.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>

namespace aztcast {

namespace {

constexpr auto POLL_INTERVAL = std::chrono::seconds(2);

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return value;
}

std::string extension_of(const std::string& path) {
    auto dot = path.find_last_of('.');
    auto slash = path.find_last_of('/');
    if (dot == std::string::npos || (slash != std::string::npos && dot < slash)) {
        return "";
    }
    return lower(path.substr(dot + 1));
}

// What the peer said it can do, in the vocabulary PeerObservation already uses. Self-reported and
// as spoofable as everything else a peer volunteers; useful for the shape of a swarm, not for
// identifying anyone.
std::vector<std::string> capabilities_of(const lt::peer_info& peer) {
    std::vector<std::string> capabilities;
    if (peer.flags & lt::peer_info::dht) capabilities.emplace_back("DHT");
    if (peer.flags & lt::peer_info::supports_extensions) capabilities.emplace_back("EXTENDED");
    if (peer.flags & lt::peer_info::pex) capabilities.emplace_back("PEX");
    if (peer.flags & lt::peer_info::utp_socket) capabilities.emplace_back("UTP");
    if (peer.flags & lt::peer_info::rc4_encrypted || peer.flags & lt::peer_info::plaintext_encrypted) {
        capabilities.emplace_back("ENCRYPTED");
    }
    return capabilities;
}

std::string endpoint_key(const std::string& ip, int port) {
    return ip + ":" + std::to_string(port);
}

}  // namespace

Download::Download(Session& session, Conn& conn, StartRequest request)
    : session_(session), conn_(conn), request_(std::move(request)) {}

Download::~Download() {
    if (handle_.is_valid()) {
        session_.detach(hash_);
        // Removes the torrent but keeps what it wrote. The files are the point; the session should
        // not be left holding a torrent nobody is watching, which is how a stalled magnet used to
        // leak a connection pool for the life of the process.
        session_.handle().remove_torrent(handle_);
    }
}

void Download::run() {
    lt::error_code parse_error;
    lt::add_torrent_params params = lt::parse_magnet_uri(request_.magnet, parse_error);
    if (parse_error) {
        conn_.send({{"type", "error"}, {"message", "magnet is not parseable: " + parse_error.message()}});
        return;
    }
    params.save_path = request_.target_dir;

    // Peer exchange, off when asked. This used to be parsed, logged as "pex=off", and then not
    // done -- the docker profile sets it for a stated privacy reason (PEX trades peer lists with
    // everyone connected, broadcasting the swarm's membership further than the trackers already
    // do) and the engine affirmed a property it was not delivering. A per-torrent flag rather than
    // a session built without default plugins: same effect, and it keeps the setting where the
    // rest of the per-download configuration already is.
    if (request_.network.disable_peer_exchange) {
        params.flags |= lt::torrent_flags::disable_pex;
    }
    // Rarest-first is libtorrent's default and the right one here: sequential only pays when
    // something consumes partial data, and nothing does — the transcode starts after the last piece.
    lt::error_code add_error;
    handle_ = session_.handle().add_torrent(std::move(params), add_error);
    if (add_error || !handle_.is_valid()) {
        conn_.send({{"type", "error"}, {"message", "could not add torrent: " + add_error.message()}});
        return;
    }

    hash_ = handle_.info_hashes().v1;
    handle_.set_max_connections(request_.network.max_peer_connections_per_torrent);
    session_.attach(hash_, this);

    // The stream operator is the public way to the hex form; sha1_hash::to_string() hands back the
    // twenty raw bytes, which would go onto the wire as mojibake and match no infohash anywhere.
    std::ostringstream hex;
    hex << hash_;
    if (!conn_.send({{"type", "started"}, {"infoHash", hex.str()}})) {
        return;
    }

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(request_.timeout_seconds);
    while (true) {
        if (conn_.broken()) {
            // The client hung up, which is how a download is cancelled. Nothing to report to.
            std::fprintf(stderr, "engine: client for %s went away, abandoning\n", request_.video_id.c_str());
            return;
        }
        {
            std::lock_guard<std::mutex> guard(state_mutex_);
            if (failed_) {
                conn_.send({{"type", "error"}, {"message", failure_}});
                return;
            }
        }
        if (std::chrono::steady_clock::now() > deadline) {
            conn_.send({{"type", "error"},
                        {"message", "timed out after " + std::to_string(request_.timeout_seconds) + "s"}});
            return;
        }

        poll_progress();
        poll_peers();

        bool done;
        {
            std::lock_guard<std::mutex> guard(state_mutex_);
            done = finished_;
        }
        if (done) {
            // One last sample before anything is torn down. This is the only moment at which the
            // final byte counts exist and the torrent is still being attributed to this video.
            poll_peers();
            std::string file = locate_largest_video();
            if (file.empty()) {
                conn_.send({{"type", "error"}, {"message", "no video file found under " + request_.target_dir}});
            } else {
                conn_.send({{"type", "done"}, {"file", file}});
            }
            return;
        }

        std::this_thread::sleep_for(POLL_INTERVAL);
    }
}

void Download::poll_progress() {
    lt::torrent_status status = handle_.status();
    int percent = static_cast<int>(status.progress * 100.0f);
    if (percent > 100) percent = 100;
    // Monotonic and only on change, matching the contract TorrentDownloader states: a consumer is
    // promised it never sees a lower number than it was last given.
    if (percent > last_percent_) {
        last_percent_ = percent;
        conn_.send({{"type", "progress"}, {"percent", percent}});
    }

    if (!self_reported_) {
        std::string address = session_.address_peers_see();
        if (!address.empty()) {
            self_reported_ = true;
            conn_.send({{"type", "self"}, {"address", address}});
        }
    }
}

void Download::poll_peers() {
    // Nobody is listening, so none of this is worth doing. The provider log is off by default,
    // which binds the API's sink to a no-op lambda -- and the engine had no way to know, so it
    // built every event, wrote every event, and the JVM parsed every event to hand it to a
    // function that discards it. The client now says so in the start request.
    if (!request_.peer_events) {
        return;
    }

    std::string batch;
    std::vector<lt::peer_info> peers;
    handle_.get_peer_info(peers);

    // Hoisted out of the loop below, where it used to sit. The piece count does not change once
    // metadata has arrived, but it was being fetched once per peer per tick -- 200 calls every two
    // seconds on a healthy swarm, all but a handful of them discarded unread. torrent_file() also
    // returns a shared_ptr by value and may be a synchronous call into libtorrent's own network
    // thread, which would mean the poller was contending with the transfer it exists to measure.
    int pieces_total = 0;
    if (auto info = handle_.torrent_file()) {
        pieces_total = info->num_pieces();
    }

    for (const lt::peer_info& peer : peers) {
        const std::string ip = peer.ip.address().to_string();
        const int port = peer.ip.port();
        const std::string key = endpoint_key(ip, port);

        bool announce_connected = false;
        bool announce_pieces = false;
        bool announce_transfer = false;
        int pieces_complete = static_cast<int>(peer.pieces.count());

        {
            std::lock_guard<std::mutex> guard(state_mutex_);
            Seen& seen = seen_[key];
            if (!seen.announced_connected) {
                seen.announced_connected = true;
                seen.first = std::chrono::steady_clock::now();
                announce_connected = true;
            }
            if (pieces_complete != seen.pieces_complete) {
                seen.pieces_complete = pieces_complete;
                announce_pieces = true;
            }
            // Only when bytes actually moved. The poll runs every two seconds per peer and most
            // ticks carry nothing new; a TRANSFER for each of them would be noise the provider log
            // has to store and then filter.
            if (peer.total_download != seen.downloaded || peer.total_upload != seen.uploaded) {
                seen.downloaded = peer.total_download;
                seen.uploaded = peer.total_upload;
                announce_transfer = true;
            }
        }

        // Nothing to say about this peer this tick, and most ticks say nothing about most peers.
        // The `common` object below used to be built for every peer before this was checked, so a
        // swarm sitting still still cost two hundred JSON constructions every two seconds.
        if (!announce_connected && !announce_pieces && !announce_transfer) {
            continue;
        }

        json common = {{"client", peer.client},
                       {"capabilities", capabilities_of(peer)}};
        if (announce_connected) {
            append_peer(batch, "CONNECTED", ip, port, common);
        }
        if (announce_pieces && pieces_total > 0) {
            json extra = common;
            extra["piecesComplete"] = pieces_complete;
            extra["piecesTotal"] = pieces_total;
            append_peer(batch, "BITFIELD", ip, port, extra);
        }
        if (announce_transfer) {
            json extra = common;
            extra["bytesDownloaded"] = peer.total_download;
            extra["bytesUploaded"] = peer.total_upload;
            append_peer(batch, "TRANSFER", ip, port, extra);
        }
    }

    // One write for the whole tick rather than one per event.
    conn_.send_lines(batch);
}

/** Serialises one peer event onto the tick's outgoing blob. */
void Download::append_peer(
        std::string& batch, const std::string& kind, const std::string& ip, int port, const json& extra) {
    json message = extra;
    message["type"] = "peer";
    message["kind"] = kind;
    message["ip"] = ip;
    message["port"] = port;
    batch += message.dump();
    batch += '\n';
}

void Download::emit_peer(const std::string& kind, const std::string& ip, int port, const json& extra) {
    json message = extra;
    message["type"] = "peer";
    message["kind"] = kind;
    message["ip"] = ip;
    message["port"] = port;
    conn_.send(message);
}

void Download::on_peer_connect(const std::string& ip, int port) {
    if (!request_.peer_events) {
        return;
    }
    // DISCOVERED rather than CONNECTED, and the difference is worth stating: libtorrent reports the
    // moment it decides to dial a peer, which means the peer was named by a tracker, DHT or PEX and
    // is being acted on. CONNECTED is emitted by the poller once the peer actually appears in the
    // live peer list, which is the point at which a handshake has happened.
    emit_peer("DISCOVERED", ip, port, json::object());
}

void Download::on_peer_disconnect(const std::string& ip, int port) {
    if (!request_.peer_events) {
        return;
    }
    const std::string key = endpoint_key(ip, port);
    json extra = json::object();
    {
        std::lock_guard<std::mutex> guard(state_mutex_);
        auto found = seen_.find(key);
        if (found != seen_.end()) {
            auto lived = std::chrono::steady_clock::now() - found->second.first;
            extra["connectedSeconds"] = static_cast<int>(
                std::chrono::duration_cast<std::chrono::seconds>(lived).count());
            extra["bytesDownloaded"] = found->second.downloaded;
            extra["bytesUploaded"] = found->second.uploaded;
            seen_.erase(found);
        }
    }
    emit_peer("DISCONNECTED", ip, port, extra);
}

void Download::on_metadata() {
    {
        std::lock_guard<std::mutex> guard(state_mutex_);
        if (metadata_ready_) {
            return;
        }
        metadata_ready_ = true;
    }
    choose_files();
}

void Download::on_finished() {
    std::lock_guard<std::mutex> guard(state_mutex_);
    finished_ = true;
}

void Download::on_error(const std::string& message) {
    std::lock_guard<std::mutex> guard(state_mutex_);
    failed_ = true;
    failure_ = message;
}

void Download::choose_files() {
    if (!request_.video_only) {
        return;
    }
    auto info = handle_.torrent_file();
    if (!info) {
        return;
    }
    const lt::file_storage& files = info->files();
    const int count = files.num_files();
    if (count <= 1) {
        // Nothing to skip, and deliberately no attempt to match the single file against the
        // extension list: a one-file torrent is the file that was wanted.
        return;
    }

    int wanted = -1;
    std::int64_t largest = -1;
    for (int i = 0; i < count; ++i) {
        const lt::file_index_t index{i};
        const std::string extension = extension_of(files.file_path(index));
        const bool is_video = std::find(request_.video_extensions.begin(), request_.video_extensions.end(),
                                        extension) != request_.video_extensions.end();
        if (is_video && files.file_size(index) > largest) {
            largest = files.file_size(index);
            wanted = i;
        }
    }
    if (wanted < 0) {
        // No file matched. Take everything rather than nothing: a wrong skip is a torrent that
        // completes without the video in it, which fails the ingestion far downstream with a
        // confusing message. Too much is merely slow. Same reasoning as LargestVideoFileSelector.
        std::fprintf(stderr, "engine: no video file in %s, keeping every file\n", request_.video_id.c_str());
        return;
    }

    std::vector<lt::download_priority_t> priorities(static_cast<std::size_t>(count),
                                                    lt::dont_download);
    priorities[static_cast<std::size_t>(wanted)] = lt::default_priority;
    handle_.prioritize_files(priorities);
    std::fprintf(stderr, "engine: %s keeping %s (%lld bytes) of %d files\n", request_.video_id.c_str(),
                 files.file_path(lt::file_index_t{wanted}).c_str(), static_cast<long long>(largest), count);
}

std::string Download::locate_largest_video() const {
    auto info = handle_.torrent_file();
    if (!info) {
        return "";
    }
    const lt::file_storage& files = info->files();
    std::string best;
    std::int64_t largest = -1;
    for (int i = 0; i < files.num_files(); ++i) {
        const lt::file_index_t index{i};
        if (handle_.file_priority(index) == lt::dont_download) {
            continue;
        }
        const std::string extension = extension_of(files.file_path(index));
        const bool is_video = std::find(request_.video_extensions.begin(), request_.video_extensions.end(),
                                        extension) != request_.video_extensions.end();
        if (is_video && files.file_size(index) > largest) {
            largest = files.file_size(index);
            best = request_.target_dir + "/" + files.file_path(index);
        }
    }
    return best;
}

}  // namespace aztcast
