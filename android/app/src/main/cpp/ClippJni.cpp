// JNI surface between the Kotlin app (net.clipp.android.ClippNative) and the
// shared Clipp core. The Android counterpart of ios/Clipp/Bridge/ClippBridge.mm:
// same lifecycle (sodium -> host id -> registers -> runtime), same pairing and
// send/re-share semantics, plus delete-everywhere with a one-deep undo (the
// desktop's DeleteActivityItemEverywhere / UndoDelete, which iOS doesn't expose).
//
// Conventions:
//  * Strings that may hold user text cross the boundary as UTF-8 byte arrays
//    (JNI's NewStringUTF/GetStringUTFChars use "modified UTF-8", which mangles
//    emoji). Protocol strings that are always ASCII use jstring.
//  * List-shaped results are returned as UTF-8 JSON so the Kotlin side can
//    parse them with org.json; no per-field JNI object construction.
//  * Native -> Kotlin calls happen on core threads. The Kotlin handlers only
//    post work; they never call back into native synchronously.

#include "AndroidBridge.h"

#include "ClipboardActivityStore.h"
#include "ClipboardFormat.h"
#include "ClipboardHashGuard.h"
#include "ClipboardPayload.h"
#include "CryptoChannel.h"
#include "KeyManager.h"
#include "LocalPeerName.h"
#include "Logger.h"
#include "MDNSDiscovery.h"
#include "NetworkDefs.h"
#include "NetworkRuntime.h"
#include "PeerDisplay.h"
#include "PeerManager.h"
#include "RegisterConfig.h"
#include "RegisterPersistenceRuntime.h"
#include "RegisterStore.h"
#include "Settings.h"

#include <sodium.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <signal.h>
#include <string>
#include <vector>

extern Settings g_settings;
extern KeyManager g_keyManager;
extern PeerDisplay g_peerDisplay;
extern PeerManager g_peerManager;
extern NetworkRuntime g_networkRuntime;
extern RegisterStore g_registerStore;
extern ClipboardHashGuard g_clipboardHashGuard;
extern ClipboardActivityStore g_clipboardActivityStore;

// ===========================================================================
// JNI plumbing
// ===========================================================================
namespace clipp_android {
namespace {

JavaVM* g_vm = nullptr;
jclass g_nativeClass = nullptr;

// Detaches threads that Env() attached, when they exit.
struct ThreadDetacher {
    bool attached = false;
    ~ThreadDetacher() {
        if (attached && g_vm != nullptr) {
            g_vm->DetachCurrentThread();
        }
    }
};
thread_local ThreadDetacher t_detacher;

}  // namespace

JNIEnv* Env() {
    if (g_vm == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    const jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    #if defined(__ANDROID__) && !defined(CLIPP_HOST_CHECK)
    JNIEnv** attachOut = &env;            // Android's jni.h takes JNIEnv**
#else
    void** attachOut = reinterpret_cast<void**>(&env);  // desktop JDK jni.h takes void**
#endif
    if (status == JNI_EDETACHED && g_vm->AttachCurrentThread(attachOut, nullptr) == JNI_OK) {
        t_detacher.attached = true;
        return env;
    }
    return nullptr;
}

jclass NativeClass() { return g_nativeClass; }

void CallStaticVoid(const char* name, const char* signature, ...) {
    JNIEnv* env = Env();
    if (env == nullptr || g_nativeClass == nullptr) {
        return;
    }
    jmethodID method = env->GetStaticMethodID(g_nativeClass, name, signature);
    if (method == nullptr) {
        env->ExceptionClear();
        g_logger.log("Android", Logger::Level::Error, "Missing Kotlin callback ClippNative.%s%s", name, signature);
        return;
    }
    va_list args;
    va_start(args, signature);
    env->CallStaticVoidMethodV(g_nativeClass, method, args);
    va_end(args);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

jbyteArray ToJavaBytes(JNIEnv* env, const unsigned char* data, size_t size) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
    if (array != nullptr && size > 0) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
    }
    return array;
}

jbyteArray ToJavaBytes(JNIEnv* env, const std::string& utf8) {
    return ToJavaBytes(env, reinterpret_cast<const unsigned char*>(utf8.data()), utf8.size());
}

std::string FromJavaBytes(JNIEnv* env, jbyteArray bytes) {
    if (bytes == nullptr) {
        return {};
    }
    const jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(out.data()));
    }
    return out;
}

}  // namespace clipp_android

using namespace clipp_android;

