package io.camunda.demo;

import static io.camunda.process.test.api.CamundaAssert.assertThatDecision;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tests the DMN decision "auto-approve-invoice" as deployed by the process application on the remote cluster. Adapted from the CPT example
 * (InvoiceDecisionTest). The decision is evaluated on its own, without the process.
 *
 * <p>The assertion uses the response of the evaluation, so it is scoped to this evaluation and
 * independent of other decision instances on the cluster.
 */
@SpringBootTest(
    classes = TestApplication.class,
    properties = "camunda.client.worker.defaults.enabled=false")
@CamundaSpringProcessTest
public class InvoiceDecisionTest {

  @Autowired private CamundaClient client;

  @BeforeEach
  void requireProcessApplication() {
    ProcessApplicationDeployment.requireDeployed(client);
  }

  @CsvSource(
      delimiter = '|',
      useHeadersInDisplayName = true,
      textBlock =
          """
          Amount | Supplier ID | Auto-approve |
             30  |     1089    |      true    |
             80  |     0815    |      true    |
            220  |     0815    |     false    |
            220  |     1234    |      true    |
           1000  |     1234    |     false    |
           1000  |     0405    |      true    |
           7500  |     0405    |     false    |
          """)
  @DisplayName("Should auto-approve an invoice")
  @ParameterizedTest
  void shouldAutoApproveInvoice(
      final int invoiceAmount, final String invoiceSupplierId, final boolean autoApprove) {
    // given
    final Map<String, Object> invoice =
        Map.of(
            "id", "INV-DECISION",
            "amount", invoiceAmount,
            "currency", "EUR",
            "supplier", Map.of("id", invoiceSupplierId, "name", "Supplier Inc."),
            "contactEmail", "accounting@supplier.com");

    // when
    final EvaluateDecisionResponse response =
        client
            .newEvaluateDecisionCommand()
            .decisionId("auto-approve-invoice")
            .variable("invoice", invoice)
            .send()
            .join();

    // then
    assertThatDecision(response).hasOutput(autoApprove);
  }
}
