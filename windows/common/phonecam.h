// Contract between the client (PhoneCam.exe) and the virtual camera (PhoneCamSource.dll).
#pragma once
#include <windows.h>
#include <shlobj.h>
#include <string>

namespace phonecam {

constexpr wchar_t kClsid[] = L"{02C7D363-CE79-4BAE-8B1D-9F40C1F01133}";
constexpr wchar_t kCameraName[] = L"PhoneCam";
constexpr wchar_t kSourceDll[] = L"PhoneCamSource.dll";

// Created by the camera, which runs inside the Frame Server service and may create Global\ objects;
// the client only opens them.
constexpr wchar_t kMapName[] = L"Global\\PhoneCamFrame";
constexpr wchar_t kEventName[] = L"Global\\PhoneCamEvent";

constexpr UINT32 kMaxWidth = 3840, kMaxHeight = 2160;
constexpr DWORD kMaxFrameSize = kMaxWidth * kMaxHeight * 3 / 2;  // NV12

struct SharedFrame {
    volatile LONG seq;            // seqlock: odd while the client writes `nv12`
    volatile LONG width, height;  // format the camera was opened with, written by the camera
    volatile LONG64 lastReadTick; // GetTickCount64() of the camera's last delivered sample
    BYTE nv12[kMaxFrameSize];
};

// Machine-wide settings, shared by the client and the camera:
// %ProgramData%\PhoneCam\settings.ini (users may write it, the installer sets the folder ACL).
struct Settings {
    UINT32 width = 1920, height = 1080, fps = 30;  // [camera]
    UINT32 bitrate = 10'000'000, keyframeInterval = 1, port = 8765;  // [stream]
    std::wstring adbPath;  // [adb] Path, empty = search PATH and the Android SDK
    bool closeToTray = false;  // [client] CloseToTray: closing the window keeps it in the notification area
};

inline std::wstring DataDir() {
    PWSTR p = nullptr;
    std::wstring dir;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_ProgramData, 0, nullptr, &p))) dir = std::wstring(p) + L"\\PhoneCam";
    CoTaskMemFree(p);
    return dir;
}

inline std::wstring SettingsPath() { return DataDir() + L"\\settings.ini"; }

inline UINT32 Clamp(UINT32 v, UINT32 lo, UINT32 hi, UINT32 fallback) { return v < lo || v > hi ? fallback : v; }

inline Settings LoadSettings() {
    Settings s, d;
    auto path = SettingsPath();
    auto get = [&](const wchar_t* sec, const wchar_t* key, UINT32 def) { return (UINT32)GetPrivateProfileIntW(sec, key, def, path.c_str()); };
    // NV12 needs even dimensions; out-of-range values fall back to the defaults.
    s.width = Clamp(get(L"camera", L"Width", d.width), 160, kMaxWidth, d.width) & ~1u;
    s.height = Clamp(get(L"camera", L"Height", d.height), 120, kMaxHeight, d.height) & ~1u;
    s.fps = Clamp(get(L"camera", L"Fps", d.fps), 1, 120, d.fps);
    s.bitrate = Clamp(get(L"stream", L"Bitrate", d.bitrate), 100'000, 200'000'000, d.bitrate);
    s.keyframeInterval = Clamp(get(L"stream", L"KeyframeInterval", d.keyframeInterval), 1, 60, d.keyframeInterval);
    s.port = Clamp(get(L"stream", L"Port", d.port), 1024, 65535, d.port);
    wchar_t buf[MAX_PATH] = {};
    GetPrivateProfileStringW(L"adb", L"Path", L"", buf, MAX_PATH, path.c_str());
    s.adbPath = buf;
    s.closeToTray = get(L"client", L"CloseToTray", 0) != 0;
    return s;
}

inline bool SaveSettings(const Settings& s) {
    auto path = SettingsPath();
    auto put = [&](const wchar_t* sec, const wchar_t* key, UINT32 v) {
        return WritePrivateProfileStringW(sec, key, std::to_wstring(v).c_str(), path.c_str()) != 0;
    };
    return put(L"camera", L"Width", s.width) && put(L"camera", L"Height", s.height) && put(L"camera", L"Fps", s.fps)
        && put(L"stream", L"Bitrate", s.bitrate) && put(L"stream", L"KeyframeInterval", s.keyframeInterval)
        && put(L"stream", L"Port", s.port)
        && WritePrivateProfileStringW(L"adb", L"Path", s.adbPath.c_str(), path.c_str()) != 0
        && put(L"client", L"CloseToTray", s.closeToTray);
}

}  // namespace phonecam
