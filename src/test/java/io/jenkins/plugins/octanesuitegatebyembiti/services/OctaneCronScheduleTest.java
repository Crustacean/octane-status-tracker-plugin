package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.Test;

public class OctaneCronScheduleTest {
  private static final ZoneId CONTROLLER_ZONE = ZoneId.systemDefault();

  @Test
  public void calculatesStandardDailyCronOccurrence() {
    OctaneCronSchedule schedule = new OctaneCronSchedule("0 8 * * *");
    Instant after = at(2026, 7, 17, 7, 59);

    assertEquals(at(2026, 7, 17, 8, 0), schedule.nextAfter(after, null, Duration.ZERO));
    assertEquals("At 08:00 every day.", schedule.description());
  }

  @Test
  public void describesAndCalculatesRangeBasedWeekdayCron() {
    OctaneCronSchedule schedule = new OctaneCronSchedule("0 * * * 1-5");
    Instant after = at(2026, 7, 17, 9, 1);

    assertEquals(at(2026, 7, 17, 10, 0), schedule.nextAfter(after, null, Duration.ZERO));
    assertEquals(
        "At minute 0 on every day-of-week from Monday through Friday.", schedule.description());
  }

  @Test
  public void describesAndCalculatesStepBasedHourCron() {
    OctaneCronSchedule schedule = new OctaneCronSchedule("23 0-20/2 * * *");
    Instant after = at(2026, 7, 17, 9, 30);

    assertEquals(at(2026, 7, 17, 10, 23), schedule.nextAfter(after, null, Duration.ZERO));
    assertEquals("At minute 23 past every 2nd hour from 0 through 20.", schedule.description());
  }

  @Test
  public void calculatesStepBasedMinuteCron() {
    OctaneCronSchedule schedule = new OctaneCronSchedule("*/15 * * * *");

    assertEquals(
        at(2026, 7, 17, 10, 15), schedule.nextAfter(at(2026, 7, 17, 10, 1), null, Duration.ZERO));
    assertEquals("Every 15 minutes.", schedule.description());
  }

  @Test
  public void describesAndCalculatesEveryMinuteOnWeekdays() {
    OctaneCronSchedule schedule = new OctaneCronSchedule("* * * * 1-5");

    assertEquals(
        at(2026, 7, 17, 10, 2), schedule.nextAfter(at(2026, 7, 17, 10, 1), null, Duration.ZERO));
    assertEquals(
        "Every minute on every day-of-week from Monday through Friday.", schedule.description());
  }

  @Test
  public void throttlesAggressiveCronToOneDeliveryPerMinute() {
    OctaneCronSchedule schedule = new OctaneCronSchedule("* * * * *");
    Instant lastDelivery = at(2026, 7, 17, 10, 1);

    assertEquals(
        at(2026, 7, 17, 10, 2),
        schedule.nextAfter(
            at(2026, 7, 17, 10, 1).plusSeconds(30L), lastDelivery, Duration.ofMinutes(1L)));
    assertEquals("Every minute.", schedule.description());
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsBlankCronExpression() {
    new OctaneCronSchedule("  ");
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsCronExpressionWithMoreThanFiveFields() {
    new OctaneCronSchedule("* * * * * *");
  }

  private Instant at(int year, int month, int day, int hour, int minute) {
    return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, CONTROLLER_ZONE).toInstant();
  }
}
