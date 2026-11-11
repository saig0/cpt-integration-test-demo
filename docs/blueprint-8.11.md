# Blueprint: process integration tests with CPT on a remote cluster — 8.11

How to set up Camunda Process Test (CPT) so that process tests run against an **already running cluster**, with the process
resources and the job workers in a **separate process application**. Everything below is verified with this demo
(branch `main`); the claims, marks (✅ 🔎 ☁️ ❓) and the background are in [CAPABILITIES.md](CAPABILITIES.md).

> **Versions.** C8 Run `8.11.0-alpha1` and the process application on `8.11.0-alpha1`; the tests use the CPT **`8.11.0-SNAPSHOT`**
> because the public clock API was merged after the alpha1 release. When the next alpha is released, set `cpt.version` to it
> and remove the snapshot repository. Other versions: [8.10](blueprint-8.10.md), [8.9](blueprint-8.9.md).

## The setup

| Component | Role | In the demo |
| --- | --- | --- |
| **Cluster** | A Camunda 8 cluster that CPT uses **exclusively** (C8 Run locally, a dedicated cluster in CI, or SaaS). | `c8 cluster start` |
| **Process application** | Owns the BPMN/DMN and **deploys** them at startup. Hosts the job workers (the "external services"). Started independently, keeps running across test runs. | [`process-application`](../process-application) |
| **Process tests** | CPT tests with `runtime-mode: remote`. They start instances, complete user tasks and assert. They **don't deploy resources and don't run workers**. | [`process-tests`](../process-tests) |

Start order: **cluster → process application → tests**. If the process application hasn't deployed the resources, creating an
instance is rejected with `NOT_FOUND … process definition … none found`.

## 1. The cluster

```bash
c8 cluster start 8.11.0-alpha1
```

* **No clock flag.** CPT 8.11 reads and changes the clock through the public clock API (`/v2/clock`), so
  `CAMUNDA_SYSTEM_CLOCKCONTROLLED` is **not** needed (✅).
* **No management API (port 9600) needed** with `data-deletion-mode: none`: not for the purge, not for the clock, not for the start-time
  isolation (✅ the suite passed with the port closed).
* With authorizations enabled, the CPT user needs `READ` and `UPDATE` on `SYSTEM` for the clock (🔎 docs, ❓ not tried).
* The runtime must not be older than CPT. An alpha1 runtime with the snapshot CPT works (✅).

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
aren't discovered.

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

