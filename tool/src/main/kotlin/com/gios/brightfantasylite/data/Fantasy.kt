package com.gios.brightfantasylite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gios.brightfantasylite.model.FreeAgent
import com.gios.brightfantasylite.model.LeagueState
import com.gios.brightfantasylite.model.Matchup
import com.gios.brightfantasylite.model.Roster
import com.gios.brightfantasylite.model.RosterSlot
import com.thelightphone.sdk.SealedLightContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** League id, season, which team is yours, and the two ESPN cookies. */
data class Config(
    val leagueId: String = "",
    /** 0 means "whatever the football calendar says". */
    val season: Int = 0,
    /** 0 until it has been worked out from the SWID or picked. */
    val teamId: Int = 0,
    val swid: String = "",
    val espnS2: String = "",
) {
    val configured: Boolean get() = leagueId.isNotBlank()
    val signedIn: Boolean get() = swid.isNotBlank() && espnS2.isNotBlank()
    fun effectiveSeason(): Int = if (season > 0) season else Espn.currentSeason()
}

/** One head-to-head with both lineups on it. */
data class MatchupDetail(
    val matchup: Matchup,
    val teamId: Int,
    val myRoster: Roster?,
    val theirRoster: Roster?,
) {
    val mine get() = matchup.sideFor(teamId)
    val theirs get() = matchup.opponentOf(teamId)
}

/**
 * Every request the tool makes, the rules about which one to make, and the state the
 * screens share. A process-wide singleton so the matchup, the roster and the league page
 * read one copy of "which week are we on" instead of three.
 *
 * The request plan is BrightFantasy's, measured on a twelve-team league: settings and
 * teams (61 KB), the schedule without a week (52 KB), and each roster by `forTeamId`
 * (94 KB) rather than the whole league's (1.1 MB). `mBoxscore` is never used.
 */
class Fantasy private constructor(ctx: SealedLightContext) {

    private val dataStore: DataStore<Preferences> = ctx.dataStore
    private val cacheDir: File = File(ctx.filesDir, "espn").apply { mkdirs() }
    private val lock = Mutex()

    /** For work that should finish after the screen that started it has closed. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var config = Config()
    private var configLoaded = false

    private val api = FantasyApi { FantasyApi.Credentials(config.swid, config.espnS2) }

    // ------------------------------------------------------------------ shared state

    private val _configState = MutableStateFlow<Config?>(null)
    /** Null until the stored settings have been read. */
    val configState: StateFlow<Config?> = _configState.asStateFlow()

    private val _league = MutableStateFlow<LeagueState?>(null)
    val league: StateFlow<LeagueState?> = _league.asStateFlow()

    private val _week = MutableStateFlow(0)
    val week: StateFlow<Int> = _week.asStateFlow()

    private val _matchup = MutableStateFlow<MatchupDetail?>(null)
    val matchup: StateFlow<MatchupDetail?> = _matchup.asStateFlow()

    private val _scoreboard = MutableStateFlow<List<Matchup>>(emptyList())
    val scoreboard: StateFlow<List<Matchup>> = _scoreboard.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The one line that says the screen is wrong, and why. Null when it isn't. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Set when ESPN refused the cookies, so the screens can point at setup. */
    private val _needsLogin = MutableStateFlow(false)
    val needsLogin: StateFlow<Boolean> = _needsLogin.asStateFlow()

    private val _loadedAt = MutableStateFlow(0L)
    val loadedAt: StateFlow<Long> = _loadedAt.asStateFlow()

    private val _byProjection = MutableStateFlow(true)
    val byProjection: StateFlow<Boolean> = _byProjection.asStateFlow()

    val teamId: Int get() = config.teamId
    val swid: String get() = config.swid

    fun clearError() {
        _error.value = null
    }

    // ------------------------------------------------------------------ settings

    suspend fun loadConfig(): Config {
        if (configLoaded) return config
        val prefs = dataStore.data.first()
        // A cookie that no longer decrypts (key lost on restore) reads as signed out.
        config = Config(
            leagueId = prefs[KEY_LEAGUE].orEmpty(),
            season = prefs[KEY_SEASON] ?: 0,
            teamId = prefs[KEY_TEAM] ?: 0,
            swid = prefs[KEY_SWID]?.let { CookieCipher.decrypt(it) }.orEmpty(),
            espnS2 = prefs[KEY_S2]?.let { CookieCipher.decrypt(it) }.orEmpty(),
        )
        _byProjection.value = prefs[KEY_BY_PROJECTION] ?: true
        configLoaded = true
        _configState.value = config
        return config
    }

