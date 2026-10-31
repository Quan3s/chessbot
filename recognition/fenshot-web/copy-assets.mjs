import { copyFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

const output = resolve("../../app/src/main/assets/fenshot");
const runtime = resolve("node_modules/onnxruntime-web/dist");
const fenshot = resolve("node_modules/@scoriiu/fenshot");

copyFileSync(
  resolve(runtime, "ort-wasm-simd-threaded.mjs"),
  resolve(output, "ort-wasm-simd-threaded.mjs"),
);
copyFileSync(
  resolve(fenshot, "model/chess-tiles-v2.onnx"),
  resolve(output, "chess-tiles-v2.onnx"),
);
const licenseOutput = resolve(output, "licenses");
mkdirSync(licenseOutput, { recursive: true });
for (const name of ["FENSHOT.txt", "ONNX_RUNTIME.txt"]) {
  copyFileSync(resolve("licenses", name), resolve(licenseOutput, name));
}
