package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.plugins.PathTask;
import qupath.lib.plugins.TaskRunner;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Never return to model cleanup while an inference task still owns native resources. */
final class IfInferenceRunner implements TaskRunner {
    private final int threads;
    private final BooleanSupplier cancelled;
    private final DoubleConsumer progress;
    private volatile boolean stopped;

    IfInferenceRunner(int threads, BooleanSupplier cancelled, DoubleConsumer progress) {
        if (threads < 1 || threads > 4) throw new IllegalArgumentException("Invalid inference parallelism");
        this.threads=threads; this.cancelled=cancelled; this.progress=progress;
    }
    @Override public boolean isCancelled() { return stopped || cancelled.getAsBoolean(); }
    @Override public synchronized void runTasks(String message, Collection<? extends Runnable> tasks) {
        if (isCancelled() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (tasks.isEmpty()) return;
        var pool=Executors.newFixedThreadPool(threads, r -> {
            var t=new Thread(r,"if-inference-tile"); t.setDaemon(true); return t;
        });
        var completion=new ExecutorCompletionService<Void>(pool);
        boolean interrupted=false, success=false;
        try {
            var remaining=tasks.iterator();
            int completed=0, inFlight=0;
            while(completed<tasks.size()) {
                if(isCancelled()) throw new CancellationException();
                // Retain at most one submitted task per worker, including completed
                // results not yet consumed. Never queue a whole slide of Futures.
                while(inFlight<threads && remaining.hasNext()) {
                    if(isCancelled() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                    var task=remaining.next();
                    completion.submit(() -> {if(!isCancelled())task.run(); return null;});
                    inFlight++;
                }
                var future=completion.poll(100,TimeUnit.MILLISECONDS);
                if(future!=null) {future.get(); inFlight--; progress.accept((double)++completed/tasks.size());}
            }
            success=true;
        } catch(InterruptedException e) {
            interrupted=true; stopped=true; throw new CancellationException("Inference interrupted");
        } catch(ExecutionException e) {
            throw new IllegalStateException("Inference task failed",e.getCause());
        } finally {
            // Do not interrupt shared ImageServer reads: FileChannel interruption closes
            // the channel and can affect the viewer. Queued tasks skip via stopped.
            if(!success) stopped=true;
            pool.shutdown();
            // JNI inference may ignore interrupts. Keep the model alive until it actually exits.
            while(!pool.isTerminated()) {
                try {pool.awaitTermination(100,TimeUnit.MILLISECONDS);}
                catch(InterruptedException e) {interrupted=true; stopped=true; pool.shutdown();}
            }
            try {
                for(var task:tasks) if(task instanceof PathTask pathTask)
                    pathTask.taskComplete(!success || isCancelled());
            } finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
}
