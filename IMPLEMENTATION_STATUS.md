# CampusDesk v5 — verification status

**Implemented:** Java 21 API, React 19 frontend source, host Wi-Fi-only dashboard access, automatic isolated guest sessions (no login), empty-by-default persistent store, local helpdesk AI interface, rule-based routing, optional Jev API adapter, optional OpenAI-compatible LLM adapter, record lookups with permission checks, human-confirmed ticket draft submission, browser-isolated tickets, Java SSE updates, and mobile-adaptive styling.

**Validated in the current environment:** Java 21 compilation and 41 Java HTTP/API assertions, including blocked access away from the configured Wi-Fi, no seeded records, bot suggestions and absence of cross-visitor data disclosure. JSX source parsed with TypeScript's JSX parser without syntax diagnostics.

**Not validated end to end here:** npm/Vite bundle or Windows launcher, because npm registry lookup was unavailable in this runtime. They require testing on a Windows PC with JDK 21 and Node 22+ and internet for first-time dependency installation.

**Not connected:** real EduPrime, live college records, staff delivery, institution SSO, Jev credentials, external AI credentials. An SSID gate is not secure student/staff authentication; local user data and tickets are for prototype testing.

## AI bot bug-fix update (same v5 archive)

- Corrected routing for mixed greetings/issues, unpaid payment mismatches, broken classroom Wi-Fi, and procedural complaint questions.
- Explicit empty-data answers for attendance, timetable, faculty and payment records; no fabricated values.
- Corrected model configuration reporting and enabled general external chat even when record sharing is disabled. Matching local records stay on-device unless explicitly opted in.
- Fixed React bot error replies, double-submit locking, drafted ticket lifecycle, and Ctrl+Enter submission.
- Verified **41** Java HTTP/API assertions and JSX parse without syntax errors. Full React dependency build and Windows behavior not verified here (npm packages unavailable in this environment).

## Antigravity/Hermes Bridge update (2026-10-12)

- Added Java `AgyBridge.java`, compatible with the reference GitHub project's OpenAI-style `/v1/chat/completions` response. The reference only consumes the last user prompt; the adapter puts all helpdesk instructions there.
- Added configurable bridge URL, model, local-only network guard, timeout, opt-in record sharing and optional API key. Disabled by default.
- Existing deterministic Jev/local routing and explicit user-confirmed ticket submission remain unchanged. On bridge failure, a clearly labeled local response is shown.
- Added `AgyBridgeTests.java` with a mock local HTTP server; no fake/live provider claims.
- **Unresolved:** real Antigravity CLI account, hardened bridge process on Windows, institutional privacy acceptance, and model quota/terms. The referenced upstream proxy runs with `--dangerously-skip-permissions` and is not safe to host for untrusted students unmodified.

## 2026-10-12 focused update

- React overview/helpdesk switching hardened with defensive bootstrap normalization, an exception boundary, and recoverable Wi-Fi/API error screens; no more forced hard reload on a transient Wi-Fi check.
- Faculty directory has 154 sourced public faculty names from 8 official VNRVJIET departments, with department filters, search and original source links. Data is a dated partial snapshot, not a live synchronization.
- Timetable functionality removed from UI and API imports/lookups.
- Ticket comments/reply form removed and comment endpoint disabled. Past comments remain stored in existing databases without being shown, preserving user data and auditability.
- Regression suite: 48 Wi-Fi/helpdesk Java assertions + 23 bridge mock assertions passed. TypeScript transpilation passed. Full Vite build / browser click verification remains pending, because npm registry access is blocked in this container.

## Java / Jev / AGY revision (2026-10-12)

- AGY preferred through `AgyCli.java`, calling the official CLI in sandboxed headless mode; no automatic dangerous permission bypass.
- Jev remains independent for typed routing and local fallback. SEND_TO remains user-confirmed draft only.
- Java server compiled into prebuilt `CampusDesk.jar`, with offline browser fallback and launcher that can install portable JRE with explicit consent.
- Source React UI now has per-panel error boundaries and stronger normalization; React production build not possible here due to npm registry unavailable.
- AGY official Windows install/login setup script included; requires separate interactive sign-in and cannot be authenticated by packaging alone.
- 48 API assertions and 23 bridge mock tests passed. No live AGY/Jev connectivity or Windows click-through has been verified.

## Presentation Jev key configuration (v7)

The GitHub-ready source ZIP intentionally excludes `.env` and all Jev credentials. Make your own `.env` from `.env.example` and add `JEV_API_KEY` privately. `Start_CampusDesk_Windows.ps1` loads this local file. The Java router calls the Jev `systemone` endpoint only when configured and available, otherwise uses local rules. Live Jev connectivity has not been verified. Never commit credentials; rotate any key contained in a previously shared presentation ZIP.
