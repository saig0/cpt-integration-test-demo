package io.camunda.demo;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot configuration of the tests. It deploys the BPMN and DMN resources to the cluster. It does not
 * contain job workers: they run as a separate application (see the {@code workers} module).
 */
@SpringBootApplication
@Deployment(resources = {"classpath*:/bpmn/**/*.bpmn", "classpath*:/bpmn/**/*.dmn"})
public class TestApplication {}
