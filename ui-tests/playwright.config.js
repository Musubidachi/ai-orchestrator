// @ts-check
const { defineConfig } = require("@playwright/test");
const path = require("node:path");

// Runs at config load, before webServer is launched.
require("./global-setup")();

/**
 * Runs the real editor against a throwaway Forge root.
 *
 * The fixture root is built by global-setup rather than pointed at the user's own wishlist: a
 * browser test that reorders specs or writes files must not touch real work, and a suite that
 * depends on whatever happens to be queued today is not a regression test.
 */
module.exports = defineConfig({
  testDir: "./tests",
  timeout: 30_000,
  fullyParallel: false,        // one server, one wishlist on disk
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:7788",
    viewport: { width: 1280, height: 900 },
    trace: "retain-on-failure",
  },
  webServer: {
    command: [
      "java",
      "-jar",
      path.join(__dirname, "..", "forge", "target", "forge.jar"),
      "ui",
      "--port", "7788",
      "--root", path.join(__dirname, ".fixture"),
    ].join(" "),
    url: "http://localhost:7788",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
