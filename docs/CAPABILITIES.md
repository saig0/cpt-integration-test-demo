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
| ✅ **verified** | Run in this demo against a local C8 Run of the same version (`mvn test`, 21 tests: Java, decision and JSON tests; external workers). The 8.11-SNAPSHOT runs against C8 Run 8.11.0-alpha1. |
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
   customer's 8.9 environment needs an upgrade, a backport, or a dedicated test cluster.
4. **Clock manipulation and "no purge" don't mix on 8.10 and in the 8.11.0-alpha1 jar**: after `increaseTime`, the next test
   fails with `Cluster is unhealthy` (#62541, ✅ reproduced). The public clock API (#23160, CPT PR #66862, merged to `main` on
   2026-10-28, after the alpha1 release candidates) fixes it — it is **not in the 8.11.0-alpha1 CPT jar** (✅ it still calls
   `/actuator/clock`), but **in 8.11.0-SNAPSHOT: ✅ all 21 tests pass with `data-deletion-mode: none` and `increaseTime`.**
5. **SaaS works for everything except the clock (☁️ 8.11).** Against a real SaaS cluster (8.11.0-alpha1, `mode: saas`, external workers on the same
   cluster, `data-deletion-mode: none`) 19 of 21 tests pass; the 2 timer tests are the exception (see limitation 10). A second run on top of the
   data of the first run passes as well (☁️): unique IDs, scoped selectors and the start-time isolation (via `GET /v2/clock`) keep the runs apart.
   Don't use `increaseTime` on SaaS: it stalled the search index of the cluster (limitation 10).
6. **With the 8.11-SNAPSHOT the whole suite, including the timer tests, ran without the management API and without a
   controllable-clock flag** (✅ local cluster, management port set to a closed port, purge off). That is the best case for a
   shared environment; SaaS itself is still ❓ unverified.
7. **Don't mix CPT job-worker mocks with real workers for the same job type.** They compete (✅ 5 of 10 jobs went to the mock).
8. **Scope everything to the process instance of the test** (✅ failed on 8.9 without it) — the selectors are cluster-wide
   unless told otherwise.

## What a remote run needs

| Requirement | 8.9.28 | 8.10.9 | 8.11-alpha1 | 8.11-SNAPSHOT | Notes |
| --- | --- | --- | --- | --- | --- |
| `camunda.process-test.runtime-mode: remote` | ✅ | ✅ | ✅ | ✅ | |
| Runtime version ≥ CPT version | ✅ | ✅ | ✅ | ✅ (C8 Run alpha1 with a newer snapshot works) | Docs: "CPT doesn't support older Camunda runtimes". The demo pins both to the same version. |
| Client address/credentials: `camunda.client.*` | ✅ | ✅ | ✅ | ✅ | 8.9.0 used `camunda.process-test.remote.client.*`, deprecated since 8.9.1. |
| Management API (port 9600) reachable | ✅ needed | ✅ needed¹ | ✅ needed¹ | only for the purge² | Purge (all versions) and the clock (≤ alpha1). |
| Cluster clock controllable (`CAMUNDA_SYSTEM_CLOCKCONTROLLED=true` or `ZEEBE_CLOCK_CONTROLLED=true`) | ✅ needed | ✅ needed | ✅ needed | ❌ **not needed** (✅ legacy endpoint stayed `403`, timer tests passed) | Only for `increaseTime`/`setTime`. Verified: without it `DELETE /actuator/clock` returns `403 … the clock is immutable`; both variable names work on all three versions. The public clock API of the 8.11 runtime replaces it. |
| Authorizations on `SYSTEM` (READ, UPDATE) for the CPT user | – | – | – | 🔎 needed (docs); ❓ not tried | Needed because CPT uses the public clock API. Only relevant when authorizations are enabled. |
| Job workers in a separate app | ✅ | ✅ | ✅ | ✅ | `camunda.client.worker.defaults.enabled=false` in the tests. |

