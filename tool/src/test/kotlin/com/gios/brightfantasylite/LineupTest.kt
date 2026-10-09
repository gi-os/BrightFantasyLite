package com.gios.brightfantasylite

import com.gios.brightfantasylite.data.Espn
import com.gios.brightfantasylite.data.JObj
import com.gios.brightfantasylite.data.Lineup
import com.gios.brightfantasylite.model.RosterSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The start/sit rules that run before anything goes to ESPN. */
class LineupTest {

    private fun slot(
        id: Int,
        name: String,
        slotId: Int,
        eligible: List<Int>,
        locked: Boolean = false,
    ) = RosterSlot(
        playerId = id, name = name, slotId = slotId, positionId = 1, proTeamId = 2,
        eligibleSlots = eligible, points = 0.0, projected = 0.0, seasonTotal = 0.0,
        seasonAverage = 0.0, injuryStatus = "", percentOwned = 0.0, positionalRank = 0,
        lineupLocked = locked, statLine = emptyList(), kickoffMillis = 0L,
        opponentProTeamId = 0, gameFinished = true,
    )

    // QB: QB, OP, bench, IR. RB: RB, RB/WR, FLEX, bench, IR.
    private val qb = slot(1, "Josh Allen", 0, listOf(0, 7, 20, 21))
    private val benchQb = slot(2, "Backup Qb", Espn.SLOT_BENCH, listOf(0, 7, 20, 21))
    private val flexRb = slot(3, "Flex Back", 23, listOf(2, 3, 23, 20, 21))
    private val benchRb = slot(4, "Bench Back", Espn.SLOT_BENCH, listOf(2, 3, 23, 20, 21))
    private val irRb = slot(5, "Hurt Back", Espn.SLOT_IR, listOf(2, 3, 23, 20, 21))

    @Test
    fun `a like-for-like swap is legal`() {
        assertNull(Lineup.swapProblem(qb, benchQb))
        assertNull(Lineup.swapProblem(benchRb, flexRb))
    }

    @Test
    fun `the same player twice is refused`() {
        assertEquals("Pick two different players.", Lineup.swapProblem(qb, qb))
    }

    @Test
    fun `a quarterback cannot go into the flex`() {
        assertEquals("Josh Allen can't play FLEX.", Lineup.swapProblem(qb, flexRb))
        assertEquals("Flex Back can't play QB.", Lineup.swapProblem(flexRb, qb))
    }

    @Test
    fun `a bench-to-bench move needs no eligibility`() {
        assertNull(Lineup.swapProblem(benchQb, benchRb))
        assertNull(Lineup.swapProblem(benchRb, irRb))
    }

    @Test
    fun `a running back cannot fill a bench quarterback's starting spot in reverse`() {
        // Bench RB into the QB slot: the RB is the one who can't play QB.
        assertEquals("Bench Back can't play QB.", Lineup.swapProblem(benchRb, qb))
    }

    @Test
    fun `a locked player names himself`() {
        val lockedQb = qb.copy(lineupLocked = true)
        assertEquals(
            "Josh Allen is locked — that game has started.",
            Lineup.swapProblem(benchQb, lockedQb),
        )
    }

    @Test
    fun `a swap is two moves in one transaction`() {
        val moves = Lineup.swapMoves(benchRb, flexRb)
        assertEquals(
            listOf(Lineup.Move(4, 20, 23), Lineup.Move(3, 23, 20)),
            moves,
        )
        val body = JObj.parse(Lineup.lineupBody(7, "{ABC}", 5, moves))
        assertEquals("ROSTER", body.optString("type"))
        assertEquals(7, body.optInt("teamId"))
        assertEquals(5, body.optInt("scoringPeriodId"))
        assertEquals("{ABC}", body.optString("memberId"))
        val items = body.optJSONArray("items")!!
        assertEquals(2, items.length())
        assertEquals(23, items.optJSONObject(0)!!.optInt("toLineupSlotId"))
    }

    @Test
    fun `add and drop ride in one transaction`() {
        val claim = JObj.parse(Lineup.addDropBody(7, "{ABC}", 5, 100, 4, onWaivers = true, bid = 3))
        assertEquals("WAIVER", claim.optString("type"))
        assertEquals(3, claim.optInt("bidAmount"))
        assertEquals(2, claim.optJSONArray("items")!!.length())

        val pickup = JObj.parse(Lineup.addDropBody(7, "{ABC}", 5, 100, null, onWaivers = false))
        assertEquals("FREEAGENT", pickup.optString("type"))
        assertFalse(pickup.has("bidAmount"))
        assertEquals(1, pickup.optJSONArray("items")!!.length())
    }

    @Test
    fun `past weeks are read-only`() {
        assertFalse(Lineup.editable(4, 5))
        assertTrue(Lineup.editable(5, 5))
        assertTrue(Lineup.editable(6, 5))
    }

    @Test
    fun `your team is found from the swid regardless of case`() {
        val owners = mapOf(1 to listOf("{AAA}"), 2 to listOf("{bbb-ccc}"))
        assertEquals(2, Lineup.findMyTeam(owners, "{BBB-CCC}"))
        assertNull(Lineup.findMyTeam(owners, ""))
        assertNull(Lineup.findMyTeam(owners, "{ZZZ}"))
    }
}
