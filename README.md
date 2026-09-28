# Octane Status Tracker

This is a Jenkins plugin that polls an existing ALM Octane suite runs and evaluates its
results against quality gates before a Pipeline stage or Freestyle build proceeds. It
provides live execution and tester reports, defect analysis, and optional screenshot emails.

- Plugin ID: `octane-status-tracker`
- Requirements: Jenkins 2.568.3 LTS or newer; Java 21 or newer on the controller and agents
- License: [MIT](LICENSE)

## Parent and Child Pipeline Setup

Proposition: keep organization-wide defaults and shared Pipeline logic in one **parent** repository.
Each project's **child** repository contains the bootstrap Jenkinsfile and a
`variables.yaml` file for its specific dashboard, spaces, suite runs and reporting options.
After the initial setup, test engineers edit the child YAML; they do not need to
change the parent Jenkinsfile for each project making it easy to transfer ownership during handover.

### Example Repositories

| Repository | Purpose |
| --- | --- |
| [Parent Pipeline](https://github.com/Crustacean/octane-status-tracker-parent) | Shared Jenkinsfile and organization-wide `octane_spaces_mapping.json`, together at the repository root. |
| [Octane spaces exporter](https://github.com/Crustacean/octane-spaces-export) | Generates the mapping of shared spaces and workspaces visible to your Octane account. |
| [Child Pipeline](https://github.com/Crustacean/octane-status-tracker-child) | Bootstrap Jenkinsfile and project-specific `variables.yaml`, together at the repository root. |
| [Deployment trigger](https://github.com/Crustacean/octane-downstream-trigger) | Example deployment Pipeline that triggers the child Jenkins job and waits for its result. |

Clone or fork these examples into repositories your organization controls. Replace
the example URLs, IDs, names and email addresses before running them. Keep private
space mappings and project configuration in access-controlled repositories.

### Jenkins Prerequisites

Install Octane Status Tracker and these companion plugins through **Manage Jenkins >
Plugins**, accepting their dependencies:

| Plugin | Required for |
| --- | --- |
| [Pipeline](https://plugins.jenkins.io/workflow-aggregator/) (`workflow-aggregator`) | Running the Scripted child and Declarative parent Jenkinsfiles. |
| [Pipeline Utility Steps](https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/) (`pipeline-utility-steps`) | `readYaml`, `readJSON` and `writeJSON`. This is the YAML reader; no separate YAML plugin is needed. |
| [Git](https://plugins.jenkins.io/git/) (`git`) | Checking out the child and parent repositories. |
| [Credentials](https://plugins.jenkins.io/credentials/) (`credentials`) | Storing Octane API client credentials and Git checkout credentials. |

Credentials and [Mailer](https://plugins.jenkins.io/mailer/) are dependencies of
Octane Status Tracker. The full Pipeline suite, Git and Pipeline Utility Steps must
also be installed for these examples to work.
For deployment integration, ensure [Pipeline: Build Step](https://www.jenkins.io/doc/pipeline/steps/pipeline-build-step/)
(`pipeline-build-step`) is installed for the `build` step.

Use a Linux/Unix agent with Git and a shell for the child bootstrap, which uses
`sh`, `cp` and `chmod`. For the parent's email reports, configure SMTP under **Manage
Jenkins > System > E-mail Notification** and provide a supported screenshot browser.
The built-in sender uses Mailer, not Email Extension.

### Organization Setup

1. Clone the [parent repository](https://github.com/Crustacean/octane-status-tracker-parent.git)
   and configure its shared defaults once: quality criteria, polling, timeouts and
   organization email settings. Host this configured parent in your Git service.
2. Clone the [exporter](https://github.com/Crustacean/octane-spaces-export.git) and run
   it with an Octane account authorized to list the required shared spaces and workspaces:

```sh
git clone https://github.com/Crustacean/octane-spaces-export.git
cd octane-spaces-export
python3 -m venv .venv
. .venv/bin/activate
python -m pip install requests
export OCTANE_BASE_URL='https://octane.example.com'
export OCTANE_CLIENT_ID='your-api-client-id'
python main.py
```

The exporter prompts for the client secret when `OCTANE_CLIENT_SECRET` is unset.
Do not commit secrets or put them in shell command history. Python is needed on the
machine running the exporter, not by the Jenkins polling job itself.

3. Place the generated `octane_spaces_mapping.json` beside the parent `Jenkinsfile`.
   Its filename and relative path must match the parent's `OCTANE_SPACES_MAPPING_FILE`
   setting. Regenerate the mapping when spaces change; the export only includes
   spaces the supplied account can access.
4. Create a Jenkins **Secret text** credential containing the HTTPS Octane base URL
   (no query, fragment, or embedded username/password). Set `shared_url` to its
   credential ID, for example `"shared_url": "octane-shared-url"`. Each shared space
   has `"specific_url": ""`; leave it blank to inherit `shared_url`, or set a
   different Secret text credential ID for that space. IDs must contain only letters,
   digits, `_`, `-`, and `.`. A nonblank override that cannot be resolved fails the
   build. Credentials must be accessible to the executing job (global or its containing folder).
   Literal URLs are rejected in both fields, including an overridden `shared_url`.
   The parent must pass the selected credential ID through
   the existing `baseUrl` step argument unchanged; the plugin resolves it at runtime
   and persists only the ID. Do not put the secret URL in Pipeline Groovy or JSON.
5. Create Jenkins **Username with password** credentials for each Octane connection:
   username = `client_id`, password = `client_secret`. In the relevant shared-space
   JSON object, set `"apiCredentialId": "octane-api-client"` to reference that
   credential ID. Without this optional field, the parent derives the credential ID
   from the shared-space name, lowercasing it and replacing whitespace with `_`
   (for example, `Example Shared Space` becomes `example_shared_space`). Ensure the
   credential exists and is accessible to the child job. Repeat for other spaces;
   preserve any manually added credential IDs when regenerating the mapping.

### Project Setup

1. Clone or fork the [child repository](https://github.com/Crustacean/octane-status-tracker-child.git)
   for the project. Keep `Jenkinsfile` and `variables.yaml` at its root. Edit the YAML
   using names or numeric IDs from your exported mapping and suite-run IDs belonging
   to the selected workspace. For example:

```yaml
OCTANE_SHARED_SPACE_NAME: "Example Shared Space"
OCTANE_WORKSPACE_NAME: "Example Workspace"
OCTANE_REGRESSION_SUITE_RUN_ID: "1196,1200"
OCTANE_CRITICAL_SUITE_RUN_ID: "1204"
OCTANE_PROJECT_NAME: "Example Project"
OCTANE_TIMEOUT_MINUTES: "120"
OCTANE_TIMEOUT_MINUTES_EXTENDED: "0"
OCTANE_EMAIL_TO: "qa-team@example.com"
PROGRESS_EMAIL_INTERVAL_CRONJOB: ""
```

These are placeholders. An empty cron value disables interval emails; the parent
still sends its final report. The current parent example
requires a nonempty critical suite-run selector. Its shared criteria are inherited
unless the YAML supplies `OCTANE_CRITERIA`.

2. Create a Jenkins **Pipeline from SCM** job pointing to the child repository, or a
   **Multibranch Pipeline** for its branches. Use `Jenkinsfile` as the script path.
   Configure checkout credentials for the child repository if needed.
3. Configure the child bootstrap's parent checkout settings before its first run:

| Job parameter | Value |
| --- | --- |
| `DIR1_REPOSITORY_URL` | Your configured parent repository's Git URL. |
| `DIR1_BRANCH` | Parent branch, such as `main`. This is independent of the child branch. |
| `DIR1_CREDENTIALS_ID` | Jenkins Git credential ID authorized to read the parent repository. |

For a regular Pipeline job, define these as String parameters. For a Multibranch
setup without preconfigured branch-job parameters, set the three fallback values
at the top of the child Jenkinsfile once. The example URL and
`example-git-credentials` fallback are placeholders; leaving the credential parameter
blank does not disable that fallback. For HTTPS Git, use a Username with password
credential with your Git token as the password. These checkout parameters are not
Octane options and must not be added to `variables.yaml`.

4. Run the child job. It checks out both repositories, copies `variables.yaml` into
   the parent checkout, validates it, and loads the parent Pipeline. Configuration
   precedence is **child YAML > matching job parameter > parent default**. Explicit
   blank YAML values also override defaults; omit a key to inherit its default.
5. Confirm the selected space/workspace and suite runs in the console, then open
   **Octane Gate Report**. Subsequent project changes normally require only YAML edits.

Use only YAML keys supported by the selected parent's `pipelineConfigurationDefaults`.
Unknown keys fail validation even when blank. In the current examples, the child
also includes `ARGOCD_SYNC_REPO_URL`, `ARGOCD_SYNC_BRANCH`, `ARGOCD_STATE_FILE_PATH`
and `ARGOCD_GIT_CREDENTIAL_ID`, which the parent does not declare. Remove those four
optional entries when using this parent. Review compatibility when updating either
repository rather than assuming all example revisions can be mixed.

### Deployment Integration

Other arrangements are possible, but the recommended design is for the deployment
Pipeline to trigger the **child Jenkins job**, not the parent repository directly.
Start with the [deployment-trigger example](https://github.com/Crustacean/octane-downstream-trigger.git).
Set `OCTANE_TEST_JOB` to the child's Jenkins job path (including folders) and
`OCTANE_TEST_BRANCH` to its branch-job name for Multibranch, or blank for a regular
Pipeline job. For branch names containing `/`, use the branch-job name as stored by
Jenkins, including its encoding; the example concatenates the job and branch values.

The example uses `build(wait: true, propagate: false)` to wait and inspect the result.
Only `SUCCESS` reaches the deployment placeholder; `UNSTABLE`, `FAILURE`, `ABORTED`
or an unknown result abort deployment. Replace the placeholder with your actual UAT
deployment steps after verifying the child gate and email configuration.

## Direct Step Configuration

For a custom Pipeline that does not use the parent/child examples, configure the
plugin step directly:

Store an Octane API client in Jenkins **Username with password** credentials: the
username is `client_id`, and the password is `client_secret`. Use an HTTPS Octane URL
with a trusted certificate chain. Never put credentials in the Jenkinsfile.

```groovy
octaneSuiteGate(
  serverId: 'octane',
  baseUrl: 'secret-id',
  credentialsId: 'octane-api-client',
  sharedSpaceId: '1001',
  workspaceId: '5002',
  suiteRunId: '1196,1200',
  criteria: 'regressions.executionRate == 100 AND regressions.passRate >= 95',
  pollIntervalSeconds: 30,
  timeoutMinutes: 120,
  timeoutMinutesExtended: 0,
  markUnstable: false
)
```

Replace the example IDs with values from the selected Octane workspace. Runtime
connections do not fall back to Jenkins global server configuration. Use Jenkins'
Pipeline Syntax Snippet Generator for the complete step configuration.

`suiteRunId` accepts IDs or release selectors. Optional `octaneGateScope` entries
define additional buckets, such as `critical`. Critical scope takes ownership of
duplicate suite-run IDs. Metrics use the active, deduplicated suite-run pool.

- `executionRate`: Passed + Failed + Blocked, divided by total tests.
- `completionRate`: Passed + Failed + Blocked + Skipped, divided by total tests.
- `passRate`: passed tests divided by executed tests (Passed + Failed + Blocked).
- In Progress and No Run are not completed tests.

Criteria support scoped variables, arithmetic, comparisons, `AND`, `OR`, parentheses
and percentage literals. Invalid expressions are rejected before Octane authentication.
Use `octaneDefectGroup` to define grouped defect criteria. The gate polls at the
configured interval; request duration adds to the time between polling cycles.

For Freestyle jobs, add **ALM Octane Suite Gate** and configure the mapping file,
shared space, workspace and criteria. Build reports appear under **Octane Gate Report**.
The `octaneEmailReport` and `octaneCronProgressEmail` Pipeline steps provide final and
interval reporting. Email and screenshot features require their configured mail
transport and a supported browser executable; they are not needed for basic polling.
