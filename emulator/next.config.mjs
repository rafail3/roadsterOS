import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/** @type {import('next').NextConfig} */
export default {
  devIndicators: false,
  // the About page shows the version the unit would show
  env: { RDSTR_VERSION: readFileSync(new URL("../version.txt", import.meta.url), "utf8").trim() },
  turbopack: { root: fileURLToPath(new URL("..", import.meta.url)) },
};
