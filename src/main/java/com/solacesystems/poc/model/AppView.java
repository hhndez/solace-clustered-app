package com.solacesystems.poc.model;

public interface AppView<StateType> {
    StateType getApplicationState();

    StateType getLastApplicationState();

    HAState getHAStatus();

    SeqState getSequenceStatus();
}
