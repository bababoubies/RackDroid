# Voice-to-Patch Feature Specification

**Repository:** `bababoubies/RackDroid`  
**Feature:** Voice-controlled patch synthesis for Rack VCV  
**Status:** Draft — pending implementation  

---

## 1. Overview

Enable users to speak natural-language requests (e.g., "make a bassline lead", "add a VCO with pitch CV", "create a drum kit patch") and have the app automatically generate a valid VCV Rack patch (`*.vcv`) and load it into the synthesizer.

**Goal:** Reduce friction from "I hear this sound in my head but can't build the patch quickly" → speak it → patch loads.

**Scope (MVP):**
- Single-language short commands (3–8 words)
- Pre-defined module families: VCO, VCF, VCA, LFO, Envelope, Sample Player
- Generated patches: 4–6 modules max, simple cabling
- Load via existing import intent (no UI overhaul)

---

## 2. Voice Input Layer

### 2.1. STT Integration
- Use Android `SpeechRecognizer` (on-device, no network required for basic commands)
- Add a **Mic button** in the Glass Toolbar (right side, beside patch actions)
- Permissions: `RECORD_AUDIO` (runtime, added to `AndroidManifest.xml`)
- Button click → `startListening()` → result → parse intent

### 2.2. Command Phrase Format
| Phrase Type | Example | Expected Output |
|---|---|---|
| VCO add | "add a VCO" | `module: VCO, params: { fundamental: 440 }` |
| Bassline | "make a bassline" | `modules: [VCO, VCF, VCA, ADSR], cabling: [VCO→VCF, VCF→VCA, ADSR→VCA]` |
| LFO add | "add an LFO" | `module: LFO, params: { frequency: 5, type: sine }` |
| Drum kit | "drum kit patch" | Pre-built 3-module drum template |

### 2.3. Backend (Optional LLM)
- If the rule-based mapping can't express the intent, fall back to a local LLM (e.g., Gemma 3n on-device) or an API call to generate the patch JSON.
- Guardrail: always validate generated JSON against the VCV patch schema before loading.

---

## 3. Patch Generation (spec → .vcv JSON)

### 3.1. Module Data Structure
Each module in the generated patch has:
- `name`: string (matches Rack module identifiers, e.g. `VCV VCO`, `VCV VCF`)
- `params`: object of parameter name → value
- `connections`: array of `{ from: "module_id:param", to: "module_id:param" }`

### 3.2. Template Library (JSON-based)
Store module templates in `assets/patch_templates.json`:
```json
{
  "vcv_vco": {
    "module_type": "VCV VCO",
    "default_params": { "fundamental": 440, "saw_sync: 0, pulse_width: 0.5 }",
    "connections": []
  },
  "vcv_vcf": {
    "module_type": "VCV VCF",
    "default_params": { "cutoff: 1000, resonance: 0.5 }",
    "connections": []
  },
  ...
}
```

### 3.3. Patch JSON Schema (VCV-compatible)
Generated patches must conform to VCV's `*.vcv` JSON format:
```json
{
  "metadata": { "author": "voice-input", "date": "2026-01-01" },
  "modules": [ ... ],
  "cables": [ ... ]
}
```
- Reuse existing Rack 2.6.4 schema validation if possible.
- Output file saved to `Android/data/.../files/patches/voice_<timestamp>.vcv`.

---

## 4. App Integration

### 4.1. Import Intent (reuse existing)
VCV Rack Android already has `handleImportIntent()` in `MainActivity.kt`. After generating the `.vcv` file:
```kotlin
// Write file to external/public dir, then:
val intent = Intent(Intent.ACTION_SEND)
intent.setType("application/vnd.ucv")
// ... fill intent data/extra ...
startActivity(intent) // or use FileProvider
```

### 4.2. In-App Load (optional)
If we want to avoid the system intent, we can call the native loader directly:
```kotlin
// From Kotlin, bridge to native:
com.rackdroid.native.PatchLoader.loadPatchFromAssets("voice_bass.vcv")
```
This would require a small JNI stub or exposing a Kt-native bridge.

### 4.3. UI Feedback
- Mic button: listening (pulsing) → processing (spinner) → success (checkmark) → failure (toast)
- Show generated patch preview: module list + simple patch cable diagram (optional)

---

## 5. Implementation Milestones

| Milestone | Deliverable | Estimate |
|---|---|---|
| M1 | Mic button + STT integration → text result shown in Log | 1 day |
| M2 | Rule-based intent → module template mapping + .vcv JSON generator | 2 days |
| M3 | End-to-end: voice → text → patch → import intent loads in app | 2 days |
| M4 | Optional: LLM fallback for ambiguous commands | 2+ days |
| M5 | Polish: UI feedback, error handling, patch naming/preview | 1 day |

---

## 6. Open Questions / Decisions

1. **Mic button placement:** Toolbar right side vs. Module Palette floating button vs. dedicated "Voice" tab.
2. **Command language:** English only? Other languages later? Phrase structure freedom vs. strict keywords.
3. **Patch complexity limit:** 4 modules max for MVP, or allow user to say "add more modules" iteratively?
4. **LLM choice:** Local on-device (Gemma 3n, Phi-3 mini) vs. cloud API (OpenAI, Anthropic) vs. none (rule-based only).
5. **Patch persistence:** Save to `patches/` directory, user can reuse later? Or ephemeral only for this session?

---

## 7. Next Immediate Step

**Add the Mic button + basic STT** in `app/src/main/java/org/rackdroid/MainActivity.kt` (or a new `VoiceController.kt`). This is the smallest viable slice — gets voice input into the app, prints recognized text to Log. Once that's confirmed working, we layer on the patch generation.

**Success criteria for M1:**
- Mic button appears in the toolbar
- Tapping it starts listening
- After ~3 seconds, recognized text appears in Android Studio Logcat
- Button returns to idle state

---

*Document generated via voice-to-patch feature planning.*  
*Next: implement M1, verify, then iterate M2–M5.*