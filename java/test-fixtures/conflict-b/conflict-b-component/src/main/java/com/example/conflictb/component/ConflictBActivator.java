package com.example.conflictb.component;

import com.example.conflictb.api.ConflictBService;
import io.itara.api.ItaraActivator;

public class ConflictBActivator implements ItaraActivator {

    @Override
    public ConflictBService activate() {
        return new ConflictBServiceImpl();
    }
}
