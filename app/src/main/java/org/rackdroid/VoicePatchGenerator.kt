package org.rackdroid

/**
 * Voice-to-patch generator -- milestone 2: intent to .vcv JSON.
 *
 * Turns a recognized voice command into a valid VCV Rack patch spec.
 * Rule-based for the MVP; patch_templates.json can be extended later
 * without code changes.
 */
class VoicePatchGenerator private constructor() {

    fun generatePatch(command: String): String {
        val normalized = command.trim().lowercase()
        val intent = detectIntent(normalized)

        return when (intent) {
            "bassline" -> buildBasslinePatch()
            "drumkit"  -> buildDrumKitPatch()
            "lfo"      -> buildLfoPatch()
            "adsr"     -> buildAdsrPatch()
            "vca"      -> buildVcaPatch()
            "vcf"      -> buildVcfPatch()
            "vco"      -> buildVcoPatch()
            "sample_player" -> buildSamplePlayerPatch()
            else       -> buildDefaultPatch()
        }
    }

    private fun detectIntent(command: String): String = when {
        command.contains("bassline") || command.contains("bass line") -> "bassline"
        command.contains("drum")                                      -> "drumkit"
        command.contains("lfo")                                       -> "lfo"
        command.contains("adsr") || command.contains("envelope")      -> "adsr"
        command.contains("vca")                                       -> "vca"
        command.contains("vcf")                                       -> "vcf"
        command.contains("vco")                                       -> "vco"
        command.contains("sample") || command.contains("sampler")     -> "sample_player"
        else                                                          -> "default"
    }

    private fun buildVcoPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV VCO", "params": { "fundamental": 440, "saw_sync": 0, "pulse_width": 0.5, "sync_input": 0 }, "connections": [] } ], "cables": [] }"""

    private fun buildVcfPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV VCF", "params": { "cutoff": 1000, "resonance": 0.5, "env_amount": 1.0 }, "connections": [] } ], "cables": [] }"""

    private fun buildVcaPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV VCA", "params": { "gain": 0.8, "env_amount": 1.0 }, "connections": [] } ], "cables": [] }"""

    private fun buildLfoPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV LFO", "params": { "frequency": 5.0, "type": "sine", "delay": 0.0 }, "connections": [] } ], "cables": [] }"""

    private fun buildAdsrPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV ADSR", "params": { "attack": 0.01, "decay": 0.1, "sustain": 0.7, "release": 1.0 }, "connections": [] } ], "cables": [] }"""

    private fun buildSamplePlayerPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV Sample Player", "params": { "sample_file": "", "root_key": 60, "gain": 0.5, "loop": false }, "connections": [] } ], "cables": [] }"""

    private fun buildBasslinePatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV VCO", "params": { "fundamental": 220, "saw_sync": 0, "pulse_width": 0.5, "sync_input": 0 }, "connections": [] }, { "name": "VCV VCF", "params": { "cutoff": 800, "resonance": 0.5, "env_amount": 1.0 }, "connections": [] }, { "name": "VCV VCA", "params": { "gain": 0.8, "env_amount": 1.0 }, "connections": [] }, { "name": "VCV LFO", "params": { "frequency": 2.0, "type": "sine", "delay": 0.0 }, "connections": [] }, { "name": "VCV ADSR", "params": { "attack": 0.01, "decay": 0.1, "sustain": 0.7, "release": 1.0 }, "connections": [] } ], "cables": [] }"""

    private fun buildDrumKitPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [ { "name": "VCV VCO", "params": { "fundamental": 440, "saw_sync": 0, "pulse_width": 0.5, "sync_input": 0 }, "connections": [] } ], "cables": [] }"""

    private fun buildDefaultPatch(): String =
        """{ "metadata": { "author": "voice-input", "date": "" }, "modules": [], "cables": [] }"""

    companion object {
        fun create(): VoicePatchGenerator = VoicePatchGenerator()
    }
}
