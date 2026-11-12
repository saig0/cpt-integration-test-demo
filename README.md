# CPT integration test demo

Demo for running [Camunda Process Test (CPT)](https://docs.camunda.io/docs/apis-tools/testing/getting-started/)
tests against an **already running Camunda 8 cluster**, while the **process application runs as a separate application**
that owns the process resources (BPMN, DMN) and hosts the job workers. It mimics the setup of an integration environment in
which the process application, the external services and custom connectors are deployed independently, and the process tests
verify the deployed process and invoke the real workers instead of mocking them.

> **Branch `8.9` — Camunda `8.9.28`.** The other versions live in the branches [`main`](../../tree/main) (C8 Run `8.11.0-alpha1` with CPT `8.11.0-SNAPSHOT`) and [`8.10`](../../tree/8.10) (`8.10.9`). What works in which version, the limitations and the
> recommended next steps are collected in **[docs/CAPABILITIES.md](docs/CAPABILITIES.md)**.
>
> How to set it up (cluster, process application, test project, configuration) — one blueprint per version: **[8.11](docs/blueprint-8.11.md)**, **[8.10](docs/blueprint-8.10.md)**, **[8.9](docs/blueprint-8.9.md)**.

## What is in here

```
.
├── process-application/  Spring Boot app: owns and deploys the BPMN and DMN, hosts the job workers (the "external services"). Started independently.
├── process-tests/        CPT tests (Java + JSON). Connects to a remote cluster. No process resources and no job workers inside.
├── docs/             Capabilities, limitations and version differences, a blueprint per version
└── .env.example      SaaS credentials template
```

The process is an invoice approval process with the DMN decision `auto-approve-invoice`. It is based on the invoice example of the
[CPT example project](https://github.com/camunda/camunda/tree/main/testing/camunda-process-test-example). 

![Process diagram](docs/invoice-approval-process.png)

Tests in `process-tests/src/test`:

| Test | Style | Covers |
| --- | --- | --- |
| `InvoiceApprovalTest` | Java | happy path, rejection, auto-approval (DMN), approval timeout (clock), archive error |
| `InvoiceDecisionTest` | Java, parameterized | the decision table, 7 rows |
| `InvoiceApprovalJsonTest` | JSON (`test-cases/*.json`, `test-cases-clock/*.json`) | the same paths and decision rows as JSON test cases |

## How it works

* **Remote runtime.** `camunda.process-test.runtime-mode: remote` makes CPT connect to the running cluster instead of
  starting a Testcontainers runtime. No Docker is needed. See
  [`process-tests/src/test/resources/application.yaml`](process-tests/src/test/resources/application.yaml).
* **The process application owns the resources.** The BPMN and DMN live in `process-application` and are deployed by it at startup
  (`@Deployment`). **The tests don't deploy anything**: they verify the process as deployed. That way a test run can't override what the process application has deployed.
* **Workers are external.** The tests neither start nor mock the job workers
  The tests assert the *effects*: the process path and the variables the workers return.
* **No cleanup, isolation by scoping.** CPT's default cleanup purges **all** data of the cluster after each test,
  including the resources that the process application deployed — the tests would fail from the second test on.
  The tests therefore avoid the purge (see below) and isolate themselves with a unique invoice ID per test,
  selectors that are scoped to the started process instance (CPT 8.9 has no start-time scoping).
* **No cleanup possible on 8.9.** CPT 8.9 has no `data-deletion-mode`: it always purges the cluster through the management API (port 9600), which would delete the
  resources of the process application. `application.yaml` therefore points the management API address to a **closed port**: CPT logs `Failed to delete the runtime data,
  skipping` after each test and keeps the data. The price: **no clock manipulation** (CPT 8.9 uses the same address) and **no JSON tests** (CPT 8.9 has no start-time
  isolation, the JSON test cases select by process definition ID). Both are switched off (`demo.clock-manipulation.enabled`, `demo.json-tests.enabled`).
* **Versions.** The tests, the process application and C8 Run use `camunda.version` (`8.9.28`). Spring Boot 4.0 with a pinned `httpclient5` (see the root `pom.xml`).

## Prerequisites

* Java 21 and Maven 3.9+
* The [c8 CLI](https://github.com/camunda/c8ctl) to install and run C8 Run: `npm install -g @camunda8/cli`
* For SaaS: a cluster and an API client (see [Run on Camunda 8 SaaS](#run-on-camunda-8-saas))

## Run on a local C8 Run (default)

**1. Start the cluster** with the c8 CLI. It downloads the version on the first start. CPT 8.9 needs the clock control enabled:

```bash
CAMUNDA_SYSTEM_CLOCKCONTROLLED=true c8 cluster start 8.9.28
```

The environment variable makes the clock of the cluster controllable (CPT 8.9 changes the clock through the management API). The timer tests are **off** on this
branch: the management API address is closed on purpose (see above), so the clock can't be changed. The variable only matters if you restore the address to experiment.

**2. Start the process application** in a second terminal. It deploys the BPMN/DMN and runs the job workers. It keeps running across test runs. It is ready when `http://localhost:8090/actuator/health/readiness` reports `UP`.

```bash
mvn -pl process-application spring-boot:run
```

**3. Run the tests**

```bash
mvn -pl process-tests test
```

11 tests run and pass (4 Java process tests, 7 decision rows); 3 are skipped by design (the timer test and the JSON test cases).

A coverage report (HTML and JSON, BPMN and DMN) is written to `process-tests/target/coverage-report/report.html`. Open the HTML in a
browser to see which paths and decision rows were executed. CPT 8.9 has no start-time scoping: on a cluster that is not purged the report may include the data of earlier runs.

Then, stop the process application and the cluster. 

```bash
c8 cluster stop
```

## Run on Camunda 8 SaaS

SaaS has no management API (port 9600). The default configuration of this branch already avoids the purge by pointing the management API address to a closed port. The `saas` profile
increases the assertion timeout. **Not verified on SaaS** for 8.9 (only 8.11 was).

1. In the SaaS console create an API client (Cluster → API) with the scopes `Orchestration Cluster API`.
2. `cp .env.example .env`, fill in the values (the file is gitignored), and load them: `set -a; . .env; set +a`. For a stage other than production set
   `CAMUNDA_CLUSTER_DOMAIN` (for example `ultrawombat.com` for INT). The credentials file of the console (`export …`) works as well: `set -a; . <file>; set +a`.
 
   ```bash
   cp .env.example .env
   ```

3. Start the process application against SaaS (it stays running):

   ```bash
   set -a; . .env; set +a
   SPRING_PROFILES_ACTIVE=saas mvn -pl process-application spring-boot:run
   ```

4. Run the tests against SaaS:

   ```bash
   set -a; . .env; set +a
   mvn -pl process-tests test -Psaas
   ```

> Status: **not verified on SaaS for 8.9**. On 8.11 (main) it was verified against a cluster on the Camunda INT stage; see the README of `main` and [docs/CAPABILITIES.md](docs/CAPABILITIES.md).

## Configuration reference

| What | Where |
| --- | --- |
| Remote runtime, addresses, timeouts | `process-tests/src/test/resources/application.yaml` |
| SaaS overrides | `process-tests/src/test/resources/application-saas.yaml` |
| Process application: connection (C8 Run / SaaS) | `process-application/src/main/resources/application*.yaml` |
| Camunda version of the branch | `camunda.version` in the root `pom.xml` |

Any property can be overridden on the command line, for example
`mvn -pl process-tests test -Dcamunda.process-test.assertion.timeout=PT30S`.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Failed to connect to remote Camunda runtime` | The cluster is not running or not ready, or the addresses are wrong. Check `c8 cluster status`. |
| `Command 'CREATE' rejected with code 'NOT_FOUND': Expected to find process definition with process ID 'Process_InvoiceApproval', but none found` | The process application has not deployed the resources. Start it first (it deploys the BPMN/DMN). Also happens after a purge of the cluster: restart the process application. |
| Assertions time out waiting for a step | The process application (workers) is not running, or runs against another cluster. Check its log. |
