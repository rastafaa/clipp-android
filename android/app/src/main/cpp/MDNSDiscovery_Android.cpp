// Android DNS-SD discovery.
//
// The wire protocol is identical to the desktop/iOS builds (MDNSDiscovery.h):
// `_clipp._tcp` instances named `clipp-<8hex>` whose TXT record carries the
// encrypted PacketV1 (MDNSProtocol). Android has no native mDNS API, so the
// actual browse/register/resolve runs in Kotlin on NsdManager
// (NsdDiscovery.kt). This file owns everything protocol-shaped: building and
// decrypting the TXT payload, self/collision filtering, and the
// instance -> hostId bookkeeping that turns per-instance NSD events into the
// per-hostId Added/Removed events NetworkRuntime expects (same rules as
// MDNSDiscovery_Apple.mm).

#include "AndroidBridge.h"

#include "HostId.h"
#include "Logger.h"
#include "MDNSDiscovery.h"
#include "MDNSProtocol.h"
#include "OsType.h"
#include "Settings.h"

#include <sodium.h>

#include <atomic>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include <arpa/inet.h>

extern Settings g_settings;

namespace MDNSDiscovery {
namespace {

struct ResolvedInstance {
    bool resolved = false;
    HostId hostId;
    std::string deviceName;
};

struct State {
    std::mutex mutex;
    Callback callback = nullptr;
    bool running = false;
    bool publishing = false;
    bool includeSelf = false;
    HostId localHostId;
    std::string publishedNameLower;
    std::map<std::string, ResolvedInstance> liveInstances;
    std::map<HostId, std::set<std::string>> liveByHostId;
    std::atomic<bool> collisionWarning{ false };
};

State& S() {
    static State state;
    return state;
}

std::string LowerAscii(std::string s) {
    for (auto& c : s) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return s;
}

// Random, non-identifying label per publish (see MDNSDiscovery_Apple.mm).
std::string MakeInstanceName() {
    unsigned char raw[4]{};
    randombytes_buf(raw, sizeof(raw));
    static const char kHex[] = "0123456789abcdef";
    std::string name = "clipp-";
    for (unsigned char b : raw) {
        name.push_back(kHex[b >> 4]);
        name.push_back(kHex[b & 0x0F]);
    }
    return name;
}

jobjectArray ToStringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    for (size_t i = 0; i < values.size(); ++i) {
        jstring s = env->NewStringUTF(values[i].c_str());  // TXT keys/values are ASCII (base64)
        env->SetObjectArrayElement(array, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(stringClass);
    return array;
}

// Builds the local TXT record and asks Kotlin to register it. Returns false when
// there is nothing to publish yet (no host id or no group key).
bool PublishLocked(State& s) {
    HostId hostId;
    if (!g_settings.getHostID(hostId)) {
        g_logger.log("MDNSDiscovery::publish", Logger::Level::Error, "DNS-SD: no host id, cannot publish.");
        return false;
    }
    s.localHostId = hostId;

    const MDNSProtocol::PacketV1 packet =
        MDNSProtocol::BuildLocalPacket(MDNSProtocol::GetLocalDeviceName(), hostId, GetLocalOsType());
    std::map<std::string, std::string> txt;
    if (!MDNSProtocol::EncodeTxt(packet, txt)) {
        g_logger.log("MDNSDiscovery::publish", Logger::Level::Debug, "DNS-SD: skipping publish (no network key yet).");
        return false;
    }

    std::vector<std::string> keys;
    std::vector<std::string> values;
    for (const auto& [key, value] : txt) {
        keys.push_back(key);
        values.push_back(value);
    }

    const std::string instanceName = MakeInstanceName();
    s.publishedNameLower = LowerAscii(instanceName);

    JNIEnv* env = clipp_android::Env();
    if (env == nullptr) {
        return false;
    }
    jstring jName = env->NewStringUTF(instanceName.c_str());
    jobjectArray jKeys = ToStringArray(env, keys);
    jobjectArray jValues = ToStringArray(env, values);
    clipp_android::CallStaticVoid("mdnsPublish", "(Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;)V",
                                  jName, static_cast<jint>(g_settings.tcpPort()), jKeys, jValues);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jKeys);
    env->DeleteLocalRef(jValues);
    s.publishing = true;
    g_logger.log("MDNSDiscovery::publish", Logger::Level::Info, "DNS-SD publishing as '%s'.", instanceName.c_str());
    return true;
}

void UnpublishLocked(State& s) {
    if (s.publishing) {
        clipp_android::CallStaticVoid("mdnsUnpublish", "()V");
        s.publishing = false;
    }
    s.publishedNameLower.clear();
}

}  // namespace

bool Start(Callback callback, bool publishLocal, bool includeSelf) {
    State& s = S();
    std::lock_guard<std::mutex> lock(s.mutex);
    if (s.running) {
        UnpublishLocked(s);
        clipp_android::CallStaticVoid("mdnsBrowseStop", "()V");
    }
    s.callback = callback;
    s.includeSelf = includeSelf;
    s.collisionWarning.store(false);
    s.liveInstances.clear();
    s.liveByHostId.clear();
    g_settings.getHostID(s.localHostId);

    if (publishLocal) {
        PublishLocked(s);
    }
    clipp_android::CallStaticVoid("mdnsBrowseStart", "()V");
    s.running = true;
    return true;
}

void Stop() {
    State& s = S();
    std::lock_guard<std::mutex> lock(s.mutex);
    if (!s.running) {
        return;
    }
    UnpublishLocked(s);
    clipp_android::CallStaticVoid("mdnsBrowseStop", "()V");
    s.callback = nullptr;
    s.running = false;
    s.liveInstances.clear();
    s.liveByHostId.clear();
}

void Republish() {
    State& s = S();
    std::lock_guard<std::mutex> lock(s.mutex);
    if (!s.running || !s.publishing) {
        return;
    }
    UnpublishLocked(s);
    PublishLocked(s);
}

bool HasHostIDCollisionWarning() { return S().collisionWarning.load(); }

void ClearHostIDCollisionWarning() { S().collisionWarning.store(false); }

// One-shot browse is used only by the CLI verbs and the iOS share extension,
// neither of which exists on Android: the app always runs the full daemon.
bool BrowseStream(std::chrono::milliseconds, bool, const std::function<bool(const DiscoveredPeer&)>&) {
    return false;
}

}  // namespace MDNSDiscovery

