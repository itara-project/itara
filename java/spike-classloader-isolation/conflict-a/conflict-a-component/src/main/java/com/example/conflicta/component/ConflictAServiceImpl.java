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
        System.out.println("[SPIKE][FATJAR] ConflictBService.class loaded by: "
                + ConflictBService.class.getClassLoader());
        ClassLoader expectedCl = Thread.currentThread().getContextClassLoader();
        String result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Thread t = Thread.currentThread();
            System.out.println("[SPIKE][FJP] component=conflict-a"
                    + " poolThread=" + t.getName() + "(" + t.getId() + ")"
                    + " observedTccl=" + t.getContextClassLoader()
                    + " expectedTccl=" + expectedCl);
            return formatter.format("conflict-a");
        }).join();
        String fromB = conflictB.describe();
        return result + " -> " + fromB;
    }
}
