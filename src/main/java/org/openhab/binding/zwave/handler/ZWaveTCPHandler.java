/**
 * Copyright (c) 2014-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zwave.handler;

import static org.openhab.binding.zwave.ZWaveBindingConstants.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.zwave.ZWaveBindingConstants;
import org.openhab.binding.zwave.internal.protocol.SerialMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ZWaveTCPHandler} is responsible for the tcp communications to the ZWave stick.
 * <p>
 * The {@link ZWaveTCPHandler} is a SmartHome bridge. It handles the tcp communications, and provides a number of
 * channels that feed back communications statistics.
 *
 * @author Aitor Iturrioz - Initial contribution
 */
public class ZWaveTCPHandler extends ZWaveControllerHandler {

    private Logger logger = LoggerFactory.getLogger(ZWaveTCPHandler.class);

    private String tcp_hostname;
    private Integer tcp_port;

    private InetAddress inet;

    private long receiveThreadTime = 0;

    private Socket socatSocket = null;
    private ZWaveTCPReceiveThread tcpReceiveThread;
    private ZWaveTCPConnectionThread tcpConnectionThread;

    private int SOFCount = 0;
    private int CANCount = 0;
    private int NAKCount = 0;
    private int ACKCount = 0;
    private int OOFCount = 0;
    private int CSECount = 0;

    public ZWaveTCPHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing ZWave TCP controller.");

        tcp_hostname = (String) getConfig().get(CONFIGURATION_TCP_HOST);
        tcp_port = Double.valueOf(getConfig().get(CONFIGURATION_TCP_PORT).toString()).intValue();

        if (tcp_hostname == null || tcp_hostname.length() == 0) {
            logger.error("ZWave IP hostname is not set.");
            return;
        }

        if (tcp_port == null || tcp_port == 0) {
            logger.error("ZWave IP port is not set.");
            return;
        }

        super.initialize();

