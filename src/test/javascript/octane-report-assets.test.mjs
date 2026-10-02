import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import {dashboardCss, dashboardScript, reportMarkup} from "./report-assets.mjs";

test("the report loads external CSS and JavaScript without inline blocks or handlers", () => {
  assert.doesNotMatch(reportMarkup, /<style\b/i);
  assert.doesNotMatch(reportMarkup, /<script(?![^>]*\bsrc=)[^>]*>/i);
  assert.doesNotMatch(reportMarkup, /\son[a-z]+\s*=/i);
  assert.match(reportMarkup, /<l:header>\s*<st:adjunct includes="css\.octane-dashboard"/);
  const scriptIndex = reportMarkup.indexOf('<st:adjunct includes="js.octane-dashboard"');
  for (const marker of ['id="octane-dashboard"', 'src="scaleReportScript"', 'src="testManagementScript"']) {
    assert.ok(scriptIndex > reportMarkup.indexOf(marker), `${marker} must precede dashboard startup`);
  }
});

test("extracted assets are static and executable without Jelly interpolation", () => {
  assert.doesNotMatch(dashboardCss + dashboardScript, /\$\{|<!\[CDATA\[|\]\]>/);
  assert.doesNotMatch(dashboardScript, /[^\x00-\x7f]/);
  assert.match(dashboardCss, /\.octane-dashboard\s*\{/);
  assert.match(dashboardScript, /window\.fetch\(snapshotUrl/);
  assert.doesNotThrow(() => new vm.Script(dashboardScript));
  assert.doesNotThrow(() => vm.runInNewContext(dashboardScript, {
    document: {getElementById: () => null}
  }));
});
