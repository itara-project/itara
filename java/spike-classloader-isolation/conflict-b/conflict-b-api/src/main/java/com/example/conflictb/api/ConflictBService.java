package com.example.conflictb.api;

import io.itara.api.ComponentInterface;
import io.itara.api.ContractMethod;

@ComponentInterface(id = "conflict-b")
public interface ConflictBService {

    @ContractMethod
    String describe();
}
