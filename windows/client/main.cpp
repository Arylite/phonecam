// PhoneCam client: status, settings and camera install in one small window.
#include "app.h"
#include <commctrl.h>
#include <shellapi.h>
#include <windowsx.h>
#include <cstdio>
#include <thread>

#pragma comment(linker, "\"/manifestdependency:type='win32' name='Microsoft.Windows.Common-Controls' version='6.0.0.0' processorArchitecture='*' publicKeyToken='6595b64144ccf1df' language='*'\"")

using namespace phonecam;

Status g_status;
std::atomic<bool> g_quit{ false };
std::atomic<unsigned> g_settingsVersion{ 0 };

namespace {

std::mutex g_settingsLock;
Settings g_settings;

constexpr wchar_t kWindowClass[] = L"PhoneCamClient";
constexpr UINT WM_APP_INSTALLED = WM_APP + 1;
constexpr UINT WM_APP_TRAY = WM_APP + 2;

enum Id {
    IdCamera = 100, IdAdb, IdPhone, IdApps, IdResolution, IdFps, IdBitrate, IdKeyframe, IdPort, IdAdbPath, IdTray,
    IdSave, IdInstall, IdUninstall, IdMenuOpen, IdMenuQuit
};

HWND g_wnd;
UINT g_taskbarCreated;  // sent when Explorer restarts: the tray icon must be added again
NOTIFYICONDATAW g_tray{ sizeof(g_tray) };
bool g_trayShown = false;
HFONT g_font;
UINT g_dpi = 96;
InstallState g_install = InstallState::NotInstalled;
bool g_busy = false;  // an elevated install or uninstall is running

int S(int v) { return MulDiv(v, (int)g_dpi, 96); }

HWND Add(const wchar_t* cls, const wchar_t* text, DWORD style, int x, int y, int w, int h, int id = 0) {
    HWND c = CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | style, S(x), S(y), S(w), S(h), g_wnd, (HMENU)(INT_PTR)id, nullptr, nullptr);
    SendMessageW(c, WM_SETFONT, (WPARAM)g_font, FALSE);
    return c;
}

void SetText(int id, const std::wstring& text) {
    wchar_t cur[512];
    GetDlgItemTextW(g_wnd, id, cur, 512);
    if (text != cur) SetDlgItemTextW(g_wnd, id, text.c_str());  // only on change: avoids flicker
}

std::wstring GetText(int id) {
    wchar_t buf[MAX_PATH];
    GetDlgItemTextW(g_wnd, id, buf, MAX_PATH);
    return buf;
}

void RefreshInstall() {
    g_install = GetInstallState();
    Settings cfg = CurrentSettings();
    wchar_t text[128];
    switch (g_install) {
    case InstallState::NotInstalled: swprintf_s(text, L"Not installed"); break;
    case InstallState::Outdated: swprintf_s(text, L"Installed, update available"); break;
    default: swprintf_s(text, L"Installed as \"%s\", %ux%u at %u fps", kCameraName, cfg.width, cfg.height, cfg.fps);
    }
    SetText(IdCamera, text);
    SetText(IdInstall, g_install == InstallState::Outdated ? L"Update camera" : g_install == InstallState::Installed ? L"Reinstall camera" : L"Install camera");
    EnableWindow(GetDlgItem(g_wnd, IdInstall), !g_busy);
    EnableWindow(GetDlgItem(g_wnd, IdUninstall), !g_busy && g_install != InstallState::NotInstalled);
}

// The tray icon is there while "close to tray" is on, so the hidden window can always be found.
void UpdateTray(bool show) {
    if (show == g_trayShown) return;
    g_tray.hWnd = g_wnd;
    g_tray.uID = 1;
    if (show) {
        g_tray.uFlags = NIF_ICON | NIF_TIP | NIF_MESSAGE | NIF_SHOWTIP;
        g_tray.uCallbackMessage = WM_APP_TRAY;
        if (!g_tray.hIcon) LoadIconMetric(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(1), LIM_SMALL, &g_tray.hIcon);
        wcscpy_s(g_tray.szTip, kCameraName);
        Shell_NotifyIconW(NIM_ADD, &g_tray);
        g_tray.uVersion = NOTIFYICON_VERSION_4;
        Shell_NotifyIconW(NIM_SETVERSION, &g_tray);
    } else {
        Shell_NotifyIconW(NIM_DELETE, &g_tray);
    }
    g_trayShown = show;
}

