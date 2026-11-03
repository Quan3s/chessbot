#!/usr/bin/env python3
"""Self-hosted Stockfish 19 / Maia-2 API for ChessBot Assist."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import ipaddress
import json
import os
import platform
import secrets
import shutil
import ssl
import sys
import tarfile
import tempfile
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
DEFAULT_CONFIG = ROOT / "config.json"
STOCKFISH_TAG = "sf_19"
STOCKFISH_ASSET = "stockfish-linux-x86-64-universal.tar.gz"
STOCKFISH_RELEASE_URL = (
    "https://api.github.com/repos/official-stockfish/Stockfish/releases/tags/sf_19"
)
MAX_REQUEST_BYTES = 16 * 1024
MAX_DOWNLOAD_BYTES = 160 * 1024 * 1024
MAX_MAIA_ELO = 2000
MIN_MAIA_ELO = 1100

DEFAULTS: dict[str, Any] = {
    "engine": "stockfish",
    "maia_model": "rapid",
    "maia_elo": 2000,
    "movetime_ms": 5000,
    "threads": max(1, min(16, os.cpu_count() or 1)),
    "hash_mb": 1024,
    "stockfish_path": str(ROOT / "bin" / "stockfish-19"),
    "host": "0.0.0.0",
    "port": 8765,
    "api_key": "",
}


def load_config(path: Path) -> dict[str, Any]:
    config = dict(DEFAULTS)
    if path.exists():
        try:
            with path.open("r", encoding="utf-8") as file:
                stored = json.load(file)
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError(f"Không đọc được config {path}: {error}") from error
        if not isinstance(stored, dict):
            raise ValueError(f"Config {path} phải là một JSON object.")
        config.update(stored)
    validate_config(config)
    return config


def validate_config(config: dict[str, Any]) -> None:
    if config.get("engine") not in {"stockfish", "maia"}:
        raise ValueError("engine phải là stockfish hoặc maia.")
    if config.get("maia_model") not in {"rapid", "blitz"}:
        raise ValueError("maia_model phải là rapid hoặc blitz.")
    if not MIN_MAIA_ELO <= int(config.get("maia_elo", 2000)) <= MAX_MAIA_ELO:
        raise ValueError("maia_elo phải nằm trong khoảng 1100–2000.")
    if not 100 <= int(config.get("movetime_ms", 5000)) <= 60000:
        raise ValueError("movetime_ms phải nằm trong khoảng 100–60000.")
    if not 1 <= int(config.get("threads", 1)) <= 256:
        raise ValueError("threads phải nằm trong khoảng 1–256.")
    if not 16 <= int(config.get("hash_mb", 1024)) <= 65536:
        raise ValueError("hash_mb phải nằm trong khoảng 16–65536.")
    if not 1 <= int(config.get("port", 8765)) <= 65535:
        raise ValueError("port không hợp lệ.")
    for key in ("host", "stockfish_path", "api_key"):
        if not isinstance(config.get(key), str):
            raise ValueError(f"{key} phải là chuỗi.")


def save_config(path: Path, config: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def prompt_value(label: str, current: Any, parse: Any = str) -> Any:
    try:
        raw = input(f"{label} [{current}]: ").strip()
    except EOFError:
        return current
    if not raw:
        return current
    try:
        return parse(raw)
    except (TypeError, ValueError):
        print("Giá trị không hợp lệ; giữ nguyên lựa chọn hiện tại.")
        return current


def prompt_configuration(config: dict[str, Any]) -> dict[str, Any]:
    print("\nChọn bot: [1] Stockfish 19 (toàn lực)  [2] Maia-2")
    choice = prompt_value("Bot", "1" if config["engine"] == "stockfish" else "2")
    config["engine"] = "maia" if choice == "2" else "stockfish"

    if config["engine"] == "maia":
        print("Maia-2 hỗ trợ Rapid hoặc Blitz; mức Elo được mô hình hóa tối đa là 2000.")
        config["maia_model"] = prompt_value("Model (rapid/blitz)", config["maia_model"])
        if config["maia_model"] not in {"rapid", "blitz"}:
            print("Model không hợp lệ; dùng rapid.")
            config["maia_model"] = "rapid"
        config["maia_elo"] = prompt_value("Elo Maia (1100–2000)", config["maia_elo"], int)
        config["maia_elo"] = max(MIN_MAIA_ELO, min(MAX_MAIA_ELO, config["maia_elo"]))
    else:
        config["threads"] = prompt_value("Số luồng Stockfish", config["threads"], int)
        config["hash_mb"] = prompt_value("Hash RAM (MB)", config["hash_mb"], int)
        config["movetime_ms"] = prompt_value(
            "Thời gian nghĩ mỗi nước (ms)", config["movetime_ms"], int
        )
        config["threads"] = max(1, min(256, config["threads"]))
        config["hash_mb"] = max(16, min(65536, config["hash_mb"]))
        config["movetime_ms"] = max(100, min(60000, config["movetime_ms"]))
        path = Path(config["stockfish_path"]).expanduser()
        if not path.is_file():
            answer = prompt_value(
                "Tự tải binary Stockfish 19 chính thức cho Linux x86_64? Enter = Yes",
                "yes",
            )
            config["auto_download_stockfish"] = answer.lower() not in {"n", "no", "0"}
    return config


def download_stockfish(destination: Path) -> Path:
    if platform.system() != "Linux" or platform.machine().lower() not in {
        "x86_64",
        "amd64",
    }:
        raise RuntimeError(
            "Tự tải Stockfish 19 hiện hỗ trợ Linux x86_64; "
            "hãy cài binary phù hợp rồi đặt stockfish_path trong config."
        )

    request = urllib.request.Request(
        STOCKFISH_RELEASE_URL, headers={"Accept": "application/vnd.github+json"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.loads(response.read(2 * 1024 * 1024))
    if release.get("tag_name") != STOCKFISH_TAG or release.get("draft") or release.get(
        "prerelease"
    ):
        raise RuntimeError("Không xác minh được release Stockfish 19 chính thức.")
    asset = next(
        (
            item
            for item in release.get("assets", [])
            if item.get("name") == STOCKFISH_ASSET
        ),
        None,
    )
    expected = asset.get("digest", "") if asset else ""
    url = asset.get("browser_download_url", "") if asset else ""
    prefix = "https://github.com/official-stockfish/Stockfish/releases/download/sf_19/"
    if (
        not asset
        or not expected.startswith("sha256:")
        or not url.startswith(prefix)
        or url != prefix + STOCKFISH_ASSET
        or int(asset.get("size", 0)) > MAX_DOWNLOAD_BYTES
    ):
        raise RuntimeError("Release Stockfish 19 thiếu binary hoặc checksum hợp lệ.")

    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix="chessbot-sf-", dir=destination.parent
    ) as temporary_dir:
        archive = Path(temporary_dir) / STOCKFISH_ASSET
        digest = hashlib.sha256()
        request = urllib.request.Request(url, headers={"User-Agent": "ChessBotAssist"})
        with urllib.request.urlopen(request, timeout=60) as response, archive.open("wb") as out:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > MAX_DOWNLOAD_BYTES:
                    raise RuntimeError("Gói Stockfish vượt quá giới hạn kích thước.")
                digest.update(chunk)
                out.write(chunk)
        if digest.hexdigest() != expected.removeprefix("sha256:"):
            raise RuntimeError("SHA-256 binary Stockfish không khớp; đã hủy tải.")

        binary_path: Path | None = None
        network_paths: list[Path] = []
        with tarfile.open(archive, "r:gz") as bundle:
            for member in bundle.getmembers():
                if not member.isfile() or member.size > MAX_DOWNLOAD_BYTES:
                    continue
                name = Path(member.name).name.lower()
                if not name.endswith(".nnue") and not name.startswith("stockfish"):
                    continue
                stream = bundle.extractfile(member)
                if stream is None:
                    continue
                extracted = Path(temporary_dir) / Path(member.name).name
                with extracted.open("wb") as out:
                    while chunk := stream.read(1024 * 1024):
                        out.write(chunk)
                if name.endswith(".nnue"):
                    network_paths.append(extracted)
                elif extracted.read_bytes()[:4] == b"\x7fELF":
                    binary_path = extracted
            if binary_path is None:
                raise RuntimeError("Không tìm thấy binary Linux trong gói Stockfish.")
            temporary_binary = destination.with_suffix(".tmp")
            shutil.copyfile(binary_path, temporary_binary)
            os.chmod(temporary_binary, 0o700)
            temporary_binary.replace(destination)
            for network in network_paths:
                target = destination.parent / network.name
                if not target.exists():
                    network.replace(target)
        if not destination.is_file():
            raise RuntimeError("Không tìm thấy binary Linux trong gói Stockfish.")
    return destination


class StockfishRunner:
    def __init__(self, config: dict[str, Any]):
        import chess.engine

        binary = Path(config["stockfish_path"]).expanduser()
        if not binary.is_file() and config.get("auto_download_stockfish", True):
            binary = download_stockfish(binary)
            config["stockfish_path"] = str(binary)
        if not binary.is_file():
            raise FileNotFoundError(
                f"Không tìm thấy Stockfish tại {binary}; kiểm tra stockfish_path."
            )
        self.chess = __import__("chess")
        self.chess_engine = chess.engine
        self.engine = chess.engine.SimpleEngine.popen_uci(
            str(binary), timeout=30, setpgrp=True, cwd=str(binary.parent)
        )
        options = self.engine.options
        settings: dict[str, Any] = {}
        for option, value in (
            ("Threads", int(config["threads"])),
            ("Hash", int(config["hash_mb"])),
            ("UCI_LimitStrength", False),
            ("Skill Level", 20),
        ):
            if option in options:
                settings[option] = value
        self.engine.configure(settings)
        self.name = self.engine.id.get("name", "Stockfish 19")
        print(
            f"{self.name}: {config['threads']} threads, {config['hash_mb']} MB, "
            "UCI_LimitStrength=false, Skill Level=20"
        )

    def best_move(self, fen: str, movetime_ms: int, elo: int) -> str:
        board = self.chess.Board(fen)
        result = self.engine.play(
            board, self.chess_engine.Limit(time=max(0.1, movetime_ms / 1000))
        )
        if result.move is None:
            raise ValueError("Vị trí không có nước đi hợp lệ.")
        return result.move.uci()

    def close(self) -> None:
        self.engine.quit()


class MaiaRunner:
    def __init__(self, config: dict[str, Any]):
        from maia2 import inference, model

        self.model = model.from_pretrained(
            type=config["maia_model"], device="auto", save_root=str(ROOT / "models")
        )
        self.prepared = inference.prepare()
        self.inference = inference
        self.name = f"Maia-2 {config['maia_model'].title()}"
        print(f"Đã nạp {self.name}; Elo mô hình 1100–{MAX_MAIA_ELO}.")

    def best_move(self, fen: str, movetime_ms: int, elo: int) -> str:
        if not MIN_MAIA_ELO <= elo <= MAX_MAIA_ELO or elo % 100:
            raise ValueError("Elo Maia phải là bội số 100 trong khoảng 1100–2000.")
        probabilities, _ = self.inference.inference_each(
            self.model, self.prepared, fen, elo, elo
        )
        if not probabilities:
            raise ValueError("Maia không tìm được nước đi.")
        return next(iter(probabilities))

    def close(self) -> None:
        del self.model


class MoveService:
    def __init__(self, config: dict[str, Any], runner: Any | None = None):
        self.config = config
        if runner is None:
            runner = StockfishRunner(config) if config["engine"] == "stockfish" else MaiaRunner(config)
        self.runner = runner
        self.lock = threading.Lock()

    def health(self) -> dict[str, Any]:
        return {
            "ok": True,
            "engine": self.config["engine"],
            "name": self.runner.name,
            "maia_max_elo": MAX_MAIA_ELO,
        }

    def best_move(self, request: dict[str, Any]) -> dict[str, Any]:
        import chess

        engine = request.get("engine")
        if engine != self.config["engine"]:
            raise ValueError(f"Server đang cấu hình bot {self.config['engine']}.")
        fen = request.get("fen")
        if not isinstance(fen, str) or not 1 <= len(fen) <= 256:
            raise ValueError("FEN không hợp lệ.")
        try:
            board = chess.Board(fen)
        except ValueError as error:
            raise ValueError("FEN không hợp lệ.") from error
        if not board.is_valid():
            raise ValueError("Vị trí FEN không hợp lệ.")
        movetime_ms = request.get("movetime_ms", self.config["movetime_ms"])
        elo = request.get("elo", self.config["maia_elo"])
        if isinstance(movetime_ms, bool) or not isinstance(movetime_ms, int):
            raise ValueError("movetime_ms phải là số nguyên.")
        if not 100 <= movetime_ms <= 60000:
            raise ValueError("movetime_ms phải nằm trong khoảng 100–60000.")
        if isinstance(elo, bool) or not isinstance(elo, int):
            raise ValueError("elo phải là số nguyên.")
        if self.config["engine"] == "maia" and (
            not MIN_MAIA_ELO <= elo <= MAX_MAIA_ELO or elo % 100
        ):
            raise ValueError("Elo Maia phải là bội số 100 trong khoảng 1100–2000.")
        with self.lock:
            move = self.runner.best_move(fen, movetime_ms, elo)
        try:
            parsed_move = chess.Move.from_uci(move)
        except (TypeError, ValueError) as error:
            raise RuntimeError("Engine trả về nước đi không hợp lệ.") from error
        if parsed_move not in board.legal_moves:
            raise RuntimeError("Engine trả về nước đi trái luật.")
        return {"move": move, "engine": self.config["engine"]}

    def close(self) -> None:
        self.runner.close()


def make_handler(
    service: MoveService, api_key: str, https_enabled: bool = False
) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "ChessBotHost/1.0"

        def _authorized(self) -> bool:
            if not https_enabled:
                try:
                    client_ip = ipaddress.ip_address(self.client_address[0])
                except ValueError:
                    self._send_json(403, {"error": "HTTP chỉ được phép trong mạng LAN."})
                    return False
                if not (
                    client_ip.is_private
                    or client_ip.is_loopback
                    or client_ip.is_link_local
                ):
                    self._send_json(403, {"error": "HTTP chỉ được phép trong mạng LAN."})
                    return False
            provided = self.headers.get("Authorization", "")
            expected = "Bearer " + api_key
            if hmac.compare_digest(provided, expected):
                return True
            self._send_json(401, {"error": "API key không đúng."})
            return False

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:
            if self.path != "/health":
                self._send_json(404, {"error": "Không tìm thấy endpoint."})
                return
            if self._authorized():
                self._send_json(200, service.health())

        def do_POST(self) -> None:
            if self.path != "/bestmove":
                self._send_json(404, {"error": "Không tìm thấy endpoint."})
                return
            if not self._authorized():
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 1 <= length <= MAX_REQUEST_BYTES:
                    raise ValueError("Kích thước request không hợp lệ.")
                raw = self.rfile.read(length)
                payload = json.loads(raw)
                if not isinstance(payload, dict):
                    raise ValueError("Request phải là JSON object.")
                response = service.best_move(payload)
                self._send_json(200, response)
            except (ValueError, json.JSONDecodeError) as error:
                self._send_json(400, {"error": str(error)})
            except Exception:
                print("Lỗi xử lý nước đi:", file=sys.stderr)
                import traceback

                traceback.print_exc()
                self._send_json(500, {"error": "Lỗi engine trên máy chủ."})

        def log_message(self, fmt: str, *args: Any) -> None:
            print(f"{self.client_address[0]} - {fmt % args}")

    return Handler


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG,
                        help="đường dẫn file JSON cấu hình")
    parser.add_argument("--host", help="địa chỉ bind, ví dụ 0.0.0.0 hoặc 127.0.0.1")
    parser.add_argument("--port", type=int, help="cổng HTTP/HTTPS")
    parser.add_argument("--cert", type=Path, help="chứng chỉ TLS PEM để bật HTTPS trực tiếp")
    parser.add_argument("--key", type=Path, help="private key TLS PEM")
    parser.add_argument("--no-prompt", action="store_true", help="dùng config mà không hỏi")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    config = load_config(args.config)
    if not args.no_prompt:
        config = prompt_configuration(config)
    if args.host:
        config["host"] = args.host
    if args.port:
        config["port"] = args.port
    if (args.cert is None) != (args.key is None):
        raise ValueError("Cần truyền cả --cert và --key để bật HTTPS.")
    if args.cert:
        config["tls_cert"] = str(args.cert)
        config["tls_key"] = str(args.key)
    if not config.get("api_key"):
        config["api_key"] = secrets.token_urlsafe(32)
    validate_config(config)
    save_config(args.config, config)

    service = MoveService(config)
    tls_enabled = bool(config.get("tls_cert") and config.get("tls_key"))
    server = ThreadingHTTPServer(
        (config["host"], int(config["port"])),
        make_handler(service, config["api_key"], https_enabled=tls_enabled),
    )
    if tls_enabled:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(config["tls_cert"], config["tls_key"])
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    else:
        scheme = "http"
        if config["host"] in {"0.0.0.0", "::"}:
            print(
                "CẢNH BÁO: HTTP không mã hóa. Chỉ dùng trong LAN; "
                "dùng reverse proxy TLS hoặc --cert/--key khi truy cập Internet.",
                file=sys.stderr,
            )
    print(f"ChessBot host: {scheme}://{config['host']}:{config['port']}")
    print("API key (nhập vào app): " + config["api_key"])
    print("POST /bestmove và GET /health yêu cầu Authorization: Bearer <API key>.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nĐang dừng server…")
    finally:
        server.server_close()
        service.close()


if __name__ == "__main__":
    main()
