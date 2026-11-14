package io.github.xiaofengzhou.qupath.mrxs;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class IfInferenceRunnerTest {
    @Test void cancellationWaitsForNoninterruptibleNativeTask() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var finished=new CountDownLatch(1); var outcome=new AtomicReference<Throwable>();
        var runner=new IfInferenceRunner(1,()->false,v->{});
        var coordinator=new Thread(()->{
            try {runner.runTasks(List.of(()->{
                entered.countDown();
                while(release.getCount()!=0) try {release.await();} catch(InterruptedException ignored) {}
            }));} catch(Throwable t){outcome.set(t);} finally {finished.countDown();}
        });
        coordinator.start();
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); coordinator.interrupt();
            assertFalse(finished.await(200,TimeUnit.MILLISECONDS),"Must not release model while tile is running");
        } finally {release.countDown(); coordinator.join(5000);}
        assertFalse(coordinator.isAlive());
        assertInstanceOf(CancellationException.class,outcome.get()); assertTrue(runner.isCancelled());
    }
    @Test void successfulRunCompletesAllTasksAndReportsProgress() {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var progress=new AtomicReference<Double>();
        var runner=new IfInferenceRunner(2,()->false,progress::set);
        runner.runTasks(List.of(count::incrementAndGet,count::incrementAndGet));
        assertEquals(2,count.get()); assertEquals(1.0,progress.get()); assertFalse(runner.isCancelled());
    }
    @Test void taskFailurePropagatesAndStopsRunner() {
        var runner=new IfInferenceRunner(1,()->false,v->{});
        assertThrows(IllegalStateException.class,()->runner.runTasks(List.of(()->{throw new IllegalArgumentException("test");})));
        assertTrue(runner.isCancelled());
    }
    @Test void cancellationDoesNotSubmitRemainingTiles() {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var visited=new java.util.concurrent.atomic.AtomicInteger();
        var executed=new java.util.concurrent.atomic.AtomicInteger();
        var tasks=new java.util.AbstractCollection<Runnable>() {
            public int size(){return 10000;}
            public java.util.Iterator<Runnable> iterator(){return new java.util.Iterator<>() {
                int index;
                public boolean hasNext(){return index<size();}
                public Runnable next(){index++;visited.incrementAndGet();return executed::incrementAndGet;}
            };}
        };
        var runner=new IfInferenceRunner(1,cancelled::get,p->cancelled.set(true));
        assertThrows(CancellationException.class,()->runner.runTasks(tasks));
        assertEquals(1,executed.get());
        // One scheduling visit plus the separate mandatory PathTask cleanup scan.
        assertEquals(10001,visited.get());
    }
    @Test void streamsLargeBatchWithoutLosingTasks() {
        var executed=new java.util.concurrent.atomic.AtomicInteger();
        var runner=new IfInferenceRunner(2,()->false,p->{});
        runner.runTasks(java.util.Collections.nCopies(10000,(Runnable)executed::incrementAndGet));
        assertEquals(10000,executed.get());
    }
}
