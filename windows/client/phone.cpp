// Wire protocol (big-endian), see docs/protocol.md:
//   phone -> PC  hello   "PCAM" u8 version
//   PC -> phone  config  u16 width, u16 height, u8 fps, u32 bitrate, u8 keyframe interval (s)
//   phone -> PC  message u8 type (1 = H.264 Annex-B access unit, 2 = UTF-8 error), u32 length, payload
//   PC -> phone  ack     one byte per video message, once decoded (the phone caps frames in flight)
#include "app.h"
#include "decoder.h"
#include <winsock2.h>
#include <ws2tcpip.h>
#include <mfapi.h>
#include <cstdio>
#include <vector>

using namespace phonecam;

namespace {

constexpr BYTE kVersion = 1;
constexpr BYTE kVideo = 1, kError = 2;
constexpr UINT32 kMaxMessage = 32 * 1024 * 1024;

std::atomic<SOCKET> g_phone{ INVALID_SOCKET };

// Writes decoded frames into the camera's shared memory. The camera creates it when an app
// opens it, so until then frames are dropped and opening is retried.
class FrameSink {
    HANDLE _map = nullptr, _event = nullptr;
    SharedFrame* _shared = nullptr;
    ULONGLONG _nextTry = 0;

    bool Open() {
        if (_shared) return true;
        if (GetTickCount64() < _nextTry) return false;
        _nextTry = GetTickCount64() + 1000;
        _map = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, kMapName);
        _event = OpenEventW(EVENT_MODIFY_STATE, FALSE, kEventName);
        if (_map && _event) _shared = (SharedFrame*)MapViewOfFile(_map, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(SharedFrame));
        if (_shared) return true;
        if (_map) CloseHandle(_map);
        if (_event) CloseHandle(_event);
        _map = _event = nullptr;
        return false;
    }

public:
    ~FrameSink() {
        if (_shared) UnmapViewOfFile(_shared);
        if (_map) CloseHandle(_map);
        if (_event) CloseHandle(_event);
    }

    void Write(const std::vector<BYTE>& nv12, const Settings& cfg) {
        // A camera opened with another size keeps it until the app reopens it (see UpdateApps).
        if (!Open() || (UINT32)_shared->width != cfg.width || (UINT32)_shared->height != cfg.height) return;
        // Seqlock: odd while writing, so the camera never sends a half-written frame.
        LONG seq = (_shared->seq + 1) | 1;
        _shared->seq = seq;
        MemoryBarrier();
        memcpy(_shared->nv12, nv12.data(), nv12.size());
        MemoryBarrier();
        _shared->seq = seq + 1;
        SetEvent(_event);
    }

    void UpdateApps(const Settings& cfg) {
        wchar_t text[160];
        if (!Open() || GetTickCount64() - (ULONGLONG)_shared->lastReadTick > 1000)
            swprintf_s(text, L"Not in use");
        else if ((UINT32)_shared->width != cfg.width || (UINT32)_shared->height != cfg.height)
            swprintf_s(text, L"Opened at %ldx%ld: reopen the camera in your app to switch to %ux%u",
                _shared->width, _shared->height, cfg.width, cfg.height);
        else
            swprintf_s(text, L"In use (%ux%u)", cfg.width, cfg.height);
        SetStatus(&Status::apps, text);
    }
};

bool RecvAll(SOCKET s, void* buf, int len) {
    for (char* p = (char*)buf; len > 0;) {
        int n = recv(s, p, len, 0);
        if (n <= 0) return false;
        p += n, len -= n;
    }
    return true;
}

UINT32 ReadBE(const BYTE* p, int bytes) {
    UINT32 v = 0;
    while (bytes--) v = v << 8 | *p++;
    return v;
}

void WriteBE(BYTE*& p, UINT32 v, int bytes) {
    while (bytes--) *p++ = (BYTE)(v >> (bytes * 8));
}

