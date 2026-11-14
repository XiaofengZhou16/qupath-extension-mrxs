package io.github.xiaofengzhou.qupath.mrxs;
import java.nio.file.*;
import java.util.*;
public class IfClassificationFigureSmoke {
 public static void main(String[] args)throws Exception {
  if(args.length!=1)throw new IllegalArgumentException("new SVG output path");
  var roi=IfNuclearMeasurementsTest.rect(0,0,32,8);
  var cells=new ArrayList<IfAnalysis.Cell>();
  for(int i=0;i<4;i++)cells.add(new IfAnalysis.Cell("synthetic-"+i,IfNuclearMeasurementsTest.rect(4+8*i,0,4,4),IfNuclearMeasurementsTest.rect(5+8*i,1,2,2)));
  var markers=new ArrayList<IfAnalysisProfile.Marker>();
  for(int i=0;i<3;i++)markers.add(new IfAnalysisProfile.Marker(i,"SYNTHETIC marker "+i+" <validation only> & "+"extended-name-".repeat(8),100,100,100));
  try(var server=new IfAnalysisTest.TruthServer(3)) {
   server.setMetadata(new qupath.lib.images.servers.ImageServerMetadata.Builder(server.getMetadata()).channels(markers.stream().map(m->qupath.lib.images.servers.ImageChannel.getInstance(m.name(),0xffffff)).toList()).build());
   var result=IfAnalysis.analyze(server,roi,new IfAnalysisProfile(1,0,markers),cells,()->false,p->{});
   IfClassificationFigure.write(Path.of(args[0]),roi,cells,result,()->false);
   System.out.println("SYNTHETIC_LAYOUT_ONLY masks="+result.cells().stream().map(IfAnalysis.CellResult::pn).toList());
  }
 }
}
