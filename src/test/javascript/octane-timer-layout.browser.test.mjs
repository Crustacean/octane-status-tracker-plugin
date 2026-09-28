import assert from "node:assert/strict";
import {spawn, spawnSync} from "node:child_process";
import {existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {createServer} from "node:net";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {pathToFileURL} from "node:url";

const jellyPath =
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
    + "OctaneGateReportAction/index.jelly";
const jelly = readFileSync(jellyPath, "utf8");
const styleMatch = jelly.match(/<style>([\s\S]*?)<\/style>/);
assert.ok(styleMatch, "The report page must contain its dashboard stylesheet");
const metricLabelSource = jelly
    .split("/* OCTANE_TEST_METRIC_LABELS_START */")[1]
    .split("/* OCTANE_TEST_METRIC_LABELS_END */")[0];
const activityRingLayoutSource = jelly
    .split("/* OCTANE_ACTIVITY_RING_LAYOUT_START */")[1]
    .split("/* OCTANE_ACTIVITY_RING_LAYOUT_END */")[0];
const activityRingUpdateSource = jelly
    .split("/* OCTANE_ACTIVITY_RING_UPDATE_START */")[1]
    .split("/* OCTANE_ACTIVITY_RING_UPDATE_END */")[0];

const viewports = [
  {height: 640, name: "compact", width: 360},
  {height: 420, name: "short-wide", width: 1200},
  {height: 900, name: "tablet", width: 768},
  {height: 900, name: "desktop", width: 1440},
  {height: 1440, name: "wide", width: 2560}
];
const snapGeckodriver = "/snap/firefox/current/usr/lib/firefox/geckodriver";
const geckodriverExecutable = existsSync(snapGeckodriver)
    ? snapGeckodriver
    : "geckodriver";

function executableAvailable(name) {
  return spawnSync("sh", ["-c", `command -v ${name}`], {stdio: "ignore"}).status === 0;
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function availablePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      server.close(error => error ? reject(error) : resolve(address.port));
    });
  });
}

async function webdriverRequest(baseUrl, method, path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: {"Content-Type": "application/json"},
    method,
    signal: AbortSignal.timeout(10000)
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || (payload.value && payload.value.error)) {
    throw new Error(
        `WebDriver ${method} ${path} failed: ${JSON.stringify(payload.value || payload)}`);
  }
  return payload.value;
}

async function waitForWebdriver(baseUrl, driver) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (driver.exitCode !== null) {
      throw new Error(`geckodriver exited before startup with code ${driver.exitCode}`);
    }
    try {
      const response = await fetch(`${baseUrl}/status`, {signal: AbortSignal.timeout(1000)});
      if (response.ok) {
        return;
      }
    } catch (error) {
      // The driver has not opened its socket yet.
    }
    await delay(100);
  }
  throw new Error("Timed out waiting for geckodriver");
}

async function withFirefox(callback) {
  const port = await availablePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const driver = spawn(
      geckodriverExecutable,
      ["--host", "127.0.0.1", "--port", String(port), "--log", "fatal"],
      {detached: true, stdio: "ignore"});
  let sessionId = "";
  try {
    await waitForWebdriver(baseUrl, driver);
    const session = await webdriverRequest(baseUrl, "POST", "/session", {
      capabilities: {
        alwaysMatch: {
          browserName: "firefox",
          "moz:firefoxOptions": {args: ["-headless"]}
        }
      }
    });
    sessionId = session.sessionId;
    await webdriverRequest(
        baseUrl, "POST", `/session/${sessionId}/timeouts`, {script: 5000});
    await callback({baseUrl, sessionId});
  } finally {
    if (sessionId) {
      await Promise.race([
        webdriverRequest(baseUrl, "DELETE", `/session/${sessionId}`).catch(() => {}),
        delay(3000)
      ]);
    }
    try {
      driver.kill("SIGKILL");
    } catch (error) {
      // The driver already exited after deleting the session.
    }
    driver.unref();
  }
}

function timerCard(index, title) {
  const metricsFace = index === 0
      ? `<div class="octane-flip-face octane-flip-face-metrics" data-card-view="metrics">
          <div class="octane-flip-face-header">
            <div>
              <h2 class="octane-card-title">Test Insights</h2>
              <div class="octane-muted">Current Job Analytics</div>
            </div>
          </div>
          <div class="octane-flip-face-body" data-test-metrics-panel="true">
            <div class="octane-test-metrics-grid">
              <article class="octane-test-metric-card octane-test-metric-automation-usage">
                <div class="octane-test-metric-heading"><span>Automation Usage</span></div>
                <div class="octane-test-metric-visual octane-test-metric-visual-automation">
                  <div class="octane-test-metric-value">80%</div>
                  <div class="octane-test-metric-automation-segments"
                      data-test-metric-segments="true">
                    <div class="octane-test-metric-automation-track"
                        aria-label="8/10 tests Automated" role="img">
                      <span class="octane-test-metric-automation-color
                          octane-test-metric-automation-automated"
                          style="--octane-test-metric-segment-share:80%"></span>
                      <span class="octane-test-metric-automation-color
                          octane-test-metric-automation-manual"
                          style="--octane-test-metric-segment-share:20%"></span>
                    </div>
                    <div class="octane-test-metric-automation-labels">
                      <div class="octane-test-metric-automation-segment"
                          data-test-metric-segment="true" data-full-label="🔥 Automated"
                          data-short-label="🔥"
                          style="--octane-test-metric-segment-share:80%">
                        <span class="octane-test-metric-segment-label">🔥 Automated</span>
                      </div>
                      <div class="octane-test-metric-automation-segment"
                          data-test-metric-segment="true" data-full-label="🐢 Manual"
                          data-short-label="🐢"
                          style="--octane-test-metric-segment-share:20%">
                        <span class="octane-test-metric-segment-label">🐢 Manual</span>
                      </div>
                    </div>
                  </div>
                </div>
                <div class="octane-test-metric-detail">8/10 tests Automated</div>
                <div class="octane-test-metric-trend octane-test-metric-trend-positive">
                  Target achieved
                </div>
              </article>
              <article class="octane-test-metric-card octane-test-metric-success-rate">
                <div class="octane-test-metric-heading"><span>Success Rate</span></div>
                <div class="octane-test-metric-visual octane-test-metric-gauge">
                  <svg class="octane-test-metric-gauge-svg" viewBox="0 0 84 48"
                      aria-hidden="true">
                    <path class="octane-test-metric-gauge-track"
                        d="M12 42 A30 30 0 0 1 72 42" pathLength="100"></path>
                    <path class="octane-test-metric-gauge-fill"
                        d="M12 42 A30 30 0 0 1 72 42" pathLength="100"
                        stroke-dasharray="62.2 100"></path>
                    <text class="octane-test-metric-gauge-value" x="42" y="43">
                      62.2%
                    </text>
                  </svg>
                </div>
                <div class="octane-test-metric-detail">680 / 1093 passed</div>
                <div class="octane-test-metric-trend octane-test-metric-trend-positive">
                  +1.7% improvement
                </div>
              </article>
              <article class="octane-test-metric-card octane-test-metric-execution">
                <div class="octane-test-metric-heading"><span>Execution Completion</span></div>
                <div class="octane-test-metric-visual octane-test-metric-visual-progress">
                  <div class="octane-test-metric-value">71.3%</div>
                  <div class="octane-test-metric-progress-wrap">
                    <progress class="octane-test-metric-progress" max="100"
                        value="71.3"></progress>
                  </div>
                </div>
                <div class="octane-test-metric-detail">779 / 1093 executed</div>
                <div class="octane-test-metric-trend octane-test-metric-trend-warning">
                  -12.4% from last cycle
                </div>
              </article>
              <article class="octane-test-metric-card octane-test-metric-defects">
                <div class="octane-test-metric-heading"><span>Open Defects</span></div>
                <div class="octane-test-metric-visual octane-test-metric-visual-defects">
                  <div class="octane-test-metric-value">28 open</div>
                  <div class="octane-test-metric-defect-segments"
                      data-test-metric-segments="true">
                    <div class="octane-test-metric-defect-track" aria-hidden="true">
                      ${[
                      ["Major (14)", "M (14)", "critical", 50],
                      ["Medium (9)", "M (9)", "medium", 32.1429],
                      ["Unspecified (5)", "U (5)", "unspecified", 17.8571]
                    ].map(([, , severity, percentage]) => `
                        <span class="octane-test-metric-defect-color
                            octane-test-metric-defect-color-${severity}"
                            style="--octane-test-metric-segment-share:${percentage}%">
                        </span>`).join("")}
                    </div>
                    <div class="octane-test-metric-defect-labels">
                      ${[
                        ["Major (14)", "M (14)", 50],
                        ["Medium (9)", "M (9)", 32.1429],
                        ["Unspecified (5)", "U (5)", 17.8571]
                      ].map(([full, short, percentage]) => `
                      <div class="octane-test-metric-defect-segment"
                          data-test-metric-segment="true" data-full-label="${full}"
                          data-short-label="${short}"
                          style="--octane-test-metric-segment-share:${percentage}%">
                        <span class="octane-test-metric-defect-label">${full}</span>
                      </div>`).join("")}
                    </div>
                  </div>
                </div>
                <div class="octane-test-metric-detail">2.6 per 100 tests</div>
                <div class="octane-test-metric-trend octane-test-metric-trend-negative">
                  +9 from last cycle
                </div>
              </article>
            </div>
          </div>
        </div>`
      : "";
  return `
    <section class="octane-chart-card octane-timer-card" data-active-view="timer"
        data-card-key="${index === 0 ? "timer-timeout" : `timer-${index}`}">
      <div class="octane-flip-viewport">
        <div class="octane-flip-face octane-flip-face-timer">
          <div class="octane-flip-face-header">
            <div>
              <h2 class="octane-card-title">${title}</h2>
              <div class="octane-muted">Responsive graph</div>
            </div>
          </div>
          <div class="octane-flip-face-body">
            <div class="octane-timer-wrap">
              <svg class="octane-timer-donut" viewBox="0 0 240 240" role="img"
                  aria-label="${title}">
                <circle class="octane-timer-track" cx="120" cy="120" r="92"></circle>
                <circle class="octane-timer-progress" data-timer-progress="true"
                    cx="120" cy="120" r="92" pathLength="100" stroke="#34C759"
                    stroke-dasharray="72 100" transform="rotate(-90 120 120)"></circle>
                <text class="octane-timer-value" x="120" y="118">72</text>
                <text class="octane-timer-unit" x="120" y="139">percent</text>
              </svg>
            </div>
          </div>
        </div>
        ${metricsFace}
      </div>
    </section>`;
}

