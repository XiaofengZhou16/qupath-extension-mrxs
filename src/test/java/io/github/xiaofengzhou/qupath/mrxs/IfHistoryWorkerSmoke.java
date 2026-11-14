package io.github.xiaofengzhou.qupath.mrxs;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic JavaFX cancellation lifecycle test; no windows or screenshots. */
public class IfHistoryWorkerSmoke {
    private static final AtomicReference<Task<?>> active=new AtomicReference<>();
    private static final SimpleBooleanProperty busy=new SimpleBooleanProperty();
    private static void fx(Runnable action) throws Exception {
        var future=new FutureTask<Void>(action,null);Platform.runLater(future);future.get(10,TimeUnit.SECONDS);
    }
    public static void main(String[] args) throws Exception {
        var release=new CountDownLatch(1);
        try {
            var ready=new CountDownLatch(1);Platform.startup(ready::countDown);
            if(!ready.await(10,TimeUnit.SECONDS))throw new AssertionError("FX startup timeout");
            Platform.setImplicitExit(false);
            var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
            var task=new Task<Void>() {
                @Override protected Void call() throws Exception {
                    entered.countDown();
                    if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Release timeout");
                    return null;
                }
            };
            fx(()->{task.setOnCancelled(e->cancelled.countDown());IfHistoryPanel.startWorker(task,busy,active,"history-test-cancel");});
            if(!entered.await(5,TimeUnit.SECONDS))throw new AssertionError("Worker not entered");
            fx(()->task.cancel(false));
            if(!cancelled.await(5,TimeUnit.SECONDS))throw new AssertionError("Cancellation event missing");
            fx(()->{
                if(!busy.get()||active.get()!=task)throw new AssertionError("Early unlock on cancelled event");
                try{IfHistoryPanel.startWorker(new Task<Void>(){protected Void call(){return null;}},busy,active,"overlap");
                    throw new AssertionError("Overlapping worker accepted");
                }catch(IllegalStateException expected){}
            });
            release.countDown();awaitIdle();
            // Retry after cancellation must work. Failure also releases the slot.
            var succeeded=new CountDownLatch(1);
            fx(()->{var retry=new Task<Void>(){protected Void call(){return null;}};
                retry.setOnSucceeded(e->succeeded.countDown());IfHistoryPanel.startWorker(retry,busy,active,"history-test-retry");});
            if(!succeeded.await(5,TimeUnit.SECONDS))throw new AssertionError("Retry failed");awaitIdle();
            var failed=new CountDownLatch(1);
            fx(()->{var error=new Task<Void>(){protected Void call(){throw new IllegalArgumentException("expected");}};
                error.setOnFailed(e->failed.countDown());IfHistoryPanel.startWorker(error,busy,active,"history-test-error");});
            if(!failed.await(5,TimeUnit.SECONDS))throw new AssertionError("Failure event missing");awaitIdle();
            System.out.println("IF_HISTORY_WORKER_OK cancellationDoesNotUnlock=true overlapRejected=true retry=true failureCleanup=true screenshots=false");
        }finally{release.countDown();Platform.exit();}
    }
    private static void awaitIdle() throws Exception {
        var idle=new CountDownLatch(1);
        fx(()->{if(!busy.get())idle.countDown();else busy.addListener((o,a,b)->{if(!b)idle.countDown();});});
        if(!idle.await(5,TimeUnit.SECONDS))throw new AssertionError("Worker slot not released");
        fx(()->{if(active.get()!=null||busy.get())throw new AssertionError("Stale worker slot");});
    }
}
