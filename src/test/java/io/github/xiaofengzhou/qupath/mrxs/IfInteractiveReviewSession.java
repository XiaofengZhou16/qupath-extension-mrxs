package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/** Opens an isolated project copy for actual pointer review; performs no automated edits. */
public final class IfInteractiveReviewSession {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("copied project.qpproj new session directory");
        Path projectFile=Path.of(args[0]),output=Path.of(args[1]);
        Files.createDirectory(output);
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);
        Platform.startup(()->{
            try {
                PathPrefs.userPathProperty().set(Files.createDirectory(output.resolve("prefs")).toString());
                var q=QuPathGUI.createHiddenInstance();
                var project=qupath.lib.projects.ProjectIO.loadProject(projectFile.toFile(),java.awt.image.BufferedImage.class);
                q.setProject(project);
                if(!q.openImageEntry(project.getImageList().getFirst()))throw new IllegalStateException("Project did not open");
                q.getStage().setWidth(1200);q.getStage().setHeight(850);q.getStage().show();
                q.getStage().setOnHidden(e->{done.countDown();Platform.exit();});
                Files.writeString(output.resolve("session.json"),qupath.lib.io.GsonTools.getInstance(true).toJson(java.util.Map.of(
                    "software",IfSoftwareProvenance.snapshot(),"project",projectFile.toAbsolutePath().toString(),
                    "purpose","Actual pointer review; no automated editing; no accuracy claim")));
                System.out.println("INTERACTIVE_READY");
            }catch(Throwable t){t.printStackTrace();done.countDown();Platform.exit();}
        });
        done.await();
    }
}
