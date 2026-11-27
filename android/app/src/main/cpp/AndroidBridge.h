#pragma once

// Shared plumbing between the Android native files: JNI environment access for
// native -> Kotlin calls (made from core threads), and the few cross-file hooks.

#include <jni.h>

#include <string>
#include <utility>
#include <vector>

namespace clipp_android {

// JNIEnv for the calling thread, attaching it to the VM on first use. Threads
// attached here detach automatically when they exit. Returns nullptr only if
// the VM isn't loaded yet.
JNIEnv* Env();

// The Kotlin `ClippNative` class (a global ref resolved at JNI_OnLoad).
jclass NativeClass();

// Calls a `static void name(signature)` on ClippNative, clearing any exception
// so a Kotlin-side failure can't poison the native thread.
void CallStaticVoid(const char* name, const char* signature, ...);

jbyteArray ToJavaBytes(JNIEnv* env, const std::string& utf8);
jbyteArray ToJavaBytes(JNIEnv* env, const unsigned char* data, size_t size);
std::string FromJavaBytes(JNIEnv* env, jbyteArray bytes);

}  // namespace clipp_android

// AndroidPlatform.cpp: forwards a ConnectivityManager change to the core.
void ClippAndroidNotifyNetworkChanged();

// MDNSDiscovery_Android.cpp: results from Kotlin's NsdManager.
void ClippAndroidMdnsRegistered(const std::string& instanceName);
void ClippAndroidMdnsResolved(const std::string& instanceName, const std::string& ip, int port,
                              const std::vector<std::pair<std::string, std::string>>& txt);
void ClippAndroidMdnsLost(const std::string& instanceName);
