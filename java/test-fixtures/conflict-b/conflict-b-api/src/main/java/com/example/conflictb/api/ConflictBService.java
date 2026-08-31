package com.example.conflictb.api;

import dev.itara.api.ComponentInterface;
import dev.itara.api.ContractMethod;

@ComponentInterface(id = "conflict-b")
public interface ConflictBService {

    @ContractMethod
    String describe();

    @ContractMethod
    ClassLoader captureClassLoader();
}
