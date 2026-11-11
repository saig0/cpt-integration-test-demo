package io.camunda.demo;

import static io.camunda.process.test.api.CamundaAssert.assertThatDecision;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.CamundaAssert.assertThatUserTask;
import static io.camunda.process.test.api.assertions.DecisionSelectors.byProcessInstanceKey;
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
 * Process tests for the invoice approval process. They run against a remote cluster (see {@code
 * application.yaml}). The process application deploys the process and runs the job workers that
 * simulate the external services and custom connectors. The tests verify the path through the
 * process and the variables that the workers return.
 *
 * <p>CPT 8.9 doesn't limit its queries to the start time of the test case, and the cluster isn't
 * purged between the tests. Each test therefore uses a unique invoice ID, and the user task
 * selectors are scoped to the started process instance. An unscoped {@code
 * byElementId("UserTask_ApproveInvoice")} could match (and complete!) the user task of another
 * test.
 */
@SpringBootTest(classes = TestApplication.class)
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

  @DisplayName("Path when the invoice is approved automatically by the decision")
  @Test
  void autoApproval() {
    // given: a small invoice (< 50 EUR) that the DMN decision "auto-approve-invoice" approves
    final String invoiceId = newInvoiceId("AUTO");

    // when
    final ProcessInstanceEvent processInstance = startProcess(invoiceId, 30, "1089");

    // then: no human is involved
    assertThatProcessInstance(processInstance)
        .hasCompletedElementsInOrder(
            byId("StartEvent_InvoiceReceived"),
            byId("Activity_13m8dmh"),
            byId("ServiceTask_ArchiveInvoice"),
            byId("ServiceTask_AddInvoiceAccounting"),
            byId("EndEvent_InvoiceApproved"))
        .hasNotActivatedElements(byId("UserTask_ApproveInvoice"))
        .hasVariable("auto_approve", true)
        .hasVariable("accountingEntryId", "ACC-" + invoiceId)
        .isCompleted();
    assertThatDecision(byProcessInstanceKey(processInstance.getProcessInstanceKey()))
        .hasOutput(true);
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
    // 12000 EUR from supplier 0815: the decision requires a manual approval
    return startProcess(invoiceId, 12000, "0815");
  }

  private ProcessInstanceEvent startProcess(
      final String invoiceId, final int amount, final String supplierId) {
    final Map<String, Object> invoice =
        Map.of(
            "id",
            invoiceId,
            "amount",
            amount,
            "currency",
            "EUR",
            "supplier",
            Map.of("id", supplierId, "name", "Acme GmbH"),
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
