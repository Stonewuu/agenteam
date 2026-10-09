# Contributing to AgenTeam

[简体中文](CONTRIBUTING.md) · **English**

You can contribute to AgenTeam by fixing bugs, improving features, updating documentation, or sharing feedback from using the project.

This guide explains how to prepare changes, run checks, and submit a pull request.

## 1. Describe the problem or improvement

Search [Issues](https://github.com/Stonewuu/agenteam/issues) for related discussions first. Add information to an existing issue when it covers the same problem; otherwise, open a new issue to describe your report or suggestion.

For a bug report, include:

- The application version, operating system, and deployment method.
- Steps that reproduce the problem.
- The expected and actual results.
- Error logs or screenshots with sensitive information removed.

For a feature request, describe the use case, the current limitation, and the behavior you need. Before implementing a new feature or changing an interface or database structure, describe the goal and affected areas in an issue.

## 2. Set up your development environment

1. Fork the repository to your GitHub account and clone your fork locally.
2. Create a working branch from `main`. Keep each pull request focused on one clear goal.
3. Follow the [Development and contributions section of the README](README.en.md#development) to prepare the environment, generate configuration, and start the services.

The repository is organized by responsibility:

| Directory | Contents |
| --- | --- |
| `agenteam-server/` | Backend code and tests |
| `agenteam-web/` | Frontend code and tests |
| `deploy/` | Deployment configuration, setup tools, and container build files |
| `docs/` | Deployment, release, and third-party software documentation |

## 3. Update code and documentation

- Follow the directory structure, naming conventions, and code style of the module you are changing. Backend business code is organized by technical layer and business module; frontend business code belongs in the corresponding feature directory.
- Use braces for control statement bodies and format them across multiple lines. Write comments, logs, and user-facing error messages in clear Chinese, while keeping technical names in their original form.
- When fixing a bug or changing behavior, use existing tests or add cases that cover the triggering conditions, expected results, and error handling.
- Update the relevant documentation and examples when features, configuration, or deployment steps change. Keep the Chinese and English versions of the README and contribution guide in sync.
- Do not commit keys, passwords, real user data, local runtime configuration, or build artifacts. Remove sensitive information from logs, screenshots, and examples before sharing them.

## 4. Check your changes

Choose checks based on what you changed. For documentation-only changes, verify accuracy, links, paths, and formatting. For code changes, run the relevant tests.

From the repository root, check the formatting of both unstaged and staged changes:

```sh
git diff --check
git diff --cached --check
```

### Backend checks

The backend uses the repository's Maven Wrapper to run a fixed Maven version. Run the full test suite when changing package structure, shared configuration, or core workflows. For other backend changes, run the affected tests.

The full suite includes container integration tests. Keep Docker running and build the required test images from the repository root:

```sh
docker build --tag agenteam/sandbox-office:local deploy/sandbox
docker build --tag agenteam/test-minio:2025-09-07-07c3a429 agenteam-server/src/test/resources/containers/minio
```

Enter `agenteam-server/` and run the full suite.

Linux / macOS:

```sh
sh ./mvnw test
```

Windows PowerShell:

```powershell
.\mvnw.cmd test
```

### Frontend checks

Run the following commands in `agenteam-web/`. On the first installation or after dependencies change, run `pnpm install --frozen-lockfile` to install from the lockfile. Use the pnpm version specified by `packageManager` in [package.json](agenteam-web/package.json).

```sh
pnpm lint
pnpm exec next typegen
pnpm exec tsc --noEmit
```

If you change production routes or build configuration, also run a production build:

```sh
pnpm build
```

Run the specific tests for the feature you changed. For example, use `pnpm test:navigation` for navigation, `pnpm test:channel-auth` for enterprise messaging entry points, and `pnpm test:schedule-actions` for scheduled tasks. See [package.json](agenteam-web/package.json) for the complete script list.

### Deployment and automated checks

When changing deployment configuration, build scripts, or database migrations, verify the affected build, startup, or upgrade steps. Describe the impact on existing configuration and data. Use a separate test database for database checks.

The repository runs automated checks when pull requests are opened or updated and when changes are pushed to `main`. The steps are defined in [community-checks.yml](.github/workflows/community-checks.yml). Investigate failures in both local and automated checks, and explain in the pull request why any checks were not run.

## 5. Write commit messages

Follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/). Use `type: description` for commit titles. Write the description, body, and footers in English to describe the actual change. Add a module name in parentheses after the type when a scope is needed.

The body and footers are optional. Separate the title, body, and footer section with blank lines. Mark a breaking change with `!` after the type or scope, immediately before the colon, or add a `BREAKING CHANGE: ...` footer. Explain the change and the required upgrade steps.

| Type | Purpose |
| --- | --- |
| `feat` | Add a feature |
| `fix` | Fix a bug |
| `docs` | Update documentation |
| `refactor` | Change code structure while preserving behavior |
| `test` | Add or update tests |
| `ci` | Change automated checks |
| `chore` | Maintain build configuration, dependencies, or tools |

Examples:

```text
fix(auth): fix redirects after session expiration
docs: add agent-assisted deployment instructions
test: cover scheduled task execution failures
```

## 6. Submit a pull request

Push your working branch to your fork and open a pull request against AgenTeam's `main` branch. Include:

- **Reason for the change:** The problem, use case, and links to related issues.
- **Changes made:** The resulting behavior and affected areas.
- **Validation:** The commands you ran, their results, and reasons for any checks you did not run.
- **User interface changes:** Screenshots of the updated interface.
- **Configuration and data changes:** Instructions for deployment, interface, or database changes, including the impact on existing configuration and data.

Mark user interface, configuration, or data changes as "Not applicable" when they do not apply. Address review feedback on the same working branch and update the relevant validation results.

## License and third-party sources

AgenTeam uses the [Apache-2.0 license](LICENSE). Contribution terms are defined in Section 5, "Submission of Contributions," of that license.

When adding third-party code, fonts, images, or dependencies, state the source, version, and license in the commit message. Preserve copyright notices and license texts as required by each component's license. See the [third-party notices](docs/releases/third-party.md) for information about existing components.
