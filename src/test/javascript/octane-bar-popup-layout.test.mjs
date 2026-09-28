import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import vm from "node:vm";

const jellyPath =
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
    + "OctaneGateReportAction/index.jelly";
const jelly = readFileSync(jellyPath, "utf8");
const layoutSource = jelly
    .split("/* OCTANE_BAR_POPUP_LAYOUT_START */")[1]
    .split("/* OCTANE_BAR_POPUP_LAYOUT_END */")[0];
const context = {};
vm.runInNewContext(
    `${layoutSource}\n`
        + "this.chooseBarPopupPlacement = chooseBarPopupPlacement;\n"
        + "this.pointerAnchorRectangle = pointerAnchorRectangle;",
    context);
const chooseBarPopupPlacement = context.chooseBarPopupPlacement;
const pointerAnchorRectangle = context.pointerAnchorRectangle;

const viewport = {bottom: 300, left: 0, right: 500, top: 0};
const popup = {height: 80, width: 100};

test("keeps the preferred side when it fits", () => {
  const placement = chooseBarPopupPlacement(
      {bottom: 220, left: 200, right: 240, top: 100}, popup, viewport, [], "right");

  assert.equal(placement.side, "right");
  assert.equal(placement.left, 254);
});

test("anchors each mouse placement to the latest pointer coordinates", () => {
  const firstAnchor = pointerAnchorRectangle({clientX: 180, clientY: 120});
  const secondAnchor = pointerAnchorRectangle({clientX: 220, clientY: 170});
  const firstPlacement =
      chooseBarPopupPlacement(firstAnchor, popup, viewport, [], "right");
  const secondPlacement =
      chooseBarPopupPlacement(secondAnchor, popup, viewport, [], "right");

  assert.equal(firstPlacement.left, 194);
  assert.equal(firstPlacement.top, 80);
  assert.equal(secondPlacement.left, 234);
  assert.equal(secondPlacement.top, 130);
});

test("flips right at the left viewport edge", () => {
  const placement = chooseBarPopupPlacement(
      {bottom: 220, left: 10, right: 30, top: 100}, popup, viewport, [], "left");

  assert.equal(placement.side, "right");
});

test("flips left at the right viewport edge", () => {
  const placement = chooseBarPopupPlacement(
      {bottom: 220, left: 470, right: 490, top: 100}, popup, viewport, [], "right");

  assert.equal(placement.side, "left");
});

test("uses a vertical fallback when dense bars block both sides", () => {
  const placement = chooseBarPopupPlacement(
      {bottom: 250, left: 220, right: 250, top: 120},
      {height: 80, width: 120},
      viewport,
      [
        {bottom: 250, left: 170, right: 206, top: 100},
        {bottom: 250, left: 264, right: 300, top: 100}
      ],
      "right");

  assert.equal(placement.side, "above");
});

test("clamps the fallback fully inside a narrow chart viewport", () => {
  const narrowViewport = {bottom: 120, left: 0, right: 160, top: 0};
  const narrowPopup = {height: 104, width: 144};
  const placement = chooseBarPopupPlacement(
      {bottom: 90, left: 70, right: 90, top: 50},
      narrowPopup,
      narrowViewport,
      [],
      "right");

  assert.ok(placement.left >= 8);
  assert.ok(placement.top >= 8);
  assert.ok(placement.left + narrowPopup.width <= 152);
  assert.ok(placement.top + narrowPopup.height <= 112);
});
