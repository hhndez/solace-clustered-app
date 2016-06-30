package com.solacesystems.poc.conn;

import com.solacesystems.poc.model.HAState;
import com.solacesystems.poc.model.SeqState;
import com.solacesystems.solclientj.core.handle.Handle;

public class Helper {
    public static void logHAStateChange(HAState from, HAState to) {
        System.out.println("HA Change: " + from + " => " + to);
    }

    public static void logSeqStateChange(SeqState from, SeqState to) {
        System.out.println("Seq Change: " + from + " => " + to);
    }

    public static void destroyHandle(Handle handle) {
        try {
            if (handle != null && handle.isBound()) {
                handle.destroy();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
