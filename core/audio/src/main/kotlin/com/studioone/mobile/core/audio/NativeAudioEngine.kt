package com.studioone.mobile.core.audio

/**
 * Raw JNI surface of the C++ engine. All methods are cheap (lock-free pushes
 * or critical-section copies) unless documented otherwise. Kotlin code should
 * go through [AudioEngineController] instead of calling this directly.
 *
 * Command type ordinals MUST stay in sync with
 * native/engine/EngineCommands.h::CommandType (contract test:
 * NativeContractTest.commandOrdinalsMatchHeader).
 */
internal object NativeAudioEngine {

    init {
        // c++_shared STL is packaged in the APK by AGP; load it first.
        System.loadLibrary("c++_shared")
        System.loadLibrary("studioone-audio")
    }

    // Lifecycle (control thread; blocking — never call from UI thread).
    external fun nativeCreate(
        sampleRate: Int, framesPerBlock: Int, maxStrips: Int, maxInstrumentStrips: Int,
        lowLatency: Boolean, exclusive: Boolean, inputEnabled: Boolean,
    ): Long

    external fun nativeDestroy(handle: Long)
    external fun nativeRestart(handle: Long): Boolean

    // Generic command.
    external fun nativeSendCommand(
        handle: Long, type: Int, target: Int, arg1: Int, arg2: Int,
        fval: Float, fval2: Float, lval: Long, lval2: Long,
    ): Boolean

    external fun nativeSetFxSlot(handle: Long, target: Int, slot: Int, pluginId: Int, paramPairs: FloatArray?): Boolean
    external fun nativeUploadAutomation(handle: Long, target: Int, paramId: Int, points: FloatArray): Boolean
    external fun nativeSetInstrument(handle: Long, target: Int, instrumentType: Int, presetBlob: FloatArray?): Boolean

    // Sample pool.
    external fun nativeAllocSampleSlot(handle: Long): Int
    external fun nativeLoadSample(handle: Long, slot: Int, interleaved: FloatArray, channels: Int, sampleRate: Int): Boolean
    external fun nativeUnloadSample(handle: Long, slot: Int)

    // Playback feed (prefetch threads).
    external fun nativeFeedTrack(handle: Long, stripHandle: Int, left: FloatArray, right: FloatArray, frames: Int): Int
    external fun nativeFeedWritable(handle: Long, stripHandle: Int): Int

    // MIDI.
    external fun nativeSendMidi(
        handle: Long, stripHandle: Int, sampleOffset: Int, type: Int, channel: Int,
        key: Int, velocity: Int, bend: Int, fval: Float,
    )

    // Recording.
    external fun nativeBeginTake(handle: Long, stripHandle: Int, path: String, channels: Int, bitsPerSample: Int): Int
    external fun nativeEndTake(handle: Long, takeIndex: Int): Long
    external fun nativeTakeStats(handle: Long, takeIndex: Int, out: LongArray)

    // Meters & telemetry.
    external fun nativePullMasterMeters(handle: Long, out: FloatArray)
    external fun nativePullStripMeters(handle: Long, stripHandle: Int, out: FloatArray): Boolean
    external fun nativePullSpectrum(handle: Long, out: FloatArray): Boolean
    external fun nativeLatencyReport(handle: Long, floatsOut: FloatArray, intsOut: IntArray)
    external fun nativeTransportPosition(handle: Long): Long
    external fun nativeTransportState(handle: Long): Int
    external fun nativeDrainReclaim(handle: Long): Int

    /** Must mirror engine/EngineCommands.h::CommandType exactly. */
    object CommandType {
        const val PLAY = 0; const val STOP = 1; const val PAUSE = 2
        const val RECORD_START = 3; const val RECORD_STOP = 4
        const val SET_POSITION_FRAMES = 5; const val SET_LOOP_REGION = 6
        const val SET_TEMPO = 7; const val SET_TIME_SIGNATURE = 8
        const val SET_METRONOME = 9; const val SET_COUNT_IN = 10
        const val ADD_STRIP = 11; const val REMOVE_STRIP = 12; const val SET_STRIP_ACTIVE = 13
        const val SET_STRIP_GAIN_DB = 14; const val SET_STRIP_PAN = 15
        const val SET_STRIP_MUTE = 16; const val SET_STRIP_SOLO = 17
        const val SET_STRIP_WIDTH = 18; const val SET_STRIP_INPUT_GAIN = 19
        const val SET_STRIP_OUTPUT_BUS = 20; const val SET_STRIP_ORDER = 21
        const val SET_SEND_LEVEL = 22; const val SET_SEND_ENABLED = 23; const val SET_SEND_PAN = 24
        const val SET_FX_SLOT = 25; const val SET_FX_PARAM = 26; const val SET_FX_BYPASS = 27
        const val SET_FX_WET_MIX = 28; const val MOVE_FX_SLOT = 29
        const val UPLOAD_AUTOMATION = 30; const val CLEAR_AUTOMATION = 31; const val SET_AUTOMATION_MODE = 32
        const val SET_SOURCE_ACTIVE = 33; const val SEEK_SOURCE = 34
        const val SET_INSTRUMENT = 35; const val REMOVE_INSTRUMENT = 36
        const val SET_INSTRUMENT_MACRO = 37; const val SET_ARPEGGIATOR = 38; const val DRUM_PAD_SET = 39
        const val LOAD_SAMPLE = 40; const val UNLOAD_SAMPLE = 41
        const val ARM_STRIP = 42; const val SET_MONITOR_FX = 43; const val SET_MONITOR_MODE = 44
        const val SET_BUS_GAIN = 45; const val SET_BUS_PAN = 46; const val SET_BUS_MUTE = 47
        const val SET_MASTER_LIMITER = 48; const val RESET_METERS = 49; const val RESET_LOUDNESS = 50
        const val SET_BUFFER_SIZE = 51; const val PANIC = 52; const val NOP = 53
    }
}
