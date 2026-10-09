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
import com.gios.brightfantasylite.data.Espn
import com.gios.brightfantasylite.data.Fantasy
import com.gios.brightfantasylite.model.FreeAgent
import com.gios.brightfantasylite.model.RosterSlot
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The position filters, in the order a wire is actually searched. From BrightFantasy. */
private val FILTERS: List<Pair<String, List<Int>>> = listOf(
    "ALL" to Espn.SEARCHABLE_SLOTS,
    "QB" to listOf(0),
    "RB" to listOf(2),
    "WR" to listOf(4),
    "TE" to listOf(6),
    "FLEX" to listOf(2, 4, 6),
    "D/ST" to listOf(16),
    "K" to listOf(17),
)

data class WireState(
    val filter: Int = 0,
    val loading: Boolean = false,
    val agents: List<FreeAgent> = emptyList(),
    val error: String? = null,
)

/**
 * The wire loads when you open it and when you change the filter or the sort. There is
 * no paging past the first fifty: the wire is for finding a pickup, not for browsing.
 */
class WireViewModel(private val fantasy: Fantasy) : WheelViewModel<Unit>() {
    private val _state = MutableStateFlow(WireState())
    val state: StateFlow<WireState> = _state.asStateFlow()
    val byProjection: StateFlow<Boolean> = fantasy.byProjection

    private val _modal = MutableStateFlow<String?>(null)
    val modal: StateFlow<String?> = _modal.asStateFlow()

    private var loaded = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (!loaded) load()
    }

    fun nextFilter() {
        _state.value = _state.value.copy(filter = (_state.value.filter + 1) % FILTERS.size)
        load()
    }

    fun toggleSort() {
        viewModelScope.launch {
            fantasy.setByProjection(!fantasy.byProjection.value)
            load()
        }
    }

    fun load() {
        if (_state.value.loading) return
        loaded = true
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            fantasy.loadConfig()
            val result = fantasy.freeAgents(FILTERS[_state.value.filter].second, onWaivers = null)
            _state.value = when (result) {
                is Fantasy.Outcome.Ok -> _state.value.copy(loading = false, agents = result.value)
                is Fantasy.Outcome.Failed -> _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    fun showModal(message: String) {
        _modal.value = message
    }

    fun dismissModal() {
        _modal.value = null
    }
}

class WireScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, WireViewModel>(sealedActivity) {

    private val fantasy = Fantasy.get(lightContext)

    override val viewModelClass: Class<WireViewModel> get() = WireViewModel::class.java

    override fun createViewModel() = WireViewModel(fantasy)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val byProjection by viewModel.byProjection.collectAsState()
        val modal by viewModel.modal.collectAsState()
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { viewModel.wheel.collect { scroll.animateScrollBy(it * MatchupScreen.WHEEL_STEP_PX) } }

        FantasySurface {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text("Wire · ${FILTERS[state.filter].first}"),
                    rightButton = LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::load),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading && state.agents.isEmpty() -> CenterMessage("Loading…")
                        state.error != null && state.agents.isEmpty() -> CenterMessage(state.error ?: "")
                        state.agents.isEmpty() -> CenterMessage("Nobody available at ${FILTERS[state.filter].first}.")
                        else -> LightScrollView(
                            scrollState = scroll,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = if (state.loading) "Updating…" else "Sorted by ${if (byProjection) "this week's projection" else "percent owned"}. W = on waivers.",
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                align = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            state.agents.forEach { agent ->
                                ValueRow(
                                    label = (if (agent.onWaivers) "W  " else "") + agent.name,
                                    value = pts(agent.projected),
                                    sub = buildString {
                                        append(agent.position).append(' ').append(agent.proTeam)
                                        agent.injuryTag()?.let { append(" · ").append(it) }
                                        append(" · ").append(pts(agent.percentOwned)).append("% owned")
                                        append(" · last wk ").append(pts(agent.lastWeek))
                                    },
                                    onClick = {
                                        navigateTo({ AddDropScreen(it, agent) }) { done ->
                                            if (done == true) {
                                                viewModel.showModal(if (agent.onWaivers) "Claim submitted for ${agent.name}." else "${agent.name} added.")
                                                viewModel.load()
                                            }
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        }
                    }
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.Text(FILTERS[(state.filter + 1) % FILTERS.size].first, onClick = viewModel::nextFilter),
                        LightBarButton.Text(if (byProjection) "BY OWNED" else "BY PROJ", onClick = viewModel::toggleSort),
                    ),
                )
            }
            modal?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissModal) }
        }
    }
}

