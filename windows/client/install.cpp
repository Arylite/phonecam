#include "app.h"
#include <aclapi.h>
#include <sddl.h>
#include <fstream>
#include <iterator>
#include <vector>

using namespace phonecam;

std::wstring AppDir() {
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(nullptr, path, MAX_PATH);
    std::wstring s = path;
    return s.substr(0, s.find_last_of(L'\\'));
}

std::wstring Utf8ToWide(const std::string& s) {
    std::wstring w(MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), (int)w.size());
    return w;
}

namespace {

// The Frame Server runs as Local Service and cannot read DLLs inside a user profile.
std::wstring InstallDir() {
    PWSTR p = nullptr;
    std::wstring dir;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_ProgramFiles, 0, nullptr, &p))) dir = std::wstring(p) + L"\\PhoneCam";
    CoTaskMemFree(p);
    return dir;
}

std::wstring InstalledDll() {
    wchar_t path[MAX_PATH];
    DWORD size = sizeof(path);
    std::wstring key = std::wstring(L"Software\\Classes\\CLSID\\") + kClsid + L"\\InprocServer32";
    if (RegGetValueW(HKEY_LOCAL_MACHINE, key.c_str(), nullptr, RRF_RT_REG_SZ, nullptr, path, &size) != ERROR_SUCCESS) return L"";
    return path;
}

std::vector<char> ReadAll(const std::wstring& path) {
    std::ifstream f(path, std::ios::binary);
    return { std::istreambuf_iterator<char>(f), {} };
}

// Releases the loaded camera DLL so it can be replaced; Windows restarts the service on demand.
void StopFrameServer() {
    SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT);
    if (!scm) return;
    if (SC_HANDLE svc = OpenServiceW(scm, L"FrameServer", SERVICE_STOP | SERVICE_QUERY_STATUS)) {
        SERVICE_STATUS st;
        ControlService(svc, SERVICE_CONTROL_STOP, &st);
        for (int i = 0; i < 100 && QueryServiceStatus(svc, &st) && st.dwCurrentState != SERVICE_STOPPED; i++) Sleep(100);
        CloseServiceHandle(svc);
    }
    CloseServiceHandle(scm);
}

// Users edit settings.ini from the (non-elevated) client; the camera reads it as Local Service.
DWORD PrepareDataDir() {
    std::wstring dir = DataDir();
    if (!CreateDirectoryW(dir.c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) return GetLastError();
    PSECURITY_DESCRIPTOR sd;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"D:PAI(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1301bf;;;BU)(A;OICI;FR;;;LS)", SDDL_REVISION_1, &sd, nullptr))
        return GetLastError();
    BOOL present, defaulted;
    PACL dacl;
    GetSecurityDescriptorDacl(sd, &present, &dacl, &defaulted);
    DWORD err = SetNamedSecurityInfoW(dir.data(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
        nullptr, nullptr, dacl, nullptr);
    LocalFree(sd);
    return err;
}

HRESULT CallExport(const std::wstring& dll, const char* name) {
    HMODULE m = LoadLibraryExW(dll.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!m) return HRESULT_FROM_WIN32(GetLastError());
    auto fn = (HRESULT(STDAPICALLTYPE*)())GetProcAddress(m, name);
    HRESULT hr = fn ? fn() : HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND);
    FreeLibrary(m);
    return hr;
}

}  // namespace

InstallState GetInstallState() {
    std::wstring installed = InstalledDll();
    if (installed.empty() || GetFileAttributesW(installed.c_str()) == INVALID_FILE_ATTRIBUTES) return InstallState::NotInstalled;
    std::wstring bundled = AppDir() + L"\\" + kSourceDll;
    if (GetFileAttributesW(bundled.c_str()) != INVALID_FILE_ATTRIBUTES && ReadAll(bundled) != ReadAll(installed))
        return InstallState::Outdated;
    return InstallState::Installed;
}

int RunInstall() {
    if (DWORD err = PrepareDataDir()) return HRESULT_FROM_WIN32(err);
    std::wstring dir = InstallDir(), dll = dir + L"\\" + kSourceDll;
    if (!CreateDirectoryW(dir.c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) return HRESULT_FROM_WIN32(GetLastError());
    StopFrameServer();
    if (!CopyFileW((AppDir() + L"\\" + kSourceDll).c_str(), dll.c_str(), FALSE)) return HRESULT_FROM_WIN32(GetLastError());
    return CallExport(dll, "DllRegisterServer");
}

int RunUninstall() {
    std::wstring dll = InstalledDll();
    if (dll.empty()) return S_OK;
    HRESULT hr = CallExport(dll, "DllUnregisterServer");
    StopFrameServer();
    DeleteFileW(dll.c_str());
    RemoveDirectoryW(dll.substr(0, dll.find_last_of(L'\\')).c_str());
    return hr;
}
