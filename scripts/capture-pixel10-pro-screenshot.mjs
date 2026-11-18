#!/usr/bin/env bun
/**
 * Boots a temporary Pixel 10 Pro resolution Android emulator (1280x2856 @ 480 dpi),
 * connects the app to `scripts/mock-openchamber-server.mjs`, captures a crisp PNG
 * screenshot, verifies its dimensions, and tears down cleanly.
 */

import { spawn, spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { createServer } from "node:net";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { parseArgs } from "node:util";

const PIXEL_10_PRO_WIDTH = 1280;
const PIXEL_10_PRO_HEIGHT = 2856;
const PIXEL_10_PRO_DENSITY = 480;
const APP_PACKAGE = "com.openchamber.ptt";
const MAIN_ACTIVITY = `${APP_PACKAGE}/.MainActivity`;

function printHelp() {
  console.log(`Usage: bun scripts/capture-pixel10-pro-screenshot.mjs [options]

Captures an Android emulator screenshot at Google Pixel 10 Pro resolution
(${PIXEL_10_PRO_WIDTH}x${PIXEL_10_PRO_HEIGHT} px, ${PIXEL_10_PRO_DENSITY} dpi).

Options:
  --out <path>            Output PNG path (default: "docs/screenshots/pixel-10-pro-cockpit.png")
  --screen <mode>         Screen to capture: "cockpit" | "sessions" (default: "cockpit")
  --theme <mode>          System UI theme: "light" | "dark" (default: "light")
  --apk <path>            Debug APK path (default: "app/build/outputs/apk/debug/app-debug.apk")
  --system-image <pkg>    Android system image package (default: "system-images;android-35;default;x86_64")
  --build                 Run ./gradlew assembleDebug before installing APK
  -h, --help              Show this help text

Examples:
  bun scripts/capture-pixel10-pro-screenshot.mjs
  bun scripts/capture-pixel10-pro-screenshot.mjs --screen sessions --theme dark --out /tmp/opencode/p10_sessions.png`);
}

const { values } = parseArgs({
  args: Bun.argv.slice(2),
  options: {
    out: { type: "string", default: "docs/screenshots/pixel-10-pro-cockpit.png" },
    screen: { type: "string", default: "cockpit" },
    theme: { type: "string", default: "light" },
    apk: { type: "string", default: "app/build/outputs/apk/debug/app-debug.apk" },
    "system-image": { type: "string", default: "system-images;android-35;default;x86_64" },
    build: { type: "boolean", default: false },
    help: { type: "boolean", short: "h", default: false },
  },
  strict: true,
});

if (values.help) {
  printHelp();
  process.exit(0);
}

if (values.screen !== "cockpit" && values.screen !== "sessions") {
  console.error(`Unsupported --screen "${values.screen}". Expected "cockpit" or "sessions".`);
  process.exit(1);
}
if (values.theme !== "light" && values.theme !== "dark") {
  console.error(`Unsupported --theme "${values.theme}". Expected "light" or "dark".`);
  process.exit(1);
}

const androidHome =
  process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || join(homedir(), "Android", "Sdk");
const adbBin = join(androidHome, "platform-tools", "adb");
const emulatorBin = join(androidHome, "emulator", "emulator");

function findAvdManager(sdkRoot) {
  const latest = join(sdkRoot, "cmdline-tools", "latest", "bin", "avdmanager");
  if (existsSync(latest)) return latest;
  const cmdlineDir = join(sdkRoot, "cmdline-tools");
  if (existsSync(cmdlineDir)) {
    for (const entry of readdirSync(cmdlineDir).sort().reverse()) {
      const candidate = join(cmdlineDir, entry, "bin", "avdmanager");
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

const avdManagerBin = findAvdManager(androidHome);
if (!existsSync(adbBin) || !existsSync(emulatorBin) || !avdManagerBin) {
  console.error(
    `Missing Android SDK tools under "${androidHome}". Expected platform-tools/adb, emulator/emulator, and cmdline-tools/*/bin/avdmanager.`,
  );
  process.exit(1);
}

const systemImagePkg = values["system-image"];
const sysImgParts = systemImagePkg.split(";");
if (sysImgParts.length !== 4 || !existsSync(join(androidHome, ...sysImgParts))) {
  console.error(`System image not found at "${join(androidHome, ...sysImgParts)}" for "${systemImagePkg}".`);
  process.exit(1);
}

const apkPath = resolve(values.apk);
if (values.build || !existsSync(apkPath)) {
  console.log("Building debug APK via ./gradlew assembleDebug...");
  const javaHome =
    process.env.JAVA_HOME ||
    (existsSync("/home/linuxbrew/.linuxbrew/opt/openjdk@21")
      ? "/home/linuxbrew/.linuxbrew/opt/openjdk@21"
      : undefined);
  const res = spawnSync("./gradlew", ["assembleDebug", "--console=plain"], {
    stdio: "inherit",
    env: {
      ...process.env,
      ANDROID_HOME: androidHome,
      ...(javaHome ? { JAVA_HOME: javaHome } : {}),
    },
  });
  if (res.status !== 0) {
    process.exit(res.status ?? 1);
  }
}

if (!existsSync(apkPath)) {
  console.error(`APK not found at ${apkPath}`);
  process.exit(1);
}

function runCmd(bin, args, opts = {}) {
  const res = spawnSync(bin, args, {
    encoding: "utf8",
    ...opts,
  });
  if (res.status !== 0 && !opts.allowFailure) {
    const stderr = (res.stderr || "").trim();
    const stdout = (res.stdout || "").trim();
    throw new Error(`Command failed: ${bin} ${args.join(" ")}\n${stderr || stdout}`);
  }
  return res;
}

function runCmdBinary(bin, args) {
  const res = spawnSync(bin, args, { encoding: null, maxBuffer: 64 * 1024 * 1024 });
  if (res.status !== 0) {
    throw new Error(`Command failed: ${bin} ${args.join(" ")}`);
  }
  return res.stdout;
}

function pickFreeTcpPort() {
  return new Promise((resolvePort, reject) => {
    const srv = createServer();
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      srv.close((err) => (err ? reject(err) : resolvePort(port)));
    });
    srv.on("error", reject);
  });
}

function pickFreeEmulatorPort() {
  const devicesOut = runCmd(adbBin, ["devices"], { allowFailure: true }).stdout || "";
  const activePorts = new Set(
    [...devicesOut.matchAll(/emulator-(\d+)/g)].map((m) => Number.parseInt(m[1], 10)),
  );
  for (let p = 5556; p <= 5650; p += 2) {
    if (!activePorts.has(p)) return p;
  }
  throw new Error("No free Android emulator console port available in 5556..5650.");
}

function decodeXmlEntities(value) {
  return value
    .replaceAll("&amp;", "&")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", "\"")
    .replaceAll("&apos;", "'");
}

function parseNodes(xml) {
  const nodes = [];
  const nodeRe = /<node\b([^>]*?)\/?>/g;
  let match;
  while ((match = nodeRe.exec(xml)) !== null) {
    const attrsText = match[1];
    const attrs = {};
    const attrRe = /([a-zA-Z0-9:_-]+)="([^"]*)"/g;
    let am;
    while ((am = attrRe.exec(attrsText)) !== null) {
      attrs[am[1]] = decodeXmlEntities(am[2]);
    }
    const boundsMatch = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(attrs.bounds || "");
    if (!boundsMatch) continue;
    const left = Number.parseInt(boundsMatch[1], 10);
    const top = Number.parseInt(boundsMatch[2], 10);
    const right = Number.parseInt(boundsMatch[3], 10);
    const bottom = Number.parseInt(boundsMatch[4], 10);
    nodes.push({
      text: attrs.text || "",
      desc: attrs["content-desc"] || "",
      className: attrs.class || "",
      cx: Math.round((left + right) / 2),
      cy: Math.round((top + bottom) / 2),
    });
  }
  return nodes;
}

