# Staged events without a human GM — findings

Read-only research in worktree `wt-staged-events` (branch `feat/staged-events`). Question: can an AI
director start an event from `scripts/event/` on a schedule, with **real bot clients** as the visible
participants, **`FakePlayer` map objects** as the watching crowd, and a server notice — with no GM?

Legend: **verified** = the file at `path:line` was read directly. **uncertain** = reasoned from
verified code but not traced end to end.

---

## 0. Premise check (read this first)

One premise in the brief is false, one is self-contradictory, and one is correct.

- **"A human GM normally has to run every one of them by hand" — false.** Of the 108 files in
  `scripts/event/`, 36 schedule themselves from their own `init()` (`em.schedule` /
  `em.scheduleAtTimestamp`), and 51 distinct event names are opened by players through
  `scripts/npc/` or `scripts/map/onUserEnter/`. The only GM-only event system is the unrelated
  four-class one in `server/events/gm/` (`Ola`, `Fitness`, `Coconut`, `Snowball`, driven by
  `!startevent`/`!startmapevent`/`@joinevent`, `StartEventCommand.java:45-57`). Verified.
  A concrete self-triggering example:
  `scripts/event/AreaBossBamboo.js:34` — `setupTask = em.schedule("start", 0); //spawns upon server
  start`, and `:56` broadcasts a notice when the boss appears.
- **"A notice tells the player" vs. "the player should discover the event" is self-contradictory.**
  Pick one; the mechanism is the same either way (§4).
- **"108 event scripts" — correct** (`ls scripts/event/*.js | wc -l` = 108).

The important consequence: the server already runs timed, player-less event work. What does **not**
exist is any way to start an *instance* (`EventInstanceManager`) without a `Character`. That is the
whole gap.

---

## 1. Can an event instance exist with no players in it?

**Yes — routinely, and there is no reaper for it.** Three verified levels of evidence.

**(a) The script manager runs script code with zero players.**
`EventScriptManager`'s constructor calls `init()` (`EventScriptManager.java:59`), which invokes each
loaded script's `init()` (`EventScriptManager.java:81-88`, `invokeFunction("init", null)`). Channels
get their manager at boot via `Channel.reloadEventScriptManager`
(`Channel.java:173-181`, called from `Server.java:952`). A script's `init()` is therefore a
player-free entry point that already schedules future work:
`scripts/event/AreaBossBamboo.js:34`; the whole `AreaBoss*` family; `scripts/event/2xEvent.js:38-42`
(`em.scheduleAtTimestamp`). Those `Runnable`s run on `TimerManager`'s thread
(`EventManager.schedule` → `EventScriptScheduler.registerEntry`, `EventManager.java:168-176`,
`EventScriptScheduler.java:97-115`).

**(b) Instances are already created and fully set up before the first player is registered.**
Every `EventManager.startInstance` overload calls the script's `setup()` **before** registering a
player or setting a leader: `EventManager.java:401` (`createInstance("setup", …)`) → `:415`
(`eim.setLeader`) → `:418` (`eim.registerExpedition`) → `:420` (`eim.startEvent()`). So between
`setup()` and the first `registerPlayer` there is a live, timer-running, mob-spawning instance with
`getPlayerCount() == 0`.
`scripts/event/GuildQuest.js:128-178` is the clearest case: `setup(level, lobbyid)` creates the
instance, `resetPQ`s 29 maps (spawning every mob in the instance, `MapleMap.java:3986`,
`MapleMap.java:3446`), starts the event timer, and returns — with no player in it.
`scripts/event/GuildQuest.js:187-192` (`afterSetup`) even handles a null leader explicitly.

**(c) Nothing today starts a whole instance player-free, but every primitive needed is public.**
- `EventManager.newInstance(String)` — public, no player (`EventManager.java:218`).
- `EventInstanceManager.startEvent()` — public, no player (`EventInstanceManager.java:1085`); it just
  flips `eventStarted` and invokes the script's `afterSetup`.
- `EventManager.getIv()` — public (`EventManager.java:204`), and the `Invocable` is a
  `SynchronizedInvocable` (`EventScriptManager.java:108`, `SynchronizedInvocable.java:31`), so a
  background thread may invoke a script function and blocks only while another script call holds the
  monitor.
- What is **private** and therefore unavailable is the bookkeeping:
  `startLobbyInstance` (`EventManager.java:289`), `registerEventInstance` (`:358`),
  `createInstance` (`:354`), `freeLobbyInstance` (`:309`).

