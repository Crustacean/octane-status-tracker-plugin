import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = readFileSync(
    "src/main/webapp/js/octane-test-management.js",
    "utf8");
const jelly = readFileSync(
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
        + "OctaneGateReportAction/index.jelly",
    "utf8");
const context = {window: {}};
vm.runInNewContext(source, context);
const testManagement = context.window.OctaneTestManagement;
const failureAxisMaximum = testManagement.failureAxisMaximum;
const failureAxisValueWidth = testManagement.failureAxisValueWidth;
const failureCategoryOptions = testManagement.failureCategoryOptions;
const failureDetailMatches = testManagement.failureDetailMatches;
const filterFailureDetailEntries = testManagement.filterFailureDetailEntries;
const integerAxisTicks = testManagement.integerAxisTicks;
const sortFailureDefects = testManagement.sortFailureDefects;
const timelineAxisScale = testManagement.timelineAxisScale;

test("renders ten discrete execution intervals in bottom-up status order", () => {
  assert.match(
      source,
      /STATE_KEYS = \["passed", "failed", "blocked", "skipped"\]/);
  assert.match(source, /payload && payload\.executionIntervals/);
  assert.match(source, /Math\.max\(0, nonNegative\(point\[key\]\) - previous\[key\]\)/);
  assert.match(source, /intervals\.forEach\(function \(interval, partIndex\)/);
  assert.match(jelly, /flex-direction: column-reverse/);
  assert.doesNotMatch(
      source,
      /STATE_KEYS = \[[^\]]*(?:inProgress|planned)[^\]]*\]/);
});

test("renders dynamic failure clusters and keeps a valid category selected", () => {
  assert.match(source, /payload\.failureCategories/);
  assert.match(
      source,
      /renderCategorySwitcher\(zone, switcher, categories, payload\.totalDefects\)/);
  assert.match(source, /failureCategoryOptions\(categories, totalDefects\)/);
  assert.match(
      source,
      /Number\.isFinite\(reportedTotal\) && reportedTotal >= 0/);
  assert.match(
      source,
      /total \+ nonNegative\(category\.open\) \+ nonNegative\(category\.closed\)/);
  assert.match(source, /category\.label/);
  assert.match(source, /group\.title = category\.label \|\| "Category"/);
  assert.match(source, /data-selected-category/);
  assert.match(source, /selected !== "all" && !available/);
  assert.match(source, /data-management-category-filter/);
  assert.match(source, /setSelectedCategory\(zone, category\)/);
  assert.match(source, /__octaneTestManagementOnCategorySelect/);
});

