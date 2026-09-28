(function (root, factory) {
  "use strict";
  var api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.OctaneScaleReport = api;
  }
})(typeof window !== "undefined" ? window : null, function () {
  "use strict";

  var MAX_VISIBLE_BARS = 80;
  var MIN_BAR_SLOT_PX = 10;
  var OVERFLOW_WIDTH_PX = 24;
  var MIN_READABLE_LABEL_CHARACTERS = 6;
  var SVG_NAMESPACE = "http://www.w3.org/2000/svg";
  var DONUT_CENTER = 50;
  var DONUT_RADIUS = 46;
  var DONUT_HOLE_RADIUS = 37.36;
  var mountedZones = typeof WeakMap === "function" ? new WeakMap() : null;

  function computeVisibleBarCount(width, totalBars) {
    var safeWidth = Math.max(0, Number(width) || 0);
    var safeTotal = Math.max(0, Number(totalBars) || 0);
    var capacity = Math.max(
        1, Math.floor(Math.max(0, safeWidth - OVERFLOW_WIDTH_PX) / MIN_BAR_SLOT_PX));
    return Math.min(MAX_VISIBLE_BARS, safeTotal, capacity);
  }

  function createElement(name, className, text) {
    var element = document.createElement(name);
    if (className) {
      element.className = className;
    }
    if (text !== undefined && text !== null) {
      element.textContent = String(text);
    }
    return element;
  }

  function createSvgElement(name, className) {
    var element = document.createElementNS(SVG_NAMESPACE, name);
    if (className) {
      element.setAttribute("class", className);
    }
    return element;
  }

  function auditSelectedAxes(element, section) {
    if (!element || element.getAttribute("data-axes-audited") === "true") {
      return;
    }
    var xAxis = section.xAxis || "Tester";
    var yAxis = section.yAxis || "Count";
    console.log(selectedAxesAuditMessage(xAxis, yAxis));
    element.setAttribute("data-axes-audited", "true");
  }

  function selectedAxesAuditMessage(xAxis, yAxis) {
    return `SELECTED AXES: X: ${String(xAxis || "Tester").toUpperCase()}, Y: ${String(yAxis || "Count").toUpperCase()}`;
  }

  function appendText(parent, name, className, text) {
    var element = createElement(name, className, text);
    parent.appendChild(element);
    return element;
  }

  function appendSvgText(parent, className, value, x, y) {
    var text = createSvgElement("text", className);
    text.setAttribute("x", String(x));
    text.setAttribute("y", String(y));
    text.textContent = String(value);
    parent.appendChild(text);
    return text;
  }

  function truncateAxisLabel(value, maximumCharacters) {
    var label = String(value || "");
    var limit = Math.max(0, Math.floor(Number(maximumCharacters) || 0));
    if (limit === 0 || label.length <= limit) {
      return label;
    }
    return limit === 1 ? "\u2026" : label.slice(0, limit - 1) + "\u2026";
  }

  function axisLabelLayout(
      labelWidth, slotWidth, averageCharacterWidth, maximumMargin, fontHeight) {
    var safeLabelWidth = Math.max(0, Number(labelWidth) || 0);
    var safeSlotWidth = Math.max(1, Number(slotWidth) || 1);
    var safeCharacterWidth = Math.max(1, Number(averageCharacterWidth) || 6);
    var safeMargin = Math.max(16, Number(maximumMargin) || 48);
    var safeFontHeight = Math.max(1, Number(fontHeight) || 12);
    var readableWidth = safeCharacterWidth * (MIN_READABLE_LABEL_CHARACTERS + 1);
    var overlaps = safeLabelWidth > safeSlotWidth;
    if (!overlaps && safeSlotWidth >= readableWidth) {
      return {axisMargin: safeFontHeight + 6, maximumCharacters: 0, rotation: 0};
    }
    var rotation = safeSlotWidth < readableWidth ? -90 : -45;
    var radians = Math.abs(rotation) * Math.PI / 180;
    var availableTextWidth = Math.max(
        safeCharacterWidth,
        (safeMargin - Math.cos(radians) * safeFontHeight) / Math.sin(radians));
    var maximumCharacters = Math.max(
        1, Math.floor(availableTextWidth / safeCharacterWidth));
    var renderedWidth = Math.min(safeLabelWidth, maximumCharacters * safeCharacterWidth);
    var projectedHeight =
        Math.sin(radians) * renderedWidth + Math.cos(radians) * safeFontHeight;
    return {
      axisMargin: Math.min(safeMargin, Math.ceil(projectedHeight + 6)),
      maximumCharacters: maximumCharacters,
      rotation: rotation
    };
  }

  function measureAxisLabel(value, font) {
    if (typeof document === "undefined" || !document.createElement) {
      return String(value || "").length * 6;
    }
    var canvas = measureAxisLabel.canvas;
    if (!canvas) {
      canvas = document.createElement("canvas");
      measureAxisLabel.canvas = canvas;
    }
    var context = canvas.getContext && canvas.getContext("2d");
    if (!context) {
      return String(value || "").length * 6;
    }
    context.font = font || '10px Inter, "Segoe UI", Arial, sans-serif';
    return context.measureText(String(value || "")).width;
  }

  function donutPoint(angle, radius) {
    var radians = angle * Math.PI / 180;
    return {
      x: DONUT_CENTER + radius * Math.cos(radians),
      y: DONUT_CENTER + radius * Math.sin(radians)
    };
  }

  function donutNumber(value) {
    return Number(value).toFixed(3);
  }

  function donutPath(startAngle, endAngle) {
    var start = donutPoint(startAngle, DONUT_RADIUS);
    var end = donutPoint(endAngle, DONUT_RADIUS);
    var largeArc = endAngle - startAngle > 180 ? 1 : 0;
    return "M " + donutNumber(DONUT_CENTER) + " " + donutNumber(DONUT_CENTER)
        + " L " + donutNumber(start.x) + " " + donutNumber(start.y)
        + " A " + donutNumber(DONUT_RADIUS) + " " + donutNumber(DONUT_RADIUS)
        + " 0 " + largeArc + " 1 " + donutNumber(end.x) + " " + donutNumber(end.y)
        + " Z";
  }

  function computeDonutSlices(statuses, total) {
    var safeTotal = Math.max(0, Number(total) || 0);
    if (safeTotal === 0) {
      return [];
    }
    var angle = -90;
    var slices = [];
    (statuses || []).forEach(function (status) {
      var count = Math.max(0, Number(status.count) || 0);
      if (count === 0) {
        return;
      }
      var percentage = count * 100 / safeTotal;
      var endAngle = angle + 360 * percentage / 100;
      slices.push({
        fullCircle: percentage >= 99.999999,
        path: percentage >= 99.999999 ? "" : donutPath(angle, endAngle),
        percentage: percentage,
        percentageLabel: status.percentageLabel || percentage.toFixed(2) + "%",
        status: status
      });
      angle = endAngle;
    });
    return slices;
  }

  function createCardActions() {
    var actions = createElement("div", "octane-card-actions");
    var expand = createElement("button", "octane-expand-toggle");
    expand.type = "button";
    expand.setAttribute("aria-label", "Expand widget");
    expand.setAttribute("aria-expanded", "false");
    expand.title = "Expand widget";
    var expandIcon = createSvgElement("svg", "octane-action-icon octane-icon-expand");
    expandIcon.setAttribute("viewBox", "0 0 24 24");
    expandIcon.setAttribute("aria-hidden", "true");
    ["M8 4H4v4", "M16 4h4v4", "M20 16v4h-4", "M8 20H4v-4"].forEach(
        function (pathValue) {
          var path = createSvgElement("path", "");
          path.setAttribute("d", pathValue);
          expandIcon.appendChild(path);
        });
    var collapseIcon = createSvgElement("svg", "octane-action-icon octane-icon-collapse");
    collapseIcon.setAttribute("viewBox", "0 0 24 24");
    collapseIcon.setAttribute("aria-hidden", "true");
    ["M9 3v6H3", "M3 9l6-6", "M15 21v-6h6", "M21 15l-6 6"].forEach(
        function (pathValue) {
          var path = createSvgElement("path", "");
          path.setAttribute("d", pathValue);
          collapseIcon.appendChild(path);
        });
    expand.appendChild(expandIcon);
    expand.appendChild(collapseIcon);
    actions.appendChild(expand);

    var move = createElement("button", "octane-card-tools");
    move.type = "button";
    move.setAttribute("aria-label", "Move widget");
    move.title = "Drag to move. Use arrow keys to reorder. Resize from the corner.";
    var moveIcon = createSvgElement("svg", "octane-grabber-icon");
    moveIcon.setAttribute("viewBox", "0 0 24 24");
    moveIcon.setAttribute("aria-hidden", "true");
    [5, 12, 19].forEach(function (cy) {
      var circle = createSvgElement("circle", "");
      circle.setAttribute("cx", "12");
      circle.setAttribute("cy", String(cy));
      circle.setAttribute("r", "2");
      moveIcon.appendChild(circle);
    });
    move.appendChild(moveIcon);
    actions.appendChild(move);
    return actions;
  }

  function createCard(section, kind) {
    var card = createElement("section", "octane-chart-card octane-deferred-chart-card");
    card.draggable = true;
    card.setAttribute("data-card-key", kind + "-" + section.source);
    card.setAttribute("data-octane-section", section.id);
    var header = createElement("div", "octane-card-header");
    var heading = createElement("div", "");
    appendText(
        heading,
        "h2",
        "octane-card-title",
        kind === "distribution" ? section.distributionTitle : section.barChartTitle);
    header.appendChild(heading);
    var actions = createCardActions();
    header.appendChild(actions);
    card.appendChild(header);
    return {actions: actions, card: card, heading: heading};
  }

  function appendBarLegend(heading, section) {
    var metadata = createElement("div", "octane-suite-chart-meta");
    appendText(
        metadata,
        "span",
        "octane-total-label",
        "Total Suiteruns: " + section.suiteRunCount);
    var legend = createElement("div", "octane-legend");
    (section.totals || []).forEach(function (status) {
      if (Number(status.count) <= 0) {
        return;
      }
      var row = createElement("span", "octane-legend-row");
      var swatch = createElement("span", "octane-swatch");
      swatch.style.background = status.color;
      row.appendChild(swatch);
      appendText(row, "span", "", status.label);
      legend.appendChild(row);
    });
    metadata.appendChild(legend);
    heading.appendChild(metadata);
  }

  function renderDistribution(section) {
    var parts = createCard(section, "distribution");
    var total = section.metrics && section.metrics.total;
    appendText(
        parts.heading,
        "div",
        "octane-muted octane-distribution-subtitle",
        "Total test cases: " + total);
    var graph = createElement("div", "octane-chart-inner octane-donut-graph");
    var layout = createElement("div", "octane-donut-layout");
    var wrap = createElement("div", "octane-donut-wrap");
    var svg = createSvgElement("svg", "octane-donut octane-client-donut");
    svg.setAttribute("viewBox", "3 3 94 94");
    svg.setAttribute("preserveAspectRatio", "xMidYMid meet");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", section.distributionTitle);
    var slices = computeDonutSlices(section.totals || [], total);
    slices.forEach(function (slice) {
      var graphic = createSvgElement(
          slice.fullCircle ? "circle" : "path",
          slice.fullCircle ? "" : "octane-donut-segment");
      if (slice.fullCircle) {
        graphic.setAttribute("cx", "50");
        graphic.setAttribute("cy", "50");
        graphic.setAttribute("r", "46");
      } else {
        graphic.setAttribute("d", slice.path);
      }
      graphic.setAttribute("fill", slice.status.color);
      var title = createSvgElement("title", "");
      title.textContent = slice.status.label + ": " + slice.status.count
          + " (" + slice.percentageLabel + ")";
      graphic.appendChild(title);
      svg.appendChild(graphic);
    });
    var hole = createSvgElement("circle", "octane-donut-hole");
    hole.setAttribute("cx", "50");
    hole.setAttribute("cy", "50");
    hole.setAttribute("r", String(DONUT_HOLE_RADIUS));
    svg.appendChild(hole);
    var value = appendSvgText(svg, "octane-donut-center-value", total, "50", "46");
    value.setAttribute("dominant-baseline", "central");
    value.setAttribute("text-anchor", "middle");
    var label = appendSvgText(
        svg, "octane-donut-center-label", "Total test cases", "50", "57");
    label.setAttribute("dominant-baseline", "central");
    label.setAttribute("text-anchor", "middle");
    wrap.appendChild(svg);
    layout.appendChild(wrap);
    layout.appendChild(distributionLegend(section));
    graph.appendChild(layout);
    parts.card.appendChild(graph);
    return parts.card;
  }

  function distributionLegend(section) {
    var table = createElement("table", "octane-donut-legend");
    appendText(table, "caption", "", section.distributionTitle);
    var body = createElement("tbody", "");
    (section.totals || []).forEach(function (status) {
      if (Number(status.count) <= 0) {
        return;
      }
      var row = createElement("tr", "");
      var statusCell = createElement("th", "octane-donut-legend-status");
      statusCell.setAttribute("scope", "row");
      var statusLabel = createElement("span", "octane-donut-legend-label");
      var swatch = createElement("span", "octane-swatch");
      swatch.style.background = status.color;
      swatch.setAttribute("aria-hidden", "true");
      statusLabel.appendChild(swatch);
      appendText(statusLabel, "span", "", status.label);
      statusCell.appendChild(statusLabel);
      row.appendChild(statusCell);
      appendText(
          row, "td", "octane-donut-legend-percentage", status.percentageLabel);
      body.appendChild(row);
    });
    if (Number(section.executedTestCount) > 0) {
      var automationRow = createElement("tr", "");
      automationRow.setAttribute("data-automation-usage-row", "true");
      var automationStatus = createElement("th", "octane-donut-legend-status");
      automationStatus.setAttribute("scope", "row");
      var automationLabel = createElement("span", "octane-donut-legend-label");
      appendText(
          automationLabel,
          "span",
          "octane-automation-icon",
          section.automationEmoji
              || (Number(section.automationPercentage) > 0 ? "🔥" : "🐢"))
          .setAttribute("aria-hidden", "true");
      appendText(automationLabel, "span", "", "Automation Usage");
      automationStatus.appendChild(automationLabel);
      automationRow.appendChild(automationStatus);
      appendText(
          automationRow,
          "td",
          "octane-donut-legend-percentage",
          section.automationPercentageLabel || String(section.automationPercentage || 0) + "%");
      body.appendChild(automationRow);
    }
    table.appendChild(body);
    return table;
  }

  function buildSectionUrl(baseUrl, sectionId, cursor, limit) {
    var separator = baseUrl.indexOf("?") >= 0 ? "&" : "?";
    return baseUrl
        + separator
        + "section="
        + encodeURIComponent(sectionId)
        + "&cursor="
        + encodeURIComponent(cursor)
        + "&limit="
        + encodeURIComponent(limit);
  }

  function fetchJson(url, checksum, signal) {
    var headers = {Accept: "application/json"};
    if (checksum) {
      headers["If-None-Match"] = '"' + checksum + '"';
    }
    var options = {
      cache: "no-store",
      credentials: "same-origin",
      headers: headers
    };
    if (signal) {
      options.signal = signal;
    }
    return window.fetch(url, options).then(function (response) {
      if (response.status === 304) {
        return null;
      }
      if (!response.ok) {
        throw new Error("Octane report data request failed: " + response.status);
      }
      return response.json();
    });
  }

  function createRequestController() {
    return typeof AbortController === "function" ? new AbortController() : null;
  }

  function signalFor(controller) {
    return controller ? controller.signal : null;
  }

  function abortRequest(controller) {
    if (controller) {
      controller.abort();
    }
  }

  function isAbortError(error) {
    return Boolean(error && error.name === "AbortError");
  }

  function statusByKey(bar, key) {
    var statuses = bar.statuses || [];
    for (var index = 0; index < statuses.length; index += 1) {
      if (statuses[index].key === key) {
        return statuses[index];
      }
    }
    return {
      count: 0,
      label: key === "running" ? "In Progress" : key,
      tooltipColor: ""
    };
  }

  function stampBarData(group, bar, cardKey) {
    group.setAttribute("data-card-key", cardKey);
    group.setAttribute("data-bar-key", bar.id);
    group.setAttribute("data-dominant-status-color", bar.dominantStatusColor || "");
    group.setAttribute("data-dominant-status-label", bar.dominantStatusLabel || "");
    ["passed", "failed", "blocked", "skipped", "running"].forEach(function (key) {
      var status = statusByKey(bar, key);
      group.setAttribute("data-status-" + key + "-count", String(status.count || 0));
      group.setAttribute("data-status-" + key + "-color", status.tooltipColor || "");
      group.setAttribute("data-status-" + key + "-label", status.label || key);
    });
    var inProgress = statusByKey(bar, "running");
    group.setAttribute("data-status-in-progress-count", String(inProgress.count || 0));
    group.setAttribute("data-status-in-progress-label", inProgress.label || "In Progress");
    group.setAttribute("data-bar-name", bar.name || "");
    group.setAttribute("data-bar-total", String(bar.total || 0));
    group.setAttribute(
        "data-automation-percentage", String(Number(bar.automationPercentage) || 0));
    group.setAttribute(
        "data-automation-emoji",
        bar.automationEmoji || (Number(bar.automationPercentage) > 0 ? "🔥" : "🐢"));
  }

  function renderBarChart(card, section, page, measuredWidth) {
    var old = card.querySelector("[data-client-bar-content]");
    if (old) {
      old.remove();
    }
    var content = createElement("div", "octane-chart-inner octane-client-bar-content");
    content.setAttribute("data-client-bar-content", "true");
    var svg = createSvgElement("svg", "octane-client-bar-chart");
    var plotLeft = 52;
    var plotTop = 10;
    var plotBottom = 252;
    var chartWidth = Math.max(320, Number(measuredWidth) || 700);
    var pageCursor = Math.max(0, Number(page.cursor) || 0);
    var hiddenCount = Math.max(0, Number(page.totalBars) - (page.bars || []).length);
    var overflowSvgWidth = hiddenCount > 0 ? OVERFLOW_WIDTH_PX * 1000 / chartWidth : 0;
    var plotRight = 988 - overflowSvgWidth;
    var plotWidth = Math.max(1, plotRight - plotLeft);
    var maximum = Math.max(1, Number(section.maxTotal) || 1);
    var bars = page.bars || [];
    var slotWidth = bars.length > 0 ? plotWidth / bars.length : plotWidth;
    var slotWidthPx = slotWidth * chartWidth / 1000;
    var labelFont = '12px Inter, "Segoe UI", Arial, sans-serif';
    var averageCharacterWidth = measureAxisLabel("MMMMMM\u2026", labelFont) / 7;
    var maximumLabelWidth = bars.reduce(function (width, bar) {
      return Math.max(
          width,
          measureAxisLabel(bar.axisLabel || bar.name || "", labelFont));
    }, 0);
    var labelLayout = axisLabelLayout(
        maximumLabelWidth, slotWidthPx, averageCharacterWidth, 60, 12);
    var svgHeight = labelLayout.rotation === 0 ? 300 : 344;
    svg.setAttribute("viewBox", "0 0 1000 " + svgHeight);
    svg.style.aspectRatio = "1000 / " + svgHeight;
    svg.setAttribute("preserveAspectRatio", "xMidYMid meet");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", section.barChartTitle);
    svg.setAttribute("data-x-axis", section.xAxis || "Tester");
    svg.setAttribute("data-y-axis", section.yAxis || "Count");
    svg.setAttribute("data-tooltips-enabled", String(section.tooltipsEnabled !== false));
    auditSelectedAxes(svg, section);

    (section.yAxisTicks || []).forEach(function (tick) {
      var y = plotBottom - Number(tick) / maximum * (plotBottom - plotTop);
      var grid = createSvgElement("line", "octane-client-grid-line");
      grid.setAttribute("x1", String(plotLeft));
      grid.setAttribute("x2", String(plotRight));
      grid.setAttribute("y1", String(y));
      grid.setAttribute("y2", String(y));
      svg.appendChild(grid);
      appendSvgText(svg, "octane-client-axis-value", tick, plotLeft - 8, y + 4);
    });
    var axis = createSvgElement("line", "octane-client-axis-line");
    axis.setAttribute("x1", String(plotLeft));
    axis.setAttribute("x2", String(plotRight));
    axis.setAttribute("y1", String(plotBottom));
    axis.setAttribute("y2", String(plotBottom));
    svg.appendChild(axis);

    var barWidthPx = Math.min(100, Math.max(8, slotWidthPx * 0.72));
    var barWidth = barWidthPx * 1000 / chartWidth;
    bars.forEach(function (bar, barIndex) {
      var center = plotLeft + slotWidth * (barIndex + 0.5);
      var group = createSvgElement("g", "octane-suite-column octane-client-suite-column");
      if (section.tooltipsEnabled !== false) {
        group.setAttribute("tabindex", "0");
      }
      group.setAttribute("role", "img");
      group.setAttribute("aria-label", bar.title || bar.name || "Tester bar");
      if (section.tooltipsEnabled !== false) {
        stampBarData(group, bar, "bars-" + section.source);
      }
      var currentBottom = plotBottom;
      var hitHeight = Math.max(
          0.5, Number(bar.total) / maximum * (plotBottom - plotTop));
      var hitTarget = createSvgElement(
          "rect",
          section.tooltipsEnabled !== false
              ? "octane-vertical-bar octane-client-bar-hit-target"
              : "octane-client-bar-hit-target");
      hitTarget.setAttribute("x", String(center - barWidth / 2));
      hitTarget.setAttribute("y", String(plotBottom - hitHeight));
      hitTarget.setAttribute("width", String(barWidth));
      hitTarget.setAttribute("height", String(hitHeight));
      hitTarget.setAttribute("fill", "transparent");
      (bar.statuses || []).forEach(function (status) {
        if (Number(status.count) <= 0) {
          return;
        }
        var height = Number(status.count) / maximum * (plotBottom - plotTop);
        var segment = createSvgElement("rect", "octane-client-bar-segment");
        segment.setAttribute("x", String(center - barWidth / 2));
        segment.setAttribute("y", String(currentBottom - height));
        segment.setAttribute("width", String(barWidth));
        segment.setAttribute("height", String(Math.max(0.5, height)));
        segment.setAttribute("fill", status.color);
        group.appendChild(segment);
        currentBottom -= height;
      });
      var fullLabel = bar.axisLabel || bar.name || "";
      var label = appendSvgText(
          group,
          "octane-client-axis-label",
          truncateAxisLabel(fullLabel, labelLayout.maximumCharacters),
          center,
          277);
      label.setAttribute("data-axis-label-rotation", String(Math.abs(labelLayout.rotation)));
      label.setAttribute("text-anchor", labelLayout.rotation === 0 ? "middle" : "end");
      if (labelLayout.rotation !== 0) {
        label.setAttribute(
            "transform",
            "rotate(" + labelLayout.rotation + " " + center + " 277)");
      }
      // Keep the transparent hit area above the painted segments so delegated pointer events
      // consistently target the bar in every browser.
      group.appendChild(hitTarget);
      svg.appendChild(group);
    });

    if (hiddenCount > 0) {
      var overflowStart = plotRight;
      var overflowEnd = Math.min(998, overflowStart + overflowSvgWidth);
      var overflow = createSvgElement("line", "octane-client-overflow-line");
      overflow.setAttribute("x1", String(overflowStart));
      overflow.setAttribute("x2", String(overflowEnd));
      overflow.setAttribute("y1", String(plotBottom));
      overflow.setAttribute("y2", String(plotBottom));
      svg.appendChild(overflow);
      var overflowLabel = appendSvgText(
          svg, "octane-client-overflow-label", "+" + hiddenCount, overflowEnd, 277);
      overflowLabel.setAttribute("text-anchor", "end");
    }
    content.appendChild(svg);
    content.appendChild(barSummaryTable(section, bars));
    card.appendChild(content);
    card.setAttribute("data-octane-loaded", "true");
  }

  function barSummaryTable(section, bars) {
    var table = createElement("table", "octane-chart-data-summary octane-visually-hidden");
    appendText(table, "caption", "", section.barChartTitle + " visible data");
    var body = createElement("tbody", "");
    bars.forEach(function (bar) {
      (bar.statuses || []).forEach(function (status) {
        if (Number(status.count) <= 0) {
          return;
        }
        var row = createElement("tr", "");
        var name = appendText(row, "th", "", bar.name);
        name.setAttribute("scope", "row");
        appendText(row, "td", "", status.label);
        appendText(row, "td", "", status.count);
        appendText(row, "td", "", status.percentageLabel);
        appendText(row, "td", "", bar.total);
        body.appendChild(row);
      });
    });
    table.appendChild(body);
    return table;
  }

  function renderBarCard(section, state) {
    var parts = createCard(section, "bars");
    appendBarLegend(parts.heading, section);
    var pageStatus = createElement("span", "octane-visually-hidden");
    pageStatus.setAttribute("aria-live", "polite");
    parts.heading.appendChild(pageStatus);
    var previous = createPageButton("previous", "Previous tester bars", "M15 18l-6-6 6-6");
    var next = createPageButton("next", "Next tester bars", "M9 18l6-6-6-6");
    parts.actions.insertBefore(previous, parts.actions.firstChild);
    parts.actions.insertBefore(next, parts.actions.children[1] || parts.actions.firstChild);
    var loading = createElement("div", "octane-client-chart-loading", "Loading chart data...");
    loading.setAttribute("role", "status");
    parts.card.appendChild(loading);
    var lastLimit = 0;
    var currentCursor = Math.max(
        0,
        Number(section.barCount || 0)
            - computeVisibleBarCount(700, section.barCount));
    var currentPage = null;
    var loadingRequest = false;
    var pendingCursor = null;
    var requestController = null;
    var requestGeneration = 0;
    var measuredChartWidth = 0;
    function chartWidth() {
      if (measuredChartWidth <= 0) {
        measuredChartWidth = Math.max(320, parts.card.clientWidth || 700);
      }
      return measuredChartWidth;
    }
    function load(cursor) {
      var renderWidth = chartWidth();
      var limit = computeVisibleBarCount(renderWidth, section.barCount);
      var safeCursor = Math.max(0, Number(cursor) || 0);
      if (loadingRequest) {
        pendingCursor = safeCursor;
        return;
      }
      if (limit === lastLimit
              && safeCursor === currentCursor
              && parts.card.hasAttribute("data-octane-loaded")) {
        return;
      }
      loadingRequest = true;
      pendingCursor = null;
      var generation = ++requestGeneration;
      requestController = createRequestController();
      parts.card.setAttribute("aria-busy", "true");
      previous.disabled = true;
      next.disabled = true;
      fetchJson(
              buildSectionUrl(state.dataUrl, section.id, safeCursor, limit),
              "",
              signalFor(requestController))
          .then(function (page) {
            if (generation !== requestGeneration || !parts.card.isConnected) {
              return;
            }
            loadingRequest = false;
            requestController = null;
            parts.card.setAttribute("aria-busy", "false");
            if (!page) {
              loadPending();
              return;
            }
            lastLimit = limit;
            currentCursor = Math.max(0, Number(page.cursor) || 0);
            currentPage = page;
            loading.remove();
            renderBarChart(parts.card, section, page, renderWidth);
            updatePageControls();
            loadPending();
          })
          .catch(function (error) {
            if (generation !== requestGeneration || !parts.card.isConnected) {
              return;
            }
            loadingRequest = false;
            requestController = null;
            parts.card.setAttribute("aria-busy", "false");
            if (isAbortError(error)) {
              return;
            }
            if (!loading.isConnected) {
              parts.card.appendChild(loading);
            }
            loading.textContent = "Chart data is temporarily unavailable.";
            updatePageControls();
            loadPending();
          });
    }
    function loadPending() {
      if (pendingCursor == null) {
        return;
      }
      var cursor = pendingCursor;
      pendingCursor = null;
      load(cursor);
    }
    function updatePageControls() {
      var total = currentPage ? Math.max(0, Number(currentPage.totalBars) || 0) : 0;
      var shown = currentPage && currentPage.bars ? currentPage.bars.length : 0;
      previous.hidden = total <= shown;
      next.hidden = total <= shown;
      previous.disabled = loadingRequest || currentCursor <= 0;
      next.disabled =
          loadingRequest || !currentPage || Number(currentPage.nextCursor) < 0;
      if (shown > 0) {
        pageStatus.textContent =
            "Showing tester bars "
            + (currentCursor + 1)
            + " to "
            + (currentCursor + shown)
            + " of "
            + total;
      } else {
        pageStatus.textContent = "No tester bars available";
      }
    }
    previous.addEventListener("click", function () {
      load(Math.max(0, currentCursor - Math.max(1, lastLimit)));
    });
    next.addEventListener("click", function () {
      if (currentPage && Number(currentPage.nextCursor) >= 0) {
        load(Number(currentPage.nextCursor));
      }
    });
    updatePageControls();
    if (typeof IntersectionObserver === "function") {
      var observer = new IntersectionObserver(function (entries) {
        if (entries.some(function (entry) { return entry.isIntersecting; })) {
          load(currentCursor);
          observer.disconnect();
        }
      }, {rootMargin: "240px 0px"});
      observer.observe(parts.card);
      trackCleanup(state, function () { observer.disconnect(); });
    } else {
      load(currentCursor);
    }
    if (typeof ResizeObserver === "function") {
      var resizeTimer = 0;
      var resizeObserver = new ResizeObserver(function () {
        window.clearTimeout(resizeTimer);
        measuredChartWidth = 0;
        resizeTimer = window.setTimeout(function () { load(currentCursor); }, 80);
      });
      resizeObserver.observe(parts.card);
      trackCleanup(state, function () {
        window.clearTimeout(resizeTimer);
        resizeObserver.disconnect();
      });
    }
    trackCleanup(state, function () {
      pendingCursor = null;
      requestGeneration++;
      abortRequest(requestController);
      requestController = null;
    });
    return parts.card;
  }

  function createPageButton(direction, label, pathValue) {
    var button = createElement("button", "octane-client-page-button");
    button.type = "button";
    button.setAttribute("aria-label", label);
    button.title = label;
    button.setAttribute("data-page-direction", direction);
    var icon = createSvgElement("svg", "octane-action-icon");
    icon.setAttribute("viewBox", "0 0 24 24");
    icon.setAttribute("aria-hidden", "true");
    var path = createSvgElement("path", "");
    path.setAttribute("d", pathValue);
    icon.appendChild(path);
    button.appendChild(icon);
    return button;
  }

  function trackCleanup(state, cleanup) {
    if (!state.cleanups) {
      state.cleanups = [];
    }
    state.cleanups.push(cleanup);
  }

  function disposeState(state) {
    (state.cleanups || []).forEach(function (cleanup) {
      cleanup();
    });
    state.cleanups = [];
  }

  function renderIndex(zone, payload, state) {
    disposeState(state);
    var fragment = document.createDocumentFragment();
    (payload.sections || []).forEach(function (section) {
      fragment.appendChild(renderDistribution(section));
      fragment.appendChild(renderBarCard(section, state));
    });
    if (!payload.sections || payload.sections.length === 0) {
      var card = createElement("section", "octane-chart-card");
      card.setAttribute("data-card-key", "suite-run-empty");
      appendText(card, "h2", "octane-card-title", "Suite run charts");
      appendText(card, "div", "octane-empty", payload.message || "No report data yet.");
      fragment.appendChild(card);
    }
    zone.replaceChildren(fragment);
    zone.setAttribute("data-report-schema-version", String(payload.schemaVersion || 0));
    zone.setAttribute("data-report-client-ready", "true");
  }

  function stateForZone(zone, dataUrl) {
    var state = mountedZones ? mountedZones.get(zone) : zone.__octaneScaleState;
    if (state) {
      return state;
    }
    state = {
      checksum: "",
      cleanups: [],
      controller: null,
      dataUrl: dataUrl,
      generation: 0,
      promise: null
    };
    if (mountedZones) {
      mountedZones.set(zone, state);
    } else {
      zone.__octaneScaleState = state;
    }
    return state;
  }

  function completeMount(zone, payload, state, generation) {
    if (generation !== state.generation) {
      return false;
    }
    if (payload) {
      renderIndex(zone, payload, state);
    }
    state.controller = null;
    zone.setAttribute("aria-busy", "false");
    return true;
  }

  function failMount(zone, state, generation, error) {
    if (generation !== state.generation) {
      return false;
    }
    state.controller = null;
    zone.setAttribute("aria-busy", "false");
    if (isAbortError(error)) {
      return false;
    }
    zone.setAttribute("data-report-client-error", "true");
    return false;
  }

  function mount(zone, dataUrl, checksum) {
    if (!zone || !dataUrl || typeof window === "undefined" || !window.fetch) {
      return Promise.resolve(false);
    }
    var state = stateForZone(zone, dataUrl);
    state.dataUrl = dataUrl;
    if (state.promise && state.checksum === checksum) {
      return state.promise;
    }
    abortRequest(state.controller);
    state.controller = createRequestController();
    state.checksum = checksum || "";
    var generation = ++state.generation;
    zone.setAttribute("aria-busy", "true");
    state.promise = fetchJson(dataUrl, "", signalFor(state.controller))
        .then(function (payload) {
          return completeMount(zone, payload, state, generation);
        })
        .catch(function (error) {
          return failMount(zone, state, generation, error);
        });
    if (typeof window !== "undefined") {
      window.__octaneReportReady = state.promise;
    }
    return state.promise;
  }

  return {
    MAX_VISIBLE_BARS: MAX_VISIBLE_BARS,
    DONUT_HOLE_RADIUS: DONUT_HOLE_RADIUS,
    OVERFLOW_WIDTH_PX: OVERFLOW_WIDTH_PX,
    axisLabelLayout: axisLabelLayout,
    computeDonutSlices: computeDonutSlices,
    computeVisibleBarCount: computeVisibleBarCount,
    measureAxisLabel: measureAxisLabel,
    truncateAxisLabel: truncateAxisLabel,
    stampBarData: stampBarData,
    selectedAxesAuditMessage: selectedAxesAuditMessage,
    mount: mount
  };
});
