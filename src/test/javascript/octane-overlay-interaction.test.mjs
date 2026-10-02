import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";

import {reportSource} from "./report-assets.mjs";
const containmentSource = reportSource
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
  assert.match(reportSource, /event\.target !== expandedBackdrop/);
});

test("focused content and tooltips occupy deterministic top overlay layers", () => {
  assert.match(
      reportSource,
      /\.octane-expanded-backdrop\s*\{[^}]*z-index: 2147483645;/s);
  assert.ok(
      (reportSource.match(/z-index: 2147483646;/g) || []).length >= 3,
      "all focused zone/card variants must sit above the backdrop");
  assert.match(reportSource, /\.octane-bar-popup\s*\{[^}]*z-index: 2147483647;/s);
});

test("tooltip rendering and viewport refreshes are delegated and debounced", () => {
  assert.equal(
      (reportSource.match(/dashboard\.addEventListener\("mousemove"/g) || []).length,
      1);
  assert.match(reportSource, /function scheduleBarPopup\(column, point, input\)/);
  assert.match(reportSource, /window\.setTimeout\(function \(\) \{[\s\S]*?showBarPopup/s);
  assert.match(reportSource, /function scheduleActiveBarPopupRefresh\(\)/);
  assert.match(reportSource, /window\.setTimeout\(refreshActiveBarPopup, 100\)/);
});
