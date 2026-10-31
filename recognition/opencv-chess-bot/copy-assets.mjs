import { copyFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

const output = resolve("../../app/src/main/assets/chess-recognition");
const runtime = resolve("node_modules/onnxruntime-web/dist");

copyFileSync(
  resolve(runtime, "ort-wasm-simd-threaded.mjs"),
  resolve(output, "ort-wasm-simd-threaded.mjs"),
);
copyFileSync(
  resolve("model/piece_classifier.onnx"),
  resolve(output, "piece_classifier.onnx"),
);
const licenseOutput = resolve(output, "licenses");
mkdirSync(licenseOutput, { recursive: true });
copyFileSync(resolve("LICENSE"), resolve(licenseOutput, "OPEN-CV-CHESS-BOT.txt"));
copyFileSync(
  resolve("licenses/ONNX_RUNTIME.txt"),
  resolve(licenseOutput, "ONNX_RUNTIME.txt"),
);
