// PhoneCam virtual camera: a Media Foundation custom source that the Windows Camera Frame Server
// loads (Windows 11 MFCreateVirtualCamera, user mode, no driver). The client writes decoded NV12
// frames into shared memory; each requested sample carries the latest one.
#include "../common/phonecam.h"
#include <initguid.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mfvirtualcamera.h>
#include <ks.h>
#include <ksproxy.h>
#include <ksmedia.h>
#include <sddl.h>
#include <wrl.h>
#include <wrl/module.h>
#include <cstdio>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

using namespace Microsoft::WRL;
using namespace phonecam;

struct __declspec(uuid("28F54685-06FD-11D2-B27A-00A0C9223196")) IKsControl;

static HMODULE g_module;

#define RIF(x) do { HRESULT hr_ = (x); if (FAILED(hr_)) return hr_; } while (0)

#define KS_NOT_FOUND \
    STDMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); } \
    STDMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); } \
    STDMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }

class Stream : public RuntimeClass<RuntimeClassFlags<ClassicCom>,
    ChainInterfaces<IMFMediaStream2, IMFMediaStream, IMFMediaEventGenerator>, IKsControl> {
    std::mutex _m;
    IMFMediaSource* _source = nullptr;  // weak: the source owns this stream
    ComPtr<IMFMediaEventQueue> _queue;
    ComPtr<IMFStreamDescriptor> _desc;
    ComPtr<IMFAttributes> _attrs;
    MF_STREAM_STATE _state = MF_STREAM_STATE_STOPPED;
    std::deque<ComPtr<IUnknown>> _tokens;
    HANDLE _wake = nullptr, _stop = nullptr, _map = nullptr, _frameEvent = nullptr;
    SharedFrame* _shared = nullptr;
    std::thread _worker;
    UINT32 _w = 0, _h = 0, _fps = 0;
    DWORD _size = 0;
    // Worker thread only.
    std::vector<BYTE> _nv12;
    LONG _lastSeq = 0;
    bool _unsent = false;
    ULONGLONG _lastSent = 0;

    void OpenShared() {
        SECURITY_ATTRIBUTES sa{ sizeof(sa) };
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:(A;;GA;;;SY)(A;;GA;;;LS)(A;;GA;;;IU)",
                SDDL_REVISION_1, &sa.lpSecurityDescriptor, nullptr))
            return;
        _map = CreateFileMappingW(INVALID_HANDLE_VALUE, &sa, PAGE_READWRITE, 0, sizeof(SharedFrame), kMapName);
        if (_map) _shared = (SharedFrame*)MapViewOfFile(_map, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(SharedFrame));
        _frameEvent = CreateEventW(&sa, FALSE, FALSE, kEventName);
        LocalFree(sa.lpSecurityDescriptor);
        if (_shared) { _shared->width = _w; _shared->height = _h; }  // the client only writes frames of this size
    }

    bool ReadFrame() {
        if (!_shared) return false;
        LONG seq = _shared->seq;
        if (seq == _lastSeq || (seq & 1)) return false;
        memcpy(_nv12.data(), (const void*)_shared->nv12, _size);
        MemoryBarrier();
        if (_shared->seq != seq) return false;  // overwritten while copying; the next event brings a fresh one
        _lastSeq = seq;
        return true;
    }

    void Deliver(IUnknown* token) {
        ComPtr<IMFSample> sample;
        ComPtr<IMFMediaBuffer> buf;
        BYTE* p;
        if (FAILED(MFCreateSample(&sample)) || FAILED(MFCreateMemoryBuffer(_size, &buf))) return;
        if (FAILED(buf->Lock(&p, nullptr, nullptr))) return;
        memcpy(p, _nv12.data(), _size);
        buf->Unlock();
        buf->SetCurrentLength(_size);
        sample->AddBuffer(buf.Get());
        sample->SetSampleTime(MFGetSystemTime());
        sample->SetSampleDuration(10'000'000 / _fps);
        if (token) sample->SetUnknown(MFSampleExtension_Token, token);
        _queue->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample.Get());
        if (_shared) _shared->lastReadTick = (LONG64)GetTickCount64();
    }

    void Run() {
        HANDLE handles[] = { _stop, _wake, _frameEvent };
        DWORD count = _frameEvent ? 3 : 2;
        // Wakes on a new frame or a new request; the timeout re-sends the last frame so apps never stall.
        while (WaitForMultipleObjects(count, handles, FALSE, 50) != WAIT_OBJECT_0) {
            if (ReadFrame()) _unsent = true;
            ULONGLONG now = GetTickCount64();
            if (!_unsent && now - _lastSent < 100) continue;
            ComPtr<IUnknown> token;
            {
                std::lock_guard<std::mutex> lock(_m);
                if (_tokens.empty()) continue;
                token = _tokens.front();
                _tokens.pop_front();
            }
            Deliver(token.Get());
            _unsent = false;
            _lastSent = now;
        }
    }

    void StopWorker() {
        if (!_worker.joinable()) return;
        SetEvent(_stop);
        _worker.join();
        ResetEvent(_stop);
    }

