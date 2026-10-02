import {readFileSync} from "node:fs";

export const reportMarkup = readFileSync(
    "src/main/resources/io/jenkins/plugins/octanesuitegatebyembiti/actions/"
        + "OctaneGateReportAction/index.jelly", "utf8");
export const dashboardCss = readFileSync("src/main/webapp/css/octane-dashboard.css", "utf8");
export const dashboardScript = readFileSync("src/main/webapp/js/octane-dashboard.js", "utf8");

// Existing source-contract tests span markup, styling and behavior.
export const reportSource = [reportMarkup, dashboardCss, dashboardScript].join("\n");
