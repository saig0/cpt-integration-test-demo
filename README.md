# CPT integration test demo

Demo for running [Camunda Process Test (CPT)](https://docs.camunda.io/docs/apis-tools/testing/getting-started/)
tests against an **already running Camunda 8 cluster**, while the **process application runs as a separate application**
that owns the process resources (BPMN, DMN) and hosts the job workers. It mimics the setup of an integration environment in
which the process application, the external services and custom connectors are deployed independently, and the process tests
verify the deployed process and invoke the real workers instead of mocking them.

> **Branch `8.9` — Camunda `8.9.28`.** The other versions live in the branches [`main`](../../tree/main) (C8 Run `8.11.0-alpha1` with CPT `8.11.0-SNAPSHOT`) and [`8.10`](../../tree/8.10) (`8.10.9`). What works in which version, the limitations and the
> recommended next steps are collected in **[docs/CAPABILITIES.md](docs/CAPABILITIES.md)**.

## What is in here

```
.
├── process-application/  Spring Boot app: owns and deploys the BPMN and DMN, hosts the job workers (the "external services"). Started independently.
├── process-tests/        CPT tests (Java + JSON). Connects to a remote cluster. No process resources and no job workers inside.
├── docs/             Capabilities, limitations and version differences
└── .env.example      SaaS credentials template
```

The process is an invoice approval process with the DMN decision `auto-approve-invoice`. It is based on the invoice example of the
[CPT example project](https://github.com/camunda/camunda/tree/main/testing/camunda-process-test-example). The *Send invoice rejection* task is a service task with
the job type `send-invoice-rejection`, handled by a job worker (instead of an HTTP connector).

| Element                        | Job type / decision         | Handled by                      |
| ------------------------------ | --------------------------- | ------------------------------- |
| Auto-approve invoice           | DMN `auto-approve-invoice`  | the cluster (deployed by the process application) |
| Archive invoice                | `archive-invoice`           | `process-application` (fails for `…LEGACY-ERROR…` IDs) |
| Add invoice to accounting      | `add-invoice-to-accounting` | `process-application`                       |
| Send invoice rejection         | `send-invoice-rejection`    | `process-application` (replaces the connector) |
| Approve / manually archive     | user tasks                  | the tests (`completeUserTask`)  |

Tests in `process-tests/src/test`:

| Test | Style | Covers |
| --- | --- | --- |
| `InvoiceApprovalTest` | Java | happy path, rejection, auto-approval (DMN), approval timeout (clock), archive error |
| `InvoiceDecisionTest` | Java, parameterized | the decision table, 7 rows |
| `InvoiceApprovalJsonTest` | JSON (`test-cases/*.json`, `test-cases-clock/*.json`) | the same paths and decision rows as JSON test cases |

## How it works

```
 ┌──────────────────────┐   start instances, assert,    ┌─────────────────────────┐
 │ process-tests (JUnit)│   complete user tasks,        │                         │
 │  CPT, runtime-mode:  │ ────────────────────────────► │  Camunda 8 cluster      │
 │  remote              │   increase time               │  C8 Run (default)       │
 └──────────────────────┘                               │  or SaaS                │
 ┌──────────────────────┐   deploy BPMN/DMN             │                         │
 │ process-application  │ ────────────────────────────► │                         │
 │  resources + workers │ ◄──────────────────────────── │                         │
 └──────────────────────┘   activate / complete jobs    └─────────────────────────┘
```

* **Remote runtime.** `camunda.process-test.runtime-mode: remote` makes CPT connect to the running cluster instead of
  starting a Testcontainers runtime. No Docker is needed. See
  [`process-tests/src/test/resources/application.yaml`](process-tests/src/test/resources/application.yaml).
* **The process application owns the resources.** The BPMN and DMN live in `process-application` and are deployed by it at startup
  (`@Deployment`). **The tests don't deploy anything**: they verify the process as deployed. (If the resources are missing, the process
  instance creation is rejected with a clear `NOT_FOUND` error.) That way a test run can't override what the process application has deployed.
* **Workers are external.** The tests neither start nor mock the job workers
  The tests assert the *effects*: the process path and the
  variables the workers return (`archiveReference`, `accountingEntryId`, `rejectionNotificationId`).
* **No cleanup, isolation by scoping.** CPT's default cleanup purges **all** data of the cluster after each test
  (`POST /actuator/cluster/purge`), including the resources that the process application deployed — the tests would fail from the second test on.
  The tests therefore avoid the purge (see below) and isolate themselves with a unique invoice ID per test,
  selectors that are scoped to the started process instance (CPT 8.9 has no start-time scoping).
* **No cleanup possible on 8.9.** CPT 8.9 has no `data-deletion-mode`: it always purges the cluster through the management API (port 9600), which would delete the
  resources of the process application. `application.yaml` therefore points the management API address to a **closed port**: CPT logs `Failed to delete the runtime data,
  skipping` after each test and keeps the data. The price: **no clock manipulation** (CPT 8.9 uses the same address) and **no JSON tests** (CPT 8.9 has no start-time
  isolation, the JSON test cases select by process definition ID). Both are switched off (`demo.clock-manipulation.enabled`, `demo.json-tests.enabled`).
* **Versions.** The tests, the process application and C8 Run use `camunda.version` (`8.9.28`). Spring Boot 4.0 with a pinned `httpclient5` (see the root `pom.xml`).

## Prerequisites

* Java 21 and Maven 3.9+
* The [c8 CLI](https://github.com/camunda/c8ctl) (`c8`, also available as `c8ctl`) to install and run C8 Run: `npm install -g @camunda8/cli`
* For SaaS: a cluster and an API client (see [Run on Camunda 8 SaaS](#run-on-camunda-8-saas))

## Run on a local C8 Run (default)

**1. Start the cluster** with the c8 CLI (`c8` is the short name of `c8ctl`). It downloads the version on the first start. CPT 8.9. needs the clock control enabled:

```bash
CAMUNDA_SYSTEM_CLOCKCONTROLLED=true c8 cluster start 8.9.28
```

The environment variable makes the clock of the cluster controllable (CPT 8.9 changes the clock through the management API). The timer tests are **off** on this
branch: the management API address is closed on purpose (see above), so the clock can't be changed. The variable only matters if you restore the address to experiment.

**2. Start the process application** in a second terminal. It deploys the BPMN/DMN and runs the job workers. It keeps running across test runs.

```bash
mvn -pl process-application spring-boot:run
```

**3. Run the tests**

```bash
mvn -pl process-tests test
```

14 tests run and pass (4 Java process tests, 7 decision rows; the timer test and the 9 JSON test cases are skipped).
A coverage report (HTML and JSON, BPMN and DMN) is written to `process-tests/target/coverage-report/` (`report.html`, `report.json`). CPT 8.9 has no start-time scoping: on a cluster that is not purged the report may include the data of earlier runs.

Stop the cluster with `c8 cluster stop`.

> ⚠️ **CPT's default is to delete all data of the cluster after every test** — this demo turns it off (it would delete the deployed resources).
> Don't enable it (`data-deletion-mode: cluster_purge`) and never point the tests at a cluster with data
> you care about. Use a dedicated test cluster (a separate C8 Run version/instance is enough). **Use the cluster exclusively for CPT:** CPT doesn't
> support parallel tests, and purge, clock and coverage are cluster-wide. Run one test job at a time per cluster. See
> [limitations](docs/CAPABILITIES.md#limitations-and-blockers).

## Run on Camunda 8 SaaS

SaaS has no management API (port 9600). The default configuration of this branch already avoids the purge by pointing the management API address to a closed port. The `saas` profile
increases the assertion timeout. **Not verified on SaaS** for 8.9 (only 8.11 was).

1. In the SaaS console create an API client (Cluster → API) with the scopes `Zeebe`, `Tasklist`, `Operate` (or `Orchestration`).
2. `cp .env.example .env`, fill in the values (the file is gitignored), and load them: `set -a; . .env; set +a`. For a stage other than production set
   `CAMUNDA_CLUSTER_DOMAIN` (for example `ultrawombat.com` for INT). The credentials file of the console (`export …`) works as well: `set -a; . <file>; set +a`.
3. Start the process application against SaaS (it stays running):

   ```bash
   SPRING_PROFILES_ACTIVE=saas mvn -pl process-application spring-boot:run
   ```

4. Run the tests against SaaS:

   ```bash
   mvn -pl process-tests test -Psaas
   ```

> Status: **not verified on SaaS for 8.9**. On 8.11 (main) it was verified against a cluster on the Camunda INT stage; see the README of `main` and [docs/CAPABILITIES.md](docs/CAPABILITIES.md).

## Configuration reference

| What | Where |
| --- | --- |
| Remote runtime, addresses, timeouts | `process-tests/src/test/resources/application.yaml` |
| SaaS overrides | `process-tests/src/test/resources/application-saas.yaml` |
| Process application: connection (C8 Run / SaaS), resources | `process-application/src/main/resources/application*.yaml`, `…/bpmn/` |
| Camunda version of the branch | `camunda.version` in the root `pom.xml` |

Any property can be overridden on the command line, for example
`mvn -pl process-tests test -Dcamunda.process-test.assertion.timeout=PT30S`.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Failed to connect to remote Camunda runtime` | The cluster is not running or not ready, or the addresses are wrong. Check `c8 cluster status`. |
| `Command 'CREATE' rejected with code 'NOT_FOUND': Expected to find process definition with process ID 'Process_InvoiceApproval', but none found` | The process application has not deployed the resources. Start it first (it deploys the BPMN/DMN). Also happens after a purge of the cluster: restart the process application. |
| Assertions time out waiting for a step | The process application (workers) is not running, or runs against another cluster. Check its log. |
| Worker log shows `500 Internal Server Error` while activating jobs | Only when the cluster is purged (not the default of this demo). Harmless, the workers recover. |
