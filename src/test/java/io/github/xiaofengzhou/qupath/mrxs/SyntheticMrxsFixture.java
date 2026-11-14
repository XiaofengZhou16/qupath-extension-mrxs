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

        byte[] packedRgb = jpeg(IMAGE_SIZE, new Color(40, 100, 180));
        byte[] packedRgbLevel1 = jpeg(IMAGE_SIZE / 2, new Color(42, 102, 178));
        byte[] cy5 = jpeg(IMAGE_SIZE, new Color(20, 30, 220));
        byte[] cy5Level1 = jpeg(IMAGE_SIZE / 2, new Color(22, 32, 218));
        Files.write(
                directory.resolve("Data0000.dat"),
                dataFile(packedRgb, packedRgbLevel1, cy5, cy5Level1)
        );
        Files.write(
                directory.resolve("Index.dat"),
                index(
                        packedRgb.length, packedRgbLevel1.length,
                        cy5.length, cy5Level1.length
                )
        );
        Files.writeString(
                directory.resolve("Slidedat.ini"),
                slidedat(storedBitDepth, imageFormat),
                StandardCharsets.UTF_8
        );
        return anchor;
    }

    private static byte[] jpeg(int size, Color color) throws IOException {
        BufferedImage image = new BufferedImage(
                size, size, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, size, size);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "JPEG", output)) {
            throw new IOException("No JPEG writer is available for synthetic MRXS fixture");
        }
        return output.toByteArray();
    }

    /** Four camera positions, top-right unscanned, 2-pixel base overlap.
     * Level 1 packs all four into one JPEG, including gray in the inactive quadrant.
     */
    static Path createConcatenated(Path parent) throws IOException {
        return createConcatenated(parent, 2.0);
    }

    static Path createConcatenated(Path parent, double overlap) throws IOException {
        Path directory=parent.resolve("concatenated");Files.createDirectories(directory);
        Path anchor=parent.resolve("concatenated.mrxs");Files.write(anchor,new byte[0]);
        BufferedImage packed=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB);
        int[] gray={20,128,60,128};
        for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
            int v=gray[(y/8)*2+x/8];packed.setRGB(x,y,(v<<16)|(v<<8)|v);
        }
        var bytes=new ByteArrayOutputStream();ImageIO.write(packed,"JPEG",bytes);
        byte[] combined=bytes.toByteArray();
        byte[][] payloads={jpeg(16,new Color(20,20,20)),jpeg(16,new Color(60,60,60)),
                jpeg(16,new Color(100,100,100)),combined};
        Files.write(directory.resolve("Data0000.dat"),dataFile(payloads));
        int[] offsets=new int[4];int offset=DATA_HEADER_SIZE;
        for(int i=0;i<4;i++){offsets[i]=offset;offset+=payloads[i].length;}
        ByteBuffer idx=ByteBuffer.allocate(77+2*64+2*32).order(ByteOrder.LITTLE_ENDIAN);
        idx.put("01.01".getBytes(StandardCharsets.US_ASCII));
        idx.put(SLIDE_ID.getBytes(StandardCharsets.US_ASCII));idx.putInt(45);idx.putInt(0);
        int cursor=77;
        for(int flat:new int[]{0,1,6,7}) {
            boolean base=flat%2==0;int count=base?3:1;
            idx.putInt(45+flat*4,cursor);writeListHead(idx,cursor,cursor+8);
            idx.position(cursor+8);idx.putInt(count);idx.putInt(0);
            for(int i=0;i<count;i++) {
                int payload=base?i:3;
                idx.putInt(base?new int[]{0,2,3}[i]:0);
                idx.putInt(offsets[payload]);idx.putInt(payloads[payload].length);idx.putInt(0);
            }
            cursor+=16+count*16;
        }
        Files.write(directory.resolve("Index.dat"),idx.array());
        String ini=slidedat(8,"JPEG").replace("IMAGENUMBER_X=1","IMAGENUMBER_X=2")
                .replace("IMAGENUMBER_Y=1","IMAGENUMBER_Y=2")
                .replace("DIGITIZER_WIDTH=8","DIGITIZER_WIDTH=16")
                .replace("DIGITIZER_HEIGHT=8","DIGITIZER_HEIGHT=16")
                .replace("OVERLAP_X=0","OVERLAP_X="+overlap)
                .replace("OVERLAP_Y=0","OVERLAP_Y="+overlap);
        Files.writeString(directory.resolve("Slidedat.ini"),ini);
        return anchor;
    }

    private static byte[] dataFile(byte[]... payloads) {
        ByteBuffer header = ByteBuffer.allocate(DATA_HEADER_SIZE);
        header.put("01.01".getBytes(StandardCharsets.US_ASCII));
        header.put(SLIDE_ID.getBytes(StandardCharsets.US_ASCII));
        header.put("000".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                DATA_HEADER_SIZE + java.util.Arrays.stream(payloads)
                        .mapToInt(payload -> payload.length).sum()
        );
        output.writeBytes(header.array());
        for (byte[] payload : payloads) {
            output.writeBytes(payload);
        }
        return output.toByteArray();
    }

    static Path createConcatenatedWithPositions(Path parent, boolean compressed) throws IOException {
        Path anchor=createConcatenated(parent);
        Path directory=parent.resolve("concatenated");
        ByteBuffer positions=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        for(int[] xy:new int[][]{{0,0},{0,0},{2,12},{14,14}})
            positions.put((byte)0).putInt(xy[0]).putInt(xy[1]);
        byte[] payload=positions.array();
        if(compressed) {
            var output=new ByteArrayOutputStream();
            try(var deflater=new java.util.zip.DeflaterOutputStream(output)){deflater.write(payload);}
            payload=output.toByteArray();
        }
        byte[] data=Files.readAllBytes(directory.resolve("Data0000.dat"));
        var expandedData=new ByteArrayOutputStream();expandedData.writeBytes(data);expandedData.writeBytes(payload);
        Files.write(directory.resolve("Data0000.dat"),expandedData.toByteArray());
        byte[] original=Files.readAllBytes(directory.resolve("Index.dat"));
        ByteBuffer idx=ByteBuffer.allocate(original.length+40).order(ByteOrder.LITTLE_ENDIAN);
        idx.put(original);idx.putInt(41,original.length);
        idx.position(original.length);idx.putInt(original.length+4);
        idx.putInt(0);idx.putInt(original.length+12);
        idx.putInt(1);idx.putInt(0);idx.putInt(0);idx.putInt(0);
        idx.putInt(data.length);idx.putInt(payload.length);idx.putInt(0);
        Files.write(directory.resolve("Index.dat"),idx.array());
        Path ini=directory.resolve("Slidedat.ini");
        Files.writeString(ini,Files.readString(ini).replace("NONHIER_COUNT=0",
                "NONHIER_COUNT=1\nNONHIER_0_COUNT=1\nNONHIER_0_NAME="+
                        (compressed?"StitchingIntensityLayer":"VIMSLIDE_POSITION_BUFFER")));
        return anchor;
    }

    static Path createDividedCameras(Path parent) throws IOException {
        Path anchor=createConcatenated(parent);
        Path directory=parent.resolve("concatenated");
        Path ini=directory.resolve("Slidedat.ini");
        Files.writeString(ini,Files.readString(ini)
                .replace("IMAGENUMBER_X=2","IMAGENUMBER_X=4")
                .replace("IMAGENUMBER_Y=2","IMAGENUMBER_Y=4")
                .replace("CameraImageDivisionsPerSide=1","CameraImageDivisionsPerSide=2")
                .replace("IMAGE_CONCAT_FACTOR=1","IMAGE_CONCAT_FACTOR=2")
                .replace("MICROMETER_PER_PIXEL_X=1.0","MICROMETER_PER_PIXEL_X=2.0")
                .replace("MICROMETER_PER_PIXEL_Y=1.0","MICROMETER_PER_PIXEL_Y=2.0"));
        Path indexPath=directory.resolve("Index.dat");
        ByteBuffer index=ByteBuffer.wrap(Files.readAllBytes(indexPath)).order(ByteOrder.LITTLE_ENDIAN);
        for(int flat:new int[]{0,6}) {
            int head=index.getInt(45+flat*4),page=index.getInt(head+4);
            // One native subdivision marks each of cameras (0,0), (0,1), (1,1) active.
            int[] imageIds={0,8,10};
            for(int i=0;i<3;i++)index.putInt(page+8+i*16,imageIds[i]);
        }
        Files.write(indexPath,index.array());
        return anchor;
    }

    private static byte[] index(
            int packedLevel0Length,
            int packedLevel1Length,
            int cy5Level0Length,
            int cy5Level1Length
    ) {
        int hierarchyRoot = 45;
        int hierarchyRecords = 8;
        int entryStart = hierarchyRoot + hierarchyRecords * Integer.BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(entryStart + 4 * 32)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("01.01".getBytes(StandardCharsets.US_ASCII));
        buffer.put(SLIDE_ID.getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(hierarchyRoot);
        buffer.putInt(0); // No non-hierarchical records.

        int[] flatIndices = {0, 1, 6, 7};
        int[] lengths = {
                packedLevel0Length, packedLevel1Length,
                cy5Level0Length, cy5Level1Length
        };
        int dataOffset = DATA_HEADER_SIZE;
        for (int i = 0; i < lengths.length; i++) {
            int listHead = entryStart + i * 32;
            int page = listHead + 8;
            buffer.putInt(hierarchyRoot + flatIndices[i] * Integer.BYTES, listHead);
            writeListHead(buffer, listHead, page);
            writePage(buffer, page, dataOffset, lengths[i]);
            dataOffset += lengths[i];
        }
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
                HIER_0_COUNT=2
                HIER_0_DEFAULT=0
                HIER_0_VAL_0=Zoom0
                HIER_0_VAL_0_SECTION=ZOOM_0
                HIER_0_VAL_1=Zoom1
                HIER_0_VAL_1_SECTION=ZOOM_1
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

                [ZOOM_1]
                MICROMETER_PER_PIXEL_X=1.0
                MICROMETER_PER_PIXEL_Y=1.0
                DIGITIZER_WIDTH=%d
                DIGITIZER_HEIGHT=%d
                IMAGE_CONCAT_FACTOR=1
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
                imageFormat,
                IMAGE_SIZE / 2,
                IMAGE_SIZE / 2,
                imageFormat
        );
    }
}
