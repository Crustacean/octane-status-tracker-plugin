package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;

public class OctaneGateStatusCount implements Serializable {
  private static final long serialVersionUID = 1L;

  private final OctaneGateStatusBucket bucket;
  private final int count;
  private final double percentage;

  OctaneGateStatusCount(OctaneGateStatusBucket bucket, int count, int total) {
    this.bucket = bucket;
    this.count = count;
    this.percentage = Util.percentage(count, total);
  }

  public OctaneGateStatusBucket getBucket() {
    return bucket;
  }

  public String getLabel() {
    return bucket.getLabel();
  }

  public String getColor() {
    return bucket.getColor();
  }

  public String getDataKey() {
    return bucket.getDataKey();
  }

  public String getTooltipColor() {
    return bucket.getTooltipColor();
  }

  public int getCount() {
    return count;
  }

  public double getPercentage() {
    return percentage;
  }

  public String getFormattedPercentage() {
    return Util.formatDecimal(percentage, 2);
  }

  public String getPercentageLabel() {
    return Util.formatPercentage(percentage, 2);
  }

  public String getTitle() {
    return getLabel() + ": " + count + " (" + getFormattedPercentage() + "%)";
  }

  public String getWidthStyle() {
    return "width: " + getFormattedPercentage() + "%; background: " + getColor() + ";";
  }

  public String getHeightStyle() {
    return "height: " + getFormattedPercentage() + "%; background: " + getColor() + ";";
  }
}
