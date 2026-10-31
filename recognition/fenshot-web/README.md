# Offline board recognition assets

This WebView bundle uses Fenshot 0.1.5 and ONNX Runtime Web 1.26.0. It runs
locally; screenshots and model inference are not sent to a server.

Run `npm ci && npm run build` in this directory to create the Android assets
under `app/src/main/assets/fenshot`.

Fenshot and ONNX Runtime Web are distributed under MIT licenses. Their license
texts are included in `licenses/` in this directory.