test("renders a counted All pill and category pills without count suffixes", () => {
  const categories = [
    {key: "api", label: "API", open: 3, closed: 1, defects: [{}, {}, {}, {}]},
    {key: "ui", label: "UI", open: 2, closed: 2, defects: [{}, {}, {}, {}]},
    {key: "db", label: "DB", open: 2, closed: 0, defects: [{}, {}]}
  ];

  assert.deepEqual(
      JSON.parse(JSON.stringify(failureCategoryOptions(categories, 10))),
      [
        {key: "all", label: "All", count: 10},
        {key: "api", label: "API", count: 4},
        {key: "ui", label: "UI", count: 4},
        {key: "db", label: "DB", count: 2}
      ]);
  assert.doesNotMatch(jelly, /data-management-defect-status-filter/);
  assert.doesNotMatch(jelly, /data-management-failure-status-switcher/);
  assert.match(source, /key === "all"[\s\S]*?"All " \+ category\.count/);
  assert.match(source, /: category\.label \|\| "Other"/);
  assert.match(
      jelly,
      /\.octane-management-defect-row\[hidden\],[\s\S]*?\.octane-management-defect-empty\[hidden\]\s*\{\s*display:\s*none;/);
});

test("uses the same canonical key for mapped defects, pills, and chart bars", () => {
  assert.deepEqual(
      JSON.parse(JSON.stringify(failureCategoryOptions([
        {key: 7, label: "Numeric", defects: [{}]},
        {label: "Other", defects: [{}, {}]}
      ], 3))),
      [
        {key: "all", label: "All", count: 3},
        {key: "7", label: "Numeric", count: 1},
        {key: "other-failures", label: "Other", count: 2}
      ]);
  assert.equal(failureDetailMatches({category: 7}, "7"), true);
  assert.equal(failureDetailMatches({category: ""}, "other-failures"), true);
  assert.match(source, /group\.setAttribute\("data-management-category-group", categoryKey\)/);
  assert.match(source, /bar\.setAttribute\("data-management-category", categoryKey\)/);
  assert.match(
      source,
      /entries\.push\(\{category: failureCategoryKey\(category\), defect: defect\}\)/);
});

test("uses a dynamic maximum one above the largest failure category total", () => {
  assert.equal(failureAxisMaximum([]), 1);
  assert.equal(failureAxisMaximum([{open: 1, closed: 0}]), 2);
  assert.equal(
      failureAxisMaximum([
        {open: 2, closed: 3},
        {open: 1, closed: 1}
      ]),
      6);
  assert.equal(failureAxisMaximum([{open: 3000, closed: 0}]), 3001);

  const expandedMaximum = failureAxisMaximum([{open: 7, closed: 3}]);
  const reducedMaximum = failureAxisMaximum([{open: 1, closed: 1}]);
  assert.equal(expandedMaximum, 11);
  assert.equal(reducedMaximum, 3);
});

test("generates distinct integer failure ticks from the ceiling to zero", () => {
  assert.deepEqual(Array.from(integerAxisTicks(2)), [2, 1, 0]);
  assert.deepEqual(Array.from(integerAxisTicks(6)), [6, 5, 4, 3, 2, 1, 0]);

  const largeTicks = Array.from(integerAxisTicks(3001));
  assert.equal(largeTicks[0], 3001);
  assert.equal(largeTicks.at(-1), 0);
  assert.equal(new Set(largeTicks).size, largeTicks.length);
  assert.ok(largeTicks.length <= 10);
  assert.ok(largeTicks.every(Number.isInteger));
  assert.ok(largeTicks.every((tick, index) => index === 0 || tick < largeTicks[index - 1]));
});

test("expands the Failure Analysis y-axis gutter for three-plus digit values", () => {
  assert.equal(failureAxisValueWidth([9, 0]), 2);
  assert.equal(failureAxisValueWidth([125, 100, 0]), 4);
  assert.equal(failureAxisValueWidth([3001, 0]), 5);
  assert.match(
      source,
      /--octane-management-failure-axis-value-width[\s\S]*?failureAxisValueWidth\(ticks\) \+ "ch"/);
  assert.match(
      jelly,
      /\.octane-management-failure-axis-layout\s*\{[^}]*grid-template-columns:\s*1\.35rem var\(--octane-management-failure-axis-value-width\) minmax\(0, 1fr\)/s);
});

test("binds failure labels and grid lines to the same axis positions", () => {
  assert.match(source, /function failureAxisPosition\(value, maximum\)/);
  assert.match(source, /setFailureYAxisLabels\(yLabels, ticks, maximum\)/);
  assert.match(source, /renderFailureGridLines\(chart, ticks, maximum\)/);
  assert.match(source, /data-management-axis-value/);
  assert.match(source, /data-management-grid-value/);
  assert.match(
      source,
      /--octane-management-axis-position[\s\S]*?failureAxisPosition\(tick, maximum\) \+ "%"/);
});

test("keeps the failure ceiling line and label one character below the header", () => {
  assert.match(source, /octane-management-failure-axis-track/);
  assert.match(
      jelly,
      /\.octane-management-failure-axis-track\s*\{[^}]*inset: 1ch 0 0;[^}]*position: absolute;/s);
  assert.match(
      jelly,
      /\.octane-management-failure-grid-lines\s*\{[^}]*top: 1ch;/s);
});

test("uses whole-number management ticks above a solid zero baseline", () => {
  assert.deepEqual(
      JSON.parse(JSON.stringify(timelineAxisScale(1))),
      {maximum: 1, step: 1, ticks: [1, 0]});
  assert.deepEqual(
      JSON.parse(JSON.stringify(timelineAxisScale(5))),
      {maximum: 8, step: 2, ticks: [8, 6, 4, 2, 0]});
  assert.match(source, /if \(tick > 0\)/);
  assert.match(source, /octane-management-timeline-axis-line/);
  assert.match(source, /octane-management-timeline-axis-dotted/);
  assert.match(
      source,
      /TIMELINE_BOUNDS\.left,[\s\S]*?TIMELINE_BOUNDS\.right,[\s\S]*?TIMELINE_BOUNDS\.bottom/);
});

test("adds dotted real-time lead and tail tracks to schedule and sprint charts", () => {
  assert.match(
      source,
      /left: 80,[\s\S]*?right: 920,[\s\S]*?width: 1000/);
  assert.match(source, /renderTimelineSvgAxes\(svg, scale\)/);
  assert.match(source, /renderTimelineHtmlGrid\(plot, scale\)/);
  assert.match(
      jelly,
      /\.octane-management-timeline-axis-dotted\s*\{[^}]*stroke-dasharray: 3 7;/s);
  assert.match(
      jelly,
      /\.octane-management-state-bars::before\s*\{[^}]*background-image:[^}]*inset-inline: 0;/s);
  assert.match(
      jelly,
      /\.octane-management-state-bars::after\s*\{[^}]*background: var\(--input-border\);[^}]*inset-inline: 8%;/s);
  assert.match(
      jelly,
      /\.octane-management-x-labels\s*\{[^}]*padding: 0\.25rem 8% 0;/s);
});

