package io.github.xiaofengzhou.qupath.mrxs;

/** Pixel-center coverage of a fractional, translated source subregion (no intensity masking). */
record MrxsSubtileGeometry(int x, int y, int width, int height,
                          double sourceCenterX, double sourceCenterY) {
    static MrxsSubtileGeometry create(double dx, double dy, double sx, double sy,
                                     double width, double height) {
        if (!Double.isFinite(dx + dy + sx + sy + width + height) || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid subtile geometry");
        int x = (int) Math.ceil(dx - 0.5), y = (int) Math.ceil(dy - 0.5);
        int endX = (int) Math.ceil(dx + width - 0.5), endY = (int) Math.ceil(dy + height - 0.5);
        return new MrxsSubtileGeometry(x, y, endX - x, endY - y,
                sx + x + 0.5 - dx, sy + y + 0.5 - dy);
    }
}
