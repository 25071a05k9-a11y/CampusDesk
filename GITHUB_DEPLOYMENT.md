# GitHub deployment record — CampusDesk

## Important: this is source-code publication, not cloud hosting

CampusDesk is a Java 21 backend + React/Vite frontend local college helpdesk prototype. The included JAR and offline frontend assets allow a Windows local demonstration. It does **not** use the MySQL/Maven commands in the provided friend's report, and publishing to GitHub does **not** deploy a publicly reachable service.

## GitHub screenshots for the deployment section

After publishing this project to an empty GitHub repo named `CampusDesk` under your GitHub account:

1. Screenshot 1 — repository Code tab showing project name, README, and root files:
   `https://github.com/25071a05k9-a11y/CampusDesk`
2. Screenshot 2 — Java backend source tree (shows source code filenames):
   `https://github.com/25071a05k9-a11y/CampusDesk/tree/main/server/src`
3. Screenshot 3 — React source tree (optional):
   `https://github.com/25071a05k9-a11y/CampusDesk/tree/main/frontend/src`

These become working URLs **only after the repository is actually created and pushed**. Do not present them as live until verified.

## Publish from Windows

First create a **new, empty** repository `CampusDesk` in GitHub. Do not initialize with README/.gitignore/license (those are already in this folder). The repo may be public or private; public lets instructors view without signing in. Never upload the original credential-bearing v7 presentation ZIP.

Open PowerShell/Command Prompt in the extracted `CampusDesk` project directory:

```cmd
git init
git branch -M main
git add .
git commit -m "Publish CampusDesk Java React project"
git remote add origin https://github.com/25071a05k9-a11y/CampusDesk.git
git push -u origin main
```

Git may ask you to configure your username/email and sign in via the browser. Verify the Code tab and source tree after pushing.

## Suggested accurate report text

> Deployment and version control: The CampusDesk source is maintained in a GitHub repository with separate Java backend and React frontend directories. The prototype is intended for local Windows execution. It uses Java 21 for its HTTP backend and React/Vite for the UI, with a bundled offline demo build. After extracting or cloning the repository, the user can configure a local `.env` file from `.env.example` and run `Start_CampusDesk_Windows.cmd`. The application serves its interface at `http://127.0.0.1:8000`, subject to campus Wi-Fi configuration. Jev and AGY remain optional integrations requiring separate authentication. GitHub publication is source-code hosting and does not itself constitute cloud application deployment.
