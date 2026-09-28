package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.AbortException;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;

public class GateFailedException extends AbortException {
  private static final long serialVersionUID = 1L;

  private final transient GateResult result;

  GateFailedException(String message, GateResult result) {
    super(message);
    this.result = result;
  }

  public GateResult getResult() {
    return result;
  }
}
