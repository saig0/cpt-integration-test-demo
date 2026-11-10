package io.camunda.demo.workers;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Simulates the accounting system. */
@Component
public class AddInvoiceToAccountingWorker {

  private static final Logger LOG = LoggerFactory.getLogger(AddInvoiceToAccountingWorker.class);

  @JobWorker(type = "add-invoice-to-accounting")
  public Map<String, Object> addInvoiceToAccounting(
      @Variable("invoiceId") final String invoiceId) {
    LOG.info("Adding invoice {} to the accounting system", invoiceId);

    return Map.of("accountingEntryId", "ACC-" + invoiceId);
  }
}
