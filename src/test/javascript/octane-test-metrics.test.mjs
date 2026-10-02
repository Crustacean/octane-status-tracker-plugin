import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";

import {reportSource} from "./report-assets.mjs";
const labelSource = reportSource
    .split("/* OCTANE_TEST_METRIC_LABELS_START */")[1]
    .split("/* OCTANE_TEST_METRIC_LABELS_END */")[0];

function metricSegment({availableWidth, fullLabel, fullWidth, shortLabel, shortWidth}) {
  let text = "";
  const label = {
    title: "",
    getBoundingClientRect() {
      return {width: availableWidth};
    },
    get scrollWidth() {
      return text === fullLabel ? fullWidth : shortWidth;
    },
    get textContent() {
      return text;
    },
    set textContent(value) {
      text = value;
    }
  };
  return {
    getAttribute(name) {
      return name === "data-full-label" ? fullLabel : shortLabel;
    },
    getBoundingClientRect() {
      return {width: availableWidth};
    },
    label,
    querySelector() {
      return label;
    }
  };
}

function contextFor(segments) {
  const root = {
    querySelectorAll(selector) {
      if (selector === "[data-test-metric-segment]") {
        return segments;
      }
      return [];
    }
  };
  const context = {
    dashboard: root,
    requestFrame: callback => {
      callback();
      return 1;
    },
    window: {addEventListener() {}}
  };
  vm.runInNewContext(
      `${labelSource}
this.fitTestMetricSegmentLabels = fitTestMetricSegmentLabels;`,
      context);
  return {context, root};
}

test("keeps full defect labels when the proportional segment is wide enough", () => {
  const segment = metricSegment({
    availableWidth: 110,
    fullLabel: "Major (12)",
    fullWidth: 82,
    shortLabel: "M (12)",
    shortWidth: 44
  });
  const {context, root} = contextFor([segment]);

  context.fitTestMetricSegmentLabels(root);

  assert.equal(segment.label.textContent, "Major (12)");
  assert.equal(segment.label.title, "Major (12)");
});

test("uses the compact defect label when the rendered segment is narrow", () => {
  const segment = metricSegment({
    availableWidth: 42,
    fullLabel: "Unspecified (5)",
    fullWidth: 116,
    shortLabel: "U (5)",
    shortWidth: 34
  });
  const {context, root} = contextFor([segment]);

  context.fitTestMetricSegmentLabels(root);

  assert.equal(segment.label.textContent, "U (5)");
  assert.equal(segment.label.title, "Unspecified (5)");
});

test("falls back to automation emojis when a segment cannot fit its name", () => {
  const segment = metricSegment({
    availableWidth: 34,
    fullLabel: "🔥 Automated",
    fullWidth: 96,
    shortLabel: "🔥",
    shortWidth: 18
  });
  const {context, root} = contextFor([segment]);

  context.fitTestMetricSegmentLabels(root);

  assert.equal(segment.label.textContent, "🔥");
  assert.equal(segment.label.title, "🔥 Automated");
});

test("retains responsive and polling hooks for refreshed metric markup", () => {
  assert.match(reportSource, /new window\.ResizeObserver/);
  assert.match(reportSource, /testMetricSegmentResizeObserver\.disconnect\(\)/);
  assert.match(reportSource, /initializeTestMetricSegments\(panel\)/);
  assert.match(reportSource, /initializeTestMetricSegments\(dashboard\)/);
  assert.match(reportSource, /grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/);
  assert.match(reportSource, /grid-template-rows: repeat\(2, minmax\(0, 1fr\)\)/);
  assert.match(reportSource, /\.octane-test-metric-card \{[\s\S]*?overflow: hidden/);
  assert.match(reportSource, /\.octane-test-metric-gauge-svg \{[\s\S]*?aspect-ratio: 7 \/ 4/);
  assert.match(reportSource, /container-name: octane-test-metric-gauge/);
  assert.match(reportSource, /width: min\(92cqw, 175cqh, 28rem\)/);
  assert.match(
      reportSource,
      /@media \(max-height: 34rem\) and \(min-aspect-ratio: 2 \/ 1\)/);
  assert.match(reportSource, /<text class="octane-test-metric-gauge-value" x="42" y="43">/);
  assert.match(reportSource, /\.octane-test-metric-progress-wrap \{[\s\S]*?aspect-ratio: 34 \/ 1/);
  assert.match(reportSource, /\.octane-test-metric-defect-track \{[\s\S]*?aspect-ratio: 34 \/ 1/);
  assert.match(reportSource, /\.octane-test-metric-automation-track/);
  assert.match(reportSource, /--octane-system-good: #0f766e/);
  assert.match(reportSource, /--octane-system-bad: #4338ca/);
  assert.match(reportSource, /--octane-system-good: #198980/);
  assert.match(reportSource, /--octane-system-bad: #7268ED/);
  assert.match(reportSource, /\.octane-test-metric-automation-automated[\s\S]*?var\(--octane-system-good\)/);
  assert.match(reportSource, /\.octane-test-metric-automation-manual[\s\S]*?var\(--octane-system-bad\)/);
  assert.match(
      reportSource,
      /\.octane-test-metric-segment-label, \.octane-test-metric-defect-label/);
  assert.match(reportSource, /\.octane-test-metric-trend \{[\s\S]*?display: inline-flex/);
  assert.match(reportSource, /\.octane-test-metric-trend \{[\s\S]*?white-space: nowrap/);
  assert.match(reportSource, /\.octane-test-metric-defect-color:only-child/);
});
