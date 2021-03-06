/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zwave.internal.protocol;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.openhab.binding.zwave.internal.protocol.SerialMessage.SerialMessageClass;
import org.openhab.binding.zwave.internal.protocol.SerialMessage.SerialMessagePriority;
import org.openhab.binding.zwave.internal.protocol.SerialMessage.SerialMessageType;
import org.openhab.binding.zwave.internal.protocol.NodeStage;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveCommandClass.CommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveWakeUpCommandClass;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveInitializationCompletedEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveNodeStatusEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveTransactionCompletedEvent;

import org.openhab.binding.zwave.internal.protocol.serialmessage.AddNodeMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.AssignReturnRouteMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.AssignSucReturnRouteMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.DeleteReturnRouteMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.IdentifyNodeMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.RequestNodeNeighborUpdateMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.RemoveFailedNodeMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.RequestNodeInfoMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.GetRoutingInfoMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.SendDataMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.SerialApiSoftResetMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.ZWaveCommandProcessor;
import org.openhab.binding.zwave.internal.protocol.serialmessage.GetVersionMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.MemoryGetIdMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.SerialApiGetCapabilitiesMessageClass;
import org.openhab.binding.zwave.internal.protocol.serialmessage.SerialApiGetInitDataMessageClass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZWave controller class. Implements communication with the Z-Wave
 * controller stick using serial messages.
 * @author Victor Belov
 * @author Brian Crosby
 * @author Chris Jackson
 * @since 1.3.0
 */
public class ZWaveController {
	
	private static final Logger logger = LoggerFactory.getLogger(ZWaveController.class);
	
	private static final int QUERY_STAGE_TIMEOUT = 120000;
	private static final int ZWAVE_RESPONSE_TIMEOUT = 5000;		// 5000 ms ZWAVE_RESPONSE TIMEOUT
	private static final int ZWAVE_RECEIVE_TIMEOUT = 1000;		// 1000 ms ZWAVE_RECEIVE_TIMEOUT
	private static final int INITIAL_QUEUE_SIZE = 128; 
	private static final long WATCHDOG_TIMER_PERIOD = 10000;	// 10 seconds watchdog timer

	private static final int TRANSMIT_OPTION_ACK = 0x01;
	private static final int TRANSMIT_OPTION_AUTO_ROUTE = 0x04;
	private static final int TRANSMIT_OPTION_EXPLORE = 0x20;
	
	private final Map<Integer, ZWaveNode> zwaveNodes = new HashMap<Integer, ZWaveNode>();
	private final ArrayList<ZWaveEventListener> zwaveEventListeners = new ArrayList<ZWaveEventListener>();
	private final PriorityBlockingQueue<SerialMessage> sendQueue = new PriorityBlockingQueue<SerialMessage>(INITIAL_QUEUE_SIZE, new SerialMessage.SerialMessageComparator(this));
	private ZWaveSendThread sendThread;
	private ZWaveReceiveThread receiveThread;
	
	private final Semaphore transactionCompleted = new Semaphore(1);
	private volatile SerialMessage lastSentMessage = null;
	private SerialPort serialPort;
	private Timer watchdog;
	
	private String zWaveVersion = "Unknown";
	private String serialAPIVersion = "Unknown";
	private int homeId = 0;
	private int ownNodeId = 0;
	private int manufactureId = 0;
	private int deviceType = 0; 
	private int deviceId = 0;
	private int ZWaveLibraryType = 0;
	private int sentDataPointer = 1;
	
	private int SOFCount = 0;
	private int CANCount = 0;
	private int NAKCount = 0;
	private int ACKCount = 0;
	private int OOFCount = 0;
	private AtomicInteger timeOutCount = new AtomicInteger(0);
	
	private boolean initializationComplete = false;
	
	private boolean isConnected;

	// Constructors
	
	/**
	 * Constructor. Creates a new instance of the Z-Wave controller class.
	 * @param serialPortName the serial port name to use for 
	 * communication with the Z-Wave controller stick.
	 * @throws SerialInterfaceException when a connection error occurs.
	 */
	public ZWaveController(final String serialPortName) throws SerialInterfaceException {
			logger.info("Starting Z-Wave controller");
			connect(serialPortName);
			this.watchdog = new Timer(true);
			this.watchdog.schedule(
					new WatchDogTimerTask(serialPortName), 
					WATCHDOG_TIMER_PERIOD, WATCHDOG_TIMER_PERIOD);
	}

