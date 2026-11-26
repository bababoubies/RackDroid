package org.rackdroid

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.Locale

/**
 * Voice input for the rack -- milestone 1: speech to text.
 *
 * A mic button floats over the bottom-right corner of the rack. Tap it,
 * speak, and what the device heard comes back in a glass card you can
 * select or copy anywhere.
 *
 * Why a floating button and not a toolbar icon: the tool row is a fixed
 * 8-column grid of exactly sixteen buttons (addMidiButton), two full rows.
 * A seventeenth needs a third row and reshapes the whole card, which is a
 * far larger change than this feature warrants.
 *
 * Why an Application subclass does the attaching: MainActivity is 142 KB
 * of programmatically built UI -- the toolbar, palette, menus and dialogs are all constructed in code,
 * and there is no layout XML to hang a new control on. Every edit to that
 * file is a large diff against a file this project could not test from here.
 *
 * The recogniser is the platform's own (SpeechRecognizer), not a bundled
 * model and not a network call of ours. That matters for this app: its
 * whole privacy story is that it talks to nobody. The device decides where
 * the audio goes -- on many phones the platform service is on-device; on
 * some it is the assistant's cloud. Like microphone access itself, it is
 * the user's setting to make; this class only reports when that choice
 * needs a network the phone does not have.
 */

class VoicePatchGenerator private constructor(private val templates: String) {

    private val templateMap = mapOf(
        "vco" to "${templates}vcv_vco",
        "vcf" to "${templates}vcv_vcf",
        "vca" to "${templates}vcv_vca",
        "lfo" to "${templates}vcv_lfo",
        "adsr" to "${templates}vcv_adsr",
        "sample_player" to "${templates}vcv_sample_player"
    )

    /** Returns the JSON patch spec for the given command phrase. */
    fun generateSpec(command: String): String {
        val lower = command.lower()
        // Very simple keyword matching for MVP (3-8 words)
        return when {
            lower.contains("add a vco") -> generateVCO(split(lower))
            lower.contains("make a bassline") -> generateBassline(split(lower))
            lower.contains("add an lfo") -> generateLFO(split(lower))
            lower.contains("drum kit patch") -> generateDrumKit()
            else -> generateDefault(split(lower))
        }
    }

    private fun split(text: String): List<String> = text.split(/\s+/)

    private fun generateVCO(params: Map<String, String>): String = """
      {
        "metadata": { "author": "voice-input", "date": "" },
        "modules": [{ "name": "VCV VCO", "params": { "fundamental": ${params["fundamental"]}, "sync_input": ${params["sync_input"]}, "pulse_width": ${params["pulse_width"]} }, "connections": [] }],
        "cables": []
      }
      """

    private fun generateBassline(params: Map<String, String>): String = """
      {
        "metadata": { "author": "voice-input", "date": "" },
        "modules": [
          { "name": "VCV VCO", "params": { "fundamental": ${params["fundamental"]}, "cutoff": ${params["cutoff"]}, "resonance": ${params["resonance"]}, "env_amount": ${params["env_amount"]} }, "connections": [{ "from": "module:VCO.fundamental", "to": "module:VCF.cutoff" }] },
          { "name": "VCV VCF", "params": { "cutoff": ${params["cutoff"]}, "resonance": ${params["resonance"]}, "env_amount": ${params["env_amount"]} }, "connections": [{ "from": "module:VCF.cutoff", "to": "module:VCA.gain" }] },
          { "name": "VCV VCA", "params": { "gain": ${params["gain"]}, "env_amount": ${params["env_amount"]} }, "connections": [{ "from": "module:VCA.gain", "to": "module:VCO.fundamental" }] },
          { "name": "VCV LFO", "params": { "frequency": ${params["frequency"]}, "type": "${params["type"]}, "delay": ${params["delay"]} }, "connections": [] },
          { "name": "VCV ADSR", "params": { "attack": ${params["attack"]}, "decay": ${params["decay"]}, "sustain": ${params["sustain"]}, "release": ${params["release"]} }, "connections": [] },
          { "name": "VCV Sample Player", "params": { "root_key": ${params["root_key"]}, "gain": ${params["gain"]}, "loop": ${params["loop"]} }, "connections": [] }
        ],
        "cables": [{ "from": "module:VCO.fundamental", "to": "module:VCF.cutoff" }, { "from": "module:VCF.cutoff", "to": "module:VCA.gain" }, { "from": "module:VCA.gain", "to": "module:VCO.fundamental" }]
      }
      """

