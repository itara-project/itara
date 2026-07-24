package com.example.conflictb.component;

import com.example.conflictb.api.ConflictBService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

public class ConflictBActivator implements ItaraActivator {

    @Override
    public ConflictBService activate(ItaraRegistry registry) {
        return new ConflictBServiceImpl();
    }
}
