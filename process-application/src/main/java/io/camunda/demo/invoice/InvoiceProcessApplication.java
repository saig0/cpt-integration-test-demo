package io.camunda.demo.invoice;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The invoice process application. It owns the process resources (BPMN and DMN in {@code
 * src/main/resources/bpmn}) and the job workers of the process.
 *
 * <p>It is started independently of the tests and connects to the same Camunda cluster as the
 * tests. On startup, it deploys its resources to the cluster. The workers simulate the external
 * services and custom connectors of the process. The tests (module {@code process-tests}) neither
 * deploy nor change these resources: they verify the process as it is deployed by the process
 * application.
 */
@SpringBootApplication
@Deployment(resources = {"classpath*:/bpmn/**/*.bpmn", "classpath*:/bpmn/**/*.dmn"})
public class InvoiceProcessApplication {

  public static void main(final String[] args) {
    SpringApplication.run(InvoiceProcessApplication.class, args);
  }
}