function dumpUiNodes(serial) {
  runCmd(adbBin, ["-s", serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"]);
  const xml = runCmd(adbBin, ["-s", serial, "shell", "cat", "/sdcard/window_dump.xml"]).stdout || "";
  return parseNodes(xml);
}

async function waitForNode(serial, predicate, label, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const nodes = dumpUiNodes(serial);
      const found = nodes.find(predicate);
      if (found) return found;
    } catch {
      // Retry while window settles
    }
    await Bun.sleep(400);
  }
  throw new Error(`Timed out waiting for UI node: ${label}`);
}

async function tapNode(serial, predicate, label, timeoutMs = 20_000) {
  const node = await waitForNode(serial, predicate, label, timeoutMs);
  runCmd(adbBin, ["-s", serial, "shell", "input", "tap", String(node.cx), String(node.cy)]);
  return node;
}

function escapeAdbText(text) {
  return text.replace(/([\\ "'()&|;<>?*$`~])/g, "\\$1");
}

function readPngDimensions(pngBuf) {
  const sig = "89504e470d0a1a0a";
  if (pngBuf.length < 24 || pngBuf.subarray(0, 8).toString("hex") !== sig) {
    throw new Error("Captured buffer is not a valid PNG image.");
  }
  return {
    width: pngBuf.readUInt32BE(16),
    height: pngBuf.readUInt32BE(20),
  };
}

mkdirSync("/tmp/opencode", { recursive: true });
const avdHome = mkdtempSync("/tmp/opencode/pixel10pro-");
const avdName = "pixel_10_pro_capture";
const emuPort = pickFreeEmulatorPort();
const serial = `emulator-${emuPort}`;
const mockPort = await pickFreeTcpPort();
const outPath = resolve(values.out);

let mockProc = null;
let emuProc = null;

try {
  console.log(`Creating temporary Pixel 10 Pro AVD (${PIXEL_10_PRO_WIDTH}x${PIXEL_10_PRO_HEIGHT} @ ${PIXEL_10_PRO_DENSITY} dpi)...`);
  runCmd(
    avdManagerBin,
    ["create", "avd", "-n", avdName, "-k", systemImagePkg, "--device", "pixel_7_pro", "--force"],
    {
      env: { ...process.env, ANDROID_HOME: androidHome, ANDROID_AVD_HOME: avdHome },
    },
  );

  const configIniPath = join(avdHome, `${avdName}.avd`, "config.ini");
  let configIni = readFileSync(configIniPath, "utf8");
  const replacements = {
    "hw.lcd.width": String(PIXEL_10_PRO_WIDTH),
    "hw.lcd.height": String(PIXEL_10_PRO_HEIGHT),
    "hw.lcd.density": String(PIXEL_10_PRO_DENSITY),
    "hw.gpu.enabled": "yes",
    "hw.ramSize": "2048M",
  };
  for (const [k, v] of Object.entries(replacements)) {
    const re = new RegExp(`^${k.replaceAll(".", "\\.")}=.*$`, "m");
    configIni = re.test(configIni) ? configIni.replace(re, `${k}=${v}`) : `${configIni.trimEnd()}\n${k}=${v}\n`;
  }
  writeFileSync(configIniPath, configIni);

  console.log(`Starting local mock OpenChamber server on 127.0.0.1:${mockPort}...`);
  mockProc = spawn("bun", ["scripts/mock-openchamber-server.mjs", "--ports", String(mockPort), "--host", "127.0.0.1"], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  await new Promise((resolveReady, rejectReady) => {
    const timer = setTimeout(() => rejectReady(new Error("Mock server startup timed out")), 10_000);
    mockProc.stdout.on("data", (chunk) => {
      if (chunk.toString().includes(`127.0.0.1:${mockPort}`)) {
        clearTimeout(timer);
        resolveReady();
      }
    });
    mockProc.stderr.on("data", (d) => process.stderr.write(d));
  });

  console.log(`Booting ${serial} headless...`);
  emuProc = spawn(
    emulatorBin,
    [
      "-avd",
      avdName,
      "-port",
      String(emuPort),
      "-no-window",
      "-no-audio",
      "-no-boot-anim",
      "-no-snapshot",
      "-gpu",
      "swiftshader_indirect",
    ],
    {
      env: { ...process.env, ANDROID_HOME: androidHome, ANDROID_AVD_HOME: avdHome },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );

  const bootDeadline = Date.now() + 120_000;
  let booted = false;
  while (Date.now() < bootDeadline) {
    const res = runCmd(adbBin, ["-s", serial, "shell", "getprop", "sys.boot_completed"], {
      allowFailure: true,
    });
    if ((res.stdout || "").trim() === "1") {
      booted = true;
      break;
    }
    await Bun.sleep(1000);
  }
  if (!booted) {
    throw new Error(`Timed out waiting for ${serial} to boot.`);
  }

  const wmSize = (runCmd(adbBin, ["-s", serial, "shell", "wm", "size"]).stdout || "").trim();
  const wmDensity = (runCmd(adbBin, ["-s", serial, "shell", "wm", "density"]).stdout || "").trim();
  console.log(`${serial} ready — ${wmSize}; ${wmDensity}`);

  runCmd(adbBin, ["-s", serial, "shell", "cmd", "uimode", "night", values.theme === "dark" ? "yes" : "no"]);
  runCmd(adbBin, ["-s", serial, "shell", "settings", "put", "system", "font_scale", "1.0"]);
  runCmd(adbBin, ["-s", serial, "install", "-r", "-g", apkPath]);
  runCmd(adbBin, ["-s", serial, "shell", "am", "start", "-n", MAIN_ACTIVITY]);

  await tapNode(serial, (n) => n.text === "Server URL", "Server URL field");
  await Bun.sleep(250);
  runCmd(adbBin, ["-s", serial, "shell", "input", "text", escapeAdbText(`http://10.0.2.2:${mockPort}`)]);
  runCmd(adbBin, ["-s", serial, "shell", "input", "keyevent", "4"]);
  await Bun.sleep(300);

  await tapNode(serial, (n) => n.text === "Password", "Password field");
  await Bun.sleep(250);
  runCmd(adbBin, ["-s", serial, "shell", "input", "text", "demo"]);
  runCmd(adbBin, ["-s", serial, "shell", "input", "keyevent", "4"]);
  await Bun.sleep(300);

  await tapNode(serial, (n) => n.text === "Connect", "Connect button");

  await tapNode(serial, (n) => n.desc === "Switch session", "Switch session pill");
  await tapNode(serial, (n) => n.text === "Voice cockpit PR review", "Voice cockpit PR review row");
  await waitForNode(serial, (n) => n.text === "Latest reply", "Latest reply card");

  if (values.screen === "sessions") {
    await tapNode(serial, (n) => n.desc === "Switch session", "Switch session pill");
    await waitForNode(serial, (n) => n.text === "Select Session", "Select Session sheet");
    await tapNode(serial, (n) => n.desc === "2 subagents", "Expand subagents toggle").catch(() => {});
  }

  await Bun.sleep(700);
  const png = runCmdBinary(adbBin, ["-s", serial, "exec-out", "screencap", "-p"]);
  const dims = readPngDimensions(png);
  if (dims.width !== PIXEL_10_PRO_WIDTH || dims.height !== PIXEL_10_PRO_HEIGHT) {
    throw new Error(
      `Expected ${PIXEL_10_PRO_WIDTH}x${PIXEL_10_PRO_HEIGHT} PNG, got ${dims.width}x${dims.height}.`,
    );
  }

  mkdirSync(dirname(outPath), { recursive: true });
  writeFileSync(outPath, png);
  console.log(`Captured ${dims.width}x${dims.height} screenshot -> ${outPath} (${png.length} bytes)`);
} finally {
  if (mockProc) {
    mockProc.kill("SIGTERM");
  }
  runCmd(adbBin, ["-s", serial, "emu", "kill"], { allowFailure: true });
  if (emuProc && emuProc.exitCode === null) {
    await Bun.sleep(1000);
    if (emuProc.exitCode === null) emuProc.kill("SIGKILL");
  }
  rmSync(avdHome, { recursive: true, force: true });
}
