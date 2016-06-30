package com.solacesystems.poc;
import com.solacesystems.poc.model.*;
import com.solacesystems.poc.conn.*;
import com.solacesystems.solclientj.core.SolEnum;
import com.solacesystems.solclientj.core.SolclientException;
import com.solacesystems.solclientj.core.event.*;
import com.solacesystems.solclientj.core.handle.*;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class App implements SessionEventCallback
{

    public static void main(String[] args){
        if (args.length < 6)
        {
            System.out.println("USAGE: <IP> <APP-ID> <APP-INST-#> <SOL-VPN> <SOL-USER> <SOL-PASS>\n\n\n");
            return;
        }
        String host  = args[0];
        String appid = args[1];
        int instance = Integer.parseInt(args[2]);
        String vpn   = args[3];
        String user  = args[4];
        String pass  = args[5];

        App app = new App(appid, instance);

        // create session and connect to a Solace message router
        app.initAndConnect(host, vpn, user, pass);
        // bind to all our queues: application queue, last-value-queue
        app.bindOnConnect();
        // main thread sleeps while background thread receives messages
        app.run();

        app.destroy();
    }

    private App(String appid, int instance)
    {
        _model = new AppModel(appid, instance);
        _connector = new SolaceConnector();
        _outTopic = appid + "/app2/new";
    }

    private void initAndConnect(String ip, String vpn, String user, String pass) throws SolclientException
    {
        initState();
        _connector.ConnectSession(ip, vpn, user, pass, this);
    }

    private void bindOnConnect() {
        // Wait until the Solace Session is UP before binding to queues
        boolean connected = false;
        while(!connected) {
            if (_model.getSequenceStatus() == SeqState.CONNECTED) {
                bindQueues();
                connected = true;
            }
        }
    }

    private void run()
    {
        boolean running = true;
        while (running)
        {
            System.out.println("STATE: " + _model);
            try {
                Thread.sleep(1000);
            } catch(InterruptedException e) {
                e.printStackTrace();
                running = false;
            }
        }
    }

    private void bindQueues()
    {
        // The order of instantiation matters; lvqflow is used for active-flow-ind
        // which triggers recovering state via browser, then starts appflow
        // after recovery completes
        _lvqBrowser = _connector.BrowseQueue(_model.getLVQName(),
                new MessageCallback() {
                    public void onMessage(Handle handle) {
                        MessageSupport ms = (MessageSupport) handle;
                        onLVQMessage(ms.getRxMessage());
                    }
                },
                new FlowEventCallback() {
                    public void onEvent(FlowHandle flowHandle) {
                        FlowEvent event = flowHandle.getFlowEvent();
                        System.out.println("LVQ BROWSER FLOW EVENT: " + event);
                    }
                });
        _appflow = _connector.BindQueue(_model.getAppQueueName(),
                new MessageCallback() {
                    public void onMessage(Handle handle) {
                        MessageSupport ms = (MessageSupport) handle;
                        onAppMessage(ms.getRxMessage());
                    }
                },
                new FlowEventCallback() {
                    public void onEvent(FlowHandle flowHandle) {
                        FlowEvent event = flowHandle.getFlowEvent();
                        onAppFlowEvent(event);
                    }
                });
        _lvqflow = _connector.BindQueue(_model.getLVQName(),
                new MessageCallback() {
                    public void onMessage(Handle handle) {
                        System.out.println("!!! ERROR !!! ONLY FOR ACTIVE-FLOW-INDICATOR; DO NOT CONSUME MESSAGES HERE !!!");
                    }
                },
                new FlowEventCallback() {
                    public void onEvent(FlowHandle flowHandle) {
                        onLVQFlowEvent(flowHandle);
                    }
                });
        _model.setSequenceStatus(SeqState.BOUND);
    }

    ////////////////////////////////////////////////////////////////////////
    //////////            Event Handlers                           /////////
    ////////////////////////////////////////////////////////////////////////
    public void onEvent(SessionHandle sessionHandle) {
        SessionEvent event = sessionHandle.getSessionEvent();
        System.out.println("Session event: " + event);
        switch(event.getSessionEventCode()) {
            case SolEnum.SessionEventCode.UP_NOTICE:
                _model.setHAStatus(HAState.CONNECTED);
                _model.setSequenceStatus(SeqState.CONNECTED);
                break;
            case SolEnum.SessionEventCode.DOWN_ERROR:
                break;
            case SolEnum.SessionEventCode.RECONNECTING_NOTICE:
                break;
            case SolEnum.SessionEventCode.RECONNECTED_NOTICE:
                break;
            default:
                break;
        }
    }

    private void onAppFlowEvent(FlowEvent event) {
        System.out.println("App flow event: " + event);
    }
    /// Invoked on the appflow when message arrives
    private void onAppMessage(MessageHandle msg) {
        _appmsgbuf.clear();
        msg.getBinaryAttachment(_appmsgbuf);
        newOrderEvent(Serializer.DeserializeClientOrder(_appmsgbuf));
    }

    /// Invoked on the lvqflow when flow event occurs
    private void onLVQFlowEvent(FlowHandle flowHandle) {
        FlowEvent event = flowHandle.getFlowEvent();
        System.out.println("LVQ flow event: " + event);
        switch (event.getFlowEventEnum())
        {
            case SolEnum.FlowEventCode.UP_NOTICE:
                recoverLastState();
                break;
            case SolEnum.FlowEventCode.ACTIVE:
                becomeActive();
                break;
            case SolEnum.FlowEventCode.INACTIVE:
                becomeBackup();
                break;
            default:
                break;
        }
    }
    // Invoked on the LVQBrowser flowhandle
    private void onLVQMessage(MessageHandle msg) {
        processLastOutputMsg(msg);
    }
    ////////////////////////////////////////////////////////////////////////
    //////////            /Event Handlers                          /////////
    ////////////////////////////////////////////////////////////////////////


    /// Invoked on the appflow when application message arrives
    private void newOrderEvent(ClientOrder order) {
        ClientOrder appState = _model.getApplicationState();
        if (appState == null || order.getSequenceId() >= appState.getSequenceId()) {
            if (_model.getSequenceStatus() != SeqState.UPTODATE)
                _model.setSequenceStatus(SeqState.UPTODATE);
            _model.setApplicationState(order);
        }
        else {
            System.out.println("\tIGNORED MESSAGE {"+order.getSequenceId()
                    +"} because it is behind recovered state {"+appState.getSequenceId()+"}");
        }
        _model.setLastApplicationState(order);

        // If we're the active member of the cluster, we are responsible
        // for all output but don't publish until we have new input data
        if (_model.getHAStatus() == HAState.ACTIVE && _model.getSequenceStatus() == SeqState.UPTODATE)
        {
            _connector.SendOutput(Serializer.SerializeClientOrder(_sermsgbuf, _model.getApplicationState()), _outTopic);
        }
    }

    //////////////////////////////////////////////////
    //// HA & Sequence State transition functions ////
    //////////////////////////////////////////////////
    private void initState() {
        _model.setApplicationState(null);
        _model.setLastApplicationState(null);
        _model.setHAStatus(HAState.DISCONNECTED);
        _model.setSequenceStatus(SeqState.INIT);
    }

    // Invoked on the lvqflow when flow UP event occurs
    // or when flow changes from INACTIVE to ACTIVE
    // This function tries to browse the message on the LVQ
    // to recover the last output state from this application
    private void recoverLastState() {
        if (_model.getSequenceStatus() != SeqState.RECOVERING)
        {
            System.out.println("Recovering last state from the LVQ, current sequence state is " + _model.getSequenceStatus());
            _model.setSequenceStatus(SeqState.RECOVERING);
            _task = new TimerTask() {
                @Override
                public void run() { noLastStateMessage(); }
            };
            _timer.schedule(_task, 250);
            if (_lvqBrowser == null) {
                System.out.println("Still constructing lvq browser handle");
            }
            _lvqBrowser.start(); // if a msg arrives it is passed to processLastOutputMsg (below)
        }
        else
        {
            System.out.println("In the midst of recovering from last state, skipping the LVQ check.");
        }
        _model.setHAStatus(HAState.BACKUP);
    }

    // Invoked on the LVQBrowser flowhandle when message arrives
    private void processLastOutputMsg(MessageHandle lvqmsg) {
        _task.cancel();
        _lvqBrowser.stop();
        // Deserialize the lvq-message
        _lvqmsgbuf.clear();
        lvqmsg.getBinaryAttachment(_lvqmsgbuf);
        ClientOrder lvqOrder = Serializer.DeserializeClientOrder(_lvqmsgbuf);
        // Compare the lvq-message sequenceId to our current-state sequenceId
        ClientOrder appOrder = _model.getApplicationState();
        String lvqstr = (lvqOrder==null) ? "(null)" : lvqOrder.toString();
        String appstr = (appOrder==null) ? "(null)" : appOrder.toString();
        System.out.println("LAST OUTPUT MSG: {"+lvqstr+"}; LAST INPUT MSG: {"+appstr+"}");
        if (lvqOrder != null && (appOrder == null ||  appOrder.getSequenceId() < lvqOrder.getSequenceId()))
        {
            _model.setApplicationState(lvqOrder);
            _model.setSequenceStatus(SeqState.RECOVERING_FROM_FLOW);
        }
        else
        {
            _model.setSequenceStatus(SeqState.UPTODATE);
        }
        _appflow.start();
    }

    /// After  the lvqflow flow UP event occurs, the browser flow is started and a timer
    /// set in case there are no LVQ messages to browse. In this case, we timed out with no
    /// messages so we give up on the LVQ and start the appflow messages from scratch.
    private void noLastStateMessage() {
        _lvqBrowser.stop();
        _model.setSequenceStatus(SeqState.RECOVERING_FROM_FLOW);
        _appflow.start();
    }

    // Invoked on the lvqflow when flow ACTIVE event occurs
    private void becomeActive()
    {
        recoverLastState();
        _model.setHAStatus(HAState.ACTIVE);
    }

    // Invoked on the lvqflow when flow INACTIVE event occurs
    private void becomeBackup()
    {
        _model.setHAStatus(HAState.BACKUP);
    }

    private void destroy() {
        if (_appflow != null) {
            _appflow.stop();
            Helper.destroyHandle(_appflow);
        }
        if (_lvqBrowser != null) {
            _lvqBrowser.stop();
            Helper.destroyHandle(_lvqBrowser);
        }
        if (_lvqflow != null) {
            _lvqflow.stop();
            Helper.destroyHandle(_lvqflow);
        }
        _connector.destroy();
    }


    private final AppModel _model;
    private final SolaceConnector _connector;
    private final String _outTopic;
    private FlowHandle _lvqBrowser;
    private FlowHandle _lvqflow;
    private FlowHandle _appflow;
    private final ByteBuffer _lvqmsgbuf = ByteBuffer.allocate(ClientOrder.SERIALIZED_SIZE);
    private final ByteBuffer _appmsgbuf = ByteBuffer.allocate(ClientOrder.SERIALIZED_SIZE);
    private final ByteBuffer _sermsgbuf = ByteBuffer.allocate(ClientOrder.SERIALIZED_SIZE);
    private final Timer _timer = new Timer();
    private TimerTask _task;

}
