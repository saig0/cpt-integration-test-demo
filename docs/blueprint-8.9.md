# Blueprint: process integration tests with CPT on a remote cluster — 8.9

How to set up Camunda Process Test (CPT) so that process tests run against an **already running cluster**, with the process
resources and the job workers in a **separate process application**. Everything below is verified with this demo
(branch `8.9`, version `8.9.28`); the claims, marks (✅ 🔎 ☁️ ❓) and the background are in [CAPABILITIES.md](CAPABILITIES.md).

> **8.9 is the most restricted version.** CPT 8.9 can't switch off the purge, has no start-time isolation, and needs the management API for the clock.
> The setup below works around the purge and gives up timer tests and JSON test cases. Other versions: [8.10](blueprint-8.10.md), [8.11](blueprint-8.11.md).

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
c8 cluster start 8.9.28
```

* The runtime must not be older than CPT: use the same version.
* **The clock must be controllable only for timer tests:** `CAMUNDA_SYSTEM_CLOCKCONTROLLED=true c8 cluster start 8.9.28`. The timer tests are **off** in this demo
  (the management API is closed on purpose, see below), so the plain command is enough.
* The management API (port 9600) is deliberately **not** reachable for CPT, see the purge workaround in section 3.

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
aren't discovered. The root pom imports the `camunda-bom` of `camunda.version` (`8.9.28`) and the Spring Boot BOM (**`4.0.9`**, the 4.0 line).

**8.9 needs newer Apache HttpClient versions than the Spring Boot 4.0 BOM manages** (`NoSuchMethodError: HttpAsyncClientBuilder.disableContentCompression()`).
Pin them in the root pom **before** the Spring Boot BOM import (the first declaration wins):

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

**Configuration** ([`application.yaml`](../process-tests/src/test/resources/application.yaml)). CPT 8.9 has **no `data-deletion-mode`**: it always purges the cluster after each
test through the management API, which would delete the deployed resources. The workaround is a **closed port** as the management API address. CPT then only logs
`Failed to delete the runtime data, skipping` and keeps the data (✅):

```yaml
camunda:
  process-test:
    runtime-mode: remote            # connect to the running cluster, no Testcontainers
    remote:
      camunda-monitoring-api-address: http://127.0.0.1:9   # closed port on purpose: no purge (default: http://127.0.0.1:9600)
      runtime-connection-timeout: PT1M
  client:
    mode: self-managed              # no addresses: the defaults are the local C8 Run

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

**No start-time isolation on 8.9, and the cluster isn't purged**, so the data of earlier tests stays. An unscoped `byElementId("UserTask_ApproveInvoice")`
matched a task of an earlier test (*"Expected … to be created, but was completed"*, ✅) and could even complete the task of a stranger. The tests therefore use
**a unique invoice ID per test** and **selectors scoped to the process instance**
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

* **Decisions:** `assertThatDecision(…)` with the response of `evaluateDecision` works; decision instances of a process are selected by process instance key.
* **JSON test cases are off:** they select by process definition ID with fixed data, so on a cluster that is not purged they see the data of earlier runs.
* **Timer tests are off:** CPT 8.9 changes the clock through the management API, which is closed. Opening it brings the purge back.
* **The coverage report** (`target/coverage-report/report.html`) may include the data of earlier runs.

## 4. Run

```bash
c8 cluster start 8.9.28                         # 1. cluster
mvn -pl process-application spring-boot:run     # 2. process application (second terminal, keeps running)
mvn -pl process-tests test                      # 3. tests: 11 run and pass, 3 skipped by design (timer test, JSON test cases)
c8 cluster stop
```

## 5. Configuration reference

| What | Value | Why |
| --- | --- | --- |
| `camunda.process-test.runtime-mode` | `remote` | Use the running cluster. |
| `camunda.process-test.remote.camunda-monitoring-api-address` | `http://127.0.0.1:9` (closed) | CPT 8.9 can't switch the purge off, so keep it from reaching the management API. [Limitation 1](CAPABILITIES.md#limitations-and-blockers) |
| `camunda.process-test.remote.runtime-connection-timeout` | `PT1M` | Wait for a cluster that is still starting. |
| `camunda.process-test.assertion.timeout` | default `PT10S` | Raise it on remote clusters (the data is exported asynchronously). [Limitation 8](CAPABILITIES.md#limitations-and-blockers) |
| `camunda.client.mode` and addresses | `self-managed` (defaults) or `saas` | Where the client of CPT and of the process application connect to. Use `camunda.client.*`: `camunda.process-test.remote.client.*` is deprecated since 8.9.1. |
| `data-deletion-mode`, `clock-reset-enabled` | not available | Need CPT 8.10+. |

## 6. SaaS (❓ not verified for 8.9)

Only 8.11 was run on SaaS. For 8.9: SaaS has no management API and no clock ([#23160](https://github.com/camunda/camunda/issues/23160)), and CPT 8.9 can't switch the purge off.
The `saas` profile of the demo ([`application-saas.yaml`](../process-tests/src/test/resources/application-saas.yaml)) therefore keeps the closed management API address (it also keeps CPT from
reaching an unrelated cluster on `localhost:9600`), sets `assertion.timeout: PT30S`, `camunda.client.mode: saas` and no timer tests. See
[`README`](../README.md#run-on-camunda-8-saas) for the credentials and the commands.

## Recommendations

1. **Prefer 8.10+ for integration tests against a cluster that has to keep its data.** 8.9 needs the closed-port workaround and loses timers, JSON tests and a clean coverage report.
2. **Use a dedicated cluster, exclusively for CPT, and run one test job at a time.** CPT doesn't support parallel tests; clock, purge and the coverage report are
   cluster-wide (limitation 10).
3. **The process application deploys, the tests only verify.** Tests that deploy would override what the process application deployed.
4. **Never let the purge reach a cluster you care about**: close the management API address (limitation 1) and don't leave the default `localhost:9600`.
5. **Isolate the tests yourself:** a unique ID per test and selectors scoped to the process instance (limitation 6).
6. **Keep the workers out of the test JVM and don't mock a job type that a real worker serves**: they compete for the jobs (limitations 4 and 5). Assert the effects
   (path and variables) instead.
7. **Make unsupported tests switchable** (`@EnabledIf("${demo.clock-manipulation.enabled:true}")`, `demo.json-tests.enabled`).
8. **Give the assertions more time on remote clusters** (`assertion.timeout`).

## Limitations that matter here

Details, evidence and workarounds: [CAPABILITIES.md](CAPABILITIES.md#limitations-and-blockers).

* The purge can't be switched off — only avoided by closing the management API address (1).
* No start-time isolation: scoped selectors and unique IDs are required (6); the coverage report and JSON test cases see earlier data.
* The clock needs the management API and the controllable-clock flag, so no timer tests with this setup (2, 3).
* The mock/worker competition (5).
* No parallel tests, no other users on the cluster while the tests run (10).

See also the [version differences](CAPABILITIES.md#version-differences) and the [README](../README.md) to run the demo.
