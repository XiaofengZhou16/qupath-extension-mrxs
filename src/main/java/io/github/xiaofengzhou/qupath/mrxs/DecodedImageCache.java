package io.github.xiaofengzhou.qupath.mrxs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

final class DecodedImageCache {

    private final long maximumBytes;
    private final MiraxIndex index;
    private final ConcurrentHashMap<MiraxIndex.TileEntry, CompletableFuture<BufferedImage>>
            images = new ConcurrentHashMap<>();
    private final LinkedHashMap<MiraxIndex.TileEntry, Long> accessOrder =
            new LinkedHashMap<>(32, 0.75f, true);
    private long cachedBytes;

    DecodedImageCache(MiraxIndex index, long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("Cache size must be positive");
        }
        this.index = index;
        this.maximumBytes = maximumBytes;
    }

    BufferedImage get(MiraxIndex.TileEntry entry) throws IOException {
        CompletableFuture<BufferedImage> candidate = new CompletableFuture<>();
        CompletableFuture<BufferedImage> future = images.putIfAbsent(entry, candidate);
        if (future == null) {
            future = candidate;
            try {
                BufferedImage image = ImageIO.read(
                        new ByteArrayInputStream(index.readPayload(entry))
                );
                if (image == null) {
                    throw new IOException("Unsupported MRXS image payload at " + entry);
                }
                candidate.complete(image);
            } catch (Throwable t) {
                candidate.completeExceptionally(t);
            }
        }
        try {
            BufferedImage image = future.join();
            recordAccess(entry, image);
            return image;
        } catch (CompletionException e) {
            images.remove(entry, future);
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Unable to decode MRXS tile " + entry, e.getCause());
        }
    }

    private synchronized void recordAccess(MiraxIndex.TileEntry entry,
                                           BufferedImage image) {
        if (accessOrder.containsKey(entry)) {
            accessOrder.get(entry);
            return;
        }
        long bytes = Math.max(
                entry.length(),
                (long) image.getWidth() * image.getHeight()
                        * image.getRaster().getNumBands()
        );
        accessOrder.put(entry, bytes);
        cachedBytes += bytes;
        var iterator = accessOrder.entrySet().iterator();
        while (cachedBytes > maximumBytes && iterator.hasNext()) {
            Map.Entry<MiraxIndex.TileEntry, Long> eldest = iterator.next();
            iterator.remove();
            cachedBytes -= eldest.getValue();
            images.remove(eldest.getKey());
        }
    }

    synchronized CacheStats stats() {
        return new CacheStats(accessOrder.size(), cachedBytes, maximumBytes);
    }

    void clear() {
        images.clear();
        synchronized (this) {
            accessOrder.clear();
            cachedBytes = 0;
        }
    }

    record CacheStats(int imageCount, long cachedBytes, long maximumBytes) {
    }
}
