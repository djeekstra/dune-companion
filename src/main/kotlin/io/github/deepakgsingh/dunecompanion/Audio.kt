package io.github.deepakgsingh.dunecompanion

import java.io.File

// Per-file spec for sequence cues. boostMillibels is added via LoudnessEnhancer (0 = off).
// `alternatives` are tried in order when `name` is not installed, so a cue can prefer a
// specific recording and degrade to a general one — a player's name, then their colour.
data class ClipSpec(
    val name: String,
    val boostMillibels: Int = 0,
    val alternatives: List<String> = emptyList(),
    // Playback gain, 0f..1f. Only a bed sets this below 1f.
    val volume: Float = 1f,
    // What to say out loud when none of the candidates is installed. Recorded clips are
    // optional, and a player's name can never be recorded in advance at all. Null means "look the file name up in DuneSpeech".
    val speech: String? = null,
) {
    val candidates: List<String> get() = (listOf(name) + alternatives).distinct()
}

// Accepted clip extensions, in priority order.
val CLIP_EXTS = listOf("mp3", "m4a", "aac", "ogg", "wav", "flac")

// A ClipSpec may name an exact file or a base name whose extension is supplied when the audio
// is installed.
fun clipFile(dir: File, name: String): File? {
    val exact = File(dir, name)
    if (exact.exists()) return exact
    if (name.substringAfterLast('/', "").contains('.')) return null
    return CLIP_EXTS.map { File(dir, "$name.$it") }.firstOrNull { it.exists() }
}

/** A sequence, plus an optional track played underneath it. */
data class AudioSequence(val clips: List<ClipSpec>, val bed: ClipSpec? = null)