**Blockers to a from-Java start:**
- Every *public* `startInstance` overload dereferences a `Character` (`leader.getId()`,
  e.g. `EventManager.java:382`; the name-based overload resolves one via
  `getCharacterByName` and NPEs on a miss, `EventManager.java:664-665`).
- `EIM.setLeader` NPEs on null (`EventInstanceManager.java:1239-1245`), `getLeader()` returns
  `chars.get(leaderId)` = null (`:1230-1237`), and `showClearEffect()` / `showWrongEffect()` call
  `getLeader().getMapId()` with no null guard (`:1248`, `:1258`).
- `Expedition` cannot exist without a `Character` (`Expedition.java:110-114`), so the Zakum/Horntail
  style events need a *real logged-in character* as the expedition leader regardless.

**Not verifiable statically:** whether any deployed code calls `getIv().invokeFunction("setup", …)`
directly. No such call site exists in this tree (`grep` over `src/main/java`). **uncertain.**

**No reaper.** `EventRecallCoordinator.manageEventInstances` only prunes its own recall map and is
disabled anyway (`USE_ENABLE_RECALL_EVENT: false`, `config.yaml:269`;
`EventRecallCoordinator.java:58-77`). Instances live until a script disposes them. A player-less
instance leaks its `MapManager` (and its loaded maps) until `EIM.dispose()` runs
(`EventInstanceManager.java:656-670`).

---

## 2. What gates entry?

**`minPlayers` is not an entry gate in either script named in the brief.** It is a *teardown*
threshold.

### Zakum (`scripts/event/ZakumBattle.js`, `minPlayers = 6` at `:27`)

The only hard gate is in the NPC script, not the event:

| Gate | Where |
|---|---|
| Talk to Adobis (NPC 2030013) | `scripts/npc/2030013.js` |
| Leader level in `[exped.getMinLevel(), exped.getMaxLevel()]` = `[50,255]` | `:60`; `ExpeditionType.java:36` |
| Leader holds item `4001017` (Eye of Fire) | `:99` |
| Expedition must not already exist on the channel | `:106-110` |
| **Team size ≥ `exped.getMinSize()`** | `:150-156` (`var min = exped.getMinSize(); if (size < min) return;`) |
| Then `em.startInstance(expedition)` | `:178` → `EventManager.java:367-376` |

`ExpeditionType.getMinSize()` returns `1` when `USE_ENABLE_SOLO_EXPEDITIONS` is on
(`ExpeditionType.java:60-62`); it is **off** here (`config.yaml:267`), so 6.
`EventManager.startInstance(Expedition, …)` itself performs **no** size check — verified by reading
the whole method (`EventManager.java:376-438`). The gate lives only in the NPC's JS.

`minPlayers = 6` in `ZakumBattle.js` is passed to `isExpeditionTeamLackingNow(true, minPlayers, player)`
at `:123`, `:139`, `:150` — and **that method ignores its `minPlayers` argument entirely**:
`EventInstanceManager.java:1141-1148` returns `getPlayerCount() <= 1` unless the event is cleared.
Comment at `:1145`: *"expeditions don't need to have neither the leader nor meet the minimum
requirement inside the event"*. So once in, one player keeps the instance alive.

**Expedition vs. party:** for Zakum an **expedition is required** — `ExpeditionType.ZAKUM`
(`scripts/npc/2030013.js:34`), created by `cm.createExpedition(exped)`
(`AbstractPlayerInteraction.java:1078-1091`), consumed by `em.startInstance(expedition)`.
`registerExpeditionTeam` only registers members whose `getMapId() == exped.getRecruitingMap().getId()`
(`EventInstanceManager.java:379-386`), so every participant must be standing on the recruit map.

**Instance name is part of the contract.** `ZakumBattle.js:97` names it `"Zakum" + channel`, and the
NPC's late-join path looks it up by that exact string: `em.getInstance(expedName + channel)`
(`scripts/npc/2030013.js:84`, `expedName = "Zakum"` at `:34`). A director that creates an instance
under any other name makes `eim` null there and the NPC script throws. Same shape in
`HorntailBattle.js:95`, `BalrogBattle.js:120`, `PinkBeanBattle.js:98`, `ScargaBattle.js:95` and their
NPCs (`2083004.js:82`, `1061014.js:82`, `2141001.js:85`, `9120201.js:83`, `9270047.js:84`).

### Guild Quest (`scripts/event/GuildQuest.js`, `minPlayers = 6` at `:27`)

