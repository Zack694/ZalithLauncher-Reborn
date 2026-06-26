// RecordZy audio tap - compiled directly into our custom libopenal.so build.
//
// DeviceBase::renderSamples() calls recordzy_tap_feed() with the final mixed
// interleaved PCM. The data is copied into a lock-free ring buffer, which the
// RecordZy recorder drains through the exported recordzy_tap_read(). Because
// these symbols are exported (visibility default), the recorder can resolve
// them from the loaded libopenal.so even across linker namespaces - no inline
// hooking and no symbol table needed in the shipped library.

#ifndef RECORDZY_TAP_H
#define RECORDZY_TAP_H

#ifdef __cplusplus
extern "C" {
#endif

// Called from OpenAL's mixer with freshly rendered device-format PCM.
// `dev` identifies the source ALCdevice/DeviceBase so the tap can lock onto a
// single device and ignore any other contexts (which would otherwise double up
// the captured stream).
void recordzy_tap_feed(const void *dev, const void *pcm, unsigned numSamples,
                       unsigned frameStep, unsigned freq, unsigned bytesPerSample, int isFloat);

// Drain up to maxSamples interleaved int16 samples; returns the count copied.
int recordzy_tap_read(short *out, int maxSamples);

int recordzy_tap_samplerate(void);
int recordzy_tap_channels(void);

// Number of distinct render devices/contexts seen since the last start (>1 means
// the process has multiple OpenAL outputs; the tap only captures the first).
int recordzy_tap_device_count(void);

// Enable/disable capture (and reset the ring on enable). Idle cost when off is
// a single relaxed atomic load per mixer call.
void recordzy_tap_set_active(int active);

#ifdef __cplusplus
}
#endif

#endif // RECORDZY_TAP_H