public:
    ~Stream() {
        StopWorker();
        if (_shared) UnmapViewOfFile(_shared);
        for (HANDLE h : { _wake, _stop, _map, _frameEvent }) if (h) CloseHandle(h);
    }

    HRESULT RuntimeClassInitialize(IMFMediaSource* source) {
        _source = source;
        // The format is read once: apps see a fixed camera mode until they reopen the camera.
        Settings s = LoadSettings();
        _w = s.width, _h = s.height, _fps = s.fps, _size = _w * _h * 3 / 2;

        RIF(MFCreateAttributes(&_attrs, 4));
        RIF(_attrs->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE));
        RIF(_attrs->SetUINT32(MF_DEVICESTREAM_STREAM_ID, 0));
        RIF(_attrs->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1));
        RIF(_attrs->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes_Color));

        ComPtr<IMFMediaType> type;
        RIF(MFCreateMediaType(&type));
        RIF(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
        RIF(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12));
        RIF(MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, _w, _h));
        RIF(MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, _fps, 1));
        RIF(MFSetAttributeRatio(type.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
        RIF(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
        RIF(type->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE));
        RIF(type->SetUINT32(MF_MT_DEFAULT_STRIDE, _w));
        RIF(type->SetUINT32(MF_MT_SAMPLE_SIZE, _size));
        // Android encoders emit BT.709 limited range at HD sizes, and the decoder passes it through.
        RIF(type->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709));
        RIF(type->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235));
        IMFMediaType* types[] = { type.Get() };
        RIF(MFCreateStreamDescriptor(0, 1, types, &_desc));
        RIF(_attrs->CopyAllItems(_desc.Get()));
        ComPtr<IMFMediaTypeHandler> handler;
        RIF(_desc->GetMediaTypeHandler(&handler));
        RIF(handler->SetCurrentMediaType(type.Get()));
        RIF(MFCreateEventQueue(&_queue));

        _nv12.assign(_size, 128);
        memset(_nv12.data(), 16, _w * _h);  // black until the phone streams
        _wake = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        _stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!_wake || !_stop) return HRESULT_FROM_WIN32(GetLastError());
        OpenShared();
        return S_OK;
    }

    IMFStreamDescriptor* Descriptor() { return _desc.Get(); }
    IMFAttributes* Attributes() { return _attrs.Get(); }
    bool Running() { std::lock_guard<std::mutex> lock(_m); return _state == MF_STREAM_STATE_RUNNING; }

    HRESULT Start() {
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        _state = MF_STREAM_STATE_RUNNING;
        if (!_worker.joinable()) _worker = std::thread(&Stream::Run, this);
        return _queue->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, nullptr);
    }

    HRESULT Stop() {
        StopWorker();  // outside the lock: the worker takes it
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        _state = MF_STREAM_STATE_STOPPED;
        _tokens.clear();
        return _queue->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr);
    }

    void Shutdown() {
        StopWorker();
        std::lock_guard<std::mutex> lock(_m);
        _tokens.clear();
        if (_queue) _queue->Shutdown();
        _queue.Reset();
    }

    // IMFMediaEventGenerator
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** e) override {
        ComPtr<IMFMediaEventQueue> q;
        { std::lock_guard<std::mutex> lock(_m); q = _queue; }  // GetEvent may block: don't hold the lock
        return q ? q->GetEvent(flags, e) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* state) override {
        std::lock_guard<std::mutex> lock(_m);
        return _queue ? _queue->BeginGetEvent(cb, state) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r, IMFMediaEvent** e) override {
        std::lock_guard<std::mutex> lock(_m);
        return _queue ? _queue->EndGetEvent(r, e) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP QueueEvent(MediaEventType t, REFGUID ext, HRESULT hr, const PROPVARIANT* v) override {
        std::lock_guard<std::mutex> lock(_m);
        return _queue ? _queue->QueueEventParamVar(t, ext, hr, v) : MF_E_SHUTDOWN;
    }

    // IMFMediaStream
    STDMETHODIMP GetMediaSource(IMFMediaSource** s) override {
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        *s = _source;
        _source->AddRef();
        return S_OK;
    }
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** d) override { return _desc.CopyTo(d); }
    STDMETHODIMP RequestSample(IUnknown* token) override {
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        if (_state != MF_STREAM_STATE_RUNNING) return MF_E_MEDIA_SOURCE_WRONGSTATE;
        _tokens.emplace_back(token);
        SetEvent(_wake);
        return S_OK;
    }

    // IMFMediaStream2
    STDMETHODIMP SetStreamState(MF_STREAM_STATE s) override {
        if (s == MF_STREAM_STATE_PAUSED) return MF_E_INVALID_STATE_TRANSITION;
        if (s == MF_STREAM_STATE_RUNNING) return Running() ? S_OK : Start();
        return Running() ? Stop() : S_OK;
    }
    STDMETHODIMP GetStreamState(MF_STREAM_STATE* s) override {
        std::lock_guard<std::mutex> lock(_m);
        *s = _state;
        return S_OK;
    }

    KS_NOT_FOUND
};