function activityRingCard() {
  return `
    <section class="octane-chart-card" id="octane-activity-layout-fixture"
        style="height:280px; margin-block-start:0.5rem; width:100%">
      <div class="octane-flip-face" data-card-view="timer">
       <div class="octane-flip-face-header">
        <div class="octane-activity-header-copy">
          <h2 class="octane-card-title">Execution Activity Rings</h2>
          <div class="octane-muted octane-activity-subtitle">
            <span class="octane-activity-inline-legend"
                data-activity-inline-legend="true">
              ${[
                ["execution", "Execution Rate", "Execution", "87.50%"],
                ["pass", "Pass Rate", "Pass", "92.25%"],
                ["automation", "Automation Usage", "Automation", "74.75%"]
              ].map(([key, full, compact, value]) => `
                <span class="octane-activity-legend-item">
                  <span class="octane-activity-ring-swatch octane-activity-ring-${key}"></span>
                  <span class="octane-activity-legend-full">${full}</span>
                  <span class="octane-activity-legend-compact">${compact}</span>
                  (<span data-activity-rate="${key}">${value}</span>)
                </span>`).join("")}
            </span>
          </div>
        </div>
       </div>
       <div class="octane-flip-face-body">
        <div class="octane-activity-rings" data-activity-rings="true"
            data-side-legend-visible="false"
            data-activity-value-execution="87.5"
            data-activity-value-pass="92.25"
            data-activity-value-automation="74.75">
          <div class="octane-activity-rings-layout">
            <div class="octane-activity-rings-graphic">
              <svg class="octane-activity-rings-svg" viewBox="0 0 240 240">
                <circle class="octane-activity-ring-track octane-activity-ring-execution"
                    cx="120" cy="120" r="84"></circle>
                <circle class="octane-activity-ring-progress octane-activity-ring-execution"
                    data-activity-ring="execution" cx="120" cy="120" r="84" pathLength="100"
                    stroke-dasharray="87.5 100" transform="rotate(-90 120 120)"></circle>
                <circle class="octane-activity-ring-track octane-activity-ring-pass"
                    cx="120" cy="120" r="64.5"></circle>
                <circle class="octane-activity-ring-progress octane-activity-ring-pass"
                    data-activity-ring="pass" cx="120" cy="120" r="64.5" pathLength="100"
                    stroke-dasharray="92.25 100" transform="rotate(-90 120 120)"></circle>
                <circle class="octane-activity-ring-track octane-activity-ring-automation"
                    cx="120" cy="120" r="45"></circle>
                <circle class="octane-activity-ring-progress octane-activity-ring-automation"
                    data-activity-ring="automation" cx="120" cy="120" r="45" pathLength="100"
                    stroke-dasharray="74.75 100" transform="rotate(-90 120 120)"></circle>
              </svg>
            </div>
            <table class="octane-activity-side-legend">
              <caption>Execution activity rates</caption>
              <tbody>
                <tr><th>Execution Rate</th><td data-activity-rate="execution">87.50%</td></tr>
                <tr><th>Pass Rate</th><td data-activity-rate="pass">92.25%</td></tr>
                <tr><th>Automation Usage</th><td data-activity-rate="automation">74.75%</td></tr>
              </tbody>
            </table>
          </div>
        </div>
       </div>
      </div>
    </section>`;
}

function testerDetailsFixture() {
  const tracker = (key, condition, rateLabel) => `
    <section class="octane-tester-tracker" aria-labelledby="${key}-tracker-title">
      <h3 class="octane-tester-tracker-title" id="${key}-tracker-title"
          aria-label="Testers with LESS THAN ${condition}">
        <span class="octane-tester-responsive-prefix">Testers with </span><span
            class="octane-tester-comparison-full">LESS THAN </span><span
            class="octane-tester-comparison-compact">&lt; </span><span
            data-tester-title-condition="true">${condition}</span>
      </h3>
      <div class="octane-tester-column-content" data-empty="false">
        <table class="octane-tester-table">
          <colgroup>
            <col class="octane-tester-email-column">
            <col class="octane-tester-rate-column">
          </colgroup>
          <thead>
            <tr>
              <th class="octane-tester-email" scope="col">Email</th>
              <th class="octane-tester-rate octane-tester-rate-header" scope="col"
                  aria-label="Suiterun ${rateLabel}">
                <span class="octane-tester-responsive-prefix">Suiterun </span><span>${rateLabel}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td class="octane-tester-email">
                tester.with.a.long.identity@example.com
              </td>
              <td class="octane-tester-rate">97.25%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>`;
  return `
    <section class="octane-tester-details-zone" id="octane-tester-layout-fixture">
      <header class="octane-tester-details-header">
        <h2 class="octane-tester-details-title">Tester Details</h2>
      </header>
      <div class="octane-tester-details-body">
        ${tracker("execution", "90% Execution (2)", "Execution")}
        ${tracker("pass-rate", "70% Pass Rate (3)", "Pass Rate")}
      </div>
    </section>`;
}

function managementCard(index, title) {
  const categoryNavigation = index === 2
      ? `<div class="octane-card-actions">
          <div class="octane-management-failure-tab-nav"
              data-management-failure-tab-nav="true"
              role="group" aria-label="Defect group filters">
            <button class="octane-management-category-scroll" type="button"
                data-management-category-scroll="-1" data-visible="true"
                aria-label="Scroll defect groups left">
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path d="M12.5 4.5L7 10l5.5 5.5"></path>
              </svg>
            </button>
            <div class="octane-management-failure-switcher"
                data-management-failure-switcher="true"
                data-selected-category="all" data-selected-status="all">
              ${[
                "All",
                "Environment Configuration",
                "Automation Framework",
                "Data Quality",
                "API Contract",
                "Infrastructure",
                "Product Defect",
                "Third Party"
              ].map((category, categoryIndex) => `
                <button class="octane-management-category-toggle" type="button"
                    aria-pressed="${categoryIndex === 0}">
                  ${category}
                </button>`).join("")}
            </div>
            <button class="octane-management-category-scroll" type="button"
                data-management-category-scroll="1" data-visible="true"
                aria-label="Scroll defect groups right">
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path d="M7.5 4.5L13 10l-5.5 5.5"></path>
              </svg>
            </button>
          </div>
        </div>`
      : "";
  const chart = index === 2
      ? `<div class="octane-management-failure-layout">
          <div class="octane-management-plot-layout
              octane-management-failure-axis-layout">
            <div class="octane-management-y-axis-title">Defects</div>
            <div class="octane-management-y-labels">
              <span class="octane-management-failure-axis-track">
                <span class="octane-management-axis-value" data-management-axis-value="10"
                    style="--octane-management-axis-position: 0%">10</span>
                <span class="octane-management-axis-value" data-management-axis-value="5"
                    style="--octane-management-axis-position: 50%">5</span>
                <span class="octane-management-axis-value" data-management-axis-value="0"
                    style="--octane-management-axis-position: 100%">0</span>
              </span>
            </div>
            <div class="octane-management-failure-chart">
              <span class="octane-management-failure-grid-lines" aria-hidden="true">
                <span class="octane-management-failure-grid-line"
                    data-management-grid-value="10"
                    style="--octane-management-axis-position: 0%"></span>
                <span class="octane-management-failure-grid-line"
                    data-management-grid-value="5"
                    style="--octane-management-axis-position: 50%"></span>
              </span>
              ${Array.from({length: 8}, (_, category) => `
                <div class="octane-management-failure-group" role="group">
                  <span class="octane-management-failure-bars">
                    <button class="octane-management-failure-bar" type="button"
                        data-management-defect-status="open"
                        style="--octane-bar-color: #FF453A;
                            --octane-bar-size: ${30 + category * 5}%"></button>
                    <button class="octane-management-failure-bar" type="button"
                        data-management-defect-status="closed"
                        style="--octane-bar-color: #34C759;
                            --octane-bar-size: ${20 + category * 4}%"></button>
                  </span>
                  <span class="octane-management-failure-label">
                    Long dynamic failure category ${category + 1}
                  </span>
                </div>`).join("")}
            </div>
          </div>
          <div class="octane-management-defect-detail-panel">
            <div class="octane-management-defect-list" role="table">
              <div class="octane-management-defect-header" role="row">
                ${["Defect ID", "Defect Description", "Status", "Severity"]
                  .map((label, column) => `
                    <div class="octane-management-defect-header-cell" role="columnheader"
                        ${column === 0 ? 'aria-sort="ascending"' : ""}>
                      <button class="octane-management-defect-sort" type="button">
                        <span class="octane-management-defect-sort-label">${label}</span>
                        <span class="octane-management-defect-sort-indicator"
                            aria-hidden="true"></span>
                      </button>
                    </div>`).join("")}
              </div>
              ${["Critical", "Very High", "High", "Medium", "Low", "Unspecified"]
                .map((severity, defect) => `
                  <div class="octane-management-defect-row" role="row">
                    <span class="octane-management-defect-id">#D-${defect + 1000}</span>
                    <span class="octane-management-defect-description">
                      Checkout payment authorization failed for a long regional account name
                    </span>
                    <span class="octane-management-defect-pill"
                        style="--octane-pill-color: #FF453A">Open</span>
                    <span class="octane-management-defect-pill"
                        style="--octane-pill-color: #9D1D34">${severity}</span>
                  </div>`).join("")}
            </div>
          </div>
        </div>`
      : index === 3
        ? `<div class="octane-management-metrics-grid">
            <article class="octane-management-metric-tile octane-management-tone-bad">
              <h3 class="octane-management-metric-title">Defect Compliance</h3>
              <div class="octane-management-metric-value">12 open</div>
              <div class="octane-management-metric-detail">2 / 7 defect criteria met</div>
            </article>
            <article class="octane-management-metric-tile octane-management-tone-bad">
              <h3 class="octane-management-metric-title">Open vs Closed</h3>
              <div class="octane-management-metric-value">12 open</div>
              <div class="octane-management-metric-detail">18 closed</div>
            </article>
            <article class="octane-management-metric-tile octane-management-tone-neutral"
                data-management-metric-key="tester-volume">
              <h3 class="octane-management-metric-title">Top Testers by Volume</h3>
              <div class="octane-management-metric-value">5 testers</div>
              <div class="octane-management-metric-detail">3 below 80% execution</div>
              <ul class="octane-management-metric-items">
                <li><span>tester.alpha.with.an.extremely.long.enterprise.identity.and.region@example.com</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">57 tests</span><span class="octane-management-metric-item-separator">|</span><span class="octane-management-metric-item-secondary">100%</span></strong></li>
                <li><span>tester.beta.with.an.extremely.long.enterprise.identity.and.region@example.com</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">46 tests</span><span class="octane-management-metric-item-separator">|</span><span class="octane-management-metric-item-secondary">80%</span></strong></li>
                <li><span>Tester Gamma</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">45 tests</span><span class="octane-management-metric-item-separator">|</span><span class="octane-management-metric-item-secondary">70%</span></strong></li>
                <li><span>Tester Delta</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">44 tests</span><span class="octane-management-metric-item-separator">|</span><span class="octane-management-metric-item-secondary">60%</span></strong></li>
                <li><span>Tester Epsilon</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">40 tests</span><span class="octane-management-metric-item-separator">|</span><span class="octane-management-metric-item-secondary">50%</span></strong></li>
              </ul>
            </article>
            <article class="octane-management-metric-tile octane-management-tone-bad"
                data-management-metric-key="tester-defects">
              <h3 class="octane-management-metric-title">Top Testers by Open Defects</h3>
              <div class="octane-management-metric-value">12 open</div>
              <div class="octane-management-metric-detail">Highest open workload</div>
              <ul class="octane-management-metric-items">
                <li><span>tester.alpha.with.an.extremely.long.enterprise.identity.and.region@example.com</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">105 open</span></strong></li>
                <li><span>tester.beta.with.an.extremely.long.enterprise.identity.and.region@example.com</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">23 open</span></strong></li>
                <li><span>Tester Gamma</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">8 open</span></strong></li>
                <li><span>Tester Delta</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">2 open</span></strong></li>
                <li><span>Tester Epsilon</span><strong class="octane-management-metric-item-value"><span class="octane-management-metric-item-primary">1 open</span></strong></li>
              </ul>
            </article>
          </div>`
        : `<div class="octane-management-chart">
          <div class="octane-management-plot-layout">
            <div class="octane-management-y-axis-title">Tests Executed</div>
            <div class="octane-management-y-labels">
              <span>10</span><span>5</span><span>0</span>
            </div>
            <div class="octane-management-state-bars">
              ${Array.from({length: 10}, (_, part) => `
                <div class="octane-management-state-column">
                  <span class="octane-management-state-segment"
                      style="--octane-segment-color: #34C759;
                          --octane-segment-size: ${20 + part * 6}%"></span>
                </div>`).join("")}
            </div>
            <div class="octane-management-x-labels">
              <span>Start</span><span>Middle</span><span>End</span>
            </div>
          </div>
        </div>`;
  return `
    <section class="octane-chart-card octane-test-management-card"
        data-card-key="test-management-${index}">
      <div class="octane-card-header">
        <div class="octane-management-header-copy">
          <h2 class="octane-card-title">${title}</h2>
          <div class="octane-management-subtitle-line">
            <div class="octane-management-legend">
              <span class="octane-management-legend-item">
                <span class="octane-management-legend-swatch"
                    style="--octane-legend-color: #34C759"></span>
                <span>Responsive series</span>
              </span>
            </div>
          </div>
        </div>
        ${categoryNavigation}
      </div>
      <div class="octane-management-card-body">
        ${chart}
      </div>
    </section>`;
}

