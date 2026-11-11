# Running CPT tests on a remote environment — capabilities, limitations, version differences

Input for [camunda/camunda#64034](https://github.com/camunda/camunda/issues/64034). State: **2026-11-10**.
Versions: CPT / C8 Run **8.9.28**, **8.10.9**, **8.11.0-alpha1**. For 8.11 the demo also uses the **CPT `8.11.0-SNAPSHOT`** (build of 2026-11-10,
including the public clock API) against C8 Run 8.11.0-alpha1, until the next alpha is released. All verified with this demo.
Below, "8.11-alpha1" means the released alpha1 CPT jar, "8.11-SNAPSHOT" the snapshot.
**SaaS** was verified for 8.11 only: a cluster on the Camunda INT stage (`ultrawombat.com`, version 8.11.0-alpha1) with the 8.11-SNAPSHOT CPT.
8.10 and 8.9 were not run on SaaS.

How to read the claims:

| Mark | Meaning |
| --- | --- |
| ✅ **verified** | Run in this demo against a local C8 Run of the same version (`mvn test`: 21 tests on 8.11, 19 run on 8.10, 14 run on 8.9; Java, decision and JSON tests; external workers). Results marked ✅ for the 8.9 timer and JSON tests, and the purge results, were measured in an earlier setup of the demo (the tests deployed the resources and CPT purged the cluster); the demo's current default is described in limitation 12. The 8.11-SNAPSHOT runs against C8 Run 8.11.0-alpha1. C8 Run's default secondary storage is H2 (RDBMS exporter), unlike SaaS and typical self-managed clusters (Elasticsearch). |
| 🔎 **source** | Derived from the CPT source (tags `8.9.28`, `8.10.9`, `8.11.0-alpha1`, `main`), the released jars or the docs. Not run. |
| ☁️ **verified on SaaS** | Run against the INT-stage SaaS cluster above (8.11 only). |
| ❓ **unverified** | Plausible, but not tested — mostly because no SaaS cluster / secured self-managed cluster was available. |

## TL;DR

1. **It works.** CPT with `runtime-mode: remote` runs the invoice tests against a running cluster with the job workers in a
   separate application — on 8.9.28, 8.10.9 and 8.11.0-alpha1 (✅). No Docker needed.
2. **The blocker for a shared QA environment is the cleanup, not the connection.** By default CPT wipes the *whole cluster*
   after every test (`POST /actuator/cluster/purge`) and manipulates the *cluster-wide* clock. Both are fine on a dedicated
   C8 Run, and destructive on a cluster used for manual/acceptance testing.
3. **8.10+ can switch the cleanup off** (`data-deletion-mode: none`, `clock-reset-enabled: false`). **8.9 cannot** — the
   customer's 8.9 environment needs an upgrade, a backport, or a dedicated test cluster. **Switching it off is mandatory when the process application owns the
   deployed resources** (as in this demo): the purge deletes the deployments too (✅ limitation 12).
4. **Clock manipulation and "no purge" don't mix on 8.10 and in the 8.11.0-alpha1 jar**: after `increaseTime`, the next test
   fails with `Cluster is unhealthy` (#62541, ✅ reproduced). The public clock API (#23160, CPT PR #66862, merged to `main` on
   2026-10-28, after the alpha1 release candidates) fixes it — it is **not in the 8.11.0-alpha1 CPT jar** (✅ it still calls
   `/actuator/clock`), but **in 8.11.0-SNAPSHOT: ✅ all 21 tests pass with `data-deletion-mode: none` and `increaseTime`.**
5. **SaaS works for everything except the clock (☁️ 8.11).** Against a real SaaS cluster (8.11.0-alpha1, `mode: saas`, external workers on the same
   cluster, `data-deletion-mode: none`) 19 of 21 tests pass; the 2 timer tests are the exception (see limitation 10). A second run on top of the
   data of the first run passes as well (☁️): unique IDs, instance-scoped selectors and the start-time isolation (via `GET /v2/clock`) kept the runs apart (that run used the instance-scoped selectors; the tests were simplified afterwards, see limitation 6).
   Don't use `increaseTime` on SaaS: with Elasticsearch it stalled indexing of the cluster (limitation 10; reproduced locally with Elasticsearch).
6. **With the 8.11-SNAPSHOT the whole suite, including the timer tests, ran without the management API and without a
   controllable-clock flag** (✅ local cluster, management port set to a closed port, purge off). That is the best case for a
   shared environment; SaaS itself is still ❓ unverified.
7. **Don't mix CPT job-worker mocks with real workers for the same job type.** They compete (✅ 5 of 10 jobs went to the mock).
8. **One test run per cluster, exclusively.** CPT doesn't support parallel tests; purge, clock and the coverage report work on the whole cluster (limitation 11). Serialize the CI job and don't share the cluster.
9. **Isolation without a purge comes from CPT's start-time scoping (8.10+).** Plain selectors are enough there (✅ 21/21, twice in a row on `main`). 8.9 has no such scoping:
   it needs selectors scoped to the process instance (✅ failed without them).

## What a remote run needs

| Requirement | 8.9.28 | 8.10.9 | 8.11-alpha1 | 8.11-SNAPSHOT | Notes |
| --- | --- | --- | --- | --- | --- |
| `camunda.process-test.runtime-mode: remote` | ✅ | ✅ | ✅ | ✅ | |
| Runtime version ≥ CPT version | ✅ | ✅ | ✅ | ✅ (C8 Run alpha1 with a newer snapshot works) | Docs: "CPT doesn't support older Camunda runtimes". The demo pins both to the same version. |
| Client address/credentials: `camunda.client.*` | ✅ | ✅ | ✅ | ✅ | 8.9.0 used `camunda.process-test.remote.client.*`, deprecated since 8.9.1. |
| Management API (port 9600) reachable | ✅ needed | ✅ needed¹ | ✅ needed¹ | only for the purge² | Purge (all versions) and the clock (≤ alpha1). |
| Cluster clock controllable (`CAMUNDA_SYSTEM_CLOCKCONTROLLED=true` or `ZEEBE_CLOCK_CONTROLLED=true`) | ✅ needed | ✅ needed | ✅ needed | ❌ **not needed** (✅ legacy endpoint stayed `403`, timer tests passed) | Only for `increaseTime`/`setTime`. Verified: without it `DELETE /actuator/clock` returns `403 … the clock is immutable`; both variable names work on all three versions. The public clock API of the 8.11 runtime replaces it. |
| Authorizations on `SYSTEM` (READ, UPDATE) for the CPT user | – | – | – | 🔎 needed (docs); ❓ not tried | Needed because CPT uses the public clock API. Only relevant when authorizations are enabled. |
| Cluster used **exclusively** by CPT (no parallel test runs, no other work) | needed | needed | needed | needed | see limitation 11 |
| Process resources deployed by the process application, not by the tests | ✅ needs the workaround of limitation 12 | ✅ with `data-deletion-mode: none` | ✅ same | ✅ same | The tests don't deploy; without the deployment, creating an instance is rejected with a clear error. |
| Job workers in a separate app | ✅ | ✅ | ✅ | ✅ | The test module contains no workers. |

¹ Not needed if `data-deletion-mode: none` **and** `clock-reset-enabled: false` **and** no `increaseTime` — but then CPT logs a
warning at every test start and disables the start-time isolation (✅ see limitation 6).

² With `data-deletion-mode: none` it isn't needed at all (✅).

## Capability matrix

The first four columns ran on a dedicated local C8 Run (the process application deployed the resources, the tests ran twice in a row without purge) (8.11-SNAPSHOT: CPT snapshot against C8 Run 8.11.0-alpha1). Shared/SaaS = a cluster that must not be wiped. SaaS columns are 🔎/❓ only.

| Capability | C8 Run 8.9 | C8 Run 8.10 | 8.11-alpha1 | 8.11-SNAPSHOT | Shared / SaaS |
| --- | --- | --- | --- | --- | --- |
| Process resources deployed by the process application (`@Deployment`), tests start instances | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| Process-instance and user-task assertions | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11, with `assertion.timeout` 30 s |
| Complete user tasks (`completeUserTask`) | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| External job workers do the work, test asserts the result variables | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| BPMN error from an external worker → error boundary event | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| Timer test with `increaseTime(5 days)` | ✅ | ✅ | ✅ | ✅ (also without management API, ✅) | ❌ no clock on SaaS ≤ 8.10 (#23160). 8.11: ☁️ `increaseTime` works, but ❌ stalls Elasticsearch indexing for the size of the jump (limitation 10) |
| DMN: deploy, business rule task, evaluate decision, `assertThatDecision` | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| JSON test cases (`@TestCaseSource`, `TestCaseRunner`) incl. `ASSERT_VARIABLES`, `EVALUATE_DECISION`, `INCREASE_TIME` | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 passed, ⚠️ fixed data, selectors by definition ID (see 6) |
| Coverage report (HTML + JSON, BPMN and DMN) per test class | ✅ | ✅ | ✅ | ✅ scoped to the test data (see 6) | ☁️ 8.11: generated, scoped to the test data |
| Clean slate per test (purge) | ✅ default | ✅ default | ✅ default | ✅ default | ❌ **destructive**; SaaS has no management API, use `data-deletion-mode: none` (☁️ works) |
| Switch purge off | ❌ no switch (✅ logs `Failed to delete the runtime data, skipping`) | ✅ `data-deletion-mode: none` | ✅ same | ✅ same | needed |
| Test-case isolation by start time | ❌ (not in the jar) | ✅ source + ✅ warning | ✅ same | ✅ works without management API (clock read via REST; no warning). ☁️ also on SaaS: the second run on leftover data passed | ⚠️ ≤ alpha1: falls back to *off* when the management API is unreachable (✅ warning) |
| Clock + "no purge" together | ✅ n/a (purge always on) | ❌ #62541 ✅ reproduced | ❌ ✅ reproduced (alpha1 jar) | ✅ fixed (public clock API) | ✅ with the 8.11 snapshot on a local cluster. ☁️ **SaaS / Elasticsearch: ❌ indexing stalls** (limitation 10); H2 (C8 Run): ✅ |
| `mockJobWorker` for a type that real workers also serve | ⚠️ competes ✅ | ⚠️ | ⚠️ ✅ | ⚠️ | ⚠️ |
| `mockChildProcess`, `mockDmnDecision` | 🔎 deploy stub resources | 🔎 | 🔎 | 🔎 | ⚠️ stubs stay on the cluster without purge |
| Connectors | 🔎 run in the cluster's connector runtime, CPT doesn't start them | 🔎 | 🔎 | 🔎 | 🔎 same |
| Secrets for connectors | 🔎 cluster owns them | 🔎 `cluster-secrets` ignored + warning | 🔎 same | 🔎 same | 🔎 same |
| Multi-tenancy toggle of CPT | 🔎 ignored + warning (remote) | 🔎 | 🔎 | 🔎 | 🔎 |
| Authenticated cluster (Basic, OIDC) | ❓ via `camunda.client.auth.*` (docs) | ❓ | ❓ | ❓ | ☁️ OAuth client credentials (SaaS) 8.11 |

## Limitations and blockers

Ordered by impact for the issue's scenario (shared QA cluster on self-managed Kubernetes/OpenShift, GitLab CI, 8.9 → 8.11,
custom connectors).

### 1. CPT deletes all data of the cluster after each test — ✅ verified
A process instance started by someone else before the test run was gone afterwards (`POST /actuator/cluster/purge`,
all versions). Deployments, instances, incidents, everything.

* The purge also deletes the **deployed BPMN/DMN** (✅ limitation 12): the process application's deployments are gone after the first test.
* 8.9: **no way to switch it off.** If the management API is unreachable, CPT only logs `Failed to delete the runtime data,
  skipping` and carries on (✅), so a "no purge" run is possible by accident (point the address to a closed port, as the 8.9
  branch does by default, see limitation 12) but it's not a feature.
* 8.10+: `camunda.process-test.data-deletion-mode: none`.
* Danger: the default monitoring address is `http://localhost:9600`. A mis-configured run can purge an unrelated local cluster.
* Next step: use a dedicated cluster for CPT, or run with `none` plus the isolation rules below.

### 2. The clock is cluster-wide — ✅ verified
`increaseTime(5 days)` made the timer of an *unrelated* process instance fire (it completed). On a shared cluster
every process with a timer, job timeout, message TTL or backoff is affected. Only use clock manipulation on a dedicated test cluster.
No clock manipulation on SaaS (≤ 8.10, #23160). With the public clock API (8.11) the clock stays cluster-wide: the effect on other instances is the same. With an Elasticsearch exporter it also delays indexing (limitation 10).

### 3. Clock manipulation breaks the next test when the data is not purged (#62541) — ✅ reproduced on 8.10.9 and the 8.11.0-alpha1 jar, ✅ fixed in 8.11.0-SNAPSHOT
Setting `data-deletion-mode: none` and calling `increaseTime`: the next test fails in `beforeTestMethod` with
`Cluster is unhealthy … health=UNHEALTHY` (`actor appears blocked … valueType=JOB_BATCH`). The cluster stays `DOWN` even after
the clock reset (`DELETE /actuator/clock` → 200); a purge (or restart) recovers it (✅).

* Issue status: closed 2026-11-09. "Mitigated in 8.11 by switching to the new public clock API; won't be fixed in 8.10."
* The released **8.11.0-alpha1 CPT jar still uses `/actuator/clock`**: PR #66862 (public clock API in CPT) was merged on
  2026-10-28, after the alpha1 release candidates (`rc2`: 2026-10-27) and before the 2026-11-02 release, but not included. With that
  jar against C8 Run 8.11.0-alpha1 the failure reproduces (✅).
* **With CPT `8.11.0-SNAPSHOT` (2026-11-10) against C8 Run 8.11.0-alpha1 it is fixed (✅):** all 21 tests, including both timer tests, pass
  with `data-deletion-mode: none`, and the cluster stays healthy. The runtime part of the public clock API (`GET /v2/clock`, `POST /v2/clock/add`,
  `POST /v2/clock/reset`) was already in C8 Run 8.11.0-alpha1.
* Workaround (8.10, 8.11-alpha1 jar): no `increaseTime` together with `none`. Test timers on a dedicated cluster with purge,
  or make the timer duration a process variable and use short durations on the shared cluster.

### 4. Job workers: stale REST long-polling with `data-deletion-mode: none` (#62521, open) — ❓ not reproduced
Workers that run **in the test JVM** (Spring Boot default `@JobWorker`s, CPT mocks) keep their long-poll request across tests and can
take jobs that belong to the next test. Workaround: `camunda.client.zeebe.prefer-rest-over-grpc=false`. **This demo is
not affected by design**: the workers are external and never see a test boundary. It *is* a reason to keep workers
out of the test JVM.

### 5. CPT mocks and real workers compete for jobs — ✅ verified (8.11.0-alpha1 jar)
`processTestContext.mockJobWorker("add-invoice-to-accounting")` while the external worker was running: 5 of 10 jobs went
to the mock, 5 to the real worker. On a remote environment with real workers/connectors, a job type is served either by the
real worker **or** by a mock — not both.

* Replacing the HTTP connector by a worker (this demo) means no connector job has to be mocked (`mockJobWorker("io.camunda:http-json:1")`). If you do want to mock the connector on a shared cluster, you must stop the connector runtime for that
  job type (impossible on SaaS), or add a test-specific BPMN variant.
* Idea for a feature: scope a mock to the process instances of the test (job filter by instance key / tag).

### 6. Isolation without a purge: start-time scoping (8.10+), instance-scoped selectors (8.9) — ✅ verified
Without a purge, the data of earlier runs and tests stays on the cluster, and selectors like `byElementId("UserTask_ApproveInvoice")` could match it.

* **8.10+:** CPT limits its queries (assertions, utilities such as `completeUserTask`, the coverage report) to the data created after the test case started
  (🔎 `testCaseStartTime`). **Plain selectors are enough:** the tests of `main` use `byElementId("UserTask_ApproveInvoice")` and pass 21/21, twice in a row on the same
  cluster without purge (✅ the second run starts with the data of the first).
* In 8.10 and the 8.11.0-alpha1 jar, CPT reads the cluster time for that through the management API; if it can't, it logs *"Test case isolation by start time will be
  disabled for this test case"* (✅) and the queries are cluster-wide again. Then instance-scoped selectors are needed (`byElementId("…", processInstanceKey)`).
  The 8.11 snapshot reads the time through the public clock API and keeps the isolation without the management API (✅ no warning).
* **8.9:** no start-time scoping (absent in the 8.9.28 jar). Without a purge, `assertThatUserTask(byElementId("UserTask_ApproveInvoice"))` matched a task from an earlier test
  (*"Expected … to be created, but was completed"*, ✅) — and `completeUserTask(byElementId(…))` could complete a stranger's task. The `8.9` branch scopes the selectors to the
  process instance and uses unique invoice IDs.

**The coverage report uses the same scoping** (✅ 8.11 snapshot, local and ☁️ SaaS): CPT writes `report.html` and `report.json` (to `target/coverage-report`, plus a summary per
test class in the log) from the data of the test run. Proof: with 20 process instances left on the cluster (`none`), a run of only the decision tests produced a
report with the decision coverage and **no process coverage**; and the 2 consecutive full runs both listed exactly the 21 test cases of their own run. Without start-time
isolation (8.9 jar, or when the clock can't be read) the report would include everything on the cluster (🔎 not run; on 8.9 the purge keeps the cluster empty anyway).

### 7. Management API (9600) must be reachable for the purge and (≤ 8.11-alpha1 jar) the clock — 🔎/❓
On Kubernetes/OpenShift that's the Camunda pod's management port: a Service/Route or `kubectl port-forward`. It is
unauthenticated on C8 Run; on a secured cluster the actuator endpoints may need other protection — ❓ **to verify on the
target environment**. With `none` + `clock-reset-enabled: false` + no `increaseTime`, the connection is not needed
(✅ simulated with a closed port on 8.10.9 and 8.11.0-alpha1). **With the 8.11 snapshot, `none` alone is enough**, even with
`increaseTime` (✅ closed port, 21 tests passed).

### 8. Eventual consistency — 🔎
Assertions query the secondary storage, which is filled asynchronously. They retry until `camunda.process-test.assertion.timeout`
(default 10 s). Remote/SaaS needs more (the `saas` profile uses 30 s). ☁️ A suite run on SaaS takes ~1.5-2 min vs. ~45 s locally (JSON tests 33-51 s vs. 23 s).

### 9. Smaller points
* **8.9 dependency conflict** (✅): with the Spring Boot 4.0 BOM, the 8.9.28 client fails with
  `NoSuchMethodError: HttpAsyncClientBuilder.disableContentCompression()`; pin `httpclient5` 5.6.5 and `httpcore5(-h2)` 5.4.4
  in `dependencyManagement` (the `8.9` branch does; a property override of an imported BOM doesn't work).
* Workers log `500 Internal Server Error` while activating jobs during the purge, then recover (✅, harmless).
* The `-parameters` compiler flag is needed for `@Variable` without a name when you don't use the Spring Boot parent (✅).
* 8.11.0-alpha1 is an alpha, and `main` of this demo uses a **moving snapshot** for CPT and the client (`cpt.version` in the root `pom.xml`). Re-run
  after the next alpha and switch `cpt.version` to the release. Snapshots come from `https://artifacts.camunda.com/artifactory/public`.
* `mockDmnDecision("auto-approve-invoice", …)` would deploy a stub DMN with the *same* decision ID (🔎). Without purge, it replaces the real
  decision for all later instances. The demo doesn't use it; it evaluates the real decision.
* JSON test cases (✅ on 8.10 and 8.11; switched off on the 8.9 branch with `demo.json-tests.enabled=false`): they use fixed data and select by process definition ID (`processDefinitionId`), not by
  instance — so on a shared cluster they depend on the cleanup/start-time isolation, unlike the Java tests. The example's
  `MOCK_JOB_WORKER_*` instructions were dropped (they'd compete with the external workers, limitation 5). The schema for 8.11
  (`…/cpt-test-cases/8.11/schema.json`) is not published yet (404); the files reference the 8.10 schema.
* Secrets: CPT's `connectors-secrets` / `cluster-secrets` are ignored for remote runtimes (🔎, warning since 8.10).
* SaaS specifics (☁️): the Spring SDK `saas` mode points to `camunda.io`; for another stage set `camunda.client.cloud.domain` (here `ultrawombat.com`) —
  token URL, audience and addresses follow. Explicit `rest-address`/`grpc-address` in the base `application.yaml` also override the SaaS mode (a bug of this
  demo, fixed). The credentials file of the console also exports `ZEEBE_*` variables: the SDK logs deprecation warnings for them.
* Not verified: SaaS with 8.10/8.9, authenticated self-managed clusters incl. the `SYSTEM` authorizations,
  OpenShift, the 8.11 final CPT. Parallel test execution is not supported (limitation 11), so it was not tried.

### 10. A clock jump delays the Elasticsearch exporter by the size of the jump — ✅ reproduced locally with Elasticsearch; ☁️ observed on SaaS (8.11)
**Symptom.** With the timer tests enabled (`increaseTime(5 days)`, then CPT resets the clock), every following test fails with "No process instance / user task /
decision instance … found". The engine keeps working (external workers process new instances, the topology is `healthy`), but the **search index receives
nothing new**. ☁️ On the INT-stage SaaS cluster (8.11.0-alpha1) nothing newer than the timer test's instance was indexed for 21+ minutes (checked until 12:09Z).

**Cause: the secondary storage / exporter, not CPT.** Confirmed by a reproduction (the hint that SaaS uses Elasticsearch, unlike C8 Run's H2, was right):

| Setup (local, C8 Run 8.11.0-alpha1, snapshot CPT) | Result |
| --- | --- |
| Default: H2 database + RDBMS exporter | ✅ 21/21 with `none` and timer tests; index delay after a +120 s jump and reset: **1.0 s** (no delay) |
| Elasticsearch 8.19.14 + Elasticsearch exporter, no timer tests (control) | ✅ 19 passed, 2 skipped |
| Elasticsearch, timer tests on (same suite as on SaaS) | ❌ the timer test passes, **the 20 other tests fail**; ES holds only the instances up to the timer test, no exporter errors in the log, exporter `ENABLED` |
| Elasticsearch, +120 s jump, then reset | indexing normal while jumped (1.0 s); **an instance created right after the reset appeared in the index after 121.4 s** — the size of the jump |
| Elasticsearch, 600 more instances after the stall | ✅ the index caught up (523 of 600 visible) — the **batch-size flush still works**, the **time-based flush does not** |

**Interpretation (❓ not verified in the exporter source).** The exporter flushes its batch on a timer that runs on the broker's clock. A jump forward moves the next
flush into the future; after the reset the clock goes back, and the next flush is due only when real time reaches the jumped time. Small batches (a test suite)
stay unflushed until then. With a +5 day jump that means **up to 5 days**. For the SaaS cluster used here the index would then recover around **2026-11-15 11:48Z**,
or earlier after a restart (❓ not tried; no purge on SaaS).

**Consequences**
* **Do not manipulate the clock on any cluster with an Elasticsearch/OpenSearch exporter that you cannot restart quickly: SaaS and probably every self-managed
  Kubernetes/OpenShift cluster** (their default storage is Elasticsearch/OpenSearch). This probably holds for the old management API on 8.10/8.9 as well
  (❓ not tried; the mechanism is in the exporter). The `saas` profile keeps the timer tests off.
* The same mechanism may be part of why #62541 showed an unhealthy partition (❓).
* If a clock test must run on such a cluster: keep the jump small (the delay equals the jump) and make the timer durations in the test configurable.
* Candidate for an issue: the exporter's flush timer should not depend on the (manipulated) clock, or the clock reset should reschedule it.

### 11. No parallel tests: the cluster must be used exclusively by CPT — 🔎 given by the CPT design, consistent with what we measured
CPT doesn't support running tests in parallel, and a remote cluster used for integration tests should belong to CPT **alone** while the tests run:
* the cleanup (purge) and the clock reset/jump are **cluster-wide**, so one test run disturbs any other test run or person using the cluster (✅ limitation 1 and 2);
* the assertions are scoped by the *start time of the test case* and by what the test selects, not by "who created the data" — concurrent runs on one cluster see
  each other's data in time-scoped queries, coverage reports and process-definition selectors (JSON test cases select by `processDefinitionId`);
* the assertion data source of CPT is global state (`CamundaAssert.initialize(dataSource)` in the execution listener, 🔎 source), so also two test classes in one JVM can't run concurrently.

Consequences for CI (e.g. GitLab): run the CPT job **serialized** per cluster (for example a `resource_group`), one cluster per pipeline/branch if pipelines must run concurrently, and
don't use that cluster for manual/acceptance testing at the same time. This also settles the "shared QA cluster" question of the issue: *shared with people or other
pipelines* doesn't work reliably — use a dedicated CPT cluster, or accept the limits above (`none`, start-time scoping or instance-scoped selectors, no clock) on a cluster that nobody else uses at that moment.

### 12. Who owns the process resources: the process application, not the tests — ✅ verified (8.9, 8.10, 8.11)
In an integration scenario the **process application deploys and owns the BPMN/DMN** (here: module `process-application`, `@Deployment` at startup). The tests
only verify the process. If the tests deployed their own copy (as the CPT example does with `@Deployment` in the test application), every test run would deploy
resources to the cluster: a changed or stale copy **overrides the version the process application deployed** (a new latest version that other users and the real
workers run against). The demo's tests therefore **don't deploy** and have no extra check: if the process application hasn't deployed the resources, the first command fails at once
with `Command 'CREATE' rejected with code 'NOT_FOUND': Expected to find process definition with process ID 'Process_InvoiceApproval', but none found` (✅ the same for the
Java tests, the JSON test cases and the decision tests: `… no decision found for id …`).

* **The default purge is incompatible with this.** With `data-deletion-mode: cluster_purge` the first test passes, the purge deletes the deployments, and the other tests
  fail (✅ 6 of 7 decision tests: *"no decision found for id 'auto-approve-invoice'"*; the decision definitions on the cluster went from 1 to 0). So the integration setup needs
  `data-deletion-mode: none` (8.10+) and relies on the isolation rules of limitation 6.
* **8.9** has no switch. The 8.9 branch points the management API address to a closed port: CPT logs `Failed to delete the runtime data, skipping` and keeps
  the data (✅ 14 tests, run twice on the same cluster; the deployed version stayed 1). Consequences (by design of this workaround): **no clock manipulation** (it uses the same
  address) and **no JSON tests** (no start-time isolation; they select by process definition ID and see the data of earlier runs). An upgrade or a backport of
  `data-deletion-mode` is the real fix.
* **8.10**: `none` works, but timer tests (`increaseTime`) are off by default: together with `none` they leave the cluster unhealthy (#62541). 19 of 21 tests run
  (✅ twice on the same cluster).
* **8.11 (snapshot)**: everything incl. timer tests, 21 of 21 (✅ twice on the same cluster, process version stayed 1).
* Not chosen: tests that deploy the *same* resources taken from the process application artifact. Identical content creates no new version, but a changed model
  would still be deployed by the tests, and the purge would still force a redeploy before every test.

## Version differences

### 8.11 (`main`: C8 Run 8.11.0-alpha1 + CPT 8.11.0-SNAPSHOT; the alpha1 jar was verified as well)

* **8.11.0-alpha1 jar:** works like 8.10 (the CPT remote runtime classes are identical in `8.10.9` and `8.11.0-alpha1`), including #62541.
* **Public clock API (snapshot, PR #66862).** The runtime of 8.11.0-alpha1 already has `GET /v2/clock`, `POST /v2/clock/add` and
  `POST /v2/clock/reset` (✅ `GET`, `add` (`{"offset":"PT120S"}`, an ISO duration) and `reset` work *without* the controllable-clock flag). The CPT snapshot uses them through the
  Camunda client (`CamundaClockClientImpl`). Verified consequences (✅ local C8 Run 8.11.0-alpha1):
  * no `zeebe.clock.controlled` / `CAMUNDA_SYSTEM_CLOCKCONTROLLED` flag needed (the legacy endpoint stayed `403`, timer tests passed),
  * #62541 is fixed (`increaseTime` + `data-deletion-mode: none`),
  * no management API needed for the clock or the start-time isolation; with `data-deletion-mode: none` the suite ran with the management
    port closed.

  Per the 8.11 docs, with authorizations enabled the CPT user needs `READ` + `UPDATE` on `SYSTEM` (🔎/❓ not tried).
* Purge is unchanged (`POST /actuator/cluster/purge`, management API). Remaining SaaS blocker: purge → use `data-deletion-mode: none`.
* New in the docs vs 8.10: utilities to complete user tasks with dynamic variables / a variables table (🔎).

### 8.10 (`8.10` branch, 8.10.9 verified)

* In this demo: `data-deletion-mode: none`, timer tests off by default (#62541), 19 of 21 tests (limitation 12).
* New vs 8.9: `data-deletion-mode` (`CLUSTER_PURGE` | `NONE`), `clock-reset-enabled`, `query-page-limit`, test-case start-time
  isolation, `cluster-secrets` (8.10.6+; ignored with a warning in remote mode), semantic similarity, update-variables utilities.
* Clock: management API (`/actuator/clock`), needs the controllable-clock flag.
* Known issues: #62541 (✅ reproduced, won't fix in 8.10; fixed in the 8.11 snapshot), #62521 (open).

### 8.9 (`8.9` branch, 8.9.28 verified)

* In this demo: closed management API address to avoid the purge, no timer and JSON tests, 14 tests with 3 skipped by design (limitation 12).
* Remote runtime supported; clock via management API (flag needed); **purge cannot be switched off**; no start-time isolation (✅ tests
  fail on a dirty cluster without scoped selectors); no `data-deletion-mode`, `clock-reset-enabled`.
* Connectors REST address default `8086` and the `camunda.client.*` client properties since 8.9.1.
* Spring Boot 4.0.x and the `httpclient5` pin (see above).

## Next steps (proposal for #64034)

**Docs** (the issue's technical requirements)
1. Add a page "Integration tests on a remote / shared environment" to the CPT docs (remote runtime section): dedicated cluster
   requirement, what the cleanup deletes, clock effects, scoping rules (limitations 1, 2, 6), external workers vs. mocks (5),
   SaaS configuration. Use this repo as the worked example.
2. Align the [Process OS CPT guide](https://github.com/camunda/process-os/blob/main/shared/references/camunda-testing-guide.md#integration-tests--camunda-saas):
   it shows SaaS only with `mode: saas`; it should add `data-deletion-mode: none` / `clock-reset-enabled: false` (8.10+), the
   missing 8.9 option, and the scoping rules.

**Product** (decide, then open issues)
3. 8.9 customers: backport `data-deletion-mode` and `clock-reset-enabled` to 8.9.x, or recommend 8.10+ / a dedicated test cluster.
4. Re-run the demo with the first 8.11 alpha that contains PR #66862 (switch `cpt.version` from the snapshot to the release), and decide
   whether to fix #62541 for 8.10.
5. Make a purge of a *remote* cluster explicit (e.g. warn, or require an opt-in) — today it is the default and the docs say
   only "keep in mind".
6. Scope assertions/utilities/mocks to the process instances created by the test (limitations 5, 6) so shared-cluster tests are safe by default.

**Validation still open**
7. Report limitation 10 (the Elasticsearch exporter's time-based flush is delayed by a clock jump) as an issue with the reproduction above; until then the timer tests stay off on SaaS and on shared Elasticsearch clusters.
   Run clock tests on Elasticsearch in CI too, not only on C8 Run with H2.
   Run the demo against an OpenShift cluster with
   authentication: management API access, `authorizations`, outbound connector runtime, GitLab CI job.
8. Document that parallel test execution is not supported and the cluster must be exclusive (limitation 11), including the CI guidance. A guard (for example a lock on the cluster, or a warning when other process instances exist) would make misuse visible.
