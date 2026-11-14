package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class IfBatchQueueTest {
    @TempDir Path root;
    final List<IfBatchQueue.Item> items = List.of(new IfBatchQueue.Item("slide1-roi1","input-a"), new IfBatchQueue.Item("slide2-roi1","input-b"));
    IfBatchQueue.Work work(AtomicInteger calls) {
        return new IfBatchQueue.Work() {
            public void execute(IfBatchQueue.Item i, Path p, java.util.function.BooleanSupplier c) throws Exception {
                calls.incrementAndGet(); Files.createDirectory(p); Files.writeString(p.resolve("result"), i.fingerprint());
            }
            public void verify(IfBatchQueue.Item i, Path p) throws Exception {
                if (!Files.readString(p.resolve("result")).equals(i.fingerprint())) throw new IOException("Corrupt output");
            }
        };
    }
    @Test void resumesWithoutOverwriteAndRetriesTamperedOutputInNewDirectory() throws Exception {
        var calls = new AtomicInteger(); var w = work(calls);
        var first = IfBatchQueue.run(root, items, w, ()->false);
        IfBatchQueue.run(root, items, w, ()->false); assertEquals(2, calls.get());
        var damaged = root.resolve(first.attempts().getFirst().directory()).resolve("result");
        Files.writeString(damaged,"damaged");
        var recovered = IfBatchQueue.run(root, items, w, ()->false);
        assertEquals(3,calls.get()); assertEquals("damaged",Files.readString(damaged));
        assertEquals("invalid",recovered.attempts().getFirst().status());
        assertEquals("complete",recovered.attempts().getLast().status());
        assertThrows(IOException.class,()->IfBatchQueue.run(root,List.of(new IfBatchQueue.Item("slide1-roi1","changed")),w,()->false));
    }
    @Test void cancellationStopsNextTaskAndResumeKeepsCancelledAttempt() throws Exception {
        var flag = new AtomicBoolean(); var calls = new AtomicInteger(); var delegate=work(calls);
        var stop = new IfBatchQueue.Work() {
            public void execute(IfBatchQueue.Item i,Path p,java.util.function.BooleanSupplier c) throws Exception {
                delegate.execute(i,p,c); flag.set(true);
            }
            public void verify(IfBatchQueue.Item i,Path p) throws Exception {delegate.verify(i,p);}
        };
        var first=IfBatchQueue.run(root,items,stop,flag::get);
        assertEquals(1,calls.get()); assertEquals("cancelled",first.attempts().getFirst().status());
        flag.set(false); var resumed=IfBatchQueue.run(root,items,delegate,flag::get);
        assertEquals(3,calls.get()); assertEquals(3,resumed.attempts().size());
    }
    @Test void interruptedAttemptIsPreservedAndRetried() throws Exception {
        var directory="attempt-00000000-0000-0000-0000-000000000001";
        Files.createDirectory(root.resolve(directory));
        Files.writeString(root.resolve(directory).resolve("partial"),"keep");
        var journal=new IfBatchQueue.Journal(1,items,List.of(new IfBatchQueue.Attempt(items.getFirst().id(),directory,"running","")));
        Files.writeString(root.resolve("queue.json"),qupath.lib.io.GsonTools.getInstance().toJson(journal));
        var result=IfBatchQueue.run(root,items,work(new AtomicInteger()),()->false);
        assertEquals("interrupted",result.attempts().getFirst().status());
        assertEquals(3,result.attempts().size());
        assertEquals("keep",Files.readString(root.resolve(directory).resolve("partial")));
    }
    @Test void failedTaskDoesNotBlockNextAndConcurrentWriterIsRejected() throws Exception {
        var calls=new AtomicInteger(); var delegate=work(calls);
        var failing=new IfBatchQueue.Work() {
            public void execute(IfBatchQueue.Item i,Path p,java.util.function.BooleanSupplier c) throws Exception {
                assertThrows(IOException.class,()->IfBatchQueue.run(root,items,delegate,()->false));
                if(i.equals(items.getFirst()))throw new IOException("source unavailable");
                delegate.execute(i,p,c);
            }
            public void verify(IfBatchQueue.Item i,Path p) throws Exception {delegate.verify(i,p);}
        };
        var result=IfBatchQueue.run(root,items,failing,()->false);
        assertEquals(List.of("failed","complete"),result.attempts().stream().map(IfBatchQueue.Attempt::status).toList());
    }
}