// ===========================================================================
// Core lifecycle (mirrors ClippBridge.mm)
// ===========================================================================
namespace {

constexpr int kKindText = 0;
constexpr int kKindPng = 1;
constexpr int kKindJpeg = 2;

std::mutex g_runtimeMutex;
bool g_runtimeStarted = false;

// One-deep undo for delete (desktop UndoSlotKind::Activity).
std::mutex g_undoMutex;
std::shared_ptr<const ClipboardPayload> g_undoPayload;

std::string Utf8(const std::wstring& value) { return clipp_platform_detail::Utf16ToUtf8String(value); }

uint64_t NowMs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}

std::string JsonString(const std::string& value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                char buf[8];
                std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                out += buf;
            } else {
                out.push_back(static_cast<char>(c));
            }
        }
    }
    out += "\"";
    return out;
}

const char* KindName(ClipboardActivityPayloadKind kind) {
    switch (kind) {
    case ClipboardActivityPayloadKind::Text: return "text";
    case ClipboardActivityPayloadKind::PrivateText: return "private";
    case ClipboardActivityPayloadKind::PrivatePlaceholder: return "placeholder";
    case ClipboardActivityPayloadKind::Link: return "link";
    case ClipboardActivityPayloadKind::Image: return "image";
    case ClipboardActivityPayloadKind::Unsupported:
    default: return "unsupported";
    }
}

void ActivityWatcher(const ClipboardActivityUpdate&, void*) {
    CallStaticVoid("onActivityChanged", "()V");
}

void PeerWatcher(const PeerDisplayUpdate&, void*) {
    CallStaticVoid("onPeersChanged", "()V");
}

void ConfigureOnce() {
    static std::once_flag once;
    std::call_once(once, [] {
        g_clipboardActivityStore.SetLimits(g_settings.clipboardHistoryMemoryLimitBytes(),
                                           g_settings.clipboardHistoryMaxAgeSeconds(),
                                           g_settings.clipboardHistoryMaxItems());
        g_clipboardActivityStore.QueryAndRegister(ActivityWatcher, nullptr);
        g_peerDisplay.QueryAndRegister(PeerWatcher, nullptr);
#if CLIPP_REGISTERS_DAEMON
        HostId hostID{};
        g_settings.getHostID(hostID);
        g_registerStore.SetLocalHost(hostID);
        g_registerStore.SetLimits(g_settings.registerTtlSeconds() * 1000ull,
                                  static_cast<size_t>(g_settings.registerMaxCount()),
                                  RegisterStore::kDefaultMaxValueBytes);
        g_registerStore.SeedClockFloor(Hlc{ g_settings.registerHlcFloorMs(), 0 });
#endif
    });
}

bool InitCore() {
    static std::once_flag once;
    static bool ok = false;
    std::call_once(once, [] {
        if (sodium_init() < 0) {
            return;
        }
        HostId hostID;
        if (!g_settings.ensureHostID(hostID)) {
            return;
        }
        signal(SIGPIPE, SIG_IGN);
        ConfigureOnce();
        ok = true;
    });
    return ok;
}

bool StartRuntime() {
    if (!InitCore()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_runtimeMutex);
    if (g_runtimeStarted) {
        return true;
    }
    g_logger.log("Android", Logger::Level::Info, "Starting Clipp networking core (group '%s').",
                 g_settings.networkName().c_str());
#if CLIPP_REGISTERS_DAEMON
    clipp::StartRegisterPersistence();
#endif
    if (!g_networkRuntime.Start()) {
        g_logger.log("Android", Logger::Level::Error, "Unable to start network runtime.");
        return false;
    }
    g_runtimeStarted = true;
    return true;
}

void StopRuntime() {
    std::lock_guard<std::mutex> lock(g_runtimeMutex);
    if (!g_runtimeStarted) {
        return;
    }
    g_networkRuntime.Stop();
    g_peerManager.ClearPeers();
#if CLIPP_REGISTERS_DAEMON
    clipp::StopRegisterPersistence();
#endif
    g_runtimeStarted = false;
    g_logger.log("Android", Logger::Level::Info, "Network runtime stopped.");
}

// Every pairing change funnels through here (see RestartNetworkRuntime in ClippBridge.mm).
void RestartRuntime() {
    std::lock_guard<std::mutex> lock(g_runtimeMutex);
#if CLIPP_REGISTERS_DAEMON
    clipp::StartRegisterPersistence();
    clipp::RegisterPersistenceKeyChanged();
#endif
    if (g_runtimeStarted) {
        g_networkRuntime.Restart();
    } else if (g_keyManager.HaveNetworkKey() && g_networkRuntime.Start()) {
        g_runtimeStarted = true;
    }
}

