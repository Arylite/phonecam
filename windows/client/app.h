// State shared between the window (main.cpp) and the worker threads.
#pragma once
#include "../common/phonecam.h"
#include <atomic>
#include <mutex>
#include <string>

// Status lines shown in the window, written by the worker threads.
struct Status {
    std::mutex m;
    std::wstring adb, phone, apps;
};
extern Status g_status;
void SetStatus(std::wstring Status::*line, std::wstring text);

extern std::atomic<bool> g_quit;
extern std::atomic<unsigned> g_settingsVersion;  // bumped on every save
phonecam::Settings CurrentSettings();

// phone.cpp: accepts the phone over the adb tunnel, decodes its H.264 and feeds the camera.
void PhoneLoop();
void KickPhone();  // drops the connection so the phone reconnects with the new settings

// adb.cpp: keeps `adb reverse` in place so plugging the phone in is enough.
void AdbLoop();

// install.cpp
enum class InstallState { NotInstalled, Outdated, Installed };
InstallState GetInstallState();
int RunInstall();    // elevated: `PhoneCam.exe --install`
int RunUninstall();  // elevated: `PhoneCam.exe --uninstall`
std::wstring AppDir();
std::wstring Utf8ToWide(const std::string& s);
