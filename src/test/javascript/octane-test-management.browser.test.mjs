import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {pathToFileURL} from "node:url";

const source = readFileSync("src/main/webapp/js/octane-test-management.js", "utf8");
const jelly = readFileSync(
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
        + "OctaneGateReportAction/index.jelly",
    "utf8");
const hiddenDefectRowsRule = jelly.match(
    /\.octane-management-defect-row\[hidden\],[\s\S]*?\{\s*display:\s*none;\s*\}/)?.[0] || "";
const chromiumAvailable =
    spawnSync("sh", ["-c", "command -v google-chrome"], {stdio: "ignore"}).status === 0;

function defects(category, count, startId) {
  return Array.from({length: count}, (_, index) => ({
    category,
    defect: {
      description: `${category} defect ${index + 1}`,
      id: String(startId + index),
      open: index % 2 === 0,
      severity: "High",
      severityColorKey: "high",
      severityLabel: "High",
      status: index % 2 === 0 ? "Open" : "Closed"
    }
  })).map(entry => entry.defect);
}

function category(key, label, count, startId) {
  const categoryDefects = defects(key, count, startId);
  return {
    closed: categoryDefects.filter(defect => !defect.open).length,
    defects: categoryDefects,
    key,
    label,
    open: categoryDefects.filter(defect => defect.open).length
  };
}

function fixtureHtml() {
  const payload = {
    failureCategories: [
      category("loan-check", "Loan Check", 1, 1),
      category("digital-more", "Digital More", 1, 2),
      category("thi-team", "Thi Team", 2, 3),
      category("paybill", "Paybill", 3, 5),
      category("i-pre", "I Pre", 2, 8)
    ],
    totalDefects: 9
  };
  const refreshedPayload = {
    failureCategories: [
      category("loan-check", "Loan Check", 2, 20),
      category("digital-more", "Digital More", 1, 30),
      category("thi-team", "Thi Team", 2, 40),
      category("paybill", "Paybill", 3, 50),
      category("i-pre", "I Pre", 2, 60)
    ],
    totalDefects: 10
  };
  return `<!doctype html><html><head><style>
    .octane-management-defect-row { display: grid; }
    ${hiddenDefectRowsRule}
  </style></head><body>
    <section id="management-zone">
      <article class="octane-test-management-card"
          data-card-key="test-management-failures">
        <div data-management-legend></div>
        <div data-management-failure-switcher data-selected-category="all"
            data-selected-status="all"></div>
        <div data-management-failures>
          <div class="octane-management-failure-axis-layout"></div>
          <div data-management-y-labels></div>
          <div data-management-failure-bars></div>
        </div>
        <div data-management-defect-list data-sort-column="id"
            data-sort-direction="ascending"></div>
      </article>
    </section>
    <script>${source}</script>
    <script>
      var zone = document.getElementById("management-zone");
      var card = zone.querySelector('[data-card-key="test-management-failures"]');
      OctaneTestManagement.mount(zone, ${JSON.stringify(payload)}, {
        onCategorySelect: function () {
          card.classList.add("octane-expanded");
        }
      });

      function visibleRows() {
        return Array.prototype.filter.call(
            zone.querySelectorAll(".octane-management-defect-row"),
            function (row) { return getComputedStyle(row).display !== "none"; });
      }

      document.body.setAttribute(
          "data-all-label",
          zone.querySelector('[data-management-category-filter="all"]').textContent);
      document.body.setAttribute(
          "data-digital-label",
          zone.querySelector('[data-management-category-filter="digital-more"]').textContent);

      zone.querySelector('[data-management-category-filter="loan-check"]').click();
      var loanRows = visibleRows();
      document.body.setAttribute("data-loan-count", String(loanRows.length));
      document.body.setAttribute("data-loan-categories", loanRows.map(function (row) {
        return row.getAttribute("data-management-defect-category");
      }).join(","));

      zone.querySelector('[data-management-category-filter="paybill"]').click();
      var pillRows = visibleRows();
      document.body.setAttribute("data-pill-count", String(pillRows.length));
      document.body.setAttribute("data-pill-categories", pillRows.map(function (row) {
        return row.getAttribute("data-management-defect-category");
      }).join(","));

      zone.querySelector('[data-management-category="paybill"]').click();
      var barRows = visibleRows();
      document.body.setAttribute("data-bar-count", String(barRows.length));
      document.body.setAttribute("data-bar-categories", barRows.map(function (row) {
        return row.getAttribute("data-management-defect-category");
      }).join(","));

      zone.querySelector('[data-management-category-filter="all"]').click();
      var allRows = visibleRows();
      var switcher = zone.querySelector("[data-management-failure-switcher]");
      document.body.setAttribute("data-all-count", String(allRows.length));
      document.body.setAttribute(
          "data-all-selected",
          zone.querySelector('[data-management-category-filter="all"]')
              .getAttribute("aria-pressed"));
      document.body.setAttribute(
          "data-all-status", switcher.getAttribute("data-selected-status"));

      zone.querySelector('[data-management-category-filter="loan-check"]').click();
      OctaneTestManagement.update(zone, ${JSON.stringify(refreshedPayload)});
      OctaneTestManagement.render(zone);
      var refreshedRows = visibleRows();
      document.body.setAttribute("data-refresh-count", String(refreshedRows.length));
      document.body.setAttribute(
          "data-refresh-categories",
          refreshedRows.map(function (row) {
            return row.getAttribute("data-management-defect-category");
          }).join(","));
      document.body.setAttribute(
          "data-refresh-selected",
          zone.querySelector('[data-management-category-filter="loan-check"]')
              .getAttribute("aria-pressed"));
      var refreshedBar = zone.querySelector(".octane-management-failure-group");
      var refreshedRow = zone.querySelector(".octane-management-defect-row");
      OctaneTestManagement.update(
          zone, JSON.parse(${JSON.stringify(JSON.stringify(refreshedPayload))}));
      OctaneTestManagement.render(zone);
      document.body.setAttribute(
          "data-unchanged-bar-reused",
          String(refreshedBar === zone.querySelector(".octane-management-failure-group")));
      document.body.setAttribute(
          "data-unchanged-row-reused",
          String(refreshedRow === zone.querySelector(".octane-management-defect-row")));

      var attack = ${JSON.stringify('</script><img src=x onerror="window.xss=1">\'"&').replaceAll("<", "\\u003c")};
      var hostile = {
        totalDefects: 2,
        failureCategories: [
          {key: attack, label: attack, open: 1, closed: 0, defects: [
            {id: attack, description: attack, status: "Open", severity: attack, open: true}
          ]},
          {key: "safe", label: "Safe", open: 1, closed: 0, defects: [
            {id: "safe", description: "Safe", status: "Open", severity: "High", open: true}
          ]}
        ]
      };
      OctaneTestManagement.update(zone, hostile);
      OctaneTestManagement.render(zone);
      function clickHostile(attribute) {
        Array.from(zone.querySelectorAll("[" + attribute + "]"))
            .find(function (node) { return node.getAttribute(attribute) === attack; }).click();
        var rows = visibleRows();
        return rows.length === 1
            && rows[0].getAttribute("data-management-defect-category") === attack
            && rows[0].querySelector(".octane-management-defect-description").textContent === attack;
      }
      var hostilePillSafe = clickHostile("data-management-category-filter");
      var hostileBarSafe = clickHostile("data-management-category");
      OctaneTestManagement.update(zone, JSON.parse(JSON.stringify(hostile)));
      OctaneTestManagement.render(zone);
      setTimeout(function () {
        document.body.setAttribute("data-hostile-category-safe", String(
            hostilePillSafe && hostileBarSafe && visibleRows().length === 1
                && !window.xss && !zone.querySelector("img,script,iframe,[onerror],[onload]")));
      }, 50);
    </script>
  </body></html>`;
}