**Dependencies** ([`process-tests/pom.xml`](../process-tests/pom.xml)). The tests use the Camunda client and CPT of `cpt.version`; the snapshot
needs the Camunda repository:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.camunda</groupId>
      <artifactId>camunda-bom</artifactId>
      <version>${cpt.version}</version>   <!-- 8.11.0-SNAPSHOT -->
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<repositories> <!-- only while cpt.version is a SNAPSHOT -->
  <repository>
    <id>camunda-public</id>
    <url>https://artifacts.camunda.com/artifactory/public</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

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
    data-deletion-mode: none        # don't purge the cluster (it would delete the deployed resources)
    remote:
      runtime-connection-timeout: PT1M
      # camunda-monitoring-api-address: http://127.0.0.1:9600   # default; only used by the purge
  client:
    mode: self-managed              # no addresses: the defaults are the local C8 Run
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
}
```

* **Plain selectors are enough** (`byElementId("UserTask_ApproveInvoice")`): without a purge CPT limits its queries to the data created after the
  test case started (✅ the suite passes twice in a row on the same cluster).
* **Timer tests** use `processTestContext.increaseTime(Duration.ofDays(5))` and work on a local cluster (✅ also with `data-deletion-mode: none`).
* **DMN, JSON test cases and the coverage report** work the same way (`@TestCaseSource` with `TestCaseRunner`, `assertThatDecision`).
  The coverage report (`target/coverage-report/report.html`) covers only the data of the run.

## 4. Run

```bash
c8 cluster start 8.11.0-alpha1                  # 1. cluster
mvn -pl process-application spring-boot:run     # 2. process application (second terminal, keeps running)
mvn -pl process-tests test                      # 3. tests: 21 of 21 pass
c8 cluster stop
```

## 5. Configuration reference

| What | Value | Why |
| --- | --- | --- |
| `camunda.process-test.runtime-mode` | `remote` | Use the running cluster. |
| `camunda.process-test.data-deletion-mode` | `none` | The default (`cluster_purge`) deletes everything on the cluster, including the deployed BPMN/DMN. [Limitation 1](CAPABILITIES.md#limitations-and-blockers) |
| `camunda.process-test.remote.runtime-connection-timeout` | `PT1M` | Wait for a cluster that is still starting. |
| `camunda.process-test.remote.camunda-monitoring-api-address` | default `http://127.0.0.1:9600` | Only used by the purge. Not needed with `none`. |
| `camunda.process-test.assertion.timeout` | default `PT10S`; `PT30S` on SaaS | The data is exported asynchronously. [Limitation 8](CAPABILITIES.md#limitations-and-blockers) |
| `camunda.process-test.clock-reset-enabled` | default `true`; `false` on SaaS | Reset the clock after each test. |
| `camunda.client.mode` and addresses | `self-managed` (defaults) or `saas` | Where the client of CPT and of the process application connect to. |

## 6. Run on SaaS (☁️ verified on a cluster on the INT stage)

SaaS has no management API, so the purge must be off. Run the process application and the tests with the `saas` profile
([`application-saas.yaml`](../process-tests/src/test/resources/application-saas.yaml)):

```yaml
camunda:
  process-test:
    runtime-mode: remote
    data-deletion-mode: none
    clock-reset-enabled: false
    assertion:
      timeout: PT30S
  client:
    mode: saas
    auth:
      client-id: ${CAMUNDA_CLIENT_ID}
      client-secret: ${CAMUNDA_CLIENT_SECRET}
    cloud:
      cluster-id: ${CAMUNDA_CLUSTER_ID}
      region: ${CAMUNDA_CLUSTER_REGION}
      domain: ${CAMUNDA_CLUSTER_DOMAIN:camunda.io}   # another stage, e.g. ultrawombat.com (INT)
```

```bash
set -a; . .env; set +a                                           # credentials, see .env.example
SPRING_PROFILES_ACTIVE=saas mvn -pl process-application spring-boot:run
mvn -pl process-tests test -Psaas                                # 19 of 21 pass, the 2 timer tests are skipped
```

**Keep the timer tests off on SaaS.** A clock jump delays the Elasticsearch exporter by the size of the jump and stalls the search index
([limitation 9](CAPABILITIES.md#limitations-and-blockers)). The demo disables them with `demo.clock-manipulation.enabled=false`.

## Recommendations

1. **Use a dedicated cluster, exclusively for CPT, and run one test job at a time.** CPT doesn't support parallel tests; clock, purge and the coverage report are
   cluster-wide (limitation 10).
2. **The process application deploys, the tests only verify.** Tests that deploy would override what the process application deployed.
3. **Set `data-deletion-mode: none`.** With the default purge, the first test deletes the deployed resources, and every later test fails.
4. **Rely on the start-time scoping** instead of unique IDs or process-instance selectors (limitation 6). Don't point `camunda-monitoring-api-address` to an unrelated cluster.
5. **Keep the workers out of the test JVM and don't mock a job type that a real worker serves**: they compete for the jobs (limitations 4 and 5). Assert the effects
   (path and variables) instead.
6. **Use the clock only on a local C8 Run with its default H2 storage.** Don't use it with Elasticsearch/OpenSearch (limitation 9). Make timer durations
   configurable in the process, so that other clusters can use short durations.
7. **Make unsupported tests switchable** (`@EnabledIf("${demo.clock-manipulation.enabled:true}")`) so that one suite runs on C8 Run and SaaS.
8. **Give the assertions more time on remote clusters** (`assertion.timeout: PT30S`).

## Limitations that matter here

Details, evidence and workarounds: [CAPABILITIES.md](CAPABILITIES.md#limitations-and-blockers).

* The purge is cluster-wide and deletes the deployed resources — keep it off (1).
* The clock is cluster-wide — never use it on a shared cluster (2); with an Elasticsearch exporter it also stalls indexing (9).
* No parallel tests, no other users on the cluster while the tests run (10).
* Mocks and real workers compete for the same job type (5).
* Not verified: an authenticated self-managed cluster (Basic, OIDC), a cluster with authorizations enabled, and how to expose the management API on Kubernetes (limitation 7, [capability matrix](CAPABILITIES.md#capability-matrix)).

See also the [version differences](CAPABILITIES.md#version-differences) and the [README](../README.md) to run the demo.
