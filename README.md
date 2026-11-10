# CPT integration test demo

Demo for [camunda/camunda#64034](https://github.com/camunda/camunda/issues/64034) — *"I can run process
integration tests on a remote environment"*.

It shows how to run [Camunda Process Test (CPT)](https://docs.camunda.io/docs/apis-tools/testing/getting-started/)
tests against an **already running Camunda 8 cluster** (local C8 Run by default, Camunda 8 SaaS as an
alternative), while the **job workers run as separate applications** — like the external services and custom
connectors of a real system.

> **Branch `8.10` — Camunda `8.10.9`.** The other versions live in the branches
> [`main`](../../tree/main) (`8.11.0-alpha1`) and [`8.9`](../../tree/8.9) (`8.9.28`). What works in which version, the limitations and the
> recommended next steps are collected in **[docs/CAPABILITIES.md](docs/CAPABILITIES.md)**.

## What is in here

```
.
├── workers/          Spring Boot app with the job workers (the "external services"). Started independently.
├── process-tests/    CPT tests (Java + JSON), the BPMN and the DMN. Connects to a remote cluster. No job workers inside.
├── scripts/          start-c8run.sh: start C8 Run with a controllable clock
├── docs/             Capabilities, limitations and version differences
└── .env.example      SaaS credentials template
```

The process is the invoice approval example from the CPT example project
([`InvoiceApprovalTest`](https://github.com/camunda/camunda/blob/09bdbbc1b704ef96ecca0e192cc9c7b46249c465/testing/camunda-process-test-example/src/test/java/io/camunda/InvoiceApprovalTest.java),
[`InvoiceDecisionTest`](https://github.com/camunda/camunda/blob/09bdbbc1b704ef96ecca0e192cc9c7b46249c465/testing/camunda-process-test-example/src/test/java/io/camunda/InvoiceDecisionTest.java),
[`InvoiceApprovalJsonTest`](https://github.com/camunda/camunda/blob/96e0c444f3c4dcddcc94c327618b4130f31ff57e/testing/camunda-process-test-example/src/test/java/io/camunda/InvoiceApprovalJsonTest.java))
with its BPMN and the DMN `auto-approve-invoice`. The only change to the model: the *Send invoice rejection* task is no
longer an HTTP JSON connector, but a plain service task with the job type `send-invoice-rejection`, handled by a job worker.

| Element                        | Job type / decision         | Handled by                      |
| ------------------------------ | --------------------------- | ------------------------------- |
| Auto-approve invoice           | DMN `auto-approve-invoice`  | the cluster (deployed by the tests) |
| Archive invoice                | `archive-invoice`           | `workers` (fails for `…LEGACY-ERROR…` IDs) |
| Add invoice to accounting      | `add-invoice-to-accounting` | `workers`                       |
| Send invoice rejection         | `send-invoice-rejection`    | `workers` (replaces the connector) |
| Approve / manually archive     | user tasks                  | the tests (`completeUserTask`)  |

Tests in `process-tests/src/test`:

| Test | Style | Covers |
| --- | --- | --- |
| `InvoiceApprovalTest` | Java | happy path, rejection, auto-approval (DMN), approval timeout (clock), archive error |
| `InvoiceDecisionTest` | Java, parameterized | the decision table, 7 rows |
| `InvoiceApprovalJsonTest` | JSON (`test-cases/*.json`, `test-cases-clock/*.json`) | the same paths and decision rows as JSON test cases |

## How it works

```
 ┌──────────────────────┐  deploy, start, assert,       ┌─────────────────────────┐
 │ process-tests (JUnit)│  complete user tasks,         │  Camunda 8 cluster      │
 │  CPT, runtime-mode:  │ ────────────────────────────► │  C8 Run  (default)      │
 │  remote              │  increase time, purge data    │  or SaaS                │
 └──────────────────────┘  (REST/gRPC 8080/26500,       │                         │
                            management API 9600)        │                         │
 ┌──────────────────────┐                                │                         │
 │ workers (Spring Boot)│ ◄───────── activate/complete jobs ─────────────────────┘
 │  external services   │
 └──────────────────────┘
```

* **Remote runtime.** `camunda.process-test.runtime-mode: remote` makes CPT connect to the running cluster instead of
  starting a Testcontainers runtime. No Docker is needed. See
  [`process-tests/src/test/resources/application.yaml`](process-tests/src/test/resources/application.yaml).
* **Workers are external.** The tests neither start nor mock the job workers
  (`camunda.client.worker.defaults.enabled=false`). The tests assert the *effects*: the process path and the
  variables the workers return (`archiveReference`, `accountingEntryId`, `rejectionNotificationId`).
* **Deployment.** `TestApplication` deploys the BPMN with `@Deployment`. CPT re-deploys it before each test because
  it purges the cluster between the tests.
* **Test isolation.** On C8 Run, CPT deletes **all** data of the cluster after each test (`POST /actuator/cluster/purge`
  on the management API). On a cluster where that is not possible (SaaS), the tests are isolated by a unique invoice ID
  and by assertions that are scoped to the started process instance.
* **Clock.** `increaseTime(Duration.ofDays(5))` (timer test) needs a cluster with a controllable clock. That is
  why `scripts/start-c8run.sh` enables it. The test is skipped for SaaS (`demo.clock-manipulation.enabled=false`).

## Prerequisites

* Java 21 and Maven 3.9+
* [`c8ctl`](https://github.com/camunda/c8ctl) (Camunda 8 CLI) to install and run C8 Run: `npm install -g @camunda8/cli`
* For SaaS: a cluster and an API client (see [Run on Camunda 8 SaaS](#run-on-camunda-8-saas))

## Run on a local C8 Run (default)

**1. Start the cluster** with a controllable clock. The script starts the version of `camunda.version` (`8.10.9` on this branch). `c8ctl` downloads it if needed.

```bash
scripts/start-c8run.sh
```

The script starts C8 Run and verifies that the clock is controllable. Without that, only the timer test fails.
Equivalent manual start: `CAMUNDA_SYSTEM_CLOCKCONTROLLED=true c8ctl cluster start 8.10.9`.

**2. Start the job workers** in a second terminal. They keep running across test runs.

```bash
mvn -pl workers spring-boot:run
```

**3. Run the tests**

```bash
mvn -pl process-tests test
```

All 21 tests pass (5 Java process tests, 7 decision rows, 9 JSON test cases). The timeout tests need the controllable clock.
A coverage report is written to `process-tests/target/coverage-report/report.html`.

Stop the cluster with `c8ctl cluster stop`.

> ⚠️ **CPT deletes all data of the cluster after every test.** Never point the tests at a cluster with data
> you care about. Use a dedicated test cluster (a separate C8 Run version/instance is enough). See
> [limitations](docs/CAPABILITIES.md#limitations-and-blockers).

## Run on Camunda 8 SaaS

SaaS has no management API (port 9600) and no controllable clock. The `saas` profile therefore turns off the data
deletion and the clock reset, increases the assertion timeout and skips the timer test.

1. In the SaaS console create an API client (Cluster → API) with the scopes `Zeebe`, `Tasklist`, `Operate`.
2. `cp .env.example .env`, fill in the values, and load them: `set -a; . .env; set +a`
3. Start the workers against SaaS (they stay running):

   ```bash
   SPRING_PROFILES_ACTIVE=saas mvn -pl workers spring-boot:run
   ```

4. Run the tests against SaaS:

   ```bash
   mvn -pl process-tests test -Psaas
   ```

> Status: the SaaS profile is **written but not verified** — no SaaS cluster was available when this demo was
> built. Treat it as a starting point. See [docs/CAPABILITIES.md](docs/CAPABILITIES.md) for what to expect.

## Configuration reference

| What | Where |
| --- | --- |
| Remote runtime, addresses, timeouts | `process-tests/src/test/resources/application.yaml` |
| SaaS overrides | `process-tests/src/test/resources/application-saas.yaml` |
| Worker connection (C8 Run / SaaS) | `workers/src/main/resources/application*.yaml` |
| Camunda version of the branch | `camunda.version` in the root `pom.xml` |

Any property can be overridden on the command line, for example
`mvn -pl process-tests test -Dcamunda.process-test.data-deletion-mode=none` (8.10+).

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Failed to connect to remote Camunda runtime` | The cluster is not running or not ready, or the addresses are wrong. Check `c8ctl cluster status`. |
| Timer test fails: *clock is immutable* / 403 | The cluster was started without a controllable clock. Use `scripts/start-c8run.sh`. |
| `Cluster is unhealthy` before a test | Known issue after `increaseTime` with `data-deletion-mode=none` (#62541). Reset the clock (`curl -X DELETE localhost:9600/actuator/clock`), purge (`curl -X POST localhost:9600/actuator/cluster/purge`) or restart the cluster. |
| Assertions time out waiting for a step | The workers are not running (or run against another cluster). Check the worker log. |
| Worker log shows `500 Internal Server Error` while activating jobs | Harmless: it happens while CPT purges the cluster. The workers recover. |
