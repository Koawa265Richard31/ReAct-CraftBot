# ReAct-CraftBot

> **This is not an official Numen project.** This repository is a fork of
> [Numen](https://github.com/Dwinovo/minecraft-numen) that connects the mod's body
> to an **external agent brain** ([DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness),
> "DSH" below), so the fake player living inside Minecraft is driven by real out-of-process reasoning.
>
> Names, logos and art assets belong to the upstream author; this project does not use its branding.
> 中文版：[README.md](README.md)

## What it is

A Forge 1.20.1 mod derived from Numen. The **body** keeps the upstream design: a real `ServerPlayer`
subclass that walks, mines, crafts, fights, opens chests and wears gear.

**The brain is not in the mod.** The mod only does three things:

1. Exposes Lua action APIs (`numen.move` / `numen.inv` / `numen.scan` …) plus a gear abstraction (`GearSource`);
2. Exposes the body over **MCP (HTTP, default `127.0.0.1:8765`)** to an external agent;
3. Keeps the local state needed to "stay alive", and publishes a **read-only** side channel
   (`127.0.0.1:8766`) for everything that must be observable while she is busy.

```
external agent (DSH / your own MCP client)
        │  MCP 8765: tool calls (say, scripts, screenshots, events)
        ▼
  client mod ── 8766 GET /live ──► read-only state snapshot (no lease, no program cut)
        │
        ▼
  server side (companion body / script exec / gear slots / Curios and other sources)
```

## Capability status

Honest split: **landed** = code is in this repository and passes offline gates; **unverified** = not yet
compiled into a jar and not yet tried in game.

| Capability | Status |
|---|---|
| All upstream body abilities (move/mine/craft/fight/containers/FTB quests) | landed |
| `numen.move.fly` / `flyTo`, `numen.inv.inspect` | landed (earlier changes of this fork) |
| MCP server + lease (idle after 90s without a call) | landed |
| **In-game panel** (F9, seven tabs: character / tasks / chat / skills / usage / notices / settings) | landed · **unverified** |
| **Character tab (read-only)**: health/food/effects/activity, gear slots (incl. Curios), 36-slot inventory | landed · **unverified** (needs a matching server: payload gained `health/maxHealth/air/gear`) |
| **Read-only channel `/live` (8766)**: 4 Hz snapshot, own token, never renews the lease | landed · **unverified** |
| Settings migration (provider / persona / voice / STT / skin / MCP / brain / theme) | landed · **unverified** |
| Screenshot retention (`keepShots`, default 10) with resizing | landed · **unverified** |
| Gear & inventory **writes** (wear/unequip/transfer) | **in progress**, not in this version |
| Protocol versioning (cross-version connects degrade instead of crashing) | planned |
| Public chat entry (`/yuuka <text>` typed in the vanilla chat bar) | in progress |

> The panel and `/live` rely on **new** payload fields, so **client and server must ship the same version**.
> Cross-version behaviour and its degradation matrix are the protocol-versioning work.

## Install and connect

1. Install the **same version** of the jar on client and server (a mismatch fails the handshake).
2. Enable MCP: set `enabled: true` in `config/numen/mcp_server.json` (**off by default**; a random token
   is generated on first launch).
3. Point your agent at `http://127.0.0.1:8765/mcp` with `Authorization: Bearer <token>`.
4. For read-only state: enable `live.enabled`, then
   `curl -H "X-Numen-Live: <live_token>" http://127.0.0.1:8766/live`.

### Two things to know before connecting your own MCP client

- **Lease**: every `tools/call` renews it (90s by default), and **a call from another client cuts the
  program currently running** (the receipt is `this turn was cut off`). Poll slowly, never concurrently with actions.
- **`/live` is not MCP**: no JSON-RPC, no lease renewal, no program slot — it exists for "look while she works".

## The panel (F9)

Styled after the video-settings screen actually shipped in the modpack (Embeddium/Sodium look: dark
translucent, left category list, right rows).

| Tab | Content |
|---|---|
| Character | status strip, gear slots (Curios included; unregistered sources render as read-only dashed cells), 27+9 inventory |
| Tasks | current objective, round, tokens spent, stuck count (`CurrentTaskPayload` + `GoalState`) |
| Chat | conversation entry points and unread counts (opens the existing chat screen) |
| Skills | enable/disable learned skills |
| Usage | token ledger (read-only) |
| Notices | event history (ring buffer, max 200) |
| Settings | eight sections, reusing the existing settings panels as-is |

Two permanent cells at the bottom of the left rail: **mini-bar toggle** and **stop** (danger colour, confirmed).

## Why gear slots are "mod-agnostic"

The abstraction is a **pluggable source**, not a hard-coded slot table:

- `GearSource#slots(body)` yields `GearSlot`s (`name / worn / refuseWear / refuseRemove / swap`);
- vanilla armour + offhand is one source, Curios is another (`plugins/curios`);
- the panel iterates every source and **knows no specific mod**; unregistered sources are read-only.

Supporting a new slot system = writing one `GearSource` plugin, no panel changes.

## Changes vs upstream

| Location | Change |
|---|---|
| `MoveApi` / `FlyTo*` | `numen.move.fly` / `flyTo` (straight line + collision, no ground pathing) |
| `InvApi` | `numen.inv.inspect(item)`: read item tooltips to compare gear |
| `ConsentCards` | consent requests are also written to chat |
| `Companions` | respawn at own spawn point first, owner's side only as fallback |
| `client/screen/panel/**` | new in-game panel (original to this fork) |
| `client/live/**` | new read-only side channel (original to this fork) |
| `network/payload/NumenStatePayload` | added `health / maxHealth / air / gear` (for the panel) |
| `AgentLoop` | fixed an `aside` leak: a cut now returns a failure receipt instead of stalling the program slot |

Full history: [FORK.md](FORK.md).

## Known limitations

- The panel and `/live` are **not yet verified in game**; earlier builds do not contain them.
- With an older server, health and gear cannot be shown (new fields) — it degrades, but only after the
  protocol-versioning work lands.
- Sandboxing, self-iterating tool writes and the secret fence live on the **host side** (the external agent),
  not in this repository.
- Upstream art assets are not included, so a self-built jar has incomplete GUI assets (see [LICENSING.md](LICENSING.md)).

## License

| Scope | License |
|---|---|
| Code inherited from upstream (`api/`, `core/`, `agent/`, `plugins/`, `pathing/`, `ui/`, `lua/`, `ai/`) | **LGPL-3.0-only** (see [LICENSE](LICENSE)) |
| Original to this fork: `api/common/src/client/java/com/dwinovo/numen/client/screen/panel/**`, `client/live/**` | **MIT** |
| Upstream art assets | All Rights Reserved, **not included here** |

New files should carry `SPDX-License-Identifier: MIT`.

## Build

```bash
JAVA_HOME=/opt/java17 ./gradlew build -x test --no-daemon
# artifact: core/forge/build/libs/numen-forge-1.20.1-0.1.4.1-all.jar
```

The full flow (Chinese lang injection, two-sided deployment) is in [FORK.md](FORK.md).

## Upstream and credits

- Numen — <https://github.com/Dwinovo/minecraft-numen>
- DeepSeek Harness — <https://github.com/deepseek-ai/deepseek-harness>
- Upstream README archive: [UPSTREAM-README.md](UPSTREAM-README.md) / [UPSTREAM-README_EN.md](UPSTREAM-README_EN.md)
