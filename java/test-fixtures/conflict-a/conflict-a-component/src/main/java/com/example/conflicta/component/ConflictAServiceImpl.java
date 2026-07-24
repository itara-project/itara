package com.example.conflicta.component;

import com.example.conflicta.api.ConflictAService;
import com.example.conflictb.api.ConflictBService;
import com.example.shared.Formatter;

public class ConflictAServiceImpl implements ConflictAService {

    private final ConflictBService conflictB;
    private final Formatter formatter = new Formatter();

    public ConflictAServiceImpl(ConflictBService conflictB) {
        this.conflictB = conflictB;
    }

    @Override
    public String describe() {
        String result = java.util.concurrent.CompletableFuture.supplyAsync(() -> formatter.format("conflict-a")).join();
        String fromB = conflictB.describe();
        return result + " -> " + fromB;
    }

    @Override
    public ClassLoader captureClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }
}