test("reveals the clicked failure bar and matching tab after individual focus opens", () => {
  assert.match(
      jelly,
      /function expandFailureCategory\(categoryBar, category, status\)\s*\{[\s\S]*?expandCard\(card\);[\s\S]*?OctaneTestManagement\.revealFailureCategory\(\s*testManagementZone, category\);/);
  assert.match(source, /function scheduleFailureCategoryReveal\(zone, category\)/);
  assert.match(source, /global\.requestAnimationFrame\(function \(\) \{/);
  assert.match(source, /function revealFailureCategory\(zone, category\)/);
  assert.match(
      source,
      /card\.classList\.contains\("octane-expanded"\)/);
  assert.match(
      source,
      /categoryElement\(chart, "data-management-category", category\)/);
  assert.match(
      source,
      /categoryElement\(switcher, "data-management-category-filter", category\)/);
  assert.equal(
      (source.match(/scrollHorizontalItemIntoView\(/g) || []).length >= 3,
      true);
  assert.match(source, /container\.scrollWidth <= container\.clientWidth \+ 1/);
  assert.match(
      source,
      /container\.scrollTo\(\{behavior: "auto", left: nextScrollLeft\}\)/);
  assert.match(
      source,
      /revealFailureCategory: scheduleFailureCategoryReveal/);
});

test("opens Test Failure Analysis in individual focus from an email deep link", () => {
  assert.match(jelly, /function applyDeepLinkedTestFailureFocus\(\)/);
  assert.match(
      jelly,
      /parameters\.get\("octaneFocus"\) !== "test-management-failures"/);
  assert.match(
      jelly,
      /parameters\.get\("octaneFocusMode"\) !== "individual"/);
  assert.match(
      jelly,
      /dashboard\.querySelector\(\s*'\[data-card-key="test-management-failures"\]'\)/);
  assert.match(
      jelly,
      /function applyDeepLinkedTestFailureFocus[\s\S]*?expandCard\(card\);/);
  assert.match(jelly, /applyDeepLinkedTestFailureFocus\(\);/);
});

test("keeps focused defect groups on one line with conditional scroll controls", () => {
  assert.match(jelly, /data-management-failure-tab-nav="true"/);
  assert.match(jelly, /data-management-category-scroll="-1"/);
  assert.match(jelly, /data-management-category-scroll="1"/);
  assert.match(jelly, /aria-label="Scroll defect groups left"/);
  assert.match(jelly, /aria-label="Scroll defect groups right"/);
  assert.match(
      jelly,
      /\.octane-management-failure-switcher\s*\{[^}]*flex-wrap: nowrap;[^}]*overflow-x: auto;/s);
  assert.match(
      jelly,
      /\.octane-management-category-toggle\s*\{[^}]*flex: 0 0 auto;[^}]*white-space: nowrap;/s);
  assert.match(
      jelly,
      /\.octane-management-category-scroll\[data-visible="true"\]\s*\{[^}]*display: inline-flex;/s);
  assert.match(source, /function updateCategoryScrollControls\(container\)/);
  assert.match(source, /navigation\.clientWidth - \(columnGap \* 2\)/);
  assert.match(source, /container\.scrollWidth > availableWithoutControls \+ 1/);
  assert.match(source, /previous\.disabled = !hasOverflow \|\| scrollLeft <= 1/);
  assert.match(source, /next\.disabled = !hasOverflow \|\| scrollLeft >= maximum - 1/);
  assert.match(source, /new global\.ResizeObserver/);
  assert.match(source, /container\.scrollBy\(\{/);
  assert.match(source, /data-management-category-scroll/);
  assert.match(source, /scrollIntoView\(\{/);
});

test("builds content through safe DOM APIs and no HTML injection", () => {
  assert.doesNotMatch(source, /\.innerHTML\s*=/);
  assert.match(source, /textContent =/);
  assert.match(source, /document\.createElement/);
  assert.match(
      jelly,
      /\.octane-management-defect-description\s*\{[^}]*white-space: pre-line;/s);
});

test("reuses cached defect rows when switching Failure Analysis tabs", () => {
  const categorySwitchSource = source
      .split("function setSelectedCategory(zone, category) {")[1]
      .split("function failureDetailEntries(payload) {")[0];
  assert.match(source, /list\.__octaneFailureDetailCache/);
  assert.match(source, /cache\.renderKey !== renderKey/);
  assert.match(source, /entry\.row\.hidden = true/);
  assert.match(source, /function applyFailureDetailFilters\(list, cache, category\)/);
  assert.match(
      source,
      /if \(cache\.sortColumn !== sortState\.column[\s\S]*?renderFailureDetailStructure/);
  assert.doesNotMatch(categorySwitchSource, /clear\(/);
});

test("skips unchanged Failure Analysis DOM work and batches replacements", () => {
  assert.match(source, /function failureRenderKey\(payload, colors\)/);
  assert.match(source, /panel\.__octaneFailureRenderKey === renderKey/);
  assert.match(source, /panel\.__octaneFailureRenderKey = renderKey/);
  assert.match(source, /var chartFragment = document\.createDocumentFragment\(\)/);
  assert.match(source, /chart\.appendChild\(chartFragment\)/);
  assert.match(source, /var fragment = document\.createDocumentFragment\(\)/);
  assert.match(source, /if \(cache\.visibleCategory === category\) \{\s*return;/);
});

test("filters the defect table to the category selected by a pill", () => {
  const entries = [
    ...Array.from({length: 5}, (_, index) => ({
      category: "ui", defect: {id: String(index + 1), open: index < 3}
    })),
    ...Array.from({length: 3}, (_, index) => ({
      category: "api", defect: {id: String(index + 6), open: index < 2}
    }))
  ];

  const uiDefects = filterFailureDetailEntries(entries, "ui");
  assert.equal(uiDefects.length, 5);
  assert.ok(uiDefects.every((entry) => entry.category === "ui"));
});

test("filters the defect table to the category selected by a vertical bar", () => {
  const entries = [
    ...Array.from({length: 4}, (_, index) => ({
      category: "database", defect: {id: String(index + 1), open: index < 2}
    })),
    ...Array.from({length: 2}, (_, index) => ({
      category: "network", defect: {id: String(index + 5), open: true}
    }))
  ];

  const databaseDefects = filterFailureDetailEntries(entries, "database");
  assert.equal(databaseDefects.length, 4);
  assert.ok(databaseDefects.every((entry) => entry.category === "database"));
});

test("binds pills and bar segments to the same category-only filter", () => {
  assert.match(source, /createElement\("button", "octane-management-failure-bar"\)/);
  assert.match(source, /data-management-defect-status/);
  assert.match(source, /function selectFailureCategory\(zone, category\)/);
  assert.match(
      source,
      /setSelectedCategory\(zone, category\);[\s\S]*?__octaneTestManagementOnCategorySelect\(categoryBar, category, status\)/);
  assert.match(
      source,
      /setSelectedCategory\(zone, category\)[\s\S]*?selectFailureCategory\(zone, category\)/);
  assert.doesNotMatch(source, /function selectFailureSegment\(/);
  assert.match(source, /selectedButton\.scrollIntoView\(\{/);
});

test("isolates the defect table to the selected category pill and resets on All", () => {
  const entries = [
    ...Array.from({length: 3}, (_, index) => ({
      category: "paybill-clos", defect: {id: "pc" + index}
    })),
    ...Array.from({length: 2}, (_, index) => ({
      category: "thi-team", defect: {id: "tt" + index}
    })),
    {category: "digital-more", defect: {id: "dm0"}},
    {category: "i-pre", defect: {id: "ip0"}},
    ...Array.from({length: 2}, (_, index) => ({
      category: "other", defect: {id: "ot" + index}
    }))
  ];
  assert.equal(entries.length, 9);

  const paybillClosRows = filterFailureDetailEntries(entries, "paybill-clos");
  assert.equal(paybillClosRows.length, 3);
  assert.ok(paybillClosRows.every((entry) => entry.category === "paybill-clos"));

  const resetRows = filterFailureDetailEntries(entries, "all");
  assert.equal(resetRows.length, 9);

  // Selection state and table re-filtering must be one call chain, not two independent updates,
  // otherwise a pill can render active while the table keeps showing every category.
  assert.match(
      source,
      /function setFailureSelection\(zone, category\) \{[\s\S]*?zone\.__octaneSelectedFailureCategory = selectedCategory;[\s\S]*?switcher\.setAttribute\("data-selected-category", selectedCategory\);[\s\S]*?renderFailureDetails\(zone\);\n  \}/);
  assert.match(
      source,
      /function renderFailureDetails\(zone, colors, renderKey\) \{[\s\S]*?var category = selectedCategory\(zone\);[\s\S]*?applyFailureDetailFilters\(list, cache, category\);/);
  // Pills must match by the category key, never by the rendered "Label N" text with its count.
  assert.match(
      source,
      /button\.setAttribute\(\s*"data-management-category-filter", key\);/);
  assert.match(
      source,
      /function failureDetailMatches\(entry, category\) \{[\s\S]*?failureCategoryKey\(entry\.category\) === selected;/);
});

test("exposes responsive focus, scaling, and card controls", () => {
  assert.match(jelly, /\.octane-test-management-zone\.octane-zone-focused/);
  assert.match(
      jelly,
      /\.octane-test-management-card\s*\{[^}]*flex: 1 1 calc\(25% - 0\.75rem\)/s);
  assert.match(
      jelly,
      /\.octane-test-management-zone\.octane-zone-focused[\s\S]*nth-of-type\(3\)/);
  assert.match(jelly, /container-type: size/);
  assert.match(jelly, /font-size: clamp\(/);
  assert.match(jelly, /data-card-key="test-management-burndown"/);
  assert.match(jelly, /data-card-key="test-management-current-state"/);
  assert.match(jelly, /data-card-key="test-management-failures"/);
  assert.match(jelly, /data-card-key="test-management-metrics"/);
  assert.equal(new Set(
      (jelly.match(/data-card-key="test-management-[^"]+"/g) || [])
          .map((match) => match.slice("data-card-key=\"".length, -1))).size, 4);
});

test("uses legend-only headers and explicitly labelled vertical axes", () => {
  assert.match(jelly, /octane-management-subtitle-line/);
  assert.equal(
      (jelly.match(/data-management-legend="true"/g) || []).length >= 3,
      true);
  assert.match(jelly, /Testing Against Schedule/);
  assert.match(jelly, /Execution per Sprint Parts/);
  assert.doesNotMatch(jelly, /Burn-down Chart/);
  assert.doesNotMatch(jelly, /Current Execution State/);
  assert.doesNotMatch(jelly, /Defect Root-Cause Breakdown/);
  assert.match(jelly, /octane-management-y-axis-title">Total Test cases</);
  assert.match(jelly, /octane-management-y-axis-title">Tests Executed</);
  assert.match(jelly, /octane-management-y-axis-title">Defects</);
  assert.match(
      jelly,
      /\.octane-management-subtitle-line\s*\{[^}]*flex-wrap: nowrap;[^}]*overflow-x: auto;/s);
});

test("matches timer heights and preserves bounded scrollable bar tracks", () => {
  assert.match(
      jelly,
      /\.octane-timer-zone:not\(\.octane-zone-focused\)[\s\S]*?height: 280px;[\s\S]*?max-height: 280px;[\s\S]*?min-height: 280px;/);
  assert.match(
      jelly,
      /\.octane-test-management-zone:not\(\.octane-zone-focused\)[\s\S]*?> \.octane-test-management-card:not\(\.octane-expanded\)/);
  assert.match(
      jelly,
      /\.octane-management-state-bars\s*\{[^}]*--octane-management-bar-gap: clamp\(2px, 1cqw, 40px\);[^}]*--octane-management-bar-width: clamp\(8px, 4cqw, 100px\);[^}]*overflow-x: auto;/s);
  assert.match(
      jelly,
      /\.octane-management-state-column\s*\{[^}]*max-width: 100px;[^}]*min-width: 8px;/s);
  assert.match(
      jelly,
      /\.octane-management-failure-chart\s*\{[^}]*overflow-x: hidden;[^}]*scrollbar-width: none;/s);
  assert.match(
      jelly,
      /\.octane-chart-card\[data-card-key="test-management-failures"\]\.octane-expanded\s*\.octane-management-failure-chart\s*\{[^}]*overflow-x: auto;[^}]*overscroll-behavior-inline: contain;[^}]*scrollbar-color: var\(--octane-management-scrollbar-thumb\) transparent;[^}]*scrollbar-gutter: stable;[^}]*scrollbar-width: thin;/s);
  assert.match(
      jelly,
      /\.octane-chart-card\[data-card-key="test-management-failures"\]\.octane-expanded\s*\.octane-management-failure-chart::-webkit-scrollbar\s*\{[^}]*height: 6px;/s);
  assert.match(
      jelly,
      /\.octane-chart-card\[data-card-key="test-management-failures"\]\.octane-expanded\s*\.octane-management-failure-chart::-webkit-scrollbar-track\s*\{[^}]*background: transparent;/s);
  assert.match(
      jelly,
      /\.octane-chart-card\[data-card-key="test-management-failures"\]\.octane-expanded\s*\.octane-management-failure-chart::-webkit-scrollbar-thumb\s*\{[^}]*background: var\(--octane-management-scrollbar-thumb\);[^}]*border-radius: 999px;/s);
  assert.match(
      jelly,
      /\.octane-management-failure-bar\s*\{[^}]*max-width: 100px;[^}]*min-width: 8px;/s);
  assert.match(
      jelly,
      /\.octane-management-failure-label\s*\{[^}]*overflow: hidden;[^}]*text-overflow: ellipsis;[^}]*white-space: nowrap;/s);
});

test("keeps standard grid axes, rounded metric tiles, and capsule pills", () => {
  assert.match(
      jelly,
      /\.octane-management-plot-layout\s*\{[^}]*--octane-management-axis-gap: 0\.09rem;[^}]*column-gap: var\(--octane-management-axis-gap\)[^}]*grid-template-columns:\s*1\.35rem max-content minmax\(0, 1fr\)/s);
  assert.match(
      jelly,
      /\.octane-bar-graph\s*\{[^}]*column-gap: 0\.09rem;[^}]*grid-template-columns: 1\.35rem max-content minmax\(0, 1fr\)/s);
  assert.match(jelly, /\.octane-management-timeline-axis-line/);
  assert.match(jelly, /\.octane-management-state-bars::after/);
  assert.match(jelly, /\.octane-management-failure-grid-lines/);
  assert.match(jelly, /\.octane-management-failure-grid-line/);
  assert.match(jelly, /\.octane-management-failure-chart::after/);
  assert.match(
      jelly,
      /\.octane-management-failure-axis-layout\s*\{[^}]*--octane-management-failure-axis-row: 1\.65rem;[^}]*grid-template-rows:\s*minmax\(0, 1fr\) var\(--octane-management-failure-axis-row\)/s);
  assert.match(
      jelly,
      /\.octane-management-failure-axis-layout\s*> \.octane-management-y-labels\s*\{[^}]*display: block;[^}]*overflow: visible;[^}]*padding: 0;[^}]*position: relative;/s);
  assert.match(
      jelly,
      /\.octane-management-failure-axis-layout[\s\S]*?> \.octane-management-y-labels[\s\S]*?\.octane-management-failure-axis-track \.octane-management-axis-value\s*\{[^}]*top: var\(--octane-management-axis-position\);[^}]*transform: translateY\(-50%\);/s);
  assert.match(
      jelly,
      /\.octane-management-failure-group\s*\{[^}]*grid-template-rows:\s*minmax\(0, 1fr\) var\(--octane-management-failure-axis-row\)/s);
  assert.match(
      jelly,
      /\.octane-management-failure-chart::after\s*\{[^}]*bottom: calc\(var\(--octane-management-failure-axis-row\) - 1px\)/s);
  assert.match(
      jelly,
      /\.octane-management-metric-tile\s*\{[^}]*border-radius: 12px/s);
  assert.match(
      jelly,
      /\.octane-management-defect-pill\s*\{[^}]*border-radius: 9999px[^}]*padding:\s*clamp\(0\.25rem, 1cqi, 0\.4rem\)\s*clamp\(0\.4rem, 1\.5cqi, 0\.75rem\)/s);
});