    private fun generateLFO(params: Map<String, String>): String = """
      {
        "metadata": { "author": "voice-input", "date": "" },
        "modules": [{ "name": "VCV LFO", "params": { "frequency": ${params["frequency"]}, "type": ${params["type"]}, "delay": ${params["delay"]} }, "connections": [] }],
        "cables": []
      }
      """

    private fun generateDrumKit(): String = """
      {
        "metadata": { "author": "voice-input", "date": "" },
        "modules": [
          { "name": "VCV VCO", "params": { "fundamental": 440, "saw_sync": 0, "pulse_width": 0.5, "sync_input": 0 }, "connections": [] },
          { "name": "VCV VCF", "params": { "cutoff": 1000, "resonance": 0.5, "env_amount": 1.0 }, "connections": [] },
          { "name": "VCV VCA", "params": { "gain": 0.8, "env_amount": 1.0 }, "connections": [] },
          { "name": "VCV LFO", "params": { "frequency": 5.0, "type": "sine", "delay": 0.0 }, "connections": [] },
          { "name": "VCV ADSR", "params": { "attack": 0.01, "decay": 0.1, "sustain": 0.7, "release": 1.0 }, "connections": [] },
          { "name": "VCV Sample Player", "params": { "root_key": 60, "gain": 0.5, "loop": false }, "connections": [] }
        ],
        "cables": [{ "from": "module:VCO.fundamental", "to": "module:VCF.cutoff" }, { "from": "module:VCF.cutoff", "to": "module:VCA.gain" }, { "from": "module:VCA.gain", "to": "module:VCO.fundamental" }]
      }
      """

    private fun generateDefault(text: String): String = """
      {
        "metadata": { "author": "voice-input", "date": "" },
        "modules": [],
        "cables": []
      }
      """

    companion object {
        const val VCO = "vcv_vco"
        const val VCF = "vcv_vcf"
        const val VCA = "vcv_vca"
        const val LFO = "vcv_lfo"
        const val ADSR = "vcv_adsr"
        const val SAMPLE_PLAYER = "vcv_sample_player"

        @Volatile private var templates = mutableMapOf<String, String>()

        init {
            templates[VCO] = buildTemplate(templates.getOrElse(VCO, "{}"))
            templates[VCF] = buildTemplate(templates.getOrElse(VCF, "{}"))
            templates[VCA] = buildTemplate(templates.getOrElse(VCA, "{}"))
            templates[LFO] = buildTemplate(templates.getOrElse(LFO, "{}"))
            templates[ADSR] = buildTemplate(templates.getOrElse(ADSR, "{}"))
            templates[SAMPLE_PLAYER] = buildTemplate(templates.getOrElse(SAMPLE_PLAYER, "{}"))
        }

        private fun buildTemplate(tmpl: String): String = tmpl.replace("{module_type}", "") + "\n" + tmpl.replace("{default_params}", "{").replace("}", "},").replace("{connections}", "[]")

        private fun safeBuild(prefix: String, params: Map<String, String>, connections: Array<Pair<String, String>>): String = """
          {
            "metadata": { "author": "voice-input", "date": "" },
            "modules": [
              { "name": "${prefix} ${params["fundamental"] ?? ""}, "params": { "${params.keys.first()}": "${params.values.first()}" }, "connections": ${if (connections.isNotEmpty()) "[" + connections.join(", ") + "]" } },
              ...
            ]
          }
        """

        // Simplified per-module generation using template keys
        private fun generateSpecForCommand(cmd: String): String {
            val parts = split(cmd.lower())
            return when {
                cmd.contains("add a vco") -> generateVCO(parts)
                cmd.contains("make a bassline") -> generateBassline(parts)
                cmd.contains("add an lfo") -> generateLFO(parts)
                cmd.contains("drum kit patch") -> generateDrumKit()
                else -> generateDefault(parts)
            }
        }
    }
}

// Helper to parse "add a VCO" into params
fun split(text: String): List<String> = text.split(/\b\w+\b/)
