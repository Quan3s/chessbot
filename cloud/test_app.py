import importlib.util
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


MODULE_PATH = Path(__file__).with_name("app.py")
SPEC = importlib.util.spec_from_file_location("chessbot_host", MODULE_PATH)
host = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host)


class FakeRunner:
    name = "Fake engine"

    def best_move(self, fen, movetime_ms, elo):
        return "e2e4"

    def close(self):
        pass


class HostTests(unittest.TestCase):
    def setUp(self):
        self.config = dict(host.DEFAULTS)
        self.config.update({"engine": "stockfish", "api_key": "test-key"})
        self.service = host.MoveService(self.config, FakeRunner())
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0), host.make_handler(self.service, "test-key")
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def call(self, method, path, payload=None, key="test-key"):
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(self.url + path, data=data, method=method)
        request.add_header("Authorization", "Bearer " + key)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def test_health_requires_api_key(self):
        status, _ = self.call("GET", "/health", key="wrong")
        self.assertEqual(status, 401)
        status, _ = self.call("GET", "/health", key="wrong-é")
        self.assertEqual(status, 401)
        status, payload = self.call("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(payload["engine"], "stockfish")

    def test_best_move_validates_bot_and_position(self):
        body = {
            "engine": "stockfish",
            "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "movetime_ms": 5000,
            "elo": 2000,
        }
        status, payload = self.call("POST", "/bestmove", body)
        self.assertEqual(status, 200)
        self.assertEqual(payload["move"], "e2e4")
        body["engine"] = "maia"
        status, _ = self.call("POST", "/bestmove", body)
        self.assertEqual(status, 400)
        body["engine"] = "stockfish"
        body["fen"] = "not-a-fen"
        status, _ = self.call("POST", "/bestmove", body)
        self.assertEqual(status, 400)

    def test_rejects_illegal_engine_move(self):
        class BadRunner(FakeRunner):
            def best_move(self, fen, movetime_ms, elo):
                return "e2e5"

        service = host.MoveService(self.config, BadRunner())
        with self.assertRaises(RuntimeError):
            service.best_move({
                "engine": "stockfish",
                "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "movetime_ms": 1000,
                "elo": 2000,
            })

    def test_maia_elo_is_limited_to_supported_categories(self):
        config = dict(self.config)
        config["engine"] = "maia"
        service = host.MoveService(config, FakeRunner())
        request = {
            "engine": "maia",
            "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "movetime_ms": 1000,
            "elo": 2050,
        }
        with self.assertRaises(ValueError):
            service.best_move(request)


if __name__ == "__main__":
    unittest.main()