	// Incoming message handlers
	
	/**
	 * Handles incoming Serial Messages. Serial messages can either be messages
	 * that are a response to our own requests, or the stick asking us information.
	 * @param incomingMessage the incoming message to process.
	 */
	private void handleIncomingMessage(SerialMessage incomingMessage) {
		logger.trace("Incoming message to process");
		logger.debug(incomingMessage.toString());
		
		switch (incomingMessage.getMessageType()) {
			case Request:
				handleIncomingRequestMessage(incomingMessage);
				break;
			case Response:
				handleIncomingResponseMessage(incomingMessage);
				break;
			default:
				logger.warn("Unsupported incomingMessageType: 0x%02X", incomingMessage.getMessageType());
		}
	}

	/**
	 * Handles an incoming request message.
	 * An incoming request message is a message initiated by a node or the controller.
	 * @param incomingMessage the incoming message to process.
	 */
	private void handleIncomingRequestMessage(SerialMessage incomingMessage) {
		logger.trace("Message type = REQUEST");

		ZWaveCommandProcessor processor = ZWaveCommandProcessor.getMessageDispatcher(incomingMessage.getMessageClass());
		if(processor != null) {
			processor.handleRequest(this, lastSentMessage, incomingMessage);

			if(processor.isTransactionComplete()) {
				notifyEventListeners(new ZWaveTransactionCompletedEvent(this.lastSentMessage));
				transactionCompleted.release();
				logger.trace("Released. Transaction completed permit count -> {}", transactionCompleted.availablePermits());
			}
		}
		else {
			logger.warn(String.format("TODO: Implement processing of Request Message = %s (0x%02X)",
					incomingMessage.getMessageClass().getLabel(),
					incomingMessage.getMessageClass().getKey()));
		}
	}

	/**
	 * Handles a failed SendData request. This can either be because of the stick actively reporting it
	 * or because of a time-out of the transaction in the send thread.
	 * @param originalMessage the original message that was sent
	 */
	private void handleFailedSendDataRequest(SerialMessage originalMessage) {
		new SendDataMessageClass().handleFailedSendDataRequest(this, originalMessage);
	}

	/**
	 * Handles an incoming response message.
	 * An incoming response message is a response, based one of our own requests.
	 * @param incomingMessage the response message to process.
	 */
	private void handleIncomingResponseMessage(SerialMessage incomingMessage) {
		logger.trace("Message type = RESPONSE");

		ZWaveCommandProcessor processor = ZWaveCommandProcessor.getMessageDispatcher(incomingMessage.getMessageClass());
		if(processor != null) {
			processor.handleResponse(this, lastSentMessage, incomingMessage);

			if(processor.isTransactionComplete()) {
				notifyEventListeners(new ZWaveTransactionCompletedEvent(this.lastSentMessage));
				transactionCompleted.release();
				logger.trace("Released. Transaction completed permit count -> {}", transactionCompleted.availablePermits());
			}
		}
		else {
			logger.warn(String.format("TODO: Implement processing of Response Message = %s (0x%02X)",
					incomingMessage.getMessageClass().getLabel(),
					incomingMessage.getMessageClass().getKey()));
		}

		switch (incomingMessage.getMessageClass()) {
			case GetVersion:
				this.zWaveVersion = ((GetVersionMessageClass)processor).getVersion();
				this.ZWaveLibraryType = ((GetVersionMessageClass)processor).getLibraryType();
				break;
			case MemoryGetId:
				this.ownNodeId = ((MemoryGetIdMessageClass)processor).getNodeId();
				this.homeId = ((MemoryGetIdMessageClass)processor).getHomeId();
				break;
			case SerialApiGetInitData:
				this.isConnected = true;
				for(Integer nodeId : ((SerialApiGetInitDataMessageClass)processor).getNodes()) {
					// Place nodes in the local ZWave Controller
					ZWaveNode node = new ZWaveNode(this.homeId, nodeId, this);
					if(nodeId == this.ownNodeId) {
						// This is the controller node.
						// We already know the device type, id, manufacturer so set it here
						// It won't be set later as we probably won't request the manufacturer specific data
						node.setDeviceId(this.getDeviceId());
						node.setDeviceType(this.getDeviceType());
						node.setManufacturer(this.getManufactureId());
					}
					this.zwaveNodes.put(nodeId, node);
					node.advanceNodeStage(NodeStage.PROTOINFO);
				}
				break;
			case SerialApiGetCapabilities:
				this.serialAPIVersion = ((SerialApiGetCapabilitiesMessageClass)processor).getSerialAPIVersion();
				this.manufactureId = ((SerialApiGetCapabilitiesMessageClass)processor).getManufactureId();
				this.deviceId = ((SerialApiGetCapabilitiesMessageClass)processor).getDeviceId();
				this.deviceType = ((SerialApiGetCapabilitiesMessageClass)processor).getDeviceType();
				
				this.enqueue(new SerialApiGetInitDataMessageClass().doRequest());
				break;
			default:
				break;				
		}
	}
	
