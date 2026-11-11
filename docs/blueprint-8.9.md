# Blueprint: process integration tests with CPT on a remote cluster — 8.9

How to set up Camunda Process Test (CPT) so that process tests run against an **already running cluster**, while the process
resources and the job workers live in a **separate process application**. The evidence, the verification status and the background
are in [CAPABILITIES.md](CAPABILITIES.md). The concrete commands to run the demo are in the [README](../README.md).

> **8.9 is the most restricted version.** CPT 8.9 has no setting to switch off the cleanup, no start-time isolation, and needs the management API for the clock.
> The setup below works around the cleanup and gives up timer tests and JSON test cases. Other versions: [8.10](blueprint-8.10.md), [8.11](blueprint-8.11.md).

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
  deployed. CPT 8.9 has no `data-deletion-mode`, so the demo keeps CPT from reaching the management API by pointing its address to a closed port.
* **The tests isolate themselves.** CPT 8.9 has no start-time scoping, so the data of earlier tests stays visible: every test uses a unique invoice ID and selectors scoped to its process instance.
* **The workers are external.** The tests neither start nor mock them; they assert the effects (path and variables).

## 1. The cluster

* A Camunda 8 cluster of version 8.9. The runtime must not be older than CPT.
* Reachable by the tests and the process application: REST (`http://localhost:8080`) and gRPC (`http://localhost:26500`) for a local C8 Run.
* **The management API (port 9600) is deliberately not reachable for CPT** (see the cleanup workaround in section 3).
* **The clock must be controllable only for timer tests:** set `CAMUNDA_SYSTEM_CLOCKCONTROLLED=true` (or `ZEEBE_CLOCK_CONTROLLED=true`) in the environment of the cluster. The timer
  tests are **off** in this demo (the clock uses the same management API address, which is closed), so the demo doesn't need it.
