// Puts the files the page loads at runtime into public/: the core-car bundle, rebuilt when its
// Kotlin sources changed, and the recorded car scenarios with an index of their names.
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dirname, "../..");
const pub = resolve(import.meta.dirname, "../public");
const bundle = join(root, "core-car/build/kotlin-webpack/js/productionExecutable/rdstr.js");

const newest = (dir) =>
  Math.max(...readdirSync(dir, { recursive: true }).map((f) => statSync(join(dir, f)).mtimeMs));

if (!existsSync(bundle) || newest(join(root, "core-car/src")) > statSync(bundle).mtimeMs) {
  const win = process.platform === "win32";
  const task = [":core-car:jsBrowserProductionWebpack", "--no-daemon"];
  const { status } = spawnSync(win ? ".\\gradlew.bat" : "./gradlew", task, { cwd: root, stdio: "inherit", shell: win });
  if (status !== 0) process.exit(status ?? 1);
}
cpSync(bundle, join(pub, "rdstr.js"));

const recordings = join(root, "sim/recordings");
mkdirSync(join(pub, "recordings"), { recursive: true });
const names = readdirSync(recordings).filter((f) => f.endsWith(".jsonl"));
names.forEach((f) => cpSync(join(recordings, f), join(pub, "recordings", f)));
writeFileSync(join(pub, "recordings/index.json"), JSON.stringify(names.map((f) => f.replace(".jsonl", ""))));
