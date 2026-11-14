package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.io.GsonTools;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Sequential durable coordinator. Callers must freeze source/ROI/settings identities and validate outputs. */
final class IfBatchQueue {
    record Item(String id, String fingerprint) {
        Item {
            if (id == null || !id.matches("[A-Za-z0-9_-]+") || fingerprint == null || fingerprint.isBlank())
                throw new IllegalArgumentException("Task requires a safe unique ID and immutable input fingerprint");
        }
    }
    record Attempt(String task, String directory, String status, String error) {}
    record Journal(int schema, List<Item> items, List<Attempt> attempts) {}
    interface Work {
        /** Must return only after native workers stop. Output must be written to the new attempt directory. */
        void execute(Item item, Path output, BooleanSupplier cancelled) throws Exception;
        /** Must check completed output integrity and its association with the frozen input. */
        void verify(Item item, Path output) throws Exception;
    }
    private IfBatchQueue() {}

    static Journal run(Path root, List<Item> requested, Work work, BooleanSupplier cancelled) throws IOException {
        var items = List.copyOf(requested);
        if (items.isEmpty() || items.stream().map(Item::id).distinct().count() != items.size())
            throw new IllegalArgumentException("Empty or duplicate task list");
        Files.createDirectories(root);
        try (var channel = FileChannel.open(root.resolve("queue.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("This batch is already running");
            Path journalPath = root.resolve("queue.json");
            Journal old;
            try {
                old = Files.exists(journalPath)
                        ? GsonTools.getInstance().fromJson(Files.readString(journalPath), Journal.class)
                        : new Journal(1, items, List.of());
                if (old == null || old.schema != 1 || !items.equals(old.items) || old.attempts == null)
                    throw new IOException("Batch input list changed or journal unsupported; use a new batch directory");
            } catch (RuntimeException e) { throw new IOException("Invalid batch journal", e); }
            var attempts = new ArrayList<Attempt>();
            for (var a : old.attempts) {
                if (a == null || items.stream().noneMatch(i -> i.id.equals(a.task))
                        || a.directory == null || !a.directory.matches("attempt-[a-f0-9-]{36}")
                        || !Set.of("running", "interrupted", "complete", "failed", "cancelled", "invalid").contains(a.status))
                    throw new IOException("Invalid attempt record");
                attempts.add("running".equals(a.status)
                        ? new Attempt(a.task, a.directory, "interrupted", "Previous process ended before completion was recorded") : a);
            }
            save(journalPath, new Journal(1, items, attempts));
            for (var item : items) {
                if (cancelled.getAsBoolean()) break;
                boolean complete = false;
                for (int n = 0; n < attempts.size(); n++) {
                    var a = attempts.get(n);
                    if (!a.task.equals(item.id) || !a.status.equals("complete")) continue;
                    try { work.verify(item, root.resolve(a.directory)); complete = true; }
                    catch (Exception e) {
                        if (e instanceof CancellationException || cancelled.getAsBoolean())
                            return new Journal(1, items, List.copyOf(attempts));
                        attempts.set(n, new Attempt(a.task, a.directory, "invalid", e.toString()));
                        save(journalPath, new Journal(1, items, attempts));
                    }
                }
                if (complete || cancelled.getAsBoolean()) continue;
                String directory = "attempt-" + UUID.randomUUID();
                int index = attempts.size();
                attempts.add(new Attempt(item.id, directory, "running", ""));
                save(journalPath, new Journal(1, items, attempts));
                String status = "complete", error = "";
                try {
                    work.execute(item, root.resolve(directory), cancelled);
                    if (cancelled.getAsBoolean()) throw new CancellationException();
                    work.verify(item, root.resolve(directory));
                } catch (Exception e) {
                    status = e instanceof CancellationException || cancelled.getAsBoolean() ? "cancelled" : "failed";
                    error = e.toString();
                }
                attempts.set(index, new Attempt(item.id, directory, status, error));
                save(journalPath, new Journal(1, items, attempts));
                if (status.equals("cancelled")) break;
            }
            return new Journal(1, items, List.copyOf(attempts));
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new IOException("This batch is already running", e);
        }
    }

    private static void save(Path path, Journal journal) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "queue-", ".tmp");
        try {
            Files.writeString(temp, GsonTools.getInstance(true).toJson(journal));
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            // Fail rather than use a non-atomic replacement that could destroy the recovery record.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
