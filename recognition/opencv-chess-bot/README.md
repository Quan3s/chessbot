# OpenCV Chess Bot piece recognizer

This packages the ONNX piece classifier from
https://github.com/7dpk/OpenCV-Chess-Bot, licensed under MIT. The model
recognizes all 64 squares, including arbitrary mid-game positions. ChessBot
uses the user's calibrated board rectangle and runs ONNX inference offline in
Android WebView; the existing legal-move validator remains authoritative.

The upstream board detector is intended to search a desktop screenshot. In
ChessBot the user has already calibrated the board bounds, so the classifier
uses that precise crop instead of re-detecting the board on every frame.

Run `npm ci && npm run build` in this directory to create the Android assets
under `app/src/main/assets/chess-recognition`.
