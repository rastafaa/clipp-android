// Android platform glue that the shared core expects from every daemon build:
// the process-wide singletons (desktop: main.cpp, iOS: the Bridge/*.mm files),
// DataPaths, and the network-change monitor.
//
// Paths and the device name arrive as environment variables that ClippApp sets
// (android.system.Os.setenv) BEFORE System.loadLibrary. That ordering matters:
// g_settings is a static whose constructor reads the settings file, so the
// location has to be known before the library's static initializers run.

#include "AndroidBridge.h"

#include "ClipboardActivityStore.h"
#include "ClipboardHashGuard.h"
#include "NetworkChangeMonitor.h"
#include "NetworkRuntime.h"
#include "PeerDisplay.h"
#include "PeerManager.h"
#include "RegisterStore.h"
#include "Settings.h"
#include "platform/DataPaths.h"

#include <cerrno>
#include <cstdlib>
#include <functional>
#include <mutex>
#include <string>
#include <utility>

#include <sys/stat.h>

Settings g_settings;
PeerDisplay g_peerDisplay;
PeerManager g_peerManager;
NetworkRuntime g_networkRuntime;
RegisterStore g_registerStore;
ClipboardHashGuard g_clipboardHashGuard;
ClipboardActivityStore g_clipboardActivityStore;

namespace {

bool EnvDirectory(const char* name, std::string& out) {
    const char* value = std::getenv(name);
    if (value == nullptr || value[0] == '\0') {
        return false;
    }
    out = value;
    // Single level: the app creates the parent (its filesDir) already.
    if (::mkdir(out.c_str(), 0700) != 0 && errno != EEXIST) {
        return false;
    }
    return true;
}

}  // namespace

namespace clipp {

bool ResolveStateDirectory(std::string& outUtf8Dir) {
    return EnvDirectory("CLIPP_STATE_DIR", outUtf8Dir);
}

bool ResolveLogDirectory(std::string& outUtf8Dir) {
    (void)outUtf8Dir;
    return false;  // logcat only; no rolling log files on Android
}

bool ResolveCrashDumpDirectory(std::string& outUtf8Dir) {
    (void)outUtf8Dir;
    return false;
}

// ---------------------------------------------------------------------------
// Network change monitor. Android exposes connectivity changes only through
// ConnectivityManager (Java), so the Kotlin service owns the NetworkCallback
// and forwards each change here via ClippNative.nativeOnNetworkChanged().
// ---------------------------------------------------------------------------
struct NetworkChangeMonitor {
    std::function<void()> onChange;
};

namespace {
std::mutex g_monitorMutex;
NetworkChangeMonitor* g_monitor = nullptr;
}  // namespace

NetworkChangeMonitor* StartNetworkChangeMonitor(std::function<void()> onChange) {
    std::lock_guard<std::mutex> lock(g_monitorMutex);
    delete g_monitor;
    g_monitor = new NetworkChangeMonitor{ std::move(onChange) };
    return g_monitor;
}

void StopNetworkChangeMonitor(NetworkChangeMonitor* monitor) {
    std::lock_guard<std::mutex> lock(g_monitorMutex);
    if (monitor != nullptr && monitor == g_monitor) {
        g_monitor = nullptr;
    }
    delete monitor;
}

}  // namespace clipp

void ClippAndroidNotifyNetworkChanged() {
    std::function<void()> callback;
    {
        std::lock_guard<std::mutex> lock(clipp::g_monitorMutex);
        if (clipp::g_monitor != nullptr) {
            callback = clipp::g_monitor->onChange;
        }
    }
    if (callback) {
        callback();
    }
}