test("scales testing metric typography inside compact quadrant containers", () => {
  assert.match(
      jelly,
      /\.octane-management-metrics-grid\s*\{[^}]*container-name: octane-management-metrics;[^}]*container-type: size;[^}]*gap: clamp\(0\.35rem, min\(1\.8cqi, 2\.5cqh\), 1rem\);[^}]*grid-template-rows: minmax\(0, 1fr\) minmax\(0, 1\.5fr\);/s);
  assert.match(
      jelly,
      /\.octane-management-metric-tile\s*\{[^}]*align-items: center;[^}]*overflow: hidden;[^}]*text-align: center;/s);
  assert.match(
      jelly,
      /\.octane-management-metric-title\s*\{[^}]*font-size: clamp\(0\.65rem, min\(2\.6cqi, 5cqh\), 1\.15rem\);[^}]*font-weight: 600;/s);
  assert.match(
      jelly,
      /\.octane-management-metric-value\s*\{[^}]*font-size: clamp\(1\.1rem, min\(5cqi, 10cqh\), 2\.7rem\);[^}]*font-weight: 700;/s);
  assert.match(
      jelly,
      /@container octane-management-metrics\s*\(max-width: 15rem\) or \(max-height: 10rem\)[\s\S]*?\.octane-management-metric-value\s*\{[^}]*font-size: clamp\(0\.56rem, min\(5\.5cqi, 6cqh\), 0\.88rem\)/s);
});

