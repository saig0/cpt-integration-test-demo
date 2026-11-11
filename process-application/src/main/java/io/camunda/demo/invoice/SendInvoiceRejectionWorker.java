package io.camunda.demo.invoice;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Replaces the HTTP JSON connector (io.camunda:http-json:1) of the original example. It simulates
 * a custom connector that notifies the supplier about the rejected invoice.
 */
@Component
public class SendInvoiceRejectionWorker {

  private static final Logger LOG = LoggerFactory.getLogger(SendInvoiceRejectionWorker.class);

  @JobWorker(type = "send-invoice-rejection")
  public Map<String, Object> sendInvoiceRejection(
      @Variable("invoiceId") final String invoiceId,
      @Variable("recipient") final String recipient,
      @Variable("rejectionReason") final String rejectionReason) {
    LOG.info(
        "Sending rejection of invoice {} to {} (reason: {})", invoiceId, recipient, rejectionReason);

    return Map.of("rejectionNotificationId", "REJECTION-" + invoiceId);
  }
}
