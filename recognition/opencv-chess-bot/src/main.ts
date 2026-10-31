import * as ort from "onnxruntime-web/wasm";

declare global {
  interface Window {
    ChessBotBridge: {
      onResult(requestId: string, result: string): void;
    };
    recognizeFrame(requestId: string): Promise<void>;
  }
}

const INPUT_SIZE = 48;
const BOARD_SIZE = INPUT_SIZE * 8;
const CLASS_COUNT = 13;
const EMPTY = 0;
const WHITE_KING = 6;
const BLACK_PAWN = 7;
const BLACK_KING = 12;
const FEN_SYMBOLS = ["", "P", "N", "B", "R", "Q", "K", "p", "n", "b", "r", "q", "k"];
const CONFIDENCE_FLOOR = 0.5;

ort.env.wasm.numThreads = 1;
ort.env.wasm.wasmPaths = {
  mjs: "/chess-recognition/ort-wasm-simd-threaded.mjs",
  wasm: "/chess-recognition/ort-wasm-simd-threaded.wasm",
};

const sessionPromise = ort.InferenceSession.create(
  "/chess-recognition/piece_classifier.onnx",
  { executionProviders: ["wasm"] },
);

function inferWhiteAtBottom(grid: number[][]): boolean {
  let score = 0;
  for (let row = 0; row < 8; row++) {
    for (const piece of grid[row]) {
      if (piece === EMPTY) continue;
      const bottom = row >= 4;
      const white = piece <= WHITE_KING;
      score += white === bottom ? 1 : -1;
    }
  }
  return score >= 0;
}

function placementFor(grid: number[][], whiteAtBottom: boolean): string {
  const ranks: string[] = [];
  for (let rank = 7; rank >= 0; rank--) {
    let fenRank = "";
    let empty = 0;
    for (let file = 0; file < 8; file++) {
      const row = whiteAtBottom ? 7 - rank : rank;
      const col = whiteAtBottom ? file : 7 - file;
      const symbol = FEN_SYMBOLS[grid[row][col]];
      if (!symbol) {
        empty++;
      } else {
        if (empty > 0) fenRank += empty;
        fenRank += symbol;
        empty = 0;
      }
    }
    if (empty > 0) fenRank += empty;
    ranks.push(fenRank);
  }
  return ranks.join("/");
}

function isPlausible(grid: number[][]): boolean {
  let whiteKings = 0;
  let blackKings = 0;
  for (let row = 0; row < 8; row++) {
    for (const piece of grid[row]) {
      if (piece === WHITE_KING) whiteKings++;
      if (piece === BLACK_KING) blackKings++;
      if ((row === 0 || row === 7) && (piece === 1 || piece === BLACK_PAWN)) {
        return false;
      }
    }
  }
  return whiteKings === 1 && blackKings === 1;
}

async function classifyBoard(image: HTMLImageElement): Promise<{
  placement: string;
  placementOtherOrientation: string;
  whiteAtBottom: boolean;
  reliable: boolean;
  plausible: boolean;
  minConfidence: number;
  meanConfidence: number;
}> {
  const canvas = document.createElement("canvas");
  canvas.width = BOARD_SIZE;
  canvas.height = BOARD_SIZE;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) throw new Error("Canvas 2d context unavailable");
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = "high";
  context.drawImage(image, 0, 0, BOARD_SIZE, BOARD_SIZE);
  const pixels = context.getImageData(0, 0, BOARD_SIZE, BOARD_SIZE).data;
  const planeSize = INPUT_SIZE * INPUT_SIZE;
  const input = new Float32Array(64 * 3 * planeSize);

  for (let row = 0; row < 8; row++) {
    for (let col = 0; col < 8; col++) {
      const square = row * 8 + col;
      const plane = square * 3 * planeSize;
      for (let y = 0; y < INPUT_SIZE; y++) {
        for (let x = 0; x < INPUT_SIZE; x++) {
          const pixel = ((row * INPUT_SIZE + y) * BOARD_SIZE
            + col * INPUT_SIZE + x) * 4;
          const offset = y * INPUT_SIZE + x;
          input[plane + offset] = pixels[pixel + 2] / 255;
          input[plane + planeSize + offset] = pixels[pixel + 1] / 255;
          input[plane + 2 * planeSize + offset] = pixels[pixel] / 255;
        }
      }
    }
  }

  const tensor = new ort.Tensor("float32", input, [64, 3, INPUT_SIZE, INPUT_SIZE]);
  const session = await sessionPromise;
  const outputs = await session.run({ [session.inputNames[0]]: tensor });
  const logits = outputs[session.outputNames[0]].data as Float32Array;
  if (logits.length !== 64 * CLASS_COUNT) {
    throw new Error(`Unexpected classifier output size: ${logits.length}`);
  }

  const grid: number[][] = [];
  let minConfidence = 1;
  let confidenceSum = 0;
  for (let row = 0; row < 8; row++) {
    const rank: number[] = [];
    for (let col = 0; col < 8; col++) {
      const square = row * 8 + col;
      let maxLogit = -Infinity;
      let bestClass = EMPTY;
      for (let cls = 0; cls < CLASS_COUNT; cls++) {
        const value = logits[square * CLASS_COUNT + cls];
        if (value > maxLogit) {
          maxLogit = value;
          bestClass = cls;
        }
      }
      let expSum = 0;
      for (let cls = 0; cls < CLASS_COUNT; cls++) {
        expSum += Math.exp(logits[square * CLASS_COUNT + cls] - maxLogit);
      }
      const confidence = 1 / expSum;
      minConfidence = Math.min(minConfidence, confidence);
      confidenceSum += confidence;
      rank.push(bestClass);
    }
    grid.push(rank);
  }

  const whiteAtBottom = inferWhiteAtBottom(grid);
  const plausible = isPlausible(grid);
  return {
    placement: placementFor(grid, whiteAtBottom),
    placementOtherOrientation: placementFor(grid, !whiteAtBottom),
    whiteAtBottom,
    reliable: minConfidence >= CONFIDENCE_FLOOR,
    plausible,
    minConfidence,
    meanConfidence: confidenceSum / 64,
  };
}

window.recognizeFrame = async (requestId: string): Promise<void> => {
  try {
    const image = new Image();
    image.src = `/frame.jpg?request=${encodeURIComponent(requestId)}`;
    await image.decode();
    const result = await classifyBoard(image);
    window.ChessBotBridge.onResult(requestId, JSON.stringify(result));
  } catch (error) {
    window.ChessBotBridge.onResult(requestId, JSON.stringify({ error: String(error) }));
  }
};

void sessionPromise.catch(() => undefined);
