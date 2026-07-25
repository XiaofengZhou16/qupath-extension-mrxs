package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.images.servers.TileRequest;

import java.awt.Point;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.InflaterInputStream;

final class MrxsImageServer extends AbstractTileableImageServer {

    private static final int PREFERRED_TILE_SIZE = 512;

    private final URI uri;
    private final MrxsMetadata metadata;
    private final MiraxIndex index;
    private final ImageServerMetadata originalMetadata;
    private final List<LevelGeometry> levels;
    private final int[] slidePositions;
    private final Map<StorageKey, PlacedImageIndex> placedImages =
            new ConcurrentHashMap<>();
    private final Map<Integer, List<MrxsMetadata.FluorescenceChannel>> channelsByFilter;
    private final DecodedImageCache decodedImageCache;
    private final ColorModel colorModel;

    MrxsImageServer(URI uri, String... args) throws IOException {
        this.uri = uri;
        Path path = Path.of(uri);
        metadata = SlidedatParser.parse(path);
        validateSupportedMetadata(metadata);
        index = MiraxIndex.open(metadata);
        levels = createLevelGeometry(metadata);
        slidePositions = readSlidePositions();
        channelsByFilter = metadata.channels().stream().collect(
                java.util.stream.Collectors.groupingBy(
                        MrxsMetadata.FluorescenceChannel::filterLevelIndex
                )
        );
        decodedImageCache = new DecodedImageCache(index, 128L * 1024 * 1024);

        List<ImageChannel> imageChannels = metadata.channels().stream()
                .map(channel -> ImageChannel.getInstance(channel.name(), channel.colorRgb()))
                .toList();
        colorModel = ColorModelFactory.createColorModel(PixelType.UINT8, imageChannels);
        var resolutionBuilder = new ImageResolutionLevel.Builder(
                levels.getFirst().width(), levels.getFirst().height()
        );
        for (var level : levels) {
            resolutionBuilder.addLevel(level.width(), level.height());
        }
        originalMetadata = new ImageServerMetadata.Builder(
                getClass(), uri.toString(),
                levels.getFirst().width(), levels.getFirst().height()
        )
                .name(path.getFileName().toString())
                .channels(imageChannels)
                .rgb(false)
                .pixelType(PixelType.UINT8)
                .pixelSizeMicrons(
                        metadata.zoomLevels().getFirst().pixelSizeXMicrons(),
                        metadata.zoomLevels().getFirst().pixelSizeYMicrons()
                )
                .magnification(metadata.objectiveMagnification())
                .preferredTileSize(PREFERRED_TILE_SIZE, PREFERRED_TILE_SIZE)
                .levels(resolutionBuilder.build())
                .build();
    }

    private static void validateSupportedMetadata(MrxsMetadata metadata)
            throws IOException {
        if (metadata.storedBitDepth() != 8) {
            throw new IOException("Unsupported MRXS stored bit depth: "
                    + metadata.storedBitDepth() + " (only 8-bit is currently supported)");
        }
        if (metadata.imageDivisionsPerSide() <= 0
                || metadata.imageCountX() % metadata.imageDivisionsPerSide() != 0
                || metadata.imageCountY() % metadata.imageDivisionsPerSide() != 0) {
            throw new IOException("Invalid MRXS camera image-division geometry");
        }
        for (var level : metadata.zoomLevels()) {
            if (!"JPEG".equalsIgnoreCase(level.imageFormat())) {
                throw new IOException("Unsupported MRXS image format at zoom "
                        + level.index() + ": " + level.imageFormat());
            }
            if (level.concatFactor() < 0 || level.concatFactor() > 20) {
                throw new IOException("Unsupported MRXS concat exponent at zoom "
                        + level.index() + ": " + level.concatFactor());
            }
        }
        int filterCount = metadata.hierarchy()
                .get(metadata.filterHierarchyIndex()).valueCount();
        for (var channel : metadata.channels()) {
            if (channel.filterLevelIndex() < 0
                    || channel.filterLevelIndex() >= filterCount) {
                throw new IOException("Invalid filter level for channel "
                        + channel.name() + ": " + channel.filterLevelIndex());
            }
            if (channel.storedComponent() < 0 || channel.storedComponent() > 2) {
                throw new IOException("Unsupported packed component for channel "
                        + channel.name() + ": " + channel.storedComponent());
            }
        }
    }

