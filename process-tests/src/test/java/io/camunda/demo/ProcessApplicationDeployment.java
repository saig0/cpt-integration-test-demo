package io.camunda.demo;

import io.camunda.client.CamundaClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Verifies that the process application has deployed its resources to the cluster. The tests don't
 * deploy the resources themselves, so they can't override what the process application has
 * deployed. If the resources are missing, the tests fail fast with a clear message.
 */
final class ProcessApplicationDeployment {

  static final String PROCESS_ID = "Process_InvoiceApproval";
  static final String DECISION_ID = "auto-approve-invoice";

  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static volatile boolean verified = false;
  private static volatile IllegalStateException failure = null;

  private ProcessApplicationDeployment() {}

  static void requireDeployed(final CamundaClient client) {
    if (verified) {
      return;
    }
    if (failure != null) {
      // fail fast: don't wait again for every test
      throw failure;
    }

    final Instant deadline = Instant.now().plus(TIMEOUT);
    while (true) {
      final boolean processDeployed =
          !client
              .newProcessDefinitionSearchRequest()
              .filter(f -> f.processDefinitionId(PROCESS_ID))
              .send()
              .join()
              .items()
              .isEmpty();
      final boolean decisionDeployed =
          !client
              .newDecisionDefinitionSearchRequest()
              .filter(f -> f.decisionDefinitionId(DECISION_ID))
              .send()
              .join()
              .items()
              .isEmpty();

      if (processDeployed && decisionDeployed) {
        verified = true;
        return;
      }
      if (Instant.now().isAfter(deadline)) {
        failure =
            new IllegalStateException(
                "The process application has not deployed its resources to the cluster [process: "
                    + PROCESS_ID
                    + " deployed: "
                    + processDeployed
                    + ", decision: "
                    + DECISION_ID
                    + " deployed: "
                    + decisionDeployed
                    + "]. Start the process application (mvn -pl process-application spring-boot:run)"
                    + " before the tests. The tests don't deploy resources. Note: a purge of the cluster"
                    + " (camunda.process-test.data-deletion-mode=cluster_purge) deletes the deployed"
                    + " resources.");
        throw failure;
      }
      try {
        Thread.sleep(1000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
