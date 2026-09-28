import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const jellyPath =
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
    + "OctaneGateReportAction/index.jelly";
const jelly = readFileSync(jellyPath, "utf8");
const timerDisplaySource = jelly
    .split("/* OCTANE_TIMER_DISPLAY_START */")[1]
    .split("/* OCTANE_TIMER_DISPLAY_END */")[0];

function cssRule(selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = jelly.match(new RegExp(`(?:^|\\n)\\s*${escapedSelector}\\s*\\{([^}]*)\\}`));
  assert.ok(match, `Missing CSS rule for ${selector}`);
  return match[1];
}

const context = {
  clamp: (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value))
};
vm.runInNewContext(
    `${timerDisplaySource}
this.timeoutCountdownRemainingMillis = timeoutCountdownRemainingMillis;
this.timerSampleTimeMillis = timerSampleTimeMillis;
this.timeoutTimerShowsSpent = timeoutTimerShowsSpent;
this.timerDisplayParts = timerDisplayParts;`,
    context);

function displayParts(milliseconds) {
  return {...context.timerDisplayParts(milliseconds)};
}

test("formats the task examples with the largest unit and its immediate sub-unit", () => {
  assert.deepEqual(
      displayParts(119 * 60 * 1000),
      {
        accessibleLabel: "1 hour and 59 minutes",
        compactLabel: "h + m",
        fullLabel: "hours + minutes",
        value: "1:59"
      });
  assert.deepEqual(
      displayParts(1500 * 60 * 1000),
      {
        accessibleLabel: "1 day and 1 hour",
        compactLabel: "d + h",
        fullLabel: "days + hours",
        value: "1:01"
      });
});

test("switches units exactly at hour, day, and week boundaries", () => {
  assert.equal(displayParts(8 * 24 * 60 * 60 * 1000).value, "1:01");
  assert.equal(displayParts(8 * 24 * 60 * 60 * 1000).fullLabel, "weeks + days");
  assert.equal(displayParts(7 * 24 * 60 * 60 * 1000).value, "1:00");
  assert.equal(displayParts(24 * 60 * 60 * 1000).value, "1:00");
  assert.equal(displayParts(24 * 60 * 60 * 1000).fullLabel, "days + hours");
  assert.equal(displayParts(60 * 60 * 1000).value, "1:00");
  assert.equal(displayParts(60 * 60 * 1000).fullLabel, "hours + minutes");
  assert.equal(displayParts(3599 * 1000).value, "59:59");
  assert.equal(displayParts(3599 * 1000).fullLabel, "minutes + seconds");
});

test("rounds a partial final second up and locks depleted time at zero", () => {
  assert.equal(displayParts(1).value, "0:01");
  assert.equal(displayParts(0).value, "0:00");
  assert.equal(displayParts(-1000).value, "0:00");
  assert.equal(displayParts(0).fullLabel, "minutes + seconds");
  assert.equal(displayParts(0).compactLabel, "m + s");
});

test("counts down one continuous base plus extended timeout window", () => {
  const minute = 60 * 1000;
  const remaining = elapsedMinutes => context.timeoutCountdownRemainingMillis(
      0,
      120 * minute,
      30 * minute,
      elapsedMinutes * minute);

  assert.equal(remaining(0), 150 * minute);
  assert.equal(remaining(120), 30 * minute);
  assert.equal(remaining(130), 20 * minute);
  assert.equal(remaining(150), 0);
  assert.equal(remaining(151), 0);
  assert.equal(
      context.timeoutCountdownRemainingMillis(0, 120 * minute, 0, 120 * minute),
      0);
});

test("freezes the testing timer at the accepted manual-exit instant", () => {
  assert.equal(context.timerSampleTimeMillis(true, 1000, 4500, 9000), 4500);
  assert.equal(context.timerSampleTimeMillis(true, 1000, 0, 9000), 9000);
  assert.equal(context.timerSampleTimeMillis(false, 1000, 0, 9000), 1000);
});