test("clips every metric quadrant and keeps tester rows on one line", () => {
  assert.match(
      jelly,
      /\.octane-management-metric-tile\s*\{[^}]*overflow: hidden;[^}]*padding: clamp\(0\.35rem, min\(2\.2cqi, 3cqh\), 1rem\);/s);
  assert.match(
      source,
      /tile\.setAttribute\(\s*"data-management-metric-key",\s*String\(metric\.key \|\| ""\)\.trim\(\)\.toLowerCase\(\)\);/s);
  assert.match(
      jelly,
      /\.octane-management-metric-items li\s*\{[^}]*display: grid;[^}]*grid-template-columns: minmax\(0, 1fr\) max-content;/s);
  assert.match(
      jelly,
      /data-management-metric-key="tester-volume"[\s\S]*?\.octane-management-metric-item-value\s*\{[^}]*grid-template-columns: minmax\(7ch, max-content\) 0\.5ch 4ch;[^}]*margin-inline-end: 1ch;/s);
  assert.match(
      jelly,
      /\.octane-management-metric-items > li > span\s*\{[^}]*overflow: hidden;[^}]*text-overflow: ellipsis;[^}]*white-space: nowrap;/s);
  assert.match(source, /item\.primaryValue != null/);
  assert.match(source, /octane-management-metric-item-secondary/);
});

