package io.camunda.demo;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.CamundaAssert.assertThatUserTask;
import static io.camunda.process.test.api.assertions.ElementSelectors.byId;
import static io.camunda.process.test.api.assertions.UserTaskSelectors.byElementId;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.UserTaskSelector;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.EnabledIf;

/**
 * Process tests for the invoice approval process, adapted from the CPT example (InvoiceApprovalTest
 * in camunda/camunda, testing/camunda-process-test-example).
 *
 * <p>Differences to the original example:
 *
 * <ul>
 *   <li>The tests run against a remote cluster (C8 Run or SaaS), see {@code application.yaml}.
 *   <li>The job workers are NOT started by the tests and NOT mocked. They run as a separate
 *       application (module {@code workers}) and simulate external services and custom connectors.
 *       The tests verify their effects through the variables they return to the process instance.
 *   <li>The HTTP connector of the rejection task is replaced by the job worker {@code
 *       send-invoice-rejection}.
 * </ul>
 *
 * <p>Each test uses a unique invoice ID, and the user task selectors are scoped to the started
 * process instance. That keeps the tests independent on a shared cluster where CPT doesn't delete
 * the data between the tests (SaaS). An unscoped {@code byElementId("UserTask_ApproveInvoice")}
 * could match (and complete!) the user task of another test or process instance.
 */
@SpringBootTest(
    classes = TestApplication.class,
    properties = {
      // Don't start job workers in the test JVM. There are none, but be explicit: the workers are
      // external, and a worker in the test would compete with them.
      "camunda.client.worker.defaults.enabled=false",
    })
@CamundaSpringProcessTest
public class InvoiceApprovalTest {

  private static final String PROCESS_ID = "Process_InvoiceApproval";

  @Autowired private CamundaClient client;
  @Autowired private CamundaProcessTestContext processTestContext;

  @DisplayName("Happy path through the process")
  @Test
  void happyPath() {
    // given
    final String invoiceId = newInvoiceId("HAPPY");
    final ProcessInstanceEvent processInstance = startProcess(invoiceId);

    // when: a human approves the invoice
    assertThatUserTask(approveInvoiceTask(processInstance)).isCreated().hasAssignee("Zee");
    processTestContext.completeUserTask(
        approveInvoiceTask(processInstance), Map.of("approved", true));

    // then: the external workers archived the invoice and added it to the accounting system
    assertThatProcessInstance(processInstance)
        .hasCompletedElementsInOrder(
            byId("StartEvent_InvoiceReceived"),
            byId("UserTask_ApproveInvoice"),
            byId("ServiceTask_ArchiveInvoice"),
            byId("ServiceTask_AddInvoiceAccounting"),
            byId("EndEvent_InvoiceApproved"))
        .hasVariable("archiveReference", "ARCHIVE-" + invoiceId)
        .hasVariable("accountingEntryId", "ACC-" + invoiceId)
        .isCompleted();
  }

  @DisplayName("Path when the invoice was rejected")
  @Test
  void rejectionPath() {
    // given
    final String invoiceId = newInvoiceId("REJECTED");
    final ProcessInstanceEvent processInstance = startProcess(invoiceId);

    // when: a human rejects the invoice
    assertThatUserTask(approveInvoiceTask(processInstance)).isCreated().hasAssignee("Zee");
    processTestContext.completeUserTask(
        approveInvoiceTask(processInstance),
        Map.of("approved", false, "rejectionReason", "it is a test case :-)"));

    // then: the external worker (the replacement of the HTTP connector) sent the rejection
    assertThatProcessInstance(processInstance)
        .hasCompletedElementsInOrder(
            byId("StartEvent_InvoiceReceived"),
            byId("UserTask_ApproveInvoice"),
            byId("Gateway_Approved"),
            byId("ServiceTask_SendRejection"),
            byId("EndEvent_InvoiceRejected"))
        .hasVariable("rejectionNotificationId", "REJECTION-" + invoiceId)
        .isCompleted();
  }

  /**
   * Requires to manipulate the clock of the cluster. This is possible with C8 Run if the clock is
   * controllable (see README), but not with SaaS. The test is disabled if {@code
   * demo.clock-manipulation.enabled=false} (see application-saas.yaml).
   */
  @DisplayName("Path when there is a timeout on the approval")
  @EnabledIf(expression = "${demo.clock-manipulation.enabled:true}", loadContext = true)
  @Test
  void approvalTimeout() {
    // given
    final String invoiceId = newInvoiceId("TIMEOUT");
    final ProcessInstanceEvent processInstance = startProcess(invoiceId);

    assertThatProcessInstance(processInstance).hasActiveElements(byId("UserTask_ApproveInvoice"));

    // when: the approval takes too long
    processTestContext.increaseTime(Duration.ofDays(5));

    // then: the invoice is approved automatically
    assertThatProcessInstance(processInstance)
        .hasCompletedElementsInOrder(
            byId("StartEvent_InvoiceReceived"),
            byId("ServiceTask_ArchiveInvoice"),
            byId("ServiceTask_AddInvoiceAccounting"),
            byId("EndEvent_InvoiceApproved"))
        .hasTerminatedElements(byId("UserTask_ApproveInvoice"))
        .hasVariable("accountingEntryId", "ACC-" + invoiceId)
        .isCompleted();
  }

  @DisplayName("Path when the archive system raises an error")
  @Test
  void archiveSystemError() {
    // given: the simulated legacy archive system fails for this invoice
    final String invoiceId = newInvoiceId("LEGACY-ERROR");
    final ProcessInstanceEvent processInstance = startProcess(invoiceId);

    // when: a human approves the invoice
    assertThatUserTask(approveInvoiceTask(processInstance)).isCreated();
    processTestContext.completeUserTask(
        approveInvoiceTask(processInstance), Map.of("approved", true));

    // then: the error from the external worker leads to the manual archiving
    assertThatUserTask(manuallyArchiveTask(processInstance))
        .isCreated()
        .hasCandidateGroup("archive-team");
    processTestContext.completeUserTask(manuallyArchiveTask(processInstance));

    assertThatProcessInstance(processInstance)
        .hasCompletedElementsInOrder(
            byId("StartEvent_InvoiceReceived"),
            byId("UserTask_ApproveInvoice"),
            byId("UserTask_ManuallyArchiveInvoice"),
            byId("ServiceTask_AddInvoiceAccounting"),
            byId("EndEvent_InvoiceApproved"))
        .hasTerminatedElements(byId("ServiceTask_ArchiveInvoice"))
        .hasVariable("accountingEntryId", "ACC-" + invoiceId)
        .isCompleted();
  }

  // Scope the selectors to the process instance of the test (see the class comment).
  private static UserTaskSelector approveInvoiceTask(final ProcessInstanceEvent processInstance) {
    return byElementId("UserTask_ApproveInvoice", processInstance.getProcessInstanceKey());
  }

  private static UserTaskSelector manuallyArchiveTask(final ProcessInstanceEvent processInstance) {
    return byElementId("UserTask_ManuallyArchiveInvoice", processInstance.getProcessInstanceKey());
  }

  private ProcessInstanceEvent startProcess(final String invoiceId) {
    final Map<String, Object> invoice =
        Map.of(
            "id",
            invoiceId,
            "amount",
            12000,
            "currency",
            "EUR",
            "supplier",
            Map.of("id", "0815", "name", "Acme GmbH"),
            "contactEmail",
            "accounting@acme.com");

    return client
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .variables(Map.of("approver", "Zee", "invoice", invoice))
        .send()
        .join();
  }

  private static String newInvoiceId(final String scenario) {
    return "INV-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
