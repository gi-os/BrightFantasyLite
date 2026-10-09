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
import com.gios.brightfantasylite.data.Fantasy
import com.gios.brightfantasylite.model.LeagueState
import com.gios.brightfantasylite.model.Matchup
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LeagueViewModel(fantasy: Fantasy) : FantasyViewModel<Unit>(fantasy) {
    private val _scores = MutableStateFlow(false)
    val scores: StateFlow<Boolean> = _scores.asStateFlow()

    fun show(scores: Boolean) {
        _scores.value = scores
    }
}

/** Standings with the playoff line drawn, and the week's scoreboard. */
class LeagueScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LeagueViewModel>(sealedActivity) {

    private val fantasy = Fantasy.get(lightContext)

    override val viewModelClass: Class<LeagueViewModel> get() = LeagueViewModel::class.java

    override fun createViewModel() = LeagueViewModel(fantasy)

    @Composable
    override fun Content() {
        val league by fantasy.league.collectAsState()
        val scoreboard by fantasy.scoreboard.collectAsState()
        val week by fantasy.week.collectAsState()
        val busy by fantasy.busy.collectAsState()
        val loadedAt by fantasy.loadedAt.collectAsState()
        val scores by viewModel.scores.collectAsState()
        val modal by viewModel.modal.collectAsState()
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { viewModel.wheel.collect { scroll.animateScrollBy(it * MatchupScreen.WHEEL_STEP_PX) } }

        FantasySurface {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text(if (scores) "Scores" else "Standings"),
                    rightButton = LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::refreshNow),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val l = league
                    if (l == null) {
                        CenterMessage(if (busy) "Loading…" else "Nothing loaded yet.")
                    } else {
                        LightScrollView(
                            scrollState = scroll,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            StatusLine(busy, loadedAt, null)
                            if (scores) {
                                val played = l.weeksPlayed()
                                WeekBar(
                                    label = "WEEK $week",
                                    canGoBack = played.isEmpty() || week > played.first(),
                                    canGoForward = played.isEmpty() || week < played.last(),
                                    onStep = viewModel::stepWeek,
                                )
                                Rule()
                                Scoreboard(l, scoreboard, fantasy.teamId)
                            } else {
                                Standings(l, fantasy.teamId)
                            }
                            Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        }
                    }
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.Text("STANDINGS", onClick = { viewModel.show(false) }),
                        LightBarButton.Text("SCORES", onClick = { viewModel.show(true) }),
                    ),
                )
            }
            modal?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissModal) }
        }
    }
}

@Composable
private fun Standings(league: LeagueState, myTeamId: Int) {
    val rows = league.standings()
    LightText(
        text = "${league.info.scoringName} · top ${league.info.playoffTeams} make the playoffs",
        variant = LightTextVariant.Fine,
        lighten = true,
    )
    rows.forEachIndexed { i, team ->
        ValueRow(
            label = "${i + 1}. ${team.name}",
            value = team.record,
            sub = "${pts(team.pointsFor)} PF · ${pts(team.pointsAgainst)} PA · ${team.streak}",
            dim = team.id != myTeamId,
        )
        // The playoff cut is the one line in a standings table anyone looks for.
        if (i + 1 == league.info.playoffTeams && i + 1 < rows.size) {
            Rule()
            LightText(text = "PLAYOFF LINE", variant = LightTextVariant.Micro, lighten = true)
            Rule()
        }
    }
}

@Composable
private fun Scoreboard(league: LeagueState, matchups: List<Matchup>, myTeamId: Int) {
    if (matchups.isEmpty()) {
        LightText(text = "No games this week.", variant = LightTextVariant.Copy)
        return
    }
    matchups.forEach { m ->
        val home = league.team(m.home.teamId)
        val away = m.away?.let { league.team(it.teamId) }
        val mine = m.involves(myTeamId)
        ValueRow(label = home?.name ?: "Team ${m.home.teamId}", value = pts(m.home.points), dim = !mine)
        if (m.away != null) {
            ValueRow(label = away?.name ?: "Team ${m.away.teamId}", value = pts(m.away.points), dim = !mine)
        } else {
            LightText(text = "Bye", variant = LightTextVariant.Fine, lighten = true)
        }
        Rule()
    }
}
