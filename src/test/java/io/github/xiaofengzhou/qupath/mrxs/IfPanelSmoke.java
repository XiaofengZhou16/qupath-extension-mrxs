package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Opt-in GUI construction smoke. No screenshot, no persisted preferences, no existing QuPath instance touched. */
public class IfPanelSmoke {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("slide.mrxs isolated-user-directory");
        System.setProperty("java.util.prefs.PreferencesFactory", MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1); var failure=new AtomicReference<Throwable>();
        Platform.startup(()-> {
            try {
                java.nio.file.Files.createDirectories(Path.of(args[1]));
                PathPrefs.userPathProperty().set(args[1]);
                var qupath=QuPathGUI.createHiddenInstance();
                var server=new MrxsImageServer(Path.of(args[0]).toUri());
                var data=new ImageData<>(server,ImageData.ImageType.FLUORESCENCE);
                qupath.getViewer().setImageData(data);
                new IfAnalysisPanel(qupath).show();
                Platform.runLater(()-> {
                    try {
                        var panel=Window.getWindows().stream().filter(w->w instanceof Stage s && s.getTitle().startsWith("Multiplex IF"))
                                .map(Stage.class::cast).findFirst().orElseThrow();
                        var root=panel.getScene().getRoot(); root.applyCss(); root.layout();
                        var mode=root.lookupAll(".combo-box").stream().filter(n->n instanceof ComboBox<?> c
                                && c.getItems().contains("InstanSeg AI (optional extension)")).map(n->(ComboBox<?>)n).findFirst().orElseThrow();
                        for(int choice=0;choice<3;choice++) { mode.getSelectionModel().select(choice); root.applyCss(); root.layout(); }
                        var scroll=(ScrollPane)root;
                        if(scroll.getViewportBounds().getWidth()<700) throw new IllegalStateException("Unexpected viewport width");
                        if(!IfInstanSeg.isAvailable(IfInstanSeg.extensionLoader())) throw new IllegalStateException("Optional AI classloader not found");
                        if(!data.getHierarchy().getDetectionObjects().isEmpty()) throw new IllegalStateException("Opening panel modified detections");
                        System.out.println("IF_PANEL_SMOKE_OK modes=3 viewport="+scroll.getViewportBounds()+" content="+scroll.getContent().getLayoutBounds()+" noScreenshots=true");
                        panel.close(); qupath.getViewer().resetImageData(); server.close();
                    } catch(Throwable t) {failure.set(t);}
                    finally {done.countDown();Platform.exit();}
                });
            } catch(Throwable t) {failure.set(t);done.countDown();Platform.exit();}
        });
        if(!done.await(45,TimeUnit.SECONDS)) {System.err.println("GUI smoke timeout");System.exit(2);}
        if(failure.get()!=null) {failure.get().printStackTrace();System.exit(1);}
        System.exit(0);
    }

    public static final class MemoryPreferencesFactory implements PreferencesFactory {
        private final Preferences root=new MemoryNode(null,"");
        public Preferences userRoot(){return root;}
        public Preferences systemRoot(){return root;}
    }
    private static final class MemoryNode extends AbstractPreferences {
        private final HashMap<String,String> values=new HashMap<>();
        MemoryNode(AbstractPreferences parent,String name){super(parent,name);}
        protected void putSpi(String key,String value){values.put(key,value);}
        protected String getSpi(String key){return values.get(key);}
        protected void removeSpi(String key){values.remove(key);}
        protected void removeNodeSpi(){values.clear();}
        protected String[] keysSpi(){return values.keySet().toArray(String[]::new);}
        protected String[] childrenNamesSpi(){return new String[0];}
        protected AbstractPreferences childSpi(String name){return new MemoryNode(this,name);}
        protected void syncSpi(){}
        protected void flushSpi(){}
    }
}