    @Override
    protected BufferedImage readTile(TileRequest request) throws IOException {
        int levelIndex = request.getLevel();
        LevelGeometry geometry = levels.get(levelIndex);
        int outputWidth = request.getTileWidth();
        int outputHeight = request.getTileHeight();
        int levelX = (int) Math.round(request.getImageX() / geometry.downsample());
        int levelY = (int) Math.round(request.getImageY() / geometry.downsample());

        byte[][] bands = new byte[metadata.channels().size()][outputWidth * outputHeight];
        for (var filterChannels : channelsByFilter.entrySet()) {
            StorageKey key = new StorageKey(levelIndex, filterChannels.getKey());
            PlacedImageIndex imageIndex;
            try {
                imageIndex = getPlacedImageIndex(key);
            } catch (ImageLoadingException e) {
                throw (IOException) e.getCause();
            }
            for (PlacedImage placed : imageIndex.query(
                    levelX, levelY, outputWidth, outputHeight
            )) {
                BufferedImage source = decodedImageCache.get(placed.entry());
                for (var channel : filterChannels.getValue()) {
                    copyComponent(
                            source, channel.storedComponent(), placed.x(), placed.y(),
                            levelX, levelY, outputWidth, outputHeight, bands[channel.index()]
                    );
                }
            }
        }

        DataBufferByte buffer = new DataBufferByte(bands, outputWidth * outputHeight);
        WritableRaster raster = Raster.createWritableRaster(
                new BandedSampleModel(
                        DataBuffer.TYPE_BYTE, outputWidth, outputHeight, bands.length
                ),
                buffer,
                new Point()
        );
        return new BufferedImage(colorModel, raster, false, null);
    }

    private PlacedImageIndex getPlacedImageIndex(StorageKey key) {
        return placedImages.computeIfAbsent(key, ignored -> {
            try {
                return loadPlacedImages(key.level(), key.filter());
            } catch (IOException e) {
                throw new ImageLoadingException(e);
            }
        });
    }

    private PlacedImageIndex loadPlacedImages(int level, int filter) throws IOException {
        List<PlacedImage> result = new ArrayList<>();
        LevelGeometry geometry = levels.get(level);
        for (var entry : index.readTiles(level, filter)) {
            int gridX = Math.toIntExact(entry.imageIndex() % metadata.imageCountX());
            int gridY = Math.toIntExact(entry.imageIndex() / metadata.imageCountX());
            double[] position = positionAtLevelZero(gridX, gridY);
            result.add(new PlacedImage(
                    (int) Math.round(position[0] / geometry.downsample()),
                    (int) Math.round(position[1] / geometry.downsample()),
                    metadata.zoomLevels().get(level).imageWidth(),
                    metadata.zoomLevels().get(level).imageHeight(),
                    entry
            ));
        }
        return new PlacedImageIndex(result, PREFERRED_TILE_SIZE);
    }

    private double[] positionAtLevelZero(int gridX, int gridY) {
        var base = metadata.zoomLevels().getFirst();
        int divisions = metadata.imageDivisionsPerSide();
        int cameraX = gridX / divisions;
        int cameraY = gridY / divisions;
        int subdivisionX = gridX % divisions;
        int subdivisionY = gridY % divisions;
        int positionsAcross = metadata.imageCountX() / divisions;
        int positionIndex = cameraY * positionsAcross + cameraX;
        if (slidePositions != null) {
            return new double[] {
                    slidePositions[positionIndex * 2] + subdivisionX * base.imageWidth(),
                    slidePositions[positionIndex * 2 + 1] + subdivisionY * base.imageHeight()
            };
        }
        return new double[] {
                cameraX * (base.imageWidth() * divisions - base.overlapX())
                        + subdivisionX * base.imageWidth(),
                cameraY * (base.imageHeight() * divisions - base.overlapY())
                        + subdivisionY * base.imageHeight()
        };
    }

