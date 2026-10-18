#include "decoder.h"
#include <codecapi.h>
#include <mfapi.h>
#include <mferror.h>
#include <wmcodecdsp.h>

using Microsoft::WRL::ComPtr;

#define RIF(x) do { HRESULT hr_ = (x); if (FAILED(hr_)) return hr_; } while (0)

HRESULT H264Decoder::Init(UINT32 width, UINT32 height, UINT32 fps) {
    _w = width, _h = height, _fps = fps, _time = 0;
    RIF(CoCreateInstance(CLSID_CMSH264DecoderMFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&_mft)));
    ComPtr<IMFAttributes> attrs;
    RIF(_mft->GetAttributes(&attrs));
    RIF(attrs->SetUINT32(CODECAPI_AVLowLatencyMode, TRUE));

    ComPtr<IMFMediaType> in;
    RIF(MFCreateMediaType(&in));
    RIF(in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
    RIF(in->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264));
    RIF(MFSetAttributeSize(in.Get(), MF_MT_FRAME_SIZE, _w, _h));
    RIF(MFSetAttributeRatio(in.Get(), MF_MT_FRAME_RATE, _fps, 1));
    RIF(in->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
    RIF(_mft->SetInputType(0, in.Get(), 0));
    RIF(SetOutputType());
    RIF(_mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0));
    return _mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
}

HRESULT H264Decoder::SetOutputType() {
    for (DWORD i = 0;; i++) {
        ComPtr<IMFMediaType> t;
        RIF(_mft->GetOutputAvailableType(0, i, &t));
        GUID sub;
        if (SUCCEEDED(t->GetGUID(MF_MT_SUBTYPE, &sub)) && sub == MFVideoFormat_NV12) {
            RIF(_mft->SetOutputType(0, t.Get(), 0));
            // The decoder pads the height to a multiple of 16 (1080 -> 1088); the UV plane starts after it.
            UINT32 w, h;
            RIF(MFGetAttributeSize(t.Get(), MF_MT_FRAME_SIZE, &w, &h));
            if (w < _w || h < _h) return MF_E_INVALIDMEDIATYPE;  // stream is not the size we asked the phone for
            _alignedW = w, _alignedH = h;
            return S_OK;
        }
    }
}

HRESULT H264Decoder::Decode(const BYTE* data, DWORD size, BYTE* nv12) {
    ComPtr<IMFMediaBuffer> buf;
    ComPtr<IMFSample> sample;
    BYTE* p;
    RIF(MFCreateMemoryBuffer(size, &buf));
    RIF(buf->Lock(&p, nullptr, nullptr));
    memcpy(p, data, size);
    buf->Unlock();
    RIF(buf->SetCurrentLength(size));
    RIF(MFCreateSample(&sample));
    RIF(sample->AddBuffer(buf.Get()));
    RIF(sample->SetSampleTime(_time));
    RIF(sample->SetSampleDuration(10'000'000 / _fps));
    _time += 10'000'000 / _fps;

    bool got = false;
    HRESULT hr = _mft->ProcessInput(0, sample.Get(), 0);
    if (hr == MF_E_NOTACCEPTING) {
        RIF(Drain(nv12, got));
        hr = _mft->ProcessInput(0, sample.Get(), 0);
    }
    RIF(hr);
    RIF(Drain(nv12, got));
    return got ? S_OK : S_FALSE;
}

HRESULT H264Decoder::Drain(BYTE* nv12, bool& got) {
    for (;;) {
        MFT_OUTPUT_STREAM_INFO info{};
        RIF(_mft->GetOutputStreamInfo(0, &info));
        ComPtr<IMFSample> sample;
        if (!(info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES))) {
            ComPtr<IMFMediaBuffer> buf;
            RIF(MFCreateMemoryBuffer(info.cbSize, &buf));
            RIF(MFCreateSample(&sample));
            RIF(sample->AddBuffer(buf.Get()));
        }
        MFT_OUTPUT_DATA_BUFFER out{};
        out.pSample = sample.Get();
        DWORD status = 0;
        HRESULT hr = _mft->ProcessOutput(0, 1, &out, &status);
        if (out.pEvents) out.pEvents->Release();
        if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) return S_OK;
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE) { RIF(SetOutputType()); continue; }
        RIF(hr);
        // Only the newest picture matters: an older one in the same batch is overwritten.
        hr = CopyOut(out.pSample, nv12);
        if (out.pSample != sample.Get()) out.pSample->Release();
        RIF(hr);
        got = true;
    }
}

HRESULT H264Decoder::CopyOut(IMFSample* sample, BYTE* nv12) {
    ComPtr<IMFMediaBuffer> buf;
    RIF(sample->ConvertToContiguousBuffer(&buf));
    BYTE* src;
    LONG pitch;
    ComPtr<IMF2DBuffer> buf2d;
    bool is2d = SUCCEEDED(buf.As(&buf2d));
    if (is2d) RIF(buf2d->Lock2D(&src, &pitch));
    else { RIF(buf->Lock(&src, nullptr, nullptr)); pitch = (LONG)_alignedW; }
    for (UINT32 y = 0; y < _h; y++) memcpy(nv12 + y * _w, src + y * pitch, _w);
    const BYTE* uv = src + pitch * _alignedH;
    for (UINT32 y = 0; y < _h / 2; y++) memcpy(nv12 + _w * _h + y * _w, uv + y * pitch, _w);
    if (is2d) buf2d->Unlock2D(); else buf->Unlock();
    return S_OK;
}
