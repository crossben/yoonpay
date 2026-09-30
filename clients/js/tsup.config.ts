import { defineConfig } from "tsup";

// The generated/ sources are bundled into dist: npm consumers never see them.
export default defineConfig({
  entry: {
    index: "src/index.ts",
    "adapters/express": "src/adapters/express.ts",
    "adapters/fastify": "src/adapters/fastify.ts",
    "adapters/nest": "src/adapters/nest.ts",
    "adapters/next": "src/adapters/next.ts",
  },
  format: ["esm", "cjs"],
  dts: true,
  sourcemap: true,
  clean: true,
  target: "es2020",
  platform: "neutral",
});
