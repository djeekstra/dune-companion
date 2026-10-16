# Extra-agent reminder — proposal only

No reminder is enabled. A returning turn is not enough evidence: a player may have acquired
Swordmaster or Mentat without recording it yet, or may simply be taking a reveal turn.

Candidate line to try aloud: **“Reveal, or extra agent?”**

Before implementation, agree on the trigger and test these cases:

- Two ordinary agents spent, no extra agent: remind once when that player next gets the turn.
- Swordmaster already tracked: allow the third agent without a premature reminder.
- Mentat already tracked: respect its extra agent, including a claim made during the turn.
- Unrecorded Swordmaster or Mentat: the question must not imply the player made a mistake.
- Already revealed, paused, undo, restored session and next round: avoid repeated or stale cues.

The reminder must never advance the turn, mark a reveal, or assign an extra agent. Decide
whether a generic question is useful enough, or whether the panel should offer a trial toggle
first. Device listening and a real table trial should precede committing the behavior.
