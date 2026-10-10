# BrightFantasy Lite

> **This is the Lite version.** BrightFantasy Lite is built only with Light's official SDK, so it can go in the Light Tool Library. It does less than the full app. For the full app, get [BrightFantasy](https://github.com/gi-os/BrightFantasy) ([on BrightMarket](https://brightmarket.gzl.dev/app/com.gios.brightfantasy/)).

ESPN fantasy football as a Light Phone III tool. It shows your matchup, lets you set your
lineup, and has the standings, the week's scores and the waiver wire. It is a port of
[BrightFantasy](https://github.com/gi-os/BrightFantasy), Giovanni's sideloaded app, to the
official [Light SDK](https://github.com/lightphone/light-sdk) so it can go in Light's Tool
Library.

## What it does

| Screen | What's on it |
| --- | --- |
| **Matchup** (opens here) | Both starting lineups side by side, slot by slot, the score and the margin, how many starters still have to play, and the projected finish. Step back through past weeks. Tap a player for his box score. |
| **My team** | Start/sit: tap one player, then the one he swaps with. Illegal swaps are refused with the reason before anything is sent ("Josh Allen can't play FLEX", "... is locked, that game has started"). Shows what you started, the best lineup you could have started, and the gap. Past weeks are read-only. |
| **League** | Standings with the playoff line drawn, and the scoreboard for the week. |
| **Wire** | Free agents and waivers by position, sorted by this week's projection or by percent owned. Tap a player, pick who to drop (or nobody), and the add and drop go to ESPN as one transaction. |
| **Setup** | League id, `SWID` and `espn_s2`, typed or scanned from a QR code. Pick your team if the tool can't tell which one is yours. |

## Kept and dropped

The ESPN logic is BrightFantasy's, ported almost line for line: the URL builders, slot and
position tables, the `x-fantasy-filter` header, the parser (stat source/split matching, the
FLEX-is-slot-23 rule, bye vs. not-yet-played from the pro schedule), the lineup legality
check and the transaction bodies. Their unit tests came across too.

| From BrightFantasy | In Lite | Why |
| --- | --- | --- |
| Matchup, roster with start/sit, standings, scoreboard, waiver wire | Kept | |
| QR setup (`brightfantasy://setup?...`) | Kept, same format | Scanned with the SDK's `LightQrCodeScanner`, so codes from BrightFantasy's setup page work unchanged. |
| Cookies in plain SharedPreferences | Changed: encrypted | Stored in DataStore, encrypted with an AES-GCM key in the Android Keystore. |
| Power rankings, activity feed, draft board | Dropped | Kept the tool small. The feed and the draft board also need a second player-name lookup per screen. |
| Player page with weekly bar chart | Reduced | Tapping a player shows his week and season totals in a modal. |
| Shake to report a bug | Dropped | Needs the accelerometer through `getSystemService`, which the SDK blocks. |
| Wheel scrolling | Kept | Same key mapping as the other Bright tools. |
| Auto-refresh | Not added | Refreshes when opened (at most once a minute) and when you press refresh (at most every 15 seconds). No timers, no background work. Light's guidelines ask for no compulsive refresh. |
| Notifications, widgets, WebView sign-in | Not available | Tools get no notification API, no widgets, no WebView. Sign-in is the QR code or typing. |

## Setup

1. On a computer, sign in at fantasy.espn.com and open your league. The league id is the
   number after `leagueId=` in the address bar.
2. For a private league, open developer tools, then Application (or Storage), then
   Cookies, then `espn.com`, and copy `SWID` and `espn_s2`.
3. Open the setup page, [gi-os.github.io/BrightFantasy](https://gi-os.github.io/BrightFantasy/)
   (a copy is in [`setup/index.html`](setup/index.html)). It draws a QR code in your browser
   and has no network code.
4. In the tool, open Setup, press SCAN QR, then SAVE. SAVE loads the league to check the
   cookies before keeping them.

A public league needs only the league id. The cookies expire every few months; when the tool
says ESPN refused the login, make a fresh code.

## Network and privacy

The tool talks to two hosts, both ESPN's, and nothing else:

- `lm-api-reads.fantasy.espn.com`: league settings, teams, schedule, rosters, free agents,
  and the NFL schedule (for kickoff times and byes).
- `lm-api-writes.fantasy.espn.com`: lineup changes and add/drops, only when you make one.

The `SWID` and `espn_s2` cookies are sent only to those hosts. League data is cached in the
tool's private files so the last standings show when you're offline. Remove league and
cookies in Setup deletes both.

## Things to know

Writes are not verified against a real account. BrightFantasy's author had read access to a
public league and no ESPN login, so the lineup and add/drop bodies are the shape ESPN's web
client sends. Every failure shows ESPN's own message, so the first real attempt says what is
wrong. Waiver claims go in with a bid of 0.

## Build

```
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest :tool:assembleDebug
```

CI (`.github/workflows/build-tool.yml`) runs the tests, lint and the debug build on every
push. The tool lives in `tool/`; `sdk/`, `plugin/` and `lint-rules/` are upstream and
untouched.

## Tool Library submission

- Tool id `com.gios.brightfantasylite`, label "BrightFantasy Lite", set in `tool/lighttool.toml`.
- Permissions: `INTERNET` (ESPN) and `CAMERA` (the setup QR scan only). No capabilities.
- No push, no background jobs, no location, no notifications.
- Not affiliated with ESPN. It uses ESPN's undocumented fantasy API, which can change
  without notice.

## Credits

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT). See
[LICENSE](LICENSE).