¹ Not needed if `data-deletion-mode: none` **and** `clock-reset-enabled: false` **and** no `increaseTime` — but then CPT logs a
warning at every test start and disables the start-time isolation (✅ see limitation 6).

² With `data-deletion-mode: none` it isn't needed at all (✅).

## Capability matrix

The first four columns ran on a dedicated local C8 Run (8.11-SNAPSHOT: CPT snapshot against C8 Run 8.11.0-alpha1). Shared/SaaS = a cluster that must not be wiped. SaaS columns are 🔎/❓ only.

| Capability | C8 Run 8.9 | C8 Run 8.10 | 8.11-alpha1 | 8.11-SNAPSHOT | Shared / SaaS |
| --- | --- | --- | --- | --- | --- |
| Deploy BPMN (`@Deployment`), start instances | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| Process-instance and user-task assertions | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11, with `assertion.timeout` 30 s |
| Complete user tasks (`completeUserTask`) | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| External job workers do the work, test asserts the result variables | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| BPMN error from an external worker → error boundary event | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| Timer test with `increaseTime(5 days)` | ✅ | ✅ | ✅ | ✅ (also without management API, ✅) | ❌ no clock on SaaS ≤ 8.10 (#23160). 8.11: ☁️ `increaseTime` works, but ❌ stalled the search index (limitation 10) |
| DMN: deploy, business rule task, evaluate decision, `assertThatDecision` | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 |
| JSON test cases (`@TestCaseSource`, `TestCaseRunner`) incl. `ASSERT_VARIABLES`, `EVALUATE_DECISION`, `INCREASE_TIME` | ✅ | ✅ | ✅ | ✅ | ☁️ 8.11 passed, ⚠️ fixed data, selectors by definition ID (see 6) |
| Process coverage report | ✅ | ✅ | ✅ | ✅ | ❓ |
| Clean slate per test (purge) | ✅ default | ✅ default | ✅ default | ✅ default | ❌ **destructive**; SaaS has no management API, use `data-deletion-mode: none` (☁️ works) |
| Switch purge off | ❌ no switch (✅ logs `Failed to delete the runtime data, skipping`) | ✅ `data-deletion-mode: none` | ✅ same | ✅ same | needed |
| Test-case isolation by start time | ❌ (not in the jar) | ✅ source + ✅ warning | ✅ same | ✅ works without management API (clock read via REST; no warning). ☁️ also on SaaS: the second run on leftover data passed | ⚠️ ≤ alpha1: falls back to *off* when the management API is unreachable (✅ warning) |
| Clock + "no purge" together | ✅ n/a (purge always on) | ❌ #62541 ✅ reproduced | ❌ ✅ reproduced (alpha1 jar) | ✅ fixed (public clock API) | ✅ with the 8.11 snapshot on a local cluster. ☁️ **SaaS: ❌ the search index stalled** (limitation 10) |
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

* 8.9: **no way to switch it off.** If the management API is unreachable, CPT only logs `Failed to delete the runtime data,
  skipping` and carries on (✅), so a "no purge" run is possible by accident (point the address to a closed port, as the 8.9
  `saas` profile does) but it's not a feature.
* 8.10+: `camunda.process-test.data-deletion-mode: none`.
* Danger: the default monitoring address is `http://localhost:9600`. A mis-configured run can purge an unrelated local cluster.
* Next step: use a dedicated cluster for CPT, or run with `none` plus the isolation rules below.

### 2. The clock is cluster-wide — ✅ verified
`increaseTime(5 days)` made the timer of an *unrelated* process instance fire (it completed). On a shared cluster
every process with a timer, job timeout, message TTL or backoff is affected. Only use clock manipulation on a dedicated test cluster.
No clock manipulation on SaaS (≤ 8.10, #23160). With the public clock API (8.11) the clock stays cluster-wide: the effect on other instances is the same.

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

### 10. SaaS: `increaseTime` stalled the search index of the cluster — ☁️ observed on 8.11 (SNAPSHOT CPT, 8.11.0-alpha1 cluster)
The suite with the timer tests enabled (`demo.clock-manipulation.enabled=true`, `clock-reset-enabled: true`): the first test that ran was the JSON
timer test (`INCREASE_TIME` `P5D`) — it passed, and CPT reset the clock afterwards (`GET /v2/clock` showed the real time again). **All 20 following tests failed**
with "No process instance / user task / decision instance … found". The engine kept working (a probe instance was processed by the external workers), the
cluster topology stayed `healthy`, but **the search API (`POST /v2/process-instances/search`, `/v2/decision-instances/search`) returned nothing newer than the
timer test's instance**: no new data was indexed. This resembles #62541 (a clock jump leaves the cluster in a bad state), but on SaaS there is no
purge/restart to recover from — **the index had not recovered 21 minutes later** (checked until 12:09Z; the clock and the topology were normal, the search still returned the 17 instances up to
  the timer test, although the cluster had processed ~30 more instances and one probe instance). Nothing was tried on the SaaS side (no purge available);
  recovering the search probably needs a restart/recreation of the cluster by its owner. ❓ Whether it recovers by itself (for example when the real time passes
  the 5 days of the jump) is not known.
* Not reproduced locally (C8 Run 8.11.0-alpha1 with the same snapshot: 21/21, also with `none`). The secondary storage is different (C8 Run vs. SaaS), so it
  looks storage/exporter related. ❓ Root cause unknown; a candidate for an issue (needs the SaaS team).
* Conclusion: **do not manipulate the clock on a SaaS (or any shared) cluster.** Test timers there with short, configurable timer durations instead. The
  `saas` profile keeps the timer tests off.

### 4. Job workers: stale REST long-polling with `data-deletion-mode: none` (#62521, open) — ❓ not reproduced
Workers that run **in the test JVM** (Spring Boot default `@JobWorker`s, CPT mocks) keep their long-poll request across tests and can
take jobs that belong to the next test. Workaround: `camunda.client.zeebe.prefer-rest-over-grpc=false`. **This demo is
not affected by design**: the workers are external and never see a test boundary. It *is* a reason to keep workers
out of the test JVM.

### 5. CPT mocks and real workers compete for jobs — ✅ verified (8.11.0-alpha1 jar)
`processTestContext.mockJobWorker("add-invoice-to-accounting")` while the external worker was running: 5 of 10 jobs went
to the mock, 5 to the real worker. On a remote environment with real workers/connectors, a job type is served either by the
real worker **or** by a mock — not both.

* Replacing the HTTP connector by a worker (this demo) removes the need for the `mockJobWorker("io.camunda:http-json:1")` of the original
  example. If you do want to mock the connector on a shared cluster, you must stop the connector runtime for that
  job type (impossible on SaaS), or add a test-specific BPMN variant.
* Idea for a feature: scope a mock to the process instances of the test (job filter by instance key / tag).

### 6. Selectors are cluster-wide; test isolation needs scoping — ✅ verified
Without purge, `assertThatUserTask(byElementId("UserTask_ApproveInvoice"))` matched a task from an earlier test
(*"Expected … to be created, but was completed"*, 8.9.28) — and `completeUserTask(byElementId(…))` could complete
a stranger's task. Rules for a shared cluster, all implemented in this demo:

* unique business data per test (invoice ID), *and*
* selectors scoped to the process instance: `byElementId("…", processInstanceKey)`, `assertThatProcessInstance(instance)`.

8.10+ additionally limits the queries to data created after the test case started (🔎 `testCaseStartTime`; absent
in the 8.9.28 jar). In 8.10 and the 8.11.0-alpha1 jar that scoping reads the cluster time from the management API; if it can't,
CPT logs *"Test case isolation by start time will be disabled for this test case"* (✅) and you depend on the rules above.
The 8.11 snapshot reads the time through the public clock API and keeps the isolation without the management API (✅ no warning).

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
* JSON test cases (✅ on all three versions): they use fixed data and select by process definition ID (`processDefinitionId`), not by
  instance — so on a shared cluster they depend on the cleanup/start-time isolation, unlike the Java tests. The example's
  `MOCK_JOB_WORKER_*` instructions were dropped (they'd compete with the external workers, limitation 5). The schema for 8.11
  (`…/cpt-test-cases/8.11/schema.json`) is not published yet (404); the files reference the 8.10 schema.
* Secrets: CPT's `connectors-secrets` / `cluster-secrets` are ignored for remote runtimes (🔎, warning since 8.10).
* SaaS specifics (☁️): the Spring SDK `saas` mode points to `camunda.io`; for another stage set `camunda.client.cloud.domain` (here `ultrawombat.com`) —
  token URL, audience and addresses follow. Explicit `rest-address`/`grpc-address` in the base `application.yaml` also override the SaaS mode (a bug of this
  demo, fixed). The credentials file of the console also exports `ZEEBE_*` variables: the SDK logs deprecation warnings for them.
* Not verified: SaaS with 8.10/8.9, authenticated self-managed clusters incl. the `SYSTEM` authorizations,
  OpenShift, parallel test execution, the 8.11 final CPT.

## Version differences

### 8.11 (`main`: C8 Run 8.11.0-alpha1 + CPT 8.11.0-SNAPSHOT; the alpha1 jar was verified as well)

* **8.11.0-alpha1 jar:** works like 8.10 (the CPT remote runtime classes are identical in `8.10.9` and `8.11.0-alpha1`), including #62541.
* **Public clock API (snapshot, PR #66862).** The runtime of 8.11.0-alpha1 already has `GET /v2/clock`, `POST /v2/clock/add` and
  `POST /v2/clock/reset` (✅ `GET` and `reset` work *without* the controllable-clock flag). The CPT snapshot uses them through the
  Camunda client (`CamundaClockClientImpl`). Verified consequences (✅ local C8 Run 8.11.0-alpha1):
  * no `zeebe.clock.controlled` / `CAMUNDA_SYSTEM_CLOCKCONTROLLED` flag needed (the legacy endpoint stayed `403`, timer tests passed),
  * #62541 is fixed (`increaseTime` + `data-deletion-mode: none`),
  * no management API needed for the clock or the start-time isolation; with `data-deletion-mode: none` the suite ran with the management
    port closed.

  Per the 8.11 docs, with authorizations enabled the CPT user needs `READ` + `UPDATE` on `SYSTEM` (🔎/❓ not tried).
* Purge is unchanged (`POST /actuator/cluster/purge`, management API). Remaining SaaS blocker: purge → use `data-deletion-mode: none`.
* New in the docs vs 8.10: utilities to complete user tasks with dynamic variables / a variables table (🔎).

### 8.10 (`8.10` branch, 8.10.9 verified)

* New vs 8.9: `data-deletion-mode` (`CLUSTER_PURGE` | `NONE`), `clock-reset-enabled`, `query-page-limit`, test-case start-time
  isolation, `cluster-secrets` (8.10.6+; ignored with a warning in remote mode), semantic similarity, update-variables utilities.
* Clock: management API (`/actuator/clock`), needs the controllable-clock flag.
* Known issues: #62541 (✅ reproduced, won't fix in 8.10; fixed in the 8.11 snapshot), #62521 (open).

### 8.9 (`8.9` branch, 8.9.28 verified)

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
7. Report/analyze limitation 10 (clock manipulation on SaaS stalls the search index) with the SaaS team; until then the timer tests stay off on SaaS.
   Run the demo against an OpenShift cluster with
   authentication: management API access, `authorizations`, outbound connector runtime, GitLab CI job.
8. Try parallel test execution with the remote runtime.
