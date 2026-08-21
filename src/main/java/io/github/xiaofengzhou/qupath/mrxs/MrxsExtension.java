package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Installs user-facing MRXS diagnostic commands in QuPath.
 */
public final class MrxsExtension implements QuPathExtension {

    @Override
    public void installExtension(QuPathGUI qupath) {
        qupath.installImageDataCommand(
                "Menu.Extensions>MRXS>Show compatibility report",
                imageData -> showReport(imageData.getServer())
        );
        qupath.installCommand(
                "Menu.Extensions>MRXS>Validation>Export active viewer ROI",
                () -> exportActiveViewer(qupath)
        );
        qupath.installCommand(
                "Menu.Extensions>MRXS>Export>Export ROI - all channel combinations",
                () -> exportAllChannelCombinations(qupath)
        );
        qupath.installCommand(
                "Menu.Extensions>MRXS>Display>Raw linear (0-255, gamma 1.0)",
                () -> applyDisplayPreset(qupath, false)
        );
        qupath.installCommand(
                "Menu.Extensions>MRXS>Display>CaseViewer-like percentile preset",
                () -> applyDisplayPreset(qupath, true)
        );
        qupath.installCommand(
                "Menu.Extensions>MRXS>Display>Reset display",
                () -> resetDisplay(qupath)
        );
        qupath.installCommand(
                "Menu.Extensions>MRXS>Display>Show current channel ranges",
                () -> showCurrentRanges(qupath)
        );
        Platform.runLater(MrxsExtension::warnIfGlobalGammaIsExtreme);
    }

