package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class GateRequestLimitsTest {
  @Test
  public void clampsUserControlledPollingAndRetentionLimits() {
    GateRequest request = new GateRequest("server", "1");

    assertEquals(250, request.getLimitForMetricRunsInSuite());
    request.setPollIntervalSeconds(Integer.MAX_VALUE);
    request.setTimeoutMinutes(Integer.MAX_VALUE);
    request.setTimeoutMinutesExtended(Integer.MAX_VALUE);
    request.setRiskHeatMapMaxDefects(Integer.MAX_VALUE);
    request.setLimitForMetricRunsInSuite(0);

    assertEquals(GateRequest.MAX_POLL_INTERVAL_SECONDS, request.getPollIntervalSeconds());
    assertEquals(GateRequest.MAX_TIMEOUT_MINUTES, request.getTimeoutMinutes());
    assertEquals(GateRequest.MAX_TIMEOUT_MINUTES, request.getTimeoutMinutesExtended());
    assertEquals(GateRequest.MAX_RISK_HEAT_MAP_DEFECTS, request.getRiskHeatMapMaxDefects());
    assertEquals(1, request.getLimitForMetricRunsInSuite());
  }

  @Test
  public void rejectsUnboundedOrMultilineExplicitQueries() {
    GateRequest request = new GateRequest("server", "1");

    assertThrows(
        IllegalArgumentException.class,
        () -> request.setRiskHeatMapDefectQuery("phase EQ ^open^\nforged log line"));
    assertThrows(
        IllegalArgumentException.class, () -> request.setRiskHeatMapDefectQuery("x".repeat(4_097)));
  }
}
