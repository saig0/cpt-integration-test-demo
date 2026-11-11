# Blueprint: process integration tests with CPT on a remote cluster — 8.10

How to set up Camunda Process Test (CPT) so that process tests run against an **already running cluster**, while the process
resources and the job workers live in a **separate process application**. The evidence, the verification status and the background
are in [CAPABILITIES.md](CAPABILITIES.md). The concrete commands to run the demo are in the [README](../README.md).

> Other versions: [8.11](blueprint-8.11.md) (public clock API, timer tests work with `data-deletion-mode: none`), [8.9](blueprint-8.9.md).

## The setup

| Component | Role |
| --- | --- |
| **Cluster** | A Camunda 8 cluster that CPT uses **exclusively**: a local C8 Run or a dedicated cluster in CI. |
| **Process application** | Owns the BPMN/DMN, **deploys** them and runs the job workers (or connectors). Independent of the tests. |
| **Process tests** | CPT tests with `runtime-mode: remote`. They start instances, complete user tasks and assert. They **don't deploy resources and don't run workers**. |

Start order: **cluster → process application → tests**. If the resources aren't deployed, creating an instance is rejected with
`NOT_FOUND … process definition … none found`.

How this setup works:

* **The process application owns the resources; the tests only verify the deployed process.** A test that deploys would override what the process application deployed.
* **The cluster is not purged.** CPT's default cleanup wipes *everything* on the cluster after each test, including the resources the process application
  deployed. So the tests run with `data-deletion-mode: none` (CPT 8.10+).
* **Isolation comes from CPT's start-time scoping.** Without a purge, CPT limits its queries (assertions, utilities, the coverage report) to the data created after the test case
  started. Plain selectors such as `byElementId("UserTask_ApproveInvoice")` are enough — as long as CPT can read the cluster time through the management API (see below).
* **The workers are external.** The tests neither start nor mock them; they assert the effects (path and variables).

## 1. The cluster