private fun FreeAgent.injuryTag(): String? = when (injuryStatus.uppercase()) {
    "ACTIVE", "NORMAL", "" -> null
    "QUESTIONABLE" -> "Q"
    "DOUBTFUL" -> "D"
    "OUT" -> "O"
    "INJURY_RESERVE" -> "IR"
    else -> injuryStatus.take(3)
}

class AddDropViewModel(private val fantasy: Fantasy) : LightViewModel<Boolean>() {
    private val _drop = MutableStateFlow<RosterSlot?>(null)
    val drop: StateFlow<RosterSlot?> = _drop.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun choose(slot: RosterSlot?) {
        _drop.value = if (_drop.value?.playerId == slot?.playerId) null else slot
    }

    fun submit(agent: FreeAgent, onDone: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val failure = fantasy.addDrop(agent, _drop.value)
            _saving.value = false
            if (failure == null) onDone() else _error.value = "Nothing changed.\n\n$failure"
        }
    }

    fun dismissError() {
        _error.value = null
    }
}

/**
 * Picks who goes when a player comes in. The add and the drop go in one transaction:
 * a full roster refuses an add on its own, and dropping first then failing to add is the
 * worst of the outcomes.
 */
class AddDropScreen(sealedActivity: SealedLightActivity, private val agent: FreeAgent) :
    LightScreen<Boolean, AddDropViewModel>(sealedActivity) {

    private val fantasy = Fantasy.get(lightContext)

    override val viewModelClass: Class<AddDropViewModel> get() = AddDropViewModel::class.java

    override fun createViewModel() = AddDropViewModel(fantasy)

    @Composable
    override fun Content() {
        val roster by fantasy.roster.collectAsState()
        val drop by viewModel.drop.collectAsState()
        val saving by viewModel.saving.collectAsState()
        val error by viewModel.error.collectAsState()
        val verb = if (agent.onWaivers) "Claim" else "Add"

        FantasySurface {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text("$verb ${shortName(agent.name)}"),
                )
                LightScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "${agent.name}, ${agent.position} ${agent.proTeam}. Projected ${pts(agent.projected)}." +
                            if (agent.onWaivers) " On waivers: this is a claim, decided when waivers run." else "",
                        variant = LightTextVariant.Detail,
                    )
                    SectionHeader("Drop")
                    ValueRow(
                        label = "Nobody, I have room",
                        value = "",
                        selected = drop == null,
                        onClick = { viewModel.choose(null) },
                    )
                    roster?.let { r ->
                        (r.starters + r.bench).forEach { slot ->
                            ValueRow(
                                label = slot.name,
                                value = pts(slot.seasonTotal),
                                sub = "${slot.slotName} · ${slot.position} ${slot.proTeam}" + (slot.injuryTag?.let { " · $it" } ?: ""),
                                selected = drop?.playerId == slot.playerId,
                                onClick = { viewModel.choose(slot) },
                            )
                        }
                    }
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.Text(
                            when {
                                saving -> "…"
                                drop != null -> "${verb.uppercase()} + DROP"
                                else -> verb.uppercase()
                            },
                            onClick = { viewModel.submit(agent) { goBack(true) } },
                        ),
                    ),
                )
            }
            error?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissError) }
        }
    }
}