    private suspend fun saveConfig(next: Config) {
        config = next
        _configState.value = next
        val encSwid = next.swid.takeIf { it.isNotBlank() }?.let { CookieCipher.encrypt(it) }
        val encS2 = next.espnS2.takeIf { it.isNotBlank() }?.let { CookieCipher.encrypt(it) }
        dataStore.edit {
            it[KEY_LEAGUE] = next.leagueId
            it[KEY_SEASON] = next.season
            it[KEY_TEAM] = next.teamId
            if (encSwid != null) it[KEY_SWID] = encSwid else it.remove(KEY_SWID)
            if (encS2 != null) it[KEY_S2] = encS2 else it.remove(KEY_S2)
        }
    }

    suspend fun setByProjection(value: Boolean) {
        _byProjection.value = value
        dataStore.edit { it[KEY_BY_PROJECTION] = value }
    }

    /**
     * Saves a new league and cookies, then proves them by loading the league. Returns
     * null when ESPN accepted them, otherwise ESPN's reason. A new league is a new team,
     * so the team id is reset unless the setup code carried one.
     */
    suspend fun applySetup(leagueId: String, swid: String, espnS2: String, season: Int, teamId: Int?): String? {
        val id = leagueId.trim()
        if (id.isEmpty() || !id.all { it.isDigit() }) return "Enter the league id: the number after leagueId= in ESPN's address bar."
        val cleanSwid = swid.trim().let { if (it.isEmpty()) "" else Setup.normaliseSwid(it) }
        val cleanS2 = Setup.decode(espnS2.trim())
        if (cleanSwid.isEmpty() != cleanS2.isEmpty()) return "A private league needs both cookies, SWID and espn_s2. A public one needs neither."
        val sameLeague = id == config.leagueId
        val keepTeam = teamId ?: if (sameLeague) config.teamId else 0
        if (!sameLeague) runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
        saveConfig(Config(id, season.coerceAtLeast(0), keepTeam, cleanSwid, cleanS2))
        resetState()
        _needsLogin.value = false
        refresh(force = true)
        return _error.value
    }

    suspend fun signOut() {
        saveConfig(Config())
        resetState()
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
    }

    private fun resetState() {
        _league.value = null
        _matchup.value = null
        _scoreboard.value = emptyList()
        _week.value = 0
        _loadedAt.value = 0L
        _error.value = null
    }

    // ------------------------------------------------------------------ loading

    /**
     * League, teams and schedule, then the week's matchup. Skipped when [force] is false
     * and the last load is recent: see [Refresh].
     *
     * The week is only defaulted the first time. Once someone has stepped back to week 3,
     * a refresh refreshes week 3.
     */
    suspend fun refresh(force: Boolean) {
        loadConfig()
        if (!config.configured) return
        val now = System.currentTimeMillis()
        if (!force && !Refresh.dueOnOpen(_loadedAt.value, now)) return
        busy {
            when (val result = loadLeague()) {
                is Outcome.Ok -> {
                    val state = result.value
                    _league.value = state
                    _needsLogin.value = false
                    _error.value = null
                    if (_week.value == 0) _week.value = defaultWeek(state)
                    ensureTeamId(state)
                    loadWeekData()
                    _loadedAt.value = System.currentTimeMillis()
                }
                is Outcome.Failed -> fail(result)
            }
        }
    }

    /** True when a manual refresh may go out now. */
    fun manualRefreshAllowed(): Boolean =
        Refresh.manualAllowed(_loadedAt.value, System.currentTimeMillis())

    /**
     * Which week to open on. `scoringPeriodId` runs ahead of the schedule once the season
     * ends, and is 0 before it starts, so it is clamped to a week the schedule has.
     */
    private fun defaultWeek(state: LeagueState): Int {
        val played = state.weeksPlayed()
        val wanted = maxOf(state.info.scoringPeriodId, state.info.matchupPeriodId, 1)
        if (played.isEmpty()) return wanted
        return wanted.coerceIn(played.first(), played.last())
    }

    private suspend fun ensureTeamId(state: LeagueState) {
        if (config.teamId != 0 && state.team(config.teamId) != null) return
        val mine = Lineup.findMyTeam(state.teams.associate { it.id to it.owners }, config.swid)
        val picked = mine ?: state.teams.firstOrNull()?.id ?: 0
        if (picked != config.teamId) saveConfig(config.copy(teamId = picked))
    }

    suspend fun pickTeam(teamId: Int) {
        if (teamId == config.teamId) return
        saveConfig(config.copy(teamId = teamId))
        busy { loadWeekData() }
    }

    suspend fun stepWeek(delta: Int) {
        val state = _league.value ?: return
        val played = state.weeksPlayed()
        val bounds = if (played.isEmpty()) 1..state.info.finalScoringPeriod else played.first()..played.last()
        val next = (_week.value + delta).coerceIn(bounds)
        if (next == _week.value) return
        _week.value = next
        busy { loadWeekData() }
    }

