package io.github.deepakgsingh.dunecompanion

/**
 * What to say for a clip that is not installed.
 *
 * The rules engine names every cue by a clip file, so a table that records a voice pack can
 * drop it into the clips directory and have it win. Everyone else
 * gets these lines through the phone's own text-to-speech.
 *
 * Null means "no spoken form": either a sound effect ([duneTone] covers those) or a name this
 * table does not recognise, which is a silent no-op.
 */
fun duneSpeechFor(clipName: String): String? {
    val base = clipName.substringBeforeLast('.')
    FIXED_LINES[base]?.let { return it }

    Regex("dune_conflict_(\\d+)").matchEntire(base)?.let {
        val round = it.groupValues[1].toInt()
        // Said flat, like the nine before it: the round is news, the finality needs no shout.
        return if (round == 10) "Final round." else "Round $round."
    }
    Regex("dune_phrase_points_(\\d+)").matchEntire(base)?.let {
        val points = it.groupValues[1].toInt()
        return if (points == 1) "has one point." else "has $points points."
    }
    Regex("dune_game_hour_(\\d)").matchEntire(base)?.let {
        val hours = it.groupValues[1].toInt()
        return if (hours == 1) "One hour played." else "$hours hours played."
    }
    colourLine(base, "dune_winner_")?.let { return "$it wins!" }
    colourLine(base, "dune_third_agent_")?.let { return "$it takes the swordmaster." }
    colourLine(base, "dune_mentat_")?.let { return "$it takes the mentat." }
    colourLine(base, "dune_high_council_")?.let { return "$it joins the high council." }
    // Only reached when the ClipSpec carried no typed name, which means the seat was unnamed.
    colourLine(base, "dune_name_")?.let { return it }
    return null
}

private fun colourLine(base: String, prefix: String): String? =
    base.removePrefix(prefix).takeIf { base.startsWith(prefix) }
        ?.let(DunePlayerColor::fromWireName)
        ?.displayName
        ?.lowercase()
        ?.replaceFirstChar { it.uppercase() }

private val FIXED_LINES = mapOf(
    "dune_phrase_turn" to "has the turn.",
    "dune_phrase_already_revealed" to "has already revealed.",
    "dune_phrase_first_player" to "is first player.",
    "dune_phrase_turn_restored" to "still has the turn.",
    "dune_phase_combat" to "Combat!",
    "dune_collect_rewards" to "Collect your rewards.",
    "dune_phase_makers" to "Makers.",
    "dune_phase_recall" to "Recall.",
    "dune_timer_five" to "Five minutes.",
    "dune_timer_seven" to "Seven minutes.",
    "dune_timer_ten" to "Ten minutes.",
    "dune_timer_paused" to "Timer paused.",
    "dune_game_resumed" to "Game resumed.",
    "dune_game_another_hour" to "Another hour played.",
    "dune_checkpoint_due_05" to "Halfway point. Update the scores.",
    "dune_checkpoint_due_09" to "One conflict to go. Update the scores.",
    "dune_checkpoint_due_10" to "Final conflict resolved. Enter the final scores.",
    "dune_standings_05" to "At the halfway point,",
    "dune_standings_09" to "With one conflict to go,",
    "dune_standings_final" to "Final scores.",
    "steward_speaker_test" to "Speaker check. The companion is listening.",
)

/** The three short sound-effect markers. Played as tones when no clip is installed. */
enum class DuneTone { TURN_END, REVEAL_END, REJECTED }

fun duneTone(clipName: String): DuneTone? = when (clipName.substringBeforeLast('.')) {
    DUNE_TURN_END_CUE -> DuneTone.TURN_END
    DUNE_REVEAL_END_CUE -> DuneTone.REVEAL_END
    DUNE_TURN_REJECTED_CUE -> DuneTone.REJECTED
    else -> null
}
