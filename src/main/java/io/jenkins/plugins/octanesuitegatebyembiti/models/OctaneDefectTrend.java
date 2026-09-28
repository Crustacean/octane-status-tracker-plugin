package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OctaneDefectTrend implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final int MAX_POINTS = 2000;

  public static final String OPENED_COLOR = "#ff6361";
  public static final String CLOSED_COLOR = "#7BE5B3";

  private final String startedAt;
  private final long durationMillis;
  private final List<Point> points;

  private OctaneDefectTrend(String startedAt, long durationMillis, List<Point> points) {
    this.startedAt = startedAt;
    this.durationMillis = Math.max(1L, durationMillis);
    this.points = List.copyOf(points);
  }

  public static OctaneDefectTrend start(String startedAt, long durationMillis) {
    return new OctaneDefectTrend(startedAt, durationMillis, List.of(new Point(0L, 0, 0, 0)));
  }

  public OctaneDefectTrend append(String updatedAt, OctaneRiskHeatMap heatMap) {
    return append(updatedAt, heatMap, latestPoint().getExecuted());
  }

  public OctaneDefectTrend append(String updatedAt, OctaneRiskHeatMap heatMap, int executed) {
    Point latest = latestPoint();
    int opened = latest.getOpened();
    int closed = latest.getClosed();
    if (heatMap != null && heatMap.isEnabled() && heatMap.isAvailable()) {
      OctaneDefectSeveritySummary summary = heatMap.getDefectSeveritySummary();
      opened = summary.getTotal();
      closed = summary.getClosed();
    }
    return append(elapsedMillis(updatedAt), opened, closed, executed);
  }

  public OctaneDefectTrend append(String updatedAt, List<DefectRecord> defects, int executed) {
    OctaneDefectSeveritySummary summary = OctaneDefectSeveritySummary.fromDefects(defects);
    return append(elapsedMillis(updatedAt), summary.getTotal(), summary.getClosed(), executed);
  }

  public OctaneDefectTrend append(String updatedAt, int raised, int closed, int executed) {
    return append(elapsedMillis(updatedAt), raised, closed, executed);
  }

  public OctaneDefectTrend append(long elapsedMillis, int opened, int closed) {
    return append(elapsedMillis, opened, closed, latestPoint().getExecuted());
  }

  public OctaneDefectTrend append(long elapsedMillis, int opened, int closed, int executed) {
    long safeElapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
    Point point = new Point(safeElapsed, opened, closed, executed);
    List<Point> updatedPoints = new ArrayList<>(points);
    if (!updatedPoints.isEmpty()
        && updatedPoints.get(updatedPoints.size() - 1).getElapsedMillis() == safeElapsed) {
      updatedPoints.set(updatedPoints.size() - 1, point);
    } else {
      updatedPoints.add(point);
    }
    while (updatedPoints.size() > MAX_POINTS) {
      updatedPoints.remove(1);
    }
    return new OctaneDefectTrend(startedAt, durationMillis, updatedPoints);
  }

  public String getStartedAt() {
    return startedAt;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public List<Point> getPoints() {
    return points;
  }

  public int getOpenedTotal() {
    return latestPoint().getOpened();
  }

  public int getOpenTotal() {
    return latestPoint().getOpen();
  }

  public int getRaisedTotal() {
    return latestPoint().getOpened();
  }

  public int getClosedTotal() {
    return latestPoint().getClosed();
  }

  public int getMaximumCount() {
    int maximum = 0;
    for (Point point : points) {
      maximum = Math.max(maximum, Math.max(point.getOpened(), point.getClosed()));
    }
    return maximum;
  }

  public List<DensityBucket> getDensityBuckets() {
    return densityBuckets(durationMillis);
  }

  List<DensityBucket> densityBuckets(long totalDurationMillis) {
    long safeDuration = Math.max(1L, totalDurationMillis);
    long bucketMillis = densityBucketMillis(safeDuration);
    List<DensityBucket> buckets = new ArrayList<>();
    for (long start = 0L; start < safeDuration; start += bucketMillis) {
      long end = Math.min(safeDuration, start + bucketMillis);
      Point startPoint = pointAt(start);
      Point endPoint = pointAt(end);
      int newDefects = Math.max(0, endPoint.getOpened() - startPoint.getOpened());
      int executedTests = Math.max(0, endPoint.getExecuted() - startPoint.getExecuted());
      double density =
          executedTests == 0 ? newDefects : ((double) newDefects) / (double) executedTests;
      buckets.add(new DensityBucket(start, end, newDefects, executedTests, density));
    }
    return buckets;
  }

  private static long densityBucketMillis(long durationMillis) {
    long target = Math.max(1L, Math.round(durationMillis / 10.0));
    long[] friendly =
        new long[] {15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L};
    for (long candidate : friendly) {
      if (target <= candidate) {
        return candidate;
      }
    }
    return 900_000L;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("startedAt", startedAt);
    values.put("durationMillis", durationMillis);
    values.put("raisedTotal", getRaisedTotal());
    values.put("openedTotal", getOpenedTotal());
    values.put("closedTotal", getClosedTotal());
    values.put("openedColor", OPENED_COLOR);
    values.put("closedColor", CLOSED_COLOR);
    List<Map<String, Object>> pointValues = new ArrayList<>();
    for (Point point : points) {
      pointValues.add(point.toMap());
    }
    values.put("points", pointValues);
    List<Map<String, Object>> bucketValues = new ArrayList<>();
    for (DensityBucket bucket : getDensityBuckets()) {
      bucketValues.add(bucket.toMap());
    }
    values.put("densityBuckets", bucketValues);
    return values;
  }

  private Point latestPoint() {
    return points.isEmpty() ? new Point(0L, 0, 0, 0) : points.get(points.size() - 1);
  }

  private Point pointAt(long elapsedMillis) {
    Point selected = points.isEmpty() ? new Point(0L, 0, 0, 0) : points.get(0);
    for (Point point : points) {
      if (point.getElapsedMillis() > elapsedMillis) {
        return selected;
      }
      selected = point;
    }
    return selected;
  }

  private long elapsedMillis(String updatedAt) {
    try {
      return Duration.between(Instant.parse(startedAt), Instant.parse(updatedAt)).toMillis();
    } catch (RuntimeException e) {
      return latestPoint().getElapsedMillis();
    }
  }

  public static final class Point implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long elapsedMillis;
    private final int opened;
    private final int closed;
    private final int executed;

    private Point(long elapsedMillis, int opened, int closed, int executed) {
      this.elapsedMillis = Math.max(0L, elapsedMillis);
      this.opened = Math.max(0, opened);
      this.closed = Math.max(0, closed);
      this.executed = Math.max(0, executed);
    }

    public long getElapsedMillis() {
      return elapsedMillis;
    }

    public int getOpened() {
      return opened;
    }

    public int getClosed() {
      return closed;
    }

    public int getOpen() {
      return Math.max(0, opened - closed);
    }

    public int getExecuted() {
      return executed;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("elapsedMillis", elapsedMillis);
      values.put("opened", opened);
      values.put("closed", closed);
      values.put("executed", executed);
      return values;
    }
  }

  public static final class DensityBucket implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long startMillis;
    private final long endMillis;
    private final int newDefects;
    private final int executedTests;
    private final double density;

    private DensityBucket(
        long startMillis, long endMillis, int newDefects, int executedTests, double density) {
      this.startMillis = Math.max(0L, startMillis);
      this.endMillis = Math.max(this.startMillis, endMillis);
      this.newDefects = Math.max(0, newDefects);
      this.executedTests = Math.max(0, executedTests);
      this.density = Math.max(0.0, density);
    }

    public long getStartMillis() {
      return startMillis;
    }

    public long getEndMillis() {
      return endMillis;
    }

    public int getNewDefects() {
      return newDefects;
    }

    public int getExecutedTests() {
      return executedTests;
    }

    public double getDensity() {
      return density;
    }

    public boolean isZeroTestSpike() {
      return newDefects > 0 && executedTests == 0;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("startMillis", startMillis);
      values.put("endMillis", endMillis);
      values.put("newDefects", newDefects);
      values.put("executedTests", executedTests);
      values.put("density", density);
      values.put("zeroTestSpike", isZeroTestSpike());
      return values;
    }
  }
}
