package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class OctaneAutomationUsage implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final String MANUAL_EMOJI = "🐢";
  private static final String AUTOMATED_EMOJI = "🔥";
  private static final Pattern AUTOMATED_RUNNER =
      Pattern.compile("jenkins", Pattern.CASE_INSENSITIVE);

  private final int automatedCount;
  private final int manualCount;

  private OctaneAutomationUsage(int automatedCount, int manualCount) {
    this.automatedCount = Math.max(0, automatedCount);
    this.manualCount = Math.max(0, manualCount);
  }

  public static OctaneAutomationUsage empty() {
    return new OctaneAutomationUsage(0, 0);
  }

  public static OctaneAutomationUsage fromRuns(List<RunRecord> runs) {
    Map<String, RunRecord> uniqueRuns = new LinkedHashMap<>();
    if (runs != null) {
      for (RunRecord run : runs) {
        if (run == null) {
          continue;
        }
        uniqueRuns.putIfAbsent(identity(run), run);
      }
    }
    int automated = 0;
    for (RunRecord run : uniqueRuns.values()) {
      if (isAutomated(run)) {
        automated++;
      }
    }
    return new OctaneAutomationUsage(automated, uniqueRuns.size() - automated);
  }

  public int getAutomatedCount() {
    return automatedCount;
  }

  public int getManualCount() {
    return manualCount;
  }

  public int getTotal() {
    return automatedCount + manualCount;
  }

  public int getPercentage() {
    return getTotal() == 0 ? 0 : (int) Math.round(automatedCount * 100.0 / getTotal());
  }

  public String getPercentageText() {
    return getPercentage() + "%";
  }

  public String getEmoji() {
    return emojiForPercentage(getPercentage());
  }

  public static String emojiForPercentage(int percentage) {
    return percentage > 0 ? AUTOMATED_EMOJI : MANUAL_EMOJI;
  }

  private static boolean isAutomated(RunRecord run) {
    return AUTOMATED_RUNNER.matcher(run.getExecutionActorName()).find();
  }

  private static String identity(RunRecord run) {
    if (!run.getId().isBlank()) {
      return run.getId();
    }
    return String.join(
        "\u0000", run.getName(), run.getTestId(), run.getStatus(), run.getExecutionActorName());
  }
}