class Source : public RuntimeClass<RuntimeClassFlags<ClassicCom>,
    ChainInterfaces<IMFMediaSourceEx, IMFMediaSource, IMFMediaEventGenerator>, IMFGetService, IKsControl> {
    std::mutex _m;
    ComPtr<IMFMediaEventQueue> _queue;
    ComPtr<IMFAttributes> _attrs;
    ComPtr<IMFPresentationDescriptor> _pd;
    ComPtr<Stream> _stream;

public:
    HRESULT RuntimeClassInitialize(IMFAttributes* activatorAttrs) {
        RIF(MFCreateEventQueue(&_queue));
        RIF(MFCreateAttributes(&_attrs, 4));
        if (activatorAttrs) RIF(activatorAttrs->CopyAllItems(_attrs.Get()));

        // The Frame Server requires at least the legacy sensor profile.
        ComPtr<IMFSensorProfileCollection> profiles;
        ComPtr<IMFSensorProfile> legacy;
        RIF(MFCreateSensorProfileCollection(&profiles));
        RIF(MFCreateSensorProfile(KSCAMERAPROFILE_Legacy, 0, nullptr, &legacy));
        RIF(legacy->AddProfileFilter(0, L"((RES==;FRT<=120,1;SUT==))"));
        RIF(profiles->AddProfile(legacy.Get()));
        RIF(_attrs->SetUnknown(MF_DEVICEMFT_SENSORPROFILE_COLLECTION, profiles.Get()));

        RIF(MakeAndInitialize<Stream>(&_stream, static_cast<IMFMediaSource*>(this)));
        IMFStreamDescriptor* desc = _stream->Descriptor();
        RIF(MFCreatePresentationDescriptor(1, &desc, &_pd));
        return _pd->SelectStream(0);
    }

    // IMFMediaEventGenerator
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** e) override {
        ComPtr<IMFMediaEventQueue> q;
        { std::lock_guard<std::mutex> lock(_m); q = _queue; }
        return q ? q->GetEvent(flags, e) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* state) override {
        std::lock_guard<std::mutex> lock(_m);
        return _queue ? _queue->BeginGetEvent(cb, state) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r, IMFMediaEvent** e) override {
        std::lock_guard<std::mutex> lock(_m);
        return _queue ? _queue->EndGetEvent(r, e) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP QueueEvent(MediaEventType t, REFGUID ext, HRESULT hr, const PROPVARIANT* v) override {
        std::lock_guard<std::mutex> lock(_m);
        return _queue ? _queue->QueueEventParamVar(t, ext, hr, v) : MF_E_SHUTDOWN;
    }

    // IMFMediaSource
    STDMETHODIMP GetCharacteristics(DWORD* c) override {
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        *c = MFMEDIASOURCE_IS_LIVE;
        return S_OK;
    }
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** pd) override {
        std::lock_guard<std::mutex> lock(_m);
        return _queue ? _pd->Clone(pd) : MF_E_SHUTDOWN;
    }
    STDMETHODIMP Start(IMFPresentationDescriptor* pd, const GUID* format, const PROPVARIANT*) override {
        if (!pd) return E_POINTER;
        if (format && *format != GUID_NULL) return MF_E_UNSUPPORTED_TIME_FORMAT;
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        BOOL selected;
        ComPtr<IMFStreamDescriptor> desc;
        RIF(pd->GetStreamDescriptorByIndex(0, &selected, &desc));
        if (selected) {
            bool was = _stream->Running();
            RIF(_stream->Start());
            RIF(_queue->QueueEventParamUnk(was ? MEUpdatedStream : MENewStream, GUID_NULL, S_OK,
                static_cast<IMFMediaStream*>(_stream.Get())));
        }
        PROPVARIANT time;
        PropVariantInit(&time);
        time.vt = VT_I8;
        time.hVal.QuadPart = MFGetSystemTime();
        return _queue->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, &time);
    }
    STDMETHODIMP Stop() override {
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        if (_stream->Running()) RIF(_stream->Stop());
        return _queue->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
    }
    STDMETHODIMP Pause() override { return MF_E_INVALID_STATE_TRANSITION; }
    STDMETHODIMP Shutdown() override {
        std::lock_guard<std::mutex> lock(_m);
        if (!_queue) return MF_E_SHUTDOWN;
        _stream->Shutdown();
        _queue->Shutdown();
        _queue.Reset();
        return S_OK;
    }

    // IMFMediaSourceEx
    STDMETHODIMP GetSourceAttributes(IMFAttributes** a) override { return _attrs.CopyTo(a); }
    STDMETHODIMP GetStreamAttributes(DWORD id, IMFAttributes** a) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        *a = _stream->Attributes();
        (*a)->AddRef();
        return S_OK;
    }
    STDMETHODIMP SetD3DManager(IUnknown*) override { return S_OK; }  // samples are plain system memory

    // IMFGetService
    STDMETHODIMP GetService(REFGUID, REFIID, LPVOID*) override { return MF_E_UNSUPPORTED_SERVICE; }

    KS_NOT_FOUND
};