function fixtureHtml() {
  const cards = [
    "Testing Session Monitor",
    "Status Check",
    "Execution Progress",
    "Execution Pass Rate"
  ].map((title, index) => timerCard(index, title)).join("");
  const managementCards = [
    "Testing Against Schedule",
    "Execution per Sprint Parts",
    "Test Failure Analysis",
    "Testing Metrics"
  ].map((title, index) => managementCard(index, title)).join("");
  return `<!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          :root {
            --background: #101218;
            --input-border: #343944;
            --panel-border-color: #343944;
            --text-color: #f3f6fb;
            --text-color-secondary: #9ea9bf;
          }
          html, body { margin: 0; min-height: 100%; }
          body { background: #101218; box-sizing: border-box; padding: 0.5rem; }
          ${styleMatch[1]}
          .octane-dashboard { margin-top: 0; }
        </style>
      </head>
      <body>
        <main class="octane-dashboard" id="octane-dashboard">
          <div class="octane-timer-zone octane-card-zone" id="octane-timer-zone">
            ${cards}
          </div>
          <div class="octane-test-management-zone octane-card-zone"
              id="octane-test-management-zone">
            ${managementCards}
          </div>
          ${activityRingCard()}
          ${testerDetailsFixture()}
        </main>
        <script>
          var dashboard = document.getElementById("octane-dashboard");
          var requestFrame = window.requestAnimationFrame.bind(window);
          function clamp(value, minimum, maximum) {
            return Math.max(minimum, Math.min(maximum, value));
          }
          function trimNumber(value) {
            return String(Number(Number(value).toFixed(2)));
          }
          ${metricLabelSource}
          ${activityRingLayoutSource}
          ${activityRingUpdateSource}
          initializeTestMetricSegments(dashboard);
          initializeActivityRingLayout();
        </script>
      </body>
    </html>`;
}

function chromiumFixtureHtml() {
  return fixtureHtml().replace("</body>", `
    <script>
      (function () {
            var zone = document.getElementById("octane-timer-zone");
            var cards = Array.prototype.slice.call(
                zone.querySelectorAll(":scope > .octane-chart-card"));
            function graphsFit(candidateCards) {
              return candidateCards.length > 0 && candidateCards.every(function (card) {
                var body = card.querySelector(".octane-flip-face-body");
                var graph = card.querySelector(".octane-timer-donut");
                var bodyRect = body.getBoundingClientRect();
                var graphRect = graph.getBoundingClientRect();
                var style = getComputedStyle(graph);
                return style.display !== "none"
                    && style.visibility === "visible"
                    && Number(style.opacity) > 0
                    && graphRect.width >= 24
                    && graphRect.height >= 24
                    && Math.abs(graphRect.width - graphRect.height) <= 1.5
                    && graphRect.left >= bodyRect.left - 1
                    && graphRect.right <= bodyRect.right + 1
                    && graphRect.top >= bodyRect.top - 1
                    && graphRect.bottom <= bodyRect.bottom + 1;
              });
            }
            function afterPaint(callback) {
              setTimeout(function () {
                void zone.offsetHeight;
                callback();
              }, 0);
            }
            var checks = [];
            afterPaint(function () {
              checks.push(cards.length === 4 && graphsFit(cards));
              zone.classList.add("octane-zone-focused");
              afterPaint(function () {
                checks.push(graphsFit(cards));
                zone.classList.remove("octane-zone-focused");
                cards[0].classList.add("octane-expanded");
                afterPaint(function () {
                  checks.push(graphsFit([cards[0]]));
                  document.body.setAttribute("data-chromium-checks", checks.join(","));
                  document.body.setAttribute(
                      "data-chromium-layout", checks.every(Boolean) ? "pass" : "fail");
                });
              });
            });
      })();
    </script>
  </body>`);
}

async function execute(driver, script, args = []) {
  return webdriverRequest(
      driver.baseUrl,
      "POST",
      `/session/${driver.sessionId}/execute/sync`,
      {args, script});
}

async function executeAfterPaint(driver, script, args = []) {
  return webdriverRequest(
      driver.baseUrl,
      "POST",
      `/session/${driver.sessionId}/execute/async`,
      {
        args,
        script: `
          var suppliedArguments = Array.prototype.slice.call(arguments);
          var done = suppliedArguments.pop();
          var testArguments = suppliedArguments;
          var started = performance.now();
          requestAnimationFrame(function () {
            requestAnimationFrame(function () {
              try {
                done({
                  duration: performance.now() - started,
                  value: (function () { ${script} }).apply(null, testArguments)
                });
              } catch (error) {
                done({error: String(error && error.stack || error)});
              }
            });
          });`
      });
}

async function setViewport(driver, viewport) {
  await webdriverRequest(
      driver.baseUrl,
      "POST",
      `/session/${driver.sessionId}/window/rect`,
      {height: viewport.height, width: viewport.width, x: 0, y: 0});
}

async function setMode(driver, mode, expandedIndex = 0) {
  await execute(driver, `
    var zone = document.getElementById("octane-timer-zone");
    var cards = Array.prototype.slice.call(zone.querySelectorAll(".octane-chart-card"));
    var mode = arguments[0];
    var expandedIndex = arguments[1];
    zone.classList.toggle("octane-zone-focused", mode === "focused");
    cards.forEach(function (card, index) {
      card.classList.toggle(
          "octane-expanded", mode === "expanded" && index === expandedIndex);
    });
    return true;`, [mode, expandedIndex]);
}

async function graphMetrics(driver) {
  const result = await executeAfterPaint(driver, `
    return Array.prototype.map.call(
        document.querySelectorAll("#octane-timer-zone .octane-chart-card"),
        function (card) {
          var body = card.querySelector(".octane-flip-face-body");
          var graph = card.querySelector(".octane-timer-donut");
          var cardRect = card.getBoundingClientRect();
          var bodyRect = body.getBoundingClientRect();
          var graphRect = graph.getBoundingClientRect();
          var style = getComputedStyle(graph);
          return {
            bodyHeight: bodyRect.height,
            bodyWidth: bodyRect.width,
            cardHeight: cardRect.height,
            cardWidth: cardRect.width,
            className: card.className,
            display: style.display,
            graphHeight: graphRect.height,
            graphWidth: graphRect.width,
            maxHeight: style.maxHeight,
            maxWidth: style.maxWidth,
            opacity: Number(style.opacity),
            resolvedHeight: style.height,
            resolvedWidth: style.width,
            withinBody:
                graphRect.left >= bodyRect.left - 1
                && graphRect.right <= bodyRect.right + 1
                && graphRect.top >= bodyRect.top - 1
                && graphRect.bottom <= bodyRect.bottom + 1,
            visibility: style.visibility
          };
        });`);
  if (result.error) {
    throw new Error(result.error);
  }
  assert.ok(result.duration < 500, `Graph layout took ${result.duration.toFixed(1)}ms`);
  return result.value;
}

