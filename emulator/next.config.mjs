import { fileURLToPath } from "node:url";

/** @type {import('next').NextConfig} */
export default {
  devIndicators: false,
  turbopack: { root: fileURLToPath(new URL("..", import.meta.url)) },
};