* A Camunda 8 cluster of version 8.10. The runtime must not be older than CPT.
* Reachable by the tests and the process application: REST (`http://localhost:8080`) and gRPC (`http://localhost:26500`) for a local C8 Run.
* **The management API (port 9600) must be reachable** from the tests. CPT 8.10 uses it to read the cluster time (start-time isolation), to reset the clock after
  each test and, with the default settings, to purge the cluster. If it is unreachable, CPT logs *"Test case isolation by start time will be disabled"* and the queries
  become cluster-wide again — then scope the selectors to the process instance (see [8.9](blueprint-8.9.md#3-the-test-project)).
* **The clock must be controllable only for timer tests:** set `CAMUNDA_SYSTEM_CLOCKCONTROLLED=true` (or `ZEEBE_CLOCK_CONTROLLED=true`) in the environment of the cluster. The timer
  tests are **off** in this demo (see [recommendations](#recommendations)), so the demo doesn't need it.
* Dedicated to CPT while the tests run (see the [recommendations](#recommendations)).

## 2. The process application

It is independent of CPT, and how you run it is up to your environment: your own application, your job workers, a connector runtime. What the tests need from it:

* it is connected to the **same cluster** as the tests;
* it has **deployed the BPMN/DMN** before the tests start (the tests never deploy);
* the **job workers/connectors for all job types of the process are running** while the tests run;
* it **keeps running across test runs** — nothing in the test run removes its deployments.

The demo implements it as a Spring Boot application with `@Deployment` and `@JobWorker` ([`process-application`](../process-application)).

## 3. The test project

**Dependencies** ([`process-tests/pom.xml`](../process-tests/pom.xml)). The Camunda client and CPT come from the `camunda-bom` of `camunda.version` (`8.10.9`):

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
    data-deletion-mode: none        # don't purge the cluster: it would delete the deployed resources (CPT 8.10+)
    remote:
      camunda-monitoring-api-address: http://127.0.0.1:9600   # management API: cluster time, clock reset, clock
      runtime-connection-timeout: PT1M
  client:
    mode: self-managed              # no addresses: the defaults are a local cluster

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

* **DMN, JSON test cases and the coverage report** work the same way (`@TestCaseSource` with `TestCaseRunner`, `assertThatDecision`).
  The coverage report (`target/coverage-report/report.html`) covers only the data of the run.
* **Timer tests are off by default.** `increaseTime` together with `data-deletion-mode: none` leaves the cluster unhealthy for the next test
  ([#62541](https://github.com/camunda/camunda/issues/62541), not fixed in 8.10, fixed by the public clock API of 8.11).

## 4. Configuration reference

| What | Value | Why |
| --- | --- | --- |
| `camunda.process-test.runtime-mode` | `remote` | Use the running cluster. |
| `camunda.process-test.data-deletion-mode` | `none` | The default (`cluster_purge`) deletes everything on the cluster, including the deployed BPMN/DMN. |
| `camunda.process-test.remote.camunda-monitoring-api-address` | default `http://127.0.0.1:9600` | Management API: cluster time (start-time isolation), clock reset, clock. |
| `camunda.process-test.clock-reset-enabled` | default `true` | Reset the clock after each test through the management API. Set `false` if the API isn't reachable — the start-time isolation is then off as well. |
| `camunda.process-test.remote.runtime-connection-timeout` | `PT1M` | Wait for a cluster that is still starting. |
| `camunda.process-test.assertion.timeout` | default `PT10S` | Raise it on remote clusters: the data is exported asynchronously. |
| `camunda.client.mode` and addresses | `self-managed` (defaults) or `saas` | Where the client of CPT and of the process application connect to. |

## 5. Run on SaaS

SaaS has no management API and no controllable clock ([#23160](https://github.com/camunda/camunda/issues/23160)). Use the `saas` profile of the demo
([`application-saas.yaml`](../process-tests/src/test/resources/application-saas.yaml)) with `data-deletion-mode: none`, `clock-reset-enabled: false`,
`assertion.timeout: PT30S`, `camunda.client.mode: saas` and no timer tests. **Without the management API, CPT can't read the cluster time, so the start-time isolation is off**:
scope the selectors to the process instance and use unique invoice IDs. The credentials and commands are in the [README](../README.md#run-on-camunda-8-saas).

## Recommendations

1. **Use a dedicated cluster, exclusively for CPT, and run one test job at a time.** CPT doesn't support parallel tests; clock and coverage report are
   cluster-wide ([limitation 10](CAPABILITIES.md#limitations-and-blockers)).
2. **Keep the management API reachable**, so that CPT's start-time isolation works ([limitations 6 and 7](CAPABILITIES.md#limitations-and-blockers)). Don't point its address to an unrelated cluster.
3. **Keep the workers out of the test JVM and don't mock a job type that a real worker serves**: they compete for the jobs
   ([limitations 4 and 5](CAPABILITIES.md#limitations-and-blockers)).
4. **Don't use the clock together with `data-deletion-mode: none`** ([limitation 3](CAPABILITIES.md#limitations-and-blockers)). To test timers, make the timer durations configurable in the process,
   or run them on a separate local C8 Run with the purge (restart the process application afterwards: the purge deletes its deployments). Never use the clock with an Elasticsearch/OpenSearch exporter ([limitation 9](CAPABILITIES.md#limitations-and-blockers)).
5. **Make unsupported tests switchable** (`@EnabledIf("${demo.clock-manipulation.enabled:true}")`).
6. **Give the assertions more time on remote clusters** (`assertion.timeout`).

## Limitations that matter here

Details and workarounds: [CAPABILITIES.md](CAPABILITIES.md#limitations-and-blockers).

* The clock is cluster-wide (2) and breaks the next test when nothing is purged (3): no timer tests in this setup. With an Elasticsearch exporter a clock jump also stalls indexing (9).
* The start-time isolation needs the management API (7).
* Mocks and real workers compete for the same job type (5); workers inside the test JVM can take jobs of the next test (4) — this setup has none.
* No parallel tests, no other users on the cluster while the tests run (10).

See also the [version differences](CAPABILITIES.md#version-differences).