	// Controller methods

	/**
	 * Connects to the comm port and starts send and receive threads.
	 * @param serialPortName the port name to open
	 * @throws SerialInterfaceException when a connection error occurs.
	 */
	public void connect(final String serialPortName)
			throws SerialInterfaceException {
		logger.info("Connecting to serial port {}", serialPortName);
		try {
			CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(serialPortName);
			CommPort commPort = portIdentifier.open("org.openhab.binding.zwave",2000);
			this.serialPort = (SerialPort) commPort;
			this.serialPort.setSerialPortParams(115200,SerialPort.DATABITS_8,SerialPort.STOPBITS_1,SerialPort.PARITY_NONE);
			this.serialPort.enableReceiveThreshold(1);
			this.serialPort.enableReceiveTimeout(ZWAVE_RECEIVE_TIMEOUT);
			this.receiveThread = new ZWaveReceiveThread();
			this.receiveThread.start();
			this.sendThread = new ZWaveSendThread();
			this.sendThread.start();

			logger.info("Serial port is initialized");
		} catch (NoSuchPortException e) {
			logger.error(String.format("Port %s does not exist", serialPortName));
			throw new SerialInterfaceException(String.format("Port %s does not exist", serialPortName), e);
		} catch (PortInUseException e) {
			logger.error(String.format("Port %s in use.", serialPortName));
			throw new SerialInterfaceException(String.format("Port %s in use.", serialPortName), e);
		} catch (UnsupportedCommOperationException e) {
			logger.error(String.format("Unsupported comm operation on Port %s.", serialPortName));
			throw new SerialInterfaceException(String.format("Unsupported comm operation on Port %s.", serialPortName), e);
		}
	}
	
	/**
	 * Closes the connection to the Z-Wave controller.
	 */
	public void close()	{
		if (watchdog != null) {
			watchdog.cancel();
			watchdog = null;
		}
		
		disconnect();
		
		// clear nodes collection and send queue
		for (Object listener : this.zwaveEventListeners.toArray()) {
			if (!(listener instanceof ZWaveNode))
				continue;
			
			this.zwaveEventListeners.remove(listener);
		}
		
		this.zwaveNodes.clear();
		this.sendQueue.clear();
		
		logger.info("Stopped Z-Wave controller");
	}

	/**
	 * Disconnects from the serial interface and stops
	 * send and receive threads.
	 */
	public void disconnect() {
		if (sendThread != null) {
			sendThread.interrupt();
			try {
				sendThread.join();
			} catch (InterruptedException e) {
			}
			sendThread = null;
		}
		if (receiveThread != null) {
			receiveThread.interrupt();
			try {
				receiveThread.join();
			} catch (InterruptedException e) {
			}
			receiveThread = null;
		}
		if(transactionCompleted.availablePermits() < 0)
			transactionCompleted.release(transactionCompleted.availablePermits());
		
		transactionCompleted.drainPermits();
		logger.trace("Transaction completed permit count -> {}", transactionCompleted.availablePermits());
		if (this.serialPort != null) {
			this.serialPort.close();
			this.serialPort = null;
		}
		logger.info("Disconnected from serial port");
	}
	
