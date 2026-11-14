package io.github.xiaofengzhou.qupath.mrxs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
class IfBatchTelemetryTest {
 @TempDir Path root;
 @Test void preservesSuccessFailureAndCancellationWithSeparateSessions()throws Exception {
  var log=new IfBatchTelemetry(root);assertEquals("result",log.measure("inference","roi",()->"result"));
  var error=new IOException("source failure");assertSame(error,assertThrows(IOException.class,()->log.measure("read","roi",()->{throw error;})));
  assertThrows(CancellationException.class,()->log.measure("inference","roi2",()->{throw new CancellationException();}));
  Path first;try(var paths=Files.list(root)){first=paths.findFirst().orElseThrow();}
  var lines=Files.readAllLines(first);assertEquals(4,lines.size());
  var states=lines.stream().map(l->com.google.gson.JsonParser.parseString(l).getAsJsonObject().get("status").getAsString()).toList();
  assertEquals(java.util.List.of("started","complete","failed","cancelled"),states);
  new IfBatchTelemetry(root);assertEquals(lines,Files.readAllLines(first));
  try(var paths=Files.list(root)){assertEquals(2,paths.count());}
 }
}