Entry is a **guild queue**, not a portal:
`scripts/npc/9040000.js:123` `em.addGuildToQueue(guildId, leaderId)` →
`EventManager.addGuildToQueue` (`EventManager.java:813`) → `attemptStartGuildInstance`
(`:849`) which *polls the queue until it finds the leader character online on this channel*
(`:855-861`), then `startInstance(chr)` → the plain-Character overload. So a Guild Quest can be
started by **one character** (the guild leader); `minPlayers = 6` is only ever read at
`GuildQuest.js:208` (`checkEventTeamLacking`), `:238`, `:266`, `:280` — all after start, and all of
them either expel a player or end the instance. There is **no entry gate at all**.

---

## 3. Instanced maps

**Model: separate full maps, not copy-on-write.** Each `EventInstanceManager` owns a private
`MapManager` built in its constructor with `event == this`
(`EventInstanceManager.java:113-122`: `new MapManager(this, em.getWorldServer().getId(),
em.getChannelServer().getId())`). `MapManager.getMap` lazily calls
`MapFactory.loadMapFromWz(mapid, world, channel, event)` (`MapManager.java:77,91-102`), which
constructs a **brand new `MapleMap`** and stamps the instance onto it
(`MapFactory.java:133`, `:150-151`). Nothing is cached or shared between instances; the same map id
loaded by two `EIM`s yields two independent maps. The channel's own `MapManager` is separate and
carries `event == null` (`Channel.java:130`).

Consequence the scripts exploit: `eim.getMapInstance(id)` vs. `em.getChannelServer().getMapFactory().getMap(id)`
are *different objects for the same id*. `ZakumBattle.js:217` deliberately touches the channel map
(the shared gate reactor) while `:102` resets the instanced altar. `resetMap` / `getDisposableMap`
exist for the fresh-copy cases (`MapManager.java:50,104`).

**Lifecycle.** `EIM.dispose(boolean)` schedules `mapManager.dispose()` one minute later
(`EventInstanceManager.java:662-665`); `MapManager.dispose` calls `map.dispose()` on every map
(`MapManager.java:133-140`), and `MapleMap.dispose()` nulls `footholds`, clears `portals`, and clears
`mapobjects` (`MapleMap.java:4375-4388`). Any crowd left on the map at that point owns a dead
reference.

**Fake players work on an instanced map, unchanged.**
`FakePlayerService.populate(MapleMap, int, FakePlayerActivity[])` is public and takes any `MapleMap`
(`FakePlayerService.java:234`); it calls `map.addFakePlayerMapObject(fakePlayer)`
(`FakePlayerService.java:268` → `MapleMap.java:390`). Nothing in that path consults `event`. A
`FakePlayer` registered on an instanced map is delivered to a player who arrives later through
`MapleMap.sendObjectPlacement` (`MapleMap.java:2894-2907`), because `MapObjectType.FAKE_PLAYER` is a
non-ranged type (`MapleMap.java:2878-2892`, case at `:2884`) and `FakePlayer.sendSpawnData` sends
`PacketCreator.spawnFakePlayer` (`FakePlayer.java:144-146`). **verified.**

Two caveats:
- `FakePlayerService.walk()` returns immediately when `map.getAllPlayers().isEmpty()`
  (`FakePlayerService.java:319`), so the crowd is *frozen* (not absent) until a real player is in the
  instance. Standing-still is exactly what a passive crowd wants; it does mean "spawning a crowd and
  watching it" is not observable without a player.
- `populate` reads `map.getFootholds()` outside any try/catch (`FakePlayerService.java:235-237`). On
  a disposed map `footholds == null`, so that NPEs; the walk loop only catches per-wanderer
  (`:308-313`) and would log a WARN every 2 s per fake player. The director must call
  `FakePlayerService.despawnAll(map)` (`FakePlayerService.java:277-291`) before the instance's
  `MapManager.dispose()` fires.

**Real bot characters into an instance — the resolution trap matters.**
`Character.changeMap(MapleMap target, Portal pto)` does **not** use `target`: it re-resolves via
`getWarpMap(target.getId())` (`Character.java:1431-1435`), and `getWarpMap` only returns the instance
when the character already has `eventInstance` set (`Character.java:1329-1338`). So a bot that
merely walks into the map id lands on the **channel** copy. Participants must be registered first:
`EIM.registerPlayer(chr)` (`EventInstanceManager.java:239`) sets `chr.setEventInstance(this)` then
invokes the script's `playerEntry`, which does the warp (`ZakumBattle.js:111-115`).
The escape hatch is `Character.forceChangeMap(MapleMap, Portal)` (`Character.java:1466-1499`): it
warps straight to the passed map *and* self-registers into that map's event instance with
`registerPlayer(this, false)` — i.e. **without** running `playerEntry`. Its comment: *"this allows GMs
to patrol players inside instances."* This is the API a director wants. **verified.**