	/**
	 * Enqueues a message for sending on the send queue.
	 * @param serialMessage the serial message to enqueue.
	 */
	public void enqueue(SerialMessage serialMessage) {
		this.sendQueue.add(serialMessage);
		logger.debug("Enqueueing message. Queue length = {}", this.sendQueue.size());
	}

	/**
	 * Returns the size of the send queue.
	 */
	public int getSendQueueLength() {
		return this.sendQueue.size();
	}

	/**
	 * Notify our own event listeners of a Z-Wave event.
	 * @param event the event to send.
	 */
	public void notifyEventListeners(ZWaveEvent event) {
		logger.debug("Notifying event listeners");
		for (ZWaveEventListener listener : this.zwaveEventListeners) {
			logger.trace("Notifying {}", listener.toString());
			listener.ZWaveIncomingEvent(event);
		}
	}
	
	/**
	 * Initializes communication with the Z-Wave controller stick.
	 */
	public void initialize() {
		this.enqueue(new GetVersionMessageClass().doRequest());
		this.enqueue(new MemoryGetIdMessageClass().doRequest());
		this.enqueue(new SerialApiGetCapabilitiesMessageClass().doRequest());
	}
	
	/**
	 * Send Identify Node message to the controller.
	 * @param nodeId the nodeId of the node to identify
	 * @throws SerialInterfaceException when timing out or getting an invalid response.
	 */
	public void identifyNode(int nodeId) throws SerialInterfaceException {
		this.enqueue(new IdentifyNodeMessageClass().doRequest(nodeId));
	}
	
	/**
	 * Send Request Node info message to the controller.
	 * @param nodeId the nodeId of the node to identify
	 * @throws SerialInterfaceException when timing out or getting an invalid response.
	 */
	public void requestNodeInfo(int nodeId) {
		this.enqueue(new RequestNodeInfoMessageClass().doRequest(nodeId));
	}
	
	/**
	 * Checks for dead or sleeping nodes during Node initialization.
	 * JwS: merged checkInitComplete and checkForDeadOrSleepingNodes to prevent possibly looping nodes multiple times.
	 */
	public void checkForDeadOrSleepingNodes(){
		int completeCount = 0;
		
		if (zwaveNodes.isEmpty())
			return;
		
		// There are still nodes waiting to get a ping.
		// So skip the dead node checking.
		for (SerialMessage serialMessage : sendQueue) {
			if (serialMessage.getPriority() == SerialMessagePriority.Low)
				return;
		}
		
		logger.trace("Checking for Dead or Sleeping Nodes.");
		for (Map.Entry<Integer, ZWaveNode> entry : zwaveNodes.entrySet()){
			if (entry.getValue().getNodeStage() == NodeStage.EMPTYNODE)
				continue;
			
			logger.debug(String.format("NODE %d: Has been in Stage %s since %s", entry.getKey(), entry.getValue().getNodeStage().getLabel(), entry.getValue().getQueryStageTimeStamp().toString()));
			
			if(entry.getValue().getNodeStage() == NodeStage.DONE || entry.getValue().getNodeStage() == NodeStage.DEAD
					|| (!entry.getValue().isListening() && !entry.getValue().isFrequentlyListening())) {
				completeCount++;
				continue;
			}
			
			logger.trace("NODE {}: Checking if {} miliseconds have passed in current stage.", entry.getKey(), QUERY_STAGE_TIMEOUT);
			
			if(Calendar.getInstance().getTimeInMillis() < (entry.getValue().getQueryStageTimeStamp().getTime() + QUERY_STAGE_TIMEOUT))
				continue;
			
			logger.warn(String.format("NODE %d: May be dead, setting stage to DEAD.", entry.getKey()));
			entry.getValue().setNodeStage(NodeStage.DEAD);

			completeCount++;
		}
		
		// If all nodes are completed, then we say the binding is ready for business
		if(this.zwaveNodes.size() == completeCount && initializationComplete == false) {
			// We only want this event once!
			initializationComplete = true;
			
			ZWaveEvent zEvent = new ZWaveInitializationCompletedEvent(this.ownNodeId);
			this.notifyEventListeners(zEvent);
			
			// If there are DEAD nodes, send a Node Status event
			// We do that here to avoid messing with the binding initialisation
			for(ZWaveNode node : this.getNodes()) {
				if (node.isDead()) {
					logger.debug("NODE {}: DEAD node.", node.getNodeId());

					zEvent = new ZWaveNodeStatusEvent(node.getNodeId(), ZWaveNodeStatusEvent.State.Dead);
					this.notifyEventListeners(zEvent);
				}
			}
		}
	}

