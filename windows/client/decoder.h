#pragma once
#include <windows.h>
#include <mftransform.h>
#include <wrl/client.h>

// Windows' built-in H.264 decoder in low-latency mode (no frame reordering delay).
class H264Decoder {
public:
    HRESULT Init(UINT32 width, UINT32 height, UINT32 fps);
    // Feeds one Annex-B access unit. Returns S_OK with `nv12` (width * height * 3 / 2 bytes, no padding)
    // filled when a picture came out, S_FALSE when the decoder needs more input.
    HRESULT Decode(const BYTE* data, DWORD size, BYTE* nv12);

private:
    HRESULT SetOutputType();
    HRESULT Drain(BYTE* nv12, bool& got);
    HRESULT CopyOut(IMFSample* sample, BYTE* nv12);

    Microsoft::WRL::ComPtr<IMFTransform> _mft;
    UINT32 _w = 0, _h = 0, _fps = 30, _alignedW = 0, _alignedH = 0;
    LONGLONG _time = 0;
};