---

## 4. Announcing, and background infrastructure

**A server-wide notice needs no GM and no player thread.** `PacketCreator.serverNotice(int, String)`
(`PacketCreator.java:1235`) builds the packet; `Server.broadcastMessage(int world, Packet)`
(`Server.java:1339-1343`) loops the world's channels → `Channel.broadcastPacket`
(`Channel.java:290-294`) → `Character.sendPacket` → `Client.sendPacket`, which takes a lock around
`ioChannel.writeAndFlush` (`Client.java:1465-1472`). Nothing checks which thread calls it.
The existing precedent is a **script-scheduled** notice with nobody online:
`scripts/event/2xEvent.js:67,75` and `scripts/event/AreaBossBamboo.js:56`. `World.broadcastPacket`
(`World.java:1934`) and `World.setExpRate` (`World.java:363`) are the world-scoped equivalents.
`StartEventCommand.java:46-57` shows the GM version of the same two calls.

**What already exists for a background task** (all verified):
- `TimerManager.getInstance().register/schedule` — `server/TimerManager.java`, a
  `ScheduledThreadPoolExecutor`. Used by `FakePlayerService.start()` under a 2 s tick
  (`FakePlayerService.java:180-186`, `WALK_TICK_MS` at `:47`).
- `ThreadManager.getInstance().newTask` — `server/ThreadManager.java:55-57`. Used to fan out from
  schedulers (`EventScriptScheduler.java:99`, `EventManager.java:890`).
- **`bot/residents/ResidentDirector`** — an in-process director: a `ScheduledExecutorService` ticker
  (`ResidentDirector.java:67`, `scheduleWithFixedDelay` at `:126`), a planning thread so an LLM round
  trip never stalls the ticker (`:176`), a `BotBudget` lease per bot (`:207`), and
  `HumanPresence.humansOnline()` for scale-to-zero (`:138`, `HumanPresence.java:35-49`). Started from
  `Server.java:960` (`ResidentDirector.startIfEnabled()`).
- The participants it starts are **real v83 clients running inside the server JVM**, connecting over
  loopback: `ResidentBot.java:31-32` (`127.0.0.1:8484`), `:96-102`, and `SummonedBot.java:24-28`.
  Logins are serialised by a per-IP gate because two simultaneous loopback logins share one
  `HostHwidCache` slot (`SummonedBot.java:45-58`; `LoopbackLoginGate` as used in
  `ResidentBot.java:87-91`).
- **Bots can already play a PQ end to end**: `bot/kpq/KpqPlanner.java:17-41` drives a bot party
  through Kerning PQ, including NPC dialogue (`Action.TalkToNpc` / `Action.RespondToNpc`,
  `bot/Action.java:27,64`). They are warped into the instance by the *server* script, not by bot-side
  instance code — which is the pattern a staged event needs.

---

## 5. What is genuinely missing, in dependency order

Nothing here is speculative abstraction; each item is the smallest thing that unblocks the next.

1. **A player-free instance start on `EventManager`.** The script's `setup()` is invoked only by the
   private `createInstance` (`EventManager.java:354`) from inside `startInstance`, and every public
   overload needs a `Character`. Add a public entry point (e.g.
   `EventManager.startStagedInstance(String scriptEvent, int lobbyId, Object... setupArgs)`) that
   reuses the private `startLobbyInstance`/`registerEventInstance` bookkeeping
   (`EventManager.java:289`, `:358`) and calls `eim.startEvent()`
   (`EventInstanceManager.java:1085`). Without it a director has to reach through
   `em.getIv().invokeFunction("setup", …)` and silently skip lobby accounting.

2. **A per-script invocation contract.** There are **five different `setup` signatures** among the
   108 scripts (`function setup(level, lobbyid)` ×59, `setup(eim, leaderid)` ×36,
   `setup(difficulty, lobbyId)` ×7, `setup(channel)` ×6, `setup(eim, leaderid)` ×1 — measured with
   `grep -h "^function setup(" scripts/event/*.js`). Zakum needs `setup(channel)`; GuildQuest needs
   `setup(level, lobbyid)`. Whatever starts these must know both the arity and the instance-name
   convention (e.g. `"Zakum" + channel`, §2), or late joiners fall through the NPC.

3. **A leader that is a real character.** `Expedition` cannot be constructed without one
   (`Expedition.java:110-114`), `EIM.setLeader(null)` NPEs (`EventInstanceManager.java:1239`), and
   `showClearEffect`/`showWrongEffect` dereference `getLeader()` unguarded (`:1248`, `:1258`). So the
   staged event must hold a live bot as the expedition/party leader — which is also what makes the
   "visible participants" real rather than a second fake-player layer.

