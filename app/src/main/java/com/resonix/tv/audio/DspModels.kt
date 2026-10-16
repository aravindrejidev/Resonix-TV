package com.resonix.tv.audio

enum class EqBandType(val id: Int) {
    PEAK(0),
    LOW_SHELF(1),
    HIGH_SHELF(2),
    HIGH_PASS(3),
    LOW_PASS(4),
}

/** One parametric EQ band: frequency, gain and Q-factor. */
data class EqBand(
    val freqHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val type: EqBandType = EqBandType.PEAK,
    val enabled: Boolean = true,
)

/** Speaker position of one discrete PCM channel. */
enum class ChannelRole(val shortName: String, val label: String) {
    MONO("M", "Mono"),
    FRONT_LEFT("FL", "Front L"),
    FRONT_RIGHT("FR", "Front R"),
    CENTER("C", "Center"),
    LFE("LFE", "Subwoofer"),
    SURROUND_LEFT("SL", "Surround L"),
    SURROUND_RIGHT("SR", "Surround R"),
    BACK_LEFT("BL", "Back L"),
    BACK_RIGHT("BR", "Back R"),
    BACK_CENTER("BC", "Back C"),
    SIDE_LEFT("SDL", "Side L"),
    SIDE_RIGHT("SDR", "Side R"),
}

/** Channel order of the interleaved PCM that ExoPlayer hands to the DSP (WAVE / Android order). */
object ChannelLayouts {
    fun forChannelCount(count: Int): List<ChannelRole> = when (count) {
        1 -> listOf(ChannelRole.MONO)
        2 -> listOf(ChannelRole.FRONT_LEFT, ChannelRole.FRONT_RIGHT)
        3 -> listOf(ChannelRole.FRONT_LEFT, ChannelRole.FRONT_RIGHT, ChannelRole.CENTER)
        4 -> listOf(
            ChannelRole.FRONT_LEFT, ChannelRole.FRONT_RIGHT,
            ChannelRole.BACK_LEFT, ChannelRole.BACK_RIGHT,
        )
        5 -> listOf(
            ChannelRole.FRONT_LEFT, ChannelRole.FRONT_RIGHT, ChannelRole.CENTER,
            ChannelRole.SURROUND_LEFT, ChannelRole.SURROUND_RIGHT,
        )
        6 -> listOf( // 5.1
            ChannelRole.FRONT_LEFT, ChannelRole.FRONT_RIGHT, ChannelRole.CENTER, ChannelRole.LFE,
            ChannelRole.SURROUND_LEFT, ChannelRole.SURROUND_RIGHT,
        )
        7 -> listOf( // 6.1
            ChannelRole.FRONT_LEFT, ChannelRole.FRONT_RIGHT, ChannelRole.CENTER, ChannelRole.LFE,
            ChannelRole.BACK_CENTER, ChannelRole.SIDE_LEFT, ChannelRole.SIDE_RIGHT,
        )
        8 -> listOf( // 7.1
            ChannelRole.FRONT_LEFT, ChannelRole.FRONT_RIGHT, ChannelRole.CENTER, ChannelRole.LFE,
            ChannelRole.BACK_LEFT, ChannelRole.BACK_RIGHT,
            ChannelRole.SIDE_LEFT, ChannelRole.SIDE_RIGHT,
        )
        else -> List(count.coerceAtLeast(0)) { ChannelRole.MONO }
    }
}

object EqDefaults {
    /** Classic 10-band layout (Hz). */
    val ISO_FREQUENCIES = floatArrayOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    fun flat(): List<EqBand> = ISO_FREQUENCIES.map { EqBand(freqHz = it) }
}

/** A named combination of per-channel gains and EQ bands. */
data class DspPreset(
    val name: String,
    val channelGainsDb: Map<ChannelRole, Float> = emptyMap(),
    val bands: List<EqBand> = EqDefaults.flat(),
    val eqEnabled: Boolean = true,
)

object DspPresets {
    val FLAT = DspPreset(name = "Flat")

    /** Clearer speech: louder centre channel + a little presence around 1-4 kHz. */
    val DIALOGUE = DspPreset(
        name = "Dialogue boost",
        channelGainsDb = mapOf(ChannelRole.CENTER to 6f),
        bands = EqDefaults.flat().mapIndexed { i, b ->
            when (i) {
                5 -> b.copy(gainDb = 1.0f)
                6 -> b.copy(gainDb = 2.0f)
                7 -> b.copy(gainDb = 1.5f)
                else -> b
            }
        },
    )

    /** More bass: louder subwoofer channel + a low shelf for the other speakers. */
    val BASS = DspPreset(
        name = "Bass boost",
        channelGainsDb = mapOf(ChannelRole.LFE to 6f),
        bands = EqDefaults.flat().mapIndexed { i, b ->
            if (i == 0) b.copy(freqHz = 100f, gainDb = 4f, q = 0.7f, type = EqBandType.LOW_SHELF) else b
        },
    )

    val ALL = listOf(FLAT, DIALOGUE, BASS)
}
