package io.github.xiaofengzhou.qupath.mrxs;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import javafx.scene.control.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/** Exercises actual report button/background task; native choosers injected, no screenshots. */
public class IfHistoryReportGuiSmoke {
    public static void main(String[] args) throws Exception {
        Path history=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectory(output);
        boolean wantExcel=args.length<3||!args[2].equals("word"),wantWord=args.length<3||!args[2].equals("excel");
        var catalog=IfAnalysisHistory.scan(history,()->false);
        int selectedRuns=Integer.getInteger("if.smoke.reportRuns",2);
        if(selectedRuns<2||catalog.entries().size()<selectedRuns)throw new IllegalArgumentException("Insufficient complete runs");
        var originals=new java.util.HashMap<Path,byte[]>();
        for(var e:catalog.entries())originals.put(e.directory(),Files.readAllBytes(e.directory().resolve("manifest.json")));
        System.setProperty("java.util.prefs.PreferencesFactory",IfPanelSmoke.MemoryPreferencesFactory.class.getName());
        var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Platform.startup(()->{try {
            Path prefs=output.resolve("prefs");Files.createDirectory(prefs);PathPrefs.userPathProperty().set(prefs.toString());
            var choice=new AtomicInteger(0);
            var stage=IfHistoryPanel.show(QuPathGUI.createHiddenInstance(),owner->history,owner->switch(choice.getAndIncrement()) {
                case 0 -> null; // Native chooser cancellation must leave state unchanged.
                case 1 -> output.resolve("missing-parent"); // Deterministic background failure.
                default -> output;
            });
            var root=stage.getScene().getRoot();var choose=(Button)root.lookup("#if-history-choose");
            var export=(Button)root.lookup("#if-history-export");var cancel=(Button)root.lookup("#if-history-cancel");
            var table=(TableView<?>)root.lookup("#if-history-table");var status=(Label)root.lookup("#if-history-status");
            if(!export.isDisabled()||!cancel.isDisabled())throw new AssertionError("Initial controls");
            choose.fire();var phase=new AtomicInteger(0);var expectedRows=new AtomicInteger(0);
            var expectedNuclearRuns=new AtomicInteger(0);long start=System.nanoTime();var timer=new Timeline();
            timer.getKeyFrames().add(new KeyFrame(Duration.millis(50),event->{try {
                if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(30))throw new AssertionError("GUI timeout: "+status.getText());
                if(choose.isDisabled())return;
                if(phase.get()==0) {
                    table.getSelectionModel().selectRange(0,selectedRuns);if(export.isDisabled())throw new AssertionError("Selection did not enable export");
                    var excel=(CheckBox)root.lookup("#if-history-export-excel");var word=(CheckBox)root.lookup("#if-history-export-word");
                    excel.setSelected(false);word.setSelected(false);if(!export.isDisabled())throw new AssertionError("No-format export enabled");
                    excel.setSelected(wantExcel);word.setSelected(wantWord);if(export.isDisabled())throw new AssertionError("Requested format not enabled");
                    for(var selected:table.getSelectionModel().getSelectedItems()) {
                        var entry=(IfAnalysisHistory.Entry)selected;
                        var source=com.google.gson.JsonParser.parseString(new String(originals.get(entry.directory()),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        boolean nuclear=source.has("nuclearMeasurements");if(nuclear)expectedNuclearRuns.incrementAndGet();
                        for(int mask=1;mask<(1<<entry.profile().markers().size());mask++)
                            expectedRows.addAndGet((nuclear?10:8)*(1+Integer.bitCount(mask)));
                    }
                    String before=status.getText();export.fire();if(choose.isDisabled()||!before.equals(status.getText()))throw new AssertionError("Chooser cancel changed state");
                    export.fire();if(!choose.isDisabled()||!export.isDisabled()||cancel.isDisabled())throw new AssertionError("Busy controls");
                    phase.set(1);return;
                }
                if(phase.get()==1) {
                    if(!status.getText().startsWith("Report failed:"))throw new AssertionError(status.getText());
                    if(export.isDisabled()||!cancel.isDisabled())throw new AssertionError("Failure recovery");
                    export.fire();phase.set(2);return;
                }
                if(!status.getText().startsWith("Report saved:"))throw new AssertionError(status.getText());
                java.util.List<Path> reports;try(var paths=Files.list(output)){reports=paths.filter(p->p.getFileName().toString().startsWith("if-report-")).toList();}
                if(reports.size()!=1)throw new AssertionError("Report count");Path report=reports.getFirst();
                if(Files.exists(report.resolve("INCOMPLETE"))||Files.exists(report.resolve("results.xlsx"))!=wantExcel||Files.exists(report.resolve("summary.docx"))!=wantWord)throw new AssertionError("Report incomplete/incorrect formats");
                var json=com.google.gson.JsonParser.parseString(Files.readString(report.resolve("manifest.json"))).getAsJsonObject();
                if(json.get("runs").getAsInt()!=selectedRuns||json.get("rows").getAsInt()!=expectedRows.get()
                        ||json.get("wordQuantitativeRows").getAsInt()!=(wantWord?expectedRows.get():0))throw new AssertionError("Selected run/row count");
                int nuclearRuns=0;
                for(var source:json.getAsJsonArray("sources"))if(source.getAsJsonObject().get("nuclearSupplementStatus").getAsString().equals("included"))nuclearRuns++;
                if(nuclearRuns!=expectedNuclearRuns.get())throw new AssertionError("Nuclear supplement omitted");
                var xmlFactory=javax.xml.parsers.DocumentBuilderFactory.newInstance();xmlFactory.setNamespaceAware(true);
                xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                if(wantExcel)try(var zip=new java.util.zip.ZipFile(report.resolve("results.xlsx").toFile());var input=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {
                    var xml=xmlFactory.newDocumentBuilder().parse(input);
                    if(xml.getElementsByTagNameNS("*","row").getLength()!=expectedRows.get()+1)throw new AssertionError("Workbook rows missing");
                }
                for(var e:originals.entrySet())if(!java.util.Arrays.equals(e.getValue(),Files.readAllBytes(e.getKey().resolve("manifest.json"))))throw new AssertionError("Source changed");
                table.getSelectionModel().clearSelection();if(!export.isDisabled())throw new AssertionError("Empty selection export");
                timer.stop();stage.close();System.out.println("IF_HISTORY_REPORT_GUI_OK selectedRuns="+selectedRuns+" rows="+expectedRows.get()+" nuclearRuns="+nuclearRuns+" chooserCancel=true failureRecovery=true screenshots=false output="+report);done.countDown();Platform.exit();
            }catch(Throwable t){failure.set(t);timer.stop();stage.close();done.countDown();Platform.exit();}}));
            timer.setCycleCount(Timeline.INDEFINITE);timer.play();
        }catch(Throwable t){failure.set(t);done.countDown();Platform.exit();}});
        if(!done.await(40,TimeUnit.SECONDS)){System.err.println("GUI timeout");System.exit(2);}
        if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}System.exit(0);
    }
}