void Serve(SOCKET s, const Settings& cfg, FrameSink& sink) {
    BYTE hello[5];
    if (!RecvAll(s, hello, sizeof(hello))) return;  // adb opens and closes the tunnel on its own: not an error
    if (memcmp(hello, "PCAM", 4) != 0 || hello[4] != kVersion) {
        SetStatus(&Status::phone, L"Incompatible app version: update PhoneCam on the phone and the PC");
        return;
    }
    BYTE config[10], *p = config;
    WriteBE(p, cfg.width, 2), WriteBE(p, cfg.height, 2), WriteBE(p, cfg.fps, 1);
    WriteBE(p, cfg.bitrate, 4), WriteBE(p, cfg.keyframeInterval, 1);
    if (send(s, (const char*)config, sizeof(config), 0) != sizeof(config)) return;

    H264Decoder decoder;
    if (FAILED(decoder.Init(cfg.width, cfg.height, cfg.fps))) {
        SetStatus(&Status::phone, L"The Windows H.264 decoder is unavailable (N editions need the Media Feature Pack)");
        return;
    }
    SetStatus(&Status::phone, L"Connected, waiting for video");
    std::vector<BYTE> nv12(cfg.width * cfg.height * 3 / 2), msg;
    ULONGLONG since = GetTickCount64(), bytes = 0;
    unsigned frames = 0;
    for (BYTE header[5]; RecvAll(s, header, sizeof(header));) {
        UINT32 len = ReadBE(header + 1, 4);
        if (len > kMaxMessage) return;
        msg.resize(len);
        if (!RecvAll(s, msg.data(), (int)len)) return;
        if (header[0] == kError) {
            SetStatus(&Status::phone, L"Phone: " + Utf8ToWide(std::string(msg.begin(), msg.end())));
            continue;
        }
        if (header[0] != kVideo) continue;  // unknown message types are skipped for forward compatibility
        bytes += len;
        if (decoder.Decode(msg.data(), len, nv12.data()) == S_OK) {
            frames++;
            sink.Write(nv12, cfg);
        }
        const char ack = 1;
        if (send(s, &ack, 1, 0) != 1) return;
        ULONGLONG now = GetTickCount64();
        if (now - since >= 1000) {
            wchar_t text[96];
            double secs = (now - since) / 1000.0;
            swprintf_s(text, L"Streaming %ux%u, %.0f fps, %.1f Mbit/s", cfg.width, cfg.height, frames / secs, bytes * 8 / secs / 1e6);
            SetStatus(&Status::phone, text);
            sink.UpdateApps(cfg);
            since = now, bytes = 0, frames = 0;
        }
    }
}

}  // namespace

void KickPhone() {
    SOCKET s = g_phone;
    if (s != INVALID_SOCKET) shutdown(s, SD_BOTH);
}

void PhoneLoop() {
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION, MFSTARTUP_LITE);
    FrameSink sink;
    const std::wstring waiting = L"Waiting for the PhoneCam app";
    while (!g_quit) {
        Settings cfg = CurrentSettings();
        unsigned version = g_settingsVersion;
        // Loopback only: the phone comes through the adb tunnel, nothing is exposed on the network.
        SOCKET listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        sockaddr_in addr{ AF_INET, htons((u_short)cfg.port) };
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        BOOL on = TRUE;
        setsockopt(listener, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (const char*)&on, sizeof(on));
        if (bind(listener, (sockaddr*)&addr, sizeof(addr)) != 0 || listen(listener, 1) != 0) {
            closesocket(listener);
            SetStatus(&Status::phone, L"Port " + std::to_wstring(cfg.port) + L" is in use: pick another one");
            for (int i = 0; i < 20 && !g_quit && version == g_settingsVersion; i++) Sleep(100);
            continue;
        }
        SetStatus(&Status::phone, waiting);
        while (!g_quit && version == g_settingsVersion) {
            sink.UpdateApps(cfg);
            fd_set fds;
            FD_ZERO(&fds);
            FD_SET(listener, &fds);
            timeval timeout{ 0, 500'000 };
            if (select(0, &fds, nullptr, nullptr, &timeout) != 1) continue;
            SOCKET s = accept(listener, nullptr, nullptr);
            if (s == INVALID_SOCKET) continue;
            setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (const char*)&on, sizeof(on));
            g_phone = s;
            cfg = CurrentSettings();
            Serve(s, cfg, sink);
            g_phone = INVALID_SOCKET;
            closesocket(s);
            SetStatus(&Status::phone, waiting);
        }
        closesocket(listener);
    }
    MFShutdown();
    CoUninitialize();
    WSACleanup();
}