	/**
	 * Request the node routing information.
	 *
	 * @param nodeId The address of the node to update
	 */
	public void requestNodeRoutingInfo(int nodeId)
	{
		this.enqueue(new GetRoutingInfoMessageClass().doRequest(nodeId));
	}

	/**
	 * Request the node neighbor list to be updated for the specified node.
	 * Once this is complete, the requestNodeRoutingInfo will be called
	 * automatically to update the data in the binding.
	 *
	 * @param nodeId The address of the node to update
	 */
	public void requestNodeNeighborUpdate(int nodeId)
	{
		this.enqueue(new RequestNodeNeighborUpdateMessageClass().doRequest(nodeId));
	}

	/**
	 * Puts the controller into inclusion mode to add new nodes
	 */
	public void requestAddNodesStart()
	{
		this.enqueue(new AddNodeMessageClass().doRequestStart(true));
	}

	/**
	 * Terminates the inclusion mode
	 */
	public void requestAddNodesStop()
	{
		this.enqueue(new AddNodeMessageClass().doRequestStop());
	}

	/**
	 * Removes a failed nodes from the network.
	 * Note that this won't remove nodes that have not failed.
	 * @param nodeId The address of the node to remove
	 */
	public void requestRemoveFailedNode(int nodeId)
	{
		this.enqueue(new RemoveFailedNodeMessageClass().doRequest(nodeId));
	}

	/**
	 * Delete all return nodes from the specified node. This should be performed
	 * before updating the routes
	 * 
	 * @param nodeId
	 */
	public void requestDeleteAllReturnRoutes(int nodeId)
	{
		this.enqueue(new DeleteReturnRouteMessageClass().doRequest(nodeId));
	}

	/**
	 * Request the controller to set the return route between two nodes
	 * 
	 * @param nodeId
	 *            Source node
	 * @param destinationId
	 *            Destination node
	 */
	public void requestAssignReturnRoute(int nodeId, int destinationId)
	{
		this.enqueue(new AssignReturnRouteMessageClass().doRequest(nodeId, destinationId));
	}

	/**
	 * Request the controller to set the return route from a node to the controller
	 * 
	 * @param nodeId
	 *            Source node
	 */
	public void requestAssignSucReturnRoute(int nodeId)
	{
		this.enqueue(new AssignSucReturnRouteMessageClass().doRequest(nodeId));
	}

	/**
	 * Request the controller is soft reset.
	 * This resets the controller but doesn't loose the network configuration.
	 */
	public void requestAssignSucReturnRoute()
	{
		this.enqueue(new SerialApiSoftResetMessageClass().doRequest());
	}

	/**
	 * Transmits the SerialMessage to a single Z-Wave Node.
	 * Sets the transmission options as well.
	 * @param serialMessage the Serial message to send.
	 */
	public void sendData(SerialMessage serialMessage)
	{
    	if (serialMessage.getMessageClass() != SerialMessageClass.SendData) {
    		logger.error(String.format("Invalid message class %s (0x%02X) for sendData", serialMessage.getMessageClass().getLabel(), serialMessage.getMessageClass().getKey()));
    		return;
    	}
    	if (serialMessage.getMessageType() != SerialMessageType.Request) {
    		logger.error("Only request messages can be sent");
    		return;
    	}
    	
    	ZWaveNode node = this.getNode(serialMessage.getMessageNode());
    	
    	// Keep track of the number of packets sent to this device
    	node.incrementSendCount();

    	if (!node.isListening() && !node.isFrequentlyListening() && serialMessage.getPriority() != SerialMessagePriority.Low) {
			ZWaveWakeUpCommandClass wakeUpCommandClass = (ZWaveWakeUpCommandClass)node.getCommandClass(CommandClass.WAKE_UP);

			// If it's a battery operated device, check if it's awake or place in wake-up queue.
			if (wakeUpCommandClass != null && !wakeUpCommandClass.processOutgoingWakeupMessage(serialMessage)) {
				return;
			}
		}
    	
    	serialMessage.setTransmitOptions(TRANSMIT_OPTION_ACK | TRANSMIT_OPTION_AUTO_ROUTE | TRANSMIT_OPTION_EXPLORE);
    	if (++sentDataPointer > 0xFF)
    		sentDataPointer = 1;
    	serialMessage.setCallbackId(sentDataPointer);
    	logger.debug("Callback ID = {}", sentDataPointer);
    	this.enqueue(serialMessage);
	}
	