4. **A public way to bring up N participating bots and put them in the instance.** `SummonedBot` is
   package-private and owned by `BotPartySupervisor` (which is keyed on a player owner, not a
   director); `ResidentBot` is package-private and owned by `ResidentDirector`. A staged event needs
   its own supervisor that takes a `BotBudget.Lease` (`bot/budget/BotBudget.java`), waits on
   `LoopbackLoginGate`, and then either (a) drives bots through the NPC/portal flow, or (b) has the
   server warp them with `Character.forceChangeMap`
   (`Character.java:1466-1499`) — the only public path that enters an instance without the
   `playerEntry` side effect.

5. **Crowd spawn/despawn owned by the event.** `FakePlayerService.populate(map, n, activities)`
   (`FakePlayerService.java:234`) and `despawnAll(map)` (`:277`) are already sufficient and already
   work on instanced maps (§3). What must be *added* is the tie to the lifecycle: despawn before
   `mapManager.dispose()` fires (`EventInstanceManager.java:662-665` → `MapManager.java:133-140` →
   `MapleMap.java:4375-4388`), or `populate`'s unguarded `map.getFootholds()`
   (`FakePlayerService.java:235-237`) NPEs on the dead map and the walk loop spams a WARN per fake
   player every 2 s (`FakePlayerService.java:308-313`). `FakePlayerActivity.WAITING` (anchored, 8-20 tick dwell,
   `FakePlayerActivity.java:22,38-40`) is the activity a watching crowd wants.

6. **The director itself.** A ticker plus schedule, modelled on `ResidentDirector`
   (`ResidentDirector.java:67,126,138`), gated on `HumanPresence.humansOnline()`, with its
   LLM objective choice on a separate thread (`ResidentDirector.java:176`) and config keys beside
   the existing `USE_RESIDENTS` / `BOT_BUDGET_*` block (`config.yaml:409-444`). Nothing here exists
   for events: `ServerConfig` has `EVENT_MAX_GUILD_QUEUE`, `EVENT_LOBBY_DELAY`, `EVENT_END_TIMESTAMP`
   (`ServerConfig.java:309,310,330`) and no scheduling concept at all.

7. **Strict script-name resolution.** `EventScriptManager.getEventManager` returns the **static**
   fallback `0_EXAMPLE` manager for any unknown name (`EventScriptManager.java:45` static field,
   `:68` `fallback = events.remove("0_EXAMPLE")`, `:69-75`). Because that field is static, an unknown
   name on channel 3 can hand back the fallback `EventManager` bound to whichever channel constructed
   an `EventScriptManager` last. A director must resolve names against the channel it intends to use,
   never rely on the fallback.

8. **Serialisation discipline.** All script invocation funnels through one `SynchronizedInvocable`
   (`SynchronizedInvocable.java:31`), so a director must not hold a script call across a network
   round trip — the same rule `ResidentDirector` follows by planning on a separate thread
   (`ResidentDirector.java:28-31` documents the intent).

### Smallest viable path

For a **boss-invasion style** staged event, items 1–2 are avoidable: a real bot leader can call
`em.startInstance(expedition)` through the ordinary NPC dialogue (`scripts/npc/2030013.js:178`),
which already exercises every verified code path above. The genuinely new code is then items 3–6:
hold that bot, warp the rest in, spawn/despawn the crowd, and tick. For a **PQ-style** staged event
(GuildQuest), item 1 is required, because `startInstance(chr)` needs a character who can never
legitimately be the guild leader.

---

## Uncertain / unverified

- No call site anywhere invokes a script's `setup()` outside `EventManager`; that an external caller
  can do it successfully (checked exceptions, `afterSetup` dependencies) is reasoned, not run.
- Whether a staged instance started without lobby bookkeeping collides with a later player-driven
  start depends on the instance name colliding too (`EventManager.java:226-234` throws
  `EventInstanceInProgressException` on a duplicate name). Not tested.
- `EventManager.startInstance`'s `startSemaphore` (7 permits, `EventManager.java:81`) and
  `playerPermit` (`:80`, checked at `:382`) are keyed on a leader character id; behaviour when a director starts instances for
  the same leader repeatedly was not traced to conclusion.
- Whether a bot client can complete the Zakum NPC chain in practice (item 4001017 in inventory,
  expedition registration by six separate clients on the recruit map) is a live-test question. The
  packet-level actions required all exist (`bot/Action.java:27,64`), but that is not evidence.
