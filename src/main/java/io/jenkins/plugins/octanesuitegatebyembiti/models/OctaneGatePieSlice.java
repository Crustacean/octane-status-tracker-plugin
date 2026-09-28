package io.jenkins.plugins.octanesuitegatebyembiti.models;

import java.io.Serializable;
import java.util.Locale;

public class OctaneGatePieSlice implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final double CENTER = 50.0;
  private static final double RADIUS = 46.0;

  private final OctaneGateStatusCount status;
  private final String path;
  private final boolean fullCircle;

  OctaneGatePieSlice(OctaneGateStatusCount status, double startAngle, double endAngle) {
    this.status = status;
    this.fullCircle = status.getPercentage() >= 99.999999;
    this.path = fullCircle ? "" : buildPath(startAngle, endAngle);
  }

  public String getPath() {
    return path;
  }

  public boolean isFullCircle() {
    return fullCircle;
  }

  public String getColor() {
    return status.getColor();
  }

  public String getLabel() {
    return status.getLabel();
  }

  public int getCount() {
    return status.getCount();
  }

  public String getTitle() {
    return status.getTitle();
  }

  public String getPercentageLabel() {
    return status.getPercentageLabel();
  }

  private static String buildPath(double startAngle, double endAngle) {
    double[] start = point(startAngle, RADIUS);
    double[] end = point(endAngle, RADIUS);
    int largeArc = endAngle - startAngle > 180.0 ? 1 : 0;
    return String.format(
        Locale.ROOT,
        "M %.3f %.3f L %.3f %.3f A %.3f %.3f 0 %d 1 %.3f %.3f Z",
        CENTER,
        CENTER,
        start[0],
        start[1],
        RADIUS,
        RADIUS,
        largeArc,
        end[0],
        end[1]);
  }

  private static double[] point(double angle, double radius) {
    double radians = Math.toRadians(angle);
    return new double[] {CENTER + radius * Math.cos(radians), CENTER + radius * Math.sin(radians)};
  }
}
