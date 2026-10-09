package com.gios.brightfantasylite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.gios.brightfantasylite.data.Espn
import com.gios.brightfantasylite.data.Fantasy
import com.gios.brightfantasylite.data.Setup
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val leagueId: String = "",
    val swid: String = "",
    val espnS2: String = "",
    val season: String = "",
    val teamId: Int? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

class SetupViewModel(private val fantasy: Fantasy) : WheelViewModel<Boolean>() {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val c = fantasy.loadConfig()
            if (c.configured) {
                _state.update {
                    it.copy(
                        leagueId = c.leagueId,
                        swid = c.swid,
                        espnS2 = c.espnS2,
                        season = if (c.season > 0) c.season.toString() else "",
                        status = if (c.signedIn) "Private league, signed in." else "Public league, no cookies.",
                    )
                }
            }
        }
    }

    fun setLeague(v: String) = _state.update { it.copy(leagueId = v.trim(), teamId = null, error = null) }
    fun setSwid(v: String) = _state.update { it.copy(swid = v.trim(), error = null) }
    fun setS2(v: String) = _state.update { it.copy(espnS2 = v.trim(), error = null) }
    fun setSeason(v: String) = _state.update { it.copy(season = v.filter(Char::isDigit).take(4), error = null) }

    /**
     * Fills the fields from a scanned code. Accepts BrightFantasy's own
     * `brightfantasy://setup?...` code, the JSON form, an ESPN league URL, or a bare id.
     */
    fun applyQr(raw: String) {
        val p = Setup.parse(raw)
        if (p == null) {
            _state.update { it.copy(error = "That code isn't a BrightFantasy setup code or an ESPN league link.") }
            return
        }
        _state.update {
            it.copy(
                leagueId = p.leagueId ?: it.leagueId,
                swid = p.swid ?: it.swid,
                espnS2 = p.espnS2 ?: it.espnS2,
                season = p.season?.toString() ?: it.season,
                teamId = p.teamId ?: it.teamId,
                status = "Code read. Press SAVE to check it with ESPN.",
                error = null,
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, status = "Checking with ESPN…", error = null) }
        viewModelScope.launch {
            val problem = fantasy.applySetup(
                leagueId = s.leagueId,
                swid = s.swid,
                espnS2 = s.espnS2,
                season = s.season.toIntOrNull() ?: 0,
                teamId = s.teamId,
            )
            if (problem == null) {
                _state.update { it.copy(busy = false, status = "Saved.") }
                onDone()
            } else {
                _state.update { it.copy(busy = false, status = null, error = problem) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            fantasy.signOut()
            _state.value = SetupState(status = "League and cookies removed from this phone.")
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

/**
 * League id and the two ESPN cookies, typed or read off the QR code that BrightFantasy's
 * setup page draws. `espn_s2` is about 300 characters, so the QR is the sane way in.
 */
class SetupScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SetupViewModel>(sealedActivity) {

    private val fantasy = Fantasy.get(lightContext)

    override val viewModelClass: Class<SetupViewModel> get() = SetupViewModel::class.java

    override fun createViewModel() = SetupViewModel(fantasy)

    private fun <T> open(factory: (SealedLightActivity) -> SimpleLightScreen<T>, onResult: (T) -> Unit) =
        navigateTo(factory, onResult)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val league by fantasy.league.collectAsState()
        val config by fantasy.configState.collectAsState()

        FantasySurface {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text("Setup"),
                )
                LightScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "On a computer, open gi-os.github.io/BrightFantasy, fill in your league id and " +
                            "the SWID and espn_s2 cookies, and scan the code it draws. A public league needs only the id.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightTextField(
                        label = "League id",
                        value = state.leagueId,
                        placeholder = "105491",
                        onClick = { open({ TextEditorScreen(it, "League id", state.leagueId) }) { v -> viewModel.setLeague(v) } },
                    )
                    LightTextField(
                        label = "SWID cookie",
                        value = state.swid,
                        placeholder = "{XXXXXXXX-...}",
                        onClick = { open({ TextEditorScreen(it, "SWID", state.swid) }) { v -> viewModel.setSwid(v) } },
                    )
                    LightTextField(
                        label = "espn_s2 cookie",
                        value = if (state.espnS2.isEmpty()) "" else "${state.espnS2.take(6)}… (${state.espnS2.length} chars)",
                        placeholder = "long string",
                        onClick = { open({ TextEditorScreen(it, "espn_s2", state.espnS2) }) { v -> viewModel.setS2(v) } },
                    )
                    LightTextField(
                        label = "Season",
                        value = state.season,
                        placeholder = "${Espn.currentSeason()} (current)",
                        onClick = { open({ TextEditorScreen(it, "Season, blank for current", state.season) }) { v -> viewModel.setSeason(v) } },
                    )
                    val l = league
                    if (l != null && config?.configured == true && config?.leagueId == state.leagueId) {
                        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                        LightTextField(
                            label = "Your team",
                            value = l.team(fantasy.teamId)?.name ?: "pick one",
                            placeholder = "pick one",
                            onClick = { navigateTo({ TeamPickerScreen(it) }) },
                        )
                    }
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    state.status?.let { LightText(text = it, variant = LightTextVariant.Detail, lighten = true) }
                    if (config?.configured == true && !state.busy) {
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        LightText(
                            text = "Remove league and cookies",
                            variant = LightTextVariant.Detail,
                            underline = true,
                            modifier = Modifier.lightClickable { viewModel.signOut() },
                        )
                    }
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.Text("SCAN QR", onClick = { open({ QrScanScreen(it) }) { raw -> viewModel.applyQr(raw) } }),
                        LightBarButton.Text(if (state.busy) "…" else "SAVE", onClick = { viewModel.save { goBack(true) } }),
                    ),
                )
            }
            state.error?.let {
                Box(Modifier.fillMaxSize()) { LightFullscreenModal(message = it, onClose = viewModel::dismissError) }
            }
        }
    }
}

/** Which team is yours, for a public league (no SWID to match) or a shared login. */
class TeamPickerScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val fantasy = Fantasy.get(lightContext)

    @Composable
    override fun Content() {
        val league by fantasy.league.collectAsState()
        FantasySurface {
            Column(Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text("Your team"),
                )
                LightScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    league?.teams?.sortedBy { it.name.lowercase() }?.forEach { team ->
                        ValueRow(
                            label = team.name,
                            value = team.record,
                            sub = team.abbrev,
                            selected = team.id == fantasy.teamId,
                            onClick = {
                                // Loads the new team's week after the picker has closed.
                                fantasy.scope.launch { fantasy.pickTeam(team.id) }
                                goBack(Unit)
                            },
                        )
                    }
                }
            }
        }
    }
}