async function normalZoneMetrics(driver) {
  const result = await executeAfterPaint(driver, `
    function metricsFor(zoneId) {
      var zone = document.getElementById(zoneId);
      var zoneRect = zone.getBoundingClientRect();
      return {
        cardHeights: Array.prototype.map.call(
            zone.querySelectorAll(":scope > .octane-chart-card"),
            function (card) {
              return card.getBoundingClientRect().height;
            }),
        height: zoneRect.height
      };
    }
    return {
      management: metricsFor("octane-test-management-zone"),
      timer: metricsFor("octane-timer-zone")
    };`);
  if (result.error) {
    throw new Error(result.error);
  }
  assert.ok(result.duration < 500, `Zone layout took ${result.duration.toFixed(1)}ms`);
  return result.value;
}

async function activityRingMetrics(driver, constrainedWidth, constrainedHeight, expanded) {
  const result = await executeAfterPaint(driver, `
    var card = document.getElementById("octane-activity-layout-fixture");
    card.style.width = arguments[0] > 0 ? arguments[0] + "px" : "100%";
    card.style.maxWidth = arguments[0] > 0 ? arguments[0] + "px" : "none";
    card.style.minWidth = arguments[0] > 0 ? "0" : "";
    card.style.height = arguments[1] > 0 ? arguments[1] + "px" : "280px";
    card.classList.toggle("octane-expanded", arguments[2] === true);
    var rings = card.querySelector(".octane-activity-rings");
    fitActivityRingLegend(rings);
    var graphic = card.querySelector(".octane-activity-rings-svg");
    var subtitle = card.querySelector(".octane-activity-subtitle");
    var inlineLegend = card.querySelector(".octane-activity-inline-legend");
    var sideLegend = card.querySelector(".octane-activity-side-legend");
    var ringsRect = rings.getBoundingClientRect();
    var graphicRect = graphic.getBoundingClientRect();
    var sideLegendRect = sideLegend.getBoundingClientRect();
    var subtitleStyle = getComputedStyle(subtitle);
    var legendDisplay = getComputedStyle(sideLegend).display;
    var legendOverlap = legendDisplay !== "none"
        && graphicRect.left < sideLegendRect.right
        && graphicRect.right > sideLegendRect.left
        && graphicRect.top < sideLegendRect.bottom
        && graphicRect.bottom > sideLegendRect.top;
    return {
      centerDelta: Math.abs(
          (graphicRect.left + (graphicRect.width / 2))
          - (ringsRect.left + (ringsRect.width / 2))),
      inlineClientWidth: inlineLegend.clientWidth,
      inlineScrollWidth: inlineLegend.scrollWidth,
      legendDisplay: legendDisplay,
      legendInside:
          sideLegendRect.right <= ringsRect.right + 1
          && sideLegendRect.left >= ringsRect.left - 1,
      legendOverlap: legendOverlap,
      ringsWidth: ringsRect.width,
      subtitleOverflow: subtitleStyle.overflow,
      subtitleTextOverflow: subtitleStyle.textOverflow,
      subtitleWhiteSpace: subtitleStyle.whiteSpace
    };`, [constrainedWidth || 0, constrainedHeight || 0, expanded === true]);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function activityRingPollingUpdate(driver) {
  const result = await executeAfterPaint(driver, `
    updateActivityRings({
      executionProgress: 63.5,
      passRateProgress: 81.25,
      testMetrics: {automationPercentage: 44.75}
    });
    var card = document.getElementById("octane-activity-layout-fixture");
    var inlineLegend = card.querySelector("[data-activity-inline-legend]");
    var component = card.querySelector("[data-activity-rings]");
    function valuesWithin(root) {
      return ["execution", "pass", "automation"].map(function (key) {
        return root.querySelector('[data-activity-rate="' + key + '"]').textContent;
      });
    }
    return {
      graphicAriaLabel:
          card.querySelector(".octane-activity-rings-svg").getAttribute("aria-label"),
      inlineAriaLabel: inlineLegend.getAttribute("aria-label"),
      inlineValues: valuesWithin(inlineLegend),
      ringValues: ["execution", "pass", "automation"].map(function (key) {
        return component.querySelector('[data-activity-ring="' + key + '"]')
            .getAttribute("stroke-dasharray");
      }),
      sideValues: valuesWithin(card.querySelector(".octane-activity-side-legend"))
    };`);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function testerDetailsTableMetrics(driver, constrainedWidth) {
  const result = await executeAfterPaint(driver, `
    var zone = document.getElementById("octane-tester-layout-fixture");
    zone.style.width = arguments[0] > 0 ? arguments[0] + "px" : "100%";
    zone.style.maxWidth = arguments[0] > 0 ? arguments[0] + "px" : "none";
    return Array.prototype.map.call(
        zone.querySelectorAll(".octane-tester-table"), function (table) {
          var tracker = table.closest(".octane-tester-tracker");
          var title = tracker.querySelector(".octane-tester-tracker-title");
          var header = table.querySelector(".octane-tester-rate-header");
          var value = table.querySelector("tbody .octane-tester-rate");
          var headerRect = header.getBoundingClientRect();
          var titleRect = title.getBoundingClientRect();
          var valueRect = value.getBoundingClientRect();
          var style = getComputedStyle(header);
          var titleStyle = getComputedStyle(title);
          var textRange = document.createRange();
          textRange.selectNodeContents(header);
          var textRect = textRange.getBoundingClientRect();
          var titleRange = document.createRange();
          titleRange.selectNodeContents(title);
          var titleTextRect = titleRange.getBoundingClientRect();
          return {
            headerAriaLabel: header.getAttribute("aria-label"),
            headerContentInside:
                textRect.left >= headerRect.left - 1
                && textRect.right <= headerRect.right + 1
                && textRect.top >= headerRect.top - 1
                && textRect.bottom <= headerRect.bottom + 1,
            headerFontSize: parseFloat(style.fontSize),
            headerPrefixDisplay: getComputedStyle(
                header.querySelector(".octane-tester-responsive-prefix")).display,
            headerRightDelta: Math.abs(headerRect.right - valueRect.right),
            headerSingleLine: textRect.height <= parseFloat(style.lineHeight) * 1.2,
            paddingRight: parseFloat(style.paddingRight),
            tableLayout: getComputedStyle(table).tableLayout,
            tableOverflow: table.scrollWidth - table.clientWidth,
            textAlign: style.textAlign,
            textWrap: style.textWrap,
            titleAriaLabel: title.getAttribute("aria-label"),
            titleComparisonCompactDisplay: getComputedStyle(
                title.querySelector(".octane-tester-comparison-compact")).display,
            titleComparisonFullDisplay: getComputedStyle(
                title.querySelector(".octane-tester-comparison-full")).display,
            titleContentInside:
                titleTextRect.left >= titleRect.left - 1
                && titleTextRect.right <= titleRect.right + 1
                && titleTextRect.top >= titleRect.top - 1
                && titleTextRect.bottom <= titleRect.bottom + 1,
            titleFontSize: parseFloat(titleStyle.fontSize),
            titlePrefixDisplay: getComputedStyle(
                title.querySelector(".octane-tester-responsive-prefix")).display,
            titleSingleLine:
                titleTextRect.height <= parseFloat(titleStyle.lineHeight) * 1.2,
            titleTextWrap: titleStyle.textWrap,
            titleVisibleText: title.innerText.trim(),
            titleWhiteSpace: titleStyle.whiteSpace,
            whiteSpace: style.whiteSpace,
          };
        });`, [constrainedWidth || 0]);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function timeoutDepletionEndpoint(driver) {
  const result = await execute(driver, `
    var progress = document.querySelector(
        '#octane-timer-zone [data-card-key="timer-timeout"] [data-timer-progress]');
    var point = progress.getPointAtLength(progress.getTotalLength() * 0.75);
    var transformed = new DOMPoint(point.x, point.y).matrixTransform(progress.getCTM());
    var center = new DOMPoint(120, 120).matrixTransform(progress.getCTM());
    return {x: transformed.x, y: transformed.y, centerX: center.x, centerY: center.y};`);
  return result;
}

async function constrainedManagementBarMetrics(driver) {
  const result = await executeAfterPaint(driver, `
    var state = document.querySelector(".octane-management-state-bars");
    var failure = document.querySelector(".octane-management-failure-chart");
    state.style.width = "72px";
    state.style.maxWidth = "72px";
    state.style.justifySelf = "start";
    failure.style.width = "72px";
    failure.style.maxWidth = "72px";
    failure.style.justifySelf = "start";
    var label = failure.querySelector(".octane-management-failure-label");
    var labelStyle = getComputedStyle(label);
    var failureRect = failure.getBoundingClientRect();
    var failureGroup = failure.querySelector(".octane-management-failure-group");
    var failureGroupRect = failureGroup.getBoundingClientRect();
    var failureGroupStyle = getComputedStyle(failureGroup);
    var failureGridRows = failureGroupStyle.gridTemplateRows.trim().split(" ");
    var failureAxisRow = parseFloat(failureGridRows[failureGridRows.length - 1]);
    var failureAxisY = failureRect.bottom - failureAxisRow;
    var failureBarsRect = failureGroup
        .querySelector(".octane-management-failure-bars")
        .getBoundingClientRect();
    var failureTopLineRect = failure
        .querySelector('[data-management-grid-value="10"]')
        .getBoundingClientRect();
    var failureLabelRect = label.getBoundingClientRect();
    return {
      failureAxisY: failureAxisY,
      failureAxisRow: failureAxisRow,
      failureBarBottoms: Array.prototype.map.call(
          failure.querySelectorAll(".octane-management-failure-bar"),
          function (bar) { return bar.getBoundingClientRect().bottom; }),
      failureBarWidths: Array.prototype.map.call(
          failure.querySelectorAll(".octane-management-failure-bar"),
          function (bar) { return bar.getBoundingClientRect().width; }),
      failureClientWidth: failure.clientWidth,
      failureGridRows: failureGroupStyle.gridTemplateRows,
      failureGroupBottom: failureGroupRect.bottom,
      failureGroupHeight: failureGroupRect.height,
      failureLabelBottom: failureLabelRect.bottom,
      failureLabelHeight: failureLabelRect.height,
      failurePlotBottom: failureBarsRect.bottom,
      failureScrollWidth: failure.scrollWidth,
      failureTickAlignment: Array.prototype.map.call(
          document.querySelectorAll(
              ".octane-management-failure-axis-layout "
              + "[data-management-axis-value]"),
          function (tick) {
            var value = tick.getAttribute("data-management-axis-value");
            var line = failure.querySelector(
                '[data-management-grid-value="' + value + '"]');
            var tickRect = tick.getBoundingClientRect();
            var targetY = value === "0"
                ? failureAxisY
                : line.getBoundingClientRect().top + (line.getBoundingClientRect().height / 2);
            return {
              delta: Math.abs((tickRect.top + (tickRect.height / 2)) - targetY),
              value: Number(value)
            };
          }),
      failureTopInset: failureTopLineRect.top - failureRect.top,
      labelClientWidth: label.clientWidth,
      labelOverflow: labelStyle.overflow,
      labelScrollWidth: label.scrollWidth,
      labelTextOverflow: labelStyle.textOverflow,
      labelWhiteSpace: labelStyle.whiteSpace,
      stateBarWidths: Array.prototype.map.call(
          state.querySelectorAll(".octane-management-state-column"),
          function (bar) { return bar.getBoundingClientRect().width; }),
      stateClientWidth: state.clientWidth,
      stateScrollWidth: state.scrollWidth
    };`);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function managementAxisSpacingMetrics(driver) {
  const result = await executeAfterPaint(driver, `
    var rootFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize);
    var legacyPlotOffset = (1.15 + 1.9 + (0.04 * 2)) * rootFontSize;
    return Array.prototype.map.call(
        document.querySelectorAll(
            "#octane-test-management-zone .octane-management-plot-layout"),
        function (layout) {
          var title = layout.querySelector(".octane-management-y-axis-title");
          var labels = layout.querySelector(".octane-management-y-labels");
          var plot = layout.querySelector(
              ".octane-management-svg-wrap, .octane-management-state-bars, "
              + ".octane-management-failure-chart");
          var layoutRect = layout.getBoundingClientRect();
          var plotRect = plot.getBoundingClientRect();
          var style = getComputedStyle(layout);
          return {
            columnGap: parseFloat(style.columnGap),
            gridColumns: style.gridTemplateColumns,
            labelWidth: labels.getBoundingClientRect().width,
            plotGain: legacyPlotOffset - (plotRect.left - layoutRect.left),
            plotWidth: plotRect.width,
            titleWidth: title.getBoundingClientRect().width
          };
        });`);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function compactManagementMetricLayout(driver) {
  const result = await executeAfterPaint(driver, `
    return Array.prototype.map.call(
        document.querySelectorAll(".octane-management-metric-tile"),
        function (tile) {
          var tileRect = tile.getBoundingClientRect();
          var tileStyle = getComputedStyle(tile);
          var descendants = tile.querySelectorAll(
              ".octane-management-metric-title, .octane-management-metric-value, "
              + ".octane-management-metric-detail, .octane-management-metric-items");
          var withinTile = Array.prototype.every.call(descendants, function (element) {
            var rect = element.getBoundingClientRect();
            return rect.left >= tileRect.left - 1
                && rect.right <= tileRect.right + 1
                && rect.top >= tileRect.top - 1
                && rect.bottom <= tileRect.bottom + 1;
          });
          var title = tile.querySelector(".octane-management-metric-title");
          var value = tile.querySelector(".octane-management-metric-value");
          var rows = Array.prototype.map.call(
              tile.querySelectorAll(".octane-management-metric-items li"),
              function (row) {
                var label = row.querySelector("span");
                var itemValue = row.querySelector("strong");
                var rowStyle = getComputedStyle(row);
                var labelStyle = getComputedStyle(label);
                var labelRect = label.getBoundingClientRect();
                var valueRect = itemValue.getBoundingClientRect();
                return {
                  display: rowStyle.display,
                  flexWrap: rowStyle.flexWrap,
                  labelOverflow: labelStyle.overflow,
                  labelTextOverflow: labelStyle.textOverflow,
                  labelWhiteSpace: labelStyle.whiteSpace,
                  labelIsTruncated: label.scrollWidth > label.clientWidth,
                  valueRightGutter: tileRect.right - valueRect.right,
                  valueWithinTile: valueRect.right <= tileRect.right + 0.5,
                  valueInline: Math.abs(labelRect.top - valueRect.top) <= 1
                };
              });
          return {
            height: tileRect.height,
            key: tile.getAttribute("data-management-metric-key") || "",
            overflow: tileStyle.overflow,
            paddingBottom: parseFloat(tileStyle.paddingBottom),
            paddingLeft: parseFloat(tileStyle.paddingLeft),
            paddingRight: parseFloat(tileStyle.paddingRight),
            paddingTop: parseFloat(tileStyle.paddingTop),
            rows: rows,
            titleFontWeight: getComputedStyle(title).fontWeight,
            titleTextAlign: getComputedStyle(title).textAlign,
            valueFontSize: parseFloat(getComputedStyle(value).fontSize),
            valueFontWeight: getComputedStyle(value).fontWeight,
            valueTextAlign: getComputedStyle(value).textAlign,
            width: tileRect.width,
            withinTile: withinTile
          };
        });`);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function testMetricLayout(driver, expanded, focused) {
  const result = await executeAfterPaint(driver, `
    var card = document.querySelector('[data-card-key="timer-timeout"]');
    var zone = document.getElementById("octane-timer-zone");
    card.classList.toggle("octane-expanded", Boolean(arguments[0]));
    zone.classList.toggle("octane-zone-focused", Boolean(arguments[1]));
    card.setAttribute("data-active-view", "metrics");
    initializeTestMetricSegments(card);
    fitTestMetricSegmentLabels(card);
    var cardRect = card.getBoundingClientRect();
    var metricsGridRect = card.querySelector(".octane-test-metrics-grid")
        .getBoundingClientRect();
    var metricTileRects = Array.prototype.map.call(
        card.querySelectorAll(".octane-test-metric-card"), function (tile) {
          var rect = tile.getBoundingClientRect();
          return {bottom: rect.bottom, height: rect.height, top: rect.top, width: rect.width};
        });
    var title = card.querySelector(".octane-flip-face-metrics .octane-card-title");
    var subtitle = card.querySelector(".octane-flip-face-metrics .octane-muted");
    var automationTrack = card.querySelector(".octane-test-metric-automation-track");
    var gauge = card.querySelector(".octane-test-metric-gauge-svg");
    var gaugePath = card.querySelector(".octane-test-metric-gauge-fill");
    var gaugeValue = card.querySelector(".octane-test-metric-gauge-value");
    var gaugeVisual = card.querySelector(".octane-test-metric-gauge");
    var progress = card.querySelector(".octane-test-metric-progress-wrap");
    var executionValue = card.querySelector(
        '.octane-test-metric-execution .octane-test-metric-value');
    var defectTrack = card.querySelector(".octane-test-metric-defect-track");
    var colors = card.querySelectorAll(".octane-test-metric-defect-color");
    function ratio(element) {
      var rect = element.getBoundingClientRect();
      return rect.height > 0 ? rect.width / rect.height : 0;
    }
    var gaugeBox = gauge.getBoundingClientRect();
    var gaugeVisualBox = gaugeVisual.getBoundingClientRect();
    var gaugeValueBox = gaugeValue.getBoundingClientRect();
    var gaugeValueSvgBox = gaugeValue.getBBox();
    var gaugeStrokeWidth = parseFloat(getComputedStyle(gaugePath).strokeWidth) || 0;
    var gaugePathTouchesValue = false;
    var gaugeCollisionPoint = null;
    var gaugePathLength = gaugePath.getTotalLength();
    for (var pointIndex = 0; pointIndex <= 100; pointIndex += 1) {
      var point = gaugePath.getPointAtLength(gaugePathLength * pointIndex / 100);
      if (point.x >= gaugeValueSvgBox.x - gaugeStrokeWidth / 2
          && point.x <= gaugeValueSvgBox.x + gaugeValueSvgBox.width + gaugeStrokeWidth / 2
          && point.y >= gaugeValueSvgBox.y - gaugeStrokeWidth / 2
          && point.y <= gaugeValueSvgBox.y + gaugeValueSvgBox.height + gaugeStrokeWidth / 2) {
        gaugePathTouchesValue = true;
        gaugeCollisionPoint = {x: point.x, y: point.y};
        break;
      }
    }
    var progressRect = progress.getBoundingClientRect();
    var defectTrackRect = defectTrack.getBoundingClientRect();
    var executionValueRect = executionValue.getBoundingClientRect();
    var executionValueStyle = getComputedStyle(executionValue);
    var containedElements = card.querySelectorAll(
              ".octane-test-metric-heading, .octane-test-metric-value, "
              + ".octane-test-metric-detail, .octane-test-metric-trend, "
              + ".octane-test-metric-automation-segments, .octane-test-metric-gauge-svg, "
              + ".octane-test-metric-progress, .octane-test-metric-defect-segments");
    var escapedContent = Array.prototype.filter.call(containedElements, function (element) {
      var rect = element.getBoundingClientRect();
      return rect.left < cardRect.left - 1 || rect.right > cardRect.right + 1
          || rect.top < cardRect.top - 1 || rect.bottom > cardRect.bottom + 1;
    }).map(function (element) {
      var rect = element.getBoundingClientRect();
      return {
        bottom: rect.bottom,
        className: element.getAttribute("class") || "",
        left: rect.left,
        right: rect.right,
        top: rect.top
      };
    });
    var metrics = {
      allContentContained: escapedContent.length === 0,
      cardHeight: cardRect.height,
      escapedContent: escapedContent,
      metricTileRects: metricTileRects,
      metricsGridHeight: metricsGridRect.height,
      gaugeRatio: ratio(gauge),
      gaugePathTouchesValue: gaugePathTouchesValue,
      gaugeSpaceUtilization:
          gaugeBox.width / Math.max(1, Math.min(
              gaugeVisualBox.width * 0.84,
              gaugeVisualBox.height * 1.7,
              288)),
      gaugeWidth: gaugeBox.width,
      gaugeVisualHeight: gaugeVisualBox.height,
      gaugeVisualWidth: gaugeVisualBox.width,
      gaugeCollisionPoint: gaugeCollisionPoint,
      gaugeValueSvgBox: {
        height: gaugeValueSvgBox.height,
        width: gaugeValueSvgBox.width,
        x: gaugeValueSvgBox.x,
        y: gaugeValueSvgBox.y
      },
      gaugeValueWithinSvg:
          gaugeValueBox.left >= gaugeBox.left - 1
          && gaugeValueBox.right <= gaugeBox.right + 1
          && gaugeValueBox.top >= gaugeBox.top - 1
          && gaugeValueBox.bottom <= gaugeBox.bottom + 1,
      lowerBars: {
        defectHeight: defectTrackRect.height,
        defectTop: defectTrackRect.top,
        defectWidth: defectTrackRect.width,
        executionHeight: progressRect.height,
        executionTop: progressRect.top,
        executionWidth: progressRect.width
      },
      executionValueClearance: progressRect.top - executionValueRect.bottom,
      executionValueContentFits:
          executionValue.scrollHeight <= executionValue.clientHeight + 1
          && executionValue.scrollWidth <= executionValue.clientWidth + 1,
      executionValueFontSize: parseFloat(executionValueStyle.fontSize),
      executionValueLineHeight: parseFloat(executionValueStyle.lineHeight),
      automationLabels: Array.prototype.map.call(
          card.querySelectorAll(".octane-test-metric-segment-label"),
          function (label) { return label.textContent.trim(); }),
      defectLabels: Array.prototype.map.call(
          card.querySelectorAll(".octane-test-metric-defect-label"),
          function (label) { return label.textContent.trim(); }),
      automationRatio: ratio(automationTrack),
      progressRatio: ratio(progress),
      segmentRadii: Array.prototype.map.call(colors, function (color) {
        var style = getComputedStyle(color);
        return {
          bottomLeft: parseFloat(style.borderBottomLeftRadius),
          bottomRight: parseFloat(style.borderBottomRightRadius),
          topLeft: parseFloat(style.borderTopLeftRadius),
          topRight: parseFloat(style.borderTopRightRadius)
        };
      }),
      subtitleFontSize: parseFloat(getComputedStyle(subtitle).fontSize),
      titleFontSize: parseFloat(getComputedStyle(title).fontSize),
      trends: Array.prototype.map.call(
          card.querySelectorAll(".octane-test-metric-trend"), function (trend) {
            var style = getComputedStyle(trend);
            var rect = trend.getBoundingClientRect();
            var parentRect = trend.parentElement.getBoundingClientRect();
            return {
              alignItems: style.alignItems,
              display: style.display,
              justifyContent: style.justifyContent,
              symmetricPadding:
                  Math.abs(parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)) < 0.1
                  && Math.abs(parseFloat(style.paddingTop) - parseFloat(style.paddingBottom)) < 0.1,
              whiteSpace: style.whiteSpace,
              withinCard: rect.left >= parentRect.left - 1 && rect.right <= parentRect.right + 1
            };
          })
    };
    card.setAttribute("data-active-view", "timer");
    card.classList.remove("octane-expanded");
    zone.classList.remove("octane-zone-focused");
    return metrics;`, [expanded, focused]);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function managementDefectListLayout(driver) {
  const result = await executeAfterPaint(driver, `
    var card = document.querySelector(
        '[data-card-key="test-management-2"]');
    card.classList.add("octane-expanded");
    var rows = card.querySelectorAll(".octane-management-defect-row");
    var metrics = Array.prototype.map.call(rows, function (row) {
      var identifier = row.querySelector(".octane-management-defect-id");
      var description = row.querySelector(".octane-management-defect-description");
      var pills = row.querySelectorAll(".octane-management-defect-pill");
      var rowRect = row.getBoundingClientRect();
      var idRect = identifier.getBoundingClientRect();
      var descriptionRect = description.getBoundingClientRect();
      var pillRects = Array.prototype.map.call(
          pills, function (pill) { return pill.getBoundingClientRect(); });
      return {
        columns: getComputedStyle(row).gridTemplateColumns.trim().split(" ").length,
        descriptionAfterId: descriptionRect.left >= idRect.right - 1,
        descriptionBeforePills:
            descriptionRect.right <= pillRects[0].left + 1,
        pillWidths: pillRects.map(function (rect) { return rect.width; }),
        rowContainsContent:
            idRect.left >= rowRect.left - 1
            && descriptionRect.left >= rowRect.left - 1
            && pillRects[pillRects.length - 1].right <= rowRect.right + 1,
        rowOverflow: row.scrollWidth - row.clientWidth
      };
    });
    card.classList.remove("octane-expanded");
    return metrics;`);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function managementDefectHeaderLayout(driver) {
  const result = await executeAfterPaint(driver, `
    var card = document.querySelector(
        '[data-card-key="test-management-2"]');
    card.classList.add("octane-expanded");
    var header = card.querySelector(".octane-management-defect-header");
    var row = card.querySelector(".octane-management-defect-row");
    var headerCells = header.querySelectorAll(
        ".octane-management-defect-header-cell");
    var rowCells = row.children;
    var aligned = Array.prototype.every.call(
        headerCells,
        function (cell, index) {
          var headerRect = cell.getBoundingClientRect();
          var rowRect = rowCells[index].getBoundingClientRect();
          return Math.abs(headerRect.left - rowRect.left) <= 1
              && Math.abs(headerRect.width - rowRect.width) <= 1;
        });
    var activeIndicator = headerCells[0].querySelector(
        ".octane-management-defect-sort-indicator");
    var inactiveIndicator = headerCells[1].querySelector(
        ".octane-management-defect-sort-indicator");
    var headerRects = Array.prototype.map.call(headerCells, function (cell) {
      var rect = cell.getBoundingClientRect();
      return {left: rect.left, width: rect.width};
    });
    var rowRects = Array.prototype.map.call(rowCells, function (cell) {
      var rect = cell.getBoundingClientRect();
      return {left: rect.left, width: rect.width};
    });
    var metrics = {
      activeArrowMoreVisible:
          Number(getComputedStyle(activeIndicator).opacity)
              > Number(getComputedStyle(inactiveIndicator).opacity),
      activeSort: headerCells[0].getAttribute("aria-sort"),
      aligned: aligned,
      columns: getComputedStyle(header).gridTemplateColumns.trim().split(" ").length,
      headerRects: headerRects,
      position: getComputedStyle(header).position,
      rowRects: rowRects
    };
    card.classList.remove("octane-expanded");
    return metrics;`);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

async function managementCategoryNavigationLayout(driver) {
  const result = await executeAfterPaint(driver, `
    var card = document.querySelector('[data-card-key="test-management-2"]');
    card.classList.add("octane-expanded");
    var header = card.querySelector(".octane-card-header");
    var navigation = card.querySelector("[data-management-failure-tab-nav]");
    var switcher = card.querySelector("[data-management-failure-switcher]");
    var arrows = navigation.querySelectorAll("[data-management-category-scroll]");
    var buttons = switcher.querySelectorAll(".octane-management-category-toggle");
    var headerRect = header.getBoundingClientRect();
    var navigationRect = navigation.getBoundingClientRect();
    var buttonRects = Array.prototype.map.call(
        buttons, function (button) { return button.getBoundingClientRect(); });
    var firstTop = buttonRects[0].top;
    switcher.style.scrollBehavior = "auto";
    switcher.scrollLeft = 0;
    var before = switcher.scrollLeft;
    switcher.scrollLeft = switcher.scrollWidth;
    var after = switcher.scrollLeft;
    var metrics = {
      arrowSizes: Array.prototype.map.call(arrows, function (arrow) {
        var rect = arrow.getBoundingClientRect();
        return {height: rect.height, width: rect.width};
      }),
      headerContainsNavigation:
          navigationRect.left >= headerRect.left - 1
          && navigationRect.right <= headerRect.right + 1,
      navigationHeight: navigationRect.height,
      oneLine: buttonRects.every(function (rect) {
        return Math.abs(rect.top - firstTop) <= 1;
      }),
      overflow: switcher.scrollWidth > switcher.clientWidth,
      scrolled: after > before,
      switcherHeight: switcher.getBoundingClientRect().height
    };
    card.classList.remove("octane-expanded");
    return metrics;`);
  if (result.error) {
    throw new Error(result.error);
  }
  return result.value;
}

function assertVisibleGraphs(metrics, label, minimumSize = 24) {
  assert.equal(metrics.length, 4, `${label}: all four timer graphs must render`);
  for (const [index, metric] of metrics.entries()) {
    assert.equal(metric.display, "block", `${label}, graph ${index}: display`);
    assert.equal(metric.visibility, "visible", `${label}, graph ${index}: visibility`);
    assert.ok(metric.opacity > 0, `${label}, graph ${index}: opacity`);
    assert.ok(
        metric.graphWidth >= minimumSize && metric.graphHeight >= minimumSize,
        `${label}, graph ${index}: ${metric.graphWidth}x${metric.graphHeight}`);
    assert.ok(
        Math.abs(metric.graphWidth - metric.graphHeight) <= 1.5,
        `${label}, graph ${index}: graph must remain square ${JSON.stringify(metric)}`);
    assert.ok(
        metric.withinBody,
        `${label}, graph ${index}: graph must fit its body ${JSON.stringify(metric)}`);
  }
}

const browserAvailable =
    (existsSync(snapGeckodriver) || executableAvailable("geckodriver"))
    && executableAvailable("firefox");
const chromiumAvailable = executableAvailable("google-chrome");

test(
    "timer graphs remain visible in Chromium normal, focused, and expanded modes",
    {skip: !chromiumAvailable, timeout: 60000},
    () => {
      const directory = mkdtempSync(join(tmpdir(), "octane-chromium-layout-"));
      const fixturePath = join(directory, "fixture.html");
      writeFileSync(fixturePath, chromiumFixtureHtml(), "utf8");
      try {
        for (const viewport of [viewports[0], viewports[3]]) {
          const result = spawnSync(
              "google-chrome",
              [
                "--headless=new",
                "--no-sandbox",
                "--disable-gpu",
                `--user-data-dir=${join(directory, `profile-${viewport.name}`)}`,
                `--window-size=${viewport.width},${viewport.height}`,
                "--virtual-time-budget=2000",
                "--dump-dom",
                pathToFileURL(fixturePath).href
              ],
              {encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30000});
          assert.equal(result.status, 0, result.error || result.stderr);
          const modeChecks = result.stdout.match(/data-chromium-checks="([^"]+)"/)?.[1];
          assert.match(
              result.stdout,
              /data-chromium-layout="pass"/,
              `${viewport.name}: Chromium layout checks were ${modeChecks || "missing"}`);
        }
      } finally {
        rmSync(directory, {force: true, recursive: true});
      }
    });

test(
    "activity ring subtitle legend follows each polling payload",
    {skip: !browserAvailable, timeout: 60000},
    async () => {
      await withFirefox(async driver => {
        const fixture = Buffer.from(fixtureHtml()).toString("base64");
        await webdriverRequest(
            driver.baseUrl,
            "POST",
            `/session/${driver.sessionId}/url`,
            {url: `data:text/html;base64,${fixture}`});

        const update = await activityRingPollingUpdate(driver);
        assert.deepEqual(update.inlineValues, ["63.50%", "81.25%", "44.75%"]);
        assert.deepEqual(update.sideValues, update.inlineValues);
        assert.deepEqual(update.ringValues, ["63.5 100", "81.25 100", "44.75 100"]);
        assert.equal(
            update.inlineAriaLabel,
            "execution rate 63.50%, pass rate 81.25%, automation usage 44.75%");
        assert.equal(update.graphicAriaLabel, update.inlineAriaLabel);
      });
    });

test(
    "all timer graphs render and scale in normal, focused, and expanded modes",
    {skip: !browserAvailable, timeout: 120000},
    async () => {
      await withFirefox(async driver => {
        const fixture = Buffer.from(fixtureHtml()).toString("base64");
        await webdriverRequest(
            driver.baseUrl,
            "POST",
            `/session/${driver.sessionId}/url`,
            {url: `data:text/html;base64,${fixture}`});

        const desktopSizes = {};
        const testMetricSizes = {};
        for (const viewport of viewports) {
          await setViewport(driver, viewport);

          await setMode(driver, "normal");
          const normal = await graphMetrics(driver);
          assertVisibleGraphs(normal, `${viewport.name} normal`);
          const activity = await activityRingMetrics(driver, 0);
          assert.ok(
              activity.centerDelta <= 1,
              `${viewport.name}: activity rings shifted away from the absolute center: `
                  + JSON.stringify(activity));
          assert.equal(activity.subtitleWhiteSpace, "nowrap");
          assert.equal(activity.subtitleOverflow, "hidden");
          assert.equal(activity.subtitleTextOverflow, "ellipsis");
          if (activity.legendDisplay !== "none") {
            assert.ok(
                activity.legendInside,
                `${viewport.name}: side legend escaped the activity container`);
            assert.equal(
                activity.legendOverlap,
                false,
                `${viewport.name}: side legend overlaps the activity rings`);
          }
          const testerTables = await testerDetailsTableMetrics(
              driver, viewport.name === "compact" ? 170 : 0);
          assert.equal(testerTables.length, 2);
          assert.ok(
              testerTables.every(metric => metric.tableLayout === "auto"),
              `${viewport.name}: Tester Details retained a rigid table layout: `
                  + JSON.stringify(testerTables));
          assert.ok(
              testerTables.every(metric => metric.textAlign === "right"),
              `${viewport.name}: metric headers are not right-aligned: `
                  + JSON.stringify(testerTables));
          assert.ok(
              testerTables.every(metric =>
                metric.whiteSpace === "nowrap"
                  && metric.textWrap === "nowrap"
                  && metric.titleWhiteSpace === "nowrap"
                  && metric.titleTextWrap === "nowrap"),
              `${viewport.name}: Tester Details headers are not locked to one line: `
                  + JSON.stringify(testerTables));
          assert.ok(
              testerTables.every(metric => metric.paddingRight >= 8),
              `${viewport.name}: metric headers lost their right breathing room: `
                  + JSON.stringify(testerTables));
          assert.ok(
              testerTables.every(metric =>
                metric.tableOverflow <= 1
                  && metric.headerContentInside
                  && metric.headerRightDelta <= 1
                  && metric.headerSingleLine
                  && metric.titleContentInside
                  && metric.titleSingleLine),
              `${viewport.name}: Tester Details headers clip or miss the value-column edge: `
                  + JSON.stringify(testerTables));
          assert.ok(
              testerTables.every(metric =>
                metric.headerFontSize >= 11.1
                  && metric.headerFontSize <= 16.1
                  && metric.titleFontSize >= 11.1
                  && metric.titleFontSize <= 16.1),
              `${viewport.name}: Tester Details font scaling escaped its clamp: `
                  + JSON.stringify(testerTables));
          if (viewport.name === "compact") {
            assert.ok(
                testerTables.every(metric =>
                  metric.headerPrefixDisplay === "none"
                    && metric.titlePrefixDisplay === "none"
                    && metric.titleComparisonFullDisplay === "none"
                    && metric.titleComparisonCompactDisplay === "inline"
                    && metric.titleVisibleText.startsWith("< ")
                    && metric.titleAriaLabel.startsWith("Testers with LESS THAN ")),
                `compact: responsive header prefixes remained visible: `
                    + JSON.stringify(testerTables));
          } else {
            assert.ok(
                testerTables.every(metric =>
                  metric.headerPrefixDisplay === "inline"
                    && metric.titlePrefixDisplay === "inline"
                    && metric.titleComparisonFullDisplay === "inline"
                    && metric.titleComparisonCompactDisplay === "none"
                    && metric.titleVisibleText.startsWith("Testers with LESS THAN ")),
                `${viewport.name}: full header prefixes disappeared with sufficient room: `
                    + JSON.stringify(testerTables));
          }
          if (viewport.height >= 900) {
            const tallSlimActivity = await activityRingMetrics(driver, 600, 760, true);
            assert.equal(
                tallSlimActivity.legendDisplay,
                "none",
                `${viewport.name}: tall slim activity card did not suppress an overlapping legend: `
                    + JSON.stringify(tallSlimActivity));
          }
          if (viewport.name === "compact") {
            const constrainedActivity = await activityRingMetrics(driver, 190);
            assert.ok(
                constrainedActivity.inlineScrollWidth
                    > constrainedActivity.inlineClientWidth,
                `compact: activity subtitle did not reach its ellipsis fallback: `
                    + JSON.stringify(constrainedActivity));
            const depletionEndpoint = await timeoutDepletionEndpoint(driver);
            assert.ok(
                depletionEndpoint.x < depletionEndpoint.centerX,
                `75% remaining must place the retreating endpoint at 9 o'clock: `
                    + JSON.stringify(depletionEndpoint));
            assert.ok(
                Math.abs(depletionEndpoint.y - depletionEndpoint.centerY) <= 2,
                `75% remaining endpoint must remain level with the center: `
                    + JSON.stringify(depletionEndpoint));
          }
          const zones = await normalZoneMetrics(driver);
          assert.ok(
              Math.abs(zones.timer.height - zones.management.height) <= 1,
              `${viewport.name}: timer and management zones differ: ${JSON.stringify(zones)}`);
          assert.deepEqual(
              zones.timer.cardHeights.map(Math.round),
              zones.management.cardHeights.map(Math.round),
              `${viewport.name}: timer and management card rows must match`);
          assert.ok(
              zones.management.cardHeights.every(height => Math.abs(height - 280) <= 1),
              `${viewport.name}: management cards must remain at 280px in normal mode`);
          const barMetrics = await constrainedManagementBarMetrics(driver);
          assert.ok(
              barMetrics.stateBarWidths.every(width => width >= 8 && width <= 100),
              `${viewport.name}: state bar width escaped bounds: ${JSON.stringify(barMetrics)}`);
          assert.ok(
              barMetrics.failureBarWidths.every(width => width >= 8 && width <= 100),
              `${viewport.name}: failure bar width escaped bounds: ${JSON.stringify(barMetrics)}`);
          assert.ok(
              barMetrics.failureBarBottoms.every(
                  bottom => Math.abs(bottom - barMetrics.failureAxisY) <= 1),
              `${viewport.name}: failure bars are not anchored above the x-axis: `
                  + JSON.stringify(barMetrics));
          assert.deepEqual(
              barMetrics.failureTickAlignment.map(metric => metric.value),
              [10, 5, 0],
              `${viewport.name}: failure tick order changed`);
          assert.ok(
              barMetrics.failureTickAlignment.every(metric => metric.delta <= 1),
              `${viewport.name}: failure labels do not align with their grid lines: `
                  + JSON.stringify(barMetrics.failureTickAlignment));
          assert.ok(
              barMetrics.failureTopInset >= 3 && barMetrics.failureTopInset <= 16,
              `${viewport.name}: failure ceiling clearance is not about one character: `
                  + JSON.stringify(barMetrics));
          assert.ok(
              barMetrics.stateScrollWidth > barMetrics.stateClientWidth,
              `${viewport.name}: state x-axis did not overflow cleanly`);
          assert.ok(
              barMetrics.failureScrollWidth > barMetrics.failureClientWidth,
              `${viewport.name}: failure x-axis did not overflow cleanly`);
          assert.equal(barMetrics.labelOverflow, "hidden");
          assert.equal(barMetrics.labelTextOverflow, "ellipsis");
          assert.equal(barMetrics.labelWhiteSpace, "nowrap");
          assert.ok(barMetrics.labelScrollWidth > barMetrics.labelClientWidth);
          const axisMetrics = await managementAxisSpacingMetrics(driver);
          assert.equal(axisMetrics.length, 3);
          assert.ok(
              axisMetrics.every(metric => Math.abs(metric.columnGap - 1.44) <= 0.2),
              `${viewport.name}: management axis gap differs from tester progress: `
                  + JSON.stringify(axisMetrics));
          assert.ok(
              axisMetrics.every(metric => metric.gridColumns.split(" ").length === 3),
              `${viewport.name}: management axis grid lost its three tracks: `
                  + JSON.stringify(axisMetrics));
          assert.ok(
              axisMetrics.every(metric => metric.labelWidth < 30.4),
              `${viewport.name}: tick labels retained the old 1.9rem reservation: `
                  + JSON.stringify(axisMetrics));
          assert.ok(
              axisMetrics.every(metric => metric.plotGain >= 6),
              `${viewport.name}: plot did not absorb the reclaimed axis width: `
                  + JSON.stringify(axisMetrics));
          const testMetrics = await testMetricLayout(
              driver,
              viewport.name === "wide",
              viewport.name === "short-wide");
          assert.equal(
              testMetrics.allContentContained,
              true,
              `${viewport.name}: Test Insights content escaped its card: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              Math.abs(testMetrics.automationRatio - 34) <= 1,
              `${viewport.name}: automation bar aspect ratio changed: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              Math.abs(testMetrics.gaugeRatio - 1.75) <= 0.08,
              `${viewport.name}: gauge aspect ratio changed: ${JSON.stringify(testMetrics)}`);
          assert.equal(
              testMetrics.gaugePathTouchesValue,
              false,
              `${viewport.name}: success-rate value overlaps its half-pie: `
                  + JSON.stringify(testMetrics));
          assert.equal(
              testMetrics.gaugeValueWithinSvg,
              true,
              `${viewport.name}: success-rate value is clipped by its SVG: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              testMetrics.gaugeSpaceUtilization >= 0.95,
              `${viewport.name}: success-rate half-pie does not use available space: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              testMetrics.gaugeWidth >= 80,
              `${viewport.name}: success-rate half-pie collapsed below a legible size: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              Math.abs(testMetrics.progressRatio - 34) <= 1,
              `${viewport.name}: progress aspect ratio changed: ${JSON.stringify(testMetrics)}`);
          assert.ok(
              Math.abs(
                  testMetrics.lowerBars.executionWidth
                    - testMetrics.lowerBars.defectWidth) <= 1
                && Math.abs(
                    testMetrics.lowerBars.executionHeight
                      - testMetrics.lowerBars.defectHeight) <= 1,
              `${viewport.name}: lower metric bars differ in size: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              Math.abs(
                  testMetrics.lowerBars.executionTop
                    - testMetrics.lowerBars.defectTop) <= 1,
              `${viewport.name}: lower metric bars are vertically misaligned: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              testMetrics.executionValueLineHeight
                  >= testMetrics.executionValueFontSize
                && testMetrics.executionValueContentFits
                && testMetrics.executionValueClearance >= 1,
              `${viewport.name}: execution percentage is clipped by its progress bar: `
                  + JSON.stringify(testMetrics));
          assert.ok(
              testMetrics.trends.every(trend =>
                (trend.display === "inline-flex" || trend.display === "flex")
                  && trend.alignItems === "center"
                  && trend.justifyContent === "center"
                  && trend.symmetricPadding
                  && trend.whiteSpace === "nowrap"
                  && trend.withinCard),
              `${viewport.name}: status pills are misaligned or clipped: `
                  + JSON.stringify(testMetrics.trends));
          assert.deepEqual(
              testMetrics.segmentRadii.map(radius => ({
                left: radius.topLeft > 0 || radius.bottomLeft > 0,
                right: radius.topRight > 0 || radius.bottomRight > 0
              })),
              [{left: true, right: false}, {left: false, right: false},
                {left: false, right: true}],
              `${viewport.name}: defect segment outer corners are incorrect`);
          testMetricSizes[viewport.name] = {
            automationLabels: testMetrics.automationLabels,
            defectLabels: testMetrics.defectLabels,
            subtitle: testMetrics.subtitleFontSize,
            title: testMetrics.titleFontSize
          };
          if (viewport.name === "compact") {
            const metricTiles = await compactManagementMetricLayout(driver);
            assert.equal(metricTiles.length, 4);
            assert.ok(
                metricTiles.every(metric => metric.withinTile),
                `Compact metric content crossed a quadrant: ${JSON.stringify(metricTiles)}`);
            assert.ok(
                metricTiles.every(metric => metric.valueFontSize <= 21.6),
                `Compact metric value text did not scale down: ${JSON.stringify(metricTiles)}`);
            assert.ok(
                metricTiles.every(metric => metric.overflow === "hidden"),
                `Compact metric quadrants do not clip overflow: ${JSON.stringify(metricTiles)}`);
            assert.ok(
                metricTiles.every(metric =>
                  Math.min(
                      metric.paddingTop,
                      metric.paddingRight,
                      metric.paddingBottom,
                      metric.paddingLeft) >= 2),
                `Compact metric padding dropped below 2px: ${JSON.stringify(metricTiles)}`);
            assert.ok(
                metricTiles.every(metric =>
                  (metric.key === "tester-volume" || metric.key === "tester-defects"
                    ? metric.titleTextAlign === "start"
                    : metric.titleTextAlign === "center")
                    && metric.valueTextAlign === "center"
                    && metric.titleFontWeight === "600"
                    && metric.valueFontWeight === "700"),
                `Compact metric typography did not scale and align by tile type: `
                    + JSON.stringify(metricTiles));
            const testerRows = metricTiles
                .filter(metric =>
                  metric.key === "tester-volume" || metric.key === "tester-defects")
                .flatMap(metric => metric.rows);
            assert.ok(testerRows.length > 0);
            assert.ok(
                testerRows.every(row =>
                  row.display === "grid"
                    && row.flexWrap === "nowrap"
                    && row.labelOverflow === "hidden"
                    && row.labelTextOverflow === "ellipsis"
                    && row.labelWhiteSpace === "nowrap"
                    && row.valueInline),
                `Tester metric rows wrapped or escaped: ${JSON.stringify(testerRows)}`);
            assert.ok(
                testerRows.some(row => row.labelIsTruncated),
                `Tester metric ellipsis was not exercised: ${JSON.stringify(testerRows)}`);
            const volumeRows = metricTiles
                .find(metric => metric.key === "tester-volume").rows;
            assert.ok(
                volumeRows.every(row => row.valueWithinTile && row.valueRightGutter >= 4),
                `Tester-volume values lost their right gutter: ${JSON.stringify(volumeRows)}`);
          }
          const defectRows = await managementDefectListLayout(driver);
          assert.equal(defectRows.length, 6);
          assert.ok(
              defectRows.every(row => row.columns === 4),
              `${viewport.name}: defect rows lost their four-column layout: `
                  + JSON.stringify(defectRows));
          assert.ok(
              defectRows.every(
                  row => row.descriptionAfterId && row.descriptionBeforePills),
              `${viewport.name}: defect text crossed a column boundary: `
                  + JSON.stringify(defectRows));
          assert.ok(
              defectRows.every(row => row.rowContainsContent && row.rowOverflow <= 1),
              `${viewport.name}: defect content escaped its row: ${JSON.stringify(defectRows)}`);
          const pillWidths = defectRows.flatMap(row => row.pillWidths);
          assert.ok(
              Math.max(...pillWidths) - Math.min(...pillWidths) <= 1,
              `${viewport.name}: defect pills are not uniform: ${JSON.stringify(pillWidths)}`);
          const defectHeader = await managementDefectHeaderLayout(driver);
          assert.equal(defectHeader.activeArrowMoreVisible, true);
          assert.equal(defectHeader.activeSort, "ascending");
          assert.equal(defectHeader.columns, 4);
          assert.equal(defectHeader.position, "sticky");
          assert.equal(
              defectHeader.aligned,
              true,
              `${viewport.name}: defect sort columns are misaligned: `
                  + JSON.stringify(defectHeader));
          const categoryNavigation = await managementCategoryNavigationLayout(driver);
          assert.equal(
              categoryNavigation.overflow,
              true,
              `${viewport.name}: defect group strip did not overflow`);
          assert.equal(
              categoryNavigation.oneLine,
              true,
              `${viewport.name}: defect group tabs wrapped: ${JSON.stringify(categoryNavigation)}`);
          assert.equal(
              categoryNavigation.scrolled,
              true,
              `${viewport.name}: defect group strip did not scroll`);
          assert.equal(
              categoryNavigation.headerContainsNavigation,
              true,
              `${viewport.name}: defect group controls escaped the top bar`);
          assert.ok(
              categoryNavigation.navigationHeight
                  <= categoryNavigation.switcherHeight + 2,
              `${viewport.name}: defect group arrows distorted the top bar: `
                  + JSON.stringify(categoryNavigation));
          assert.ok(
              categoryNavigation.arrowSizes.every(
                  size => size.width <= 20 && size.height <= 20),
              `${viewport.name}: defect group arrows are oversized: `
                  + JSON.stringify(categoryNavigation));
          for (const metric of normal) {
            assert.ok(
                metric.graphWidth <= 221,
                `${viewport.name} normal: graph exceeds the 220px card cap`);
          }

          await setMode(driver, "focused");
          const focused = await graphMetrics(driver);
          assertVisibleGraphs(focused, `${viewport.name} focused`);

          const expanded = [];
          for (let cardIndex = 0; cardIndex < 4; cardIndex += 1) {
            await setMode(driver, "expanded", cardIndex);
            const expandedMetrics = await graphMetrics(driver);
            assertVisibleGraphs(expandedMetrics, `${viewport.name} expanded ${cardIndex}`);
            expanded.push(expandedMetrics[cardIndex]);
          }

          if (viewport.name === "desktop") {
            desktopSizes.normal = normal[0].graphWidth;
            desktopSizes.focused = focused[0].graphWidth;
            desktopSizes.expanded = expanded[0].graphWidth;
            desktopSizes.expandedMetric = expanded[0];
          }
        }

        assert.ok(
            desktopSizes.focused > desktopSizes.normal * 1.2,
            `Focused graph did not grow: ${JSON.stringify(desktopSizes)}`);
        assert.ok(
            desktopSizes.expanded > desktopSizes.focused * 1.2,
            `Expanded graph did not grow: ${JSON.stringify(desktopSizes)}`);
        assert.ok(
            testMetricSizes.wide.title > testMetricSizes.compact.title
                && testMetricSizes.wide.subtitle > testMetricSizes.compact.subtitle,
            `Test Insights header typography did not scale: ${JSON.stringify(testMetricSizes)}`);
        assert.ok(
            testMetricSizes.compact.defectLabels.some(label => /^[A-Z] \(\d+\)$/.test(label)),
            `Compact defect labels were not abbreviated: ${JSON.stringify(testMetricSizes)}`);
        assert.deepEqual(
            testMetricSizes.wide.defectLabels,
            ["Major (14)", "Medium (9)", "Unspecified (5)"],
            `Wide defect labels were not restored: ${JSON.stringify(testMetricSizes)}`);
        assert.ok(
            testMetricSizes.compact.automationLabels.includes("🐢"),
            `Compact automation labels did not fall back to emojis: `
                + JSON.stringify(testMetricSizes));
        assert.deepEqual(
            testMetricSizes.wide.automationLabels,
            ["🔥 Automated", "🐢 Manual"],
            `Wide automation labels were not restored: ${JSON.stringify(testMetricSizes)}`);
      });
    });
