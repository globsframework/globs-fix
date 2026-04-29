package org.globsframework.fix.engine;

import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.HeaderType;
import org.globsframework.fix.deserializer.FixMessageValue;
import org.globsframework.fix.dictionary.admin.LogonType;
import org.globsframework.fix.serializer.FixWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class TestUserSession implements UserSession, AppMessageReceiver {
    CompletableFuture<Boolean> connected = new CompletableFuture<>();
    List<FixMessageValue> received = new ArrayList<>();
    private FixWriter appWriter;
    private boolean synchroState;

    @Override
    public void logonFail() {
    }

    @Override
    public Glob getHeader() {
        return HeaderType.create("BNP", "AF");
    }

    @Override
    public Glob getLogon() {
        return LogonType.create(10);
    }

    @Override
    public AppMessageReceiver connected(FixMessageValue logon, FixWriter appWriter) {
        this.appWriter = appWriter;
        connected.complete(true);
        return this;
    }

    @Override
    public CompletableFuture<Void> logout() {
        return new CompletableFuture<>();
    }

    @Override
    public void messages(FixMessageValue fixMessageValue) {
        synchronized (this) {
            received.add(fixMessageValue);
            notify();
        }
    }

    public boolean checkEmpty() throws InterruptedException {
        synchronized (this) {
            if (received.isEmpty()) {
                this.wait(100);
            }
            return received.isEmpty();
        }
    }

    public FixMessageValue getMessage() throws InterruptedException {
        synchronized (this) {
            if (received.isEmpty()) {
                this.wait(1000);
            }
            return received.removeFirst();
        }
    }

    public MutableGlob publish(MutableGlob msg) {
        final MutableGlob header = getHeader().duplicate();
        appWriter.write(header, msg, null, false);
        return header;
    }
}
