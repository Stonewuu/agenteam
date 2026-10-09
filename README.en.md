<p align="center">
  <img src="media/readme/cover.en.svg" width="100%" alt="AgenTeam — A multi-agent collaboration platform" />
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <strong>English</strong>
</p>

<p align="center">
  <a href="https://github.com/Stonewuu/agenteam/releases/tag/v0.2.0"><img src="https://img.shields.io/badge/version-0.2.0-AD6547?style=flat-square" alt="AgenTeam 0.2.0" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-596B57?style=flat-square" alt="Apache-2.0 license" /></a>
  <a href="docs/deployment/images.md"><img src="https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&amp;logo=docker&amp;logoColor=white" alt="Deploy with Docker Compose" /></a>
</p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#quick-start"><strong>Quick start</strong></a> ·
  <a href="#development">Development</a> ·
  <a href="#license">License</a>
</p>

**AgenTeam is an open-source, self-hosted multi-agent collaboration platform that brings AI employees into your team's everyday work.**

Combine models, knowledge, skills, and tools into AI employees. Run conversational tasks, work with files, coordinate to-dos, and connect your team through WeCom and Feishu. Organize work by project so you can return to its conversations, context, and results.

<a id="features"></a>

## Features

| What you want to do | What AgenTeam provides |
| --- | --- |
| Create AI employees for different roles | Configure models, skills, knowledge, and tools, then publish agents for team members to hire. |
| Complete work through conversation | Multi-turn conversations, tool calls, subagent collaboration, and human approval at key steps. |
| Work with deliverables | Process office files, preview and download results, and reuse them across project conversations. |
| Keep the team informed | Personal and team to-dos, owners, due dates, priorities, and activity notifications. |
| Schedule recurring work | One-time, daily, weekly, and monthly agent tasks or notifications. |
| Connect existing collaboration tools | WeCom and Feishu account linking, sign-in, and personal notifications. |

<a id="workspace"></a>

### Workspace

Create a project, choose an AI employee, and start a task. Conversations, project files, and to-dos stay together so work is easy to resume.

<a href="media/readme/screenshots/en/workspace.jpg"><img src="media/readme/screenshots/en/workspace.jpg" width="100%" alt="AgenTeam workspace with projects, AI employees, and to-dos" /></a>

<a id="conversations"></a>

### AI employees and conversations

Describe your goal in natural language and let an AI employee work with its knowledge and tools. Continue the conversation, bring in subagents, and approve key actions. Preview and download generated files, then reuse them in later tasks within the same project.

<a href="media/readme/screenshots/en/conversation.jpg"><img src="media/readme/screenshots/en/conversation.jpg" width="100%" alt="An AI employee conversation with tools and file results" /></a>

### Capabilities

Manage agents, skills, plugins, knowledge bases, and data sources in one place. Publish versions, list agents, and grant resource access to your team. Connect OpenAI-compatible model services and MCP (Model Context Protocol) tools.

Administrators manage organization details, members, teams, roles, model services, and resource access, with execution usage available for review.

<a id="enterprise-messaging"></a>

### WeCom and Feishu

- **Accounts and sign-in:** Link an existing AgenTeam account and sign in with a WeCom or Feishu identity.
- **Work notifications:** Receive task completion messages, approval requests, and to-do assignments.
- **Scheduled notifications:** Notify selected members on a schedule and review delivery results by recipient and channel.
- **App management:** Configure enterprise apps, validate credentials, and send test notifications from the administration interface.

[Read the enterprise messaging guide →](docs/deployment/enterprise-messaging.md)

<a href="media/readme/screenshots/en/integrations.jpg"><img src="media/readme/screenshots/en/integrations.jpg" width="100%" alt="WeCom and Feishu integration settings" /></a>

<a id="schedules"></a>

### To-dos and schedules

Assign owners, due dates, and priorities to personal and team to-dos. Schedule agent work or notifications, preview upcoming runs, pause plans, and review execution history.

