import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const jelly = readFileSync(
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
        + "OctaneGateReportAction/index.jelly",
    "utf8");
const containmentSource = jelly
    .split("/* OCTANE_MODAL_CLICK_CONTAINMENT_START */")[1]
    .split("/* OCTANE_MODAL_CLICK_CONTAINMENT_END */")[0];
const context = {};
vm.runInNewContext(
    `${containmentSource}\nthis.stopOverlayContentClick = stopOverlayContentClick;`,
    context);

test("a click inside focused modal content cannot trigger backdrop close", () => {
  const inside = {};
  const outside = {};
  const activeModal = {contains: target => target === inside};
  let backdropCloseCount = 0;

  function simulateClick(target) {
    const event = {
      propagationStopped: false,
      stopPropagation() {
        this.propagationStopped = true;
      },
      target
    };
    context.stopOverlayContentClick(activeModal, event);
    if (!event.propagationStopped) {
      backdropCloseCount += 1;
    }
  }

  simulateClick(inside);
  assert.equal(backdropCloseCount, 0);
  simulateClick(outside);
  assert.equal(backdropCloseCount, 1);
  assert.match(jelly, /event\.target !== expandedBackdrop/);
});

test("focused content and tooltips occupy deterministic top overlay layers", () => {
  assert.match(
      jelly,
      /\.octane-expanded-backdrop\s*\{[^}]*z-index: 2147483645;/s);
  assert.ok(
      (jelly.match(/z-index: 2147483646;/g) || []).length >= 3,
      "all focused zone/card variants must sit above the backdrop");
  assert.match(jelly, /\.octane-bar-popup\s*\{[^}]*z-index: 2147483647;/s);
});

test("tooltip rendering and viewport refreshes are delegated and debounced", () => {
  assert.equal(
      (jelly.match(/dashboard\.addEventListener\("mousemove"/g) || []).length,
      1);
  assert.match(jelly, /function scheduleBarPopup\(column, point, input\)/);
  assert.match(jelly, /window\.setTimeout\(function \(\) \{[\s\S]*?showBarPopup/s);
  assert.match(jelly, /function scheduleActiveBarPopupRefresh\(\)/);
  assert.match(jelly, /window\.setTimeout\(refreshActiveBarPopup, 100\)/);
});
