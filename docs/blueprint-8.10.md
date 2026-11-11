# Blueprint: process integration tests with CPT on a remote cluster — 8.10

How to set up Camunda Process Test (CPT) so that process tests run against an **already running cluster**, with the process
resources and the job workers in a **separate process application**. Everything below is verified with this demo
(branch `8.10`, version `8.10.9`); the claims, marks (✅ 🔎 ☁️ ❓) and the background are in [CAPABILITIES.md](CAPABILITIES.md).

> Other versions: [8.11](blueprint-8.11.md) (public clock API, timer tests work with `data-deletion-mode: none`), [8.9](blueprint-8.9.md).

## The setup

| Component | Role | In the demo |
| --- | --- | --- |
| **Cluster** | A Camunda 8 cluster that CPT uses **exclusively** (C8 Run locally or a dedicated cluster in CI). | `c8 cluster start` |
| **Process application** | Owns the BPMN/DMN and **deploys** them at startup. Hosts the job workers (the "external services"). Started independently, keeps running across test runs. | [`process-application`](../process-application) |
| **Process tests** | CPT tests with `runtime-mode: remote`. They start instances, complete user tasks and assert. They **don't deploy resources and don't run workers**. | [`process-tests`](../process-tests) |

Start order: **cluster → process application → tests**. If the process application hasn't deployed the resources, creating an
instance is rejected with `NOT_FOUND … process definition … none found`.

## 1. The cluster

```bash
c8 cluster start 8.10.9
```

* **The management API (port 9600) must be reachable** from the tests (✅ needed). CPT 8.10 uses it to read the cluster time for the start-time
  isolation, to reset the clock after each test and, with the default settings, to purge the cluster. If it is unreachable, CPT logs *"Test case isolation
  by start time will be disabled"* and the queries become cluster-wide again (✅).