void ClippAndroidMdnsRegistered(const std::string& instanceName) {
    // NsdManager may rename us on a conflict; keep the self-filter in sync.
    auto& s = MDNSDiscovery::S();
    std::lock_guard<std::mutex> lock(s.mutex);
    if (s.publishing) {
        s.publishedNameLower = MDNSDiscovery::LowerAscii(instanceName);
    }
}

void ClippAndroidMdnsResolved(const std::string& instanceName, const std::string& ip, int port,
                              const std::vector<std::pair<std::string, std::string>>& txtPairs) {
    using namespace MDNSDiscovery;
    std::map<std::string, std::string> txt(txtPairs.begin(), txtPairs.end());
    MDNSProtocol::PacketV1 packet;
    if (!MDNSProtocol::DecodeTxt(txt, packet)) {
        g_logger.log("MDNSDiscovery::resolve", Logger::Level::DDebug,
                     "DNS-SD: ignoring '%s' (TXT did not decrypt).", instanceName.c_str());
        return;
    }

    DiscoveredPeer peer;
    packet.deviceName[sizeof(packet.deviceName) - 1] = '\0';
    peer.deviceName = std::string(packet.deviceName);
    peer.hostId = HostId(packet.hostId);
    peer.ip = ip;
    peer.port = static_cast<uint16_t>(port);
    peer.osType = static_cast<OsType>(ntohs(packet.osType));
    std::memcpy(peer.caps, packet.caps, sizeof(peer.caps));

    State& s = S();
    Callback cb = nullptr;
    {
        std::lock_guard<std::mutex> lock(s.mutex);
        if (!s.running) {
            return;
        }
        const std::string nameKey = LowerAscii(instanceName);
        if (peer.hostId == s.localHostId && !s.includeSelf) {
            if (!s.publishedNameLower.empty() && nameKey != s.publishedNameLower) {
                s.collisionWarning.store(true);
                g_logger.log("MDNSDiscovery::resolve", Logger::Level::Warning,
                             "DNS-SD: possible host ID collision (peer '%s' shares our host ID).",
                             instanceName.c_str());
            }
            return;
        }
        auto& entry = s.liveInstances[nameKey];
        entry.resolved = true;
        entry.hostId = peer.hostId;
        entry.deviceName = peer.deviceName;
        s.liveByHostId[peer.hostId].insert(nameKey);
        cb = s.callback;
    }
    // Added fires on every resolve; NetworkRuntime's reconciler dedups endpoints.
    if (cb) cb(Event::Added, peer);
}

void ClippAndroidMdnsLost(const std::string& instanceName) {
    using namespace MDNSDiscovery;
    State& s = S();
    Callback cb = nullptr;
    DiscoveredPeer peer;
    {
        std::lock_guard<std::mutex> lock(s.mutex);
        auto it = s.liveInstances.find(LowerAscii(instanceName));
        if (it == s.liveInstances.end()) {
            return;
        }
        const ResolvedInstance gone = it->second;
        s.liveInstances.erase(it);
        if (!gone.resolved) {
            return;
        }
        auto setIt = s.liveByHostId.find(gone.hostId);
        if (setIt != s.liveByHostId.end()) {
            setIt->second.erase(LowerAscii(instanceName));
            if (!setIt->second.empty()) {
                return;  // another instance of the same peer is still live
            }
            s.liveByHostId.erase(setIt);
        }
        peer.hostId = gone.hostId;
        peer.deviceName = gone.deviceName;
        cb = s.callback;
    }
    if (cb) cb(Event::Removed, peer);
}