* Dedicated to CPT while the tests run (see the [recommendations](#recommendations)).

## 2. The process application

It is independent of CPT, and how you run it is up to your environment: your own application, your job workers, a connector runtime. What the tests need from it:

* it is connected to the **same cluster** as the tests;
* it has **deployed the BPMN/DMN** before the tests start (the tests never deploy);
* the **job workers/connectors for all job types of the process are running** while the tests run;
* it **keeps running across test runs** — nothing in the test run removes its deployments.

The demo implements it as a Spring Boot application with `@Deployment` and `@JobWorker` ([`process-application`](../process-application)).

## 3. The test project

**Dependencies** ([`process-tests/pom.xml`](../process-tests/pom.xml)). The Camunda client and CPT come from the `camunda-bom` of `camunda.version` (`8.9.28`):

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

Use Spring Boot **4.0.x** (`4.0.9` in the demo). **The 8.9 client needs newer Apache HttpClient versions than the Spring Boot 4.0 BOM manages**
(`NoSuchMethodError: HttpAsyncClientBuilder.disableContentCompression()`). Pin them in the root pom **before** the Spring Boot BOM import (the first declaration wins):

```xml
<properties>
  <httpclient5.version>5.6.5</httpclient5.version>
  <httpcore5.version>5.4.4</httpcore5.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency> <!-- also httpcore5 and httpcore5-h2 with ${httpcore5.version} -->
      <groupId>org.apache.httpcomponents.client5</groupId>
      <artifactId>httpclient5</artifactId>
      <version>${httpclient5.version}</version>
    </dependency>
    <!-- then the imports of spring-boot-dependencies and camunda-bom -->
  </dependencies>
</dependencyManagement>
```

**Configuration** ([`application.yaml`](../process-tests/src/test/resources/application.yaml)). Without a `data-deletion-mode`, CPT 8.9 purges the cluster after each
test through the management API. A **closed port** as the management API address keeps it from doing so: CPT only logs `Failed to delete the runtime data, skipping`
and the data stays:

```yaml
camunda:
  process-test:
    runtime-mode: remote            # connect to the running cluster, no Testcontainers
    remote:
      camunda-monitoring-api-address: http://127.0.0.1:9   # closed port on purpose: no purge (default: http://127.0.0.1:9600)
      runtime-connection-timeout: PT1M
  client:
    mode: self-managed              # no addresses: the defaults are a local cluster

demo:
  clock-manipulation:
    enabled: false                  # the clock uses the same (closed) address: no timer tests
  json-tests:
    enabled: false                  # no start-time isolation, see below
```

> ⚠️ The default address is `http://localhost:9600`. A mis-configured run can purge an unrelated local cluster.

**Test class.** The Spring configuration has neither resources nor workers
([`TestApplication`](../process-tests/src/test/java/io/camunda/demo/TestApplication.java)):

```java
@SpringBootApplication
public class TestApplication {}
```

Because there is no start-time isolation and nothing is purged, an unscoped `byElementId("UserTask_ApproveInvoice")` can match a task of an earlier test
(*"Expected … to be created, but was completed"*) and even complete the task of a stranger. The tests therefore use **a unique invoice ID per test** and
**selectors scoped to the process instance**
([`InvoiceApprovalTest`](../process-tests/src/test/java/io/camunda/demo/InvoiceApprovalTest.java)):

```java
@SpringBootTest(classes = TestApplication.class)
@CamundaSpringProcessTest
class InvoiceApprovalTest {
  @Autowired CamundaClient client;
  @Autowired CamundaProcessTestContext processTestContext;

  @Test
  void happyPath() {
    final String invoiceId = "INV-HAPPY-" + UUID.randomUUID().toString().substring(0, 8);
    final ProcessInstanceEvent processInstance = client.newCreateInstanceCommand()
        .bpmnProcessId("Process_InvoiceApproval").latestVersion()
        .variables(Map.of("approver", "Zee", "invoice", Map.of("id", invoiceId, /* ... */)))
        .send().join();

    final UserTaskSelector approveTask =
        byElementId("UserTask_ApproveInvoice", processInstance.getProcessInstanceKey());   // scoped to the instance
    assertThatUserTask(approveTask).isCreated().hasAssignee("Zee");
    processTestContext.completeUserTask(approveTask, Map.of("approved", true));

    assertThatProcessInstance(processInstance)
        .hasCompletedElementsInOrder(byId("StartEvent_InvoiceReceived"), /* ... */ byId("EndEvent_InvoiceApproved"))
        .hasVariable("archiveReference", "ARCHIVE-" + invoiceId)   // returned by the external worker
        .isCompleted();
  }
}
```

* **Decisions:** use `assertThatDecision(…)` with the response of `evaluateDecision`, or select the decision instance of a process by its process instance key.
* **JSON test cases are off:** they select by process definition ID with fixed data, so on a cluster that isn't purged they see the data of earlier runs.
* **Timer tests are off:** CPT 8.9 changes the clock through the management API, which is closed. Opening it brings the purge back.
* **The coverage report** (`target/coverage-report/report.html`) may include the data of earlier runs.

## 4. Configuration reference

| What | Value | Why |
| --- | --- | --- |
| `camunda.process-test.runtime-mode` | `remote` | Use the running cluster. |
| `camunda.process-test.remote.camunda-monitoring-api-address` | `http://127.0.0.1:9` (closed) | CPT 8.9 can't switch the cleanup off, so keep it from reaching the management API. |
| `camunda.process-test.remote.runtime-connection-timeout` | `PT1M` | Wait for a cluster that is still starting. |
| `camunda.process-test.assertion.timeout` | default `PT10S` | Raise it on remote clusters: the data is exported asynchronously. |
| `camunda.client.mode` and addresses | `self-managed` (defaults) or `saas` | Where the client of CPT and of the process application connect to. Use `camunda.client.*`: `camunda.process-test.remote.client.*` is deprecated since 8.9.1. |
| `data-deletion-mode`, `clock-reset-enabled` | not available | Need CPT 8.10+. |

## 5. Run on SaaS

SaaS has no management API and no clock ([#23160](https://github.com/camunda/camunda/issues/23160)), and CPT 8.9 can't switch the cleanup off.
The `saas` profile of the demo ([`application-saas.yaml`](../process-tests/src/test/resources/application-saas.yaml)) keeps the closed management API address (it also keeps CPT from
reaching an unrelated cluster on `localhost:9600`), sets `assertion.timeout: PT30S` and `camunda.client.mode: saas`, and has no timer tests. The credentials and commands are in the
[README](../README.md#run-on-camunda-8-saas).

## Recommendations

1. **Prefer 8.10+ for integration tests against a cluster that has to keep its data.** 8.9 needs the closed-port workaround and loses timer tests, JSON test cases and a clean coverage report.
2. **Use a dedicated cluster, exclusively for CPT, and run one test job at a time.** CPT doesn't support parallel tests; clock and coverage report are
   cluster-wide ([limitation 10](CAPABILITIES.md#limitations-and-blockers)).
3. **Never let the purge reach a cluster you care about:** close the management API address and don't leave the default `localhost:9600`.
4. **Isolate the tests yourself:** a unique ID per test and selectors scoped to the process instance ([limitation 6](CAPABILITIES.md#limitations-and-blockers)).
5. **Keep the workers out of the test JVM and don't mock a job type that a real worker serves**: they compete for the jobs
   ([limitations 4 and 5](CAPABILITIES.md#limitations-and-blockers)).
6. **Make unsupported tests switchable** (`@EnabledIf("${demo.clock-manipulation.enabled:true}")`, `demo.json-tests.enabled`).
7. **Give the assertions more time on remote clusters** (`assertion.timeout`).

## Limitations that matter here

Details and workarounds: [CAPABILITIES.md](CAPABILITIES.md#limitations-and-blockers).

* No setting to switch the cleanup off (1): the closed-port workaround costs the timer tests and the JSON test cases.
* No start-time isolation: scoped selectors and unique IDs are required (6); the coverage report sees earlier data.
* The clock needs the management API and the controllable-clock flag (2, 3).
* Mocks and real workers compete for the same job type (5).
* No parallel tests, no other users on the cluster while the tests run (10).

See also the [version differences](CAPABILITIES.md#version-differences).
