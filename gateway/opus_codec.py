"""Bounded raw-Opus codec using the system libopus C API (no Python codec binding)."""
import ctypes as C
import ctypes.util
import sys
from array import array

RATE, CHANNELS, FRAME_MS = 16000, 1, 60
FRAME_SAMPLES, MAX_PACKET = 960, 4000
FORMAT = {"codec": "opus", "sample_rate": RATE, "channels": CHANNELS, "frame_ms": FRAME_MS}
MAX_INPUT_SAMPLES, MAX_OUTPUT_SAMPLES = RATE * 15, RATE * 30


class AudioError(ValueError):
    pass


def integer(obj, key, maximum):
    value = obj.get(key)
    if type(value) is not int or not 0 <= value <= maximum:
        raise AudioError("invalid integer: " + key)
    return value


def require_format(value):
    if not isinstance(value, dict) or value.get("codec") != "opus":
        raise AudioError("opus format required")
    for key in ("sample_rate", "channels", "frame_ms"):
        if integer(value, key, FORMAT[key]) != FORMAT[key]:
            raise AudioError("unsupported audio " + key)


def trim(decoded, pre_skip, samples, maximum):
    if type(pre_skip) is not int or not 0 <= pre_skip <= FRAME_SAMPLES:
        raise AudioError("invalid pre_skip")
    if type(samples) is not int or not 0 <= samples <= maximum or len(decoded) % 2:
        raise AudioError("invalid sample count")
    if samples == 0 and not decoded and pre_skip == 0:
        return b""
    padding = len(decoded) // 2 - pre_skip - samples
    if not 0 <= padding < FRAME_SAMPLES:
        raise AudioError("sample count does not match packets")
    return bytes(decoded[2 * pre_skip:2 * (pre_skip + samples)])


class LibOpus:
    def __init__(self):
        path = ctypes.util.find_library("opus")
        if not path:
            raise RuntimeError("libopus is missing; install the system libopus runtime")
        self.lib = C.CDLL(path)
        lib = self.lib
        lib.opus_encoder_create.argtypes = [C.c_int, C.c_int, C.c_int, C.POINTER(C.c_int)]
        lib.opus_encoder_create.restype = C.c_void_p
        lib.opus_decoder_create.argtypes = [C.c_int, C.c_int, C.POINTER(C.c_int)]
        lib.opus_decoder_create.restype = C.c_void_p
        lib.opus_encoder_destroy.argtypes = [C.c_void_p]
        lib.opus_decoder_destroy.argtypes = [C.c_void_p]
        lib.opus_encode.argtypes = [C.c_void_p, C.POINTER(C.c_int16), C.c_int,
                                    C.POINTER(C.c_ubyte), C.c_int32]
        lib.opus_encode.restype = C.c_int32
        lib.opus_decode.argtypes = [C.c_void_p, C.POINTER(C.c_ubyte), C.c_int32,
                                    C.POINTER(C.c_int16), C.c_int, C.c_int]
        lib.opus_decode.restype = C.c_int
        lib.opus_packet_get_nb_samples.argtypes = [C.POINTER(C.c_ubyte), C.c_int32, C.c_int32]
        lib.opus_packet_get_nb_samples.restype = C.c_int
        # Only fixed arguments of this variadic function are declared.
        lib.opus_encoder_ctl.argtypes = [C.c_void_p, C.c_int]
        lib.opus_encoder_ctl.restype = C.c_int
        lib.opus_strerror.argtypes = [C.c_int]
        lib.opus_strerror.restype = C.c_char_p
        lib.opus_get_version_string.restype = C.c_char_p

    def check(self, code):
        if code < 0:
            raise AudioError(self.lib.opus_strerror(code).decode("utf-8"))
        return code


class Encoder:
    def __init__(self, api):
        self.api, self.handle = api, None
        error = C.c_int()
        self.handle = api.lib.opus_encoder_create(RATE, 1, 2048, C.byref(error))
        try:
            api.check(error.value)
            if not self.handle:
                raise AudioError("encoder allocation failed")
            api.check(api.lib.opus_encoder_ctl(self.handle, 4002, C.c_int(24000)))  # BITRATE
            api.check(api.lib.opus_encoder_ctl(self.handle, 4010, C.c_int(5)))  # COMPLEXITY
            api.check(api.lib.opus_encoder_ctl(self.handle, 4024, C.c_int(3001)))  # SIGNAL_VOICE
            skip = C.c_int()
            api.check(api.lib.opus_encoder_ctl(self.handle, 4027, C.byref(skip)))  # GET_LOOKAHEAD
            self.pre_skip = skip.value
            if not 0 <= self.pre_skip <= FRAME_SAMPLES:
                raise AudioError("unsupported encoder lookahead")
        except BaseException:
            self.close()
            raise

    def encode(self, pcm):
        if not self.handle or len(pcm) != FRAME_SAMPLES * 2:
            raise AudioError("encoder requires one 60 ms PCM16 frame")
        values = array("h")
        values.frombytes(pcm)
        if sys.byteorder != "little":
            values.byteswap()
        source = (C.c_int16 * FRAME_SAMPLES)(*values)
        target = (C.c_ubyte * MAX_PACKET)()
        size = self.api.check(self.api.lib.opus_encode(self.handle, source, FRAME_SAMPLES, target, MAX_PACKET))
        if size == 0:
            raise AudioError("empty encoded packet")
        return bytes(target[:size])

    def packets(self, pcm):
        if len(pcm) % 2 or len(pcm) // 2 > MAX_OUTPUT_SAMPLES:
            raise AudioError("invalid output PCM length")
        if not pcm:
            return []
        data = bytes(pcm) + bytes(self.pre_skip * 2)
        padding = (-len(data)) % (FRAME_SAMPLES * 2)
        data += bytes(padding)
        return [self.encode(data[i:i + FRAME_SAMPLES * 2])
                for i in range(0, len(data), FRAME_SAMPLES * 2)]

    def close(self):
        if self.handle:
            self.api.lib.opus_encoder_destroy(self.handle)
            self.handle = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class Decoder:
    def __init__(self, api):
        self.api, self.handle = api, None
        error = C.c_int()
        self.handle = api.lib.opus_decoder_create(RATE, 1, C.byref(error))
        try:
            api.check(error.value)
            if not self.handle:
                raise AudioError("decoder allocation failed")
        except BaseException:
            self.close()
            raise

    def decode(self, packet):
        if not self.handle or not 1 <= len(packet) <= MAX_PACKET:
            raise AudioError("invalid packet size or closed decoder")
        source = (C.c_ubyte * len(packet)).from_buffer_copy(packet)
        samples = self.api.check(self.api.lib.opus_packet_get_nb_samples(source, len(packet), RATE))
        if samples != FRAME_SAMPLES:
            raise AudioError("only 60 ms Opus packets are negotiated")
        target = (C.c_int16 * FRAME_SAMPLES)()
        samples = self.api.check(self.api.lib.opus_decode(self.handle, source, len(packet), target,
                                                        FRAME_SAMPLES, 0))
        if samples != FRAME_SAMPLES:
            raise AudioError("unexpected decoded length")
        values = array("h", target)
        if sys.byteorder != "little":
            values.byteswap()
        return values.tobytes()

    def close(self):
        if self.handle:
            self.api.lib.opus_decoder_destroy(self.handle)
            self.handle = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
