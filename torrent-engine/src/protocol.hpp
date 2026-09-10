// The wire contract between streaming-api and this engine.
//
// One connection per download, and that is the whole design. There are no request ids, no
// multiplexing and no correlation to get wrong: the connection *is* the download. It opens when one
// starts, carries its events, and closes when it ends — and a client that hangs up has, by that
// act, cancelled the download. The alternative, one long-lived multiplexed connection, buys nothing
// here and costs a correlation layer on both sides.
//
// Newline-delimited JSON in both directions. Not because it is fast — it is not — but because it is
// the format both ends already have a library for, and because a protocol you can read with `nc`
// while it is failing is worth more than the microseconds. Nothing on this socket is hot: a few
// hundred events over hours of downloading.
//
//   ->  {"type":"start", ...}                  exactly one, first line
//   <-  {"type":"started","infoHash":"..."}    exactly one, unless the start is rejected
//   <-  {"type":"progress","percent":42}       0..100, monotonic, only on change
//   <-  {"type":"peer", ...}                   many
//   <-  {"type":"self","address":"..."}        when the external address becomes known
//   <-  {"type":"done","file":"/path/to.mkv"}  terminal
//   <-  {"type":"error","message":"..."}       terminal
//
// The `file` in `done` is a path in a filesystem both processes can see, and the client is expected
// to distrust it: this side is the one with its hands in the swarm, so the API re-checks that what
// comes back is inside the media root it asked for. See BrokeredTorrentDownloader.
#pragma once

#include <mutex>
#include <string>

#include <nlohmann/json.hpp>

namespace aztcast {

using json = nlohmann::json;

// One client connection, and the only thing allowed to write to its socket.
//
// Two threads emit on the same connection — the poller sampling torrent status, and the shared
// alert pump dispatching peer events — so writes are serialised here rather than at each call site.
// A JSON line interleaved with another is not a recoverable parse error on the far end; it is a
// client that stops understanding its own download.
class Conn {
public:
    explicit Conn(int fd) : fd_(fd) {}

    // Writes one line. Returns false once the peer has gone, which is how a cancelled download is
    // noticed: the client closing the socket is the cancellation signal, and every writer checks.
    bool send(const json& message);

    int fd() const { return fd_; }

    // True once a write has failed, so pollers can stop without each having to try again.
    bool broken() const { return broken_; }

private:
    int fd_;
    std::mutex write_mutex_;
    bool broken_ = false;
};

}  // namespace aztcast