<table>
  <tr>
    <td width="50%"><a href="media/readme/screenshots/en/todos.jpg"><img src="media/readme/screenshots/en/todos.jpg" width="100%" alt="Personal and team to-dos" /></a></td>
    <td width="50%"><a href="media/readme/screenshots/en/schedules.jpg"><img src="media/readme/screenshots/en/schedules.jpg" width="100%" alt="Scheduled tasks and execution history" /></a></td>
  </tr>
</table>

<a id="quick-start"></a>

## Quick start

### Option 1: Ask an AI agent to deploy

Send the following to an agent that can run commands on your computer or server:

```text
Please deploy this project for me: https://github.com/Stonewuu/agenteam
Read the deployment documentation and check the current environment first. After deployment, tell me the access URL and initial setup steps.
```

### Option 2: Deploy with Docker images

**For a server installation, use the latest released images on Docker Hub.** The deployment package includes service configuration and setup tools. Follow the [Docker deployment guide](docs/deployment/images.md) for the complete installation steps.

### Option 3: Deploy from source

Prepare Git, Docker, and Docker Compose. On Windows, use Docker Desktop in Linux container mode.

#### 1. Get the project

```sh
git clone https://github.com/Stonewuu/agenteam.git
cd agenteam/deploy
```

#### 2. Generate configuration

Linux / macOS:

```sh
sh configure.sh single
```

Windows PowerShell:

```powershell
.\configure.ps1 -Mode single
```

The setup tool creates `.env` with generated database passwords, service credentials, and a setup credential. Existing configuration is preserved.

#### 3. Start the services

```sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 300
```

#### 4. Complete the initial setup

Open **[http://localhost:8088](http://localhost:8088)** and complete the setup:

1. **Create your workspace:** Use `SETUP_CREDENTIAL` from `deploy/.env` to create an administrator and organization.
2. **Connect a model:** Add a provider, access key, and model under organization model settings.
3. **Start a task:** Create, publish, and list an agent in Capabilities, then hire it under AI employees and start a conversation. Enable command execution when the agent needs to process office files.

For public access, configure HTTPS for your domain and set `PUBLIC_URL`. Connect a mail service to use email verification, password recovery, and invitations. See the [configuration example](deploy/.env.example) and [Nginx reverse proxy example](examples/deployment/nginx.production.example.conf).

<a id="development"></a>

## Development and contributions

AgenTeam uses Java 21, Spring Boot 4, AgentScope 2, Next.js 16, and React 19. MySQL stores business data, while Redis handles sessions and live conversation data.

### Development environment

Prepare Java 21, Node.js 24, pnpm 11.15.1, and Docker. Generate `deploy/.env` as described above.

### Run locally

#### 1. Start dependencies

Start dependencies from `deploy`:

```sh
docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis
docker build -t agenteam/sandbox-office:local sandbox
```

#### 2. Start the backend

In `agenteam-server`, set `AGENTEAM_DB_USERNAME=agenteam`. Set `AGENTEAM_DB_PASSWORD` to the `DB_PASSWORD` value from `.env`, and `AGENTEAM_REDIS_PASSWORD` to its `REDIS_PASSWORD` value. Start the backend:

```sh
./mvnw spring-boot:run
```

Use `.\mvnw.cmd spring-boot:run` in Windows PowerShell. Default ports are `43306` for MySQL, `46379` for Redis, and `8080` for the backend.

#### 3. Start the frontend

In another terminal, enter `agenteam-web`:

```sh
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

Open [http://localhost:3000](http://localhost:3000). `AGENT_BACKEND_URL` defaults to `http://localhost:8080`.

### Checks and contributions

Run checks appropriate to your change: `./mvnw test` for the backend, and `pnpm lint`, `pnpm exec tsc --noEmit`, and `pnpm build` for the frontend. Additional scripts are listed in [package.json](agenteam-web/package.json).

Share suggestions and report problems through [Issues](https://github.com/Stonewuu/agenteam/issues), or open a pull request to improve the product and its documentation. See the [contribution guide](CONTRIBUTING.en.md).

<a id="license"></a>

## License

AgenTeam is available under [Apache-2.0](LICENSE). Copyright © 2026 stonewu. See [NOTICE](NOTICE) and the [third-party notices](docs/releases/third-party.md) for attribution and dependency licensing information.
