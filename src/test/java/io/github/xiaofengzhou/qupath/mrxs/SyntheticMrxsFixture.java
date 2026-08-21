package io.github.xiaofengzhou.qupath.mrxs;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class SyntheticMrxsFixture {

    private static final String SLIDE_ID = "0123456789ABCDEF0123456789ABCDEF";
    private static final int IMAGE_SIZE = 16;
    private static final int DATA_HEADER_SIZE = 296;

    private SyntheticMrxsFixture() {
    }

    static Path create(Path parent) throws IOException {
        return create(parent, 8, "JPEG");
    }

    static Path create(Path parent, int storedBitDepth, String imageFormat)
            throws IOException {
        Path anchor = parent.resolve("synthetic.mrxs");
        Path directory = parent.resolve("synthetic");
        Files.createDirectories(directory);
        Files.write(anchor, new byte[0]);

        byte[] packedRgb = jpeg(new Color(40, 100, 180));
        byte[] cy5 = jpeg(new Color(20, 30, 220));
        Files.write(
                directory.resolve("Data0000.dat"),
                dataFile(packedRgb, cy5)
        );
        Files.write(
                directory.resolve("Index.dat"),
                index(packedRgb.length, cy5.length)
        );
        Files.writeString(
                directory.resolve("Slidedat.ini"),
                slidedat(storedBitDepth, imageFormat),
                StandardCharsets.UTF_8
        );
        return anchor;
    }

    private static byte[] jpeg(Color color) throws IOException {
        BufferedImage image = new BufferedImage(
                IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "JPEG", output)) {
            throw new IOException("No JPEG writer is available for synthetic MRXS fixture");
        }
        return output.toByteArray();
    }

    private static byte[] dataFile(byte[] first, byte[] second) {
        ByteBuffer header = ByteBuffer.allocate(DATA_HEADER_SIZE);
        header.put("01.01".getBytes(StandardCharsets.US_ASCII));
        header.put(SLIDE_ID.getBytes(StandardCharsets.US_ASCII));
        header.put("000".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                DATA_HEADER_SIZE + first.length + second.length
        );
        output.writeBytes(header.array());
        output.writeBytes(first);
        output.writeBytes(second);
        return output.toByteArray();
    }

    private static byte[] index(int firstLength, int secondLength) {
        int hierarchyRoot = 45;
        int firstListHead = 61;
        int secondListHead = 69;
        int firstPage = 77;
        int secondPage = 101;
        ByteBuffer buffer = ByteBuffer.allocate(125).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("01.01".getBytes(StandardCharsets.US_ASCII));
        buffer.put(SLIDE_ID.getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(hierarchyRoot);
        buffer.putInt(0); // No non-hierarchical records.

        buffer.position(hierarchyRoot);
        buffer.putInt(firstListHead);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(secondListHead);

        writeListHead(buffer, firstListHead, firstPage);
        writeListHead(buffer, secondListHead, secondPage);
        writePage(buffer, firstPage, DATA_HEADER_SIZE, firstLength);
        writePage(buffer, secondPage, DATA_HEADER_SIZE + firstLength, secondLength);
        return buffer.array();
    }

    private static void writeListHead(ByteBuffer buffer, int offset, int page) {
        buffer.position(offset);
        buffer.putInt(0);
        buffer.putInt(page);
    }

    private static void writePage(ByteBuffer buffer, int offset, int dataOffset, int length) {
        buffer.position(offset);
        buffer.putInt(1);
        buffer.putInt(0);
        buffer.putInt(0); // image index
        buffer.putInt(dataOffset);
        buffer.putInt(length);
        buffer.putInt(0); // Data0000.dat
    }

    private static String slidedat(int storedBitDepth, String imageFormat) {
        return """
                [GENERAL]
                SLIDE_ID=%s
                SLIDE_VERSION=1.9
                CURRENT_SLIDE_VERSION=1.9
                SLIDE_TYPE=SLIDE_TYPE_FLUORESCENCE
                IMAGENUMBER_X=1
                IMAGENUMBER_Y=1
                CameraImageDivisionsPerSide=1
                VIMSLIDE_SLIDE_BITDEPTH=%d
                VIMSLIDE_CAMERA_REAL_BITDEPTH=%d
                OBJECTIVE_MAGNIFICATION=20

                [HIERARCHICAL]
                HIER_COUNT=2
                HIER_0_NAME=Slide zoom level
                HIER_0_COUNT=1
                HIER_0_DEFAULT=0
                HIER_0_VAL_0=Zoom0
                HIER_0_VAL_0_SECTION=ZOOM_0
                HIER_1_NAME=Slide filter level
                HIER_1_COUNT=4
                HIER_1_DEFAULT=0
                HIER_1_VAL_0=F0
                HIER_1_VAL_0_SECTION=FILTER_0
                HIER_1_VAL_1=F1
                HIER_1_VAL_1_SECTION=FILTER_1
                HIER_1_VAL_2=F2
                HIER_1_VAL_2_SECTION=FILTER_2
                HIER_1_VAL_3=F3
                HIER_1_VAL_3_SECTION=FILTER_3
                NONHIER_COUNT=0

                [ZOOM_0]
                MICROMETER_PER_PIXEL_X=0.5
                MICROMETER_PER_PIXEL_Y=0.5
                DIGITIZER_WIDTH=%d
                DIGITIZER_HEIGHT=%d
                IMAGE_CONCAT_FACTOR=0
                OVERLAP_X=0
                OVERLAP_Y=0
                IMAGE_FORMAT=%s

                [FILTER_0]
                FILTER_NAME=DAPI
                DATA_IN_THIS_FILTER_LEVEL=F0
                STORING_CHANNEL_NUMBER=0
                COLOR_R=0
                COLOR_G=0
                COLOR_B=255

                [FILTER_1]
                FILTER_NAME=Green
                DATA_IN_THIS_FILTER_LEVEL=F0
                STORING_CHANNEL_NUMBER=1
                COLOR_R=0
                COLOR_G=255
                COLOR_B=0

                [FILTER_2]
                FILTER_NAME=Orange
                DATA_IN_THIS_FILTER_LEVEL=F0
                STORING_CHANNEL_NUMBER=2
                COLOR_R=255
                COLOR_G=128
                COLOR_B=0

                [FILTER_3]
                FILTER_NAME=CY5
                DATA_IN_THIS_FILTER_LEVEL=F3
                STORING_CHANNEL_NUMBER=0
                COLOR_R=255
                COLOR_G=255
                COLOR_B=0

                [DATAFILE]
                FILE_COUNT=1
                FILE_0=Data0000.dat
                """.formatted(
                SLIDE_ID,
                storedBitDepth,
                storedBitDepth,
                IMAGE_SIZE,
                IMAGE_SIZE,
                imageFormat
        );
    }
}
