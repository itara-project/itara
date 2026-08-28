package com.example.conflicta.api;

import dev.itara.api.ComponentInterface;
import dev.itara.api.ContractMethod;

@ComponentInterface(id = "conflict-a")
public interface ConflictAService {

    @ContractMethod
    String describe();

    @ContractMethod
    ClassLoader captureClassLoader();
}
