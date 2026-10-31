import { resolve } from "node:path";
import { defineConfig } from "vite";

const assetsDir = resolve(__dirname, "../../app/src/main/assets/fenshot");

export default defineConfig({
  base: "/",
  build: {
    outDir: assetsDir,
    emptyOutDir: true,
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        assetFileNames: (asset) => asset.name?.endsWith(".wasm")
          ? "ort-wasm-simd-threaded.wasm"
          : "assets/[name]-[hash][extname]",
      },
    },
  },
});
