// Puts the files the page loads at runtime into public/: the core-car bundle, rebuilt when its
// Kotlin sources changed, the country borders the unit uses too, and the recorded car scenarios with an index of their names.
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readdirSync, statSync, utimesSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dirname, "../..");
const pub = resolve(import.meta.dirname, "../public");
const bundle = join(root, "core-car/build/kotlin-webpack/js/productionExecutable/rdstr.js");

const newest = (dir) =>
  Math.max(...readdirSync(dir, { recursive: true }).map((f) => statSync(join(dir, f)).mtimeMs));

if (!existsSync(bundle) || newest(join(root, "core-car/src")) > statSync(bundle).mtimeMs) {
  const gradlew = process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew";
  const { status } = spawnSync(`${gradlew} :core-car:jsBrowserProductionWebpack --no-daemon`, { cwd: root, stdio: "inherit", shell: true });
  if (status !== 0) process.exit(status ?? 1);
  // Gradle leaves an up-to-date output untouched, which would repeat this build on every start
  utimesSync(bundle, new Date(), new Date());
}
cpSync(bundle, join(pub, "rdstr.js"));
cpSync(join(root, "core-map/src/main/resources/ro/rdstr/map/countries.geojson"), join(pub, "maps/countries.geojson"));

const recordings = join(root, "sim/recordings");
mkdirSync(join(pub, "recordings"), { recursive: true });
const names = readdirSync(recordings).filter((f) => f.endsWith(".jsonl"));
names.forEach((f) => cpSync(join(recordings, f), join(pub, "recordings", f)));
writeFileSync(join(pub, "recordings/index.json"), JSON.stringify(names.map((f) => f.replace(".jsonl", ""))));
