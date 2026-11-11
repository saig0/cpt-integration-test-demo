package io.camunda.demo;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot configuration of the tests. It contains neither the process resources nor job workers:
 * the process application (module {@code process-application}) owns and deploys the BPMN and DMN
 * resources and runs the job workers. The tests only verify the process as it is deployed there.
 */
@SpringBootApplication
public class TestApplication {}