test("normalizes and renders human-readable defect status and severity pills", () => {
  assert.match(source, /function canonicalSeverity\(value\)/);
  assert.match(source, /return "Very High"/);
  assert.match(source, /return "Unspecified"/);
  assert.match(source, /function displayedSeverity\(defect\)/);
  assert.match(source, /defect && defect\.severityLabel/);
  assert.match(
      source,
      /return configuredLabel \|\| canonicalSeverity\(defect && defect\.severity\)/);
  assert.match(source, /var severityLabel = displayedSeverity\(defect\)/);
  assert.match(source, /function canonicalStatus\(defect\)/);
  assert.match(source, /return defect\.open \? "Open" : "Closed"/);
  assert.doesNotMatch(source, /category\.(?:openColor|closedColor)/);
  assert.match(
      source,
      /statusLabel === "Open" \? colors\.open : colors\.closed/);
  assert.match(
      source,
      /defect\.severityColorKey \|\| defect\.severity \|\| severityLabel/);
  assert.match(source, /status\.setAttribute\("aria-label", "Status: " \+ statusLabel\)/);
  assert.match(
      source,
      /severity\.setAttribute\("aria-label", "Severity: " \+ severityLabel\)/);
});

test("sorts defect ids naturally and reverses the active column", () => {
  const defects = [
    {id: "DEF-10"},
    {id: "DEF-2"},
    {id: "ABC-1"}
  ];
  assert.deepEqual(
      Array.from(sortFailureDefects(defects, "id", "ascending"), defect => defect.id),
      ["ABC-1", "DEF-2", "DEF-10"]);
  assert.deepEqual(
      Array.from(sortFailureDefects(defects, "id", "descending"), defect => defect.id),
      ["DEF-10", "DEF-2", "ABC-1"]);
});

