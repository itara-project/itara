package com.example.conflictb.component;

import com.example.conflictb.api.ConflictBService;
import com.example.shared.Formatter;

public class ConflictBServiceImpl implements ConflictBService {

    private final Formatter formatter = new Formatter();

    @Override
    public String describe() {
        String result = java.util.concurrent.CompletableFuture.supplyAsync(() -> formatter.format("conflict-b", true)).join();
        return result;
    }

    @Override
    public ClassLoader captureClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }
}