std::string KeyStatusJson() {
    std::string fingerprint;
    const bool hasKey = g_keyManager.HaveNetworkKey();
    if (hasKey) {
        std::string error;
        fingerprint = Utf8(g_keyManager.GetNetworkFingerprintHash(nullptr, &error));
    }
    HostId hostId;
    g_settings.getHostID(hostId);
    return "{\"networkName\":" + JsonString(g_settings.networkName()) +
           ",\"hasKey\":" + (hasKey ? "true" : "false") +
           ",\"fingerprint\":" + (fingerprint.empty() ? std::string("null") : JsonString(fingerprint)) +
           ",\"deviceName\":" + JsonString(clipp::GetLocalPeerDisplayName("Android", CryptoChannel::HOSTNAME_MAX_BYTES)) +
           ",\"hostId\":" + JsonString(hostId.ToHexString()) + "}";
}

// Publish an outgoing payload: dedup guard, activity store, broadcast.
uint64_t AddAndBroadcast(std::shared_ptr<const ClipboardPayload> payload) {
    g_clipboardHashGuard.RememberCurrent(*payload);
    const uint64_t id = g_clipboardActivityStore.Add(payload);
    g_peerManager.BroadcastClipboard(payload);
    return id;
}

}  // namespace

// Core -> app: an incoming item from the mesh (NetworkRuntime's mobile receive stub;
// the symbol name is shared with iOS). Records it, then hands live items to Kotlin,
// which writes the system clipboard.
void CLPIOSReceiveClipboardPayload(std::shared_ptr<const ClipboardPayload> payload) {
    if (!payload) {
        return;
    }
    const uint32_t format = payload->meta.formatId;
    if (format != CLIPP_FORMAT_UTF8 && !IsClippImageFormat(format)) {
        g_logger.log("Android", Logger::Level::Warning, L"Unsupported incoming clipboard format %ls (%u); ignored.",
                     ClippClipboardFormatNameW(format), format);
        return;
    }
    const bool isReplay = (payload->meta.flags & NetworkDefs::CLPM_FLAG_SYNC_REPLAY) != 0;
    if (!isReplay && !g_clipboardHashGuard.AcceptCurrent(*payload)) {
        g_logger.log("Android", Logger::Level::Debug, L"Ignoring duplicate incoming clipboard payload.");
        return;
    }
    const bool isPlaceholder = (payload->meta.flags & NetworkDefs::CLPM_FLAG_SOURCE_MARKED_PRIVATE) != 0 &&
                               payload->EncodedBytes().empty();
    const uint64_t id = g_clipboardActivityStore.Add(std::move(payload));
    if (!isReplay && !isPlaceholder && id != 0) {
        CallStaticVoid("onItemReceived", "(J)V", static_cast<jlong>(id));
    }
}

// ===========================================================================
// Exports
// ===========================================================================
#define CLIPP_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_net_clipp_android_ClippNative_##name

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    clipp_android::g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = env->FindClass("net/clipp/android/ClippNative");
    if (local == nullptr) {
        return JNI_ERR;
    }
    clipp_android::g_nativeClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return JNI_VERSION_1_6;
}

CLIPP_JNI(jboolean, nativeInit)(JNIEnv*, jclass) {
    return InitCore() ? JNI_TRUE : JNI_FALSE;
}

CLIPP_JNI(jbyteArray, nativeKeyStatus)(JNIEnv* env, jclass) {
    InitCore();
    return ToJavaBytes(env, KeyStatusJson());
}

// Returns null on success, else a UTF-8 error message.
CLIPP_JNI(jbyteArray, nativePair)(JNIEnv* env, jclass, jbyteArray jName, jbyteArray jSecret) {
    if (!InitCore()) {
        return ToJavaBytes(env, std::string("Clipp failed to initialize."));
    }
    std::string name = FromJavaBytes(env, jName);
    std::string secret = FromJavaBytes(env, jSecret);
    auto fail = [&](const std::string& message) {
        sodium_memzero(secret.data(), secret.size());
        return ToJavaBytes(env, message);
    };
    if (name.empty()) {
        return fail("Group name cannot be empty.");
    }
    if (secret.size() < 8) {
        return fail("The passphrase must be at least 8 characters.");
    }
    if (!g_settings.set_networkName(name)) {
        return fail("Unable to store the group name.");
    }
    std::string keyInput = KeyManager::BuildKeyDerivationInput(name, secret);
    sodium_memzero(secret.data(), secret.size());

    KeyManager::NetworkKey key{};
    const bool derived = g_keyManager.DeriveNetworkKey(keyInput, key);
    sodium_memzero(keyInput.data(), keyInput.size());
    if (!derived) {
        return ToJavaBytes(env, std::string("Unable to derive the group key."));
    }
    std::string storageError;
    const bool stored = g_keyManager.SetNetworkKey(key, &storageError);
    sodium_memzero(key.data(), key.size());
    if (!stored) {
        return ToJavaBytes(env, "Unable to store the group key: " + storageError);
    }
    RestartRuntime();
    return nullptr;
}

