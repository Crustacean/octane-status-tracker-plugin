package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class OctaneExecutionStatusDistribution implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final double CENTER_X = 160.0;
  private static final double CENTER_Y = 172.0;
  private static final double RADIUS = 116.0;
  private static final double START_ANGLE = 180.0;
  private static final double SWEEP_ANGLE = 180.0;
  private static final double GAP_DEGREES = 15.0;
  private static final List<OctaneGateStatusBucket> DISPLAY_ORDER =
      List.of(
          OctaneGateStatusBucket.RUNNING,
          OctaneGateStatusBucket.PASSED,
          OctaneGateStatusBucket.FAILED,
          OctaneGateStatusBucket.BLOCKED,
          OctaneGateStatusBucket.SKIPPED);

  private final int total;
  private final List<Segment> segments;

  private OctaneExecutionStatusDistribution(int total, List<Segment> segments) {
    this.total = Math.max(0, total);
    this.segments = List.copyOf(segments);
  }

  public static OctaneExecutionStatusDistribution fromStatusCounts(
      List<OctaneGateStatusCount> statusCounts) {
    int total = 0;
    List<OctaneGateStatusCount> visibleStatuses = new ArrayList<>();
    for (OctaneGateStatusCount status : statusCounts) {
      OctaneGateStatusCount nonNullStatus = Objects.requireNonNull(status);
      total += nonNullStatus.getCount();
      if (nonNullStatus.getCount() > 0) {
        visibleStatuses.add(nonNullStatus);
      }
    }
    if (total <= 0) {
      return new OctaneExecutionStatusDistribution(0, List.of());
    }

    visibleStatuses.sort(
        (left, right) -> {
          OctaneGateStatusCount nonNullLeft = Objects.requireNonNull(left);
          OctaneGateStatusCount nonNullRight = Objects.requireNonNull(right);
          int countComparison = Integer.compare(nonNullRight.getCount(), nonNullLeft.getCount());
          if (countComparison != 0) {
            return countComparison;
          }
          return Integer.compare(
              DISPLAY_ORDER.indexOf(nonNullLeft.getBucket()),
              DISPLAY_ORDER.indexOf(nonNullRight.getBucket()));
        });

    List<Segment> segments = new ArrayList<>();
    double drawableSweep =
        Math.max(0.0, SWEEP_ANGLE - (Math.max(0, visibleStatuses.size() - 1) * GAP_DEGREES));
    double startAngle = START_ANGLE;
    for (OctaneGateStatusCount status : visibleStatuses) {
      double sweep = drawableSweep * status.getCount() / total;
      double endAngle = startAngle + sweep;
      segments.add(new Segment(status, arcPath(startAngle, endAngle)));
      startAngle = endAngle + GAP_DEGREES;
    }
    return new OctaneExecutionStatusDistribution(total, segments);
  }

  public int getTotal() {
    return total;
  }

  public List<Segment> getSegments() {
    return segments;
  }

  public int getStatusCount() {
    return segments.size();
  }

  public boolean isEmpty() {
    return total <= 0 || segments.isEmpty();
  }

  private static String displayLabel(OctaneGateStatusBucket bucket) {
    return bucket.getLabel();
  }

  private static String arcPath(double startAngle, double endAngle) {
    double safeEndAngle = Math.max(startAngle, endAngle);
    double[] start = point(startAngle);
    double[] end = point(safeEndAngle);
    int largeArc = safeEndAngle - startAngle > 180.0 ? 1 : 0;
    return String.format(
        Locale.ROOT,
        "M %.3f %.3f A %.3f %.3f 0 %d 1 %.3f %.3f",
        start[0],
        start[1],
        RADIUS,
        RADIUS,
        largeArc,
        end[0],
        end[1]);
  }

  private static double[] point(double angle) {
    double radians = Math.toRadians(angle);
    return new double[] {
      CENTER_X + RADIUS * Math.cos(radians), CENTER_Y + RADIUS * Math.sin(radians)
    };
  }

  public static class Segment implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String label;
    private final String color;
    private final int count;
    private final double percentage;
    private final String path;

    private Segment(OctaneGateStatusCount status, String path) {
      this.label = displayLabel(status.getBucket());
      this.color = status.getColor();
      this.count = status.getCount();
      this.percentage = status.getPercentage();
      this.path = path;
    }

    public String getLabel() {
      return label;
    }

    public String getColor() {
      return color;
    }

    public int getCount() {
      return count;
    }

    public double getPercentage() {
      return percentage;
    }

    public String getPercentageLabel() {
      return Util.formatPercentage(percentage, 2);
    }

    public String getPath() {
      return path;
    }

    public String getTitle() {
      return label + ": " + count + " | " + getPercentageLabel();
    }
  }
}