	/**
	 * Add a listener for Z-Wave events to this controller.
	 * @param eventListener the event listener to add.
	 */
	public void addEventListener(ZWaveEventListener eventListener) {
		this.zwaveEventListeners.add(eventListener);
	}

	/**
	 * Remove a listener for Z-Wave events to this controller.
	 * @param eventListener the event listener to remove.
	 */
	public void removeEventListener(ZWaveEventListener eventListener) {
		this.zwaveEventListeners.remove(eventListener);
	}
	
    /**
     * Gets the API Version of the controller.
	 * @return the serialAPIVersion
	 */
	public String getSerialAPIVersion() {
		return serialAPIVersion;
	}

	/**
	 * Gets the Manufacturer ID of the controller. 
	 * @return the manufactureId
	 */
	public int getManufactureId() {
		return manufactureId;
	}

	/**
	 * Gets the device type of the controller;
	 * @return the deviceType
	 */
	public int getDeviceType() {
		return deviceType;
	}

	/**
	 * Gets the device ID of the controller.
	 * @return the deviceId
	 */
	public int getDeviceId() {
		return deviceId;
	}
	
	/**
	 * Gets the node ID of the controller.
	 * @return the deviceId
	 */
	public int getOwnNodeId() {
		return ownNodeId;
	}

	/**
	 * Gets the node object using it's node ID as key.
	 * Returns null if the node is not found
	 * @param nodeId the Node ID of the node to get.
	 * @return node object
	 */
	public ZWaveNode getNode(int nodeId) {
		return this.zwaveNodes.get(nodeId);
	}
	
	/**
	 * Gets the node list
	 * @return
	 */
	public Collection<ZWaveNode> getNodes() {
		return this.zwaveNodes.values();
	}

	/**
	 * Indicates a working connection to the
	 * Z-Wave controller stick and initialization complete
	 * @return isConnected;
	 */
	public boolean isConnected() {
		return isConnected && initializationComplete;
	}
	
	/**
	 * Gets the number of Start Of Frames received.
	 * @return the sOFCount
	 */
	public int getSOFCount() {
		return SOFCount;
	}

	/**
	 * Gets the number of Canceled Frames received.
	 * @return the cANCount
	 */
	public int getCANCount() {
		return CANCount;
	}

	/**
	 * Gets the number of Not Acknowledged Frames received.
	 * @return the nAKCount
	 */
	public int getNAKCount() {
		return NAKCount;
	}

	/**
	 * Gets the number of Acknowledged Frames received.
	 * @return the aCKCount
	 */
	public int getACKCount() {
		return ACKCount;
	}

	/**
	 * Returns the number of Out of Order frames received.
	 * @return the oOFCount
	 */
	public int getOOFCount() {
		return OOFCount;
	}
	
	/**
	 * Returns the number of Time-Outs while sending.
	 * @return the oOFCount
	 */
	public int getTimeOutCount() {
		return timeOutCount.get();
	}
	
	// Nested classes and enumerations
	
	/**
	 * Z-Wave controller Send Thread. Takes care of sending all messages.
	 * It uses a semaphore to synchronize communication with the receiving thread.
	 * @author Jan-Willem Spuij
	 * @since 1.3.0
	 */
	private class ZWaveSendThread extends Thread {
	
		private final Logger logger = LoggerFactory.getLogger(ZWaveSendThread.class);

