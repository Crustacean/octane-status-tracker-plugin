import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const jellyPath =
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
    + "OctaneGateReportAction/index.jelly";
const jelly = readFileSync(jellyPath, "utf8");
const densityMathSource = jelly
    .split("/* OCTANE_DEFECT_DENSITY_MATH_START */")[1]
    .split("/* OCTANE_DEFECT_DENSITY_MATH_END */")[0];
const volumeMathSource = jelly
    .split("/* OCTANE_DEFECT_VOLUME_MATH_START */")[1]
    .split("/* OCTANE_DEFECT_VOLUME_MATH_END */")[0];
const context = {
  DEFECT_DENSITY_BOUNDS: {bottom: 360, left: 80, right: 920, top: 0},
  clamp: (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value)),
  defectTrendState: {durationMillis: 60000, startedAt: Date.parse("2026-07-17T08:00:00Z")},
  isFinite,
  safeTrendNumber: (value, fallback) => {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
  },
  trimNumber: value => value.toFixed(3).replace(/\.?0+$/, "")
};
vm.runInNewContext(
    `${densityMathSource}
${volumeMathSource}
this.niceDefectDensityScale = niceDefectDensityScale;
this.activeOpenDefectCount = activeOpenDefectCount;
this.niceDefectTrendScale = niceDefectTrendScale;
this.defectTrendYAxisValues = defectTrendYAxisValues;
this.defectDensityYAxisValues = defectDensityYAxisValues;
this.densityXAxisIntervalCount = densityXAxisIntervalCount;
this.formatDensityClockOffset = formatDensityClockOffset;
this.defectDensityXAxisValues = defectDensityXAxisValues;
this.buildDefectDensityBuckets = buildDefectDensityBuckets;
this.densityXFor = densityXFor;
this.densityYFor = densityYFor;
this.defectDensityLinePath = defectDensityLinePath;
this.defectDensityAreaPath = defectDensityAreaPath;`,
    context);

test("derives header open defects without changing cumulative graph totals", () => {
  assert.equal(context.activeOpenDefectCount(64, 4), 60);
  assert.equal(context.activeOpenDefectCount(3, 1), 2);
  assert.equal(context.activeOpenDefectCount(1, 3), 0);
  assert.match(jelly, /defectTrendPath\(points, "opened", scale\)/);
  assert.match(jelly, /defectTrendPath\(points, "closed", scale\)/);
  assert.match(jelly, /total\.textContent = String\(latest\.opened\)/);
});

test("scales defect volume exactly one whole unit above its live maximum", () => {
  assert.deepEqual(
      {...context.niceDefectTrendScale(0)},
      {intervals: 1, maximum: 1, step: 1});
  assert.deepEqual(
      {...context.niceDefectTrendScale(4)},
      {intervals: 5, maximum: 5, step: 1});
  assert.equal(context.niceDefectTrendScale(5).maximum, 6);
  assert.equal(context.niceDefectTrendScale(80).maximum, 81);
  assert.equal(context.niceDefectTrendScale(3000).maximum, 3001);
  assert.equal(
      context.defectTrendYAxisValues(context.niceDefectTrendScale(80))[0],
      81);
});

test("uses one whole unit of headroom instead of a fixed five-unit minimum", () => {
  assert.deepEqual(
      {...context.niceDefectDensityScale(0)},
      {intervals: 1, maximum: 1, step: 1});
  assert.deepEqual(
      {...context.niceDefectDensityScale(0.8)},
      {intervals: 2, maximum: 2, step: 1});
  assert.equal(context.niceDefectDensityScale(80).maximum, 81);
  assert.equal(context.niceDefectDensityScale(3000).maximum, 3001);
  assert.deepEqual(
      Array.from(
          context.defectDensityYAxisValues(context.niceDefectDensityScale(0.8))),
      [2, 1, 0]);
  assert.equal(
      context.defectDensityYAxisValues(context.niceDefectDensityScale(3000))[0],
      3001);
});

test("calculates changing density from no defects through three thousand defects", () => {
  const none = context.buildDefectDensityBuckets(
      [
        {closed: 0, elapsedMillis: 0, executed: 0, opened: 0},
        {closed: 0, elapsedMillis: 15000, executed: 25, opened: 0}
      ],
      60000,
      15000);
  const fractional = context.buildDefectDensityBuckets(
      [
        {closed: 0, elapsedMillis: 0, executed: 0, opened: 0},
        {closed: 0, elapsedMillis: 15000, executed: 100, opened: 80},
        {closed: 25, elapsedMillis: 30000, executed: 200, opened: 180}
      ],
      60000,
      30000);
  const extreme = context.buildDefectDensityBuckets(
      [
        {closed: 0, elapsedMillis: 0, executed: 0, opened: 0},
        {closed: 0, elapsedMillis: 15000, executed: 0, opened: 3000}
      ],
      60000,
      15000);

  assert.equal(none[0].density, 0);
  assert.deepEqual(Array.from(fractional, bucket => bucket.density), [0.8, 1]);
  assert.deepEqual(Array.from(fractional, bucket => bucket.newDefects), [80, 100]);
  assert.equal(extreme[0].density, 3000);
  assert.equal(extreme[0].zeroTestSpike, true);
});

test("shades the full elapsed bucket and keeps coordinates inside the plot", () => {
  const buckets = [{density: 0.8, endMillis: 15000, startMillis: 0}];
  const scale = context.niceDefectDensityScale(0.8);
  const line = context.defectDensityLinePath(buckets, scale);
  const area = context.defectDensityAreaPath(buckets, scale);

  assert.equal(context.densityYFor(0.8, scale), 216);
  assert.equal(context.densityXFor(-100), 80);
  assert.equal(context.densityXFor(90000), 920);
  assert.match(line, /^M 80\.00 216\.00 H 290\.00$/);
  assert.match(area, /^M 80\.00 360\.00 L 80\.00 216\.00 H 290\.00 L 290\.00 360\.00 Z$/);
});

test("adapts x-axis label density and clock precision to available space and duration", () => {
  assert.equal(context.densityXAxisIntervalCount(320), 2);
  assert.equal(context.densityXAxisIntervalCount(800), 4);
  assert.equal(context.densityXAxisIntervalCount(1400), 5);

  const timestamp = Date.parse("2026-07-17T08:00:30Z");
  const shortLabel = context.formatDensityClockOffset(timestamp, 120000);
  const longLabel = context.formatDensityClockOffset(timestamp, 7200000);
  assert.equal((shortLabel.match(/:/g) || []).length, 2);
  assert.equal((longLabel.match(/:/g) || []).length, 1);

  const narrowLabels = Array.from(
      context.defectDensityXAxisValues(timestamp, 120000, 320));
  const wideLabels = Array.from(
      context.defectDensityXAxisValues(timestamp, 7200000, 1400));
  assert.equal(narrowLabels.length, 3);
  assert.equal(wideLabels.length, 6);
  assert.equal((narrowLabels[0].match(/:/g) || []).length, 2);
  assert.equal((wideLabels[wideLabels.length - 1].match(/:/g) || []).length, 1);
});
