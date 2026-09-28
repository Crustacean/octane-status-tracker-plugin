import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const jellyPath =
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
    + "OctaneGateReportAction/index.jelly";
const jelly = readFileSync(jellyPath, "utf8");
const fluidMathSource = jelly
    .split("/* OCTANE_FLUID_BAR_CHART_START */")[1]
    .split("/* OCTANE_FLUID_BAR_CHART_END */")[0];
const context = {isFinite};
vm.runInNewContext(
    `${fluidMathSource}
this.maxVisibleBarsForWidth = maxVisibleBarsForWidth;
this.fluidBarLayoutForWidth = fluidBarLayoutForWidth;`,
    context);

test("uses the shared bar capacity formula", () => {
  assert.equal(context.maxVisibleBarsForWidth(600), 57);
  assert.equal(context.maxVisibleBarsForWidth(800), 77);
  assert.equal(context.maxVisibleBarsForWidth(1200), 117);
});

test("always leaves room for at least one bar", () => {
  assert.equal(context.maxVisibleBarsForWidth(0), 1);
  assert.equal(context.maxVisibleBarsForWidth(24), 1);
  assert.equal(context.maxVisibleBarsForWidth(Number.NaN), 1);
});

test("scales bar width and gap within the configured bounds", () => {
  const dense = context.fluidBarLayoutForWidth(600, 57, true);
  const balanced = context.fluidBarLayoutForWidth(600, 10, false);
  const spacious = context.fluidBarLayoutForWidth(600, 5, false);
  const capped = context.fluidBarLayoutForWidth(660, 5, false);

  assert.equal(dense.barWidth, 8.053);
  assert.equal(dense.gap, 2.053);
  assert.equal(balanced.barWidth, 34.421);
  assert.equal(balanced.gap, 28.421);
  assert.equal(spacious.barWidth, 88);
  assert.equal(spacious.gap, 40);
  assert.equal(capped.barWidth, 100);
  assert.equal(capped.gap, 40);
});

test("renders a concise overflow count at the x-axis trail", () => {
  assert.match(jelly, /count\.textContent = "\+" \+ hiddenCount/);
  assert.doesNotMatch(jelly, /hiddenCount \+ " more\.\.\."/);
  assert.match(
      jelly,
      /\.octane-vertical-bars \{[\s\S]*?gap: var\(--octane-bar-gap,[\s\S]*?justify-content: center;/);
  assert.match(
      jelly,
      /\.octane-suite-column \{[\s\S]*?flex: 1 1 auto;[\s\S]*?max-width: 100px;[\s\S]*?min-width: 8px !important;/);
  assert.doesNotMatch(jelly, /margin-right: 2px !important;/);
});

test("retains the largest right-hand bars when the viewport truncates", () => {
  assert.match(
      jelly,
      /allSuiteRuns\.slice\([\s\S]*?Math\.max\(0, allSuiteRuns\.length - maxVisibleBars\)\)/);
  assert.doesNotMatch(jelly, /allSuiteRuns\.slice\(0, maxVisibleBars\)/);
});

test("uses the same fluid bar constraints in individual focused mode", () => {
  assert.doesNotMatch(
      jelly,
      /\.octane-chart-card\.octane-expanded \.octane-suite-column \{/);
  assert.doesNotMatch(jelly, /\.octane-zone-focused \.octane-suite-column \{/);
  assert.match(
      jelly,
      /function expandCard\(card\) \{[\s\S]*?card\.classList\.add\("octane-expanded"\);[\s\S]*?scheduleFluidBarCharts\(\);/);
  assert.match(
      jelly,
      /function removeExpandedState\(card\) \{[\s\S]*?card\.classList\.remove\("octane-expanded"\);[\s\S]*?scheduleFluidBarCharts\(\);/);
});

test("locks the overflow indicator to exactly 24 pixels", () => {
  const indicatorRule = jelly
      .split(".octane-bar-overflow-indicator {")[1]
      .split("}")[0];

  assert.match(indicatorRule, /flex: 0 0 24px;/);
  assert.doesNotMatch(indicatorRule, /margin-inline-start: auto;/);
  assert.match(indicatorRule, /max-width: 24px;/);
  assert.match(indicatorRule, /min-width: 24px;/);
  assert.match(indicatorRule, /width: 24px;/);
  assert.doesNotMatch(indicatorRule, /flex:\s*1/);
});
