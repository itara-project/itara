package com.example.conflicta.component;

import com.example.conflicta.api.ConflictAService;
import com.example.conflictb.api.ConflictBService;
import dev.itara.api.ItaraActivator;
import dev.itara.runtime.ComponentLookup;

public class ConflictAActivator implements ItaraActivator {

    @Override
    public ConflictAService activate() {
        ConflictBService conflictB = ComponentLookup.get("conflict-b", ConflictBService.class);
        return new ConflictAServiceImpl(conflictB);
    }
}
