package com.example.conflicta.component;

import com.example.conflicta.api.ConflictAService;
import com.example.conflictb.api.ConflictBService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

public class ConflictAActivator implements ItaraActivator {

    @Override
    public ConflictAService activate(ItaraRegistry registry) {
        ConflictBService conflictB = registry.get("conflict-b", ConflictBService.class);
        return new ConflictAServiceImpl(conflictB);
    }
}
