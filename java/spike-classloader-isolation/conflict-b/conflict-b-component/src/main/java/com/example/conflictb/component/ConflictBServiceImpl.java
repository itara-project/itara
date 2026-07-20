package com.example.conflictb.component;

import com.example.conflictb.api.ConflictBService;
import com.example.shared.Formatter;

public class ConflictBServiceImpl implements ConflictBService {

    private final Formatter formatter = new Formatter();

    @Override
    public String describe() {
      //  return formatter.format("conflict-b", true);
        ClassLoader expectedCl = Thread.currentThread().getContextClassLoader();
        String result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Thread t = Thread.currentThread();
            System.out.println("[SPIKE][FJP] component=conflict-b"
                    + " poolThread=" + t.getName() + "(" + t.getId() + ")"
                    + " observedTccl=" + t.getContextClassLoader()
                    + " expectedTccl=" + expectedCl);
            return formatter.format("conflict-b", true);
        }).join();
        return result;
    }
}
