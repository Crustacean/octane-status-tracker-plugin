(function () {
  var dashboard = document.getElementById("octane-dashboard");
  if (!dashboard) {
    return;
  }
  function auditSelectedAxes(chart) {
    if (!chart || chart.getAttribute("data-axes-audited") === "true") {
      return;
    }
    var xAxis = chart.getAttribute("data-x-axis") || "Tester";
    var yAxis = chart.getAttribute("data-y-axis") || "Count";
    console.log(
        "SELECTED AXES: X: " + xAxis.toUpperCase()
            + ", Y: " + yAxis.toUpperCase());
    chart.setAttribute("data-axes-audited", "true");
  }
  var initialAxisCharts = dashboard.querySelectorAll(
      ".octane-vertical-bars[data-x-axis][data-y-axis]");
  for (var initialAxisIndex = 0;
      initialAxisIndex < initialAxisCharts.length;
      initialAxisIndex += 1) {
    auditSelectedAxes(initialAxisCharts[initialAxisIndex]);
  }
  var timerCards = dashboard.querySelectorAll("[data-octane-timer]");
  var progressCards = dashboard.querySelectorAll("[data-octane-progress]");
  var hasPerformanceClock =
      window.performance && typeof window.performance.now === "function";
  var requestFrame = window.requestAnimationFrame || function (callback) {
    return window.setTimeout(callback, 16);
  };
  var reducedMotionQuery =
      window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : null;
  function prefersReducedMotion() {
    return reducedMotionQuery && reducedMotionQuery.matches;
  }
  function scheduleTimerFrame(callback) {
    if (prefersReducedMotion() || document.hidden) {
      return window.setTimeout(callback, 1000);
    }
    return requestFrame(callback);
  }
  var snapshotUrl = dashboard.getAttribute("data-snapshot-url") || "snapshot";
  var snapshotEtag = "";
  var currentUpdatedAt = dashboard.getAttribute("data-current-updated-at") || "";
  var currentUpdatedAtDateTimeText =
      dashboard.getAttribute("data-current-updated-at-text") || "";
  var currentJobStateLabel = dashboard.getAttribute("data-job-state-label") || "Started";
  var heatMapPanel = dashboard.querySelector("[data-heat-map-panel]");
  var lastViableRiskHeatMapHtml =
      dashboard.getAttribute("data-risk-heat-map-populated") === "true" && heatMapPanel
          ? heatMapPanel.innerHTML
          : "";
  var currentRefreshSeconds = parseInt(
      dashboard.getAttribute("data-refresh-seconds") || "1", 10);
  if (isNaN(currentRefreshSeconds)) {
    currentRefreshSeconds = 1;
  }
  var stateLabel = document.querySelector("[data-report-state-label]");
  var updatedAtLabel = document.querySelector("[data-report-updated-at]");
  var messageLabel = document.querySelector("[data-report-message]");
  var exitExtendedForm = document.querySelector("[data-exit-extended-form]");
  var exitExtendedCommand = document.querySelector("[data-exit-extended-command]");
  var exitExtendedSubmitForm = exitExtendedCommand
      ? exitExtendedCommand.closest("form")
      : null;
  var exitFinalizingStatus = document.querySelector("[data-exit-finalizing-status]");
  var reorderStatus = document.getElementById("octane-reorder-status");
  var connectivityStatus = document.getElementById("octane-connectivity-status");
  var liveRefresh = {
    consecutiveFailures: 0,
    fetching: false,
    maximumRetryDelayMillis: 15000,
    retryDelayMillis: 500,
    retryTimer: 0,
    finalizing: dashboard.getAttribute("data-report-finalizing") === "true",
    stopped: dashboard.getAttribute("data-report-building") !== "true",
    waiting: false,
    waitingSince: 0
  };
  function setConnectivityStatus(state, message) {
    if (!connectivityStatus) {
      return;
    }
    connectivityStatus.setAttribute("data-state", state);
    connectivityStatus.textContent = message || "";
    connectivityStatus.hidden = !message;
  }
  function snapshotRequestSucceeded() {
    var recovered = liveRefresh.consecutiveFailures > 0;
    liveRefresh.consecutiveFailures = 0;
    liveRefresh.retryDelayMillis = 500;
    if (recovered) {
      setConnectivityStatus("restored", "Connection restored. Live updates resumed.");
      window.setTimeout(function () {
        if (liveRefresh.consecutiveFailures === 0) {
          setConnectivityStatus("online", "");
        }
      }, 2500);
    }
  }
  function snapshotRequestFailed() {
    liveRefresh.consecutiveFailures += 1;
    liveRefresh.retryDelayMillis = Math.min(
        liveRefresh.maximumRetryDelayMillis,
        500 * Math.pow(2, Math.min(5, liveRefresh.consecutiveFailures - 1)));
    setConnectivityStatus(
        "degraded",
        navigator.onLine === false
            ? "Live updates paused while this browser is offline. The last good report remains visible."
            : "Live data is temporarily unavailable. Retrying while the last good report remains visible.");
  }
  var completionAutoFlipState = {};
  var TIMER_ACTIVE_OPACITY = "1";
  var COLOR_GOOD = "--octane-color-good";
  var COLOR_WARN = "--octane-color-warn";
  var COLOR_BAD = "--octane-color-bad";
  var COLOR_NEUTRAL = "--octane-color-neutral";
  var COLOR_TOKEN_FALLBACKS = {};
  COLOR_TOKEN_FALLBACKS[COLOR_GOOD] = "#34C759";
  COLOR_TOKEN_FALLBACKS[COLOR_WARN] = "#FFCC00";
  COLOR_TOKEN_FALLBACKS[COLOR_BAD] = "#FF3B30";
  COLOR_TOKEN_FALLBACKS[COLOR_NEUTRAL] = "#007AFF";
  var PROGRESS_COLOR_PHASES = {
    completion: [
      {limit: 20, token: COLOR_BAD},
      {limit: 79, token: COLOR_WARN},
      {limit: 100, token: COLOR_GOOD}
    ],
    execution: [
      {limit: 20, token: COLOR_BAD},
      {limit: 79, token: COLOR_WARN},
      {limit: 100, token: COLOR_GOOD}
    ],
    passRate: [
      {limit: 49, token: COLOR_BAD},
      {limit: 89, token: COLOR_WARN},
      {limit: 100, token: COLOR_GOOD}
    ],
    poll: [
      {limit: 100, token: COLOR_NEUTRAL}
    ],
    timeout: [
      {limit: 50, token: COLOR_GOOD},
      {limit: 79, token: COLOR_WARN},
      {limit: 100, token: COLOR_BAD}
    ]
  };
  var wallClockAnchor = Date.now();
  var performanceClockAnchor = hasPerformanceClock ? window.performance.now() : 0;
  function currentWallTime() {
    if (!hasPerformanceClock) {
      return Date.now();
    }
    return wallClockAnchor + window.performance.now() - performanceClockAnchor;
  }
  function parseTimestamp(value) {
    var parsed = Date.parse(value || "");
    return isNaN(parsed) ? currentWallTime() : parsed;
  }
  function clamp(value, minimum, maximum) {
    return Math.min(maximum, Math.max(minimum, value));
  }
  function trimNumber(value) {
    return value.toFixed(3).replace(/\.?0+$/, "");
  }
  function colorTokenValue(token) {
    var fallback = COLOR_TOKEN_FALLBACKS[token] || "#4a4a4a";
    if (!dashboard || !window.getComputedStyle) {
      return fallback;
    }
    var value = window.getComputedStyle(dashboard).getPropertyValue(token).trim();
    return value || fallback;
  }
  function colorsForProgress(kind, progress) {
    var phases = PROGRESS_COLOR_PHASES[kind] || PROGRESS_COLOR_PHASES.timeout;
    for (var index = 0; index < phases.length; index += 1) {
      if (progress <= phases[index].limit) {
        var phaseColor = colorTokenValue(phases[index].token);
        return [phaseColor, phaseColor, phaseColor];
      }
    }
    var finalColor = colorTokenValue(phases[phases.length - 1].token);
    return [finalColor, finalColor, finalColor];
  }
  function stopColorAt(colors, index, stopCount) {
    if (colors.length === 0) {
      return "#4a4a4a";
    }
    if (stopCount <= 1 || colors.length === 1) {
      return colors[colors.length - 1];
    }
    var colorIndex = Math.round((index / (stopCount - 1)) * (colors.length - 1));
    return colors[clamp(colorIndex, 0, colors.length - 1)];
  }
  function applyGradientStops(stops, colors) {
    if (!stops) {
      return;
    }
    for (var index = 0; index < stops.length; index += 1) {
      stops[index].setAttribute("stop-color", stopColorAt(colors, index, stops.length));
    }
  }
  function formatClockDuration(milliseconds) {
    var totalSeconds = Math.max(0, Math.ceil(milliseconds / 1000));
    var minutes = Math.floor(totalSeconds / 60);
    var seconds = totalSeconds % 60;
    return String(minutes).padStart(2, "0") + ":"
        + String(seconds).padStart(2, "0");
  }
  var lastRenderedReportStatus = "";
  function renderReportStatus(now) {
    if (!updatedAtLabel) {
      return;
    }
    var building = dashboard.getAttribute("data-report-building") === "true";
    var finalizing = dashboard.getAttribute("data-report-finalizing") === "true";
    var status;
    if (!building) {
      status = "LAST UPDATED: "
          + (currentUpdatedAtDateTimeText || currentUpdatedAt || "Unknown");
    } else if (finalizing) {
      status = "Finalizing...";
    } else if (liveRefresh.fetching || liveRefresh.waiting) {
      status = "Updating ... "
          + formatClockDuration(Math.max(0, now - liveRefresh.waitingSince));
    } else {
      var nextUpdateAt = parseTimestamp(currentUpdatedAt)
          + Math.max(1, currentRefreshSeconds) * 1000;
      status = "Status Check In : "
          + formatClockDuration(Math.max(0, nextUpdateAt - now));
    }
    if (status !== lastRenderedReportStatus) {
      updatedAtLabel.textContent = status;
      lastRenderedReportStatus = status;
    }
  }
  function setLiveUpdateStatus(message, isLastUpdated) {
    if (message === "Finalizing..." && !liveRefresh.waiting) {
      liveRefresh.waiting = true;
      liveRefresh.waitingSince = currentWallTime();
    }
    if (isLastUpdated === true) {
      liveRefresh.waiting = false;
    }
    renderReportStatus(currentWallTime());
  }
  function setExtendedExitVisibility(extendedTime, manualExit, building) {
    var pending = manualExit === true && building === true;
    var available = extendedTime === true && building === true && !pending;
    if (exitExtendedForm) {
      exitExtendedForm.setAttribute("data-visible", String(available || pending));
    }
    if (exitExtendedCommand) {
      exitExtendedCommand.setAttribute("data-visible", String(available));
    }
    if (exitFinalizingStatus) {
      exitFinalizingStatus.setAttribute("data-visible", String(pending));
    }
  }
  function htmlToElement(html) {
    var container = document.createElement("div");
    container.innerHTML = html || "";
    return container.querySelector("#octane-report-zone");
  }
  function replaceTesterRows(contentSelector, rowsSelector, testers, rateProperty) {
    var content = dashboard.querySelector(contentSelector);
    var tableRows = dashboard.querySelector(rowsSelector);
    if (!content || !tableRows) {
      return;
    }
    var rows = Array.isArray(testers) ? testers : [];
    while (tableRows.firstChild) {
      tableRows.removeChild(tableRows.firstChild);
    }
    for (var index = 0; index < rows.length; index += 1) {
      var tester = rows[index] || {};
      var row = document.createElement("tr");
      var email = document.createElement("td");
      email.className = "octane-tester-email";
      email.textContent = tester.email || "Unassigned";
      var rate = document.createElement("td");
      rate.className = "octane-tester-rate";
      rate.textContent = tester[rateProperty] || "0%";
      row.appendChild(email);
      row.appendChild(rate);
      tableRows.appendChild(row);
    }
    content.setAttribute("data-empty", String(rows.length === 0));
  }
  function updateTesterTrackerTitle(title, condition) {
    if (!title) {
      return;
    }
    var fullText = "Testers with LESS THAN " + condition;
    title.setAttribute("aria-label", fullText);
    var conditionElement = title.querySelector("[data-tester-title-condition]");
    if (conditionElement) {
      conditionElement.textContent = condition;
      return;
    }
    title.textContent = fullText;
  }
  function updateTesterDetails(payload) {
    var details = payload && payload.testerDetails;
    if (!details) {
      return;
    }
    var passRateTesters = Array.isArray(details.passRateTesters)
        ? details.passRateTesters
        : [];
    var executionTesters = Array.isArray(details.executionTesters)
        ? details.executionTesters
        : [];
    var definedScope = Array.isArray(details.definedScope) ? details.definedScope : [];
    var passRateTitle = dashboard.querySelector("[data-tester-pass-rate-title]");
    var executionTitle = dashboard.querySelector("[data-tester-execution-title]");
    updateTesterTrackerTitle(
        passRateTitle,
        String(details.basePassrateFigure || 0)
          + "% Pass Rate ("
          + String(passRateTesters.length)
          + ")");
    updateTesterTrackerTitle(
        executionTitle,
        String(details.baseExecutionFigure || 0)
          + "% Execution ("
          + String(executionTesters.length)
          + ")");
    replaceTesterRows(
        "[data-tester-pass-rate-content]",
        "[data-tester-pass-rate-rows]",
        passRateTesters,
        "passRateText");
    replaceTesterRows(
        "[data-tester-execution-content]",
        "[data-tester-execution-rows]",
        executionTesters,
        "executionRateText");
    replaceDefinedScopeRows(definedScope);
  }
  function replaceDefinedScopeRows(scopes) {
    var content = dashboard.querySelector("[data-defined-scope-content]");
    var tableRows = dashboard.querySelector("[data-defined-scope-rows]");
    if (!content || !tableRows) {
      return;
    }
    while (tableRows.firstChild) {
      tableRows.removeChild(tableRows.firstChild);
    }
    for (var index = 0; index < scopes.length; index += 1) {
      var scope = scopes[index] || {};
      var row = document.createElement("tr");
      var project = document.createElement("td");
      project.className = "octane-scope-project";
      project.textContent = scope.project || "";
      var owner = document.createElement("td");
      owner.className = "octane-scope-owner";
      owner.textContent = scope.owner || "-";
      row.appendChild(project);
      row.appendChild(owner);
      tableRows.appendChild(row);
    }
    content.setAttribute("data-empty", String(scopes.length === 0));
  }
  function setTesterDetailsExpanded(zone, expanded) {
    if (!zone) {
      return;
    }
    var body = zone.querySelector(".octane-tester-details-body");
    var button = zone.querySelector(".octane-tester-details-toggle");
    if (body) {
      body.hidden = !expanded;
    }
    if (button) {
      var action = expanded ? "Collapse" : "Expand";
      button.setAttribute("aria-expanded", String(expanded));
      button.setAttribute("aria-label", action + " tester details");
      button.setAttribute("title", action + " tester details");
    }
  }
  function createExpandButton() {
    var button = document.createElement("button");
    button.className = "octane-expand-toggle";
    button.type = "button";
    button.setAttribute("aria-label", "Expand widget");
    button.setAttribute("aria-expanded", "false");
    button.setAttribute("title", "Expand widget");
    button.innerHTML =
        '<svg class="octane-action-icon octane-icon-expand" viewBox="0 0 24 24"'
        + ' aria-hidden="true" focusable="false">'
        + '<path d="M8 4H4v4"></path><path d="M16 4h4v4"></path>'
        + '<path d="M20 16v4h-4"></path><path d="M8 20H4v-4"></path></svg>'
        + '<svg class="octane-action-icon octane-icon-collapse" viewBox="0 0 24 24"'
        + ' aria-hidden="true" focusable="false">'
        + '<path d="M9 3v6H3"></path><path d="M3 9l6-6"></path>'
        + '<path d="M15 21v-6h6"></path><path d="M21 15l-6 6"></path></svg>';
    return button;
  }
  function createZoneFocusButton() {
    var button = document.createElement("button");
    button.className = "octane-zone-focus-toggle";
    button.type = "button";
    button.setAttribute("aria-label", "Expand section");
    button.setAttribute("aria-expanded", "false");
    button.setAttribute("title", "Expand section");
    button.innerHTML =
        '<svg class="octane-action-icon octane-icon-zone-expand" viewBox="0 0 24 24"'
        + ' aria-hidden="true" focusable="false">'
        + '<path d="M8 4H4v4"></path><path d="M16 4h4v4"></path>'
        + '<path d="M20 16v4h-4"></path><path d="M8 20H4v-4"></path></svg>';
    return button;
  }
  function setCardView(card, view) {
    if (!card) {
      return;
    }
    var buttons = card.querySelectorAll(".octane-view-toggle");
    var button = buttons.length > 0 ? buttons[0] : null;
    var targetView =
        button ? button.getAttribute("data-target-view") || "heatmap" : "heatmap";
    var targetLabel =
        button
            ? button.getAttribute("data-target-view-label") || "risk heat map"
            : "risk heat map";
    var nextView = view === targetView ? targetView : "timer";
    card.setAttribute("data-active-view", nextView);
    if (!button) {
      return;
    }
    var label = nextView === targetView ? "Show timer" : "Show " + targetLabel;
    for (var index = 0; index < buttons.length; index += 1) {
      buttons[index].setAttribute("aria-label", label);
      buttons[index].setAttribute("title", label);
    }
  }
  function activeCardView(card) {
    return card ? card.getAttribute("data-active-view") || "timer" : "timer";
  }
  function toggleCardView(card) {
    var button = card ? card.querySelector(".octane-view-toggle") : null;
    var targetView = button ? button.getAttribute("data-target-view") || "heatmap" : "heatmap";
    var nextView = activeCardView(card) === targetView ? "timer" : targetView;
    setCardView(card, nextView);
    if (nextView === "defects") {
      scheduleDefectTrendAnimation();
    }
  }
  function initializeCardViews(root) {
    var cards = root.querySelectorAll("[data-active-view]");
    for (var index = 0; index < cards.length; index += 1) {
      setCardView(cards[index], activeCardView(cards[index]));
    }
  }
  function setDefectAnalyticsView(container, view) {
    if (!container) {
      return;
    }
    var nextView = view === "density" ? "density" : "volumes";
    container.setAttribute("data-active-defect-view", nextView);
    var card = container.closest(".octane-chart-card");
    var toggleRoot = card || container;
    var toggles = toggleRoot.querySelectorAll("[data-defect-target-view]");
    var selectedButton = null;
    for (var index = 0; index < toggles.length; index += 1) {
      var selected = toggles[index].getAttribute("data-defect-target-view") === nextView;
      toggles[index].setAttribute("aria-selected", String(selected));
      if (selected) {
        selectedButton = toggles[index];
      }
    }
    if (card && selectedButton) {
      var title = card.querySelector("[data-defect-view-title]");
      var subtitle = card.querySelector("[data-defect-view-subtitle]");
      if (title) {
        title.textContent =
            selectedButton.getAttribute("data-defect-title") || "Execution Defect Rate";
      }
      if (subtitle) {
        subtitle.textContent =
            selectedButton.getAttribute("data-defect-subtitle")
            || "Defect Arrival vs. Resolution Trend Analysis";
      }
    }
    if (nextView === "density") {
      renderDefectDensity(currentWallTime());
    } else {
      renderDefectTrend(currentWallTime());
    }
    scheduleDefectTrendAnimation();
  }
  function activeDefectAnalyticsView() {
    var container = dashboard.querySelector("[data-defect-analytics]");
    return container
        ? container.getAttribute("data-active-defect-view") || "volumes"
        : "volumes";
  }
  function initializeDefectAnalyticsViews(root) {
    var containers = root.querySelectorAll("[data-defect-analytics]");
    for (var index = 0; index < containers.length; index += 1) {
      setDefectAnalyticsView(
          containers[index],
          containers[index].getAttribute("data-active-defect-view") || "volumes");
    }
  }
  function updateRiskHeatMap(payload) {
    if (!payload
        || typeof payload.riskHeatMapHtml !== "string"
        || !payload.riskHeatMapHtml.trim()) {
      return;
    }
    var terminalUpdate = payload.finalizing === true || payload.building === false;
    if (terminalUpdate && payload.riskHeatMapPopulated !== true) {
      if (heatMapPanel && lastViableRiskHeatMapHtml) {
        heatMapPanel.innerHTML = lastViableRiskHeatMapHtml;
      }
      return;
    }
    if (heatMapPanel) {
      heatMapPanel.innerHTML = payload.riskHeatMapHtml;
      if (payload.riskHeatMapPopulated === true) {
        lastViableRiskHeatMapHtml = payload.riskHeatMapHtml;
        dashboard.setAttribute("data-risk-heat-map-populated", "true");
      }
    }
  }
  /* OCTANE_TEST_METRIC_LABELS_START */
  var testMetricSegmentResizeObserver = null;
  var testMetricSegmentResizeFallbackBound = false;
  var testMetricSegmentLabelScheduled = false;
  function fitTestMetricSegmentLabels(root) {
    var scope = root || dashboard;
    var segments = scope.querySelectorAll("[data-test-metric-segment]");
    for (var index = 0; index < segments.length; index += 1) {
      var segment = segments[index];
      var label = segment.querySelector(
          ".octane-test-metric-segment-label, .octane-test-metric-defect-label");
      if (!label) {
        continue;
      }
      var fullLabel = segment.getAttribute("data-full-label") || "";
      var shortLabel = segment.getAttribute("data-short-label") || fullLabel;
      label.textContent = fullLabel;
      label.title = fullLabel;
      var labelWidth = label.getBoundingClientRect().width;
      var segmentWidth = segment.getBoundingClientRect().width;
      var availableWidth = Math.max(0, labelWidth || segmentWidth);
      if (availableWidth > 0 && label.scrollWidth > availableWidth + 0.5) {
        label.textContent = shortLabel;
      }
    }
  }
  function scheduleTestMetricSegmentLabels(root) {
    if (testMetricSegmentLabelScheduled) {
      return;
    }
    testMetricSegmentLabelScheduled = true;
    requestFrame(function () {
      testMetricSegmentLabelScheduled = false;
      fitTestMetricSegmentLabels(root || dashboard);
    });
  }
  function initializeTestMetricSegments(root) {
    var scope = root || dashboard;
    var containers = scope.querySelectorAll("[data-test-metric-segments]");
    if (typeof window.ResizeObserver === "function") {
      if (testMetricSegmentResizeObserver) {
        testMetricSegmentResizeObserver.disconnect();
      }
      testMetricSegmentResizeObserver = new window.ResizeObserver(function () {
        scheduleTestMetricSegmentLabels(dashboard);
      });
      for (var index = 0; index < containers.length; index += 1) {
        testMetricSegmentResizeObserver.observe(containers[index]);
      }
    } else if (!testMetricSegmentResizeFallbackBound) {
      window.addEventListener("resize", function () {
        scheduleTestMetricSegmentLabels(dashboard);
      });
      testMetricSegmentResizeFallbackBound = true;
    }
    scheduleTestMetricSegmentLabels(scope);
  }
  /* OCTANE_TEST_METRIC_LABELS_END */
  function updateTestMetrics(payload) {
    if (!payload || typeof payload.testMetricsHtml !== "string") {
      return;
    }
    var panel = dashboard.querySelector("[data-test-metrics-panel]");
    if (panel) {
      panel.innerHTML = payload.testMetricsHtml;
      initializeTestMetricSegments(panel);
    }
  }
  /* OCTANE_ACTIVITY_RING_LAYOUT_START */
  var activityRingResizeObserver = null;
  var activityRingResizeScheduled = false;
  var activityRingResizeFallbackBound = false;
  function fitActivityRingLegend(component) {
    if (!component) {
      return;
    }
    var legend = component.querySelector(".octane-activity-side-legend");
    var graphic = component.querySelector(".octane-activity-rings-svg");
    if (!legend || !graphic || component.getBoundingClientRect().width < 576) {
      component.setAttribute("data-side-legend-visible", "false");
      return;
    }
    component.setAttribute("data-side-legend-visible", "true");
    var componentRect = component.getBoundingClientRect();
    var graphicRect = graphic.getBoundingClientRect();
    var legendRect = legend.getBoundingClientRect();
    var clearance = 8;
    var intersectsHorizontally =
        graphicRect.left < legendRect.right + clearance
        && graphicRect.right + clearance > legendRect.left;
    var intersectsVertically =
        graphicRect.top < legendRect.bottom + clearance
        && graphicRect.bottom + clearance > legendRect.top;
    var contained =
        legendRect.left >= componentRect.left
        && legendRect.right <= componentRect.right
        && legendRect.top >= componentRect.top
        && legendRect.bottom <= componentRect.bottom;
    component.setAttribute(
        "data-side-legend-visible",
        String(contained && !(intersectsHorizontally && intersectsVertically)));
  }
  function fitActivityRingLegends() {
    activityRingResizeScheduled = false;
    var components = dashboard.querySelectorAll("[data-activity-rings]");
    for (var index = 0; index < components.length; index += 1) {
      fitActivityRingLegend(components[index]);
    }
  }
  function scheduleActivityRingLayout() {
    if (activityRingResizeScheduled) {
      return;
    }
    activityRingResizeScheduled = true;
    requestFrame(fitActivityRingLegends);
  }
  function initializeActivityRingLayout() {
    if (activityRingResizeObserver) {
      activityRingResizeObserver.disconnect();
      activityRingResizeObserver = null;
    }
    var components = dashboard.querySelectorAll("[data-activity-rings]");
    if (typeof window.ResizeObserver === "function") {
      activityRingResizeObserver =
          new window.ResizeObserver(scheduleActivityRingLayout);
      for (var index = 0; index < components.length; index += 1) {
        activityRingResizeObserver.observe(components[index]);
      }
    } else if (!activityRingResizeFallbackBound) {
      window.addEventListener("resize", scheduleActivityRingLayout);
      activityRingResizeFallbackBound = true;
    }
    scheduleActivityRingLayout();
  }
  /* OCTANE_ACTIVITY_RING_LAYOUT_END */
  /* OCTANE_ACTIVITY_RING_UPDATE_START */
  function activityRingRate(component, key, value) {
    var parsed = value === null || value === undefined || value === ""
        ? NaN
        : Number(value);
    if (!isFinite(parsed)) {
      parsed = Number(component.getAttribute("data-activity-value-" + key));
    }
    return isFinite(parsed) ? clamp(parsed, 0, 100) : 0;
  }
  function updateActivityRings(payload) {
    var component = dashboard.querySelector("[data-activity-rings]");
    if (!component || !payload) {
      return;
    }
    var automation = payload.testMetrics
        ? payload.testMetrics.automationPercentage
        : null;
    var rates = {
      automation: activityRingRate(component, "automation", automation),
      execution: activityRingRate(component, "execution", payload.executionProgress),
      pass: activityRingRate(component, "pass", payload.passRateProgress)
    };
    var activityFace = component.closest(".octane-flip-face") || component;
    var labels = [];
    var accessibleNames = {
      automation: "automation usage",
      execution: "execution rate",
      pass: "pass rate"
    };
    ["execution", "pass", "automation"].forEach(function (key) {
      var rate = rates[key];
      var text = rate.toFixed(2) + "%";
      component.setAttribute("data-activity-value-" + key, trimNumber(rate));
      var ring = component.querySelector('[data-activity-ring="' + key + '"]');
      var labelsForRate =
          activityFace.querySelectorAll('[data-activity-rate="' + key + '"]');
      if (ring) {
        ring.setAttribute("stroke-dasharray", trimNumber(rate) + " 100");
      }
      for (var labelIndex = 0; labelIndex < labelsForRate.length; labelIndex += 1) {
        labelsForRate[labelIndex].textContent = text;
      }
      labels.push(accessibleNames[key] + " " + text);
    });
    var graphic = component.querySelector(".octane-activity-rings-svg");
    if (graphic) {
      graphic.setAttribute("aria-label", labels.join(", "));
    }
    var inlineLegend =
        activityFace.querySelector("[data-activity-inline-legend]");
    if (inlineLegend) {
      inlineLegend.setAttribute("aria-label", labels.join(", "));
    }
    scheduleActivityRingLayout();
  }
  /* OCTANE_ACTIVITY_RING_UPDATE_END */
  function updateExecutionStatusDistribution(payload) {
    if (!payload || typeof payload.executionStatusDistributionHtml !== "string") {
      return;
    }
    var panel = dashboard.querySelector("[data-execution-breakdown-panel]");
    if (panel) {
      panel.innerHTML = payload.executionStatusDistributionHtml;
      initializeExecutionBreakdownScaling(panel);
    }
  }
  var executionBreakdownResizeObserver = null;
  var executionBreakdownResizeScheduled = false;
  var executionBreakdownResizeFallbackBound = false;
  var executionBreakdownRoots = [];
  /* OCTANE_EXECUTION_BREAKDOWN_MATH_START */
  function executionBreakdownDimensions(
      availableHeight, availableWidth, listHeight, gap) {
    var chartHeight = Math.max(
        0, Math.floor(availableHeight - listHeight - gap));
    return {
      chartHeight: chartHeight,
      contentWidth: Math.max(
          0, Math.min(availableWidth * 0.92, chartHeight * 2, 1280))
    };
  }
  /* OCTANE_EXECUTION_BREAKDOWN_MATH_END */
  function fitExecutionBreakdown(root) {
    var breakdown = root.querySelector
        ? root.querySelector(".octane-execution-breakdown")
        : null;
    var content = breakdown
        ? breakdown.querySelector(".octane-execution-breakdown-content")
        : null;
    var list = content
        ? content.querySelector(".octane-execution-breakdown-list")
        : null;
    if (!breakdown || !content || !list) {
      return;
    }
    var availableHeight = Math.max(0, breakdown.clientHeight);
    var availableWidth = Math.max(0, breakdown.clientWidth);
    if (availableHeight <= 0 || availableWidth <= 0) {
      return;
    }
    var contentStyle = window.getComputedStyle(content);
    var gap = parseFloat(contentStyle.rowGap || contentStyle.gap || "0") || 0;
    var listHeight = Math.ceil(list.scrollHeight);
    var dimensions = executionBreakdownDimensions(
        availableHeight, availableWidth, listHeight, gap);
    content.style.setProperty(
        "--octane-execution-chart-height", dimensions.chartHeight + "px");
    content.style.setProperty(
        "--octane-execution-content-width", dimensions.contentWidth + "px");
  }
  function fitAllExecutionBreakdowns() {
    executionBreakdownResizeScheduled = false;
    for (var index = 0; index < executionBreakdownRoots.length; index += 1) {
      fitExecutionBreakdown(executionBreakdownRoots[index]);
    }
  }
  function scheduleExecutionBreakdownScaling() {
    if (executionBreakdownResizeScheduled) {
      return;
    }
    executionBreakdownResizeScheduled = true;
    requestFrame(fitAllExecutionBreakdowns);
  }
  function initializeExecutionBreakdownScaling(root) {
    if (executionBreakdownResizeObserver) {
      executionBreakdownResizeObserver.disconnect();
      executionBreakdownResizeObserver = null;
    }
    executionBreakdownRoots = root && root.querySelectorAll
        ? Array.prototype.slice.call(
            root.querySelectorAll("[data-execution-breakdown-panel]"))
        : [];
    if (root && root.matches && root.matches("[data-execution-breakdown-panel]")) {
      executionBreakdownRoots.unshift(root);
    }
    scheduleExecutionBreakdownScaling();
    if (typeof window.ResizeObserver === "function") {
      executionBreakdownResizeObserver =
          new window.ResizeObserver(scheduleExecutionBreakdownScaling);
      for (var index = 0; index < executionBreakdownRoots.length; index += 1) {
        executionBreakdownResizeObserver.observe(executionBreakdownRoots[index]);
      }
    } else if (!executionBreakdownResizeFallbackBound) {
      executionBreakdownResizeFallbackBound = true;
      window.addEventListener("resize", scheduleExecutionBreakdownScaling);
    }
  }
  var SVG_NAMESPACE = "http://www.w3.org/2000/svg";
  var DEFECT_TREND_BOUNDS = {
    left: 0,
    right: 1000,
    top: 0,
    bottom: 434
  };
  var DEFECT_DENSITY_BOUNDS = {
    left: 80,
    right: 920,
    top: 0,
    bottom: 360
  };
  var defectTrendState = null;
  var defectTrendLoopRunning = false;
  var defectDensityResizeObserver = null;
  var defectDensityResizeScheduled = false;
  var defectDensityResizeFallbackBound = false;
  function safeTrendNumber(value, fallback) {
    var parsed = Number(value);
    return isFinite(parsed) ? parsed : fallback;
  }
  function readDefectTrendPoints(panel) {
    var points = [];
    var sources = panel ? panel.querySelectorAll("[data-defect-trend-point]") : [];
    for (var index = 0; index < sources.length; index += 1) {
      points.push({
        elapsedMillis: Math.max(
            0, safeTrendNumber(sources[index].getAttribute("data-elapsed-millis"), 0)),
        opened: Math.max(0, safeTrendNumber(sources[index].getAttribute("data-opened"), 0)),
        closed: Math.max(0, safeTrendNumber(sources[index].getAttribute("data-closed"), 0)),
        executed:
            Math.max(0, safeTrendNumber(sources[index].getAttribute("data-executed"), 0))
      });
    }
    return points.length ? points : [{elapsedMillis: 0, opened: 0, closed: 0, executed: 0}];
  }
  function normalizeDefectTrendPoints(points, durationMillis) {
    var normalized = [];
    if (points && typeof points.length === "number") {
      for (var index = 0; index < points.length; index += 1) {
        normalized.push({
          elapsedMillis: clamp(
              safeTrendNumber(points[index].elapsedMillis, 0), 0, durationMillis),
          opened: Math.max(0, safeTrendNumber(points[index].opened, 0)),
          closed: Math.max(0, safeTrendNumber(points[index].closed, 0)),
          executed: Math.max(0, safeTrendNumber(points[index].executed, 0))
        });
      }
    }
    normalized.sort(function (left, right) {
      return left.elapsedMillis - right.elapsedMillis;
    });
    return normalized.length
        ? normalized
        : [{elapsedMillis: 0, opened: 0, closed: 0, executed: 0}];
  }
  function initializeDefectTrend() {
    var panel = dashboard.querySelector("[data-defect-trend-panel]");
    if (!panel) {
      return;
    }
    var durationMillis = Math.max(
        1, safeTrendNumber(panel.getAttribute("data-duration-millis"), 1));
    defectTrendState = {
      panel: panel,
      startedAt: parseTimestamp(panel.getAttribute("data-started-at")),
      durationMillis: durationMillis,
      building: panel.getAttribute("data-building") === "true",
      points: normalizeDefectTrendPoints(readDefectTrendPoints(panel), durationMillis),
      renderedYMaximum: -1,
      renderedDurationMillis: -1,
      renderedDensityYMaximum: -1,
      renderedDensityDurationMillis: -1,
      renderedDensityWidth: -1
    };
    initializeDefectDensityResizeObserver();
    renderDefectTrend(currentWallTime());
    renderDefectDensity(currentWallTime());
  }
  function renderDefectDensityAfterResize() {
    defectDensityResizeScheduled = false;
    if (defectTrendState) {
      renderDefectDensity(currentWallTime());
    }
  }
  function scheduleDefectDensityResize() {
    if (defectDensityResizeScheduled) {
      return;
    }
    defectDensityResizeScheduled = true;
    requestFrame(renderDefectDensityAfterResize);
  }
  function initializeDefectDensityResizeObserver() {
    if (defectDensityResizeObserver) {
      defectDensityResizeObserver.disconnect();
      defectDensityResizeObserver = null;
    }
    var plot = dashboard.querySelector(".octane-defect-density-plot");
    if (!plot) {
      return;
    }
    if (typeof window.ResizeObserver === "function") {
      defectDensityResizeObserver =
          new window.ResizeObserver(scheduleDefectDensityResize);
      defectDensityResizeObserver.observe(plot);
    } else if (!defectDensityResizeFallbackBound) {
      defectDensityResizeFallbackBound = true;
      window.addEventListener("resize", scheduleDefectDensityResize);
    }
  }
  function defectTrendVisible() {
    var card = dashboard.querySelector('[data-card-key="progress-pass-rate"]');
    return card
        && activeCardView(card) === "defects"
        && activeDefectAnalyticsView() === "volumes";
  }
  function defectDensityVisible() {
    var card = dashboard.querySelector('[data-card-key="progress-pass-rate"]');
    return card
        && activeCardView(card) === "defects"
        && activeDefectAnalyticsView() === "density";
  }
  function defectAnalyticsVisible() {
    var card = dashboard.querySelector('[data-card-key="progress-pass-rate"]');
    return card && activeCardView(card) === "defects";
  }
  function latestDefectTrendPoint() {
    if (!defectTrendState || !defectTrendState.points.length) {
      return {elapsedMillis: 0, opened: 0, closed: 0, executed: 0};
    }
    return defectTrendState.points[defectTrendState.points.length - 1];
  }
  /* OCTANE_DEFECT_VOLUME_MATH_START */
  function activeOpenDefectCount(raised, closed) {
    return Math.max(
        0,
        safeTrendNumber(raised, 0) - safeTrendNumber(closed, 0));
  }
  function niceDefectTrendScale(maximumCount) {
    var safeMaximum = Number(maximumCount);
    if (!isFinite(safeMaximum) || safeMaximum < 0) {
      safeMaximum = 0;
    }
    var maximum = Math.ceil(safeMaximum) + 1;
    var intervals = maximum <= 5 ? maximum : 5;
    return {
      maximum: maximum,
      step: maximum / intervals,
      intervals: intervals
    };
  }
  function defectTrendYAxisValues(scale) {
    var intervals = Math.max(1, Math.round(scale.intervals || 1));
    var values = [];
    for (var tick = intervals; tick >= 0; tick -= 1) {
      values.push(scale.step * tick);
    }
    return values;
  }
  /* OCTANE_DEFECT_VOLUME_MATH_END */
  function formatDefectTrendTime(milliseconds) {
    var totalSeconds = Math.max(0, Math.round(milliseconds / 1000));
    if (totalSeconds < 60) {
      return totalSeconds + "s";
    }
    var totalMinutes = Math.round(totalSeconds / 60);
    if (totalMinutes < 60) {
      return totalMinutes + "m";
    }
    var hours = Math.floor(totalMinutes / 60);
    var minutes = totalMinutes % 60;
    return minutes ? hours + "h " + minutes + "m" : hours + "h";
  }
  function formatClockOffset(timestampMillis) {
    var date = new Date(timestampMillis);
    if (isNaN(date.getTime())) {
      return "";
    }
    var hours = String(date.getHours()).padStart(2, "0");
    var minutes = String(date.getMinutes()).padStart(2, "0");
    return hours + ":" + minutes;
  }
  function createTrendSvgElement(name, attributes, textValue) {
    var element = document.createElementNS(SVG_NAMESPACE, name);
    for (var key in attributes) {
      if (Object.prototype.hasOwnProperty.call(attributes, key)) {
        element.setAttribute(key, attributes[key]);
      }
    }
    if (typeof textValue === "string") {
      element.textContent = textValue;
    }
    return element;
  }
  function createTrendAxisLabel(value) {
    var label = document.createElement("span");
    label.className = "octane-defect-trend-axis-label";
    label.textContent = value;
    return label;
  }
  function createDensityAxisLabel(value) {
    var label = document.createElement("span");
    label.className = "octane-defect-density-axis-label";
    label.textContent = value;
    return label;
  }
  function effectiveDefectTrendPoints(now) {
    if (!defectTrendState) {
      return [{elapsedMillis: 0, opened: 0, closed: 0, executed: 0}];
    }
    var points = defectTrendState.points.slice();
    var latest = latestDefectTrendPoint();
    var elapsedMillis = latest.elapsedMillis;
    if (defectTrendState.building) {
      elapsedMillis = Math.max(
          elapsedMillis,
          clamp(now - defectTrendState.startedAt, 0, defectTrendState.durationMillis));
    }
    if (elapsedMillis > latest.elapsedMillis) {
      points.push({
        elapsedMillis: elapsedMillis,
        opened: latest.opened,
        closed: latest.closed,
        executed: latest.executed
      });
    }
    return points;
  }
  function rebuildDefectTrendAxes(scale) {
    if (!defectTrendState) {
      return;
    }
    var panel = defectTrendState.panel;
    var grid = panel.querySelector("[data-defect-trend-grid]");
    var yLabels = panel.querySelector("[data-defect-trend-y-labels]");
    var xLabels = panel.querySelector("[data-defect-trend-x-labels]");
    if (!grid || !yLabels || !xLabels) {
      return;
    }
    grid.textContent = "";
    yLabels.textContent = "";
    xLabels.textContent = "";
    var height = DEFECT_TREND_BOUNDS.bottom - DEFECT_TREND_BOUNDS.top;
    var intervals = Math.max(1, Math.round(scale.intervals || 1));
    for (var tick = 1; tick <= intervals; tick += 1) {
      var value = scale.step * tick;
      var y = DEFECT_TREND_BOUNDS.bottom - (value / scale.maximum) * height;
      grid.appendChild(createTrendSvgElement("line", {
        "class": "octane-defect-trend-grid-line",
        x1: DEFECT_TREND_BOUNDS.left,
        y1: y,
        x2: DEFECT_TREND_BOUNDS.right,
        y2: y
      }));
    }
    var yAxisValues = defectTrendYAxisValues(scale);
    for (var yIndex = 0; yIndex < yAxisValues.length; yIndex += 1) {
      yLabels.appendChild(createTrendAxisLabel(trimNumber(yAxisValues[yIndex])));
    }
    for (var index = 0; index <= 4; index += 1) {
      var ratio = index / 4;
      xLabels.appendChild(
          createTrendAxisLabel(
              formatDefectTrendTime(defectTrendState.durationMillis * ratio)));
    }
    defectTrendState.renderedYMaximum = scale.maximum;
    defectTrendState.renderedDurationMillis = defectTrendState.durationMillis;
  }
  /* OCTANE_DEFECT_DENSITY_MATH_START */
  function niceDefectDensityScale(maximumDensity) {
    var safeMaximum = Number(maximumDensity);
    if (!isFinite(safeMaximum) || safeMaximum <= 0) {
      return {maximum: 1, step: 1, intervals: 1};
    }
    var maximum = Math.ceil(safeMaximum) + 1;
    var intervals = maximum <= 5 ? maximum : 5;
    return {
      maximum: maximum,
      step: maximum / intervals,
      intervals: intervals
    };
  }
  function formatDensityAxisValue(value) {
    return trimNumber(value);
  }
  function defectDensityYAxisValues(scale) {
    var intervals = Math.max(1, Math.round(scale.intervals || 1));
    var values = [];
    for (var tick = intervals; tick >= 0; tick -= 1) {
      values.push(scale.step * tick);
    }
    return values;
  }
  function densityXAxisIntervalCount(plotWidth) {
    var safeWidth = Number(plotWidth);
    if (!isFinite(safeWidth) || safeWidth <= 0) {
      return 4;
    }
    return clamp(Math.floor(safeWidth / 180), 2, 5);
  }
  function formatDensityClockOffset(timestampMillis, durationMillis) {
    var date = new Date(timestampMillis);
    if (isNaN(date.getTime())) {
      return "";
    }
    var hours = String(date.getHours()).padStart(2, "0");
    var minutes = String(date.getMinutes()).padStart(2, "0");
    if (durationMillis <= 300000) {
      return hours + ":" + minutes + ":" + String(date.getSeconds()).padStart(2, "0");
    }
    return hours + ":" + minutes;
  }
  function defectDensityXAxisValues(startedAt, durationMillis, plotWidth) {
    var intervals = densityXAxisIntervalCount(plotWidth);
    var values = [];
    for (var tick = 0; tick <= intervals; tick += 1) {
      values.push(
          formatDensityClockOffset(
              startedAt + durationMillis * (tick / intervals), durationMillis));
    }
    return values;
  }
  function densityBucketMillis(durationMillis) {
    var target = Math.max(1, Math.round(durationMillis / 10));
    var friendly = [15000, 30000, 60000, 120000, 300000, 600000, 900000];
    for (var index = 0; index < friendly.length; index += 1) {
      if (target <= friendly[index]) {
        return friendly[index];
      }
    }
    return 900000;
  }
  function defectPointAt(points, elapsedMillis) {
    var selected = points.length
        ? points[0]
        : {elapsedMillis: 0, opened: 0, closed: 0, executed: 0};
    for (var index = 0; index < points.length; index += 1) {
      if (points[index].elapsedMillis > elapsedMillis) {
        return selected;
      }
      selected = points[index];
    }
    return selected;
  }
  function buildDefectDensityBuckets(points, durationMillis, elapsedLimitMillis) {
    var safeDuration = Math.max(1, durationMillis);
    var bucketMillis = densityBucketMillis(safeDuration);
    var visibleLimit = clamp(elapsedLimitMillis, 0, safeDuration);
    var buckets = [];
    for (var start = 0; start < safeDuration && start < visibleLimit; start += bucketMillis) {
      var end = Math.min(safeDuration, start + bucketMillis, visibleLimit);
      var startPoint = defectPointAt(points, start);
      var endPoint = defectPointAt(points, end);
      var newDefects = Math.max(0, endPoint.opened - startPoint.opened);
      var executedTests = Math.max(0, endPoint.executed - startPoint.executed);
      buckets.push({
        startMillis: start,
        endMillis: end,
        newDefects: newDefects,
        executedTests: executedTests,
        density: executedTests === 0 ? newDefects : newDefects / executedTests,
        zeroTestSpike: newDefects > 0 && executedTests === 0
      });
    }
    return buckets;
  }
  function rebuildDefectDensityAxes(scale, plotWidth) {
    if (!defectTrendState) {
      return;
    }
    var panel = dashboard.querySelector("[data-defect-density-panel]");
    if (!panel) {
      return;
    }
    var grid = panel.querySelector("[data-defect-density-grid]");
    var yLabels = panel.querySelector("[data-defect-density-y-labels]");
    var xLabels = panel.querySelector("[data-defect-density-x-labels]");
    if (!grid || !yLabels || !xLabels) {
      return;
    }
    grid.textContent = "";
    yLabels.textContent = "";
    xLabels.textContent = "";
    var height = DEFECT_DENSITY_BOUNDS.bottom - DEFECT_DENSITY_BOUNDS.top;
    var intervals = Math.max(1, Math.round(scale.intervals || 1));
    for (var tick = 1; tick <= intervals; tick += 1) {
      var value = scale.step * tick;
      var y = DEFECT_DENSITY_BOUNDS.bottom - (value / scale.maximum) * height;
      grid.appendChild(createTrendSvgElement("line", {
        "class": "octane-defect-density-grid-line",
        x1: DEFECT_DENSITY_BOUNDS.left,
        y1: y,
        x2: DEFECT_DENSITY_BOUNDS.right,
        y2: y
      }));
    }
    var yAxisValues = defectDensityYAxisValues(scale);
    for (var yIndex = 0; yIndex < yAxisValues.length; yIndex += 1) {
      yLabels.appendChild(
          createDensityAxisLabel(formatDensityAxisValue(yAxisValues[yIndex])));
    }
    var xAxisValues = defectDensityXAxisValues(
        defectTrendState.startedAt, defectTrendState.durationMillis, plotWidth);
    for (var index = 0; index < xAxisValues.length; index += 1) {
      xLabels.appendChild(createDensityAxisLabel(xAxisValues[index]));
    }
    defectTrendState.renderedDensityYMaximum = scale.maximum;
    defectTrendState.renderedDensityDurationMillis = defectTrendState.durationMillis;
    defectTrendState.renderedDensityWidth = plotWidth;
  }
  function densityXFor(milliseconds) {
    var width = DEFECT_DENSITY_BOUNDS.right - DEFECT_DENSITY_BOUNDS.left;
    var durationMillis = Math.max(1, defectTrendState.durationMillis);
    var elapsedMillis = clamp(milliseconds, 0, durationMillis);
    return DEFECT_DENSITY_BOUNDS.left
        + (elapsedMillis / durationMillis) * width;
  }
  function densityYFor(value, scale) {
    var height = DEFECT_DENSITY_BOUNDS.bottom - DEFECT_DENSITY_BOUNDS.top;
    var ratio = clamp(value / Math.max(1, scale.maximum), 0, 1);
    return DEFECT_DENSITY_BOUNDS.bottom - ratio * height;
  }
  function defectDensityLinePath(buckets, scale) {
    if (!buckets.length) {
      return "";
    }
    var first = buckets[0];
    var firstY = densityYFor(first.density, scale);
    var path = "M " + densityXFor(first.startMillis).toFixed(2)
        + " " + firstY.toFixed(2);
    for (var index = 0; index < buckets.length; index += 1) {
      var y = densityYFor(buckets[index].density, scale);
      var startX = densityXFor(buckets[index].startMillis);
      var endX = densityXFor(buckets[index].endMillis);
      if (index > 0) {
        path += " H " + startX.toFixed(2) + " V " + y.toFixed(2);
      }
      path += " H " + endX.toFixed(2);
    }
    return path;
  }
  function defectDensityAreaPath(buckets, scale) {
    if (!buckets.length) {
      return "";
    }
    var firstX = densityXFor(buckets[0].startMillis);
    var firstY = densityYFor(buckets[0].density, scale);
    var path = "M " + firstX.toFixed(2) + " "
        + DEFECT_DENSITY_BOUNDS.bottom.toFixed(2)
        + " L " + firstX.toFixed(2) + " " + firstY.toFixed(2);
    for (var index = 0; index < buckets.length; index += 1) {
      var y = densityYFor(buckets[index].density, scale);
      var startX = densityXFor(buckets[index].startMillis);
      var endX = densityXFor(buckets[index].endMillis);
      if (index > 0) {
        path += " H " + startX.toFixed(2) + " V " + y.toFixed(2);
      }
      path += " H " + endX.toFixed(2);
    }
    var lastX = densityXFor(buckets[buckets.length - 1].endMillis);
    path += " L " + lastX.toFixed(2) + " "
        + DEFECT_DENSITY_BOUNDS.bottom.toFixed(2) + " Z";
    return path;
  }
  /* OCTANE_DEFECT_DENSITY_MATH_END */
  function defectTrendPath(points, property, scale) {
    if (!points.length) {
      return "";
    }
    var width = DEFECT_TREND_BOUNDS.right - DEFECT_TREND_BOUNDS.left;
    var height = DEFECT_TREND_BOUNDS.bottom - DEFECT_TREND_BOUNDS.top;
    function xFor(point) {
      return DEFECT_TREND_BOUNDS.left
          + (point.elapsedMillis / defectTrendState.durationMillis) * width;
    }
    function yFor(point) {
      return DEFECT_TREND_BOUNDS.bottom - (point[property] / scale.maximum) * height;
    }
    var path = "M " + xFor(points[0]).toFixed(2) + " " + yFor(points[0]).toFixed(2);
    for (var index = 1; index < points.length; index += 1) {
      path += " H " + xFor(points[index]).toFixed(2)
          + " V " + yFor(points[index]).toFixed(2);
    }
    return path;
  }
  function renderDefectTrend(now) {
    if (!defectTrendState || !defectTrendState.panel) {
      return;
    }
    var points = effectiveDefectTrendPoints(now);
    var maximumCount = 0;
    for (var index = 0; index < points.length; index += 1) {
      maximumCount = Math.max(maximumCount, points[index].opened, points[index].closed);
    }
    var scale = niceDefectTrendScale(maximumCount);
    if (scale.maximum !== defectTrendState.renderedYMaximum
        || defectTrendState.durationMillis !== defectTrendState.renderedDurationMillis) {
      rebuildDefectTrendAxes(scale);
    }
    var openedLine =
        defectTrendState.panel.querySelector("[data-defect-trend-opened-line]");
    var closedLine =
        defectTrendState.panel.querySelector("[data-defect-trend-closed-line]");
    if (openedLine) {
      openedLine.setAttribute("d", defectTrendPath(points, "opened", scale));
    }
    if (closedLine) {
      closedLine.setAttribute("d", defectTrendPath(points, "closed", scale));
    }
  }
  function renderDefectDensity(now) {
    if (!defectTrendState) {
      return;
    }
    var panel = dashboard.querySelector("[data-defect-density-panel]");
    if (!panel) {
      return;
    }
    var points = effectiveDefectTrendPoints(now);
    var latest = points.length
        ? points[points.length - 1]
        : {elapsedMillis: 0, opened: 0, closed: 0, executed: 0};
    var buckets =
        buildDefectDensityBuckets(
            points, defectTrendState.durationMillis, latest.elapsedMillis);
    var maximumDensity = 0;
    for (var index = 0; index < buckets.length; index += 1) {
      maximumDensity = Math.max(maximumDensity, buckets[index].density);
    }
    var scale = niceDefectDensityScale(maximumDensity);
    var plot = panel.querySelector(".octane-defect-density-plot");
    var plotWidth = plot ? plot.getBoundingClientRect().width : 0;
    if (!plotWidth) {
      plotWidth = DEFECT_DENSITY_BOUNDS.right - DEFECT_DENSITY_BOUNDS.left;
    }
    if (scale.maximum !== defectTrendState.renderedDensityYMaximum
        || defectTrendState.durationMillis !== defectTrendState.renderedDensityDurationMillis
        || Math.abs(plotWidth - defectTrendState.renderedDensityWidth) >= 24) {
      rebuildDefectDensityAxes(scale, plotWidth);
    }
    var total = panel.querySelector("[data-defect-density-raised-total]");
    if (total) {
      total.textContent = String(latest.opened);
    }
    var area = panel.querySelector("[data-defect-density-area]");
    var line = panel.querySelector("[data-defect-density-line]");
    if (area) {
      area.setAttribute("d", defectDensityAreaPath(buckets, scale));
    }
    if (line) {
      line.setAttribute("d", defectDensityLinePath(buckets, scale));
    }
  }
  function animateDefectTrend() {
    if (!defectTrendState || !defectTrendState.building || !defectAnalyticsVisible()) {
      defectTrendLoopRunning = false;
      return;
    }
    var now = currentWallTime();
    if (defectTrendVisible()) {
      renderDefectTrend(now);
    }
    if (defectDensityVisible()) {
      renderDefectDensity(now);
    }
    var latest = latestDefectTrendPoint();
    var elapsed = Math.max(
        latest.elapsedMillis,
        currentWallTime() - defectTrendState.startedAt);
    if (elapsed < defectTrendState.durationMillis) {
      scheduleTimerFrame(animateDefectTrend);
      return;
    }
    defectTrendLoopRunning = false;
  }
  function scheduleDefectTrendAnimation() {
    if (defectTrendLoopRunning || !defectTrendState || !defectAnalyticsVisible()) {
      return;
    }
    if (defectTrendVisible()) {
      renderDefectTrend(currentWallTime());
    }
    if (defectDensityVisible()) {
      renderDefectDensity(currentWallTime());
    }
    defectTrendLoopRunning = true;
    scheduleTimerFrame(animateDefectTrend);
  }
  function updateDefectTrend(payload) {
    if (!payload || !payload.defectTrend) {
      return;
    }
    var panel = dashboard.querySelector("[data-defect-trend-panel]");
    if (!panel) {
      return;
    }
    var trend = payload.defectTrend;
    var durationMillis = Math.max(
        1, safeTrendNumber(trend.durationMillis, defectTrendState
            ? defectTrendState.durationMillis : 1));
    var points = normalizeDefectTrendPoints(trend.points, durationMillis);
    var executedTestCount = Number(payload.executedTestCount);
    if (points.length && isFinite(executedTestCount) && executedTestCount >= 0) {
      points[points.length - 1].executed = executedTestCount;
    }
    defectTrendState = {
      panel: panel,
      startedAt: parseTimestamp(trend.startedAt),
      durationMillis: durationMillis,
      building: payload.building === true,
      points: points,
      renderedYMaximum: -1,
      renderedDurationMillis: -1,
      renderedDensityYMaximum: -1,
      renderedDensityDurationMillis: -1,
      renderedDensityWidth: -1
    };
    var latest = latestDefectTrendPoint();
    var openTotal = panel.querySelector("[data-defect-trend-open-total]");
    var closedTotal = panel.querySelector("[data-defect-trend-closed-total]");
    var closedContainer = panel.querySelector("[data-defect-trend-closed]");
    if (openTotal) {
      openTotal.textContent = String(activeOpenDefectCount(latest.opened, latest.closed));
    }
    if (closedTotal) {
      closedTotal.textContent = String(latest.closed);
    }
    if (closedContainer) {
      closedContainer.setAttribute("data-visible", String(latest.opened > 0));
    }
    renderDefectTrend(currentWallTime());
    renderDefectDensity(currentWallTime());
    scheduleDefectTrendAnimation();
  }
  function currentReportPayload() {
    var completionProgressCard =
        dashboard.querySelector('[data-octane-progress="completion"]');
    var completionProgress =
        completionProgressCard
            ? parseFloat(completionProgressCard.getAttribute("data-progress-value") || "0")
            : 0;
    var timeoutCard = dashboard.querySelector('[data-octane-timer="timeout"]');
    var timeoutSeconds = timeoutCard
        ? parseInt(timeoutCard.getAttribute("data-total-seconds") || "0", 10)
        : 0;
    var timeoutExtendedSeconds = timeoutCard
        ? parseInt(timeoutCard.getAttribute("data-extended-total-seconds") || "0", 10)
        : 0;
    return {
      building: dashboard.getAttribute("data-report-building") === "true",
      finalizing: dashboard.getAttribute("data-report-finalizing") === "true",
      extendedTime: dashboard.getAttribute("data-extended-time") === "true",
      manualExitRequested:
          dashboard.getAttribute("data-manual-exit-requested") === "true",
      manualExitRequestedAtMillis: Number(
          dashboard.getAttribute("data-manual-exit-requested-at-millis") || 0),
      completionProgress: isNaN(completionProgress) ? 0 : completionProgress,
      sampleTimeMillis: currentWallTime(),
      startedAt: timeoutCard ? timeoutCard.getAttribute("data-started-at") || "" : "",
      stateLabel: stateLabel ? stateLabel.textContent : "",
      timeoutSeconds: isNaN(timeoutSeconds) ? 0 : timeoutSeconds,
      timeoutExtendedSeconds:
          isNaN(timeoutExtendedSeconds) ? 0 : timeoutExtendedSeconds,
      updatedAt: currentUpdatedAt
    };
  }
  function payloadTimeMillis(payload, key) {
    if (
        key === "sampleTimeMillis"
        && payload
        && typeof payload.sampleTimeMillis === "number") {
      return payload.sampleTimeMillis;
    }
    var parsed = Date.parse(payload && payload[key] ? payload[key] : "");
    return isNaN(parsed) ? currentWallTime() : parsed;
  }
  function manualExitRequested(payload) {
    return payload && payload.manualExitRequested === true;
  }
  function manualExitTimeMillis(payload, fallback) {
    var requestedAt = Number(
        payload && payload.manualExitRequestedAtMillis
            ? payload.manualExitRequestedAtMillis
            : 0);
    if (manualExitRequested(payload) && isFinite(requestedAt) && requestedAt > 0) {
      return requestedAt;
    }
    return manualExitRequested(payload) ? Math.max(0, fallback || 0) : 0;
  }
  function timeoutSampleTimeMillis(payload) {
    if (payload && payload.building !== true) {
      return payloadTimeMillis(payload, "updatedAt");
    }
    return payloadTimeMillis(payload, "sampleTimeMillis");
  }
  function primaryTimeoutReached(payload) {
    var timeoutSeconds =
        Number(payload && payload.timeoutSeconds ? payload.timeoutSeconds : 0);
    if (!payload || !isFinite(timeoutSeconds) || timeoutSeconds <= 0) {
      return false;
    }
    var startedAt = payloadTimeMillis(payload, "startedAt");
    var sampleTime = timeoutSampleTimeMillis(payload);
    return sampleTime - startedAt >= timeoutSeconds * 1000;
  }
  function totalTimeoutReached(payload) {
    var timeoutSeconds =
        Number(payload && payload.timeoutSeconds ? payload.timeoutSeconds : 0);
    var timeoutExtendedSeconds =
        Number(
            payload && payload.timeoutExtendedSeconds
                ? payload.timeoutExtendedSeconds
                : 0);
    if (
        !payload
        || !isFinite(timeoutSeconds)
        || !isFinite(timeoutExtendedSeconds)
        || timeoutSeconds <= 0) {
      return false;
    }
    var startedAt = payloadTimeMillis(payload, "startedAt");
    var sampleTime = timeoutSampleTimeMillis(payload);
    return sampleTime - startedAt
        >= (timeoutSeconds + Math.max(0, timeoutExtendedSeconds)) * 1000;
  }
  function completionProgressReached(payload) {
    var completionProgress = Number(
        payload && payload.completionProgress ? payload.completionProgress : 0);
    return isFinite(completionProgress) && completionProgress >= 100;
  }
  function completionReached(payload) {
    var timedOut =
        payload && payload.building !== true && payload.stateLabel === "Timed out";
    var extendedSeconds = Number(
        payload && payload.timeoutExtendedSeconds
            ? payload.timeoutExtendedSeconds
            : 0);
    if (extendedSeconds > 0) {
      return timedOut || manualExitRequested(payload) || totalTimeoutReached(payload);
    }
    return timedOut || primaryTimeoutReached(payload) || completionProgressReached(payload);
  }
  function autoFlipKey(cardKey, targetView) {
    return cardKey + ":" + targetView;
  }
  function hasAutoFlipped(cardKey, targetView) {
    return completionAutoFlipState[autoFlipKey(cardKey, targetView)] === true;
  }
  function markAutoFlipped(card, cardKey, targetView) {
    completionAutoFlipState[autoFlipKey(cardKey, targetView)] = true;
    dashboard.setAttribute("data-has-auto-flipped", "true");
    if (card) {
      card.setAttribute("data-has-auto-flipped", "true");
    }
  }
  function autoShowCardViewOnce(payload, cardKey, targetView) {
    var card = dashboard.querySelector('[data-card-key="' + cardKey + '"]');
    if (!card || !card.querySelector(".octane-view-toggle")) {
      return null;
    }
    if (!completionReached(payload) || hasAutoFlipped(cardKey, targetView)) {
      return null;
    }
    markAutoFlipped(card, cardKey, targetView);
    if (activeCardView(card) !== targetView) {
      setCardView(card, targetView);
    }
    return card;
  }
  function runCompletionAutoFlips(payload) {
    autoShowHeatMapOnCompletion(payload);
    autoShowTestMetricsOnCompletion(payload);
    autoShowExecutionBreakdownOnCompletion(payload);
    autoShowQaHealthMetricsOnCompletion(payload);
  }
  function autoShowHeatMapOnCompletion(payload) {
    autoShowCardViewOnce(payload, "timer-poll", "heatmap");
  }
  function autoShowTestMetricsOnCompletion(payload) {
    autoShowCardViewOnce(payload, "timer-timeout", "metrics");
  }
  function autoShowExecutionBreakdownOnCompletion(payload) {
    autoShowCardViewOnce(payload, "progress-execution", "breakdown");
  }
  function autoShowQaHealthMetricsOnCompletion(payload) {
    autoShowCardViewOnce(payload, "progress-pass-rate", "metrics");
  }
  function zoneKeyFor(zone) {
    if (!zone) {
      return "";
    }
    if (zone.getAttribute("data-zone-key")) {
      return zone.getAttribute("data-zone-key");
    }
    if (zone.id === "octane-timer-zone") {
      return "timers";
    }
    if (zone.id === "octane-report-zone") {
      return "reports";
    }
    if (zone.id === "octane-test-management-zone") {
      return "test-management";
    }
    return "";
  }
  function decorateCardZones(root) {
    if (!root) {
      return;
    }
    var zones = [];
    if (root.id === "octane-timer-zone"
        || root.id === "octane-test-management-zone"
        || root.id === "octane-report-zone") {
      zones.push(root);
    }
    var nestedZones = root.querySelectorAll
        ? root.querySelectorAll(
            "#octane-timer-zone, #octane-test-management-zone, #octane-report-zone")
        : [];
    for (var nestedIndex = 0; nestedIndex < nestedZones.length; nestedIndex += 1) {
      zones.push(nestedZones[nestedIndex]);
    }
    for (var index = 0; index < zones.length; index += 1) {
      var zone = zones[index];
      var zoneKey = zoneKeyFor(zone);
      if (zoneKey) {
        zone.setAttribute("data-zone-key", zoneKey);
      }
      if (!zone.querySelector(".octane-zone-focus-toggle")) {
        zone.insertBefore(createZoneFocusButton(), zone.firstChild);
      }
    }
  }
  function removeZoneFocusButton(zone) {
    var button = zone ? zone.querySelector(".octane-zone-focus-toggle") : null;
    if (button) {
      button.remove();
    }
  }
  function restoreZoneFocusButton(zone) {
    if (!zone || zone.querySelector(".octane-zone-focus-toggle")) {
      return;
    }
    zone.insertBefore(createZoneFocusButton(), zone.firstChild);
  }
  /* OCTANE_FLUID_BAR_CHART_START */
  var FLUID_BAR_MIN_WIDTH = 8;
  var FLUID_BAR_MAX_WIDTH = 100;
  var FLUID_BAR_MIN_GAP = 2;
  var FLUID_BAR_MAX_GAP = 40;
  var FLUID_BAR_SLOT_WIDTH = FLUID_BAR_MIN_WIDTH + FLUID_BAR_MIN_GAP;
  var FLUID_BAR_OVERFLOW_WIDTH = 24;
  function maxVisibleBarsForWidth(width) {
    var safeWidth = isFinite(width) ? Math.max(0, width) : 0;
    return Math.max(
        1,
        Math.floor(
            (safeWidth - FLUID_BAR_OVERFLOW_WIDTH) / FLUID_BAR_SLOT_WIDTH));
  }
  function fluidBarLayoutForWidth(width, visibleBarCount, hasOverflow) {
    var safeWidth = isFinite(width) ? Math.max(0, width) : 0;
    var safeBarCount = isFinite(visibleBarCount)
        ? Math.max(0, Math.floor(visibleBarCount))
        : 0;
    var overflowVisible = hasOverflow === true;
    var gapCount = Math.max(
        0,
        safeBarCount - 1 + (overflowVisible ? 1 : 0));
    var availableWidth = Math.max(
        0,
        safeWidth - (overflowVisible ? FLUID_BAR_OVERFLOW_WIDTH : 0));
    var minimumWidth =
        safeBarCount * FLUID_BAR_MIN_WIDTH + gapCount * FLUID_BAR_MIN_GAP;
    var extraWidth = Math.max(0, availableWidth - minimumWidth);
    var adjustableSlots = safeBarCount + gapCount;
    var sharedIncrease = 0;
    if (adjustableSlots > 0) {
      var sharedLimit = FLUID_BAR_MAX_WIDTH - FLUID_BAR_MIN_WIDTH;
      if (gapCount > 0) {
        sharedLimit = Math.min(
            sharedLimit,
            FLUID_BAR_MAX_GAP - FLUID_BAR_MIN_GAP);
      }
      sharedIncrease = Math.min(
          sharedLimit,
          extraWidth / adjustableSlots);
    }
    var barWidth = FLUID_BAR_MIN_WIDTH + sharedIncrease;
    var gap = FLUID_BAR_MIN_GAP + sharedIncrease;
    var remainingWidth = Math.max(
        0,
        extraWidth - sharedIncrease * adjustableSlots);
    if (safeBarCount > 0 && remainingWidth > 0) {
      var barIncrease = Math.min(
          FLUID_BAR_MAX_WIDTH - barWidth,
          remainingWidth / safeBarCount);
      barWidth += barIncrease;
      remainingWidth -= barIncrease * safeBarCount;
    }
    if (gapCount > 0 && remainingWidth > 0) {
      gap += Math.min(
          FLUID_BAR_MAX_GAP - gap,
          remainingWidth / gapCount);
    }
    return {
      barWidth: Math.round(barWidth * 1000) / 1000,
      gap: Math.round(gap * 1000) / 1000
    };
  }
  /* OCTANE_FLUID_BAR_CHART_END */
  function applyFluidAxisLabelLayout(container, layout) {
    var renderer = window.OctaneScaleReport;
    if (!container || !renderer || !renderer.axisLabelLayout) {
      return;
    }
    var labels = container.querySelectorAll(".octane-suite-label");
    var graph = container.closest(".octane-bar-graph");
    if (!labels.length || !graph) {
      return;
    }
    var firstStyle = window.getComputedStyle(labels[0]);
    var fontHeight = parseFloat(firstStyle.fontSize) || 12;
    var font = firstStyle.font || fontHeight + "px Inter, sans-serif";
    var averageCharacterWidth =
        renderer.measureAxisLabel("MMMMMM\u2026", font) / 7;
    var maximumLabelWidth = 0;
    for (var index = 0; index < labels.length; index += 1) {
      var fullLabel = labels[index].getAttribute("data-axis-label")
          || labels[index].textContent.trim();
      labels[index].setAttribute("data-axis-label", fullLabel);
      maximumLabelWidth = Math.max(
          maximumLabelWidth,
          renderer.measureAxisLabel(fullLabel, font));
    }
    var graphHeight = graph.getBoundingClientRect().height;
    var containerWidth = container.getBoundingClientRect().width;
    if (graphHeight <= 0 || containerWidth <= 0) {
      graph.style.removeProperty("--octane-axis-label-row");
      for (var resetIndex = 0; resetIndex < labels.length; resetIndex += 1) {
        labels[resetIndex].textContent =
            labels[resetIndex].getAttribute("data-axis-label") || "";
        labels[resetIndex].setAttribute("data-axis-label-rotation", "0");
        labels[resetIndex].style.removeProperty("--octane-axis-label-max-width");
      }
      return;
    }
    var maximumMargin = Math.min(72, Math.max(40, graphHeight * 0.24));
    var labelLayout = renderer.axisLabelLayout(
        maximumLabelWidth,
        layout.barWidth + layout.gap,
        averageCharacterWidth,
        maximumMargin,
        fontHeight);
    graph.style.setProperty(
        "--octane-axis-label-row",
        Math.max(27, labelLayout.axisMargin) + "px");
    for (var labelIndex = 0; labelIndex < labels.length; labelIndex += 1) {
      var label = labels[labelIndex];
      var value = label.getAttribute("data-axis-label") || "";
      label.textContent = renderer.truncateAxisLabel(
          value, labelLayout.maximumCharacters);
      label.setAttribute(
          "data-axis-label-rotation", String(Math.abs(labelLayout.rotation)));
      label.style.setProperty(
          "--octane-axis-label-max-width", maximumMargin + "px");
    }
  }
  var fluidBarContainers = [];
  var fluidBarResizeObserver = null;
  var fluidBarResizeScheduled = false;
  var fluidBarResizeTimer = 0;
  var fluidBarFallbackBound = false;
  function directSuiteRunColumns(container) {
    var columns = [];
    var children = container ? container.children : [];
    for (var index = 0; index < children.length; index += 1) {
      if (children[index].classList.contains("octane-suite-column")) {
        columns.push(children[index]);
      }
    }
    return columns;
  }
  function createBarOverflowIndicator(hiddenCount) {
    var indicator = document.createElement("div");
    var line = document.createElement("span");
    var count = document.createElement("span");
    indicator.className = "octane-bar-overflow-indicator";
    indicator.setAttribute("role", "note");
    indicator.setAttribute("aria-label", hiddenCount + " tester bars omitted");
    indicator.setAttribute("data-hidden-count", String(hiddenCount));
    line.className = "octane-bar-overflow-line";
    line.setAttribute("aria-hidden", "true");
    count.className = "octane-bar-overflow-count";
    count.textContent = "+" + hiddenCount;
    indicator.appendChild(line);
    indicator.appendChild(count);
    return indicator;
  }
  function renderFluidChart(container) {
    if (!container) {
      return;
    }
    auditSelectedAxes(container);
    var allSuiteRuns = container.octaneAllSuiteRuns;
    if (!allSuiteRuns) {
      allSuiteRuns = directSuiteRunColumns(container);
      container.octaneAllSuiteRuns = allSuiteRuns;
    }
    var width = container.getBoundingClientRect().width;
    var maxVisibleBars = maxVisibleBarsForWidth(width);
    var visibleCount = Math.min(allSuiteRuns.length, maxVisibleBars);
    var hiddenCount = Math.max(0, allSuiteRuns.length - visibleCount);
    var layout = fluidBarLayoutForWidth(width, visibleCount, hiddenCount > 0);
    container.style.setProperty("--octane-bar-width", layout.barWidth + "px");
    container.style.setProperty("--octane-bar-gap", layout.gap + "px");
    var renderSignature = visibleCount + ":" + hiddenCount;
    if (container.octaneFluidRenderSignature === renderSignature) {
      applyFluidAxisLabelLayout(container, layout);
      return;
    }
    container.octaneFluidRenderSignature = renderSignature;
    container.classList.toggle("octane-fluid-bars-dense", hiddenCount > 0);
    while (container.firstChild) {
      container.removeChild(container.firstChild);
    }
    var fragment = document.createDocumentFragment();
    var visibleSuiteRuns = allSuiteRuns.slice(
        Math.max(0, allSuiteRuns.length - maxVisibleBars));
    for (var index = 0; index < visibleSuiteRuns.length; index += 1) {
      fragment.appendChild(visibleSuiteRuns[index]);
    }
    if (hiddenCount > 0) {
      fragment.appendChild(createBarOverflowIndicator(hiddenCount));
    }
    container.appendChild(fragment);
    applyFluidAxisLabelLayout(container, layout);
  }
  function renderAllFluidCharts() {
    fluidBarResizeScheduled = false;
    for (var index = 0; index < fluidBarContainers.length; index += 1) {
      renderFluidChart(fluidBarContainers[index]);
    }
  }
  function scheduleFluidBarCharts() {
    window.clearTimeout(fluidBarResizeTimer);
    fluidBarResizeTimer = window.setTimeout(function () {
      if (fluidBarResizeScheduled) {
        return;
      }
      fluidBarResizeScheduled = true;
      requestFrame(renderAllFluidCharts);
    }, 80);
  }
  function initializeFluidBarCharts(root) {
    if (fluidBarResizeObserver) {
      fluidBarResizeObserver.disconnect();
      fluidBarResizeObserver = null;
    }
    fluidBarContainers = root && root.querySelectorAll
        ? Array.prototype.slice.call(root.querySelectorAll(".octane-vertical-bars"))
        : [];
    renderAllFluidCharts();
    if (typeof window.ResizeObserver === "function") {
      fluidBarResizeObserver = new window.ResizeObserver(scheduleFluidBarCharts);
      for (var index = 0; index < fluidBarContainers.length; index += 1) {
        fluidBarResizeObserver.observe(fluidBarContainers[index]);
      }
    } else if (!fluidBarFallbackBound) {
      fluidBarFallbackBound = true;
      window.addEventListener("resize", scheduleFluidBarCharts);
    }
  }
  function decorateReportZone(zone) {
    if (!zone) {
      return;
    }
    var cards = zone.querySelectorAll(".octane-chart-card");
    for (var index = 0; index < cards.length; index += 1) {
      var header = cards[index].querySelector(".octane-card-header");
      var tools = header ? header.querySelector(".octane-card-tools") : null;
      if (!header || !tools || header.querySelector(".octane-card-actions")) {
        continue;
      }
      var actions = document.createElement("div");
      actions.className = "octane-card-actions";
      header.insertBefore(actions, tools);
      actions.appendChild(createExpandButton());
      actions.appendChild(tools);
    }
  }
  function installAnalyticsComponentSwap(root) {
    if (!root) {
      return null;
    }
    var passRateCard = root.querySelector('[data-card-key="progress-pass-rate"]');
    var defectFace = passRateCard
        ? passRateCard.querySelector('[data-card-view="defects"]')
        : null;
    var managementCard = root.querySelector(
        '[data-card-key="test-management-metrics"]');
    var passRateViewport = passRateCard
        ? passRateCard.querySelector(".octane-flip-viewport")
        : null;
    var metricsHeader = managementCard
        ? managementCard.querySelector(".octane-card-header")
        : null;
    var metricsBody = managementCard
        ? managementCard.querySelector(".octane-management-card-body")
        : null;
    if (!passRateCard || !defectFace || !managementCard || !passRateViewport
        || !metricsHeader || !metricsBody) {
      return null;
    }

    var metricsHeaderActions = metricsHeader.querySelector(".octane-card-actions");
    if (metricsHeaderActions) {
      metricsHeaderActions.remove();
    }
    metricsHeader.className = "octane-flip-face-header";
    metricsBody.className =
        "octane-flip-face-body octane-management-metrics-face-body";
    var metricsFace = document.createElement("div");
    metricsFace.className =
        "octane-flip-face octane-flip-face-management-metrics";
    metricsFace.setAttribute("data-card-view", "metrics");
    metricsFace.appendChild(metricsHeader);
    metricsFace.appendChild(metricsBody);
    passRateViewport.appendChild(metricsFace);

    var defectHeader = defectFace.querySelector(".octane-defect-face-header");
    var defectBody = defectFace.querySelector(".octane-flip-face-body");
    var timerToggle = defectFace.querySelector(".octane-defect-main-view-toggle");
    if (timerToggle) {
      timerToggle.remove();
    }
    if (defectHeader) {
      defectHeader.classList.add("octane-card-header");
      managementCard.appendChild(defectHeader);
    }
    if (defectBody) {
      defectBody.classList.add("octane-management-card-body");
      managementCard.appendChild(defectBody);
    }
    defectFace.remove();
    managementCard.setAttribute("data-card-key", "test-management-defects");
    managementCard.classList.add("octane-test-management-defects-card");
    return passRateCard;
  }
  var managementMetricsRoot = installAnalyticsComponentSwap(dashboard);
  decorateCardZones(dashboard);
  var testManagementZone = document.getElementById("octane-test-management-zone");
  function initialTestManagementPayload() {
    if (!testManagementZone) {
      return {};
    }
    try {
      return JSON.parse(
          testManagementZone.getAttribute("data-test-management-json") || "{}");
    } catch (error) {
      return {};
    }
  }
  function expandFailureCategory(categoryBar, category, status) {
    var card = categoryBar
        ? categoryBar.closest(".octane-chart-card")
        : dashboard.querySelector('[data-card-key="test-management-failures"]');
    if (card) {
      expandCard(card);
      window.OctaneTestManagement.revealFailureCategory(
          testManagementZone, category);
    }
  }
  function initializeTestManagement() {
    if (!testManagementZone || !window.OctaneTestManagement) {
      return;
    }
    window.OctaneTestManagement.mount(
        testManagementZone,
        initialTestManagementPayload(),
        {
          metricsRoot: managementMetricsRoot,
          onCategorySelect: expandFailureCategory
        });
  }
  function updateTestManagement(payload) {
    if (!testManagementZone || !window.OctaneTestManagement
        || !payload || !payload.testManagement) {
      return;
    }
    window.OctaneTestManagement.update(testManagementZone, payload.testManagement);
  }
  initializeTestManagement();
  initializeFluidBarCharts(dashboard);
  var initialClientReportZone = document.getElementById("octane-report-zone");
  if (initialClientReportZone
      && initialClientReportZone.getAttribute("data-client-rendered") === "true") {
    mountClientRenderedReport(
        initialClientReportZone,
        initialClientReportZone.getAttribute("data-report-data-url") || "data",
        initialClientReportZone.getAttribute("data-report-checksum") || "",
        null);
  }
  initializeCardViews(dashboard);
  initializeTestMetricSegments(dashboard);
  initializeActivityRingLayout();
  initializeExecutionBreakdownScaling(dashboard);
  initializeDefectAnalyticsViews(dashboard);
  initializeDefectTrend();
  scheduleDefectTrendAnimation();
  runCompletionAutoFlips(currentReportPayload());
  function createTimerState(card) {
    var totalSeconds = parseInt(card.getAttribute("data-total-seconds") || "1", 10);
    if (isNaN(totalSeconds)) {
      totalSeconds = 1;
    }
    var totalMillis = Math.max(1, totalSeconds) * 1000;
    var extendedTotalSeconds =
        parseInt(card.getAttribute("data-extended-total-seconds") || "0", 10);
    if (isNaN(extendedTotalSeconds)) {
      extendedTotalSeconds = 0;
    }
    var startedAt = parseTimestamp(card.getAttribute("data-started-at"));
    var updatedAt = parseTimestamp(card.getAttribute("data-updated-at"));
    var active = card.getAttribute("data-active") === "true";
    var extendedActive = card.getAttribute("data-extended-active") === "true";
    var mode = card.getAttribute("data-octane-timer");
    return {
      active: active,
      card: card,
      endAt: startedAt + totalMillis,
      extendedActive: extendedActive,
      graphic: card.querySelector(".octane-timer-donut"),
      extendedTotalMillis: Math.max(0, extendedTotalSeconds) * 1000,
      gradientStops: card.querySelectorAll("[data-timer-gradient-stop]"),
      lastDisplayedCompactUnit: "",
      lastDisplayedUnit: "",
      lastDisplayedValue: "",
      mode: mode,
      manualExitRequestedAtMillis:
          mode === "timeout"
              ? Number(
                  dashboard.getAttribute(
                      "data-manual-exit-requested-at-millis") || 0)
              : 0,
      progressCircle: card.querySelector("[data-timer-progress]"),
      subtitle: card.querySelector("[data-timeout-subtitle]"),
      title: card.querySelector("[data-timeout-title]"),
      totalMillis: totalMillis,
      unit: card.querySelector("[data-timer-unit]"),
      unitCompact: card.querySelector("[data-timer-unit-compact]"),
      updatedAt: updatedAt,
      value: card.querySelector("[data-timer-value]")
    };
  }
  function timerStateSampleTime(state, now) {
    return timerSampleTimeMillis(
        state.active,
        state.updatedAt,
        state.manualExitRequestedAtMillis,
        now);
  }
  function remainingMillis(state, now) {
    if (!state.active && state.mode === "poll") {
      return 0;
    }
    if (state.mode === "timeout" && state.extendedActive) {
      return 0;
    }
    var sampleTime = timerStateSampleTime(state, now);
    return clamp(state.endAt - sampleTime, 0, state.totalMillis);
  }
  function testingTimeSpentMillis(state, now) {
    var sampleTime = timerStateSampleTime(state, now);
    var elapsed = sampleTime - (state.endAt - state.totalMillis);
    var maximum = state.totalMillis + (state.extendedTotalMillis || 0);
    return clamp(elapsed, 0, maximum);
  }
  function timerTrackTotalMillis(state) {
    if (state.mode !== "timeout") {
      return state.totalMillis;
    }
    return state.totalMillis + state.extendedTotalMillis;
  }
  /* OCTANE_TIMER_DISPLAY_START */
  function timerSampleTimeMillis(active, updatedAt, manualExitAt, now) {
    return manualExitAt > 0 ? manualExitAt : active ? now : updatedAt;
  }
  function timeoutTimerShowsSpent(state) {
    return state.mode === "timeout"
        && (!state.active || state.manualExitRequestedAtMillis > 0);
  }
  function timeoutCountdownRemainingMillis(
      startedAt, baseTotalMillis, extendedTotalMillis, sampleTime) {
    var totalMillis =
        Math.max(0, baseTotalMillis) + Math.max(0, extendedTotalMillis);
    return clamp(startedAt + totalMillis - sampleTime, 0, totalMillis);
  }
  function zeroPadTimerUnit(value) {
    return value < 10 ? "0" + String(value) : String(value);
  }
  function timerQuantityLabel(value, singular, plural) {
    return String(value) + " " + (value === 1 ? singular : plural);
  }
  function timerDisplayParts(remainingMillis) {
    var totalSeconds = Math.max(0, Math.ceil(remainingMillis / 1000));
    var primary;
    var secondary;
    var primarySingular;
    var primaryPlural;
    var secondarySingular;
    var secondaryPlural;
    var fullLabel;
    var compactLabel;
    if (totalSeconds >= 604800) {
      primary = Math.floor(totalSeconds / 604800);
      secondary = Math.floor((totalSeconds % 604800) / 86400);
      primarySingular = "week";
      primaryPlural = "weeks";
      secondarySingular = "day";
      secondaryPlural = "days";
      fullLabel = "weeks + days";
      compactLabel = "w + d";
    } else if (totalSeconds >= 86400) {
      primary = Math.floor(totalSeconds / 86400);
      secondary = Math.floor((totalSeconds % 86400) / 3600);
      primarySingular = "day";
      primaryPlural = "days";
      secondarySingular = "hour";
      secondaryPlural = "hours";
      fullLabel = "days + hours";
      compactLabel = "d + h";
    } else if (totalSeconds >= 3600) {
      primary = Math.floor(totalSeconds / 3600);
      secondary = Math.floor((totalSeconds % 3600) / 60);
      primarySingular = "hour";
      primaryPlural = "hours";
      secondarySingular = "minute";
      secondaryPlural = "minutes";
      fullLabel = "hours + minutes";
      compactLabel = "h + m";
    } else {
      primary = Math.floor(totalSeconds / 60);
      secondary = totalSeconds % 60;
      primarySingular = "minute";
      primaryPlural = "minutes";
      secondarySingular = "second";
      secondaryPlural = "seconds";
      fullLabel = "minutes + seconds";
      compactLabel = "m + s";
    }
    return {
      accessibleLabel:
          timerQuantityLabel(primary, primarySingular, primaryPlural)
          + " and "
          + timerQuantityLabel(secondary, secondarySingular, secondaryPlural),
      compactLabel: compactLabel,
      fullLabel: fullLabel,
      value: String(primary) + ":" + zeroPadTimerUnit(secondary)
    };
  }
  /* OCTANE_TIMER_DISPLAY_END */
  function timerTrackRemainingMillis(state, now) {
    if (!state.active && state.mode === "poll") {
      return 0;
    }
    var sampleTime = timerStateSampleTime(state, now);
    var startedAt = state.endAt - state.totalMillis;
    if (state.mode === "timeout") {
      return timeoutCountdownRemainingMillis(
          startedAt,
          state.totalMillis,
          state.extendedTotalMillis,
          sampleTime);
    }
    return clamp(
        startedAt + state.totalMillis - sampleTime,
        0,
        state.totalMillis);
  }
  function timerColorProgress(state, now) {
    if (state.mode === "timeout") {
      return clamp(
          (testingTimeSpentMillis(state, now) / state.totalMillis) * 100,
          0,
          100);
    }
    var remaining = remainingMillis(state, now);
    return clamp(((state.totalMillis - remaining) / state.totalMillis) * 100, 0, 100);
  }
  function setTimeoutCopy(state, showSpent) {
    if (state.mode !== "timeout") {
      return;
    }
    if (state.title) {
      state.title.textContent = "Testing Session Monitor";
    }
    if (state.subtitle) {
      state.subtitle.textContent =
          showSpent ? "Session Time Spent" : "Session Time Remaining";
    }
  }
  function fitTimerText(state, displayValue, displayUnit, compactDisplayUnit) {
    if (!state.value || !state.unit) {
      return;
    }
    var valueLength = Math.max(1, displayValue.length);
    var unitLength = Math.max(1, displayUnit.length);
    var compactUnitLength = Math.max(1, compactDisplayUnit.length);
    var valueSize = clamp(116 / (valueLength * 0.56), 23, 42);
    var unitSize = clamp(78 / (unitLength * 0.52), 10, 14.7);
    var compactUnitSize = clamp(78 / (compactUnitLength * 0.52), 10, 14.7);
    var valueWeight = valueSize < 27 ? "600" : valueSize < 32 ? "650" : "700";
    state.value.style.fontSize = trimNumber(valueSize) + "px";
    state.value.style.fontWeight = valueWeight;
    state.unit.style.fontSize = trimNumber(unitSize) + "px";
    if (state.unitCompact) {
      state.unitCompact.style.fontSize = trimNumber(compactUnitSize) + "px";
    }
  }
  function setTimerText(state, duration, showSpent) {
    if (!state.value || !state.unit) {
      return;
    }
    var displayValue;
    var displayUnit;
    var compactDisplayUnit;
    if (state.mode === "timeout") {
      var displayParts = timerDisplayParts(duration);
      displayValue = displayParts.value;
      displayUnit = displayParts.fullLabel;
      compactDisplayUnit = displayParts.compactLabel;
      if (state.graphic) {
        state.graphic.setAttribute(
            "aria-label",
            (showSpent ? "Testing time spent: " : "Testing time remaining: ")
            + displayParts.accessibleLabel);
      }
    } else if (duration < 60000) {
      displayValue = String(Math.ceil(duration / 1000));
      displayUnit = displayValue === "1" ? "second" : "seconds";
      compactDisplayUnit = displayUnit;
    } else {
      displayValue = String(Math.ceil(duration / 60000));
      displayUnit = displayValue === "1" ? "minute" : "minutes";
      compactDisplayUnit = displayUnit;
    }
    if (displayValue !== state.lastDisplayedValue) {
      state.value.textContent = displayValue;
      state.lastDisplayedValue = displayValue;
    }
    if (displayUnit !== state.lastDisplayedUnit) {
      state.unit.textContent = displayUnit;
      state.lastDisplayedUnit = displayUnit;
    }
    if (
        state.unitCompact
        && compactDisplayUnit !== state.lastDisplayedCompactUnit) {
      state.unitCompact.textContent = compactDisplayUnit;
      state.lastDisplayedCompactUnit = compactDisplayUnit;
    }
    fitTimerText(state, displayValue, displayUnit, compactDisplayUnit);
  }
  function updateTimer(state, now) {
    var showSpent = timeoutTimerShowsSpent(state);
    var remaining = remainingMillis(state, now);
    var trackTotalMillis = timerTrackTotalMillis(state);
    var trackRemaining = timerTrackRemainingMillis(state, now);
    var displayDuration =
        showSpent ? testingTimeSpentMillis(state, now) : trackRemaining;
    setTimeoutCopy(state, showSpent);
    var remainingProgress = clamp((trackRemaining / trackTotalMillis) * 100, 0, 100);
    var colors = colorsForProgress(state.mode, timerColorProgress(state, now));
    if (state.progressCircle) {
      state.progressCircle.style.strokeDasharray =
          trimNumber(remainingProgress) + " 100";
      state.progressCircle.style.opacity =
          remainingProgress <= 0 ? "0" : TIMER_ACTIVE_OPACITY;
    }
    applyGradientStops(state.gradientStops, colors);
    setTimerText(
        state,
        state.mode === "timeout" ? displayDuration : remaining,
        showSpent);
  }
  var timers = [];
  var hasActiveTimer = false;
  for (var timerIndex = 0; timerIndex < timerCards.length; timerIndex += 1) {
    var state = createTimerState(timerCards[timerIndex]);
    timers.push(state);
    hasActiveTimer = hasActiveTimer || state.active;
  }
  var timerLoopRunning = false;
  function scheduleTimerAnimation() {
    if (timerLoopRunning) {
      return;
    }
    timerLoopRunning = true;
    scheduleTimerFrame(animateTimers);
  }
  function beginSnapshotRefresh(now) {
    if (liveRefresh.stopped || liveRefresh.fetching || liveRefresh.waiting) {
      return;
    }
    if (typeof window.fetch !== "function") {
      window.location.reload();
      return;
    }
    if (!liveRefresh.waiting) {
      liveRefresh.waiting = true;
      liveRefresh.waitingSince = now;
      setLiveUpdateStatus("...", false);
    }
    fetchSnapshot();
    scheduleTimerAnimation();
  }
  function scheduleSnapshotRetry() {
    if (liveRefresh.stopped) {
      return;
    }
    window.clearTimeout(liveRefresh.retryTimer);
    liveRefresh.retryTimer =
        window.setTimeout(
            fetchSnapshot,
            liveRefresh.finalizing ? 250 : liveRefresh.retryDelayMillis);
  }
  function canApplySnapshotPayload(payload) {
    if (!payload) {
      return false;
    }
    var incomingUpdatedAt = Date.parse(payload.updatedAt || "");
    var renderedUpdatedAt = Date.parse(currentUpdatedAt || "");
    if (!isNaN(incomingUpdatedAt)
        && !isNaN(renderedUpdatedAt)
        && incomingUpdatedAt < renderedUpdatedAt) {
      return false;
    }
    var renderedBuilding =
        dashboard.getAttribute("data-report-building") === "true";
    if (!renderedBuilding && payload.building === true) {
      return false;
    }
    return true;
  }
  function isFreshSnapshot(payload) {
    if (!canApplySnapshotPayload(payload)) {
      return false;
    }
    return payload.updatedAt !== currentUpdatedAt
        || payload.building === false
        || payload.finalizing
            !== (dashboard.getAttribute("data-report-finalizing") === "true")
        || payload.manualExitRequested
            !== (dashboard.getAttribute("data-manual-exit-requested") === "true")
        || Number(payload.manualExitRequestedAtMillis || 0)
            !== Number(
                dashboard.getAttribute(
                    "data-manual-exit-requested-at-millis") || 0);
  }
  function resetPollTimer(payload) {
    var updatedAt = parseTimestamp(payload.updatedAt);
    var refreshSeconds = parseInt(payload.refreshSeconds || "1", 10);
    if (isNaN(refreshSeconds)) {
      refreshSeconds = 1;
    }
    currentRefreshSeconds = Math.max(1, refreshSeconds);
    dashboard.setAttribute("data-refresh-seconds", String(currentRefreshSeconds));
    var timeoutSeconds = parseInt(payload.timeoutSeconds || "1", 10);
    if (isNaN(timeoutSeconds)) {
      timeoutSeconds = 1;
    }
    var timeoutExtendedSeconds = parseInt(payload.timeoutExtendedSeconds || "0", 10);
    if (isNaN(timeoutExtendedSeconds)) {
      timeoutExtendedSeconds = 0;
    }
    for (var index = 0; index < timers.length; index += 1) {
      var state = timers[index];
      state.active = payload.timerActive === true
          || (payload.building === true && payload.finalizing !== true);
      state.updatedAt = updatedAt;
      state.extendedActive = payload.extendedTime === true && state.mode === "timeout";
      if (state.mode === "timeout") {
        state.manualExitRequestedAtMillis = manualExitTimeMillis(
            payload, state.manualExitRequestedAtMillis);
      }
      state.card.setAttribute("data-active", String(state.active));
      state.card.setAttribute("data-updated-at", payload.updatedAt || "");
      if (state.mode === "poll") {
        state.totalMillis = Math.max(1, refreshSeconds) * 1000;
        state.endAt = updatedAt + state.totalMillis;
        state.card.setAttribute("data-total-seconds", String(refreshSeconds));
        state.card.setAttribute("data-started-at", payload.updatedAt || "");
        state.lastDisplayedCompactUnit = "";
        state.lastDisplayedUnit = "";
        state.lastDisplayedValue = "";
      } else if (state.mode === "timeout") {
        state.totalMillis = Math.max(1, timeoutSeconds) * 1000;
        state.extendedTotalMillis = Math.max(0, timeoutExtendedSeconds) * 1000;
        if (payload.startedAt) {
          state.card.setAttribute("data-started-at", payload.startedAt);
        }
        state.endAt = parseTimestamp(state.card.getAttribute("data-started-at"))
            + state.totalMillis;
        state.card.setAttribute("data-total-seconds", String(timeoutSeconds));
        state.card.setAttribute(
            "data-extended-total-seconds", String(timeoutExtendedSeconds));
        state.card.setAttribute("data-extended-active", String(state.extendedActive));
        state.lastDisplayedCompactUnit = "";
        state.lastDisplayedUnit = "";
        state.lastDisplayedValue = "";
      }
      updateTimer(state, currentWallTime());
    }
  }
  function mountClientRenderedReport(reportZone, dataUrl, checksum, restoreState) {
    if (!reportZone || !window.OctaneScaleReport) {
      return Promise.resolve(false);
    }
    reportZone.setAttribute("data-client-rendered", "true");
    reportZone.setAttribute("data-report-data-url", dataUrl || "data");
    reportZone.setAttribute("data-report-checksum", checksum || "");
    return window.OctaneScaleReport.mount(
        reportZone, dataUrl || "data", checksum || "").then(function (rendered) {
      decorateReportZone(reportZone);
      decorateCardZones(reportZone);
      if (restoreState && restoreState.expandedKey) {
        var expandedReplacement = findCardByKey(restoreState.expandedKey);
        if (expandedReplacement) {
          expandCard(expandedReplacement);
        }
      }
      if (restoreState && restoreState.focusedKey) {
        var focusedReplacement = findZoneByKey(restoreState.focusedKey);
        if (focusedReplacement) {
          focusZone(focusedReplacement);
        }
      }
      barPopupRefreshInProgress = false;
      restoreBarPopupAfterRefresh(reportZone, restoreState && restoreState.popup);
      return rendered;
    });
  }
  function applySnapshot(payload) {
    if (!canApplySnapshotPayload(payload)) {
      return;
    }
    currentUpdatedAt = payload.updatedAt || currentUpdatedAt;
    currentUpdatedAtDateTimeText =
        payload.updatedAtDateTimeText || currentUpdatedAtDateTimeText;
    currentJobStateLabel = payload.jobStateLabel
        || (payload.building === true ? "In Progress" : payload.stateLabel)
        || currentJobStateLabel;
    dashboard.setAttribute("data-current-updated-at", currentUpdatedAt);
    dashboard.setAttribute(
        "data-current-updated-at-text", currentUpdatedAtDateTimeText);
    dashboard.setAttribute("data-job-state-label", currentJobStateLabel);
    dashboard.setAttribute("data-report-building", String(payload.building === true));
    dashboard.setAttribute(
        "data-report-finalizing", String(payload.finalizing === true));
    dashboard.setAttribute("data-extended-time", String(payload.extendedTime === true));
    dashboard.setAttribute(
        "data-manual-exit-requested", String(payload.manualExitRequested === true));
    dashboard.setAttribute(
        "data-manual-exit-requested-at-millis",
        String(payload.manualExitRequestedAtMillis || 0));
    dashboard.setAttribute(
        "aria-busy",
        String(payload.finalizing === true));
    setExtendedExitVisibility(
        payload.extendedTime === true,
        payload.manualExitRequested === true,
        payload.building === true);
    if (stateLabel) {
      stateLabel.textContent = currentJobStateLabel;
    }
    if (messageLabel) {
      messageLabel.textContent = payload.message || "";
    }
    updateRiskHeatMap(payload);
    updateTestMetrics(payload);
    updateActivityRings(payload);
    updateExecutionStatusDistribution(payload);
    updateDefectTrend(payload);
    updateTestManagement(payload);
    updateTesterDetails(payload);
    runCompletionAutoFlips(payload);
    var reportZone = document.getElementById("octane-report-zone");
    var expandedKey = expandedCard ? expandedCard.getAttribute("data-card-key") : "";
    var restoreReportExpansion =
        expandedCard && reportZone && reportZone.contains(expandedCard);
    var focusedKey = focusedZone ? zoneKeyFor(focusedZone) : "";
    var restoreReportFocus = focusedZone && reportZone && focusedZone === reportZone;
    var popupRestoreState = captureBarPopupRestoreState();
    var updatedReportZone = null;
    if (reportZone && payload.reportZoneDeferred === true) {
      barPopupRefreshInProgress = true;
      if (restoreReportExpansion) {
        removeExpandedState(expandedCard);
        expandedCard = null;
      }
      if (restoreReportFocus) {
        removeFocusedZone(focusedZone);
        focusedZone = null;
      }
      mountClientRenderedReport(
          reportZone,
          payload.reportDataUrl || "data",
          payload.reportDataChecksum || "",
          {
            expandedKey: restoreReportExpansion ? expandedKey : "",
            focusedKey: restoreReportFocus ? focusedKey : "",
            popup: popupRestoreState
          });
    } else {
      updatedReportZone = htmlToElement(payload.reportZoneHtml);
      decorateReportZone(updatedReportZone);
      decorateCardZones(updatedReportZone);
    }
    if (reportZone && updatedReportZone) {
      barPopupRefreshInProgress = true;
      if (restoreReportExpansion) {
        removeExpandedState(expandedCard);
        expandedCard = null;
      }
      if (restoreReportFocus) {
        removeFocusedZone(focusedZone);
        focusedZone = null;
      }
      reportZone.replaceWith(updatedReportZone);
      if (restoreReportExpansion && expandedKey) {
        var replacementExpandedCard = findCardByKey(expandedKey);
        if (replacementExpandedCard) {
          expandCard(replacementExpandedCard);
        } else {
          collapseExpandedCard();
        }
      }
      if (restoreReportFocus && focusedKey) {
        var replacementFocusedZone = findZoneByKey(focusedKey);
        if (replacementFocusedZone) {
          focusZone(replacementFocusedZone);
        } else {
          collapseFocusedZone();
        }
      }
      initializeFluidBarCharts(updatedReportZone);
      initializeExecutionBreakdownScaling(dashboard);
      requestFrame(function () {
        barPopupRefreshInProgress = false;
        restoreBarPopupAfterRefresh(updatedReportZone, popupRestoreState);
      });
    }
    for (var index = 0; index < progressCards.length; index += 1) {
      var progressKind = progressCards[index].getAttribute("data-octane-progress");
      var progressValue =
          progressKind === "pass-rate"
              ? payload.passRateProgress || 0
              : payload.completionProgress || 0;
      progressCards[index].setAttribute("data-progress-value", String(progressValue));
      if (progressKind === "pass-rate") {
        var passRateLabel = progressCards[index].querySelector("[data-pass-rate-label]");
        if (passRateLabel) {
          passRateLabel.textContent = payload.passRateLabel || "";
        }
      }
      updateExecutionProgress(progressCards[index]);
    }
    hasActiveTimer = payload.timerActive === true
        || (payload.building === true && payload.finalizing !== true);
    liveRefresh.finalizing = payload.finalizing === true;
    liveRefresh.stopped = payload.building !== true;
    var waitedMillis = Math.max(0, currentWallTime() - liveRefresh.waitingSince);
    liveRefresh.waiting = false;
    resetPollTimer(payload);
    setLiveUpdateStatus(
        payload.finalizing === true
            ? "Finalizing..."
            : payload.building === true
            ? "+" + (waitedMillis / 1000).toFixed(1) + "s"
            : "",
        payload.building !== true);
    if (payload.building === true) {
      scheduleTimerAnimation();
    }
    if (payload.finalizing === true) {
      scheduleSnapshotRetry();
    }
  }
  function fetchSnapshot(forceStoredSnapshot) {
    if (liveRefresh.fetching || (liveRefresh.stopped && forceStoredSnapshot !== true)) {
      return;
    }
    liveRefresh.fetching = true;
    var snapshotHeaders = {"Accept": "application/json"};
    if (snapshotEtag) {
      snapshotHeaders["If-None-Match"] = snapshotEtag;
    }
    window.fetch(snapshotUrl, {
      cache: "no-store",
      credentials: "same-origin",
      headers: snapshotHeaders
    }).then(function (response) {
      if (response.status === 304) {
        return null;
      }
      if (!response.ok) {
        throw new Error("Snapshot request failed: " + response.status);
      }
      snapshotEtag = response.headers.get("ETag") || snapshotEtag;
      return response.json();
    }).then(function (payload) {
      liveRefresh.fetching = false;
      snapshotRequestSucceeded();
      if (!payload) {
        scheduleSnapshotRetry();
        return;
      }
      if (forceStoredSnapshot === true && liveRefresh.stopped) {
        updateRiskHeatMap(payload);
        updateActivityRings(payload);
        updateDefectTrend(payload);
        return;
      }
      if (isFreshSnapshot(payload)) {
        applySnapshot(payload);
        return;
      }
      scheduleSnapshotRetry();
    }).catch(function () {
      liveRefresh.fetching = false;
      snapshotRequestFailed();
      setLiveUpdateStatus("Retrying...", false);
      scheduleSnapshotRetry();
    });
  }
  function animateTimers() {
    var now = currentWallTime();
    renderReportStatus(now);
    var activeTimerStillRunning = false;
    for (var index = 0; index < timers.length; index += 1) {
      var remaining = remainingMillis(timers[index], now);
      var trackRemaining = timerTrackRemainingMillis(timers[index], now);
      updateTimer(timers[index], now);
      if (timers[index].mode === "poll" && timers[index].active && remaining <= 0) {
        beginSnapshotRefresh(now);
      }
      if (
          timers[index].mode === "timeout"
          && timers[index].active
          && trackRemaining <= 0) {
        runCompletionAutoFlips(currentReportPayload());
      }
      activeTimerStillRunning =
          activeTimerStillRunning
          || trackRemaining > 0
          || liveRefresh.waiting;
    }
    if (hasActiveTimer && activeTimerStillRunning) {
      scheduleTimerFrame(animateTimers);
      return;
    }
    timerLoopRunning = false;
  }
  if (timers.length > 0) {
    scheduleTimerAnimation();
  }
  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && hasActiveTimer) {
      scheduleTimerAnimation();
    }
  });
  window.addEventListener("offline", function () {
    snapshotRequestFailed();
  });
  window.addEventListener("online", function () {
    if (!liveRefresh.stopped) {
      window.clearTimeout(liveRefresh.retryTimer);
      liveRefresh.fetching = false;
      fetchSnapshot();
    }
  });
  if (exitExtendedSubmitForm) {
    exitExtendedSubmitForm.addEventListener("submit", function () {
      var requestedAt = currentWallTime();
      dashboard.setAttribute("data-manual-exit-requested", "true");
      dashboard.setAttribute("data-report-finalizing", "true");
      liveRefresh.finalizing = true;
      dashboard.setAttribute(
          "data-manual-exit-requested-at-millis", String(requestedAt));
      dashboard.setAttribute("aria-busy", "true");
      setExtendedExitVisibility(true, true, true);
      for (var index = 0; index < timers.length; index += 1) {
        if (timers[index].mode === "timeout") {
          timers[index].manualExitRequestedAtMillis = requestedAt;
          updateTimer(timers[index], requestedAt);
        }
      }
      setLiveUpdateStatus("Finalizing...", false);
    });
  }
  if (liveRefresh.finalizing) {
    liveRefresh.waiting = true;
    liveRefresh.waitingSince = currentWallTime();
    fetchSnapshot();
  } else if (liveRefresh.stopped) {
    fetchSnapshot(true);
  }
  function updateExecutionProgress(card) {
    var progress = parseFloat(card.getAttribute("data-progress-value") || "0");
    if (isNaN(progress)) {
      progress = 0;
    }
    progress = clamp(progress, 0, 100);
    var progressKind = card.getAttribute("data-octane-progress") || "completion";
    var progressCircle =
        card.querySelector("[data-progress-circle]")
        || card.querySelector("[data-execution-progress-circle]");
    var gradientStops = card.querySelectorAll("[data-progress-gradient-stop]");
    if (!gradientStops.length) {
      gradientStops = card.querySelectorAll("[data-execution-gradient-stop]");
    }
    var value =
        card.querySelector("[data-progress-value-text]")
        || card.querySelector("[data-execution-progress-value]");
    var displayValue = String(Math.round(progress)) + "%";
    var colorKind = progressKind === "pass-rate" ? "passRate" : progressKind;
    var executionColors = colorsForProgress(colorKind, progress);
    if (value) {
      value.textContent = displayValue;
    }
    applyGradientStops(gradientStops, executionColors);
    if (progressCircle) {
      progressCircle.style.strokeDasharray = trimNumber(progress) + " 100";
      progressCircle.style.opacity = progress <= 0 ? "0" : TIMER_ACTIVE_OPACITY;
    }
  }
  for (var progressIndex = 0; progressIndex < progressCards.length; progressIndex += 1) {
    updateExecutionProgress(progressCards[progressIndex]);
  }
  var barPopupOverlay = document.getElementById("octane-bar-popup-overlay");
  if (barPopupOverlay && barPopupOverlay.parentNode !== document.body) {
    document.body.appendChild(barPopupOverlay);
  }
  var barPopupRefreshInProgress = false;
  var barPopupRenderTimer = 0;
  var barPopupViewportTimer = 0;
  var pendingBarPopup = null;
  var activeBarPopupState = {
    barKey: "",
    cardKey: "",
    clientX: 0,
    clientY: 0,
    input: "",
    visible: false
  };
  function hideBarPopup() {
    window.clearTimeout(barPopupRenderTimer);
    barPopupRenderTimer = 0;
    pendingBarPopup = null;
    if (!activeBarPopupState.visible || !barPopupOverlay) {
      return;
    }
    barPopupOverlay.classList.remove(
        "octane-bar-popup-visible", "octane-bar-popup-restoring");
    barPopupOverlay.removeAttribute("data-placement");
    barPopupOverlay.removeAttribute("data-dominant-status-label");
    barPopupOverlay.setAttribute("aria-hidden", "true");
    barPopupOverlay.style.removeProperty("--octane-popup-border-color");
    barPopupOverlay.style.left = "";
    barPopupOverlay.style.maxWidth = "";
    barPopupOverlay.style.minWidth = "";
    barPopupOverlay.style.top = "";
    activeBarPopupState = {
      barKey: "",
      cardKey: "",
      clientX: 0,
      clientY: 0,
      input: "",
      visible: false
    };
  }
  function popupForColumn(column) {
    return column ? column.querySelector(".octane-bar-popup") : null;
  }
  function tooltipsEnabledForColumn(column) {
    var chart = column ? column.closest("[data-tooltips-enabled]") : null;
    return !chart || chart.getAttribute("data-tooltips-enabled") === "true";
  }
  function populateClientBarPopup(column) {
    if (!barPopupOverlay || !column) {
      return false;
    }
    barPopupOverlay.replaceChildren();
    var name = document.createElement("div");
    name.className = "octane-bar-popup-name";
    name.textContent = column.getAttribute("data-bar-name") || "Tester";
    barPopupOverlay.appendChild(name);
    var metrics = [
      statusMetricForColumn(column, "passed", "Passed"),
      statusMetricForColumn(column, "failed", "Failed"),
      statusMetricForColumn(column, "blocked", "Blocked"),
      statusMetricForColumn(column, "skipped", "Skipped"),
      statusMetricForColumn(column, "running", "In Progress")
    ];
    var total = Number(column.getAttribute("data-bar-total") || 0);
    if (!Number.isFinite(total) || total <= 0) {
      total = metrics.reduce(function (sum, metric) { return sum + metric.count; }, 0);
    }
    for (var index = 0; index < metrics.length; index += 1) {
      var metric = metrics[index];
      if (metric.count <= 0) {
        continue;
      }
      var row = document.createElement("div");
      row.className = "octane-bar-popup-row";
      var swatch = document.createElement("span");
      swatch.className = "octane-swatch";
      swatch.style.background = metric.color;
      var label = document.createElement("span");
      label.className = "octane-bar-popup-label";
      label.textContent = metric.label;
      var value = document.createElement("span");
      value.className = "octane-bar-popup-value";
      value.textContent = String(metric.count);
      var percent = document.createElement("span");
      percent.className = "octane-bar-popup-percent";
      percent.textContent = "(" + (total > 0 ? Math.round(metric.count * 100 / total) : 0)
          + "%)";
      row.appendChild(swatch);
      row.appendChild(label);
      row.appendChild(value);
      row.appendChild(percent);
      barPopupOverlay.appendChild(row);
    }
    var automationRow = document.createElement("div");
    automationRow.className = "octane-bar-popup-automation";
    var automationIcon = document.createElement("span");
    automationIcon.className = "octane-automation-icon";
    automationIcon.setAttribute("aria-hidden", "true");
    var automation = Number(column.getAttribute("data-automation-percentage") || 0);
    automationIcon.textContent = column.getAttribute("data-automation-emoji")
        || (Number.isFinite(automation) && automation > 0 ? "\uD83D\uDD25" : "\uD83D\uDC22");
    var automationLabel = document.createElement("span");
    automationLabel.className = "octane-bar-popup-label";
    automationLabel.textContent = "Automation Usage";
    var automationValue = document.createElement("span");
    automationValue.className = "octane-bar-popup-value";
    automationValue.textContent = (Number.isFinite(automation) ? automation : 0) + "%";
    automationRow.appendChild(automationIcon);
    automationRow.appendChild(automationLabel);
    automationRow.appendChild(automationValue);
    barPopupOverlay.appendChild(automationRow);
    var totalRow = document.createElement("div");
    totalRow.className = "octane-bar-popup-total";
    var totalLabel = document.createElement("span");
    totalLabel.textContent = "Total";
    var totalValue = document.createElement("span");
    totalValue.className = "octane-bar-popup-total-value";
    totalValue.textContent = String(total);
    totalRow.appendChild(totalLabel);
    totalRow.appendChild(totalValue);
    barPopupOverlay.appendChild(totalRow);
    return true;
  }
  function cardKeyForColumn(column) {
    return column ? column.getAttribute("data-card-key") || "" : "";
  }
  function barKeyForColumn(column) {
    return column ? column.getAttribute("data-bar-key") || "" : "";
  }
  function barForColumn(column) {
    return column ? column.querySelector(".octane-vertical-bar") : null;
  }
  function statusMetricForColumn(column, key, label) {
    var rawCount = column ? column.getAttribute("data-status-" + key + "-count") : "";
    var color = column ? column.getAttribute("data-status-" + key + "-color") || "" : "";
    var count = Number(rawCount || 0);
    return {
      color: color,
      count: Number.isFinite(count) ? count : 0,
      label: column
          ? column.getAttribute("data-status-" + key + "-label") || label
          : label
    };
  }
  function dominantStatusForColumn(column) {
    if (!column) {
      return null;
    }
    var metrics = [
      statusMetricForColumn(column, "failed", "Failed"),
      statusMetricForColumn(column, "blocked", "Blocked"),
      statusMetricForColumn(column, "passed", "Passed"),
      statusMetricForColumn(column, "skipped", "Skipped"),
      statusMetricForColumn(column, "running", "In Progress")
    ];
    var dominant = metrics[0];
    for (var index = 1; index < metrics.length; index += 1) {
      if (metrics[index].count > dominant.count) {
        dominant = metrics[index];
      }
    }
    return dominant && dominant.count > 0 ? dominant : null;
  }
  function applyBarPopupDominantColor(column) {
    if (!barPopupOverlay) {
      return;
    }
    var dominant = dominantStatusForColumn(column);
    var color =
        dominant && dominant.color
            ? dominant.color
            : column
                ? column.getAttribute("data-dominant-status-color") || ""
                : "";
    var label =
        dominant && dominant.label
            ? dominant.label
            : column
                ? column.getAttribute("data-dominant-status-label") || ""
                : "";
    if (!color) {
      barPopupOverlay.removeAttribute("data-dominant-status-label");
      barPopupOverlay.style.removeProperty("--octane-popup-border-color");
      return;
    }
    barPopupOverlay.setAttribute("data-dominant-status-label", label);
    barPopupOverlay.style.setProperty("--octane-popup-border-color", color);
  }
  function pointForColumn(column) {
    var bar = barForColumn(column);
    var rect = (bar || column).getBoundingClientRect();
    return {
      clientX: rect.left + rect.width / 2,
      clientY: rect.top + rect.height / 2
    };
  }
  function pointInsideBar(column, point) {
    var bar = barForColumn(column);
    if (!bar || !point) {
      return false;
    }
    var rect = bar.getBoundingClientRect();
    return point.clientX >= rect.left
        && point.clientX <= rect.right
        && point.clientY >= rect.top
        && point.clientY <= rect.bottom;
  }
  function findColumnByKeys(root, cardKey, barKey) {
    if (!root || !cardKey || !barKey) {
      return null;
    }
    var columns = root.querySelectorAll(".octane-suite-column[data-bar-key]");
    for (var index = 0; index < columns.length; index += 1) {
      if (cardKeyForColumn(columns[index]) === cardKey
          && barKeyForColumn(columns[index]) === barKey) {
        return columns[index];
      }
    }
    return null;
  }
  /* OCTANE_BAR_POPUP_LAYOUT_START */
  function rectangleWidth(rect) {
    return Math.max(0, rect.right - rect.left);
  }
  function rectangleHeight(rect) {
    return Math.max(0, rect.bottom - rect.top);
  }
  function intersectRectangles(first, second) {
    return {
      bottom: Math.min(first.bottom, second.bottom),
      left: Math.max(first.left, second.left),
      right: Math.max(
          Math.max(first.left, second.left), Math.min(first.right, second.right)),
      top: Math.max(first.top, second.top)
    };
  }
  function browserViewportRectangle() {
    var visualViewport = window.visualViewport;
    var left = visualViewport ? visualViewport.offsetLeft : 0;
    var top = visualViewport ? visualViewport.offsetTop : 0;
    var width = visualViewport
        ? visualViewport.width
        : window.innerWidth || document.documentElement.clientWidth;
    var height = visualViewport
        ? visualViewport.height
        : window.innerHeight || document.documentElement.clientHeight;
    return {bottom: top + height, left: left, right: left + width, top: top};
  }
  function chartViewportRectangle(column) {
    var browserViewport = browserViewportRectangle();
    var card = column ? column.closest(".octane-chart-card") : null;
    if (!card) {
      return browserViewport;
    }
    var cardRect = card.getBoundingClientRect();
    if (cardRect.width <= 0 || cardRect.height <= 0) {
      return browserViewport;
    }
    return intersectRectangles(cardRect, browserViewport);
  }
  function pointerAnchorRectangle(point) {
    return {
      bottom: point.clientY,
      left: point.clientX,
      right: point.clientX,
      top: point.clientY
    };
  }
  function popupCandidate(side, anchorRect, popupSize, gap) {
    var horizontalCenter = anchorRect.left + rectangleWidth(anchorRect) / 2;
    var verticalCenter = anchorRect.top + rectangleHeight(anchorRect) / 2;
    if (side === "left") {
      return {
        left: anchorRect.left - gap - popupSize.width,
        top: verticalCenter - popupSize.height / 2
      };
    }
    if (side === "above") {
      return {
        left: horizontalCenter - popupSize.width / 2,
        top: anchorRect.top - gap - popupSize.height
      };
    }
    if (side === "below") {
      return {
        left: horizontalCenter - popupSize.width / 2,
        top: anchorRect.bottom + gap
      };
    }
    return {
      left: anchorRect.right + gap,
      top: verticalCenter - popupSize.height / 2
    };
  }
  function popupCandidateFits(candidate, popupSize, bounds) {
    return candidate.left >= bounds.left
        && candidate.top >= bounds.top
        && candidate.left + popupSize.width <= bounds.right
        && candidate.top + popupSize.height <= bounds.bottom;
  }
  function popupCandidateOverflow(candidate, popupSize, bounds) {
    return Math.max(0, bounds.left - candidate.left)
        + Math.max(0, bounds.top - candidate.top)
        + Math.max(0, candidate.left + popupSize.width - bounds.right)
        + Math.max(0, candidate.top + popupSize.height - bounds.bottom);
  }
  function popupCandidateOverlap(candidate, popupSize, barRectangles) {
    var right = candidate.left + popupSize.width;
    var bottom = candidate.top + popupSize.height;
    var overlap = 0;
    for (var index = 0; index < barRectangles.length; index += 1) {
      var barRect = barRectangles[index];
      var overlapWidth = Math.max(
          0, Math.min(right, barRect.right) - Math.max(candidate.left, barRect.left));
      var overlapHeight = Math.max(
          0, Math.min(bottom, barRect.bottom) - Math.max(candidate.top, barRect.top));
      overlap += overlapWidth * overlapHeight;
    }
    return overlap;
  }
  function clampPopupCandidate(candidate, popupSize, bounds) {
    var maxLeft = Math.max(bounds.left, bounds.right - popupSize.width);
    var maxTop = Math.max(bounds.top, bounds.bottom - popupSize.height);
    return {
      left: Math.min(Math.max(candidate.left, bounds.left), maxLeft),
      top: Math.min(Math.max(candidate.top, bounds.top), maxTop)
    };
  }
  function chooseBarPopupPlacement(
      anchorRect, popupSize, viewportRect, barRectangles, preferredSide) {
    var gap = 14;
    var margin = 8;
    var bounds = {
      bottom: Math.max(viewportRect.top + margin, viewportRect.bottom - margin),
      left: viewportRect.left + margin,
      right: Math.max(viewportRect.left + margin, viewportRect.right - margin),
      top: viewportRect.top + margin
    };
    var sides = [];
    function addSide(side) {
      if (sides.indexOf(side) < 0) {
        sides.push(side);
      }
    }
    if (preferredSide === "left" || preferredSide === "right") {
      addSide(preferredSide);
    }
    addSide("right");
    addSide("left");
    addSide("above");
    addSide("below");

    var candidates = [];
    for (var index = 0; index < sides.length; index += 1) {
      var side = sides[index];
      var candidate = popupCandidate(side, anchorRect, popupSize, gap);
      var evaluated = {
        fits: popupCandidateFits(candidate, popupSize, bounds),
        left: candidate.left,
        overlap: popupCandidateOverlap(candidate, popupSize, barRectangles),
        overflow: popupCandidateOverflow(candidate, popupSize, bounds),
        side: side,
        top: candidate.top
      };
      candidates.push(evaluated);
      if (evaluated.fits && evaluated.overlap === 0) {
        return evaluated;
      }
    }

    var best = null;
    for (var candidateIndex = 0;
        candidateIndex < candidates.length;
        candidateIndex += 1) {
      var current = candidates[candidateIndex];
      var isVertical = current.side === "above" || current.side === "below";
      if (isVertical && current.fits
          && (!best || current.overlap < best.overlap)) {
        best = current;
      }
    }
    if (!best) {
      for (var fallbackIndex = 0;
          fallbackIndex < candidates.length;
          fallbackIndex += 1) {
        var fallback = candidates[fallbackIndex];
        var fallbackScore = fallback.overflow * 100000 + fallback.overlap;
        var bestScore = best ? best.overflow * 100000 + best.overlap : Infinity;
        if (!best || fallbackScore < bestScore) {
          best = fallback;
        }
      }
    }
    var clamped = clampPopupCandidate(best, popupSize, bounds);
    best.left = clamped.left;
    best.top = clamped.top;
    return best;
  }
  /* OCTANE_BAR_POPUP_LAYOUT_END */
  function barRectanglesForColumn(column) {
    var anchorBar = barForColumn(column);
    var plot = column ? column.closest(".octane-bar-plot") : null;
    var bars = plot ? plot.querySelectorAll(".octane-vertical-bar") : [];
    var rectangles = [];
    for (var index = 0; index < bars.length; index += 1) {
      if (bars[index] === anchorBar) {
        continue;
      }
      var rect = bars[index].getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        rectangles.push(rect);
      }
    }
    return rectangles;
  }
  function positionBarPopup(popup, column, point, input) {
    var bar = barForColumn(column);
    if (!popup || !bar) {
      return;
    }
    var viewportRect = chartViewportRectangle(column);
    var availableWidth = Math.max(1, rectangleWidth(viewportRect) - 16);
    popup.style.maxWidth = trimNumber(availableWidth) + "px";
    popup.style.minWidth = trimNumber(Math.min(174.72, availableWidth)) + "px";
    var popupSize = {
      height: popup.offsetHeight || 80,
      width: popup.offsetWidth || Math.min(180, availableWidth)
    };
    var anchorRect = input === "mouse" && point
        ? pointerAnchorRectangle(point)
        : bar.getBoundingClientRect();
    var placement = chooseBarPopupPlacement(
        anchorRect,
        popupSize,
        viewportRect,
        barRectanglesForColumn(column),
        popup.getAttribute("data-placement") || "right");
    popup.style.left = trimNumber(placement.left) + "px";
    popup.style.top = trimNumber(placement.top) + "px";
    popup.setAttribute("data-placement", placement.side);
  }
  function showBarPopup(column, point, input, restoring) {
    if (!tooltipsEnabledForColumn(column)) {
      return;
    }
    var source = popupForColumn(column);
    if (!barPopupOverlay || (!source && !column.hasAttribute("data-bar-name"))) {
      return;
    }
    var cardKey = cardKeyForColumn(column);
    var barKey = barKeyForColumn(column);
    if (!cardKey || !barKey) {
      return;
    }
    var sameBar = activeBarPopupState.visible
        && activeBarPopupState.cardKey === cardKey
        && activeBarPopupState.barKey === barKey
        && activeBarPopupState.input === input;
    if (sameBar && !restoring) {
      activeBarPopupState.clientX = point.clientX;
      activeBarPopupState.clientY = point.clientY;
      positionBarPopup(barPopupOverlay, column, point, input);
      return;
    }
    if (source) {
      barPopupOverlay.innerHTML = source.innerHTML;
    } else if (!populateClientBarPopup(column)) {
      return;
    }
    applyBarPopupDominantColor(column);
    if (restoring) {
      barPopupOverlay.classList.add("octane-bar-popup-restoring");
    } else {
      barPopupOverlay.classList.remove("octane-bar-popup-restoring");
    }
    activeBarPopupState = {
      barKey: barKey,
      cardKey: cardKey,
      clientX: point.clientX,
      clientY: point.clientY,
      input: input,
      visible: true
    };
    barPopupOverlay.setAttribute("aria-hidden", "false");
    barPopupOverlay.classList.add("octane-bar-popup-visible");
    positionBarPopup(barPopupOverlay, column, point, input);
    if (restoring) {
      requestFrame(function () {
        barPopupOverlay.classList.remove("octane-bar-popup-restoring");
      });
    }
  }
  function scheduleBarPopup(column, point, input) {
    var sameActiveBar = activeBarPopupState.visible
        && activeBarPopupState.input === input
        && cardKeyForColumn(column) === activeBarPopupState.cardKey
        && barKeyForColumn(column) === activeBarPopupState.barKey;
    if (!sameActiveBar) {
      window.clearTimeout(barPopupRenderTimer);
      barPopupRenderTimer = 0;
      pendingBarPopup = null;
      showBarPopup(column, point, input, false);
      return;
    }
    pendingBarPopup = {column: column, input: input, point: point};
    window.clearTimeout(barPopupRenderTimer);
    barPopupRenderTimer = window.setTimeout(function () {
      barPopupRenderTimer = 0;
      var pending = pendingBarPopup;
      pendingBarPopup = null;
      if (pending) {
        showBarPopup(pending.column, pending.point, pending.input, false);
      }
    }, 16);
  }
  function captureBarPopupRestoreState() {
    if (!activeBarPopupState.visible) {
      return null;
    }
    return {
      barKey: activeBarPopupState.barKey,
      cardKey: activeBarPopupState.cardKey,
      clientX: activeBarPopupState.clientX,
      clientY: activeBarPopupState.clientY,
      input: activeBarPopupState.input,
      visible: activeBarPopupState.visible
    };
  }
  function restoreBarPopupAfterRefresh(root, restoreState) {
    if (!restoreState || !restoreState.visible) {
      return;
    }
    var column =
        findColumnByKeys(root || dashboard, restoreState.cardKey, restoreState.barKey);
    if (!column) {
      hideBarPopup();
      return;
    }
    var point = restoreState.input === "focus"
        ? pointForColumn(column)
        : {clientX: restoreState.clientX, clientY: restoreState.clientY};
    if (restoreState.input !== "focus" && !pointInsideBar(column, point)) {
      hideBarPopup();
      return;
    }
    showBarPopup(column, point, restoreState.input, true);
  }
  function refreshActiveBarPopup() {
    if (!activeBarPopupState.visible) {
      return;
    }
    restoreBarPopupAfterRefresh(dashboard, captureBarPopupRestoreState());
  }
  function scheduleActiveBarPopupRefresh() {
    window.clearTimeout(barPopupViewportTimer);
    barPopupViewportTimer = window.setTimeout(refreshActiveBarPopup, 100);
  }
  dashboard.addEventListener("mousemove", function (event) {
    var bar = event.target.closest(".octane-vertical-bar");
    if (!bar || !dashboard.contains(bar)) {
      return;
    }
    var column = bar.closest(".octane-suite-column");
    if (!column || !tooltipsEnabledForColumn(column)) {
      return;
    }
    scheduleBarPopup(
        column, {clientX: event.clientX, clientY: event.clientY}, "mouse");
  });
  dashboard.addEventListener("mouseout", function (event) {
    if (barPopupRefreshInProgress) {
      return;
    }
    var bar = event.target.closest(".octane-vertical-bar");
    if (!bar) {
      return;
    }
    if (event.relatedTarget && bar.contains(event.relatedTarget)) {
      return;
    }
    var column = bar.closest(".octane-suite-column");
    if (column
        && tooltipsEnabledForColumn(column)
        && activeBarPopupState.input === "mouse"
        && cardKeyForColumn(column) === activeBarPopupState.cardKey
        && barKeyForColumn(column) === activeBarPopupState.barKey) {
      hideBarPopup();
    }
  });
  dashboard.addEventListener("focusin", function (event) {
    var column = event.target.closest(".octane-suite-column");
    if (!column
        || !dashboard.contains(column)
        || !tooltipsEnabledForColumn(column)) {
      return;
    }
    showBarPopup(column, pointForColumn(column), "focus", false);
  });
  dashboard.addEventListener("focusout", function (event) {
    var column = event.target.closest(".octane-suite-column");
    if (!column) {
      return;
    }
    if (event.relatedTarget && column.contains(event.relatedTarget)) {
      return;
    }
    if (activeBarPopupState.input === "focus"
        && cardKeyForColumn(column) === activeBarPopupState.cardKey
        && barKeyForColumn(column) === activeBarPopupState.barKey) {
      hideBarPopup();
    }
  });
  window.addEventListener("scroll", scheduleActiveBarPopupRefresh, true);
  window.addEventListener("resize", scheduleActiveBarPopupRefresh);
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", scheduleActiveBarPopupRefresh);
    window.visualViewport.addEventListener("scroll", scheduleActiveBarPopupRefresh);
  }
  var expandedCard = null;
  var focusedZone = null;
  var expandedBackdrop = document.createElement("div");
  expandedBackdrop.className = "octane-expanded-backdrop";
  var inertElements = [];
  var lastOverlayFocus = null;
  function focusableElements(container) {
    if (!container || !container.querySelectorAll) {
      return [];
    }
    return container.querySelectorAll(
        "button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])");
  }
  function firstFocusableElement(container) {
    var elements = focusableElements(container);
    for (var index = 0; index < elements.length; index += 1) {
      var element = elements[index];
      if (!element.disabled && element.getClientRects().length > 0) {
        return element;
      }
    }
    return null;
  }
  function setElementInert(element, inert) {
    if (!element) {
      return;
    }
    if ("inert" in element) {
      element.inert = inert;
    }
    if (inert) {
      element.setAttribute("data-octane-overlay-inert", "true");
      inertElements.push(element);
    } else {
      element.removeAttribute("data-octane-overlay-inert");
    }
  }
  function markInertOutside(activeElement) {
    var zones = dashboard.querySelectorAll(".octane-card-zone");
    for (var zoneIndex = 0; zoneIndex < zones.length; zoneIndex += 1) {
      if (zones[zoneIndex] !== activeElement && !zones[zoneIndex].contains(activeElement)) {
        setElementInert(zones[zoneIndex], true);
      }
    }
    var cards = dashboard.querySelectorAll(".octane-chart-card");
    for (var cardIndex = 0; cardIndex < cards.length; cardIndex += 1) {
      if (cards[cardIndex] !== activeElement
          && !cards[cardIndex].contains(activeElement)
          && !activeElement.contains(cards[cardIndex])) {
        setElementInert(cards[cardIndex], true);
      }
    }
  }
  function clearOverlayInert() {
    for (var index = 0; index < inertElements.length; index += 1) {
      setElementInert(inertElements[index], false);
    }
    inertElements = [];
  }
  function moveFocusInto(activeElement) {
    var target = firstFocusableElement(activeElement);
    if (!target) {
      activeElement.setAttribute("tabindex", "-1");
      target = activeElement;
    }
    if (target && typeof target.focus === "function") {
      target.focus({preventScroll: true});
    }
  }
  function activateOverlayAccessibility(activeElement) {
    if (!activeElement) {
      return;
    }
    clearOverlayInert();
    if (!lastOverlayFocus || !document.contains(lastOverlayFocus)) {
      lastOverlayFocus = document.activeElement;
    }
    markInertOutside(activeElement);
    moveFocusInto(activeElement);
  }
  function releaseOverlayAccessibility() {
    clearOverlayInert();
    if (lastOverlayFocus
        && document.contains(lastOverlayFocus)
        && typeof lastOverlayFocus.focus === "function") {
      lastOverlayFocus.focus({preventScroll: true});
    }
    lastOverlayFocus = null;
  }
  function activeOverlayElement() {
    return expandedCard || focusedZone;
  }
  /* OCTANE_MODAL_CLICK_CONTAINMENT_START */
  function stopOverlayContentClick(activeElement, event) {
    if (!activeElement || !event || !activeElement.contains(event.target)) {
      return false;
    }
    event.stopPropagation();
    return true;
  }
  /* OCTANE_MODAL_CLICK_CONTAINMENT_END */
  dashboard.addEventListener("click", function (event) {
    stopOverlayContentClick(activeOverlayElement(), event);
  });
  function setExpandButtonState(card, expanded) {
    var buttons = card ? card.querySelectorAll(".octane-expand-toggle") : [];
    if (!buttons.length) {
      return;
    }
    var label = expanded ? "Collapse widget" : "Expand widget";
    for (var index = 0; index < buttons.length; index += 1) {
      buttons[index].setAttribute("aria-expanded", String(expanded));
      buttons[index].setAttribute("aria-label", label);
      buttons[index].setAttribute("title", label);
    }
  }
  function removeExpandedState(card) {
    if (!card) {
      return;
    }
    card.classList.remove("octane-expanded");
    card.setAttribute("draggable", "true");
    setExpandButtonState(card, false);
    scheduleFluidBarCharts();
    scheduleExecutionBreakdownScaling();
  }
  function findCardByKey(key) {
    if (!key) {
      return null;
    }
    var cards = dashboard.querySelectorAll("[data-card-key]");
    for (var index = 0; index < cards.length; index += 1) {
      if (cards[index].getAttribute("data-card-key") === key) {
        return cards[index];
      }
    }
    return null;
  }
  function showExpandedBackdrop() {
    if (!expandedBackdrop.parentNode) {
      document.body.appendChild(expandedBackdrop);
    }
  }
  function hideExpandedBackdrop() {
    if (expandedBackdrop.parentNode) {
      expandedBackdrop.parentNode.removeChild(expandedBackdrop);
    }
  }
  function hideExpandedBackdropIfUnused() {
    if (!expandedCard && !focusedZone) {
      hideExpandedBackdrop();
      releaseOverlayAccessibility();
    }
  }
  function collapseExpandedCard() {
    if (!expandedCard) {
      hideExpandedBackdropIfUnused();
      return;
    }
    removeExpandedState(expandedCard);
    expandedCard = null;
    hideExpandedBackdropIfUnused();
  }
  function setZoneButtonState(zone, focused) {
    var button = zone ? zone.querySelector(".octane-zone-focus-toggle") : null;
    if (!button) {
      return;
    }
    button.setAttribute("aria-expanded", String(focused));
    button.setAttribute("aria-label", "Expand section");
    button.setAttribute("title", "Expand section");
  }
  function removeFocusedZone(zone) {
    if (!zone) {
      return;
    }
    zone.classList.remove("octane-zone-focused");
    restoreZoneFocusButton(zone);
    setZoneButtonState(zone, false);
    scheduleFluidBarCharts();
    scheduleExecutionBreakdownScaling();
  }
  function findZoneByKey(key) {
    if (!key) {
      return null;
    }
    var zones = dashboard.querySelectorAll("[data-zone-key]");
    for (var index = 0; index < zones.length; index += 1) {
      if (zones[index].getAttribute("data-zone-key") === key) {
        return zones[index];
      }
    }
    return null;
  }
  function collapseFocusedZone() {
    if (!focusedZone) {
      hideExpandedBackdropIfUnused();
      return;
    }
    removeFocusedZone(focusedZone);
    focusedZone = null;
    hideExpandedBackdropIfUnused();
  }
  function focusZone(zone) {
    if (!zone) {
      return;
    }
    hideBarPopup();
    collapseExpandedCard();
    if (focusedZone && focusedZone !== zone) {
      removeFocusedZone(focusedZone);
    }
    focusedZone = zone;
    showExpandedBackdrop();
    zone.classList.add("octane-zone-focused");
    removeZoneFocusButton(zone);
    activateOverlayAccessibility(zone);
    scheduleFluidBarCharts();
  }
  function expandCard(card) {
    if (!card) {
      return;
    }
    hideBarPopup();
    collapseFocusedZone();
    if (expandedCard && expandedCard !== card) {
      removeExpandedState(expandedCard);
    }
    expandedCard = card;
    showExpandedBackdrop();
    card.classList.add("octane-expanded");
    card.setAttribute("draggable", "false");
    setExpandButtonState(card, true);
    activateOverlayAccessibility(card);
    scheduleFluidBarCharts();
  }
  function applyDeepLinkedTestFailureFocus() {
    if (typeof window.URLSearchParams !== "function") {
      return;
    }
    var parameters = new window.URLSearchParams(window.location.search || "");
    if (parameters.get("octaneFocus") !== "test-management-failures"
        || parameters.get("octaneFocusMode") !== "individual") {
      return;
    }
    var card = dashboard.querySelector(
        '[data-card-key="test-management-failures"]');
    if (card) {
      expandCard(card);
    }
  }
  expandedBackdrop.addEventListener("click", function (event) {
    if (event.target !== expandedBackdrop) {
      return;
    }
    collapseExpandedCard();
    collapseFocusedZone();
  });
  document.addEventListener("focusin", function (event) {
    var activeOverlay = activeOverlayElement();
    if (!activeOverlay || activeOverlay.contains(event.target)) {
      return;
    }
    moveFocusInto(activeOverlay);
  });
  dashboard.addEventListener("click", function (event) {
    var button = event.target.closest(".octane-tester-details-toggle");
    if (!button) {
      return;
    }
    var zone = button.closest(".octane-tester-details-zone");
    event.preventDefault();
    event.stopPropagation();
    setTesterDetailsExpanded(
        zone, button.getAttribute("aria-expanded") !== "true");
  });
  dashboard.addEventListener("click", function (event) {
    var button = event.target.closest(".octane-zone-focus-toggle");
    if (!button) {
      return;
    }
    var zone = button.closest(
        ".octane-timer-zone, .octane-test-management-zone, .octane-report-zone");
    event.preventDefault();
    event.stopPropagation();
    if (zone && zone.classList.contains("octane-zone-focused")) {
      return;
    }
    focusZone(zone);
  });
  dashboard.addEventListener("click", function (event) {
    var button = event.target.closest(".octane-defect-pane-toggle");
    if (!button) {
      return;
    }
    var container = button.closest("[data-defect-analytics]");
    if (!container) {
      var card = button.closest(".octane-chart-card");
      container = card ? card.querySelector("[data-defect-analytics]") : null;
    }
    event.preventDefault();
    event.stopPropagation();
    setDefectAnalyticsView(
        container, button.getAttribute("data-defect-target-view") || "volumes");
  });
  dashboard.addEventListener("click", function (event) {
    var button = event.target.closest(".octane-view-toggle");
    if (!button) {
      return;
    }
    var card = button.closest(".octane-chart-card");
    event.preventDefault();
    event.stopPropagation();
    hideBarPopup();
    toggleCardView(card);
  });
  dashboard.addEventListener("click", function (event) {
    var button = event.target.closest(".octane-expand-toggle");
    if (!button) {
      return;
    }
    var card = button.closest(".octane-chart-card");
    event.preventDefault();
    event.stopPropagation();
    if (card && card.classList.contains("octane-expanded")) {
      collapseExpandedCard();
      return;
    }
    expandCard(card);
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") {
      hideBarPopup();
      collapseExpandedCard();
      collapseFocusedZone();
    }
  });
  var dragged = null;
  var draggedZone = null;
  function zoneForCard(card) {
    return card.parentElement ? card.parentElement.closest(".octane-card-zone") : null;
  }
  function cardChildren(zone) {
    var cards = [];
    if (!zone) {
      return cards;
    }
    for (var index = 0; index < zone.children.length; index += 1) {
      if (zone.children[index].classList.contains("octane-chart-card")) {
        cards.push(zone.children[index]);
      }
    }
    return cards;
  }
  function cardDisplayName(card) {
    var title = card ? card.querySelector(".octane-card-title") : null;
    if (title && title.textContent.trim()) {
      return title.textContent.trim();
    }
    return "Widget";
  }
  function announceCardMove(card) {
    if (reorderStatus) {
      reorderStatus.textContent = cardDisplayName(card) + " moved.";
    }
  }
  function focusCardTool(card) {
    var tool = card ? card.querySelector(".octane-card-tools") : null;
    if (tool && typeof tool.focus === "function") {
      tool.focus({preventScroll: true});
    }
  }
  function moveCardWithKeyboard(card, key) {
    var zone = zoneForCard(card);
    var cards = cardChildren(zone);
    var currentIndex = cards.indexOf(card);
    if (!zone || currentIndex < 0 || cards.length < 2 || expandedCard) {
      return false;
    }
    if ((key === "ArrowLeft" || key === "ArrowUp") && currentIndex > 0) {
      zone.insertBefore(card, cards[currentIndex - 1]);
    } else if ((key === "ArrowRight" || key === "ArrowDown")
        && currentIndex < cards.length - 1) {
      zone.insertBefore(card, cards[currentIndex + 1].nextSibling);
    } else if (key === "Home" && currentIndex > 0) {
      zone.insertBefore(card, cards[0]);
    } else if (key === "End" && currentIndex < cards.length - 1) {
      zone.appendChild(card);
    } else {
      return false;
    }
    announceCardMove(card);
    focusCardTool(card);
    return true;
  }
  dashboard.addEventListener("keydown", function (event) {
    var tool = event.target.closest(".octane-card-tools");
    if (!tool) {
      return;
    }
    var key = event.key;
    if (key !== "ArrowLeft" && key !== "ArrowRight" && key !== "ArrowUp"
        && key !== "ArrowDown" && key !== "Home" && key !== "End") {
      return;
    }
    var card = tool.closest(".octane-chart-card");
    if (moveCardWithKeyboard(card, key)) {
      event.preventDefault();
      event.stopPropagation();
    }
  });
  dashboard.addEventListener("dragstart", function (event) {
    var card = event.target.closest(".octane-chart-card");
    if (!card) {
      return;
    }
    if (event.target.closest(
        ".octane-client-page-button, .octane-expand-toggle, "
        + ".octane-zone-focus-toggle, .octane-view-toggle")
        || card.classList.contains("octane-expanded")) {
      event.preventDefault();
      return;
    }
    dragged = card;
    draggedZone = zoneForCard(card);
    card.classList.add("octane-dragging");
    event.dataTransfer.effectAllowed = "move";
  });
  dashboard.addEventListener("dragend", function () {
    if (dragged) {
      dragged.classList.remove("octane-dragging");
    }
    dragged = null;
    draggedZone = null;
  });
  dashboard.addEventListener("dragover", function (event) {
    if (!dragged || expandedCard) {
      return;
    }
    var target = event.target.closest(".octane-chart-card");
    if (!target || target === dragged) {
      return;
    }
    var targetZone = zoneForCard(target);
    if (!draggedZone || targetZone !== draggedZone) {
      return;
    }
    event.preventDefault();
    var rect = target.getBoundingClientRect();
    var before = event.clientY < rect.top + rect.height / 2;
    draggedZone.insertBefore(dragged, before ? target : target.nextSibling);
  });
  applyDeepLinkedTestFailureFocus();
})();
