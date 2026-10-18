#include "app.h"

using namespace phonecam;

namespace {

std::wstring FindAdb(const Settings& cfg) {
    if (!cfg.adbPath.empty()) return GetFileAttributesW(cfg.adbPath.c_str()) != INVALID_FILE_ATTRIBUTES ? cfg.adbPath : L"";
    wchar_t path[MAX_PATH];
    if (SearchPathW(nullptr, L"adb.exe", nullptr, MAX_PATH, path, nullptr)) return path;
    if (GetEnvironmentVariableW(L"LOCALAPPDATA", path, MAX_PATH)) {
        std::wstring sdk = std::wstring(path) + L"\\Android\\Sdk\\platform-tools\\adb.exe";
        if (GetFileAttributesW(sdk.c_str()) != INVALID_FILE_ATTRIBUTES) return sdk;
    }
    return L"";
}

// Runs adb and returns its exit code, with stdout and stderr in `out`.
DWORD RunAdb(const std::wstring& exe, const std::wstring& args, std::string& out) {
    SECURITY_ATTRIBUTES sa{ sizeof(sa), nullptr, TRUE };
    HANDLE read, write;
    if (!CreatePipe(&read, &write, &sa, 0)) return (DWORD)-1;
    SetHandleInformation(read, HANDLE_FLAG_INHERIT, 0);
    STARTUPINFOW si{ sizeof(si) };
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = si.hStdError = write;
    PROCESS_INFORMATION pi{};
    std::wstring cmd = L"\"" + exe + L"\" " + args;
    // Run from adb's own folder: the adb daemon it may start would otherwise lock ours.
    std::wstring dir = exe.substr(0, exe.find_last_of(L'\\'));
    BOOL ok = CreateProcessW(exe.c_str(), cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, dir.c_str(), &si, &pi);
    CloseHandle(write);
    DWORD code = (DWORD)-1;
    if (ok) {
        char buf[512];
        DWORD n;
        while (ReadFile(read, buf, sizeof(buf), &n, nullptr) && n) out.append(buf, n);
        if (WaitForSingleObject(pi.hProcess, 5000) == WAIT_TIMEOUT) TerminateProcess(pi.hProcess, 1);
        GetExitCodeProcess(pi.hProcess, &code);
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
    }
    CloseHandle(read);
    return code;
}

std::wstring Explain(const std::string& out) {
    if (out.find("no devices") != std::string::npos) return L"No phone on USB: plug it in with USB debugging on";
    if (out.find("unauthorized") != std::string::npos) return L"Allow USB debugging on the phone";
    if (out.find("more than one") != std::string::npos) return L"Several Android devices: keep only one plugged in";
    std::string line = out.substr(0, out.find_first_of("\r\n"));
    return line.empty() ? L"adb failed" : L"adb: " + Utf8ToWide(line);
}

}  // namespace

void AdbLoop() {
    UINT32 tunneled = 0;
    std::wstring exe;
    while (!g_quit) {
        Settings cfg = CurrentSettings();
        exe = FindAdb(cfg);
        std::string out;
        if (exe.empty()) {
            SetStatus(&Status::adb, L"adb not found: install Android platform-tools or set its path below");
        } else {
            if (tunneled && tunneled != cfg.port)
                RunAdb(exe, L"reverse --remove tcp:" + std::to_wstring(tunneled), out), tunneled = 0;
            auto port = std::to_wstring(cfg.port);
            if (RunAdb(exe, L"reverse tcp:" + port + L" tcp:" + port, out) == 0) {
                tunneled = cfg.port;
                SetStatus(&Status::adb, L"Phone connected over USB");
            } else {
                tunneled = 0;
                SetStatus(&Status::adb, Explain(out));
            }
        }
        for (int i = 0; i < 20 && !g_quit; i++) Sleep(100);
    }
    std::string out;
    if (tunneled && !exe.empty()) RunAdb(exe, L"reverse --remove tcp:" + std::to_wstring(tunneled), out);
}
