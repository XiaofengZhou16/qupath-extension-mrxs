package io.github.xiaofengzhou.qupath.mrxs;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.xml.stream.*;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

/** Vector result map, never a fluorescence raster or input to measurements. */
final class IfClassificationFigure {
    static void write(Path file, ROI roi, List<IfAnalysis.Cell> cells, IfAnalysis.Result result,
                      BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        if (!Double.isFinite(roi.getBoundsX()) || !Double.isFinite(roi.getBoundsY())
                || !Double.isFinite(roi.getBoundsWidth()) || !Double.isFinite(roi.getBoundsHeight())
                || !(roi.getBoundsWidth()>0) || !(roi.getBoundsHeight()>0))
            throw new IllegalArgumentException("Map ROI must have finite coordinates and positive dimensions");
        var measured = new HashMap<String,Integer>();
        for(var c:result.cells()) measured.put(c.id(),c.pn());
        var masks = new TreeSet<>(measured.values());
        var legends = new LinkedHashMap<Integer,List<String>>();
        for(int mask:masks) {
            String label=mask==0?"No marker positive":java.util.stream.IntStream.range(0,result.profile().markers().size())
                .filter(i->(mask&(1<<i))!=0).mapToObj(i->result.profile().markers().get(i).name()).collect(java.util.stream.Collectors.joining(" + "));
            legends.put(mask,wrap("Mask "+mask+": "+label));
        }
        int legendLines=legends.values().stream().mapToInt(List::size).sum();
        if(Files.exists(file))throw new FileAlreadyExistsException(file.toString());
        Path staging=Files.createTempFile(file.toAbsolutePath().getParent(),".classification-",".tmp");
        try {
        double scale=1000.0/Math.max(roi.getBoundsWidth(),roi.getBoundsHeight());
        double width=roi.getBoundsWidth()*scale,height=roi.getBoundsHeight()*scale;
        try(var out=Files.newBufferedWriter(staging)) {
            var x=XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            x.writeStartDocument("UTF-8","1.0");x.writeStartElement("svg");
            x.writeDefaultNamespace("http://www.w3.org/2000/svg");
            x.writeAttribute("viewBox","0 0 1000 "+(height+120+24*legendLines));
            text(x,10,22,"PN classification map — measured cells only; excluded candidates omitted");
            int line=46;
            for(int mask:masks) {
                x.writeEmptyElement("rect");x.writeAttribute("x","10");x.writeAttribute("y",""+(line-12));x.writeAttribute("width","12");x.writeAttribute("height","12");x.writeAttribute("fill",color(mask));
                for(String label:legends.get(mask)){text(x,30,line,label);line+=24;}
            }
            x.writeStartElement("g");x.writeAttribute("transform","translate(0 "+(line+10)+")");
            x.writeEmptyElement("rect");x.writeAttribute("width",""+width);x.writeAttribute("height",""+height);x.writeAttribute("fill","#dddddd");
            x.writeStartElement("g");x.writeAttribute("transform","scale("+scale+") translate("+(-roi.getBoundsX())+" "+(-roi.getBoundsY())+")");
            x.writeStartElement("defs");x.writeStartElement("clipPath");x.writeAttribute("id","roi");path(x,roi,"none",1,cancelled);x.writeEndElement();x.writeEndElement();
            x.writeStartElement("g");x.writeAttribute("clip-path","url(#roi)");
            for(var c:cells) {
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                Integer mask=measured.get(c.id());if(mask==null)continue;
                x.writeStartElement("g");x.writeAttribute("data-cell-id",c.id());x.writeAttribute("data-pn-mask",""+mask);
                path(x,c.cell(),color(mask),0.8/scale,cancelled);
                if(c.nucleus()!=null)path(x,c.nucleus(),color(mask),0.5/scale,cancelled);
                x.writeEndElement();
            }
            x.writeEndElement();x.writeEndElement();x.writeEndElement();
            double px=result.pixelWidthMicrons();
            double barPx=roi.getBoundsWidth()/5;
            String unit="source px";
            double length=barPx;
            if(Double.isFinite(px)&&px>0){length=barPx*px;unit="µm";}
            double power=Math.pow(10,Math.floor(Math.log10(length)));
            double nice=Math.floor(length/power)*power;
            double bar=nice/(unit.equals("µm")?px:1)*scale;
            x.writeEmptyElement("rect");x.writeAttribute("x","10");x.writeAttribute("y",""+(line+height+24));x.writeAttribute("width",""+bar);x.writeAttribute("height","2");
            text(x,10,line+height+44,String.format(Locale.ROOT,"%.4g %s (horizontal)",nice,unit));
            x.writeEndElement();x.writeEndDocument();x.close();
        } catch(XMLStreamException e){throw new IOException(e);}
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
        Files.move(staging,file);
        } finally { Files.deleteIfExists(staging); }
    }
    static List<String> wrap(String text) {
        var lines=new ArrayList<String>();int[] points=text.codePoints().toArray();
        for(int i=0;i<points.length;i+=60)lines.add(new String(points,i,Math.min(60,points.length-i)));
        if(lines.isEmpty())lines.add("");
        return lines;
    }
    static String sha256(Path file, BooleanSupplier cancelled) throws IOException {
        try {
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)) {
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1) {
                    if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                    digest.update(buffer,0,n);
                }
            }
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static String color(int mask) {
        var c=mask==0?java.awt.Color.DARK_GRAY:java.awt.Color.getHSBColor((float)((mask*0.61803398875)%1),0.75f,1);
        return String.format(Locale.ROOT,"#%06x",c.getRGB()&0xffffff);
    }
    private static void text(XMLStreamWriter x,double a,double b,String s)throws XMLStreamException {
        x.writeStartElement("text");x.writeAttribute("x",""+a);x.writeAttribute("y",""+b);x.writeAttribute("font-size","14");x.writeAttribute("font-family","sans-serif");x.writeCharacters(s);x.writeEndElement();
    }
    private static void path(XMLStreamWriter x,ROI roi,String color,double stroke,BooleanSupplier cancelled)throws XMLStreamException {
        var p=RoiTools.getShape(roi).getPathIterator(null);var d=new StringBuilder();double[] a=new double[6];
        while(!p.isDone()) {
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            int k=p.currentSegment(a);d.append("MLQCZ".charAt(k));int n=switch(k){case 0,1->2;case 2->4;case 3->6;default->0;};for(int i=0;i<n;i++)d.append(a[i]).append(' ');p.next();}
        x.writeEmptyElement("path");x.writeAttribute("d",d.toString());x.writeAttribute("fill","none");x.writeAttribute("stroke",color);x.writeAttribute("stroke-width",""+stroke);x.writeAttribute("clip-rule",p.getWindingRule()==java.awt.geom.PathIterator.WIND_EVEN_ODD?"evenodd":"nonzero");
    }
}
