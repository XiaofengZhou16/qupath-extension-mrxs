package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real history window, injected folder choice; no screen capture. */
public class IfHistoryGuiSmoke {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("history-folder isolated-prefs expected-run-count");
        Path root=Path.of(args[0]);int expected=Integer.parseInt(args[2]);
        if(expected<2)throw new IllegalArgumentException("Need two runs for multi-selection test");
        var before=IfAnalysisHistory.scan(root,()->false);
        var hashes=new java.util.HashMap<Path,byte[]>();
        for(var entry:before.entries())hashes.put(entry.directory(),Files.readAllBytes(entry.directory().resolve("manifest.json")));
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var failure=new AtomicReference<Throwable>();var done=new CountDownLatch(1);
        Platform.startup(()->{
            try {
                Files.createDirectory(Path.of(args[1]));PathPrefs.userPathProperty().set(args[1]);
                var qupath=QuPathGUI.createHiddenInstance();
                var stage=IfHistoryPanel.show(qupath,owner->root);
                var node=stage.getScene().getRoot();
                var choose=(Button)node.lookup("#if-history-choose");
                var table=(TableView<?>)node.lookup("#if-history-table");
                var details=(TextArea)node.lookup("#if-history-details");
                choose.fire();if(!choose.isDisabled())throw new IllegalStateException("Scan did not disable launch");
                long start=System.nanoTime();var timer=new PauseTransition(Duration.millis(100));
                timer.setOnFinished(e->{
                    try {
                        if(choose.isDisabled()) {
                            if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(20))throw new IllegalStateException("History scan timeout");
                            timer.playFromStart();return;
                        }
                        if(table.getItems().size()!=expected)throw new IllegalStateException(details.getText());
                        table.getSelectionModel().selectIndices(0,1);
                        if(table.getSelectionModel().getSelectedItems().size()!=2 || details.isEditable()
                                || !details.getText().contains("Selected runs: 2") || !details.getText().contains("pixelThreshold"))
                            throw new IllegalStateException("Multi-selection/profile details failed");
                        table.getSelectionModel().clearSelection();
                        if(!details.getText().contains("Structurally complete runs: "+expected))throw new IllegalStateException("Catalog summary not restored");
                        for(var entry:hashes.entrySet())if(!java.util.Arrays.equals(entry.getValue(),Files.readAllBytes(entry.getKey().resolve("manifest.json"))))
                            throw new IllegalStateException("History manifest changed");
                        stage.close();
                        System.out.println("IF_HISTORY_GUI_OK runs="+expected+" multiSelection=true profileDetails=true manifestBytesUnchanged=true screenshots=false nativeChooser=substituted");
                    }catch(Throwable t){failure.set(t);}finally{
                        if(timer.getStatus()!=javafx.animation.Animation.Status.RUNNING){done.countDown();Platform.exit();}
                    }
                });timer.play();
            }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(35,TimeUnit.SECONDS)){System.err.println("History GUI timed out");System.exit(2);}
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
