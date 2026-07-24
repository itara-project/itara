package com.example.conflicta.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

@ComponentInterface(id = "conflict-a")
public interface ConflictAService {

    @ContractMethod
    String describe();

    @ContractMethod
    ClassLoader captureClassLoader();
}