test("sorts descriptions by the first letter of their first word", () => {
  const defects = [
    {id: "1", description: "Zulu checkout"},
    {id: "2", description: "  alpha transfer"},
    {id: "3", description: "Beta payment"}
  ];
  assert.deepEqual(
      Array.from(
          sortFailureDefects(defects, "description", "ascending"),
          defect => defect.id),
      ["2", "3", "1"]);
});

test("groups open before closed defects and reverses on descending", () => {
  const defects = [
    {id: "closed", open: false},
    {id: "open", open: true}
  ];
  assert.deepEqual(
      Array.from(sortFailureDefects(defects, "status", "ascending"), defect => defect.id),
      ["open", "closed"]);
  assert.deepEqual(
      Array.from(sortFailureDefects(defects, "status", "descending"), defect => defect.id),
      ["closed", "open"]);
});

test("sorts individual and grouped severities by configured weight", () => {
  const defects = [
    {id: "medium", severity: "Medium", severitySortRank: 5},
    {id: "minor", severity: "Medium", severityLabel: "Minor", severitySortRank: 4},
    {id: "unspecified", severity: "Unspecified", severitySortRank: 6},
    {id: "major", severity: "High", severityLabel: "Major", severitySortRank: 1},
    {id: "very-high", severity: "Very High", severitySortRank: 2},
    {id: "high", severity: "High", severitySortRank: 3}
  ];
  assert.deepEqual(
      Array.from(
          sortFailureDefects(defects, "severity", "ascending"),
          defect => defect.id),
      ["major", "very-high", "high", "minor", "medium", "unspecified"]);
});

test("renders an accessible sticky four-column sort header with persistent state", () => {
  assert.match(source, /DEFECT_SORT_COLUMNS = \[/);
  assert.match(source, /\{key: "id", label: "Defect ID"\}/);
  assert.match(source, /data-sort-column/);
  assert.match(source, /data-sort-direction/);
  assert.match(source, /cell\.setAttribute\("aria-sort", state\.direction\)/);
  assert.match(source, /data-management-defect-sort/);
  assert.match(source, /renderDefectTableHeader\(list, sortState\)/);
  assert.match(
      source,
      /state\.column === column && state\.direction === "ascending"[\s\S]*?\? "descending"[\s\S]*?: "ascending"/);
  assert.match(
      jelly,
      /data-sort-column="id" data-sort-direction="ascending"[\s\S]*?role="table" aria-colcount="4"/);
  assert.match(
      jelly,
      /\.octane-management-defect-header\s*\{[^}]*position: sticky;[^}]*z-index: 2;/s);
  assert.match(
      jelly,
      /\.octane-management-defect-sort\s*\{[^}]*font-size: clamp\(0\.6188rem, 1\.8785cqi, 0\.7956rem\);/s);
  assert.match(
      jelly,
      /\.octane-management-defect-sort-indicator::before\s*\{[^}]*content: "\\2195";/s);
  assert.match(
      jelly,
      /\[aria-sort="ascending"\][\s\S]*?\.octane-management-defect-sort-indicator::before\s*\{[^}]*content: "\\2191";/s);
  assert.match(
      jelly,
      /\[aria-sort="descending"\][\s\S]*?\.octane-management-defect-sort-indicator::before\s*\{[^}]*content: "\\2193";/s);
});

