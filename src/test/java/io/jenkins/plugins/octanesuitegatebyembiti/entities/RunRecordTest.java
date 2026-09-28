package io.jenkins.plugins.octanesuitegatebyembiti.entities;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RunRecordTest {
  @Test
  public void convenienceConstructorNeverCopiesSuiteOwnerIntoExecutionActor() {
    RunRecord run = new RunRecord("101", "Run", "passed", "Jane Doe");

    assertEquals("Jane Doe", run.getSuiteOwnerName());
    assertEquals("", run.getExecutionActorName());
  }

  @Test
  public void immutableCopiesKeepSuiteOwnerAndExecutionActorIndependent() {
    RunRecord original =
        new RunRecord(
            "101", "Run", "passed", "Jenkins Agent", "Jane Doe", "test-1", "Test", "", "");

    RunRecord differentActor = original.withExecutionActorName("Human Runner");
    RunRecord differentOwner = original.withSuiteOwnerName("Alex Owner");

    assertEquals("Jenkins Agent", original.getExecutionActorName());
    assertEquals("Jane Doe", original.getSuiteOwnerName());
    assertEquals("Human Runner", differentActor.getExecutionActorName());
    assertEquals("Jane Doe", differentActor.getSuiteOwnerName());
    assertEquals("Jenkins Agent", differentOwner.getExecutionActorName());
    assertEquals("Alex Owner", differentOwner.getSuiteOwnerName());
  }
}