CLIPP_JNI(void, nativeUnpair)(JNIEnv*, jclass) {
    if (!InitCore()) {
        return;
    }
    g_keyManager.ClearNetworkKey();
    StopRuntime();
}

CLIPP_JNI(jboolean, nativeStart)(JNIEnv*, jclass) {
    if (!InitCore() || !g_keyManager.HaveNetworkKey()) {
        return JNI_FALSE;
    }
    return StartRuntime() ? JNI_TRUE : JNI_FALSE;
}

CLIPP_JNI(void, nativeStop)(JNIEnv*, jclass) {
    StopRuntime();
}

// Sends local content to the mesh. Returns the new activity item id, 0 on failure,
// -1 when not paired.
CLIPP_JNI(jlong, nativeSend)(JNIEnv* env, jclass, jint kind, jbyteArray jData, jboolean sourceMarkedPrivate) {
    if (!InitCore()) {
        return 0;
    }
    if (!g_keyManager.HaveNetworkKey()) {
        return -1;
    }
    StartRuntime();

    std::string data = FromJavaBytes(env, jData);
    if (data.empty()) {
        return 0;
    }
    ClipboardPayload payload;
    std::vector<unsigned char> bytes(data.begin(), data.end());
    switch (kind) {
    case kKindText:
        payload.meta.formatId = CLIPP_FORMAT_UTF8;
        bytes.push_back('\0');  // capture convention: text carries a trailing NUL
        break;
    case kKindPng:
        payload.meta.formatId = CLIPP_FORMAT_PNG;
        break;
    case kKindJpeg:
        payload.meta.formatId = CLIPP_FORMAT_JPEG;
        break;
    default:
        return 0;
    }
    if (!payload.SetUncompressedBytes(std::move(bytes))) {
        return 0;
    }
    if (sourceMarkedPrivate) {
        payload.meta.flags |= NetworkDefs::CLPM_FLAG_SOURCE_MARKED_PRIVATE;
    }
    HostId hostId;
    g_settings.getHostID(hostId);
    const std::string hostName = clipp::GetLocalPeerDisplayName("Android", CryptoChannel::HOSTNAME_MAX_BYTES);
    payload.StampOrigin(hostId, hostName.c_str(), g_settings.nextOriginSequenceNumber());
    auto shared = std::make_shared<const ClipboardPayload>(std::move(payload));
    const uint64_t id = AddAndBroadcast(shared);
    g_logger.log("Android", Logger::Level::Info, L"Sent %ls (%zu bytes).",
                 ClippClipboardFormatNameW(shared->meta.formatId), shared->EncodedBytes().size());
    return static_cast<jlong>(id);
}

CLIPP_JNI(jbyteArray, nativeItems)(JNIEnv* env, jclass) {
    std::string json = "[";
    bool first = true;
    for (const auto& header : g_clipboardActivityStore.Snapshot()) {
        const auto display = g_clipboardActivityStore.DisplayItem(header.id);
        if (!display) {
            continue;
        }
        const auto ts = std::chrono::duration_cast<std::chrono::milliseconds>(
            display->eventTimestamp.time_since_epoch()).count();
        if (!first) json += ",";
        first = false;
        json += "{\"id\":" + std::to_string(header.id) +
                ",\"device\":" + JsonString(Utf8(display->deviceName)) +
                ",\"ts\":" + std::to_string(ts) +
                ",\"out\":" + (display->direction == ClipboardActivityDirection::Outgoing ? "true" : "false") +
                ",\"kind\":\"" + KindName(display->kind) + "\"" +
                ",\"marked\":" + (display->sourceMarked ? "true" : "false") +
                ",\"preview\":" + JsonString(Utf8(display->previewText)) +
                ",\"revealed\":" + JsonString(Utf8(display->revealedPreviewText)) +
                ",\"host\":" + JsonString(Utf8(display->linkHost)) + "}";
    }
    json += "]";
    return ToJavaBytes(env, json);
}

