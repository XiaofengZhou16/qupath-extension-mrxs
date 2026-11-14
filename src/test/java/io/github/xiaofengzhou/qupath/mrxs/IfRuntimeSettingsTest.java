package io.github.xiaofengzhou.qupath.mrxs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IfRuntimeSettingsTest {
 @Test void predictorChangeInvalidatesSavedRuntimeIdentityAndSnapshotRemainsImmutable() {
  String key="instanseg.numPredictors",previous=System.getProperty(key);
  try {
   System.setProperty(key,"1");var first=IfInstanSeg.runtimeSettings();
   System.setProperty(key,"2");var second=IfInstanSeg.runtimeSettings();
   assertEquals("1",first.get("numPredictors"));assertEquals("2",second.get("numPredictors"));assertNotEquals(first,second);
   assertNotEquals(IfBatchOutput.hashText(qupath.lib.io.GsonTools.getInstance().toJson(first)),IfBatchOutput.hashText(qupath.lib.io.GsonTools.getInstance().toJson(second)));
   assertThrows(UnsupportedOperationException.class,()->first.put("numPredictors","3"));
  }finally{if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);}
 }
}
