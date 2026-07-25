package io.github.xiaofengzhou.qupath.mrxs;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class MiraxIndex implements AutoCloseable {

    private static final int HEADER_SIZE = 45;
    private static final int MAX_PAGE_ENTRIES = 10_000_000;

    private final MrxsMetadata metadata;
    private final Path indexPath;
    private final FileChannel channel;
    private final long hierarchyRoot;
    private final ConcurrentMap<Long, FileChannel> dataChannels =
            new ConcurrentHashMap<>();

    private MiraxIndex(MrxsMetadata metadata, Path indexPath, FileChannel channel,
                       long hierarchyRoot) {
        this.metadata = metadata;
        this.indexPath = indexPath;
        this.channel = channel;
        this.hierarchyRoot = hierarchyRoot;
    }

    static MiraxIndex open(MrxsMetadata metadata) throws IOException {
        Path indexPath = metadata.slideDirectory().resolve("Index.dat");
        if (!Files.isRegularFile(indexPath)) {
            throw new IOException("Missing MRXS index: " + indexPath);
        }
        FileChannel channel = FileChannel.open(indexPath, StandardOpenOption.READ);
        try {
            ByteBuffer header = read(channel, 0, HEADER_SIZE);
            byte[] versionBytes = new byte[5];
            header.get(versionBytes);
            String version = new String(versionBytes, StandardCharsets.US_ASCII);
            if (!"01.01".equals(version) && !"01.02".equals(version)) {
                throw new IOException("Unsupported MRXS index version: " + version);
            }
            byte[] idBytes = new byte[32];
            header.get(idBytes);
            String slideId = new String(idBytes, StandardCharsets.US_ASCII);
            if (!slideId.equals(metadata.slideId())) {
                throw new IOException("Slide ID mismatch between Slidedat.ini and Index.dat");
            }
            long hierarchyRoot = Integer.toUnsignedLong(header.getInt());
            validatePointer(channel, hierarchyRoot, 4, "hierarchy root");
            return new MiraxIndex(metadata, indexPath, channel, hierarchyRoot);
        } catch (Throwable t) {
            channel.close();
            throw t;
        }
    }

    List<TileEntry> readTiles(int zoomLevel, int filterLevel) throws IOException {
        int[] coordinates = new int[metadata.hierarchy().size()];
        for (int i = 0; i < coordinates.length; i++) {
            coordinates[i] = metadata.hierarchy().get(i).defaultValue();
        }
        coordinates[metadata.zoomHierarchyIndex()] = zoomLevel;
        coordinates[metadata.filterHierarchyIndex()] = filterLevel;
        return readTiles(coordinates);
    }

    List<TileEntry> readTiles(int[] coordinates) throws IOException {
        if (coordinates.length != metadata.hierarchy().size()) {
            throw new IllegalArgumentException("Expected " + metadata.hierarchy().size()
                    + " hierarchy coordinates, got " + coordinates.length);
        }
        long flatIndex = 0;
        long stride = 1;
        for (int i = 0; i < coordinates.length; i++) {
            int count = metadata.hierarchy().get(i).valueCount();
            int coordinate = coordinates[i];
            if (coordinate < 0 || coordinate >= count) {
                throw new IllegalArgumentException("Hierarchy coordinate " + i
                        + " out of range: " + coordinate);
            }
            flatIndex += coordinate * stride;
            stride = Math.multiplyExact(stride, count);
        }

        long listRecordOffset = hierarchyRoot + Math.multiplyExact(flatIndex, 4);
        validatePointer(channel, listRecordOffset, 4, "hierarchy record");
        long listHead = readUnsignedInt(channel, listRecordOffset);
        if (listHead == 0) {
            return List.of();
        }
        validatePointer(channel, listHead, 8, "hierarchy list head");
        ByteBuffer head = read(channel, listHead, 8);
        head.getInt(); // reserved
        long page = Integer.toUnsignedLong(head.getInt());

        List<TileEntry> entries = new ArrayList<>();
        int pageCount = 0;
        var visitedPages = new HashSet<Long>();
        while (page != 0) {
            if (!visitedPages.add(page)) {
                throw new IOException("Cycle in MRXS index page chain at " + page);
            }
            if (++pageCount > 100_000) {
                throw new IOException("Probable cycle in MRXS index page chain");
            }
            validatePointer(channel, page, 8, "index page");
            ByteBuffer pageHeader = read(channel, page, 8);
            long entryCount = Integer.toUnsignedLong(pageHeader.getInt());
            long nextPage = Integer.toUnsignedLong(pageHeader.getInt());
            if (entryCount > MAX_PAGE_ENTRIES) {
                throw new IOException("Unreasonable MRXS page size: " + entryCount);
            }
            long payloadBytes = Math.multiplyExact(entryCount, 16);
            validatePointer(channel, page + 8, payloadBytes, "index page payload");
            ByteBuffer payload = read(channel, page + 8, Math.toIntExact(payloadBytes));
            for (int i = 0; i < entryCount; i++) {
                entries.add(new TileEntry(
                        Integer.toUnsignedLong(payload.getInt()),
                        Integer.toUnsignedLong(payload.getInt()),
                        Integer.toUnsignedLong(payload.getInt()),
                        Integer.toUnsignedLong(payload.getInt())
                ));
            }
            page = nextPage;
        }
        return List.copyOf(entries);
    }

    Path dataFile(TileEntry entry) throws IOException {
        if (entry.fileNumber() >= metadata.dataFiles().size()) {
            throw new IOException("MRXS data file index out of range: " + entry.fileNumber());
        }
        return metadata.slideDirectory().resolve(
                metadata.dataFiles().get(Math.toIntExact(entry.fileNumber()))
        );
    }

    byte[] readPayload(TileEntry entry) throws IOException {
        Path path = dataFile(entry);
        FileChannel data;
        try {
            data = dataChannels.computeIfAbsent(entry.fileNumber(), ignored -> {
                try {
                    return FileChannel.open(path, StandardOpenOption.READ);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw new IOException("Unable to open MRXS data file: " + path, e.getCause());
        }
        try {
            validatePointer(data, entry.offset(), entry.length(), "tile payload in " + path);
            ByteBuffer buffer = read(data, entry.offset(), Math.toIntExact(entry.length()));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (java.nio.channels.ClosedChannelException e) {
            dataChannels.remove(entry.fileNumber(), data);
            throw new IOException("MRXS data file was closed while reading: " + path, e);
        }
    }

    byte[] readNonhierPayload(int recordNumber) throws IOException {
        if (recordNumber < 0) {
            throw new IllegalArgumentException("Negative non-hierarchical record number");
        }
        long nonhierRoot = readUnsignedInt(channel, 41);
        validatePointer(channel, nonhierRoot + 4L * recordNumber, 4,
                "non-hierarchical record pointer");
        long record = readUnsignedInt(channel, nonhierRoot + 4L * recordNumber);
        validatePointer(channel, record, 8, "non-hierarchical record");
        ByteBuffer recordHead = read(channel, record, 8);
        if (recordHead.getInt() != 0) {
            throw new IOException("Invalid non-hierarchical record header");
        }
        long page = Integer.toUnsignedLong(recordHead.getInt());
        validatePointer(channel, page, 28, "non-hierarchical data page");
        ByteBuffer dataPage = read(channel, page, 28);
        int count = dataPage.getInt();
        dataPage.getInt(); // next page
        int reserved1 = dataPage.getInt();
        int reserved2 = dataPage.getInt();
        if (count < 1 || reserved1 != 0 || reserved2 != 0) {
            throw new IOException("Invalid non-hierarchical data page");
        }
        long offset = Integer.toUnsignedLong(dataPage.getInt());
        long length = Integer.toUnsignedLong(dataPage.getInt());
        long fileNumber = Integer.toUnsignedLong(dataPage.getInt());
        return readPayload(new TileEntry(0, offset, length, fileNumber));
    }

    Path indexPath() {
        return indexPath;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (FileChannel dataChannel : dataChannels.values()) {
            try {
                dataChannel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        dataChannels.clear();
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    int openDataFileCount() {
        return dataChannels.size();
    }

    private static long readUnsignedInt(FileChannel channel, long offset) throws IOException {
        return Integer.toUnsignedLong(read(channel, offset, 4).getInt());
    }

    private static ByteBuffer read(FileChannel channel, long offset, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        int zeroProgressReads = 0;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset + buffer.position());
            if (count < 0) {
                throw new EOFException("Unexpected end of file at offset " + offset);
            }
            if (count == 0 && ++zeroProgressReads > 100) {
                throw new IOException("No progress while reading file at offset "
                        + (offset + buffer.position()));
            } else if (count > 0) {
                zeroProgressReads = 0;
            }
        }
        return buffer.flip();
    }

    private static void validatePointer(FileChannel channel, long offset, long length,
                                        String description) throws IOException {
        long size = channel.size();
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new IOException("Invalid " + description + ": offset=" + offset
                    + ", length=" + length + ", fileSize=" + size);
        }
    }

    record TileEntry(long imageIndex, long offset, long length, long fileNumber) {
    }
}
