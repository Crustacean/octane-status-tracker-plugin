import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const jellyPath =
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
    + "OctaneGateReportAction/index.jelly";
const jelly = readFileSync(jellyPath, "utf8");
const layoutMathSource = jelly
    .split("/* OCTANE_EXECUTION_BREAKDOWN_MATH_START */")[1]
    .split("/* OCTANE_EXECUTION_BREAKDOWN_MATH_END */")[0];
const context = {};
vm.runInNewContext(
    `${layoutMathSource}
this.executionBreakdownDimensions = executionBreakdownDimensions;`,
    context);

test("allocates the remaining height to the half-pie without clipping legend rows", () => {
  const normal = context.executionBreakdownDimensions(300, 600, 100, 12);
  const compact = context.executionBreakdownDimensions(180, 600, 100, 12);

  assert.deepEqual({...normal}, {chartHeight: 188, contentWidth: 376});
  assert.deepEqual({...compact}, {chartHeight: 68, contentWidth: 136});
  assert.equal(normal.chartHeight + 100 + 12, 300);
  assert.equal(compact.chartHeight + 100 + 12, 180);
});

test("keeps the two-to-one chart inside narrow and expanded containers", () => {
  const narrow = context.executionBreakdownDimensions(300, 240, 80, 8);
  const expanded = context.executionBreakdownDimensions(900, 2000, 140, 16);

  assert.equal(narrow.contentWidth, 220.8);
  assert.equal(expanded.chartHeight, 744);
  assert.equal(expanded.contentWidth, 1280);
  assert.ok(narrow.contentWidth <= 240 * 0.92);
  assert.ok(expanded.contentWidth <= expanded.chartHeight * 2);
});
