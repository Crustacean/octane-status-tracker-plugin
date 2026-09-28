import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {pathToFileURL} from "node:url";

const source = readFileSync("src/main/webapp/js/octane-scale-report.js", "utf8");
const chromeAvailable =
    spawnSync("sh", ["-c", "command -v google-chrome"], {stdio: "ignore"}).status === 0;

test("remote report fields remain inert text through rendering and refresh", {
  skip: !chromeAvailable, timeout: 30000
}, () => {
  const directory = mkdtempSync(join(tmpdir(), "octane-scale-xss-"));
  const fixture = join(directory, "index.html");
  // Escape the test fixture's script context, then pass the original attack strings through JSON.
  const attack = '</script><img src=x onerror="window.xss=1"><svg onload="window.xss=2">&';
  const encoded = JSON.stringify(attack).replaceAll("<", "\\u003c");
  const html = `<!doctype html><html><body><div id="zone" style="width:900px"></div>
    <script>${source}</script><script>
    window.IntersectionObserver = undefined;
    window.ResizeObserver = undefined;
    const attack = ${encoded};
    const status = {key: "failed", label: attack, count: 1, color: "#FF453A",
      percentageLabel: attack, tooltipColor: "#FF453A"};
    const bar = {id: attack, name: attack, title: attack, total: 1, statuses: [status]};
    const section = {id: 0, source: attack, distributionTitle: attack, barChartTitle: attack,
      metrics: {total: 1}, totals: [status], suiteRunCount: 1, barCount: 1, maxTotal: 1,
      executedTestCount: 1, automationPercentageLabel: attack, automationEmoji: attack};
    window.fetch = async url => ({ok: true, status: 200, json: async () =>
      String(url).includes("section=")
        ? {bars: [bar], totalBars: 1, cursor: 0, nextCursor: -1}
        : {schemaVersion: 1, sections: [section]}});
    (async () => {
      const zone = document.getElementById("zone");
      await OctaneScaleReport.mount(zone, "data", "first");
      await new Promise(resolve => setTimeout(resolve, 50));
      await OctaneScaleReport.mount(zone, "data", "second");
      await new Promise(resolve => setTimeout(resolve, 50));
      const checks = [
        !window.xss,
        !zone.querySelector("img,script,iframe,[onerror],[onload]"),
        zone.querySelector(".octane-card-title").textContent === attack,
        zone.querySelector(".octane-donut-legend-status").textContent === attack,
        zone.querySelector(".octane-chart-data-summary th").textContent === attack,
        zone.querySelector("[data-bar-name]").getAttribute("data-bar-name") === attack,
        zone.querySelectorAll("[data-octane-loaded=true]").length === 1,
        zone.querySelectorAll(".octane-chart-card").length === 2
      ];
      document.body.setAttribute("data-security-checks", checks.join(","));
      document.body.setAttribute("data-xss-safe", checks.every(Boolean) ? "pass" : "fail");
    })().catch(error => document.body.setAttribute("data-test-error", error.message));
    </script></body></html>`;
  writeFileSync(fixture, html, "utf8");
  try {
    const result = spawnSync("google-chrome", [
      "--headless=new", "--no-sandbox", "--disable-gpu",
      `--user-data-dir=${join(directory, "profile")}`,
      "--virtual-time-budget=1000", "--dump-dom", pathToFileURL(fixture).href
    ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 20000});
    assert.equal(result.status, 0, result.error || result.stderr);
    assert.match(result.stdout, /data-xss-safe="pass"/,
        result.stdout.match(/data-(?:security-checks|test-error)="[^"]*"/g)?.join(" "));
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
});
