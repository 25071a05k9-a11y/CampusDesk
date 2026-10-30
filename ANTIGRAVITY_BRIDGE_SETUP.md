# CampusDesk — Antigravity / Hermes Bridge integration

Reference project: <https://github.com/ypnpt6ysmw-bit/agy-cli> (Google Antigravity Pro Subscription & Hermes Bridge)

CampusDesk stays **Java + React**. The Java `AgyBridge` adapter makes a read-only HTTP call to an external, OpenAI-compatible `/v1/chat/completions` service, just like the repository's `agy_api.py`. CampusDesk does not bundle, install, or start `agy` or Hermes. Installing the bridge and signing in to Antigravity are separate tasks. The model credentials/session must be valid for that service.

## Important security warning about the reference project

At the time of review (2026-10-12), the referenced `agy_api.py` launches `agy` with `--dangerously-skip-permissions`. **Do not run that stock bridge as a publicly exposed or multi-student-facing service.** A coding agent with permissions bypassed can potentially read local files or run tools under the host operating-system account. It also logs portions of user prompts. Use this repository only as an **interface reference** until the bridge is hardened with a restricted account, no unsafe auto-approved tools, workspace isolation, no prompt/PII logging, and network authentication. Check the official Antigravity and Google subscription usage policies before deploying it for a campus.

For personal local testing, you can point CampusDesk at a *separately hosted, secured OpenAI-compatible service* you control. The adapter is not itself a sandbox for Antigravity's tool permissions.

## Windows configuration

1. Set up Antigravity CLI separately, if needed, using its **official Windows installation docs**, not macOS shell scripts. Verify `agy` is available and authenticated.
2. Start a **hardened and trusted local** OpenAI-compatible bridge bound to `127.0.0.1` on **port 8765**. The reference Python proxy uses port `8000` by default, which conflicts with CampusDesk. If adapting a reviewed, restricted copy of that proxy, launch its FastAPI app with Uvicorn on port 8765 rather than running the hardcoded `main()` port 8000. Example for a properly hardened compatible service: `python -m uvicorn agy_api:app --host 127.0.0.1 --port 8765`. This command must NOT be used on the unmodified unsafe source.
3. In the **CampusDesk** folder, create `.env` if needed (`copy .env.example .env`) and configure:

```env
AGY_BRIDGE_ENABLED=1
AGY_BRIDGE_URL=http://127.0.0.1:8765/v1/chat/completions
AGY_BRIDGE_MODEL=Gemini 3.5 Flash
AGY_BRIDGE_API_KEY=
AGY_BRIDGE_TIMEOUT_SECONDS=75
AGY_BRIDGE_SEND_RECORDS=0
AGY_BRIDGE_ALLOW_REMOTE=0
```

4. Restart `run_windows.bat`. Connect the PC to the configured VNRVJIET Wi-Fi first. Open CampusDesk at `http://127.0.0.1:8000` and ask the AI bot a general question.
5. The assistant will identify the source of each answer under the chat message: `Antigravity bridge · <model>` when the service replies, or `Local guidance ...` when it is disabled or unavailable. The app never silently pretends an offline response is model-generated.

**Do not paste an Antigravity login token or Google session cookie in CampusDesk.** The bridge manages its own authorized session. `AGY_BRIDGE_API_KEY` is optional and intended for bridge-layer authentication only, if your bridge explicitly supports it.

## Data protection and behavior

- With `AGY_BRIDGE_SEND_RECORDS=0`, CampusDesk does **not send retrieved local college records** to the AI. If matching local records were found, it answers locally. However, the **question typed into AI chat** is transmitted to the configured bridge when no local records were matched. Do not type passwords, roll numbers, or personally identifying records into a third-party bridge that you do not trust.
- The AI model is **text-only** from CampusDesk's perspective: it has no CampusDesk write tools or SQL access. Java routes requests and creates editable ticket drafts deterministically. Tickets are saved only after the user presses Submit.
- Enable record export only with institutional authorization. `AGY_BRIDGE_SEND_RECORDS=1` may send authorized local records, so this is inappropriate for anonymous testing or sensitive education records.
- By default only a local `http://127.0.0.1` or `localhost` bridge is permitted. To connect a remote bridge requires both `AGY_BRIDGE_ALLOW_REMOTE=1` and an HTTPS URL; verify operator security and privacy first.
- No live EduPrime data and no actual staff message delivery are added by this bridge.
- Jev routing remains independent (`JEV_API_KEY`), and local routing handles missing provider access.
- Bridge logging policies, Google terms, model quota and subscriptions may affect real behavior. A subscription does not guarantee API usage rights or quota.

## Technical shape

`React chat` → `POST /api/assistant` → `Java routing + read-only data lookups` → `AgyBridge` → trusted `POST /v1/chat/completions` → generated natural-language answer → optional **unsubmitted** ticket draft. The reference bridge's non-streaming response format is supported. Its implementation reads only the last user message, so `AgyBridge` includes all necessary safety instructions in that message instead of relying on separate system-role support.

The integration tests use a mock local HTTP server; passing them does not prove that an external Antigravity account or model can currently be accessed.
