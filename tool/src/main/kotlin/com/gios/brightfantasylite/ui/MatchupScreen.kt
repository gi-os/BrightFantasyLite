package com.gios.brightfantasylite.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.gios.brightfantasylite.data.Fantasy
import com.gios.brightfantasylite.data.MatchupDetail
import com.gios.brightfantasylite.data.Refresh
import com.gios.brightfantasylite.model.FantasyTeam
import com.gios.brightfantasylite.model.LeagueState
import com.gios.brightfantasylite.model.Roster
import com.gios.brightfantasylite.model.RosterSlot
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared by every screen that shows league data: refresh-on-open, the manual refresh
 * with its floor, week stepping, and the one modal.
 */
open class FantasyViewModel<R>(protected val fantasy: Fantasy) : WheelViewModel<R>() {

    private val _modal = MutableStateFlow<String?>(null)
    val modal: StateFlow<String?> = _modal.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<R>) {
        viewModelScope.launch { fantasy.refresh(force = false) }
    }

    fun refreshNow() {
        if (fantasy.busy.value) return
        if (!fantasy.manualRefreshAllowed()) {
            showModal("Updated ${Refresh.ago(fantasy.loadedAt.value, System.currentTimeMillis())}. Try again in a few seconds.")
            return
        }
        viewModelScope.launch { fantasy.refresh(force = true) }
    }

    fun stepWeek(delta: Int) {
        viewModelScope.launch { fantasy.stepWeek(delta) }
    }

    fun showModal(message: String) {
        _modal.value = message
    }

    fun dismissModal() {
        _modal.value = null
        fantasy.clearError()
    }
}

class MatchupViewModel(fantasy: Fantasy) : FantasyViewModel<Unit>(fantasy)

/**
 * The head-to-head: the screen the tool opens on. Both lineups side by side, slot by
 * slot, because the comparison people make is positional.
 */
@InitialScreen
class MatchupScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, MatchupViewModel>(sealedActivity) {

    private val fantasy = Fantasy.get(lightContext)

    override val viewModelClass: Class<MatchupViewModel> get() = MatchupViewModel::class.java

    override fun createViewModel() = MatchupViewModel(fantasy)

    @Composable
    override fun Content() {
        val config by fantasy.configState.collectAsState()
        val league by fantasy.league.collectAsState()
        val detail by fantasy.matchup.collectAsState()
        val week by fantasy.week.collectAsState()
        val busy by fantasy.busy.collectAsState()
        val error by fantasy.error.collectAsState()
        val needsLogin by fantasy.needsLogin.collectAsState()
        val loadedAt by fantasy.loadedAt.collectAsState()
        val modal by viewModel.modal.collectAsState()
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { viewModel.wheel.collect { scroll.animateScrollBy(it * WHEEL_STEP_PX) } }

        FantasySurface {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = if (config?.configured == true) {
                        LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::refreshNow)
                    } else {
                        null
                    },
                    center = LightTopBarCenter.Text(league?.info?.name?.take(18) ?: "Fantasy"),
                    rightButton = LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = { navigateTo({ SetupScreen(it) }) }),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val l = league
                    when {
                        config == null -> Unit
                        config?.configured != true ->
                            CenterMessage("Set up your ESPN league to see your matchup.")
                        l == null && busy -> CenterMessage("Loading…")
                        l == null -> CenterMessage(error ?: "Nothing loaded yet.")
                        else -> LightScrollView(
                            scrollState = scroll,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            StatusLine(busy, loadedAt, if (needsLogin) "ESPN refused the login. Make a fresh setup code." else null)
                            MatchupBody(l, detail, week, fantasy.teamId, viewModel::stepWeek) { slot ->
                                viewModel.showModal(playerSummary(slot))
                            }
                        }
                    }
                }
                if (config?.configured == true) {
                    LightBottomBar(
                        listOf(
                            LightBarButton.Text("MY TEAM", onClick = { navigateTo({ RosterScreen(it) }) }),
                            LightBarButton.Text("LEAGUE", onClick = { navigateTo({ LeagueScreen(it) }) }),
                            LightBarButton.Text("WIRE", onClick = { navigateTo({ WireScreen(it) }) }),
                        ),
                    )
                } else if (config != null) {
                    LightBottomBar(listOf(LightBarButton.Text("SET UP", onClick = { navigateTo({ SetupScreen(it) }) })))
                }
            }
            modal?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissModal) }
        }
    }

    companion object {
        const val WHEEL_STEP_PX = 180f
    }
}

