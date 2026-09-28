package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.scheduler.CronTab;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

final class OctaneCronSchedule implements OctaneProgressEmailScheduler.Schedule {
  private static final Map<Integer, String> DAYS_OF_WEEK =
      Map.of(
          0, "Sunday",
          1, "Monday",
          2, "Tuesday",
          3, "Wednesday",
          4, "Thursday",
          5, "Friday",
          6, "Saturday",
          7, "Sunday");

  private final String expression;
  private final CronTab cronTab;
  private final String description;

  OctaneCronSchedule(String expression) {
    this.expression = normalize(expression);
    if (this.expression.isEmpty()) {
      throw new IllegalArgumentException("Cron expression must not be blank.");
    }
    String[] fields = this.expression.split("\\s+");
    if (fields.length != 5) {
      throw new IllegalArgumentException("Cron expression must contain exactly five fields.");
    }
    try {
      cronTab = new CronTab(this.expression);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid cron expression: " + this.expression, e);
    }
    description = describe(fields);
  }

  @Override
  public String expression() {
    return expression;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public Instant nextAfter(Instant after, Instant lastDelivery, Duration minimumInterval) {
    Instant lowerBound = after.plusMillis(1L);
    if (lastDelivery != null) {
      Instant throttled = lastDelivery.plus(minimumInterval);
      if (throttled.isAfter(lowerBound)) {
        lowerBound = throttled;
      }
    }
    Calendar next = cronTab.ceil(lowerBound.toEpochMilli());
    return next.toInstant();
  }

  private String normalize(String value) {
    return Util.trimToEmpty(value).replaceAll("\\s+", " ");
  }

  private String describe(String[] fields) {
    if (matches(fields, "\\*", "\\*", "\\*", "\\*", "\\*")) {
      return "Every minute.";
    }
    String description = describeKnownSchedule(fields);
    if (description != null) {
      return description;
    }
    return "According to cron fields: minute "
        + fields[0]
        + ", hour "
        + fields[1]
        + ", day-of-month "
        + fields[2]
        + ", month "
        + fields[3]
        + ", and day-of-week "
        + fields[4]
        + ".";
  }

  private String describeKnownSchedule(String[] fields) {
    if (matches(fields, "\\*/\\d+", "\\*", "\\*", "\\*", "\\*")) {
      return "Every " + Integer.parseInt(fields[0].substring(2)) + " minutes.";
    }
    if (matches(fields, "\\*", "\\*", "\\*", "\\*", "\\d-\\d")) {
      return everyMinuteOnDayRange(fields[4]);
    }
    if (matches(fields, "\\d+", "\\d+-\\d+/\\d+", "\\*", "\\*", "\\*")) {
      return minuteAcrossHourRange(fields[0], fields[1]);
    }
    if (matches(fields, "\\d+", "\\*", "\\*", "\\*", "\\d-\\d")) {
      return minuteOnDayRange(fields[0], fields[4]);
    }
    if (matches(fields, "\\d+", "\\d+", "\\*", "\\*", "\\*")) {
      return String.format(
          Locale.ENGLISH,
          "At %02d:%02d every day.",
          Integer.parseInt(fields[1]),
          Integer.parseInt(fields[0]));
    }
    if (matches(fields, "\\d+", "\\*", "\\*", "\\*", "\\*")) {
      return "At minute " + fields[0] + " past every hour.";
    }
    return null;
  }

  private boolean matches(String[] fields, String... patterns) {
    if (fields.length != patterns.length) {
      return false;
    }
    for (int index = 0; index < fields.length; index++) {
      if (!fields[index].matches(patterns[index])) {
        return false;
      }
    }
    return true;
  }

  private String everyMinuteOnDayRange(String dayRange) {
    String[] range = dayRange.split("-");
    return "Every minute on every day-of-week from "
        + dayName(range[0])
        + " through "
        + dayName(range[1])
        + ".";
  }

  private String minuteAcrossHourRange(String minute, String hourRange) {
    String[] rangeAndStep = hourRange.split("/");
    String[] range = rangeAndStep[0].split("-");
    return "At minute "
        + minute
        + " past every "
        + ordinal(Integer.parseInt(rangeAndStep[1]))
        + " hour from "
        + range[0]
        + " through "
        + range[1]
        + ".";
  }

  private String minuteOnDayRange(String minute, String dayRange) {
    String[] range = dayRange.split("-");
    return "At minute "
        + minute
        + " on every day-of-week from "
        + dayName(range[0])
        + " through "
        + dayName(range[1])
        + ".";
  }

  private String dayName(String value) {
    return DAYS_OF_WEEK.getOrDefault(Integer.parseInt(value), value);
  }

  private String ordinal(int value) {
    int modulo100 = value % 100;
    if (modulo100 >= 11 && modulo100 <= 13) {
      return value + "th";
    }
    return switch (value % 10) {
      case 1 -> value + "st";
      case 2 -> value + "nd";
      case 3 -> value + "rd";
      default -> value + "th";
    };
  }
}