void ShowWindowFromTray() {
    ShowWindow(g_wnd, SW_SHOW);
    ShowWindow(g_wnd, SW_RESTORE);
    SetForegroundWindow(g_wnd);
}

void ShowTrayMenu(int x, int y) {
    HMENU menu = CreatePopupMenu();
    AppendMenuW(menu, MF_STRING, IdMenuOpen, L"Open PhoneCam");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, IdMenuQuit, L"Quit");
    SetMenuDefaultItem(menu, IdMenuOpen, FALSE);
    SetForegroundWindow(g_wnd);  // lets the menu close when clicking elsewhere
    TrackPopupMenu(menu, TPM_RIGHTBUTTON, x, y, 0, g_wnd, nullptr);
    DestroyMenu(menu);
}

void RefreshStatus() {
    std::lock_guard<std::mutex> lock(g_status.m);
    SetText(IdAdb, g_status.adb);
    SetText(IdPhone, g_status.phone);
    SetText(IdApps, g_status.apps);
    if (g_trayShown) {
        std::wstring tip = std::wstring(kCameraName) + L": " + g_status.phone;
        if (wcscmp(tip.substr(0, ARRAYSIZE(g_tray.szTip) - 1).c_str(), g_tray.szTip) != 0) {
            wcsncpy_s(g_tray.szTip, tip.c_str(), _TRUNCATE);
            g_tray.uFlags = NIF_TIP | NIF_SHOWTIP;
            Shell_NotifyIconW(NIM_MODIFY, &g_tray);
        }
    }
}

void SetCloseToTray(bool on) {
    Settings s = CurrentSettings();
    s.closeToTray = on;
    CreateDirectoryW(DataDir().c_str(), nullptr);
    SaveSettings(s);
    { std::lock_guard<std::mutex> lock(g_settingsLock); g_settings = s; }
    UpdateTray(on);
}

void ShowSettings(const Settings& s) {
    SetText(IdResolution, std::to_wstring(s.width) + L"x" + std::to_wstring(s.height));
    SetText(IdFps, std::to_wstring(s.fps));
    wchar_t mbps[32];
    swprintf_s(mbps, L"%g", s.bitrate / 1e6);
    SetText(IdBitrate, mbps);
    SetText(IdKeyframe, std::to_wstring(s.keyframeInterval));
    SetText(IdPort, std::to_wstring(s.port));
    SetText(IdAdbPath, s.adbPath);
    CheckDlgButton(g_wnd, IdTray, s.closeToTray ? BST_CHECKED : BST_UNCHECKED);
}

void Save() {
    Settings s;
    double mbps = 0;
    bool ok = swscanf_s(GetText(IdResolution).c_str(), L"%ux%u", &s.width, &s.height) == 2
        && swscanf_s(GetText(IdFps).c_str(), L"%u", &s.fps) == 1
        && swscanf_s(GetText(IdBitrate).c_str(), L"%lf", &mbps) == 1
        && swscanf_s(GetText(IdKeyframe).c_str(), L"%u", &s.keyframeInterval) == 1
        && swscanf_s(GetText(IdPort).c_str(), L"%u", &s.port) == 1;
    s.bitrate = (UINT32)(mbps * 1e6);
    s.adbPath = GetText(IdAdbPath);
    s.closeToTray = IsDlgButtonChecked(g_wnd, IdTray) == BST_CHECKED;
    ok = ok && s.width >= 160 && s.width <= kMaxWidth && s.height >= 120 && s.height <= kMaxHeight && s.width % 2 == 0 && s.height % 2 == 0
        && s.fps >= 1 && s.fps <= 120 && mbps >= 0.1 && mbps <= 200 && s.keyframeInterval >= 1 && s.keyframeInterval <= 60
        && s.port >= 1024 && s.port <= 65535;
    if (!ok) {
        MessageBoxW(g_wnd, L"Check the values:\n\nResolution: WIDTHxHEIGHT, even, up to 3840x2160\nFrame rate: 1 to 120\n"
            L"Bitrate: 0.1 to 200 Mbit/s\nKeyframe interval: 1 to 60 s\nPort: 1024 to 65535", L"PhoneCam", MB_ICONWARNING);
        return;
    }
    CreateDirectoryW(DataDir().c_str(), nullptr);
    if (!SaveSettings(s)) {
        MessageBoxW(g_wnd, (L"Could not write " + SettingsPath() + L".\nInstall the camera first: it sets up this folder.").c_str(),
            L"PhoneCam", MB_ICONERROR);
        return;
    }
    { std::lock_guard<std::mutex> lock(g_settingsLock); g_settings = s; }
    g_settingsVersion++;
    KickPhone();
    RefreshInstall();
}

