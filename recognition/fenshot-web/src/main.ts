import * as ort from "onnxruntime-web/wasm";
import { createRecognizer, resolveOrientation } from "@scoriiu/fenshot";

declare global {
  interface Window {
    ChessBotBridge: {
      onResult(requestId: string, result: string): void;
    };
    recognizeFrame(requestId: string): Promise<void>;
  }
}

ort.env.wasm.numThreads = 1;

const recognizer = createRecognizer({
  modelUrl: "/fenshot/chess-tiles-v2.onnx",
  wasmPaths: {
    mjs: "/fenshot/ort-wasm-simd-threaded.mjs",
    wasm: "/fenshot/ort-wasm-simd-threaded.wasm",
  },
});

window.recognizeFrame = async (requestId: string): Promise<void> => {
  try {
    const image = new Image();
    image.src = `/frame.jpg?request=${encodeURIComponent(requestId)}`;
    await image.decode();
    const scan = await recognizer.recognize(image);
    if (!scan) {
      window.ChessBotBridge.onResult(requestId, JSON.stringify(null));
      return;
    }

    const oriented = resolveOrientation(scan.placement);
    window.ChessBotBridge.onResult(requestId, JSON.stringify({
      placement: oriented.placement,
      whiteAtBottom: oriented.orientation === "white",
      reliable: scan.reliable,
      plausible: scan.plausible,
      minConfidence: scan.minConfidence,
      meanConfidence: scan.meanConfidence,
    }));
  } catch (error) {
    window.ChessBotBridge.onResult(requestId, JSON.stringify({
      error: String(error),
    }));
  }
};

recognizer.warmUp();
