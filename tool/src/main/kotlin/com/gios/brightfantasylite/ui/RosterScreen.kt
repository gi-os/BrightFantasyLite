package com.gios.brightfantasylite.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.gios.brightfantasylite.data.Fantasy
import com.gios.brightfantasylite.data.Lineup
import com.gios.brightfantasylite.model.RosterSlot
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RosterViewModel(fantasy: Fantasy) : FantasyViewModel<Unit>(fantasy) {

    /** The first player tapped, waiting for the second. */
    private val _picked = MutableStateFlow<RosterSlot?>(null)
    val picked: StateFlow<RosterSlot?> = _picked.asStateFlow()

    /** Why the last swap was refused, shown in place until the next tap. */
    private val _refused = MutableStateFlow<String?>(null)
    val refused: StateFlow<String?> = _refused.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * Tap one player, then the one he swaps with. Tapping the same player again cancels.
     * The legality check runs here, before anything is sent, and an illegal swap is
     * refused with the reason.
     */
    fun tap(slot: RosterSlot) {
        if (_saving.value) return
        val first = _picked.value
        when {
            first == null -> {
                _picked.value = slot
                _refused.value = null
            }
            first.playerId == slot.playerId -> {
                _picked.value = null
                _refused.value = null
            }
            else -> {
                _picked.value = null
                val problem = Lineup.swapProblem(first, slot)
                if (problem != null) {
                    _refused.value = problem
                    return
                }
                _refused.value = null
                _saving.value = true
                viewModelScope.launch {
                    val failure = fantasy.swap(first, slot)
                    _saving.value = false
                    if (failure != null) showModal("Lineup not saved.\n\n$failure") else _refused.value = "Lineup saved."
                }
            }
        }
    }
}

/**
 * Your team, and the one place a lineup changes. Start/sit is tap one, then tap the
 * other: no drag and drop on a 3.9" screen, and it works from the wheel too.
 */
class RosterScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, RosterViewModel>(sealedActivity) {

    private val fantasy = Fantasy.get(lightContext)

    override val viewModelClass: Class<RosterViewModel> get() = RosterViewModel::class.java

    override fun createViewModel() = RosterViewModel(fantasy)

    @Composable
    override fun Content() {
        val league by fantasy.league.collectAsState()
        val roster by fantasy.roster.collectAsState()
        val week by fantasy.week.collectAsState()
        val busy by fantasy.busy.collectAsState()
        val loadedAt by fantasy.loadedAt.collectAsState()
        val picked by viewModel.picked.collectAsState()
        val refused by viewModel.refused.collectAsState()
        val saving by viewModel.saving.collectAsState()
        val modal by viewModel.modal.collectAsState()
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { viewModel.wheel.collect { scroll.animateScrollBy(it * MatchupScreen.WHEEL_STEP_PX) } }

        FantasySurface {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text(league?.team(fantasy.teamId)?.abbrev ?: "My team"),
                    rightButton = LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::refreshNow),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val l = league
                    val r = roster
                    if (l == null || r == null) {
                        CenterMessage(if (busy) "Loading…" else "No roster for this week.")
                    } else {
                        val editable = Lineup.editable(week, l.info.scoringPeriodId)
                        val played = l.weeksPlayed()
                        val optimal = r.optimalPoints(l.info.lineupSlots)
                        LightScrollView(
                            scrollState = scroll,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            StatusLine(busy || saving, loadedAt, null)
                            WeekBar(
                                label = "WEEK $week",
                                canGoBack = played.isEmpty() || week > played.first(),
                                canGoForward = played.isEmpty() || week < played.last(),
                                onStep = viewModel::stepWeek,
                            )
                            Rule()
                            StatStrip(
                                listOf(
                                    "Started" to pts(r.startersPoints),
                                    "Best" to pts(optimal),
                                    "On bench" to pts((optimal - r.startersPoints).coerceAtLeast(0.0)),
                                ),
                            )
                            Rule()
                            picked?.let { Banner("Now tap who ${it.name.split(' ').last()} swaps with.") }
                            refused?.let { Banner(it) }
                            SectionHeader("Starters")
                            r.starters.forEach { slot ->
                                SlotRow(slot, selected = picked?.playerId == slot.playerId) {
                                    if (editable) viewModel.tap(slot) else viewModel.showModal(playerSummary(slot))
                                }
                            }
                            Rule()
                            SectionHeader("Bench")
                            r.bench.forEach { slot ->
                                SlotRow(slot, selected = picked?.playerId == slot.playerId) {
                                    if (editable) viewModel.tap(slot) else viewModel.showModal(playerSummary(slot))
                                }
                            }
                            Rule()
                            LightText(
                                text = if (editable) {
                                    "Tap a player, then tap who he swaps with. Tap him again to cancel."
                                } else {
                                    "Past weeks are read-only. Tap a player for his week."
                                },
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                align = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        }
                    }
                }
            }
            modal?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissModal) }
        }
    }
}

@Composable
private fun SlotRow(slot: RosterSlot, selected: Boolean, onClick: () -> Unit) {
    val sub = buildString {
        append(slot.slotName).append(" · ").append(slot.position).append(' ').append(slot.proTeam)
        slot.injuryTag?.let { append(" · ").append(it) }
        if (slot.lineupLocked) append(" · locked")
        append(" · proj ").append(pts(slot.projected))
    }
    ValueRow(
        label = slot.name,
        value = pts(slot.points),
        sub = sub,
        dim = !slot.starting,
        selected = selected,
        onClick = onClick,
    )
}
