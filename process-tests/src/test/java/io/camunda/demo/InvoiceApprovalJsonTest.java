package io.camunda.demo;

import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.testCases.TestCase;
import io.camunda.process.test.api.testCases.TestCaseRunner;
import io.camunda.process.test.api.testCases.TestCaseSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.EnabledIf;

/**
 * Runs the JSON test cases against the remote cluster. Adapted from the CPT example
 * (InvoiceApprovalJsonTest). InvoiceApprovalTest is the equivalent class that uses the Java API.
 *
 * <p>Differences to the original example: the test cases don't mock the job workers (the external
 * workers handle the jobs, see the workers module), and they assert the variables that the workers
 * return.
 *
 * <p>JSON test cases have fixed data and select by process definition ID, not by process instance.
 * They rely on the cleanup between the tests (C8 Run), and are less suited for a shared cluster than
 * the Java tests.
 */
@SpringBootTest(
    classes = TestApplication.class,
    properties = "camunda.client.worker.defaults.enabled=false")
@CamundaSpringProcessTest
public class InvoiceApprovalJsonTest {

  @Autowired private TestCaseRunner testCaseRunner;

  @ParameterizedTest
  @TestCaseSource(directory = "/test-cases")
  void shouldPass(final TestCase testCase, final String filename) {
    // given: the process definitions are deployed by the Spring Boot application
    // when/then: run and verify the test case
    testCaseRunner.run(testCase);
  }

  /** Test cases that manipulate the clock. Not possible on SaaS (see application-saas.yaml). */
  @EnabledIf(expression = "${demo.clock-manipulation.enabled:true}", loadContext = true)
  @ParameterizedTest
  @TestCaseSource(directory = "/test-cases-clock")
  void shouldPassWithClockManipulation(final TestCase testCase, final String filename) {
    testCaseRunner.run(testCase);
  }
}