// The Frame Server CoCreates the registered CLSID and asks for IMFActivate.
#define FWD(name, params, args) STDMETHODIMP name params override { return _a->name args; }
class __declspec(uuid("02C7D363-CE79-4BAE-8B1D-9F40C1F01133")) Activator
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFActivate, IMFAttributes>> {
    ComPtr<IMFAttributes> _a;
    ComPtr<Source> _source;

public:
    HRESULT RuntimeClassInitialize() { return MFCreateAttributes(&_a, 1); }

    STDMETHODIMP ActivateObject(REFIID riid, void** ppv) override {
        if (!_source) RIF(MakeAndInitialize<Source>(&_source, _a.Get()));
        return _source.CopyTo(riid, ppv);
    }
    STDMETHODIMP ShutdownObject() override {
        if (_source) _source->Shutdown();
        _source.Reset();
        return S_OK;
    }
    STDMETHODIMP DetachObject() override { _source.Reset(); return S_OK; }

    FWD(GetItem, (REFGUID k, PROPVARIANT* v), (k, v))
    FWD(GetItemType, (REFGUID k, MF_ATTRIBUTE_TYPE* t), (k, t))
    FWD(CompareItem, (REFGUID k, REFPROPVARIANT v, BOOL* r), (k, v, r))
    FWD(Compare, (IMFAttributes* o, MF_ATTRIBUTES_MATCH_TYPE m, BOOL* r), (o, m, r))
    FWD(GetUINT32, (REFGUID k, UINT32* v), (k, v))
    FWD(GetUINT64, (REFGUID k, UINT64* v), (k, v))
    FWD(GetDouble, (REFGUID k, double* v), (k, v))
    FWD(GetGUID, (REFGUID k, GUID* v), (k, v))
    FWD(GetStringLength, (REFGUID k, UINT32* n), (k, n))
    FWD(GetString, (REFGUID k, LPWSTR s, UINT32 c, UINT32* n), (k, s, c, n))
    FWD(GetAllocatedString, (REFGUID k, LPWSTR* s, UINT32* n), (k, s, n))
    FWD(GetBlobSize, (REFGUID k, UINT32* n), (k, n))
    FWD(GetBlob, (REFGUID k, UINT8* b, UINT32 c, UINT32* n), (k, b, c, n))
    FWD(GetAllocatedBlob, (REFGUID k, UINT8** b, UINT32* n), (k, b, n))
    FWD(GetUnknown, (REFGUID k, REFIID r, LPVOID* p), (k, r, p))
    FWD(SetItem, (REFGUID k, REFPROPVARIANT v), (k, v))
    FWD(DeleteItem, (REFGUID k), (k))
    FWD(DeleteAllItems, (), ())
    FWD(SetUINT32, (REFGUID k, UINT32 v), (k, v))
    FWD(SetUINT64, (REFGUID k, UINT64 v), (k, v))
    FWD(SetDouble, (REFGUID k, double v), (k, v))
    FWD(SetGUID, (REFGUID k, REFGUID v), (k, v))
    FWD(SetString, (REFGUID k, LPCWSTR v), (k, v))
    FWD(SetBlob, (REFGUID k, const UINT8* b, UINT32 n), (k, b, n))
    FWD(SetUnknown, (REFGUID k, IUnknown* u), (k, u))
    FWD(LockStore, (), ())
    FWD(UnlockStore, (), ())
    FWD(GetCount, (UINT32* n), (n))
    FWD(GetItemByIndex, (UINT32 i, GUID* k, PROPVARIANT* v), (i, k, v))
    FWD(CopyAllItems, (IMFAttributes* d), (d))
};
CoCreatableClass(Activator);

