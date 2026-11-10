package io.camunda.demo.workers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stand-alone application that hosts the job workers of the invoice process.
 *
 * <p>It simulates the external services and custom connectors of the system under test. It is
 * started independently of the tests and connects to the same Camunda cluster as the tests. Note
 * that it deliberately does not deploy any process: the tests (or the process application) own the
 * deployment.
 */
@SpringBootApplication
public class WorkersApplication {

  public static void main(final String[] args) {
    SpringApplication.run(WorkersApplication.class, args);
  }
}
