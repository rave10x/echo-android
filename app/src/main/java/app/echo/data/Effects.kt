package app.echo.data

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/** Equalizer + bass boost on the player's audio session. Presets are shapes (in millibels) stretched to however many bands the device has. */
class Effects(private val store: Store) {
    private var eq: Equalizer? = null
    private var bb: BassBoost? = null
    var bands = 0; private set
    var minLevel = -1500; private set
    var maxLevel = 1500; private set
    var freqs: List<Int> = emptyList(); private set
    val supported get() = eq != null

    companion object {
        val PRESETS = linkedMapOf(
            "Flat" to listOf(0, 0, 0, 0, 0), "Bass" to listOf(600, 400, 0, -100, -100), "Vocal" to listOf(-200, 0, 300, 300, -100),
            "Rock" to listOf(400, 200, -100, 200, 400), "Treble" to listOf(-200, -100, 0, 300, 500), "Pop" to listOf(-100, 200, 300, 100, -100),
        )
    }

    fun levelsFor(name: String): List<Int> {
        val shape = PRESETS[name] ?: PRESETS.getValue("Flat")
        if (bands <= 0) return shape
        return List(bands) { i -> shape[(i * (shape.size - 1) / (bands - 1).coerceAtLeast(1)).coerceIn(0, shape.lastIndex)].coerceIn(minLevel, maxLevel) }
    }

    fun attach(session: Int) {
        runCatching { eq?.release() }; runCatching { bb?.release() }; eq = null; bb = null
        if (session == 0) return
        eq = runCatching { Equalizer(0, session) }.getOrNull()
        bb = runCatching { BassBoost(0, session) }.getOrNull()
        eq?.let { e ->
            bands = e.numberOfBands.toInt(); minLevel = e.bandLevelRange[0].toInt(); maxLevel = e.bandLevelRange[1].toInt()
            freqs = (0 until bands).map { e.getCenterFreq(it.toShort()) / 1000 }
        }
        apply()
    }

    fun apply() {
        val d = store.data
        runCatching {
            eq?.let { e ->
                val lv = if (d.eqLevels.size == bands) d.eqLevels else levelsFor("Flat")
                for (i in 0 until bands) e.setBandLevel(i.toShort(), lv[i].coerceIn(minLevel, maxLevel).toShort())
                e.enabled = d.eqOn
            }
            bb?.let { b -> if (b.strengthSupported) { b.setStrength(d.bass.coerceIn(0, 1000).toShort()); b.enabled = d.eqOn && d.bass > 0 } }
        }
    }
}
