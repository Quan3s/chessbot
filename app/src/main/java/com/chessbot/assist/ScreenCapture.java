package com.chessbot.assist;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;

import java.nio.ByteBuffer;

/** MediaProjection -> ImageReader at a reduced resolution. */
public final class ScreenCapture {

    public static final class Frame {
        public int width;
        public int height;
        public int[] pixels; // ARGB
    }

    private final MediaProjection projection;
    private final int screenWidth;
    private final int screenHeight;
    private final int screenDpi;
    private final float scale;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private int[] buffer;
    private boolean released;

    public ScreenCapture(MediaProjection projection, int screenWidth, int screenHeight,
                         int screenDpi, float scale) {
        this.projection = projection;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.screenDpi = screenDpi;
        this.scale = scale;
    }

    public void start() {
        released = false;
        int w = makeEven(Math.max(64, Math.round(screenWidth * scale)));
        int h = makeEven(Math.max(64, Math.round(screenHeight * scale)));
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "chessbot-capture",
                w, h,
                Math.max(1, Math.round(screenDpi * scale)),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, null);
        buffer = new int[w * h];
    }

    public Frame grab() {
        if (released || imageReader == null) {
            return null;
        }
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            if (buffer == null || buffer.length < w * h) {
                buffer = new int[w * h];
            }
            if (pixelStride == 4) {
                for (int y = 0; y < h; y++) {
                    int row = y * rowStride;
                    int out = y * w;
                    for (int x = 0; x < w; x++) {
                        int v = buf.getInt(row + x * 4); // RGBA, big endian
                        buffer[out + x] = 0xff000000 | ((v >> 8) & 0x00ffffff);
                    }
                }
            } else {
                for (int y = 0; y < h; y++) {
                    int row = y * rowStride;
                    int out = y * w;
                    for (int x = 0; x < w; x++) {
                        int off = row + x * pixelStride;
                        int r = buf.get(off) & 255;
                        int g = buf.get(off + 1) & 255;
                        int b = buf.get(off + 2) & 255;
                        buffer[out + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
            Frame f = new Frame();
            f.width = w;
            f.height = h;
            f.pixels = buffer;
            return f;
        } catch (Throwable t) {
            EventLog.add("Lỗi chụp màn hình: " + t.getMessage());
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    public void release() {
        released = true;
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Throwable ignored) {
            }
            imageReader = null;
        }
    }

    private static int makeEven(int v) {
        return (v / 2) * 2;
    }
}
