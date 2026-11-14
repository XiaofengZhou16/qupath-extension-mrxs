package io.github.xiaofengzhou.qupath.mrxs;

import qupath.lib.io.GsonTools;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Full output hashes bind a completed attempt to its frozen queue item. Not source authenticity. */
final class IfBatchOutput {
    private static final String RECEIPT="batch-output.json";
    record Receipt(int schema, IfBatchQueue.Item item, Map<String,String> hashes) {}
    static void seal(Path output, IfBatchQueue.Item item, BooleanSupplier cancelled) throws IOException {
        checkHistory(output,cancelled);
        var receipt=new Receipt(1,item,hashes(output,cancelled));
        Files.writeString(output.resolve(RECEIPT),GsonTools.getInstance(true).toJson(receipt),StandardOpenOption.CREATE_NEW);
    }
    static void verify(Path output, IfBatchQueue.Item item, BooleanSupplier cancelled) throws IOException {
        var path=output.resolve(RECEIPT);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>16*1024*1024)
            throw new IOException("Missing or oversized batch output receipt");
        try {
            var receipt=GsonTools.getInstance().fromJson(Files.readString(path),Receipt.class);
            if(receipt==null||receipt.schema!=1||!item.equals(receipt.item)||receipt.hashes==null
                    ||!receipt.hashes.equals(hashes(output,cancelled)))throw new IOException("Batch output identity or content changed");
        } catch(com.google.gson.JsonParseException e){throw new IOException("Invalid batch receipt",e);}
        checkHistory(output,cancelled);
    }
    private static void checkHistory(Path output,BooleanSupplier cancelled)throws IOException {
        var catalog=IfAnalysisHistory.scan(output,cancelled);
        if(catalog.entries().size()!=1||!catalog.rejected().isEmpty())throw new IOException("Analysis is not structurally complete");
    }
    static String hash(Path file,BooleanSupplier cancelled)throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)) {
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1){check(cancelled);digest.update(buffer,0,n);}
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    static String hashText(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static Map<String,String> hashes(Path root,BooleanSupplier cancelled)throws IOException {
        var result=new TreeMap<String,String>();
        try(var paths=Files.walk(root)) {
            var iterator=paths.iterator();int count=0;
            while(iterator.hasNext()) {
                check(cancelled);var path=iterator.next();
                if(++count>100000)throw new IOException("Batch output exceeds file limit");
                if(Files.isSymbolicLink(path))throw new IOException("Linked batch outputs are unsupported");
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsupported output entry");
                String relative=root.relativize(path).toString();
                if(!relative.equals(RECEIPT))result.put(relative,hash(path,cancelled));
            }
        }
        return result;
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
}
