package io.camunda.demo.invoice;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.exception.BpmnError;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simulates the (legacy) archive system. */
@Component
public class ArchiveInvoiceWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ArchiveInvoiceWorker.class);

  /** Invoices with this marker in their ID make the simulated legacy archive system fail. */
  public static final String LEGACY_ERROR_MARKER = "LEGACY-ERROR";

  @JobWorker(type = "archive-invoice")
  public Map<String, Object> archiveInvoice(@Variable("invoiceId") final String invoiceId) {
    LOG.info("Archiving invoice {}", invoiceId);

    if (invoiceId.contains(LEGACY_ERROR_MARKER)) {
      throw new BpmnError(
          "LEGACY_ERROR_ARCHIVE", "The archive system had a problem with invoice " + invoiceId);
    }

    return Map.of("archiveReference", "ARCHIVE-" + invoiceId);
  }
}
