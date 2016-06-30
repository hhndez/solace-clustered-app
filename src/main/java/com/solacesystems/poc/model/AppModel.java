package com.solacesystems.poc.model;

import com.solacesystems.poc.conn.Helper;

public class AppModel implements AppView<ClientOrder> {
    public AppModel(String appid, int instance) {
        this._appid = appid;
        this._instance = instance;
    }

    public ClientOrder getApplicationState() {
        return _appState;
    }
    public void setApplicationState(ClientOrder appState) {
        this._appState = appState;
    }

    public ClientOrder getLastApplicationState() {
        return _lastAppState;
    }
    public void setLastApplicationState(ClientOrder lastAppState) {
        this._lastAppState = lastAppState;
    }

    public HAState getHAStatus() {
        return _HAStatus;
    }
    public void setHAStatus(HAState haStatus) {
        Helper.logHAStateChange(_HAStatus, haStatus);
        this._HAStatus = haStatus;
    }

    public SeqState getSequenceStatus() {
        return _sequenceStatus;
    }
    public void setSequenceStatus(SeqState sequenceStatus) {
        Helper.logSeqStateChange(_sequenceStatus, sequenceStatus);
        this._sequenceStatus = sequenceStatus;
    }

    public String getAppQueueName () { return _appid + "_inst" + _instance; }
    public String getLVQName () { return _appid + "_lvq"; }

    @Override
    public String toString() {
        return _appid + ":"      + _instance +
                " SID = ["       + (_appState==null ? -1 : _appState.getSequenceId()) +
                "] last SID = [" + (_lastAppState==null ? -1 : _lastAppState.getSequenceId()) +
                "] HA = ["       + _HAStatus +
                "] SEQ = ["      + _sequenceStatus +
                "] OBJ = "       + (_appState==null ? "(null)" : _appState.toStringBrief());
    }

    private String _appid;
    private int _instance;

    private ClientOrder _appState;
    private ClientOrder _lastAppState;
    private HAState _HAStatus;
    private SeqState _sequenceStatus;
}
