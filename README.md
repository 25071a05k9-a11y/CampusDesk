# CampusDesk — AI-Powered College Helpdesk System

CampusDesk is a local college helpdesk prototype for VNRVJIET, with a Java backend, React frontend, ticket tracking, and an assistant that can suggest routes and prepare requests for review. Optional Jev and Antigravity (AGY) integrations require separately configured services and credentials; without them, the application provides local rule-based guidance.

> This is a local prototype, not a deployed college service. The current build uses anonymous browser sessions and a host Wi-Fi check, not student identity verification. It does not connect to live college records or automatically deliver tickets to staff. Do not use it to store sensitive information.

## Architecture

- `server/src/` — Java HTTP server, local JSON storage, Wi-Fi gate, ticket handling, Jev routing and AGY adapters.
- `server/tests/` — Java Wi-Fi/API and mock AGY bridge tests.
- `frontend/src/` — React dashboard and assistant interface; `frontend/dist/` contains a separate offline demo interface.
- `reference/` — dated public faculty directory snapshot with source links.
- `CampusDesk.jar` and Windows launcher scripts — prebuilt local demo and startup tools.

The server listens on `127.0.0.1:8000`. Tickets are stored locally and scoped to a browser session. The host's SSID check is not secure authentication and is not suitable for campus-wide access. Staff dashboards, email verification and login are not enabled in this version. See [implementation status](IMPLEMENTATION_STATUS.md) and [architecture](JAVA_AGY_JEV_ARCHITECTURE.md) for details.

## Run the Windows demo

1. Install a Java 21 or newer runtime, or accept the launcher offer to download a portable Java 21 runtime. Downloading requires internet access.
2. Copy `.env.example` to `.env`. Set `CAMPUSDESK_WIFI_SSID` to the exact campus network name if known; otherwise the example uses a prefix. Never commit `.env`.
3. Run `Start_CampusDesk_Windows.cmd`. If prompted about AGY, decline to use local guidance or follow the optional setup separately.
4. Open `http://127.0.0.1:8000` on the same computer. The dashboard requires the configured Wi-Fi by default.

The prebuilt demo uses `CampusDesk.jar` and the included offline frontend; it does not require Node.js. The launcher and Wi-Fi connection were not tested on this machine.

## Build from source

Install JDK 21+ and Node.js 22+ with npm. On Windows, run `run_windows.bat`; on Linux or macOS, run `bash run_linux_mac.sh`. These scripts compile the Java source and build the React frontend. Wi-Fi detection and the default gate are designed for Windows; see [implementation status](IMPLEMENTATION_STATUS.md). The React build was verified with Node 26 and npm 11; Java compilation and launch were not verified in the publication environment.

To build just the frontend:

```sh
cd frontend
npm install
npm run build
```

The build replaces `frontend/dist` locally. To run the Java tests after compiling, use the commands in [implementation status](IMPLEMENTATION_STATUS.md) or compile the files in `server/src` and `server/tests` into a separate build directory and run `CollegeWifiTests` and `AgyBridgeTests` with assertions enabled.

## Optional integrations and privacy

Configure Jev using the `JEV_*` variables in `.env`; configure AGY CLI or the alternative HTTP bridge separately. See [AGY bridge setup](ANTIGRAVITY_BRIDGE_SETUP.md) and [architecture](JAVA_AGY_JEV_ARCHITECTURE.md). External services can receive user questions; record sharing is disabled by default. Real provider connectivity was not verified. Do not place API keys, private data, or passwords in the repository.
