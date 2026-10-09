package com.gios.brightfantasylite.data

import com.gios.brightfantasylite.model.RosterSlot

/**
 * Start/sit rules, checked on the phone before anything is sent to ESPN.
 *
 * Lifted verbatim from BrightFantasy's `FantasyViewModel.swapProblem` and
 * `FantasyRepository.swapMoves`, moved out of the view model so they can be unit tested
 * without one. ESPN does refuse an illegal lineup on its own, but its refusal is a 400
 * about lineup slot ids; the honest version ("Josh Allen can't play FLEX") is free here.
 */
object Lineup {

    data class Move(val playerId: Int, val fromSlotId: Int, val toSlotId: Int)

    /** Null when the swap is legal, otherwise the reason in plain words. */
    fun swapProblem(a: RosterSlot, b: RosterSlot): String? = when {
        a.playerId == b.playerId -> "Pick two different players."
        a.lineupLocked || b.lineupLocked ->
            "${if (a.lineupLocked) a.name else b.name} is locked — that game has started."
        // Bench and IR slots take anyone, so only a move *into* a starting slot is
        // checked against eligibility.
        Espn.isStarting(b.slotId) && b.slotId !in a.eligibleSlots ->
            "${a.name} can't play ${Espn.slotName(b.slotId)}."
        Espn.isStarting(a.slotId) && a.slotId !in b.eligibleSlots ->
            "${b.name} can't play ${Espn.slotName(a.slotId)}."
        else -> null
    }

    /**
     * The two moves a swap is: each player takes the other's slot. One transaction with
     * two items, not two transactions, or the first lands on a full lineup and is refused.
     */
    fun swapMoves(a: RosterSlot, b: RosterSlot): List<Move> = listOf(
        Move(a.playerId, a.slotId, b.slotId),
        Move(b.playerId, b.slotId, a.slotId),
    )

    /**
     * Past weeks are read-only. The week ESPN is scoring, and any later one, can still
     * be set (players already kicked off are caught by [swapProblem]'s lock check).
     */
    fun editable(week: Int, currentScoringPeriod: Int): Boolean = week >= currentScoringPeriod

    /** The ESPN transaction body for a lineup change. */
    fun lineupBody(teamId: Int, memberId: String, week: Int, moves: List<Move>): String {
        val items = moves.joinToString(",") { move ->
            "{\"playerId\":${move.playerId},\"type\":\"LINEUP\"," +
                "\"fromLineupSlotId\":${move.fromSlotId}," +
                "\"toLineupSlotId\":${move.toSlotId}}"
        }
        return "{" +
            "\"isLeagueManager\":false," +
            "\"teamId\":$teamId," +
            "\"type\":\"${Espn.TYPE_LINEUP}\"," +
            "\"memberId\":\"$memberId\"," +
            "\"scoringPeriodId\":$week," +
            "\"executionType\":\"EXECUTE\"," +
            "\"items\":[$items]" +
            "}"
    }

    /** The ESPN transaction body for an add, with an optional drop in the same transaction. */
    fun addDropBody(
        teamId: Int,
        memberId: String,
        week: Int,
        addPlayerId: Int,
        dropPlayerId: Int?,
        onWaivers: Boolean,
        bid: Int = 0,
    ): String {
        val items = buildList {
            add(
                "{\"playerId\":$addPlayerId,\"type\":\"ADD\",\"toTeamId\":$teamId," +
                    "\"fromTeamId\":0,\"toLineupSlotId\":${Espn.SLOT_BENCH}}",
            )
            if (dropPlayerId != null) {
                add(
                    "{\"playerId\":$dropPlayerId,\"type\":\"DROP\",\"fromTeamId\":$teamId," +
                        "\"toTeamId\":0,\"fromLineupSlotId\":${Espn.SLOT_BENCH}}",
                )
            }
        }.joinToString(",")
        val type = if (onWaivers) Espn.TYPE_WAIVER else Espn.TYPE_FREEAGENT
        return "{" +
            "\"isLeagueManager\":false," +
            "\"teamId\":$teamId," +
            "\"type\":\"$type\"," +
            "\"memberId\":\"$memberId\"," +
            "\"scoringPeriodId\":$week," +
            "\"executionType\":\"EXECUTE\"," +
            (if (onWaivers) "\"bidAmount\":$bid," else "") +
            "\"items\":[$items]" +
            "}"
    }

    /**
     * Which team is yours: a team's `owners` are member GUIDs in the SWID's own format.
     * Case-insensitive, because ESPN is inconsistent about it between cookie and payload.
     */
    fun findMyTeam(owners: Map<Int, List<String>>, swid: String): Int? {
        val wanted = swid.trim()
        if (wanted.isEmpty()) return null
        return owners.entries.firstOrNull { (_, list) -> list.any { it.equals(wanted, ignoreCase = true) } }?.key
    }
}