test("switches the timeout card to elapsed time for both terminal paths", () => {
  assert.equal(
      context.timeoutTimerShowsSpent(
          {active: true, manualExitRequestedAtMillis: 0, mode: "timeout"}),
      false);
  assert.equal(
      context.timeoutTimerShowsSpent(
          {active: false, manualExitRequestedAtMillis: 0, mode: "timeout"}),
      true);
  assert.equal(
      context.timeoutTimerShowsSpent(
          {active: true, manualExitRequestedAtMillis: 4500, mode: "timeout"}),
      true);
  assert.equal(
      context.timeoutTimerShowsSpent(
          {active: false, manualExitRequestedAtMillis: 0, mode: "poll"}),
      false);
  assert.match(jelly, /data-timeout-subtitle="true"/);
  assert.match(jelly, /showSpent \? testingTimeSpentMillis\(state, now\) : trackRemaining/);
  assert.match(jelly, /showSpent \? "Session Time Spent" : "Session Time Remaining"/);
  assert.match(jelly, /showSpent \? "Testing time spent: " : "Testing time remaining: "/);
});

test("renders full and compact labels with component and mobile breakpoints", () => {
  const timerWrapRule = cssRule(".octane-timer-wrap");
  const timerDonutRule = cssRule(".octane-timer-donut");

  assert.match(jelly, /data-timer-unit-compact="true"/);
  assert.match(timerWrapRule, /container-name:\s*octane-timer-display/);
  assert.match(timerWrapRule, /flex:\s*1 1 auto/);
  assert.match(timerWrapRule, /height:\s*100%/);
  assert.match(timerWrapRule, /min-width:\s*0/);
  assert.match(timerWrapRule, /width:\s*100%/);
  assert.match(timerDonutRule, /aspect-ratio:\s*1 \/ 1/);
  assert.match(timerDonutRule, /height:\s*min\(100cqw,\s*100cqh,\s*220px\)/);
  assert.match(timerDonutRule, /max-height:\s*220px/);
  assert.match(timerDonutRule, /max-width:\s*220px/);
  assert.match(
      cssRule(".octane-zone-focused .octane-timer-donut"),
      /height:\s*min\(100cqw,\s*100cqh,\s*38vh,\s*38vw\)/);
  assert.match(
      cssRule(".octane-chart-card.octane-expanded .octane-timer-donut"),
      /height:\s*min\(100cqw,\s*100cqh,\s*76vh,\s*76vw\)/);
  assert.match(jelly, /@container octane-timer-display \(max-width: 18rem\)/);
  assert.match(jelly, /@media \(max-width: 480px\)/);
  assert.match(jelly, /\.octane-timer-unit-compact\s*{\s*display:\s*none;/);
});

test("renders the dynamic job and polling status state machine", () => {
  assert.match(jelly, /data-report-status="true"/);
  assert.match(jelly, /Status Check In :/);
  assert.match(jelly, /Updating \.\.\. /);
  assert.match(jelly, /LAST UPDATED: /);
  assert.match(jelly, /data-report-finalizing="\$\{snapshot\.finalizing\}"/);
  assert.match(jelly, /function canApplySnapshotPayload\(payload\)/);
  assert.match(jelly, /incomingUpdatedAt < renderedUpdatedAt/);
  assert.match(jelly, /liveRefresh\.finalizing \? 250/);
  assert.doesNotMatch(jelly, /function fetchRiskHeatMapSnapshot\(\)/);
  assert.match(jelly, /function formatClockDuration\(milliseconds\)/);
  assert.match(jelly, /function renderReportStatus\(now\)/);
  assert.match(jelly, /payload\.jobStateLabel/);
  assert.match(jelly, /payload\.updatedAtDateTimeText/);
  assert.doesNotMatch(jelly, /formatLastUpdatedStatus/);
});

test("retains the last populated heat map through final reconciliation", () => {
  assert.match(
      jelly,
      /data-risk-heat-map-populated="\$\{snapshot\.riskHeatMap\.populatedData\}"/);
  assert.match(jelly, /var lastViableRiskHeatMapHtml/);
  assert.match(jelly, /payload\.finalizing === true \|\| payload\.building === false/);
  assert.match(jelly, /terminalUpdate && payload\.riskHeatMapPopulated !== true/);
  assert.match(jelly, /heatMapPanel\.innerHTML = lastViableRiskHeatMapHtml/);
  assert.match(jelly, /fetchSnapshot\(true\)/);
});

test("uses three equal activity rings with thirty-percent tighter edge gaps", () => {
  const radii = [...jelly.matchAll(/data-activity-ring="[^"]+"[^>]*r="([\d.]+)"/g)]
      .map(match => Number(match[1]));
  assert.deepEqual(radii, [84, 64.5, 45]);
  assert.equal(radii[0] - radii[1] - 16, 3.5);
  assert.equal(radii[1] - radii[2] - 16, 3.5);
  assert.match(jelly, /\.octane-activity-ring-track,[\s\S]*?stroke-width: 16;/);
  assert.match(jelly, /\.octane-activity-ring-track\s*{\s*opacity: 0\.2;/);
  assert.match(jelly, /stroke-linecap: round/);
  assert.match(jelly, /stroke: #FA114F/);
  assert.match(jelly, /stroke: #A6FF00/);
  assert.match(jelly, /stroke: #00FFF6/);
});

test("matches the timer ring bounds and centers the activity rings", () => {
  const timerRule = cssRule(".octane-timer-donut");
  const activityRule = cssRule(".octane-activity-rings-svg");
  const activityContainerRule = cssRule(".octane-activity-rings");
  const activityLayoutRule = cssRule(".octane-activity-rings-layout");

  for (const property of [
    /height:\s*min\(100cqw,\s*100cqh,\s*220px\)/,
    /max-height:\s*220px/,
    /max-width:\s*220px/,
    /width:\s*min\(100cqw,\s*100cqh,\s*220px\)/
  ]) {
    assert.match(timerRule, property);
    assert.match(activityRule, property);
  }
  assert.match(activityContainerRule, /padding:\s*0/);
  assert.match(activityLayoutRule, /place-items:\s*center/);
  assert.match(activityLayoutRule, /position:\s*relative/);
});

test("renders a single-line activity subtitle with compact-label and ellipsis fallbacks", () => {
  const subtitleRule = cssRule(".octane-activity-subtitle");
  const inlineLegendRule = cssRule(".octane-activity-inline-legend");

  assert.match(subtitleRule, /width:\s*100%/);
  assert.match(subtitleRule, /max-width:\s*none/);
  assert.match(subtitleRule, /overflow:\s*hidden/);
  assert.match(subtitleRule, /text-overflow:\s*ellipsis/);
  assert.match(subtitleRule, /text-wrap:\s*nowrap/);
  assert.match(subtitleRule, /white-space:\s*nowrap/);
  assert.match(inlineLegendRule, /display:\s*block/);
  assert.match(inlineLegendRule, /overflow:\s*hidden/);
  assert.match(inlineLegendRule, /text-overflow:\s*ellipsis/);
  assert.match(inlineLegendRule, /white-space:\s*nowrap/);
});

test("depletes the testing session monitor counter-clockwise from twelve o'clock", () => {
  assert.match(
      jelly,
      /<circle class="octane-timer-progress" data-timer-progress="true"[\s\S]*?transform="rotate\(-90 120 120\)"/);
  assert.doesNotMatch(
      jelly,
      /M120 36 A84 84 0 1 0 120 204 A84 84 0 1 0 120 36/);
  assert.match(
      jelly,
      /state\.progressCircle\.style\.strokeDasharray\s*=\s*trimNumber\(remainingProgress\) \+ " 100"/);
});

test("renders two-decimal inline and conditional side activity legends", () => {
  assert.doesNotMatch(jelly, /Target Achievement/);
  assert.match(jelly, /octane-activity-subtitle/);
  assert.match(jelly, />Execution Rate<\/span>/);
  assert.match(jelly, />Pass Rate<\/span>/);
  assert.match(jelly, />Automation Usage<\/span>/);
  assert.match(jelly, />Execution<\/span>/);
  assert.match(jelly, />Pass<\/span>/);
  assert.match(jelly, />Automation<\/span>/);
  assert.match(jelly, /@container octane-activity-legend \(max-width: 34rem\)/);
  assert.match(jelly, /class="octane-activity-side-legend"/);
  assert.match(jelly, /table-layout: fixed/);
  assert.match(jelly, /font-variant-numeric: tabular-nums/);
  assert.match(jelly, /@container octane-activity-rings \(min-width: 36rem\)/);
  assert.match(jelly, /data-side-legend-visible="true"/);
  assert.match(jelly, /data-side-legend-visible="true"\][\s\S]*?display: table;/);
  assert.match(jelly, /inset-inline-end:\s*0/);
  assert.match(jelly, /position:\s*absolute/);
  assert.match(jelly, /transform:\s*translateY\(-50%\)/);
  assert.match(jelly, /data-activity-inline-legend="true"/);
  assert.match(jelly, /var activityFace = component\.closest\("\.octane-flip-face"\)/);
  assert.match(
      jelly,
      /activityFace\.querySelectorAll\('\[data-activity-rate=/);
  assert.match(
      jelly,
      /inlineLegend\.setAttribute\("aria-label", labels\.join\(", "\)\)/);
  assert.match(jelly, /querySelectorAll\('\[data-activity-rate=/);
  assert.match(jelly, /rate\.toFixed\(2\) \+ "%"/);
  assert.match(jelly, /function fitActivityRingLegend\(component\)/);
  assert.match(jelly, /new window\.ResizeObserver\(scheduleActivityRingLayout\)/);
  assert.match(jelly, /intersectsHorizontally/);
  assert.match(jelly, /intersectsVertically/);
  assert.match(jelly, /scheduleActivityRingLayout\(\)/);
});

test("keeps Tester Details headers single-line with responsive prefixes", () => {
  const tableRule = cssRule(".octane-tester-table");
  const titleRule = cssRule(".octane-tester-tracker-title");
  const headerRule = cssRule(
      ".octane-tester-table thead .octane-tester-rate-header");

  assert.match(tableRule, /box-sizing:\s*border-box/);
  assert.match(tableRule, /inline-size:\s*100%/);
  assert.match(tableRule, /table-layout:\s*auto/);
  assert.match(titleRule, /font-size:\s*clamp\(0\.7rem, 3\.2cqi, 1rem\)/);
  assert.match(titleRule, /text-wrap:\s*nowrap/);
  assert.match(titleRule, /white-space:\s*nowrap/);
  assert.match(headerRule, /font-size:\s*clamp\(0\.7rem, 3\.2cqi, 1rem\)/);
  assert.match(headerRule, /overflow-wrap:\s*normal/);
  assert.match(headerRule, /padding-inline-end:\s*clamp\(0\.75rem, 2vw, 1\.25rem\)/);
  assert.match(headerRule, /text-align:\s*right/);
  assert.match(headerRule, /text-wrap:\s*nowrap/);
  assert.match(headerRule, /white-space:\s*nowrap/);
  assert.match(jelly, /@container tester-tracker \(max-width: 24rem\)/);
  assert.match(jelly, /function updateTesterTrackerTitle\(title, condition\)/);
  assert.match(jelly, /var fullText = "Testers with LESS THAN " \+ condition/);
  assert.match(jelly, /conditionElement\.textContent = condition/);
  assert.doesNotMatch(jelly, /\.octane-tester-email-column\s*,/);
  assert.equal(
      (jelly.match(/class="octane-tester-rate octane-tester-rate-header"/g) || []).length,
      2);
  assert.equal(
      (jelly.match(/class="octane-tester-responsive-prefix"/g) || []).length,
      4);
  assert.equal(
      (jelly.match(/class="octane-tester-comparison-full"/g) || []).length,
      2);
  assert.equal(
      (jelly.match(/class="octane-tester-comparison-compact"/g) || []).length,
      2);
});
