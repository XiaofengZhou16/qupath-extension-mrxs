package io.github.xiaofengzhou.qupath.mrxs;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.util.zip.*;
import javax.xml.stream.*;
import qupath.lib.io.GsonTools;

/** Streaming Office reports from validated snapshots; never aggregates biological replicates. */
final class IfOfficeReport {
    private static final String S="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String P="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String C="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String NUCLEAR_NOTE="NPN_NUCLEUS_ONLY is a separate population: valid sampled nuclei, independent of cell/cytoplasm validity, not all DAPI proposals. Its overall denominator is all_measured_nuclei; conditional ratios use marker-positive nuclei. Do not merge this metric with legacy NPN. Missing supplements in older runs are unavailable, not zero.";
    static String wordNumber(String value) {
        if (value.isEmpty()) return value;
        try {
            var number = new java.math.BigDecimal(value);
            if (number.signum() == 0) return "0";
            var rounded = number.round(new java.math.MathContext(4, java.math.RoundingMode.HALF_UP)).stripTrailingZeros();
            int exponent = rounded.precision() - rounded.scale() - 1;
            if (exponent < -3 || exponent >= 5)
                return rounded.movePointLeft(exponent).toPlainString() + "e" + exponent;
            return rounded.toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static final String NOTE="Selected report formats retain full combination results separately per run; no biological replicate pooling, AI accuracy claim or original-pixel authentication. Blank ratios are undefined, not zero. NPN/CPN/PN denominators are eligible measured cells, not all DAPI nuclei. PA uses sampled ROI area, not tissue area. Word ratios, densities and areas are rounded to 4 significant digits for display; original CSV retains full precision. Counts remain exact; ratios are fractions, not percentages. Excel numeric columns use numbers only within a conservative 15-digit/range guard; precision-protected exceptions remain text. Labels, identifiers and dates remain text. Inspect text-valued exceptions before calculations; Excel aggregates may ignore them.";

    record Formats(boolean excel,boolean word) {
        Formats {if(!excel&&!word)throw new IllegalArgumentException("Select Excel, Word or both");}
    }
    static void write(List<Path> selected,Path output,BooleanSupplier cancelled) throws IOException {
        write(selected,output,new Formats(true,true),cancelled);
    }
    static void write(List<Path> selected,Path output,Formats formats,BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(formats,"Report formats");
        var batch=IfBatchReportInput.load(selected,cancelled);
        var figures=new ArrayList<IfReportFigureInput.Figure>();long figureBytes=0;
        if(formats.word())for(var run:batch.runs()) {check(cancelled);var figure=IfReportFigureInput.load(run);figures.add(figure);
            if(figure!=null)figureBytes+=figure.png().length;
            if(figureBytes>64L*1024*1024)throw new IOException("Report figures exceed 64 MiB; export a smaller selection");}
        check(cancelled);
        Files.createDirectory(output); // Must be new; never overwrite an analysis or previous report.
        Files.writeString(output.resolve("INCOMPLETE"),"Report is not complete",StandardOpenOption.CREATE_NEW);
        try {
            if(formats.excel())workbook(batch,output.resolve("results.xlsx"),cancelled);
            if(formats.word())document(batch,figures,output.resolve("summary.docx"),cancelled);
            var manifest=new LinkedHashMap<String,Object>();
            manifest.put("schemaVersion",1);manifest.put("status","complete");
            manifest.put("createdAt",java.time.Instant.now().toString());
            manifest.put("runs",batch.runs().size());manifest.put("rows",batch.tableRows());
            manifest.put("requestedFormats",formats);
            manifest.put("wordQuantitativeRows",formats.word()?batch.tableRows():0);manifest.put("wordTableLayout",formats.word()?"per-run grouped by mode, metric and denominator type; counts exact, continuous values 4 significant digits":"not_requested");
            manifest.put("profileGroups",batch.profileGroups());manifest.put("limitations",NOTE);
            manifest.put("nuclearMeasurementDefinition",NUCLEAR_NOTE);
            manifest.put("warnings",batch.warnings());
            var figureAudit=new ArrayList<Map<String,Object>>();
            if(!formats.word())for(int i=0;i<batch.runs().size();i++)figureAudit.add(Map.of("runId",i+1,"status","not_requested"));
            for(int i=0;i<figures.size();i++){var f=figures.get(i);figureAudit.add(f==null?Map.of("runId",i+1,"status","unavailable"):
                    Map.of("runId",i+1,"status","embedded","pngSha256",f.sha256(),"width",f.width(),"height",f.height()));}
            manifest.put("reportFigures",figureAudit);
            manifest.put("sources",batch.runs().stream().map(r->Map.of("directory",r.entry().directory().toString(),
                    "manifestSha256",r.manifestSha256(),"combinationsSha256",r.combinationsSha256(),
                    "profileSha256",r.profileSha256(),"nuclearCombinationsSha256",r.nuclearCombinationsSha256(),
                    "nuclearSupplementStatus",r.measuredNuclei()<0?"unavailable":"included")).toList());
            var files=new LinkedHashMap<String,String>();
            if(formats.excel())files.put("results.xlsx",hash(output.resolve("results.xlsx")));
            if(formats.word())files.put("summary.docx",hash(output.resolve("summary.docx")));
            manifest.put("files",files);
            check(cancelled);
            Files.writeString(output.resolve("manifest.json"),GsonTools.getInstance(true).toJson(manifest),StandardOpenOption.CREATE_NEW);
            Files.delete(output.resolve("INCOMPLETE"));
        } catch(XMLStreamException e){throw new IOException("Office XML generation failed; partial report retained",e);}
    }

    private static void workbook(IfBatchReportInput.Batch b,Path path,BooleanSupplier cancel) throws IOException,XMLStreamException {
        try(var zip=new ZipOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW))) {
            types(zip,Map.of("/xl/workbook.xml","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                    "/xl/worksheets/sheet1.xml","application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
                    "/xl/worksheets/sheet2.xml","application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"));
            relationships(zip,"_rels/.rels",List.of("xl/workbook.xml"),"officeDocument");
            relationships(zip,"xl/_rels/workbook.xml.rels",List.of("worksheets/sheet1.xml","worksheets/sheet2.xml"),"worksheet");
            var x=part(zip,"xl/workbook.xml","workbook",S);x.writeNamespace("r",R);
            x.writeStartElement("sheets");
            for(int i=1;i<=2;i++){x.writeEmptyElement("sheet");x.writeAttribute("name",i==1?"Combinations":"Runs and methods");x.writeAttribute("sheetId",""+i);x.writeAttribute("r",R,"id","rId"+i);}
            x.writeEndElement();end(zip,x);
            x=part(zip,"xl/worksheets/sheet1.xml","worksheet",S);
            x.writeStartElement("sheetViews");x.writeStartElement("sheetView");x.writeAttribute("workbookViewId","0");
            x.writeEmptyElement("pane");x.writeAttribute("ySplit","1");x.writeAttribute("topLeftCell","A2");x.writeAttribute("activePane","bottomLeft");x.writeAttribute("state","frozen");x.writeEndElement();x.writeEndElement();
            x.writeStartElement("cols");for(int col=1;col<=13;col++){x.writeEmptyElement("col");x.writeAttribute("min",""+col);x.writeAttribute("max",""+col);x.writeAttribute("width",col==2?"48":col==4||col==9?"32":col==3?"26":"20");x.writeAttribute("customWidth","1");}x.writeEndElement();
            x.writeStartElement("sheetData");
            var header=new ArrayList<String>(List.of("run_id","image","created_at"));header.addAll(IfBatchReportInput.HEADER);row(x,header);
            int id=0;
            for(var run:b.runs()) {id++;for(var values:run.rows()){check(cancel);var cells=new ArrayList<String>(List.of(""+id,run.entry().imageName(),run.entry().createdAt()));cells.addAll(values);row(x,cells,Set.of(4,7,9,10,11,12));}}
            x.writeEndElement();x.writeEmptyElement("autoFilter");x.writeAttribute("ref","A1:M"+(b.tableRows()+1));end(zip,x);
            x=part(zip,"xl/worksheets/sheet2.xml","worksheet",S);x.writeStartElement("sheetData");
            row(x,List.of("Methods / limitations",NOTE));row(x,List.of("Independent nuclear measurements",NUCLEAR_NOTE));
            for(var run:b.runs())row(x,List.of("Nuclear supplement",run.entry().directory().toString(),
                    run.measuredNuclei()<0?"unavailable":"measured nuclei: "+run.measuredNuclei(),run.nuclearCombinationsSha256()));
            for(String warning:b.warnings())row(x,List.of("Warning",warning));
            row(x,List.of("run_id","image","directory","created_at","proposed_cells","measured_cells","source_status","profile_json","manifest_sha256","combinations_sha256","profile_sha256","roi_json"));
            id=0;for(var run:b.runs()) {check(cancel);var e=run.entry();row(x,List.of(""+(++id),e.imageName(),e.directory().toString(),e.createdAt(),""+e.proposedCells(),""+e.measuredCells(),e.sourceStatus(),GsonTools.getInstance().toJson(e.profile()),run.manifestSha256(),run.combinationsSha256(),run.profileSha256(),GsonTools.getInstance().toJson(e.roi(),qupath.lib.roi.interfaces.ROI.class)),Set.of(4,5));}
            x.writeEndElement();end(zip,x);
        }
    }