test("resolves semantic chart and metric colors from the active theme", () => {
  assert.match(source, /blocked: "--octane-status-blocked"/);
  assert.match(source, /closed: "--octane-color-good"/);
  assert.match(source, /failed: "--octane-status-failed"/);
  assert.match(source, /open: "--octane-color-bad"/);
  assert.match(source, /passed: "--octane-status-passed"/);
  const colorsSource = source
      .split("function colorsFor(payload, zone) {")[1]
      .split("function normalizedPoints(payload) {")[0];
  assert.equal((colorsSource.match(/global\.getComputedStyle\(zone\)/g) || []).length, 1);
  assert.match(source, /computedStyle\.getPropertyValue\(propertyName\)\.trim\(\)/);
  assert.match(source, /colorsFor\(payload, zone\)/);
  assert.match(source, /critical: "--octane-severity-critical"/);
  assert.match(source, /veryhigh: "--octane-severity-very-high"/);
  assert.match(source, /high: "--octane-severity-high"/);
  assert.match(source, /low: "--octane-severity-low"/);
  assert.match(source, /medium: "--octane-severity-medium"/);
  assert.match(source, /unspecified: "--octane-severity-unspecified"/);
  [
    ["--octane-system-red", "#FF3B30", "#FF453A"],
    ["--octane-system-yellow", "#FFCC00", "#FFD60A"],
    ["--octane-system-orange", "#FF9500", "#FF9F0A"],
    ["--octane-severity-low", "#5AC8FA", "#64D2FF"],
    ["--octane-system-purple", "#AF52DE", "#BF5AF2"],
    ["--octane-system-gray", "#8E8E93", "#8E8E93"]
  ].forEach(([property, light, dark]) => {
    assert.match(jelly, new RegExp(`${property}: ${light}`));
    if (light !== dark) {
      assert.match(jelly, new RegExp(`${property}: ${dark}`));
    }
  });
  assert.match(jelly, /--octane-severity-critical: var\(--octane-system-red\)/);
  assert.match(jelly, /--octane-severity-very-high: var\(--octane-system-yellow\)/);
  assert.match(jelly, /--octane-severity-high: var\(--octane-system-orange\)/);
  assert.match(jelly, /--octane-severity-medium: var\(--octane-system-purple\)/);
  assert.match(jelly, /--octane-severity-unspecified: var\(--octane-system-gray\)/);
  assert.match(
      jelly,
      /\.octane-management-tone-good\s*\{[^}]*background: var\(--octane-color-good\);[^}]*color: var\(--octane-color-on-emphasis\);/s);
  assert.match(
      jelly,
      /\.octane-management-tone-warning\s*\{[^}]*background: var\(--octane-system-orange\);[^}]*color: var\(--octane-color-on-emphasis\);/s);
  assert.match(
      jelly,
      /\.octane-management-tone-bad\s*\{[^}]*background: var\(--octane-color-bad\);[^}]*color: var\(--octane-color-on-emphasis\);/s);
  assert.match(
      jelly,
      /\.octane-management-tone-neutral\s*\{[^}]*background: var\(--octane-system-gray\);[^}]*color: var\(--octane-color-on-emphasis\);/s);
  assert.match(
      jelly,
      /\.octane-management-category-toggle\[aria-pressed="true"\]\s*\{[^}]*background: var\(--octane-color-neutral\);[^}]*color: var\(--octane-color-on-emphasis\);/s);
  assert.doesNotMatch(
      jelly,
      /\.octane-management-tone-(?:good|warning|bad|neutral)\s*\{[^}]*(?:#34C759|#FF3B30|#FF9500|#8E8E93|#ffffff)/s);
});

test("coalesces repeated polling updates into one connected-frame render", () => {
  assert.match(source, /function scheduleRender\(zone\)/);
  assert.match(source, /zone\.__octaneTestManagementRenderFrame != null/);
  assert.match(source, /global\.requestAnimationFrame\.bind\(global\)/);
  assert.match(source, /return global\.setTimeout\(callback, 16\)/);
  assert.match(source, /zone\.isConnected !== false/);
  assert.match(
      source,
      /function update\(zone, payload\)[\s\S]*?zone\.__octaneTestManagementPayload = payload \|\| \{\};[\s\S]*?scheduleRender\(zone\);/);
});

test("renders QA metrics through the relocated pass-rate face", () => {
  assert.match(
      source,
      /var metricsRoot = zone\.__octaneTestManagementMetricsRoot \|\| zone;/);
  assert.match(
      source,
      /options && options\.metricsRoot \? options\.metricsRoot : zone/);
  assert.match(jelly, /function installAnalyticsComponentSwap\(root\)/);
  assert.match(jelly, /metricsFace\.setAttribute\("data-card-view", "metrics"\)/);
  assert.match(jelly, /managementCard\.setAttribute\("data-card-key", "test-management-defects"\)/);
  assert.match(jelly, /metricsRoot: managementMetricsRoot/);
});

test("keeps defect ids, descriptions, status, and severity in aligned columns", () => {
  assert.match(source, /octane-management-defect-id/);
  assert.match(
      source,
      /row\.appendChild\(identifier\);\s*row\.appendChild\(description\);\s*row\.appendChild\(status\);\s*row\.appendChild\(severity\)/s);
  assert.match(
      jelly,
      /\.octane-management-defect-header,[\s\S]*?\.octane-management-defect-row\s*\{[^}]*display: grid;[^}]*grid-template-columns: var\(--octane-management-defect-columns\);/s);
  assert.match(
      jelly,
      /--octane-management-pill-width:\s*clamp\(6\.25rem, calc\(11ch \+ 1\.5rem\), 8\.5rem\)/s);
  assert.match(
      jelly,
      /--octane-management-defect-id-width:\s*clamp\(4rem, 13cqi, 6rem\)/s);
  assert.match(
      jelly,
      /--octane-management-defect-columns:\s*var\(--octane-management-defect-id-width\) minmax\(0, 1fr\)\s*repeat\(2, var\(--octane-management-pill-width\)\)/s);
  assert.match(
      jelly,
      /\.octane-management-defect-pill\s*\{[^}]*inline-size: 100%[^}]*text-align: center;/s);
  assert.match(
      jelly,
      /@container octane-management-defects \(max-width: 32rem\)/);
  assert.match(
      jelly,
      /@container octane-management-defects \(max-width: 22rem\)/);
});

test("places test management between timer and reporting zones", () => {
  const timerIndex = jelly.indexOf("id=\"octane-timer-zone\"");
  const managementIndex = jelly.indexOf("id=\"octane-test-management-zone\"");
  const reportIndex = jelly.indexOf("id=\"octane-report-zone\"");

  assert.ok(timerIndex >= 0);
  assert.ok(managementIndex > timerIndex);
  assert.ok(reportIndex > managementIndex);
});
