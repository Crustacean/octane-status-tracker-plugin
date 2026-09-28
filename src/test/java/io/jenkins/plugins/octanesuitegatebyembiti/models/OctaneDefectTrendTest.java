package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneDefectTrendTest {
  private static final String STARTED_AT = "2026-06-30T08:00:00Z";

  @Test
  public void startsAtZeroAndAppendsRaisedAndClosedTotals() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 120_000L)
            .append("2026-06-30T08:00:30Z", heatMapWithOpenAndClosedDefects());

    assertEquals(2, trend.getPoints().size());
    assertEquals(30_000L, trend.getPoints().get(1).getElapsedMillis());
    assertEquals(3, trend.getOpenedTotal());
    assertEquals(2, trend.getOpenTotal());
    assertEquals(1, trend.getClosedTotal());
    assertEquals(2, trend.getPoints().get(1).getOpen());
  }

  @Test
  public void appendsCountsFromTheCanonicalDefectDatasetWithoutAHeatMap() {
    List<DefectRecord> defects =
        List.of(
            new DefectRecord("1", "Open", "High", "", "opened", "run-1", "", "", ""),
            new DefectRecord("2", "Closed", "Low", "", "closed", "run-2", "", "", ""));

    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 60_000L).append("2026-06-30T08:00:15Z", defects, 4);

    assertEquals(2, trend.getRaisedTotal());
    assertEquals(1, trend.getClosedTotal());
    assertEquals(4, trend.getPoints().get(1).getExecuted());
  }

  @Test
  public void replacesSameTimestampAndClampsToConfiguredDuration() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 60_000L)
            .append(20_000L, 1, 0, 3)
            .append(20_000L, 2, 1, 4)
            .append(90_000L, 4, 2, 6);

    assertEquals(3, trend.getPoints().size());
    assertEquals(2, trend.getPoints().get(1).getOpened());
    assertEquals(4, trend.getPoints().get(1).getExecuted());
    assertEquals(60_000L, trend.getPoints().get(2).getElapsedMillis());
  }

  @Test
  public void unavailablePollCarriesForwardLastKnownTotals() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 120_000L)
            .append(15_000L, 5, 2)
            .append(
                "2026-06-30T08:00:45Z",
                OctaneRiskHeatMap.unavailable("Octane did not return defect data."));

    assertEquals(5, trend.getOpenedTotal());
    assertEquals(2, trend.getClosedTotal());
    assertEquals(45_000L, trend.getPoints().get(2).getElapsedMillis());
  }

  @Test
  public void exposesColorsAndSerializablePointData() {
    Map<String, Object> values =
        OctaneDefectTrend.start(STARTED_AT, 60_000L).append(30_000L, 2, 1, 8).toMap();

    assertEquals("#ff6361", values.get("openedColor"));
    assertEquals("#7BE5B3", values.get("closedColor"));
    assertEquals(2, values.get("raisedTotal"));
    assertEquals(2, values.get("openedTotal"));
    assertEquals(1, values.get("closedTotal"));
    List<?> points = (List<?>) values.get("points");
    assertEquals(2, points.size());
    assertEquals(8, ((Map<?, ?>) points.get(1)).get("executed"));
    assertFalse(((List<?>) values.get("densityBuckets")).isEmpty());
  }

  @Test
  public void calculatesDensityFromNewDefectsAndNewExecutedTestsPerBucket() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 120_000L)
            .append(15_000L, 0, 0, 0)
            .append(30_000L, 2, 0, 4)
            .append(45_000L, 3, 0, 6);

    List<OctaneDefectTrend.DensityBucket> buckets = trend.getDensityBuckets();

    assertEquals(8, buckets.size());
    assertEquals(2, buckets.get(1).getNewDefects());
    assertEquals(4, buckets.get(1).getExecutedTests());
    assertEquals(0.5, buckets.get(1).getDensity(), 0.001);
    assertEquals(1, buckets.get(2).getNewDefects());
    assertEquals(2, buckets.get(2).getExecutedTests());
    assertEquals(0.5, buckets.get(2).getDensity(), 0.001);
  }

  @Test
  public void showsZeroTestDefectSpikeUsingDefectCount() {
    OctaneDefectTrend trend = OctaneDefectTrend.start(STARTED_AT, 60_000L).append(15_000L, 3, 0, 0);

    OctaneDefectTrend.DensityBucket bucket = trend.getDensityBuckets().get(0);

    assertEquals(3, bucket.getNewDefects());
    assertEquals(0, bucket.getExecutedTests());
    assertEquals(3.0, bucket.getDensity(), 0.001);
    assertTrue(bucket.isZeroTestSpike());
  }

  @Test
  public void keepsDensityBasedOnCumulativeRaisedDefectsWhenOpenDefectsClose() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 60_000L)
            .append(15_000L, 80, 0, 100)
            .append(30_000L, 120, 60, 150);

    List<OctaneDefectTrend.DensityBucket> buckets = trend.getDensityBuckets();

    assertEquals(80, buckets.get(0).getNewDefects());
    assertEquals(100, buckets.get(0).getExecutedTests());
    assertEquals(0.8, buckets.get(0).getDensity(), 0.001);
    assertEquals(40, buckets.get(1).getNewDefects());
    assertEquals(50, buckets.get(1).getExecutedTests());
    assertEquals(0.8, buckets.get(1).getDensity(), 0.001);
    assertEquals(120, trend.getRaisedTotal());
    assertEquals(60, trend.getOpenTotal());
    assertEquals(60, trend.getClosedTotal());
  }

  @Test
  public void keepsCumulativeRaisedAndClosedLinesSeparateFromNetOpenHeader() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 60_000L).append(15_000L, 64, 4, 20);

    OctaneDefectTrend.Point latest = trend.getPoints().get(1);
    assertEquals(60, trend.getOpenTotal());
    assertEquals(4, trend.getClosedTotal());
    assertEquals(64, trend.getRaisedTotal());
    assertEquals(64, latest.getOpened());
    assertEquals(4, latest.getClosed());
  }

  @Test
  public void scalesDensityDataToThreeThousandDefectsWithoutOverflow() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 60_000L)
            .append(15_000L, 1000, 0, 0)
            .append(30_000L, 3000, 0, 0);

    List<OctaneDefectTrend.DensityBucket> buckets = trend.getDensityBuckets();

    assertEquals(1000.0, buckets.get(0).getDensity(), 0.001);
    assertEquals(2000.0, buckets.get(1).getDensity(), 0.001);
    assertTrue(buckets.get(0).isZeroTestSpike());
    assertTrue(buckets.get(1).isZeroTestSpike());
    assertEquals(3000, trend.getRaisedTotal());
  }

  @Test
  public void returnsZeroDensityForAWindowWithoutDefects() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 60_000L).append(15_000L, 0, 0, 25);

    OctaneDefectTrend.DensityBucket bucket = trend.getDensityBuckets().get(0);

    assertEquals(0, bucket.getNewDefects());
    assertEquals(25, bucket.getExecutedTests());
    assertEquals(0.0, bucket.getDensity(), 0.001);
    assertFalse(bucket.isZeroTestSpike());
  }

  @Test
  public void excludesInProgressRunsFromTheDensityExecutionDenominator() {
    List<RunRecord> runs = new java.util.ArrayList<>();
    for (int index = 0; index < 8; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed"));
    }
    runs.add(new RunRecord("failed-1", "Failed", "failed"));
    runs.add(new RunRecord("active-1", "Active", "In Progress"));
    GateMetrics metrics = GateMetrics.fromRuns(runs, defaultClassifier());

    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 60_000L).append(15_000L, 1, 0, metrics.getExecuted());

    OctaneDefectTrend.DensityBucket bucket = trend.getDensityBuckets().get(0);
    assertEquals(9, bucket.getExecutedTests());
    assertEquals(1.0 / 9.0, bucket.getDensity(), 0.000001);
  }

  @Test
  public void capsLongRunDensityBucketsAtFifteenMinutes() {
    OctaneDefectTrend trend =
        OctaneDefectTrend.start(STARTED_AT, 9_000_000L)
            .append(900_000L, 1, 0, 10)
            .append(9_000_000L, 2, 1, 20);

    List<OctaneDefectTrend.DensityBucket> buckets = trend.getDensityBuckets();

    assertEquals(10, buckets.size());
    assertEquals(0L, buckets.get(0).getStartMillis());
    assertEquals(900_000L, buckets.get(0).getEndMillis());
    assertEquals(8_100_000L, buckets.get(9).getStartMillis());
    assertEquals(9_000_000L, buckets.get(9).getEndMillis());
  }

  private OctaneRiskHeatMap heatMapWithOpenAndClosedDefects() {
    List<DefectRecord> defects =
        List.of(
            new DefectRecord("1", "Open critical", "critical", "", "new", "1", "1", "", ""),
            new DefectRecord("2", "Open high", "high", "", "opened", "2", "2", "", ""),
            new DefectRecord("3", "Closed", "medium", "", "closed", "3", "3", "", ""));
    return OctaneRiskHeatMap.of(
        new OctaneRiskHeatMapNode("workspace", "Workspace", 80, 3, 3, List.of()),
        3,
        3,
        0,
        1,
        OctaneDefectSeveritySummary.fromDefects(defects));
  }

  private StatusClassifier defaultClassifier() {
    return new StatusClassifier(
        StatusClassifier.DEFAULT_PASSED_STATUSES,
        StatusClassifier.DEFAULT_FAILED_STATUSES,
        StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
        StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }
}
