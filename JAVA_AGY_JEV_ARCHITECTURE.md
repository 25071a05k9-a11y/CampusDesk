# CampusDesk — Java + Jev + AGY + SEND_TO

Design reference: 15-slide CampusDesk CBP presentation (especially slides 3, 5–7, 10, 13). The older slide deck describes a **Python/SQLite v2 prototype**. This Java v5 is a different implementation; do not cite Python or SQLite as the live stack for this Java ZIP.

## Responsibilities

- Student UI: maintained React source under `frontend/src`. Offline no-npm fallback in `frontend/dist` enables immediate use when npm registry is unavailable.
- **Jev**: typed route selection, `database_lookup`, `agent_investigation`, `send_to_person`, `ask_clarification`. Set `JEV_API_KEY` for the remote Jev router. Otherwise the server uses explicit local rules. No fake claim that Jev is online.
- **AGY chat engine**: when `AGY_CLI_ENABLED=1`, Java invokes the official `agy` binary for every eligible chat request, headless with `--sandbox`. AGY sign-in is interactive once, through `Setup_AGY_Windows.cmd`. The older optional HTTP adapter remains in `AgyBridge.java`; never use the unmodified bridge from `agy-cli` that bypasses permissions. `AGY_CLI_ENABLED=1` takes precedence over `AGY_BRIDGE_ENABLED=1`.
- Java backend: accepts HTTP requests, applies college Wi-Fi gate and browser-session boundaries, makes read-only lookups, and saves ticket drafts ONLY after explicit student confirmation. It does not grant AGY arbitrary SQL or college API write tools.
- Data store: local JSON persistence (`data/campusdesk.json`). The 154-person dated public faculty directory is loaded from `reference/faculty-vnrvjiet.json`. Real staff room allocations are not verified. No live ERP, SIS, payment feed or staff delivery.

## Example

Student: "I paid my exam fee but the portal shows unpaid."

1. Java receives the message and retrieves only permitted local records.
2. Jev (if configured) selects the route and receiving department; otherwise local rules label the fallback.
3. AGY writes a natural language explanation if installed and authenticated; otherwise labeled local guidance is returned. AGY cannot change student records.
4. Java generates a SEND_TO ticket draft with the proposed department.
5. Student reviews the draft and submits; a local ticket ID and status are saved.
6. Student tracks that ticket. An actual institution-approved integration is required before real staff receive it.

## Correct security claims

- The portal uses a host Wi-Fi SSID gate; this does **not** authenticate students or staff.
- AGY must be used only with an approved account/session, and users should not send sensitive records to an unreviewed service.
- CLI tool execution is restricted by `--sandbox`, not a guarantee of full isolation; deploy a stronger separate container/service for real multi-user usage.
- CLI fallback is clearly labeled if the tool is missing/unavailable.

## Validation summary

- `javac` compilation succeeded with JDK 21.
- Existing `CollegeWifiTests` passed 48 assertions.
- Existing `AgyBridgeTests` passed 23 assertions against a local fake bridge; **not** a live Google account.
- React JSX syntax verified with TypeScript transpilation; a React 19/Vite production bundle was NOT built in this environment because npm registry resolution was unavailable.
- Offline browser fallback shipped; Java HTTP successfully served `/`, `/offline.js`, `/api/config`, and `/api/assistant` in an isolated test harness.
- Browser automation was attempted but Chromium did not finish in the execution environment. Do not claim end-to-end GUI click tests succeeded on Windows until verified there.
- Portable JRE download and AGY Windows login cannot be tested inside this Linux container; users must approve them on first run.
