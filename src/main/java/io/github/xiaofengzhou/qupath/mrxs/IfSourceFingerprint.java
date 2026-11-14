package io.github.xiaofengzhou.qupath.mrxs;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded change detector, deliberately not a full-slide content hash. Worker thread only. */
final class IfSourceFingerprint {
    private static final int BLOCK = 65536, MAX_FILES = 4096;
    record Stamp(String uri, long size, String modified, String sampledSha256) {}
    record Snapshot(String algorithm, List<Stamp> files) {}

    static Snapshot capture(List<String> uris, BooleanSupplier cancelled) throws IOException {
        var paths = new TreeSet<Path>();
        for (String value : uris) {
            check(cancelled);
            URI uri = URI.create(value);
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            Path anchor = Path.of(uri).toAbsolutePath().normalize();
            paths.add(anchor);
            if (anchor.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mrxs")) {
                String name = anchor.getFileName().toString();
                Path directory = anchor.resolveSibling(name.substring(0, name.length()-5));
                try (var entries = Files.newDirectoryStream(directory)) {
                    for (Path entry : entries) {
                        check(cancelled);
                        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                            throw new IOException("Unsupported MRXS sidecar entry: " + entry.getFileName());
                        paths.add(entry);
                        if (paths.size() > MAX_FILES) throw new IOException("Source fingerprint file limit exceeded");
                    }
                }
            }
        }
        if (paths.isEmpty()) return null;
        if (paths.size() > MAX_FILES) throw new IOException("Source fingerprint file limit exceeded");
        var stamps = new ArrayList<Stamp>();
        for (Path path : paths) {
            check(cancelled);
            var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) throw new IOException("Source is not a regular file: " + path);
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
            var offsets = new TreeSet<Long>();
            offsets.add(0L);
            offsets.add(Math.max(0L, (before.size()-BLOCK)/2));
            offsets.add(Math.max(0L, before.size()-BLOCK));
            ByteBuffer buffer = ByteBuffer.allocate(BLOCK);
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                for (long offset : offsets) {
                    check(cancelled);
                    digest.update(ByteBuffer.allocate(8).putLong(offset).array());
                    buffer.clear();
                    buffer.limit((int)Math.min(BLOCK, before.size()-offset));
                    channel.position(offset);
                    while (buffer.hasRemaining()) {
                        check(cancelled);
                        if (channel.read(buffer) < 0) throw new IOException("Source truncated during fingerprint");
                    }
                    digest.update(buffer.array(), 0, buffer.position());
                }
            }
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(),after.fileKey()))
                throw new IOException("Source changed during fingerprint");
            stamps.add(new Stamp(path.toUri().toString(), before.size(), before.lastModifiedTime().toString(),
                    HexFormat.of().formatHex(digest.digest())));
        }
        return new Snapshot("file-size-mtime-sha256-head-middle-tail-64KiB-v1", List.copyOf(stamps));
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
    }
}
