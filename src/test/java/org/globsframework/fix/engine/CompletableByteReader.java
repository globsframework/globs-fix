package org.globsframework.fix.engine;

import org.globsframework.fix.deserializer.ByteReader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class CompletableByteReader implements ByteReader {
    byte[] current;
    int readUntil;
    List<CompletableFuture<byte[]>> pending = new ArrayList<>();

    public CompletableFuture<byte[]> getNext() {
        synchronized (this) {
            final CompletableFuture<byte[]> e = new CompletableFuture<>();
            pending.add(e);
            this.notify();
            return e;
        }
    }

    @Override
    public int read(byte[] buf, int offset, int len) {
        if (current == null) {
            try {
                synchronized (this) {
                    if (pending.isEmpty()) {
                        this.wait(10000);
                    }
                    final CompletableFuture<byte[]> first = pending.removeFirst(); // will throw on timeout
                    current = first.get(10, TimeUnit.SECONDS);
                    readUntil = 0;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
        final int available = current.length - readUntil;
        if (len >= available) {
            System.arraycopy(current, readUntil, buf, offset, available);
            current = null;
            readUntil = 0;
            return available;
        } else {
            System.arraycopy(current, readUntil, buf, offset, len);
            readUntil += len;
            return len;
        }
    }
}