* **The clock must be controllable only for timer tests:** start the cluster with `CAMUNDA_SYSTEM_CLOCKCONTROLLED=true`. The timer tests are **off** in this demo (see
  [recommendations](#recommendations)), so the plain command above is enough for the default run:

  ```bash
  CAMUNDA_SYSTEM_CLOCKCONTROLLED=true c8 cluster start 8.10.9    # only to experiment with timer tests
  ```
* The runtime must not be older than CPT: use the same version.

## 2. The process application

Dependencies ([`process-application/pom.xml`](../process-application/pom.xml)):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>io.camunda</groupId>
  <artifactId>camunda-spring-boot-starter</artifactId>
</dependency>
```

Build with `-parameters` (`<maven.compiler.parameters>true</maven.compiler.parameters>` in the root pom), otherwise `@Variable` parameter names
aren't discovered. The root pom imports the `camunda-bom` of `camunda.version` (`8.10.9`) and the Spring Boot BOM (`4.1.2`).

The resources live in `src/main/resources/bpmn/` of the process application and are deployed at startup
([`InvoiceProcessApplication`](../process-application/src/main/java/io/camunda/demo/invoice/InvoiceProcessApplication.java)):

```java
@SpringBootApplication
@Deployment(resources = {"classpath*:/bpmn/**/*.bpmn", "classpath*:/bpmn/**/*.dmn"})
public class InvoiceProcessApplication { /* main(...) */ }
```

The job workers are plain Spring beans ([`ArchiveInvoiceWorker`](../process-application/src/main/java/io/camunda/demo/invoice/ArchiveInvoiceWorker.java)):

```java
@Component
public class ArchiveInvoiceWorker {
  @JobWorker(type = "archive-invoice")
  public Map<String, Object> archiveInvoice(@Variable("invoiceId") final String invoiceId) {
    return Map.of("archiveReference", "ARCHIVE-" + invoiceId);
  }
}
```

Connection ([`application.yaml`](../process-application/src/main/resources/application.yaml)): no addresses, the self-managed defaults
(REST `http://localhost:8080`, gRPC `http://localhost:26500`) are the local C8 Run:

```yaml
camunda:
  client:
    mode: self-managed
spring:
  main:
    web-application-type: none   # no web server needed, only the workers
```

Start it: `mvn -pl process-application spring-boot:run`. Re-deploying unchanged resources doesn't create a new version.

## 3. The test project

**Dependencies** ([`process-tests/pom.xml`](../process-tests/pom.xml)). The Camunda client and CPT come from the `camunda-bom` of the root pom:

```xml
<dependencies>
  <dependency>
    <groupId>io.camunda</groupId>
    <artifactId>camunda-spring-boot-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>io.camunda</groupId>
    <artifactId>camunda-process-test-spring</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

**Configuration** ([`application.yaml`](../process-tests/src/test/resources/application.yaml)):

```yaml
camunda:
  process-test:
    runtime-mode: remote            # connect to the running cluster, no Testcontainers
    data-deletion-mode: none        # don't purge the cluster (it would delete the deployed resources), CPT 8.10+
    remote:
      camunda-monitoring-api-address: http://127.0.0.1:9600   # management API: start time, clock reset, clock
      runtime-connection-timeout: PT1M
  client:
    mode: self-managed              # no addresses: the defaults are the local C8 Run

demo:
  clock-manipulation:
    enabled: false                  # timer tests off, see below
```

**Test class.** The Spring configuration has neither resources nor workers
([`TestApplication`](../process-tests/src/test/java/io/camunda/demo/TestApplication.java)):

```java
@SpringBootApplication
public class TestApplication {}
```

Tests start the process by its ID, drive the user tasks and assert the effects of the external workers
([`InvoiceApprovalTest`](../process-tests/src/test/java/io/camunda/demo/InvoiceApprovalTest.java)):

```java
@SpringBootTest(classes = TestApplication.class)
@CamundaSpringProcessTest
class InvoiceApprovalTest {
  @Autowired CamundaClient client;
  @Autowired CamundaProcessTestContext processTestContext;

  @Test
  void happyPath() {
    final ProcessInstanceEvent processInstance = client.newCreateInstanceCommand()
        .bpmnProcessId("Process_InvoiceApproval").latestVersion()
        .variables(Map.of("approver", "Zee", "invoice", Map.of("id", "INV-HAPPY", /* ... */)))
        .send().join();

    assertThatUserTask(byElementId("UserTask_ApproveInvoice")).isCreated().hasAssignee("Zee");
    processTestContext.completeUserTask(byElementId("UserTask_ApproveInvoice"), Map.of("approved", true));

    assertThatProcessInstance(processInstance)
        .hasCompletedElementsInOrder(byId("StartEvent_InvoiceReceived"), /* ... */ byId("EndEvent_InvoiceApproved"))
        .hasVariable("archiveReference", "ARCHIVE-INV-HAPPY")   // returned by the external worker
        .isCompleted();
  }

  @Test
  @EnabledIf(expression = "${demo.clock-manipulation.enabled:true}", loadContext = true)
  void approvalTimeout() { /* processTestContext.increaseTime(Duration.ofDays(5)) */ }
}
```

* **Plain selectors are enough** (`byElementId("UserTask_ApproveInvoice")`) as long as the start-time isolation is active, that is as long as CPT can read the
  cluster time through the management API (✅ the suite passes twice in a row on the same cluster). Without it, scope the selectors to the process
  instance — see [8.9](blueprint-8.9.md#3-the-test-project).
* **DMN, JSON test cases and the coverage report** work the same way (`@TestCaseSource` with `TestCaseRunner`, `assertThatDecision`).
  The coverage report (`target/coverage-report/report.html`) covers only the data of the run.
* **Timer tests are off by default.** `increaseTime` together with `data-deletion-mode: none` leaves the cluster unhealthy for the next test
  ([#62541](https://github.com/camunda/camunda/issues/62541), ✅ reproduced, not fixed in 8.10, fixed by the public clock API of 8.11).

## 4. Run

```bash
c8 cluster start 8.10.9                         # 1. cluster
mvn -pl process-application spring-boot:run     # 2. process application (second terminal, keeps running)
mvn -pl process-tests test                      # 3. tests: 19 run and pass, the 2 timer tests are skipped
c8 cluster stop
```

## 5. Configuration reference

| What | Value | Why |
| --- | --- | --- |
| `camunda.process-test.runtime-mode` | `remote` | Use the running cluster. |
| `camunda.process-test.data-deletion-mode` | `none` | The default (`cluster_purge`) deletes everything on the cluster, including the deployed BPMN/DMN. [Limitation 1](CAPABILITIES.md#limitations-and-blockers) |
| `camunda.process-test.remote.camunda-monitoring-api-address` | default `http://127.0.0.1:9600` | Management API: cluster time (start-time isolation), clock reset, clock. [Limitation 7](CAPABILITIES.md#limitations-and-blockers) |
| `camunda.process-test.clock-reset-enabled` | default `true` | Reset the clock after each test through the management API. `false` if the API isn't reachable — then the start-time isolation is off as well (✅ warning). |
| `camunda.process-test.remote.runtime-connection-timeout` | `PT1M` | Wait for a cluster that is still starting. |
| `camunda.process-test.assertion.timeout` | default `PT10S` | Raise it on remote clusters (the data is exported asynchronously). [Limitation 8](CAPABILITIES.md#limitations-and-blockers) |
| `camunda.client.mode` and addresses | `self-managed` (defaults) or `saas` | Where the client of CPT and of the process application connect to. |

## 6. SaaS (❓ not verified for 8.10)

Only 8.11 was run on SaaS. For 8.10 the following is derived from the source (🔎): SaaS has no management API and no controllable clock
([#23160](https://github.com/camunda/camunda/issues/23160)), so use the `saas` profile of the demo
([`application-saas.yaml`](../process-tests/src/test/resources/application-saas.yaml)) with `data-deletion-mode: none`, `clock-reset-enabled: false`,
`assertion.timeout: PT30S`, `camunda.client.mode: saas` and no timer tests. **Expect the start-time isolation to be off** (CPT can't read the cluster time without the
management API), so scope the selectors to the process instance and use unique invoice IDs. See
[`README`](../README.md#run-on-camunda-8-saas) for the credentials and the commands.

## Recommendations

1. **Use a dedicated cluster, exclusively for CPT, and run one test job at a time.** CPT doesn't support parallel tests; clock, purge and the coverage report are
   cluster-wide (limitation 10).
2. **The process application deploys, the tests only verify.** Tests that deploy would override what the process application deployed.
3. **Set `data-deletion-mode: none`.** With the default purge, the first test deletes the deployed resources, and every later test fails.
4. **Keep the management API reachable** (limitation 7), so that CPT's start-time isolation works (limitation 6). Don't point its address to an unrelated cluster.
5. **Keep the workers out of the test JVM and don't mock a job type that a real worker serves**: they compete for the jobs (limitations 4 and 5). Assert the effects
   (path and variables) instead.
6. **Don't use the clock together with `data-deletion-mode: none`** (limitation 3). To test timers, make the timer durations configurable in the process, or run them on a
   separate local C8 Run with the purge (restart the process application afterwards: the purge deletes its deployments).
   Never use the clock with an Elasticsearch/OpenSearch exporter (limitation 9).
7. **Make unsupported tests switchable** (`@EnabledIf("${demo.clock-manipulation.enabled:true}")`).
8. **Give the assertions more time on remote clusters** (`assertion.timeout`).

## Limitations that matter here

Details, evidence and workarounds: [CAPABILITIES.md](CAPABILITIES.md#limitations-and-blockers).

* The purge is cluster-wide and deletes the deployed resources — keep it off (1).
* The clock is cluster-wide (2) and breaks the next test without a purge (3): no timer tests in this setup. With an Elasticsearch exporter it also stalls indexing (9).
* The management API must be reachable for the start-time isolation (7).
* The mock/worker competition (5) and the stale REST long polling of workers in the test JVM (4, not reproduced — this setup has no workers there).
* No parallel tests, no other users on the cluster while the tests run (10).

See also the [version differences](CAPABILITIES.md#version-differences) and the [README](../README.md) to run the demo.