		/**
		 * Run method. Runs the actual sending process.
		 */
		@Override
		public void run() {
			logger.debug("Starting Z-Wave send thread");
			while (!interrupted()) {
				
				try {
					lastSentMessage = sendQueue.take();
					logger.debug("Took message from queue for sending. Queue length = {}", sendQueue.size());
				} catch (InterruptedException e1) {
					break;
				}
				
				if (lastSentMessage == null)
					continue;
				
				if (lastSentMessage.getMessageClass() == SerialMessageClass.SendData) {
					ZWaveNode node = getNode(lastSentMessage.getMessageNode());
					
					if (node != null && !node.isListening() && !node.isFrequentlyListening() && lastSentMessage.getPriority() != SerialMessagePriority.Low) {
						ZWaveWakeUpCommandClass wakeUpCommandClass = (ZWaveWakeUpCommandClass)node.getCommandClass(CommandClass.WAKE_UP);

						// If it's a battery operated device, check if it's awake or place in wake-up queue.
						if (wakeUpCommandClass != null && !wakeUpCommandClass.processOutgoingWakeupMessage(lastSentMessage)) {
							continue;
						}
					}
				}
				
				transactionCompleted.drainPermits();
				
				byte[] buffer = lastSentMessage.getMessageBuffer();
				logger.debug("Sending Message = " + SerialMessage.bb2hex(buffer));
				try {
					synchronized (serialPort.getOutputStream()) {
						serialPort.getOutputStream().write(buffer);
						serialPort.getOutputStream().flush();
					}
				} catch (IOException e) {
					logger.error("Got I/O exception {} during sending. exiting thread.", e.getLocalizedMessage());
					break;
				}
				
				try {
					if (!transactionCompleted.tryAcquire(1, ZWAVE_RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS)) {
						timeOutCount.incrementAndGet();
						if (lastSentMessage.getMessageClass() == SerialMessageClass.SendData) {
							
							buffer = new SerialMessage(SerialMessageClass.SendDataAbort, SerialMessageType.Request, SerialMessageClass.SendData, SerialMessagePriority.High).getMessageBuffer();
							logger.debug("Sending Message = " + SerialMessage.bb2hex(buffer));
							try {
								synchronized (serialPort.getOutputStream()) {
									serialPort.getOutputStream().write(buffer);
									serialPort.getOutputStream().flush();
								}
							} catch (IOException e) {
								logger.error("Got I/O exception {} during sending. exiting thread.", e.getLocalizedMessage());
								break;
							}
						}

						if (--lastSentMessage.attempts >= 0) {
							logger.error("NODE {}: Timeout while sending message. Requeueing", lastSentMessage.getMessageNode());
							if (lastSentMessage.getMessageClass() == SerialMessageClass.SendData)
								handleFailedSendDataRequest(lastSentMessage);
							else
								enqueue(lastSentMessage);
						} else
						{
							logger.warn("NODE {}: Discarding message: {}", lastSentMessage.getMessageNode(), lastSentMessage.toString());
						}
						continue;
					}
					logger.trace("Acquired. Transaction completed permit count -> {}", transactionCompleted.availablePermits());
				} catch (InterruptedException e) {
					break;
				}
				
			}
			logger.debug("Stopped Z-Wave send thread");
		}
	}

	/**
	 * Z-Wave controller Receive Thread. Takes care of receiving all messages.
	 * It uses a semaphore to synchronize communication with the sending thread.
	 * @author Jan-Willem Spuij
	 * @since 1.3.0
	 */	
	private class ZWaveReceiveThread extends Thread {
		
		private static final int SOF = 0x01;
		private static final int ACK = 0x06;
		private static final int NAK = 0x15;
		private static final int CAN = 0x18;
		
		private final Logger logger = LoggerFactory.getLogger(ZWaveReceiveThread.class);

		/**
    	 * Sends 1 byte frame response.
    	 * @param response the response code to send.
    	 */
		private void sendResponse(int response) {
			try {
				synchronized (serialPort.getOutputStream()) {
					serialPort.getOutputStream().write(response);
					serialPort.getOutputStream().flush();
				}
			} catch (IOException e) {
				logger.error(e.getMessage());
			}
		}
		
