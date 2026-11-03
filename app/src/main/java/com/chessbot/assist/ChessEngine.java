package com.chessbot.assist;

import java.io.IOException;

/** Search engine used by the game controller. */
public interface ChessEngine {
    void start() throws IOException;

    String getName();

    void configure(int skill, int threads, int hashMb) throws IOException;

    String bestMove(String fen, int movetimeMs) throws IOException;

    void quit();
}