// Format of an item's content: 0 text, 1 PNG, 2 JPEG, -1 gone/unsupported/no content.
CLIPP_JNI(jint, nativeItemFormat)(JNIEnv*, jclass, jlong id) {
    const auto payload = g_clipboardActivityStore.PayloadReference(static_cast<uint64_t>(id));
    if (!payload || payload->EncodedBytes().empty()) {
        return -1;
    }
    switch (payload->meta.formatId) {
    case CLIPP_FORMAT_UTF8: return kKindText;
    case CLIPP_FORMAT_PNG: return kKindPng;
    case CLIPP_FORMAT_JPEG: return kKindJpeg;
    default: return -1;
    }
}

// The item's content bytes: UTF-8 text without the trailing NUL, or the image file.
CLIPP_JNI(jbyteArray, nativeItemData)(JNIEnv* env, jclass, jlong id) {
    const auto payload = g_clipboardActivityStore.PayloadReference(static_cast<uint64_t>(id));
    if (!payload) {
        return nullptr;
    }
    const std::vector<unsigned char>* bytes = payload->TryGetUncompressedBytes();
    if (bytes == nullptr || bytes->empty()) {
        return nullptr;
    }
    size_t size = bytes->size();
    if (payload->meta.formatId == CLIPP_FORMAT_UTF8 && bytes->back() == '\0') {
        --size;
    }
    return ToJavaBytes(env, bytes->data(), size);
}

CLIPP_JNI(jboolean, nativeItemIsPrivate)(JNIEnv*, jclass, jlong id) {
    const auto display = g_clipboardActivityStore.DisplayItem(static_cast<uint64_t>(id));
    return display && (display->kind == ClipboardActivityPayloadKind::PrivateText || display->sourceMarked)
        ? JNI_TRUE : JNI_FALSE;
}

// "Copy" = re-share (same semantics as the iOS bridge and desktop ReshareActivityItem):
// same eventGuid/origin, timestamp bumped so every store moves it to the top.
// Kotlin writes the local clipboard itself.
CLIPP_JNI(jboolean, nativeReshare)(JNIEnv*, jclass, jlong id) {
    const auto payload = g_clipboardActivityStore.PayloadReference(static_cast<uint64_t>(id));
    if (!payload || payload->EncodedBytes().empty()) {
        return JNI_FALSE;
    }
    auto clone = std::make_shared<ClipboardPayload>();
    clone->meta = payload->meta;
    clone->meta.flags &= ~(NetworkDefs::CLPM_FLAG_SYNC_REPLAY | NetworkDefs::CLPM_FLAG_RELAY);
    clone->meta.timestamp = (std::max)(NowMs(), payload->meta.timestamp + 1);
    clone->SetEncodedBytes(std::vector<unsigned char>(payload->EncodedBytes()));
    AddAndBroadcast(std::shared_ptr<const ClipboardPayload>(std::move(clone)));
    return JNI_TRUE;
}

// Delete everywhere (desktop ClipboardActions::DeleteActivityItemEverywhere): remove
// locally, arm the undo slot, and broadcast CDEL so peers drop it from their history.
CLIPP_JNI(jboolean, nativeDelete)(JNIEnv*, jclass, jlong id) {
    const auto stored = g_clipboardActivityStore.PayloadReference(static_cast<uint64_t>(id));
    if (!stored) {
        return JNI_FALSE;
    }
    std::array<uint8_t, 16> guid{};
    std::memcpy(guid.data(), stored->meta.eventGuid, guid.size());
    if (!g_clipboardActivityStore.Remove(static_cast<uint64_t>(id))) {
        return JNI_FALSE;
    }
    {
        std::lock_guard<std::mutex> lock(g_undoMutex);
        g_undoPayload = stored;
    }
    const bool guidIsZero = std::all_of(guid.begin(), guid.end(), [](uint8_t b) { return b == 0; });
    if (!guidIsZero) {
        g_peerManager.BroadcastFrame({ 'C', 'D', 'E', 'L' }, std::vector<unsigned char>(guid.begin(), guid.end()));
    }
    return JNI_TRUE;
}