    private int[] readSlidePositions() throws IOException {
        if (metadata.positionNonhierRecord() < 0) {
            return null;
        }
        byte[] bytes = index.readNonhierPayload(metadata.positionNonhierRecord());
        int positionCount = (metadata.imageCountX() / metadata.imageDivisionsPerSide())
                * (metadata.imageCountY() / metadata.imageDivisionsPerSide());
        int expectedLength = positionCount * 9;
        if (metadata.compressedPositionRecord()) {
            try (var input = new InflaterInputStream(new ByteArrayInputStream(bytes));
                 var output = new ByteArrayOutputStream(expectedLength)) {
                input.transferTo(output);
                bytes = output.toByteArray();
            }
        }
        if (bytes.length != expectedLength) {
            throw new IOException("Unexpected MRXS slide-position length: "
                    + bytes.length + " (expected " + expectedLength + ")");
        }
        int[] positions = new int[positionCount * 2];
        var buffer = java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int levelZeroConcat = 1 << metadata.zoomLevels().getFirst().concatFactor();
        for (int i = 0; i < positionCount; i++) {
            int flags = Byte.toUnsignedInt(buffer.get());
            if ((flags & 0xfe) != 0) {
                throw new IOException("Unexpected MRXS slide-position flag: " + flags);
            }
            positions[i * 2] = buffer.getInt() * levelZeroConcat;
            positions[i * 2 + 1] = buffer.getInt() * levelZeroConcat;
        }
        return positions;
    }

    private static void copyComponent(
            BufferedImage source, int component, int sourceX, int sourceY,
            int targetX, int targetY, int targetWidth, int targetHeight, byte[] target
    ) {
        Raster raster = source.getRaster();
        int sourceBand = Math.max(0, Math.min(component, raster.getNumBands() - 1));
        int x0 = Math.max(sourceX, targetX);
        int y0 = Math.max(sourceY, targetY);
        int x1 = Math.min(sourceX + source.getWidth(), targetX + targetWidth);
        int y1 = Math.min(sourceY + source.getHeight(), targetY + targetHeight);
        int copyWidth = x1 - x0;
        if (copyWidth <= 0 || y1 <= y0) {
            return;
        }
        int[] samples = new int[copyWidth];
        for (int y = y0; y < y1; y++) {
            int sourceRow = y - sourceY;
            int targetRow = (y - targetY) * targetWidth;
            raster.getSamples(
                    x0 - sourceX, sourceRow, copyWidth, 1, sourceBand, samples
            );
            int targetOffset = targetRow + x0 - targetX;
            for (int x = 0; x < copyWidth; x++) {
                target[targetOffset + x] = (byte) samples[x];
            }
        }
    }

    private static List<LevelGeometry> createLevelGeometry(MrxsMetadata metadata) {
        var base = metadata.zoomLevels().getFirst();
        int width = stitchedDimension(
                metadata.imageCountX(), metadata.imageDivisionsPerSide(),
                base.imageWidth(), base.overlapX()
        );
        int height = stitchedDimension(
                metadata.imageCountY(), metadata.imageDivisionsPerSide(),
                base.imageHeight(), base.overlapY()
        );
        List<LevelGeometry> result = new ArrayList<>();
        long cumulative = 1;
        long baseCumulative = -1;
        for (int i = 0; i < metadata.zoomLevels().size(); i++) {
            cumulative = Math.multiplyExact(cumulative,
                    1L << metadata.zoomLevels().get(i).concatFactor());
            if (i == 0) {
                baseCumulative = cumulative;
            }
            double downsample = (double) cumulative / baseCumulative;
            result.add(new LevelGeometry(
                    downsample,
                    Math.max(1, (int) Math.ceil(width / downsample)),
                    Math.max(1, (int) Math.ceil(height / downsample))
            ));
        }
        return List.copyOf(result);
    }

