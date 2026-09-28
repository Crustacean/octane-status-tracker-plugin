package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OctaneGatePieSliceTest {
  @Test
  public void usesBoundedRadiusForSegmentedDonutSlices() {
    OctaneGateStatusCount passed =
        new OctaneGateStatusCount(OctaneGateStatusBucket.PASSED, 50, 100);

    OctaneGatePieSlice slice = new OctaneGatePieSlice(passed, -90.0, 90.0);

    assertFalse(slice.isFullCircle());
    assertTrue(slice.getPath().contains("46.000 46.000"));
    assertTrue(slice.getPath().contains("50.000 4.000"));
    assertTrue(slice.getPath().contains("50.000 96.000"));
  }

  @Test
  public void representsFullDistributionAsSingleCircle() {
    OctaneGateStatusCount passed =
        new OctaneGateStatusCount(OctaneGateStatusBucket.PASSED, 100, 100);

    OctaneGatePieSlice slice = new OctaneGatePieSlice(passed, -90.0, 270.0);

    assertTrue(slice.isFullCircle());
    assertEquals("", slice.getPath());
    assertEquals("Passed", slice.getLabel());
    assertEquals(100, slice.getCount());
  }
}
