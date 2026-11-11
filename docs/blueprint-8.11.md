# Blueprint: process integration tests with CPT on a remote cluster — 8.11

How to set up Camunda Process Test (CPT) so that process tests run against an **already running cluster**, while the process
resources and the job workers live in a **separate process application**. The evidence, the verification status and the background
are in [CAPABILITIES.md](CAPABILITIES.md). The concrete commands to run the demo are in the [README](../README.md).

> **Versions.** The cluster and the process application use `8.11.0-alpha1`; the tests use the CPT **`8.11.0-SNAPSHOT`**, because the public
> clock API was merged after the alpha1 release. When the next alpha is released, set `cpt.version` to it and remove the snapshot repository.
> Other versions: [8.10](blueprint-8.10.md), [8.9](blueprint-8.9.md).

## The setup

| Component | Role |
| --- | --- |
| **Cluster** | A Camunda 8 cluster that CPT uses **exclusively**: a local C8 Run, a dedicated cluster in CI, or SaaS. |
| **Process application** | Owns the BPMN/DMN, **deploys** them and runs the job workers (or connectors). Independent of the tests. |
| **Process tests** | CPT tests with `runtime-mode: remote`. They start instances, complete user tasks and assert. They **don't deploy resources and don't run workers**. |

Start order: **cluster → process application → tests**. If the resources aren't deployed, creating an instance is rejected with
`NOT_FOUND … process definition … none found`.

How this setup works:

* **The process application owns the resources; the tests only verify the deployed process.** A test that deploys would override what the process application deployed.
* **The cluster is not purged.** CPT's default cleanup wipes *everything* on the cluster after each test, including the resources the process application
  deployed. So the tests run with `data-deletion-mode: none`.
* **Isolation comes from CPT's start-time scoping.** Without a purge, CPT limits its queries (assertions, utilities, the coverage report) to the data created after the test case
  started. Plain selectors such as `byElementId("UserTask_ApproveInvoice")` are enough.
* **The workers are external.** The tests neither start nor mock them; they assert the effects (path and variables).

## 1. The cluster

* A Camunda 8 cluster of version 8.11. The runtime must not be older than CPT.
* Reachable by the tests and the process application: REST (`http://localhost:8080`) and gRPC (`http://localhost:26500`) for a local C8 Run, or the SaaS endpoints.
* **No clock flag needed.** CPT 8.11 reads and changes the clock through the public clock API (`/v2/clock`), so the cluster doesn't have to be started with a controllable clock.
* **The management API (port 9600) isn't needed** with `data-deletion-mode: none`: not for the cleanup, the clock or the start-time isolation.
* Dedicated to CPT while the tests run (see the [recommendations](#recommendations)).
* The secondary storage matters for timer tests: don't move the clock on a cluster with an Elasticsearch/OpenSearch exporter ([limitation 9](CAPABILITIES.md#limitations-and-blockers)).

## 2. The process application

It is independent of CPT, and how you run it is up to your environment: your own application, your job workers, a connector runtime. What the tests need from it:

* it is connected to the **same cluster** as the tests;
* it has **deployed the BPMN/DMN** before the tests start (the tests never deploy);
* the **job workers/connectors for all job types of the process are running** while the tests run;
* it **keeps running across test runs** — nothing in the test run removes its deployments.

The demo implements it as a Spring Boot application with `@Deployment` and `@JobWorker` ([`process-application`](../process-application)).

## 3. The test project

**Dependencies** ([`process-tests/pom.xml`](../process-tests/pom.xml)). The tests use the Camunda client and CPT of `cpt.version`; the snapshot needs the Camunda repository:

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
    data-deletion-mode: none        # don't purge the cluster: it would delete the deployed resources
    remote:
      runtime-connection-timeout: PT1M
  client:
    mode: self-managed              # no addresses: the defaults are a local cluster
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

* **Timer tests** use `processTestContext.increaseTime(Duration.ofDays(5))`. They work together with `data-deletion-mode: none` on a local cluster.
* **DMN, JSON test cases and the coverage report** work the same way (`@TestCaseSource` with `TestCaseRunner`, `assertThatDecision`).
  The coverage report (`target/coverage-report/report.html`) covers only the data of the run.

## 4. Configuration reference

| What | Value | Why |
| --- | --- | --- |
| `camunda.process-test.runtime-mode` | `remote` | Use the running cluster. |
| `camunda.process-test.data-deletion-mode` | `none` | The default (`cluster_purge`) deletes everything on the cluster, including the deployed BPMN/DMN. |
| `camunda.process-test.remote.runtime-connection-timeout` | `PT1M` | Wait for a cluster that is still starting. |
| `camunda.process-test.remote.camunda-monitoring-api-address` | default `http://127.0.0.1:9600` | Only used by the purge. Not needed with `none`. |
| `camunda.process-test.assertion.timeout` | default `PT10S`; `PT30S` on SaaS | The data is exported asynchronously. |
| `camunda.process-test.clock-reset-enabled` | default `true`; `false` on SaaS | Reset the clock after each test. |
| `camunda.client.mode` and addresses | `self-managed` (defaults) or `saas` | Where the client of CPT and of the process application connect to. |

## 5. Run on SaaS

SaaS has no management API, so the cleanup must be off. Use the `saas` profile for the process application and the tests
([`application-saas.yaml`](../process-tests/src/test/resources/application-saas.yaml)); the credentials come from environment variables, see the [README](../README.md#run-on-camunda-8-saas):

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

**Keep the timer tests off on SaaS.** A clock jump delays the Elasticsearch exporter by the size of the jump and stalls the search index
([limitation 9](CAPABILITIES.md#limitations-and-blockers)). The demo switches them off with `demo.clock-manipulation.enabled=false`.

## Recommendations

1. **Use a dedicated cluster, exclusively for CPT, and run one test job at a time.** CPT doesn't support parallel tests; clock and coverage report are
   cluster-wide ([limitation 10](CAPABILITIES.md#limitations-and-blockers)).
2. **Rely on the start-time scoping** instead of unique IDs or process-instance selectors ([limitation 6](CAPABILITIES.md#limitations-and-blockers)).
3. **Keep the workers out of the test JVM and don't mock a job type that a real worker serves**: they compete for the jobs
   ([limitations 4 and 5](CAPABILITIES.md#limitations-and-blockers)).
4. **Use the clock only on a local C8 Run with its default H2 storage.** Make timer durations configurable in the process, so that other clusters can use short durations.
5. **Make unsupported tests switchable** (`@EnabledIf("${demo.clock-manipulation.enabled:true}")`), so that one suite runs on C8 Run and SaaS.
6. **Give the assertions more time on remote clusters** (`assertion.timeout: PT30S`).

## Limitations that matter here

Details and workarounds: [CAPABILITIES.md](CAPABILITIES.md#limitations-and-blockers).

* The clock is cluster-wide (2); with an Elasticsearch exporter a clock jump also stalls indexing (9). No timer tests on SaaS.
* No parallel tests, no other users on the cluster while the tests run (10).
* Mocks and real workers compete for the same job type (5).

See also the [version differences](CAPABILITIES.md#version-differences).