    private static int stitchedDimension(int imageCount, int divisions,
                                         int imageSize, double overlap) {
        double size = 0;
        for (int i = 0; i < imageCount; i++) {
            size += imageSize;
            if (i % divisions == divisions - 1 && i != imageCount - 1) {
                size -= overlap;
            }
        }
        return (int) Math.ceil(size);
    }

    @Override
    protected ColorModel getDefaultColorModel() {
        return colorModel;
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return originalMetadata;
    }

    @Override
    public Collection<URI> getURIs() {
        return List.of(uri);
    }

    @Override
    public String getServerType() {
        return "MRXS multiplex fluorescence";
    }

    @Override
    protected String createID() {
        return ServerTools.createDefaultID(getClass(), uri);
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return DefaultImageServerBuilder.createInstance(
                MrxsServerBuilder.class, getMetadata(), uri
        );
    }

    @Override
    public void close() throws IOException {
        decodedImageCache.clear();
        placedImages.clear();
        index.close();
    }

    IndexStats indexStats(int level, int filter, int x, int y, int width, int height)
            throws IOException {
        try {
            PlacedImageIndex imageIndex = getPlacedImageIndex(
                    new StorageKey(level, filter)
            );
            return new IndexStats(
                    imageIndex.imageCount(),
                    imageIndex.query(x, y, width, height).size()
            );
        } catch (ImageLoadingException e) {
            throw (IOException) e.getCause();
        }
    }

    DecodedImageCache.CacheStats cacheStats() {
        return decodedImageCache.stats();
    }

    int openDataFileCount() {
        return index.openDataFileCount();
    }

    private record LevelGeometry(double downsample, int width, int height) {
    }

    private record StorageKey(int level, int filter) {
    }

    record IndexStats(int totalImages, int candidateImages) {
    }

    private record PlacedImage(int x, int y, int width, int height,
                               MiraxIndex.TileEntry entry) {
        boolean intersects(int otherX, int otherY, int otherWidth, int otherHeight) {
            return x < otherX + otherWidth && x + width > otherX
                    && y < otherY + otherHeight && y + height > otherY;
        }
    }

    private static final class PlacedImageIndex {
        private final int bucketSize;
        private final int imageCount;
        private final Map<Long, List<PlacedImage>> buckets;

        PlacedImageIndex(List<PlacedImage> images, int bucketSize) {
            this.bucketSize = bucketSize;
            this.imageCount = images.size();
            Map<Long, List<PlacedImage>> mutable = new java.util.HashMap<>();
            for (PlacedImage image : images) {
                int minX = Math.floorDiv(image.x(), bucketSize);
                int minY = Math.floorDiv(image.y(), bucketSize);
                int maxX = Math.floorDiv(image.x() + image.width() - 1, bucketSize);
                int maxY = Math.floorDiv(image.y() + image.height() - 1, bucketSize);
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        mutable.computeIfAbsent(key(x, y), ignored -> new ArrayList<>())
                                .add(image);
                    }
                }
            }
            mutable.replaceAll((ignored, value) -> List.copyOf(value));
            buckets = Map.copyOf(mutable);
        }

        List<PlacedImage> query(int x, int y, int width, int height) {
            int minX = Math.floorDiv(x, bucketSize);
            int minY = Math.floorDiv(y, bucketSize);
            int maxX = Math.floorDiv(x + width - 1, bucketSize);
            int maxY = Math.floorDiv(y + height - 1, bucketSize);
            LinkedHashSet<PlacedImage> candidates = new LinkedHashSet<>();
            for (int bucketY = minY; bucketY <= maxY; bucketY++) {
                for (int bucketX = minX; bucketX <= maxX; bucketX++) {
                    candidates.addAll(
                            buckets.getOrDefault(key(bucketX, bucketY), List.of())
                    );
                }
            }
            return candidates.stream()
                    .filter(image -> image.intersects(x, y, width, height))
                    .toList();
        }

        int imageCount() {
            return imageCount;
        }

        private static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }
    }

    private static final class ImageLoadingException extends RuntimeException {
        ImageLoadingException(IOException cause) {
            super(cause);
        }
    }
}