test(
    "delegated pill and vertical-bar clicks filter the rendered defect rows by category",
    {skip: !chromiumAvailable, timeout: 30000},
    () => {
      assert.notEqual(hiddenDefectRowsRule, "", "missing production hidden-row CSS rule");
      const directory = mkdtempSync(join(tmpdir(), "octane-category-filter-"));
      const fixturePath = join(directory, "fixture.html");
      writeFileSync(fixturePath, fixtureHtml(), "utf8");
      try {
        const result = spawnSync(
            "google-chrome",
            [
              "--headless=new",
              "--no-sandbox",
              "--disable-gpu",
              `--user-data-dir=${join(directory, "profile")}`,
              "--virtual-time-budget=1000",
              "--dump-dom",
              pathToFileURL(fixturePath).href
            ],
            {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 20000});

        assert.equal(result.status, 0, result.error || result.stderr);
        assert.match(result.stdout, /data-all-label="All 9"/);
        assert.match(result.stdout, /data-digital-label="Digital More"/);
        assert.match(result.stdout, /data-loan-count="1"/);
        assert.match(result.stdout, /data-loan-categories="loan-check"/);
        assert.match(result.stdout, /data-pill-count="3"/);
        assert.match(result.stdout, /data-pill-categories="paybill,paybill,paybill"/);
        assert.match(result.stdout, /data-bar-count="3"/);
        assert.match(result.stdout, /data-bar-categories="paybill,paybill,paybill"/);
        assert.match(result.stdout, /data-all-count="9"/);
        assert.match(result.stdout, /data-all-selected="true"/);
        assert.match(result.stdout, /data-all-status="all"/);
        assert.match(result.stdout, /data-refresh-count="2"/);
        assert.match(result.stdout, /data-refresh-categories="loan-check,loan-check"/);
        assert.match(result.stdout, /data-refresh-selected="true"/);
        assert.match(result.stdout, /data-unchanged-bar-reused="true"/);
        assert.match(result.stdout, /data-unchanged-row-reused="true"/);
        assert.match(result.stdout, /data-hostile-category-safe="true"/);
      } finally {
        rmSync(directory, {force: true, recursive: true});
      }
    });