		/**
    	 * Processes incoming message and notifies event handlers.
    	 * @param buffer the buffer to process.
    	 */
    	private void processIncomingMessage(byte[] buffer) {
    		SerialMessage serialMessage = new SerialMessage(buffer);
    		if (serialMessage.isValid) {
    			logger.trace("Message is valid, sending ACK");
    			sendResponse(ACK);
    		} else {
    			logger.error("Message is not valid, discarding");
    			return;
    		}
    		
    		handleIncomingMessage(serialMessage);
        }
		
		/**
		 * Run method. Runs the actual receiving process.
		 */
		@Override
		public void run() {
			logger.debug("Starting Z-Wave receive thread");

			// Send a NAK to resynchronise communications
			sendResponse(NAK);

			while (!interrupted()) {
				int nextByte;
				
				try {
					nextByte = serialPort.getInputStream().read();
					
					if (nextByte == -1)
						continue;
					
				} catch (IOException e) {
					logger.error("Got I/O exception {} during receiving. exiting thread.", e.getLocalizedMessage());
					break;
				}
				
				switch (nextByte) {
					case SOF:
						int messageLength;
						
						try {
							messageLength = serialPort.getInputStream().read();
							
						} catch (IOException e) {
							logger.error("Got I/O exception {} during receiving. exiting thread.", e.getLocalizedMessage());
							break;
						}
						
						byte[] buffer = new byte[messageLength + 2];
						buffer[0] = SOF;
						buffer[1] = (byte)messageLength;
						int total = 0;
						
						while (total < messageLength) {
							try {
								int read = serialPort.getInputStream().read(buffer, total + 2, messageLength - total); 
								total += (read > 0 ? read : 0);
							} catch (IOException e) {
								logger.error("Got I/O exception {} during receiving. exiting thread.", e.getLocalizedMessage());
								return;
							}
						}
						
						logger.trace("Reading message finished" );
						logger.debug("Receive Message = {}", SerialMessage.bb2hex(buffer));
						processIncomingMessage(buffer);
						SOFCount++;
						break;
					case ACK:
    					logger.trace("Received ACK");
						ACKCount++;
						break;
					case NAK:
    					logger.error("Message not acklowledged by controller (NAK), discarding");
    					transactionCompleted.release();
    					logger.trace("Released. Transaction completed permit count -> {}", transactionCompleted.availablePermits());
						NAKCount++;
						break;
					case CAN:
    					logger.error("Message cancelled by controller (CAN), resending");
						try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							break;
						}
    					enqueue(lastSentMessage);
    					transactionCompleted.release();
    					logger.trace("Released. Transaction completed permit count -> {}", transactionCompleted.availablePermits());
						CANCount++;
						break;
					default:
						logger.warn(String.format("Out of Frame flow. Got 0x%02X. Sending NAK.", nextByte));
    					sendResponse(NAK);
    					OOFCount++;
    					break;
				}
			}
			logger.debug("Stopped Z-Wave receive thread");
		}
	}

	/**
	 * WatchDogTimerTask class. Acts as a watch dog and
	 * checks the serial threads to see whether they are
	 * still running.  
	 * @author Jan-Willem Spuij
	 * @since 1.3.0
	 */
	private class WatchDogTimerTask extends TimerTask {
		
		private final Logger logger = LoggerFactory.getLogger(WatchDogTimerTask.class);
		private final String serialPortName;
		
		/**
		 * Creates a new instance of the WatchDogTimerTask class.
		 * @param serialPortName the serial port name to reconnect to
		 * in case the serial threads have died.
		 */
		public WatchDogTimerTask(String serialPortName) {
			this.serialPortName = serialPortName;
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void run() {
			logger.trace("Watchdog: Checking Serial threads");
			if ((receiveThread != null && !receiveThread.isAlive()) ||
					(sendThread != null && !sendThread.isAlive()))
			{
				logger.warn("Threads not alive, respawning");
				disconnect();
				try {
					connect(serialPortName);
				} catch (SerialInterfaceException e) {
					logger.error("unable to restart Serial threads: {}", e.getLocalizedMessage());
				}
			}
		}
	}
}