BOOL WINAPI DllMain(HINSTANCE h, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) { g_module = h; DisableThreadLibraryCalls(h); }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID c, REFIID r, void** p) { return Module<InProc>::GetModule().GetClassObject(c, r, p); }
STDAPI DllCanUnloadNow() { return Module<InProc>::GetModule().GetObjectCount() == 0 ? S_OK : S_FALSE; }

static HRESULT CreateCamera(IMFVirtualCamera** cam) {
    // System lifetime: the camera stays registered for all users after the installer exits.
    return MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, MFVirtualCameraLifetime_System,
        MFVirtualCameraAccess_AllUsers, kCameraName, kClsid, nullptr, 0, cam);
}

STDAPI DllRegisterServer() {
    wchar_t path[MAX_PATH], key[128];
    if (!GetModuleFileNameW(g_module, path, MAX_PATH)) return HRESULT_FROM_WIN32(GetLastError());
    swprintf_s(key, L"Software\\Classes\\CLSID\\%s\\InprocServer32", kClsid);
    RIF(HRESULT_FROM_WIN32(RegSetKeyValueW(HKEY_LOCAL_MACHINE, key, nullptr, REG_SZ, path,
        (DWORD)((wcslen(path) + 1) * sizeof(wchar_t)))));
    RIF(HRESULT_FROM_WIN32(RegSetKeyValueW(HKEY_LOCAL_MACHINE, key, L"ThreadingModel", REG_SZ, L"Both", sizeof(L"Both"))));
    RIF(MFStartup(MF_VERSION));
    ComPtr<IMFVirtualCamera> cam;
    HRESULT hr = CreateCamera(&cam);
    if (SUCCEEDED(hr)) hr = cam->Start(nullptr);
    cam.Reset();
    MFShutdown();
    return hr;
}

STDAPI DllUnregisterServer() {
    RIF(MFStartup(MF_VERSION));
    ComPtr<IMFVirtualCamera> cam;
    HRESULT hr = CreateCamera(&cam);
    if (SUCCEEDED(hr)) hr = cam->Remove();
    cam.Reset();
    MFShutdown();
    wchar_t key[128];
    swprintf_s(key, L"Software\\Classes\\CLSID\\%s", kClsid);
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, key);
    return hr;
}