    private static void exportAllChannelCombinations(QuPathGUI qupath) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || !(viewer.getServer() instanceof MrxsImageServer server)) {
            showError(
                    "MRXS channel combination export",
                    "The active viewer is not using QuPath MRXS Extension."
            );
            return;
        }

        var selectedObject = viewer.getSelectedObject();
        var roi = selectedObject != null && selectedObject.hasROI()
                && selectedObject.getROI().isArea()
                ? selectedObject.getROI() : null;
        MrxsCombinationExport.ExportRegion region;
        if (roi != null) {
            int x = Math.max(0, (int) Math.floor(roi.getBoundsX()));
            int y = Math.max(0, (int) Math.floor(roi.getBoundsY()));
            int x2 = Math.min(
                    server.getWidth(),
                    (int) Math.ceil(roi.getBoundsX() + roi.getBoundsWidth())
            );
            int y2 = Math.min(
                    server.getHeight(),
                    (int) Math.ceil(roi.getBoundsY() + roi.getBoundsHeight())
            );
            String name = selectedObject.getName() == null
                    ? roi.getRoiName() : selectedObject.getName();
            region = new MrxsCombinationExport.ExportRegion(
                    x, y, x2 - x, y2 - y, roi, name
            );
        } else {
            var bounds = viewer.getDisplayedRegionShape().getBounds2D();
            int x = Math.max(0, (int) Math.floor(bounds.getMinX()));
            int y = Math.max(0, (int) Math.floor(bounds.getMinY()));
            int x2 = Math.min(server.getWidth(), (int) Math.ceil(bounds.getMaxX()));
            int y2 = Math.min(server.getHeight(), (int) Math.ceil(bounds.getMaxY()));
            region = new MrxsCombinationExport.ExportRegion(
                    x, y, x2 - x, y2 - y, null, "visible-view"
            );
        }
        if (region.width() <= 0 || region.height() <= 0) {
            showError("MRXS channel combination export", "The export region is empty.");
            return;
        }

        var display = viewer.getImageDisplay();
        var available = List.copyOf(display.availableChannels());
        if (available.size() > MrxsCombinationExport.MAX_CHANNELS) {
            showError(
                    "MRXS channel combination export",
                    "This slide has " + available.size() + " channels. Selective export "
                            + "supports at most " + MrxsCombinationExport.MAX_CHANNELS + "."
            );
            return;
        }
        List<CheckBox> boxes = available.stream().map(channel -> {
            CheckBox box = new CheckBox(channel.getName());
            box.setSelected(true);
            return box;
        }).toList();
        ComboBox<String> resolution = new ComboBox<>();
        resolution.getItems().addAll(
                "Current viewer resolution", "Highest resolution"
        );
        resolution.getSelectionModel().selectFirst();
        VBox content = new VBox(8);
        content.getChildren().add(new Label(
                "Select channels. Every non-empty combination will be exported."
        ));
        content.getChildren().addAll(boxes);
        content.getChildren().add(new Label("Resolution"));
        content.getChildren().add(resolution);
        Alert options = new Alert(
                AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL
        );
        options.setTitle("MRXS channel combination export");
        options.setHeaderText(
                roi == null ? "No area ROI selected - exporting visible view"
                        : "Exporting selected ROI: " + region.name()
        );
        options.getDialogPane().setContent(content);
        if (options.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        List<MrxsCombinationExport.ChannelSelection> channels = new java.util.ArrayList<>();
        for (int i = 0; i < available.size(); i++) {
            if (boxes.get(i).isSelected()) {
                channels.add(new MrxsCombinationExport.ChannelSelection(
                        i, available.get(i).getName(),
                        available.get(i).getColor() == null
                                ? 0xffffff : available.get(i).getColor(),
                        available.get(i).getMinDisplay(),
                        available.get(i).getMaxDisplay()
                ));
            }
        }
        if (channels.isEmpty()) {
            showError("MRXS channel combination export", "Select at least one channel.");
            return;
        }
        int combinations = MrxsCombinationExport.combinationCount(channels.size());
        if (combinations > 63) {
            Alert warning = new Alert(
                    AlertType.CONFIRMATION,
                    "This will export " + combinations + " composite PNGs plus "
                            + channels.size() + " raw single-channel PNGs. Continue?",
                    ButtonType.OK, ButtonType.CANCEL
            );
            warning.setTitle("Large combination export");
            warning.setHeaderText(null);
            if (warning.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose parent folder for channel combinations");
        var selected = chooser.showDialog(qupath.getStage());
        if (selected == null) {
            return;
        }
        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        );
        Path output = selected.toPath().resolve(
                "mrxs-combinations-" + timestamp
        );
        double downsample = resolution.getSelectionModel().getSelectedIndex() == 1
                ? 1.0 : viewer.getDownsampleFactor();
        var gammaOperation = viewer.getGammaOp();
        CompletableFuture.supplyAsync(() -> {
            try {
                return MrxsCombinationExport.export(
                        server, output, region, downsample,
                        List.copyOf(channels), gammaOperation
                );
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                showError("MRXS channel combination export", cause.getMessage());
            } else {
                showInformation(
                        "MRXS channel combination export",
                        "Export complete.\n\nFolder: " + result.outputDirectory()
                                + "\nCombinations: " + result.combinationCount()
                                + "\nOutput: " + result.outputWidth() + " x "
                                + result.outputHeight()
                                + "\nNative downsample: " + result.downsample()
                                + "\n\nCombination PNGs use the current QuPath channel "
                                + "colors, ranges and gamma. Raw single-channel PNGs "
                                + "contain untransformed values."
                );
            }
        }));
    }

    private static void warnIfGlobalGammaIsExtreme() {
        double gamma = PathPrefs.viewerGammaProperty().get();
        if (gamma < 0.5 || gamma > 2.0) {
            Alert alert = new Alert(
                    AlertType.WARNING,
                    "QuPath's global viewer gamma is " + gamma + ". This may make all "
                            + "image formats look unusually dark, pale or soft.\n\n"
                            + "The MRXS extension has not changed this preference. "
                            + "Review it in QuPath Preferences if the appearance is unexpected.",
                    ButtonType.OK
            );
            alert.setTitle("Unusual QuPath viewer gamma");
            alert.setHeaderText(null);
            alert.showAndWait();
        }
    }

    private static void exportActiveViewer(QuPathGUI qupath) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || !(viewer.getServer() instanceof MrxsImageServer server)) {
            showError(
                    "MRXS validation export",
                    "The active viewer is not using QuPath MRXS Extension."
            );
            return;
        }
        var bounds = viewer.getDisplayedRegionShape().getBounds2D();
        int x = Math.max(0, (int) Math.floor(bounds.getMinX()));
        int y = Math.max(0, (int) Math.floor(bounds.getMinY()));
        int x2 = Math.min(server.getWidth(), (int) Math.ceil(bounds.getMaxX()));
        int y2 = Math.min(server.getHeight(), (int) Math.ceil(bounds.getMaxY()));
        if (x2 <= x || y2 <= y) {
            showError("MRXS validation export", "The visible image region is empty.");
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose parent folder for MRXS validation export");
        var selected = chooser.showDialog(qupath.getStage());
        if (selected == null) {
            return;
        }
        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        );
        Path output = selected.toPath().resolve("mrxs-validation-" + timestamp);
        double requestedDownsample = viewer.getDownsampleFactor();
        var imageDisplay = viewer.getImageDisplay();
        var selectedChannels = List.copyOf(imageDisplay.selectedChannels());
        var gammaOperation = viewer.getGammaOp();
        var displaySnapshot = new MrxsValidationExport.DisplaySnapshot(
                viewer.getGamma(),
                imageDisplay.availableChannels().stream()
                        .map(channel -> new MrxsValidationExport.DisplayChannel(
                                channel.getName(), channel.getMinDisplay(),
                                channel.getMaxDisplay(), selectedChannels.contains(channel)
                        ))
                        .toList()
        );
        CompletableFuture.supplyAsync(() -> {
            try {
                var result = MrxsValidationExport.export(
                        server, output, x, y, x2 - x, y2 - y,
                        requestedDownsample, displaySnapshot
                );
                MrxsValidationExport.exportDisplayComposite(
                        server, output, x, y, x2 - x, y2 - y,
                        requestedDownsample, selectedChannels, gammaOperation
                );
                return result;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                showError("MRXS validation export", cause.getMessage());
            } else {
                showInformation(
                        "MRXS validation export",
                        "Export complete.\n\nFolder: " + result.outputDirectory()
                                + "\nNative pyramid level: " + result.pyramidLevel()
                                + "\nNative downsample: " + result.downsample()
                                + "\nOutput: " + result.outputWidth() + " x "
                                + result.outputHeight()
                                + "\nChannels: " + result.channels().size()
                                + "\n\nRaw channel PNGs contain no display LUT, gamma or "
                                + "contrast transform. display-composite.png reproduces "
                                + "the selected channel LUT/ranges for visual comparison."
                );
            }
        }));
    }

    private static void applyDisplayPreset(QuPathGUI qupath, boolean caseViewerLike) {
        try {
            QuPathViewer viewer = qupath.getViewer();
            List<MrxsDisplayPresets.ChannelRange> ranges = caseViewerLike
                    ? MrxsDisplayPresets.applyCaseViewerLike(viewer)
                    : MrxsDisplayPresets.applyRawLinear(viewer);
            showInformation(
                    "MRXS display preset",
                    (caseViewerLike
                            ? "CaseViewer-like percentile preset applied."
                            : "Raw linear preset applied.")
                            + "\n\nViewer gamma: 1.0\n"
                            + formatRanges(ranges)
                            + "\nDisplay only: raw MRXS pixels and exported values are unchanged."
            );
        } catch (Exception e) {
            showError("MRXS display preset", e.getMessage());
        }
    }

    private static void showCurrentRanges(QuPathGUI qupath) {
        try {
            QuPathViewer viewer = qupath.getViewer();
            showInformation(
                    "MRXS channel display ranges",
                    "Viewer gamma: " + viewer.getGamma() + "\n\n"
                            + formatRanges(MrxsDisplayPresets.currentRanges(viewer))
            );
        } catch (Exception e) {
            showError("MRXS channel display ranges", e.getMessage());
        }
    }

    private static void resetDisplay(QuPathGUI qupath) {
        try {
            QuPathViewer viewer = qupath.getViewer();
            var ranges = MrxsDisplayPresets.reset(viewer);
            showInformation(
                    "MRXS display preset",
                    "The display state from before the first preset was restored.\n\n"
                            + "Viewer gamma: " + viewer.getGamma() + "\n"
                            + formatRanges(ranges)
            );
        } catch (Exception e) {
            showError("MRXS display preset", e.getMessage());
        }
    }

    private static String formatRanges(List<MrxsDisplayPresets.ChannelRange> ranges) {
        StringBuilder text = new StringBuilder();
        for (var range : ranges) {
            text.append(range.name()).append(": ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", range.minimum()))
                    .append(" - ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", range.maximum()))
                    .append('\n');
        }
        return text.toString();
    }

    private static void showReport(
            qupath.lib.images.servers.ImageServer<java.awt.image.BufferedImage> server
    ) {
        if (!(server instanceof MrxsImageServer mrxsServer)) {
            showError(
                    "MRXS compatibility report",
                    "The current image is not being read by QuPath MRXS Extension.\n\n"
                            + "Server type: " + server.getServerType()
            );
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return mrxsServer.diagnosticReport(true);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((report, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                showError("MRXS compatibility report", cause.getMessage());
            } else {
                showReportDialog(report);
            }
        }));
    }

    private static void showReportDialog(String report) {
        TextArea text = new TextArea(report);
        text.setEditable(false);
        text.setWrapText(false);
        text.setPrefColumnCount(72);
        text.setPrefRowCount(30);
        text.positionCaret(0);
        Alert alert = new Alert(AlertType.INFORMATION, "", ButtonType.OK);
        alert.setTitle("MRXS compatibility report");
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(text);
        alert.showAndWait();
    }

    private static void showError(String title, String message) {
        Alert alert = new Alert(
                AlertType.ERROR,
                message == null ? "Unknown MRXS diagnostic error" : message,
                ButtonType.OK
        );
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static void showInformation(String title, String message) {
        Alert alert = new Alert(AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    @Override
    public String getName() {
        return "QuPath MRXS Extension";
    }

    @Override
    public String getDescription() {
        return "Native multichannel fluorescence MRXS reading and diagnostics";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.7.0");
    }
}
