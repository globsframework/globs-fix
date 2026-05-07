package org.globsframework.fix.engine;

import java.util.concurrent.CompletableFuture;

public interface FixLogout {
    void registerOnClosed(Runnable runnable);

    CompletableFuture<Boolean> close();
}
