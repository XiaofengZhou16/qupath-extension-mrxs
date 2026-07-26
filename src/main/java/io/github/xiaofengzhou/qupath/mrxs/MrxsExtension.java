package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

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