// Undo the last delete (desktop UndoDelete): re-insert locally with the original guid
// and timestamp, and replay it to peers on the SYNC_REPLAY lane so their history
// gets it back without touching their live clipboards. Returns the restored id or 0.
CLIPP_JNI(jlong, nativeUndoDelete)(JNIEnv*, jclass) {
    std::shared_ptr<const ClipboardPayload> undo;
    {
        std::lock_guard<std::mutex> lock(g_undoMutex);
        undo = std::move(g_undoPayload);
        g_undoPayload.reset();
    }
    if (!undo) {
        return 0;
    }
    auto restored = std::make_shared<ClipboardPayload>();
    restored->meta = undo->meta;
    restored->meta.flags &= ~(NetworkDefs::CLPM_FLAG_SYNC_REPLAY | NetworkDefs::CLPM_FLAG_RELAY);
    restored->SetEncodedBytes(std::vector<unsigned char>(undo->EncodedBytes()));
    const uint64_t restoredId = g_clipboardActivityStore.Add(restored);

    auto wire = std::make_shared<ClipboardPayload>();
    wire->meta = restored->meta;
    wire->meta.flags |= NetworkDefs::CLPM_FLAG_SYNC_REPLAY;
    wire->SetEncodedBytes(std::vector<unsigned char>(restored->EncodedBytes()));
    g_peerManager.BroadcastClipboard(std::move(wire));
    return static_cast<jlong>(restoredId);
}

CLIPP_JNI(jbyteArray, nativePeers)(JNIEnv* env, jclass) {
    std::string json = "[";
    bool first = true;
    for (const auto& peer : g_peerDisplay.Query()) {
        if (!first) json += ",";
        first = false;
        json += "{\"id\":" + JsonString(peer.hostID.ToHexString()) +
                ",\"name\":" + JsonString(Utf8(peer.hostName)) +
                ",\"os\":" + std::to_string(static_cast<unsigned>(peer.osType)) +
                ",\"in\":" + (peer.hasIncomingConnection ? "true" : "false") +
                ",\"out\":" + (peer.hasOutgoingConnection ? "true" : "false") +
                ",\"issue\":" + (peer.hasConnectivityIssue() ? "true" : "false") + "}";
    }
    json += "]";
    return ToJavaBytes(env, json);
}

// Whether short single-word text (likely a password) is masked in the history.
// A core setting shared with the desktop's "mask short text previews" option.
CLIPP_JNI(jboolean, nativeMaskSecrets)(JNIEnv*, jclass) {
    return g_settings.maskShortTextPreviews() ? JNI_TRUE : JNI_FALSE;
}

CLIPP_JNI(void, nativeSetMaskSecrets)(JNIEnv*, jclass, jboolean mask) {
    g_settings.set_maskShortTextPreviews(mask == JNI_TRUE);
}

CLIPP_JNI(void, nativeOnNetworkChanged)(JNIEnv*, jclass) {
    ClippAndroidNotifyNetworkChanged();
}

CLIPP_JNI(void, nativeMdnsRegistered)(JNIEnv* env, jclass, jstring jName) {
    const char* name = env->GetStringUTFChars(jName, nullptr);
    ClippAndroidMdnsRegistered(name != nullptr ? name : "");
    if (name != nullptr) env->ReleaseStringUTFChars(jName, name);
}

CLIPP_JNI(void, nativeMdnsResolved)(JNIEnv* env, jclass, jstring jName, jstring jIp, jint port,
                                    jobjectArray jKeys, jobjectArray jValues) {
    auto str = [env](jstring s) {
        std::string out;
        if (s == nullptr) return out;
        const char* chars = env->GetStringUTFChars(s, nullptr);
        if (chars != nullptr) {
            out = chars;
            env->ReleaseStringUTFChars(s, chars);
        }
        return out;
    };
    std::vector<std::pair<std::string, std::string>> txt;
    const jsize count = jKeys != nullptr && jValues != nullptr
        ? std::min(env->GetArrayLength(jKeys), env->GetArrayLength(jValues)) : 0;
    for (jsize i = 0; i < count; ++i) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(jKeys, i));
        auto value = static_cast<jstring>(env->GetObjectArrayElement(jValues, i));
        txt.emplace_back(str(key), str(value));
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    ClippAndroidMdnsResolved(str(jName), str(jIp), port, txt);
}

CLIPP_JNI(void, nativeMdnsLost)(JNIEnv* env, jclass, jstring jName) {
    const char* name = env->GetStringUTFChars(jName, nullptr);
    ClippAndroidMdnsLost(name != nullptr ? name : "");
    if (name != nullptr) env->ReleaseStringUTFChars(jName, name);
}