        tcpConnectionThread = new ZWaveTCPConnectionThread();
        tcpConnectionThread.start();
    }

    /**
     * Closes the connection to the ZWave controller.
     */
    @Override
    public void dispose() {
        if (tcpConnectionThread != null) {
            tcpConnectionThread.interrupt();
            try {
                tcpConnectionThread.join();
            } catch (InterruptedException e) {
            }
            tcpConnectionThread = null;
        }
        if (tcpReceiveThread != null) {
            tcpReceiveThread.interrupt();
            try {
                tcpReceiveThread.join();
            } catch (InterruptedException e) {
            }
            tcpReceiveThread = null;
        }
        if (socatSocket != null) {
            try {
                socatSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            socatSocket = null;
        }
        logger.info("Stopped ZWave TCP handler");

        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // if(channelUID.getId().equals(CHANNEL_1)) {
        // TODO: handle command
        // }
    }

    @Override
    public void thingUpdated(Thing thing) {
    }

    /**
     * ZWave TCP Connection Thread. Takes care of checking the TCP socket connection.
     */
    private class ZWaveTCPConnectionThread extends Thread {

        ZWaveTCPConnectionThread() {
            super("ZWaveTCPConnectionThread");
        }

        @Override
        public void run() {
            logger.debug("Starting ZWave TPC connection thread");
            boolean reachable = true;

            while (!interrupted()) {
                try {
                    inet = InetAddress.getByName(tcp_hostname);
                    if (inet.isReachable(10000)) {
                        reachable = true;
                    } else {
                        reachable = false;
                    }
                } catch (IOException e) {
                    reachable = false;
                    logger.error("Gateway ping error: {}", e);
                    e.printStackTrace();
                }

                if (!reachable) {
                    if (socatSocket != null) {
                        onConnectionLost();
                    }
                }

                else if (socatSocket == null) {
                    try {
                        socatSocket = new Socket(tcp_hostname, tcp_port);
                        socatSocket.setSoTimeout(250);
                        onConnectionResumed();
                    } catch (UnknownHostException e) {
                        if (!getThing().getStatus().equals(ThingStatus.OFFLINE)) {
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                                    ZWaveBindingConstants.OFFLINE_TCP_EXISTS);// , tcp_hostname));
                        }
                    } catch (IOException e) {
                        if (!getThing().getStatus().equals(ThingStatus.OFFLINE)) {
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                                    ZWaveBindingConstants.OFFLINE_TCP_CONNECTION);// , tcp_hostname));
                        }
                    }
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

            }

        }

    }

    /**
     * ZWave controller Receive Thread. Takes care of receiving all messages.
     * It uses a semaphore to synchronize communication with the sending thread.
     */
    private class ZWaveTCPReceiveThread extends Thread {

        private final int SEARCH_SOF = 0;
        private final int SEARCH_LEN = 1;
        private final int SEARCH_DAT = 2;

        private int rxState = SEARCH_SOF;
        private int messageLength;
        private int rxLength;
        private byte[] rxBuffer;

        private static final int SOF = 0x01;
        private static final int ACK = 0x06;
        private static final int NAK = 0x15;
        private static final int CAN = 0x18;

        private final Logger logger = LoggerFactory.getLogger(ZWaveTCPReceiveThread.class);

        ZWaveTCPReceiveThread() {
            super("ZWaveReceiveThread");
        }

        /**
         * Sends 1 byte frame response.
         *
         * @param response
         *                     the response code to send.
         */
        private void sendResponse(int response) {
            try {
                synchronized (socatSocket.getOutputStream()) {
                    socatSocket.getOutputStream().write(response);
                    socatSocket.getOutputStream().flush();
                    logger.debug("Response SENT {}", response);
                }
            } catch (IOException e) {
                logger.error(e.getMessage());
            }
        }

        /**
         * Run method. Runs the actual receiving process.
         */
        @Override
        public void run() {
            logger.debug("Starting ZWave thread: Receive");
            receiveThreadTime = System.currentTimeMillis();
            try {
                // Initialise all the statistics channels
                updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_SOF), new DecimalType(SOFCount));
                updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_ACK), new DecimalType(ACKCount));
                updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_NAK), new DecimalType(NAKCount));
                updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_CAN), new DecimalType(CANCount));
                updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_OOF), new DecimalType(OOFCount));
                updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_CSE), new DecimalType(CSECount));

                // Send a NAK to resynchronise communications
                sendResponse(NAK);

                while (!interrupted()) {
                    int nextByte;

                    try {
                        nextByte = socatSocket.getInputStream().read();

                        // If byte value is -1, this is a connection lost
                        if (nextByte == -1) {
                            rxState = SEARCH_SOF;
                            onConnectionLost();
                            break;

                        }
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        logger.error("Got I/O exception {} during receiving. exiting thread.", e.getLocalizedMessage());
                        break;
                    }

                    switch (rxState) {
                        case SEARCH_SOF:
                            switch (nextByte) {
                                case SOF:
                                    logger.trace("Received SOF");

                                    // Keep track of statistics
                                    SOFCount++;
                                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_SOF),
                                            new DecimalType(SOFCount));
                                    rxState = SEARCH_LEN;
                                    break;

                                case ACK:
                                    // Keep track of statistics
                                    ACKCount++;
                                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_ACK),
                                            new DecimalType(ACKCount));
                                    logger.debug("Receive Message = 06");
                                    SerialMessage ackMessage = new SerialMessage(new byte[] { ACK });
                                    incomingMessage(ackMessage);
                                    break;

                                case NAK:
                                    // A NAK means the CRC was incorrectly received by the controller
                                    NAKCount++;
                                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_NAK),
                                            new DecimalType(NAKCount));
                                    logger.debug("Receive Message = 15");
                                    SerialMessage nakMessage = new SerialMessage(new byte[] { NAK });
                                    incomingMessage(nakMessage);
                                    break;

                                case CAN:
                                    // The CAN means that the controller dropped the frame
                                    CANCount++;
                                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_CAN),
                                            new DecimalType(CANCount));
                                    // logger.debug("Protocol error (CAN)");
                                    logger.debug("Receive Message = 18");
                                    SerialMessage canMessage = new SerialMessage(new byte[] { CAN });
                                    incomingMessage(canMessage);
                                    break;

                                default:
                                    OOFCount++;
                                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_OOF),
                                            new DecimalType(OOFCount));
                                    logger.debug(String.format("Protocol error (OOF). Got 0x%02X.", nextByte));
                                    // Let the timeout deal with sending the NAK
                                    break;
                            }
                            break;

                        case SEARCH_LEN:
                            // Sanity check the frame length
                            if (nextByte < 4 || nextByte > 64) {
                                logger.debug("Frame length is out of limits ({})", nextByte);

                                break;
                            }
                            messageLength = (nextByte & 0xff) + 2;

                            rxBuffer = new byte[messageLength];
                            rxBuffer[0] = SOF;
                            rxBuffer[1] = (byte) nextByte;
                            rxLength = 2;
                            rxState = SEARCH_DAT;
                            break;

                        case SEARCH_DAT:
                            rxBuffer[rxLength] = (byte) nextByte;
                            rxLength++;

                            if (rxLength < messageLength) {
                                break;
                            }

                            logger.debug("Receive Message = {}", SerialMessage.bb2hex(rxBuffer));
                            SerialMessage recvMessage = new SerialMessage(rxBuffer);
                            if (recvMessage.isValid) {
                                logger.trace("Message is valid, sending ACK");
                                sendResponse(ACK);

                                incomingMessage(recvMessage);
                            } else {
                                CSECount++;
                                updateState(new ChannelUID(getThing().getUID(), CHANNEL_SERIAL_CSE),
                                        new DecimalType(CSECount));

                                logger.debug("Message is invalid, discarding");
                                sendResponse(NAK);
                            }

                            rxState = SEARCH_SOF;
                            break;
                    }

                }
            } catch (Exception e) {
                logger.error("Exception during ZWave thread. ", e);
            }
            logger.debug("Stopped ZWave thread: Receive");

        }
    }

    @Override
    public void sendPacket(SerialMessage serialMessage) {
        byte[] buffer = serialMessage.getMessageBuffer();
        if (socatSocket == null) {
            logger.debug("NODE {}: Port closed sending REQUEST Message = {}", serialMessage.getMessageNode(),
                    SerialMessage.bb2hex(buffer));
            return;
        }

        logger.debug("NODE {}: Sending REQUEST Message = {}", serialMessage.getMessageNode(),
                SerialMessage.bb2hex(buffer));

        try {
            synchronized (socatSocket.getOutputStream()) {
                socatSocket.getOutputStream().write(buffer);
                socatSocket.getOutputStream().flush();
                logger.debug("Message SENT");
            }
        } catch (IOException e) {
            logger.error("Got I/O exception {} during sending. exiting thread.", e.getLocalizedMessage());
        }
    }

    public void onConnectionLost() {
        logger.error("Connection with the TCP/Serial gateway lost");
        receiveThreadTime = 0;
        if (tcpReceiveThread != null) {
            tcpReceiveThread.interrupt();
            try {
                tcpReceiveThread.join();
            } catch (InterruptedException e) {
            }
        }

        try {
            socatSocket.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            logger.error("Error closing the socket: {}", e.getLocalizedMessage());
        }

        tcpReceiveThread = null;
        socatSocket = null;

        if (!getThing().getStatus().equals(ThingStatus.OFFLINE)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                    ZWaveBindingConstants.OFFLINE_TCP_DISCONNECTED);// , tcp_hostname));
        }
    }

    public void onConnectionResumed() {

        tcpReceiveThread = new ZWaveTCPReceiveThread();
        tcpReceiveThread.start();

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        if (receiveThreadTime != 0) {
            initializeNetwork();
        }

    }
}