/** "Updating…", or how old the numbers are, or why they are wrong. */
@Composable
fun StatusLine(busy: Boolean, loadedAt: Long, problem: String?) {
    val text = when {
        busy -> "Updating…"
        problem != null -> problem
        loadedAt > 0 -> "Updated ${Refresh.ago(loadedAt, System.currentTimeMillis())}"
        else -> ""
    }
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MatchupBody(
    league: LeagueState,
    detail: MatchupDetail?,
    week: Int,
    myTeamId: Int,
    onStep: (Int) -> Unit,
    onPlayer: (RosterSlot) -> Unit,
) {
    val played = league.weeksPlayed()
    val playoff = week > league.info.regularSeasonWeeks
    WeekBar(
        label = if (playoff) "WEEK $week · PLAYOFFS" else "WEEK $week",
        canGoBack = played.isEmpty() || week > played.first(),
        canGoForward = played.isEmpty() || week < played.last(),
        onStep = onStep,
    )
    Rule()
    if (detail == null) {
        LightText(
            text = "No matchup for ${league.team(myTeamId)?.name ?: "your team"} this week.",
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
        )
        return
    }
    val mine = detail.mine
    val theirs = detail.theirs
    ScoreHead(
        left = league.team(detail.teamId),
        leftPoints = mine?.points ?: 0.0,
        right = theirs?.let { league.team(it.teamId) },
        rightPoints = theirs?.points ?: 0.0,
        leftNote = sideNote(detail.myRoster),
        rightNote = sideNote(detail.theirRoster),
    )
    Rule()
    if (detail.matchup.isBye) {
        LightText(text = "Bye week. No opponent.", variant = LightTextVariant.Copy)
    }
    val myStarters = detail.myRoster?.starters.orEmpty()
    val theirStarters = detail.theirRoster?.starters.orEmpty()
    val rows = maxOf(myStarters.size, theirStarters.size)
    if (rows > 0) {
        SectionHeader("Starters")
        for (i in 0 until rows) {
            FaceOff(myStarters.getOrNull(i), theirStarters.getOrNull(i), onPlayer)
        }
    }
    val bench = detail.myRoster?.bench.orEmpty()
    if (bench.isNotEmpty()) {
        Rule()
        SectionHeader("Your bench")
        bench.forEach { slot ->
            ValueRow(
                label = slot.name,
                value = pts(slot.points),
                sub = "${slot.slotName} · ${slot.position} ${slot.proTeam}" + (slot.injuryTag?.let { " · $it" } ?: ""),
                dim = true,
                onClick = { onPlayer(slot) },
            )
        }
    }
    Spacer(Modifier.height(1f.gridUnitsAsDp()))
}

@Composable
private fun ScoreHead(
    left: FantasyTeam?,
    leftPoints: Double,
    right: FantasyTeam?,
    rightPoints: Double,
    leftNote: String?,
    rightNote: String?,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 0.4f.gridUnitsAsDp())) {
        TeamScore(left, leftPoints, leftNote, alignEnd = false, modifier = Modifier.weight(1f))
        TeamScore(right, rightPoints, rightNote, alignEnd = true, modifier = Modifier.weight(1f))
    }
    val margin = leftPoints - rightPoints
    if (right != null) {
        LightText(
            text = when {
                margin > 0 -> "Up ${pts(margin)}"
                margin < 0 -> "Down ${pts(-margin)}"
                else -> "Level"
            },
            variant = LightTextVariant.Detail,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TeamScore(team: FantasyTeam?, points: Double, note: String?, alignEnd: Boolean, modifier: Modifier) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        LightText(
            text = team?.name ?: "—",
            variant = LightTextVariant.Detail,
            align = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(text = pts(points), variant = LightTextVariant.Heading, align = align)
        LightText(
            text = listOfNotNull(team?.record, note).joinToString(" · "),
            variant = LightTextVariant.Fine,
            lighten = true,
            align = align,
            maxLines = 1,
        )
    }
}

/** "3 to play · proj 118.4": whether a lead means anything. */
private fun sideNote(roster: Roster?): String? {
    if (roster == null) return null
    val parts = mutableListOf<String>()
    val left = roster.yetToPlay
    if (left > 0) parts += "$left to play"
    parts += "proj ${pts(roster.startersProjected)}"
    return parts.joinToString(" · ")
}

@Composable
private fun FaceOff(left: RosterSlot?, right: RosterSlot?, onPlayer: (RosterSlot) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 0.3f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Half(left, alignEnd = false, modifier = Modifier.weight(1f), onPlayer = onPlayer)
        LightText(
            text = left?.slotName ?: right?.slotName ?: "",
            variant = LightTextVariant.Fine,
            lighten = true,
            align = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 0.3f.gridUnitsAsDp()),
        )
        Half(right, alignEnd = true, modifier = Modifier.weight(1f), onPlayer = onPlayer)
    }
}

@Composable
private fun Half(slot: RosterSlot?, alignEnd: Boolean, modifier: Modifier, onPlayer: (RosterSlot) -> Unit) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier.let { if (slot != null) it.lightClickable { onPlayer(slot) } else it },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        LightText(
            text = slot?.let { shortName(it.name) } ?: "—",
            variant = LightTextVariant.Detail,
            align = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = buildString {
                append(slot?.let { pts(it.points) } ?: "0.0")
                slot?.injuryTag?.let { append("  ").append(it) }
            },
            variant = LightTextVariant.Fine,
            // Grey while the real game is still to come or running, full once it is final.
            lighten = slot?.yetToPlay == true,
            align = align,
            maxLines = 1,
        )
    }
}

/** "J. Gibbs": a full name does not fit in half of a 3.9" screen. */
fun shortName(full: String): String {
    val parts = full.trim().split(' ').filter { it.isNotBlank() }
    if (parts.size < 2) return full
    // A team defense is "Broncos D/ST", where the first word is the one that identifies it.
    if (parts.last().contains("D/ST")) return parts.first()
    return "${parts.first().first()}. ${parts.drop(1).joinToString(" ")}"
}

/** The player's week, for the tap-a-player modal. */
fun playerSummary(slot: RosterSlot): String = buildString {
    append(slot.name).append('\n')
    append(slot.position).append(", ").append(slot.proTeam)
    slot.injuryTag?.let { append(" · ").append(it) }
    append("\n\n")
    append(pts(slot.points)).append(" pts, projected ").append(pts(slot.projected))
    if (slot.statLine.isNotEmpty()) {
        append("\n\n")
        append(slot.statLine.joinToString("\n") { (label, v) -> "$label  ${if (v % 1.0 == 0.0) v.toInt().toString() else pts(v)}" })
    }
    append("\n\nSeason ").append(pts(slot.seasonTotal)).append(", avg ").append(pts(slot.seasonAverage))
}
