#pragma once

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

#include "ClipboardPayload.h"
#include "Listener.h"
#include "HostId.h"
#include "OsType.h"
#include "MDNSDiscovery.h"

class NetworkRuntime {
public:
    NetworkRuntime();
    ~NetworkRuntime();

    NetworkRuntime(const NetworkRuntime&) = delete;
    NetworkRuntime& operator=(const NetworkRuntime&) = delete;

    bool Start();
    void Stop();
    bool Restart();

private:
    void ThreadProc();
    void OnClipboardReceived(std::shared_ptr<const ClipboardPayload> payload);

    // Event-driven runtime work. The thread sleeps until woken; nothing polls.
    //  - SignalWake: wake the thread (from the network-change monitor, the reconciler's
    //    deferred-removal path, or Stop). `interfacesChanged` means "re-hash interfaces".
    //  - ComputeNextWake: the soonest deadline the thread must wake for (pending republish
    //    cooldown, or a deferred peer removal); nullopt = sleep until explicitly woken.
    //  - ProcessWake: re-hash if flagged, republish if pending+cooled-down, sweep removals.
    void SignalWake(bool interfacesChanged);
    std::optional<std::chrono::steady_clock::time_point> ComputeNextWake();
    void ProcessWake(std::chrono::steady_clock::time_point now);

    static void OnDiscoveryEvent(MDNSDiscovery::Event event, const MDNSDiscovery::DiscoveredPeer& peer);

    Listener listener_;
    std::thread thread_;
    mutable std::mutex mutex_;
    std::condition_variable stopCV_;
    bool stopRequested_ = false;
    bool stopping_ = false;

    // Wake coordination (guarded by mutex_). wakePending_ = "re-evaluate now";
    // interfacesChanged_ = "the network-change monitor fired, re-hash".
    bool wakePending_ = false;
    bool interfacesChanged_ = false;

    // Touched only by the runtime thread (Stop() joins it before anyone else looks).
    uint64_t lastAddrHash_ = 0;       // fingerprint last acted on
    uint64_t pendingHash_ = 0;        // latest fingerprint awaiting a (cooled-down) republish
    bool republishPending_ = false;
    std::chrono::steady_clock::time_point lastRepublish_{};
    // Whether DNS-SD discovery is actually up. Start can fail outright on an
    // ifaceless boot (DnsServiceBrowse -> ERROR_NO_NETWORK); the fingerprint-
    // change path then attempts a full re-Start instead of a Republish (which
    // would address a component that never lived).
    bool discoveryRunning_ = false;
};

extern NetworkRuntime g_networkRuntime;

// Connect-back (fork addition). Called by an incoming Peer once the remote has
// authenticated. Clipp connections carry items one way only (the dialer sends),
// so a device that can reach us but that we never discovered over DNS-SD
// (multicast filtered by the Wi-Fi/LAN bridge, a router quirk, etc.) would
// otherwise receive nothing from us. If discovery isn't already tracking that
// host, we dial it back at its address on our configured TCP port.
void NetworkRuntimeNoteAuthenticatedIncoming(const HostId& hostId, const std::string& hostNameUtf8,
                                             OsType osType, const std::string& ipUtf8);
