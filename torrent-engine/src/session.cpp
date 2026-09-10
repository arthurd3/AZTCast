#include "session.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <thread>

#include <libtorrent/alert_types.hpp>
#include <libtorrent/settings_pack.hpp>

#include "download.hpp"

namespace aztcast {

namespace {

void log(const std::string& message) {
    // stderr, unbuffered by convention, because this process has no log file of its own: it runs
    // under a supervisor that collects streams, and the API's own log is where its downloads are
    // narrated. Anything printed here is about the engine, not about a video.
    std::fprintf(stderr, "engine: %s\n", message.c_str());
    std::fflush(stderr);
}

int encryption_policy(const std::string& name) {
    if (name == "REQUIRE_ENCRYPTED") return lt::settings_pack::pe_forced;
    if (name == "PREFER_PLAINTEXT" || name == "REQUIRE_PLAINTEXT") return lt::settings_pack::pe_disabled;
    return lt::settings_pack::pe_enabled;
}

}  // namespace

Session::Session() {
    lt::settings_pack pack;
    // Quiet by default. The categories this actually consumes are switched on here rather than
    // left at libtorrent's defaults, because the peer category is chatty enough that leaving the
    // rest on would bury it.
    // connect is not optional despite the name suggesting it is about connecting: peer_connect_alert
    // and peer_disconnected_alert both live there, and they are the only source of two of the five
    // peer observations. Subscribed to status and peer alone, this engine downloaded perfectly and
    // reported a provider log missing every DISCOVERED and DISCONNECTED row.
    pack.set_int(lt::settings_pack::alert_mask,
                 lt::alert_category::error | lt::alert_category::status | lt::alert_category::peer |
                     lt::alert_category::connect | lt::alert_category::ip_block);
    pack.set_str(lt::settings_pack::user_agent, "AZTCast/0.1 libtorrent/" LIBTORRENT_VERSION);
    session_ = std::make_unique<lt::session>(lt::session_params(pack));
    alert_thread_ = std::thread([this] { pump_alerts(); });
}

Session::~Session() {
    stopping_ = true;
    if (alert_thread_.joinable()) {
        // Wakes the pump out of wait_for_alert so the join does not sit for the full timeout.
        session_->post_session_stats();
        alert_thread_.join();
    }
}

bool Session::configure(const NetworkSettings& settings) {
    std::lock_guard<std::mutex> guard(configured_mutex_);
    if (configured_) {
        return false;
    }

    lt::settings_pack pack;
    std::string interfaces = settings.acceptor_address.empty()
                                 ? "0.0.0.0:" + std::to_string(settings.acceptor_port) + ",[::]:" +
                                       std::to_string(settings.acceptor_port)
                                 : settings.acceptor_address + ":" + std::to_string(settings.acceptor_port);
    pack.set_str(lt::settings_pack::listen_interfaces, interfaces);

    int policy = encryption_policy(settings.encryption);
    pack.set_int(lt::settings_pack::out_enc_policy, policy);
    pack.set_int(lt::settings_pack::in_enc_policy, policy);

    pack.set_bool(lt::settings_pack::enable_lsd, !settings.disable_local_service_discovery);

    // ---- throughput, sized from the host rather than from a number someone liked ----
    //
    // libtorrent 2.0 removed the user-space disk cache; the OS page cache does that job now, so
    // there is nothing to size there. What is left is how many threads do the work, and at high
    // rates the bottleneck is hashing every downloaded byte rather than the I/O itself -- which is
    // why 2.0 split hashing_threads out of aio_threads in the first place.
    const int cores = static_cast<int>(std::max(1u, std::thread::hardware_concurrency()));
    pack.set_int(lt::settings_pack::aio_threads, std::clamp(cores, 4, 32));
    pack.set_int(lt::settings_pack::hashing_threads, std::clamp(cores / 2, 1, 16));

    // A season pack is hundreds of files and the default pool is 40, so the ones being written get
    // opened and closed repeatedly. Cheap to raise; it is a table of file handles.
    pack.set_int(lt::settings_pack::file_pool_size, 200);
    pack.set_int(lt::settings_pack::max_queued_disk_bytes, 16 * 1024 * 1024);

    // uTP yields to TCP by default, which is right when you are sharing a link with someone's video
    // call and wrong when the machine is doing one thing. This is the "one thing" deployment.
    pack.set_int(lt::settings_pack::mixed_mode_algorithm, lt::settings_pack::prefer_tcp);
    pack.set_int(lt::settings_pack::send_socket_buffer_size, 1024 * 1024);
    pack.set_int(lt::settings_pack::recv_socket_buffer_size, 1024 * 1024);

    // How many peers a tracker is asked for, and how fast the swarm is dialled once it answers.
    // Without the boost, libtorrent opens connections at connection_speed per second and a fresh
    // magnet spends its first half-minute finding people rather than downloading from them.
    pack.set_int(lt::settings_pack::num_want, settings.peers_per_tracker_request);
    pack.set_int(lt::settings_pack::connection_speed,
                 std::clamp(settings.max_pending_connection_requests / 4, 30, 200));
    pack.set_int(lt::settings_pack::torrent_connect_boost,
                 std::clamp(settings.max_pending_connection_requests, 30, 300));

    // The peer and connect categories are chatty on a large swarm and the default queue is 1000.
    // An overflow drops alerts silently, which would cost peer sightings rather than bytes -- but
    // silently is the part worth avoiding.
    pack.set_int(lt::settings_pack::alert_queue_size, 4000);
    // DHT stays on and bootstraps to the public routers, exactly as the runtime it replaces did.
    pack.set_bool(lt::settings_pack::enable_dht, true);
    pack.set_int(lt::settings_pack::connections_limit, settings.max_peer_connections);
    pack.set_int(lt::settings_pack::tracker_completion_timeout, settings.tracker_timeout_seconds);
    pack.set_int(lt::settings_pack::tracker_receive_timeout, settings.tracker_timeout_seconds);

    session_->apply_settings(pack);
    configured_ = true;

    // Said out loud because the runbook tells operators to reach for it when downloads are slow,
    // and under this engine it does nothing. libtorrent has no per-torrent "active peers" dial:
    // it decides for itself which of a torrent's connections to request pieces from. Mapping it to
    // something that merely sounds similar would be worse than saying so.
    if (settings.max_active_peer_connections_per_torrent > 0) {
        log("note: max-active-peer-connections-per-torrent has no equivalent here and is ignored; "
            "max-peer-connections and max-peer-connections-per-torrent are the ceilings that apply");
    }

    log("listening on " + interfaces + " encryption=" + settings.encryption +
        " lsd=" + (settings.disable_local_service_discovery ? "off" : "on") +
        " pex=" + (settings.disable_peer_exchange ? "off" : "on") + " aio=" +
        std::to_string(std::clamp(cores, 4, 32)) + " hashing=" +
        std::to_string(std::clamp(cores / 2, 1, 16)));
    return true;
}

void Session::attach(const lt::sha1_hash& hash, Download* download) {
    std::lock_guard<std::mutex> guard(downloads_mutex_);
    downloads_[hash] = download;
}

void Session::detach(const lt::sha1_hash& hash) {
    std::lock_guard<std::mutex> guard(downloads_mutex_);
    downloads_.erase(hash);
}

std::string Session::address_peers_see() {
    std::lock_guard<std::mutex> guard(self_mutex_);
    return address_peers_see_;
}

void Session::pump_alerts() {
    std::vector<lt::alert*> alerts;
    while (!stopping_) {
        session_->wait_for_alert(std::chrono::milliseconds(500));
        session_->pop_alerts(&alerts);
        for (lt::alert* alert : alerts) {
            // external_ip_alert is session-wide: it is what a remote peer told us our address looks
            // like from outside, which is the only direct evidence available that egress is masked.
            if (auto* ip = lt::alert_cast<lt::external_ip_alert>(alert)) {
                std::lock_guard<std::mutex> guard(self_mutex_);
                address_peers_see_ = ip->external_address.to_string();
                continue;
            }

            // dynamic_cast, not alert_cast. alert_cast compares alert::type() against one concrete
            // alert's id, so asking it for a base class always answers null — and torrent_alert is
            // a base class. Written with alert_cast this loop silently dropped every torrent alert
            // there is: metadata, finished, error and both peer edges. The download still ran,
            // because progress and most peer facts come from polling, which is exactly why it
            // looked like it worked.
            lt::torrent_handle handle;
            if (auto* torrent = dynamic_cast<lt::torrent_alert*>(alert)) {
                handle = torrent->handle;
            } else {
                continue;
            }
            if (!handle.is_valid()) {
                continue;
            }

            Download* download = nullptr;
            {
                std::lock_guard<std::mutex> guard(downloads_mutex_);
                auto found = downloads_.find(handle.info_hashes().v1);
                if (found == downloads_.end()) {
                    continue;
                }
                download = found->second;
            }

            if (auto* connected = lt::alert_cast<lt::peer_connect_alert>(alert)) {
                download->on_peer_connect(connected->endpoint.address().to_string(),
                                          connected->endpoint.port());
            } else if (auto* gone = lt::alert_cast<lt::peer_disconnected_alert>(alert)) {
                download->on_peer_disconnect(gone->endpoint.address().to_string(), gone->endpoint.port());
            } else if (lt::alert_cast<lt::metadata_received_alert>(alert) != nullptr) {
                download->on_metadata();
            } else if (lt::alert_cast<lt::torrent_finished_alert>(alert) != nullptr) {
                download->on_finished();
            } else if (auto* failed = lt::alert_cast<lt::torrent_error_alert>(alert)) {
                download->on_error(failed->error.message());
            }
        }
        alerts.clear();
    }
}

}  // namespace aztcast
