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
 * Runs the JSON test cases against the remote cluster. They cover the same paths as {@link
 * InvoiceApprovalTest} and the decision as {@link InvoiceDecisionTest}.
 *
 * <p>JSON test cases have fixed data and select by process definition ID, not by process instance.
 * They rely on the start-time scoping of CPT 8.10+ when the cluster isn't purged between the tests.
 * The class is disabled with {@code demo.json-tests.enabled=false}.
 */
@EnabledIf(expression = "${demo.json-tests.enabled:true}", loadContext = true)
@SpringBootTest(classes = TestApplication.class)
@CamundaSpringProcessTest
public class InvoiceApprovalJsonTest {

  @Autowired private TestCaseRunner testCaseRunner;

  @ParameterizedTest
  @TestCaseSource(directory = "/test-cases")
  void shouldPass(final TestCase testCase, final String filename) {
    // given: the process definitions are deployed by the process application
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