    private static void document(IfBatchReportInput.Batch b,List<IfReportFigureInput.Figure> figures,Path path,BooleanSupplier cancel) throws IOException,XMLStreamException {
        try(var zip=new ZipOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW))) {
            var contentTypes=new LinkedHashMap<String,String>();contentTypes.put("/word/document.xml","application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
            var targets=new ArrayList<String>();int pictureId=0;
            for(var f:figures)if(f!=null){check(cancel);String target="media/figure"+(++pictureId)+".png";targets.add(target);contentTypes.put("/word/"+target,"image/png");zip.putNextEntry(new ZipEntry("word/"+target));zip.write(f.png());zip.closeEntry();}
            types(zip,contentTypes);
            relationships(zip,"_rels/.rels",List.of("word/document.xml"),"officeDocument");
            if(!targets.isEmpty())relationships(zip,"word/_rels/document.xml.rels",targets,"image");
            var x=part(zip,"word/document.xml","document",W);x.writeNamespace("w",W);x.writeNamespace("r",R);x.writeNamespace("wp",WP);x.writeNamespace("a",A);x.writeNamespace("pic",PIC);x.writeStartElement("body");
            paragraph(x,"IF analysis batch summary / IF 分析批量摘要",true);
            paragraph(x,"Runs: "+b.runs().size()+"; combination rows: "+b.tableRows()+"; parameter groups: "+b.profileGroups(),false);
            paragraph(x,NOTE,false);paragraph(x,NUCLEAR_NOTE,false);for(String warning:b.warnings())paragraph(x,warning,false);
            int id=0;pictureId=0;for(var run:b.runs()) {check(cancel);var e=run.entry();
                paragraph(x,"Run "+(++id)+": "+e.imageName(),true,true);
                paragraph(x,"Created: "+e.createdAt()+"; measured/proposed cells: "+e.measuredCells()+"/"+e.proposedCells()+"; source: "+e.sourceStatus(),false);
                paragraph(x,"Directory: "+e.directory(),false);
                paragraph(x,"ROI bounds (source pixels): "+e.roi().getBoundsX()+", "+e.roi().getBoundsY()+", "+e.roi().getBoundsWidth()+", "+e.roi().getBoundsHeight()+"; resolution level: "+e.profile().resolutionLevel(),false);
                for(var m:e.profile().markers())paragraph(x,m.name()+" [channel "+m.channel()+"] raw thresholds: pixel="+m.pixelThreshold()+", nucleus="+m.nucleusThreshold()+", cytoplasm="+m.cytoplasmThreshold(),false);
                paragraph(x,"Combination CSV SHA256: "+run.combinationsSha256(),false);
                paragraph(x,run.measuredNuclei()<0?"Independent nuclear measurements unavailable for this run.":
                        "Independent measured nuclei: "+run.measuredNuclei()+"; nuclear CSV SHA256: "+run.nuclearCombinationsSha256(),false);
                var figure=figures.get(id-1);
                if(figure==null)paragraph(x,"ROI figure unavailable: this run has no linked exported preview. No image was inferred from filenames.",false);
                else {picture(x,figure,++pictureId);paragraph(x,figure.caption(),false);}
                quantitativeTables(x,run,cancel);
            }
            x.writeStartElement("sectPr");x.writeEmptyElement("pgSz");x.writeAttribute("w",W,"w","11906");x.writeAttribute("w",W,"h","16838");
            x.writeEmptyElement("pgMar");for(String side:List.of("top","right","bottom","left"))x.writeAttribute("w",W,side,"1440");x.writeEndElement();
            x.writeEndElement();end(zip,x);
        }
    }

    private static void quantitativeTables(XMLStreamWriter x,IfBatchReportInput.Run run,BooleanSupplier cancel)throws XMLStreamException,IOException {
        // Grouping changes presentation order only; every validated CSV row is retained exactly once.
        var groups=new LinkedHashMap<List<String>,List<List<String>>>();
        for(var row:run.rows()){check(cancel);groups.computeIfAbsent(List.of(row.get(2),row.get(3),row.get(5)),k->new ArrayList<>()).add(row);}
        paragraph(x,"Quantitative combinations / 组合定量结果",true,true);
        paragraph(x,"Every row belongs to this ROI/run. Ratio is numerator/denominator (0–1), not %. Empty physical values mean unavailable/not applicable. Inclusive and exact combinations are separate; no biological replicate aggregation.",false);
        int[] widths={2126,650,1150,1150,1250,1350,1350}; // 9026 twips = A4 printable width with 1-inch margins.
        for(var group:groups.entrySet()){
            check(cancel);x.writeStartElement("tbl");x.writeStartElement("tblPr");
            x.writeEmptyElement("tblW");x.writeAttribute("w",W,"w","9026");x.writeAttribute("w",W,"type","dxa");
            x.writeStartElement("tblBorders");for(String edge:List.of("top","left","bottom","right","insideH","insideV")){x.writeEmptyElement(edge);x.writeAttribute("w",W,"val","single");x.writeAttribute("w",W,"sz","4");x.writeAttribute("w",W,"color","B8C2CC");}x.writeEndElement();
            x.writeEmptyElement("tblLayout");x.writeAttribute("w",W,"type","fixed");x.writeEndElement();
            x.writeStartElement("tblGrid");for(int width:widths){x.writeEmptyElement("gridCol");x.writeAttribute("w",W,"w",""+width);}x.writeEndElement();
            x.writeStartElement("tr");x.writeStartElement("trPr");x.writeEmptyElement("cantSplit");x.writeEmptyElement("tblHeader");x.writeEndElement();
            wordCell(x,"Run: "+run.entry().directory().getFileName()+" | Mode: "+group.getKey().get(0)+" | Metric: "+group.getKey().get(1)+" | Denominator: "+group.getKey().get(2),9026,true,7);x.writeEndElement();
            wordRow(x,List.of("Combination","Mask","Numerator","Denominator","Ratio (0–1)","Density (/mm²)","Positive area (mm²)"),widths,true);
            for(var row:group.getValue()){check(cancel);wordRow(x,List.of(row.get(0),row.get(1),row.get(4),row.get(6),wordNumber(row.get(7)),wordNumber(row.get(8)),wordNumber(row.get(9))),widths,false);}
            x.writeEndElement();paragraph(x,"",false);
        }
    }
    private static void wordRow(XMLStreamWriter x,List<String> values,int[] widths,boolean header)throws XMLStreamException,IOException {
        x.writeStartElement("tr");x.writeStartElement("trPr");x.writeEmptyElement("cantSplit");
        if(header)x.writeEmptyElement("tblHeader");x.writeEndElement();
        for(int i=0;i<values.size();i++)wordCell(x,values.get(i),widths[i],header,1);x.writeEndElement();
    }
    private static void wordCell(XMLStreamWriter x,String value,int width,boolean header,int span)throws XMLStreamException,IOException {
        x.writeStartElement("tc");x.writeStartElement("tcPr");x.writeEmptyElement("tcW");x.writeAttribute("w",W,"w",""+width);x.writeAttribute("w",W,"type","dxa");
        if(span>1){x.writeEmptyElement("gridSpan");x.writeAttribute("w",W,"val",""+span);}
        if(header){x.writeEmptyElement("shd");x.writeAttribute("w",W,"fill","E9EEF3");}x.writeEndElement();
        paragraph(x,value,header,false,18);x.writeEndElement();
    }

    private static final String WP="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String A="http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String PIC="http://schemas.openxmlformats.org/drawingml/2006/picture";
    private static void picture(XMLStreamWriter x,IfReportFigureInput.Figure f,int id) throws XMLStreamException {
        double scale=Math.min(914400.0/150,Math.min(6*914400.0/f.width(),7*914400.0/f.height()));
        String cx=""+Math.round(f.width()*scale),cy=""+Math.round(f.height()*scale);
        x.writeStartElement("p");x.writeStartElement("pPr");x.writeEmptyElement("keepNext");x.writeEmptyElement("keepLines");x.writeEndElement();x.writeStartElement("r");x.writeStartElement("drawing");
        x.writeStartElement("wp","inline",WP);for(String side:List.of("T","B","L","R"))x.writeAttribute("dist"+side,"0");
        x.writeEmptyElement("wp","extent",WP);x.writeAttribute("cx",cx);x.writeAttribute("cy",cy);
        x.writeEmptyElement("wp","docPr",WP);x.writeAttribute("id",""+id);x.writeAttribute("name","ROI context "+id);x.writeAttribute("descr","Source-derived display composite; see caption and provenance");
        x.writeStartElement("wp","cNvGraphicFramePr",WP);x.writeEmptyElement("a","graphicFrameLocks",A);x.writeAttribute("noChangeAspect","1");x.writeEndElement();
        x.writeStartElement("a","graphic",A);x.writeStartElement("a","graphicData",A);x.writeAttribute("uri",PIC);
        x.writeStartElement("pic","pic",PIC);x.writeStartElement("pic","nvPicPr",PIC);
        x.writeEmptyElement("pic","cNvPr",PIC);x.writeAttribute("id",""+id);x.writeAttribute("name","figure"+id+".png");x.writeEmptyElement("pic","cNvPicPr",PIC);x.writeEndElement();
        x.writeStartElement("pic","blipFill",PIC);x.writeEmptyElement("a","blip",A);x.writeAttribute("r",R,"embed","rId"+id);
        x.writeStartElement("a","stretch",A);x.writeEmptyElement("a","fillRect",A);x.writeEndElement();x.writeEndElement();
        x.writeStartElement("pic","spPr",PIC);x.writeStartElement("a","xfrm",A);
        x.writeEmptyElement("a","off",A);x.writeAttribute("x","0");x.writeAttribute("y","0");x.writeEmptyElement("a","ext",A);x.writeAttribute("cx",cx);x.writeAttribute("cy",cy);x.writeEndElement();
        x.writeStartElement("a","prstGeom",A);x.writeAttribute("prst","rect");x.writeEmptyElement("a","avLst",A);x.writeEndElement();x.writeEndElement();
        for(int i=0;i<7;i++)x.writeEndElement(); // pic, graphicData, graphic, inline, drawing, run, paragraph
    }

    private static void row(XMLStreamWriter x,List<String> values) throws XMLStreamException,IOException {
        row(x,values,Set.of());
    }
    private static void row(XMLStreamWriter x,List<String> values,Set<Integer> numeric) throws XMLStreamException,IOException {
        x.writeStartElement("row");for(int i=0;i<values.size();i++){String value=values.get(i);if(value.length()>32767)throw new IOException("Report cell exceeds Excel text limit");
            x.writeStartElement("c");
            if(numeric.contains(i)&&value.isEmpty()){x.writeEndElement();continue;}
            if(numeric.contains(i)&&safeExcelNumber(value)){x.writeAttribute("t","n");x.writeStartElement("v");text(x,value);x.writeEndElement();x.writeEndElement();continue;}
            x.writeAttribute("t","inlineStr");x.writeStartElement("is");x.writeStartElement("t");
            x.writeAttribute("xml","http://www.w3.org/XML/1998/namespace","space","preserve");
            // Escape SpreadsheetML literal escape sequences before Office interprets them.
            text(x,value.replaceAll("_([xX][0-9a-fA-F]{4}_)","_x005F_$1"));
            x.writeEndElement();x.writeEndElement();x.writeEndElement();}x.writeEndElement();
    }
    static boolean safeExcelNumber(String value) {
        try {
            if(!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))return false;
            var number=new java.math.BigDecimal(value).stripTrailingZeros();
            if(number.precision()>15)return false;
            var magnitude=number.abs();
            if(magnitude.signum()==0)return true;
            if(magnitude.compareTo(new java.math.BigDecimal("1E-307"))<0||magnitude.compareTo(new java.math.BigDecimal("9.99999999999999E307"))>0)return false;
            // Integers with more than 15 decimal positions stay text even if trailing zeros compress precision.
            return number.scale()>0 || magnitude.compareTo(new java.math.BigDecimal("1E15"))<0;
        }catch(NumberFormatException e){return false;}
    }
    private static void paragraph(XMLStreamWriter x,String value,boolean bold) throws XMLStreamException,IOException {
        paragraph(x,value,bold,false);
    }
    private static void paragraph(XMLStreamWriter x,String value,boolean bold,boolean newPage) throws XMLStreamException,IOException {
        paragraph(x,value,bold,newPage,bold?24:20);
    }
    private static void paragraph(XMLStreamWriter x,String value,boolean bold,boolean newPage,int halfPoints) throws XMLStreamException,IOException {
        x.writeStartElement("p");x.writeStartElement("pPr");
        if(newPage)x.writeEmptyElement("pageBreakBefore");
        if(bold)x.writeEmptyElement("keepNext");
        x.writeEmptyElement("widowControl");x.writeEmptyElement("spacing");x.writeAttribute("w",W,"after","100");x.writeAttribute("w",W,"line","276");x.writeAttribute("w",W,"lineRule","auto");x.writeEndElement();
        x.writeStartElement("r");x.writeStartElement("rPr");x.writeEmptyElement("rFonts");
        x.writeAttribute("w",W,"ascii","Arial");x.writeAttribute("w",W,"hAnsi","Arial");x.writeAttribute("w",W,"eastAsia","PingFang SC");
        if(bold)x.writeEmptyElement("b");x.writeEmptyElement("sz");x.writeAttribute("w",W,"val",""+halfPoints);x.writeEndElement();
        // Preserve newlines as explicit Word breaks, not collapsed XML whitespace.
        String[] lines=value.split("\\R",-1);for(int i=0;i<lines.length;i++){if(i>0)x.writeEmptyElement("br");x.writeStartElement("t");x.writeAttribute("xml","http://www.w3.org/XML/1998/namespace","space","preserve");text(x,lines[i]);x.writeEndElement();}
        x.writeEndElement();x.writeEndElement();
    }
    private static void text(XMLStreamWriter x,String value) throws XMLStreamException,IOException {
        for(int i=0;i<value.length();) {int cp=value.codePointAt(i);if(!(cp==9||cp==10||cp==13||cp>=32&&cp<=0xD7FF||cp>=0xE000&&cp<=0xFFFD||cp>=0x10000&&cp<=0x10FFFF))throw new IOException("Unsupported XML character in report text");i+=Character.charCount(cp);}
        x.writeCharacters(value);
    }
    private static XMLStreamWriter part(ZipOutputStream z,String name,String root,String ns) throws IOException,XMLStreamException {
        z.putNextEntry(new ZipEntry(name));var x=XMLOutputFactory.newFactory().createXMLStreamWriter(z,"UTF-8");x.writeStartDocument("UTF-8","1.0");x.writeStartElement(root);x.writeDefaultNamespace(ns);return x;
    }
    private static void end(ZipOutputStream z,XMLStreamWriter x) throws XMLStreamException,IOException {x.writeEndElement();x.writeEndDocument();x.flush();z.closeEntry();}
    private static void types(ZipOutputStream z,Map<String,String> overrides) throws XMLStreamException,IOException {
        var x=part(z,"[Content_Types].xml","Types",C);
        x.writeEmptyElement("Default");x.writeAttribute("Extension","rels");x.writeAttribute("ContentType","application/vnd.openxmlformats-package.relationships+xml");
        x.writeEmptyElement("Default");x.writeAttribute("Extension","xml");x.writeAttribute("ContentType","application/xml");
        for(var e:new TreeMap<>(overrides).entrySet()){x.writeEmptyElement("Override");x.writeAttribute("PartName",e.getKey());x.writeAttribute("ContentType",e.getValue());}end(z,x);
    }
    private static void relationships(ZipOutputStream z,String path,List<String> targets,String type) throws IOException,XMLStreamException {
        var x=part(z,path,"Relationships",P);int i=0;for(String target:targets){x.writeEmptyElement("Relationship");x.writeAttribute("Id","rId"+(++i));x.writeAttribute("Type",R+"/"+type);x.writeAttribute("Target",target);}end(z,x);
    }
    private static String hash(Path p) throws IOException {try {var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void check(BooleanSupplier c){if(c.getAsBoolean())throw new CancellationException();}
}
