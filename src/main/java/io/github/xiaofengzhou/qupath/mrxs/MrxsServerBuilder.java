package io.github.xiaofengzhou.qupath.mrxs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class MrxsServerBuilder implements ImageServerBuilder<BufferedImage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MrxsServerBuilder.class);

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) {
        if (!supports(uri)) {
            return null;
        }
        try {
            return new MrxsImageServer(uri, args);
        } catch (Exception e) {
            LOGGER.warn("Unable to open multiplex MRXS image {}: {}", uri, e.getMessage());
            LOGGER.debug("MRXS ImageServer initialization failure", e);
            return null;
        }
    }

    @Override
    public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) {
        float support = supports(uri) ? 4.0f : 0f;
        return UriImageSupport.createInstance(
                getClass(), support,
                DefaultImageServerBuilder.createInstance(getClass(), uri, args)
        );
    }

    private static boolean supports(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        try {
            Path path = Path.of(uri);
            String name = path.getFileName().toString();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".mrxs")) {
                return false;
            }
            String stem = name.substring(0, name.length() - 5);
            if (!Files.isRegularFile(path)
                    || !Files.isRegularFile(path.resolveSibling(stem).resolve("Slidedat.ini"))
                    || !Files.isRegularFile(path.resolveSibling(stem).resolve("Index.dat"))) {
                return false;
            }
            MrxsMetadata metadata = SlidedatParser.parse(path);
            return !metadata.channels().isEmpty()
                    && metadata.filterHierarchyIndex() >= 0
                    && MrxsCompatibilityReport.assess(metadata).isSupported();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "MRXS multiplex fluorescence builder";
    }

    @Override
    public String getDescription() {
        return "Reads native multichannel fluorescence data from 3DHISTECH MRXS slides";
    }

    @Override
    public Class<BufferedImage> getImageType() {
        return BufferedImage.class;
    }

    @Override
    public boolean matchClassName(String... classNames) {
        for (String name : classNames) {
            if (name.equals(getClass().getName())
                    || name.equals(getClass().getSimpleName())
                    || name.equalsIgnoreCase("mrxs")) {
                return true;
            }
        }
        return false;
    }
}