void Elevate(const wchar_t* arg) {
    g_busy = true;
    RefreshInstall();
    std::thread([arg] {
        wchar_t exe[MAX_PATH];
        GetModuleFileNameW(nullptr, exe, MAX_PATH);
        SHELLEXECUTEINFOW sei{ sizeof(sei), SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC, g_wnd, L"runas", exe, arg };
        sei.nShow = SW_HIDE;
        DWORD code = ERROR_CANCELLED;
        if (ShellExecuteExW(&sei) && sei.hProcess) {
            WaitForSingleObject(sei.hProcess, INFINITE);
            GetExitCodeProcess(sei.hProcess, &code);
            CloseHandle(sei.hProcess);
        }
        PostMessageW(g_wnd, WM_APP_INSTALLED, code, 0);
    }).detach();
}

void CreateControls() {
    constexpr int L = 16, LW = 120, X = L + LW + 8, VW = 330, R = 26;
    int y = 16;
    const wchar_t* status[] = { L"Camera", L"USB", L"Phone", L"Apps" };
    for (int i = 0; i < 4; i++, y += R + 8) {
        Add(L"STATIC", status[i], SS_LEFT, L, y, LW, R);
        Add(L"STATIC", L"", SS_LEFT, X, y, VW, R + 8, IdCamera + i);
    }
    Add(L"STATIC", L"", SS_ETCHEDHORZ, L, y, LW + 8 + VW, 1);
    y += 16;

    HWND res = Add(WC_COMBOBOXW, L"", CBS_DROPDOWN | WS_TABSTOP | WS_VSCROLL, X, y, 160, 200, IdResolution);
    for (auto r : { L"1280x720", L"1920x1080", L"2560x1440", L"3840x2160" }) SendMessageW(res, CB_ADDSTRING, 0, (LPARAM)r);
    Add(L"STATIC", L"Resolution", SS_LEFT, L, y + 3, LW, R);
    y += R + 8;
    HWND fps = Add(WC_COMBOBOXW, L"", CBS_DROPDOWN | WS_TABSTOP | WS_VSCROLL, X, y, 160, 200, IdFps);
    for (auto f : { L"24", L"30", L"60" }) SendMessageW(fps, CB_ADDSTRING, 0, (LPARAM)f);
    Add(L"STATIC", L"Frame rate", SS_LEFT, L, y + 3, LW, R);
    y += R + 8;
    struct { const wchar_t* label; int id; int w; DWORD style; } edits[] = {
        { L"Bitrate (Mbit/s)", IdBitrate, 160, 0 },
        { L"Keyframe every (s)", IdKeyframe, 160, ES_NUMBER },
        { L"Port", IdPort, 160, ES_NUMBER },
        { L"adb path", IdAdbPath, VW, ES_AUTOHSCROLL },
    };
    for (auto& e : edits) {
        Add(L"STATIC", e.label, SS_LEFT, L, y + 3, LW, R);
        Add(WC_EDITW, L"", WS_BORDER | WS_TABSTOP | e.style, X, y, e.w, R - 2, e.id);
        y += R + 8;
    }
    SendDlgItemMessageW(g_wnd, IdAdbPath, EM_SETCUEBANNER, TRUE, (LPARAM)L"Automatic (PATH or Android SDK)");
    Add(WC_BUTTONW, L"Keep running in the notification area when closed", BS_AUTOCHECKBOX | WS_TABSTOP, X, y, VW, R, IdTray);
    y += R + 8;

    y += 8;
    Add(WC_BUTTONW, L"Save", BS_DEFPUSHBUTTON | WS_TABSTOP, X, y, 100, R + 4, IdSave);
    Add(WC_BUTTONW, L"", BS_PUSHBUTTON | WS_TABSTOP, X + 108, y, 120, R + 4, IdInstall);
    Add(WC_BUTTONW, L"Uninstall", BS_PUSHBUTTON | WS_TABSTOP, X + 236, y, 94, R + 4, IdUninstall);

    RECT rc{ 0, 0, S(L + LW + 8 + VW + 16), S(y + R + 4 + 16) };
    AdjustWindowRectExForDpi(&rc, GetWindowLongW(g_wnd, GWL_STYLE), FALSE, 0, g_dpi);
    SetWindowPos(g_wnd, nullptr, 0, 0, rc.right - rc.left, rc.bottom - rc.top, SWP_NOMOVE | SWP_NOZORDER);
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE:
        g_wnd = hwnd;
        g_dpi = GetDpiForWindow(hwnd);
        CreateControls();
        ShowSettings(CurrentSettings());
        RefreshInstall();
        UpdateTray(CurrentSettings().closeToTray);
        SetTimer(hwnd, 1, 500, nullptr);
        return 0;
    case WM_CLOSE:
        if (CurrentSettings().closeToTray) ShowWindow(hwnd, SW_HIDE);
        else DestroyWindow(hwnd);
        return 0;
    case WM_APP_TRAY:
        switch (LOWORD(lp)) {
        case NIN_SELECT:
        case NIN_KEYSELECT: ShowWindowFromTray(); break;
        case WM_CONTEXTMENU: ShowTrayMenu(GET_X_LPARAM(wp), GET_Y_LPARAM(wp)); break;
        }
        return 0;
    case WM_TIMER:
        RefreshStatus();
        return 0;
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case IdSave: Save(); break;
        case IdInstall: Elevate(L"--install"); break;
        case IdUninstall: Elevate(L"--uninstall"); break;
        case IdTray: SetCloseToTray(IsDlgButtonChecked(hwnd, IdTray) == BST_CHECKED); break;
        case IdMenuOpen: ShowWindowFromTray(); break;
        case IdMenuQuit: DestroyWindow(hwnd); break;
        }
        return 0;
    case WM_APP_INSTALLED:
        g_busy = false;
        RefreshInstall();
        if (wp != 0 && wp != ERROR_CANCELLED) {
            wchar_t text[96];
            swprintf_s(text, L"The operation failed (error 0x%08lX).", (DWORD)wp);
            MessageBoxW(hwnd, text, L"PhoneCam", MB_ICONERROR);
        }
        return 0;
    case WM_CTLCOLORSTATIC:
        SetBkColor((HDC)wp, GetSysColor(COLOR_WINDOW));
        return (LRESULT)GetSysColorBrush(COLOR_WINDOW);
    case WM_DESTROY:
        UpdateTray(false);
        PostQuitMessage(0);
        return 0;
    }
    if (msg == g_taskbarCreated && g_trayShown) {
        g_trayShown = false;
        UpdateTray(true);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

void SetStatus(std::wstring Status::*line, std::wstring text) {
    std::lock_guard<std::mutex> lock(g_status.m);
    g_status.*line = std::move(text);
}

Settings CurrentSettings() {
    std::lock_guard<std::mutex> lock(g_settingsLock);
    return g_settings;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR cmd, int show) {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (wcscmp(cmd, L"--install") == 0) return RunInstall();
    if (wcscmp(cmd, L"--uninstall") == 0) return RunUninstall();

    // One client at a time: a second one would fight over the port.
    CreateMutexW(nullptr, TRUE, L"Local\\PhoneCamClient");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        if (HWND w = FindWindowW(kWindowClass, nullptr)) {  // may be hidden in the tray
            ShowWindow(w, SW_SHOW);
            ShowWindow(w, SW_RESTORE);
            SetForegroundWindow(w);
        }
        return 0;
    }

    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_SYSTEM_AWARE);
    NONCLIENTMETRICSW ncm{ sizeof(ncm) };
    SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof(ncm), &ncm, 0);
    g_font = CreateFontIndirectW(&ncm.lfMessageFont);
    g_settings = LoadSettings();
    g_taskbarCreated = RegisterWindowMessageW(L"TaskbarCreated");
    SetStatus(&Status::adb, L"Starting...");

    WNDCLASSEXW wc{ sizeof(wc) };
    wc.lpfnWndProc = WndProc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.hIcon = LoadIconW(inst, MAKEINTRESOURCEW(1));
    wc.lpszClassName = kWindowClass;
    RegisterClassExW(&wc);
    HWND hwnd = CreateWindowExW(0, kWindowClass, L"PhoneCam", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT, 400, 400, nullptr, nullptr, inst, nullptr);

    std::thread adb(AdbLoop), phone(PhoneLoop);
    ShowWindow(hwnd, show);
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        if (IsDialogMessageW(hwnd, &msg)) continue;  // Tab between fields
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    g_quit = true;
    KickPhone();
    adb.join();
    phone.join();
    return 0;
}