    private suspend fun loadWeekData() {
        val teamId = config.teamId
        val week = _week.value
        if (teamId == 0 || week == 0) return
        when (val result = loadWeek(week)) {
            is Outcome.Ok -> {
                _scoreboard.value = result.value
                val m = result.value.firstOrNull { it.involves(teamId) }
                if (m == null) {
                    // No matchup that week (before the schedule exists, or out of the
                    // playoffs). The lineup is still yours to look at.
                    val mine = (loadRoster(teamId, week) as? Outcome.Ok)?.value
                    _matchup.value = null
                    _roster.value = mine
                } else {
                    val mine = (loadRoster(teamId, week) as? Outcome.Ok)?.value
                    val theirs = m.opponentOf(teamId)?.let { (loadRoster(it.teamId, week) as? Outcome.Ok)?.value }
                    _matchup.value = MatchupDetail(m, teamId, mine, theirs)
                    _roster.value = mine
                }
            }
            is Outcome.Failed -> fail(result)
        }
    }

    private val _roster = MutableStateFlow<Roster?>(null)
    val roster: StateFlow<Roster?> = _roster.asStateFlow()

    // ------------------------------------------------------------------ requests

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Failed(val message: String, val needsLogin: Boolean = false) : Outcome<Nothing>
    }

    private fun <T> FantasyApi.Result.map(parse: (String) -> T?): Outcome<T> = when (this) {
        is FantasyApi.Result.Ok ->
            parse(body)?.let { Outcome.Ok(it) }
                ?: Outcome.Failed("ESPN answered with something this tool could not read.")
        is FantasyApi.Result.Denied -> Outcome.Failed(message, needsLogin = code == 401 || code == 403)
        is FantasyApi.Result.Offline -> Outcome.Failed("No connection.")
    }

    private suspend fun loadLeague(): Outcome<LeagueState> = withContext(Dispatchers.IO) {
        val season = config.effectiveSeason()
        val leagueId = config.leagueId
        val meta = api.get(Espn.readUrl(season, leagueId, listOf("mSettings", "mTeam", "mStandings")))
        val metaBody = when (meta) {
            is FantasyApi.Result.Ok -> meta.body
            is FantasyApi.Result.Denied ->
                return@withContext Outcome.Failed(meta.message, meta.code == 401 || meta.code == 403)
            is FantasyApi.Result.Offline ->
                return@withContext cachedLeague()?.let { Outcome.Ok(it) } ?: Outcome.Failed("No connection.")
        }
        val info = FantasyParser.parseLeague(metaBody, leagueId)
            ?: return@withContext Outcome.Failed("ESPN answered with something this tool could not read.")
        val teams = FantasyParser.parseTeams(metaBody)
        val matchups = when (val schedule = api.get(Espn.readUrl(season, leagueId, listOf("mMatchupScore")))) {
            is FantasyApi.Result.Ok -> FantasyParser.parseMatchups(schedule.body)
            // A league whose schedule is not generated yet is a real state in August.
            else -> emptyList()
        }
        runCatching { File(cacheDir, "league.json").writeText(metaBody) }
        Outcome.Ok(LeagueState(info, teams, matchups, System.currentTimeMillis()))
    }

    /** The scoreboard for one week. Deliberately without `scoringPeriodId` (52 KB, not 353 KB). */
    private suspend fun loadWeek(week: Int): Outcome<List<Matchup>> = withContext(Dispatchers.IO) {
        val url = Espn.readUrl(config.effectiveSeason(), config.leagueId, listOf("mMatchupScore"))
        api.get(url).map { FantasyParser.parseMatchups(it, week) }
    }

    /** One team's roster for one week. */
    suspend fun loadRoster(teamId: Int, week: Int): Outcome<Roster> = withContext(Dispatchers.IO) {
        val season = config.effectiveSeason()
        val pro = proSchedule(season)
        val url = Espn.readUrl(
            season,
            config.leagueId,
            listOf("mRoster"),
            scoringPeriodId = week.coerceAtLeast(1),
            forTeamId = teamId,
        )
        api.get(url).map { FantasyParser.parseRoster(it, teamId, week, pro) }
    }

    suspend fun freeAgents(slotIds: List<Int>, onWaivers: Boolean?): Outcome<List<FreeAgent>> =
        withContext(Dispatchers.IO) {
            val season = config.effectiveSeason()
            val week = _week.value.coerceAtLeast(1)
            val url = Espn.readUrl(season, config.leagueId, listOf("kona_player_info"), scoringPeriodId = week)
            val filter = Espn.playerFilter(
                slotIds = slotIds,
                season = season,
                scoringPeriodId = week,
                limit = 50,
                onWaivers = onWaivers,
                sort = if (_byProjection.value) Espn.PlayerSort.PROJECTION else Espn.PlayerSort.OWNERSHIP,
            )
            api.get(url, filter).map { FantasyParser.parseFreeAgents(it, season, week) }
        }

    // ------------------------------------------------------------------ writes

    /**
     * Swap two players' slots. Refused here, with the reason, when illegal; otherwise one
     * transaction with both moves. Returns null on success or the reason it failed.
     *
     * Not verified against a real account by BrightFantasy either: the body is the shape
     * ESPN's web client sends, and every failure shows ESPN's own message.
     */
    suspend fun swap(a: RosterSlot, b: RosterSlot): String? {
        Lineup.swapProblem(a, b)?.let { return it }
        val week = _week.value
        val body = Lineup.lineupBody(config.teamId, config.swid, week, Lineup.swapMoves(a, b))
        return write(body)
    }

    suspend fun addDrop(add: FreeAgent, drop: RosterSlot?): String? {
        val week = _week.value.coerceAtLeast(1)
        val body = Lineup.addDropBody(
            teamId = config.teamId,
            memberId = config.swid,
            week = week,
            addPlayerId = add.playerId,
            dropPlayerId = drop?.playerId,
            onWaivers = add.onWaivers,
        )
        return write(body)
    }

    private suspend fun write(body: String): String? {
        if (!config.signedIn) return "Changing a lineup needs the SWID and espn_s2 cookies. Add them in setup."
        var problem: String? = null
        busy {
            val result = withContext(Dispatchers.IO) {
                api.post(Espn.writeUrl(config.effectiveSeason(), config.leagueId), body)
            }
            when (result) {
                is FantasyApi.Result.Ok -> loadWeekData()
                is FantasyApi.Result.Denied -> {
                    problem = result.message
                    if (result.code == 401 || result.code == 403) _needsLogin.value = true
                }
                is FantasyApi.Result.Offline -> problem = "No connection. Nothing was changed."
            }
        }
        return problem
    }

    // ------------------------------------------------------------------ cache

    /**
     * The NFL calendar, cached on disk for a week. It is the only source for kickoff
     * times and for telling "scored nothing" from "has not played".
     */
    private suspend fun proSchedule(season: Int): FantasyParser.ProSchedule {
        cachedPro?.let { (s, schedule) -> if (s == season) return schedule }
        val file = File(cacheDir, "pro-$season.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < PRO_CACHE_MILLIS
        val body = if (fresh) {
            runCatching { file.readText() }.getOrNull()
        } else {
            val fetched = (
                api.get("${Espn.READ_HOST}/apis/v3/games/${Espn.GAME}/seasons/$season?view=proTeamSchedules_wl")
                    as? FantasyApi.Result.Ok
                )?.body
            if (fetched != null) runCatching { file.writeText(fetched) }
            fetched ?: runCatching { file.readText() }.getOrNull()
        }
        val parsed = body?.let { FantasyParser.parseProSchedule(it) } ?: FantasyParser.ProSchedule.EMPTY
        cachedPro = season to parsed
        return parsed
    }

    @Volatile
    private var cachedPro: Pair<Int, FantasyParser.ProSchedule>? = null

    /** The last league payload, so opening the tool underground shows last night's standings. */
    private fun cachedLeague(): LeagueState? {
        val file = File(cacheDir, "league.json")
        val body = runCatching { file.readText() }.getOrNull() ?: return null
        val info = FantasyParser.parseLeague(body, config.leagueId) ?: return null
        return LeagueState(info, FantasyParser.parseTeams(body), emptyList(), file.lastModified())
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun busy(block: suspend () -> Unit) {
        lock.withLock {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun fail(result: Outcome.Failed) {
        _error.value = result.message
        if (result.needsLogin) _needsLogin.value = true
    }

    companion object {
        private val KEY_LEAGUE = stringPreferencesKey("league_id")
        private val KEY_SEASON = intPreferencesKey("season")
        private val KEY_TEAM = intPreferencesKey("team_id")
        private val KEY_SWID = stringPreferencesKey("swid_enc")
        private val KEY_S2 = stringPreferencesKey("espn_s2_enc")
        private val KEY_BY_PROJECTION = booleanPreferencesKey("wire_by_projection")
        private const val PRO_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: Fantasy? = null

        fun get(ctx: SealedLightContext): Fantasy =
            instance ?: synchronized(this) { instance ?: Fantasy(ctx).also { instance = it } }
    }
}
