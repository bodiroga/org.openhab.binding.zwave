/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zwave.discovery;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.jmdns.ServiceInfo;

import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.io.transport.mdns.discovery.MDNSDiscoveryParticipant;
import org.openhab.binding.zwave.ZWaveBindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ZWaveTCPDiscoveryParticipant} is responsible processing the
 * results of searches for mDNS services of type _serial2tcp._tcp.local.
 *
 * @author Aitor Iturrioz - Initial contribution
 */
public class ZWaveTCPDiscoveryParticipant implements MDNSDiscoveryParticipant {

    private Logger logger = LoggerFactory.getLogger(ZWaveTCPDiscoveryParticipant.class);

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Collections.singleton(ZWaveBindingConstants.CONTROLLER_TCP);
    }

    @Override
    public DiscoveryResult createResult(ServiceInfo info) {
        DiscoveryResult result = null;
        ThingUID uid = getThingUID(info);
        if (uid != null) {
            Map<String, Object> properties = new HashMap<>(2);
            String label = "ZWave TCP Controller";
            try {
                label = "ZWave TCP Controller - " + info.getPropertyString("friendly-name");
            } catch (Exception e) {
                // ignore and use default label
            }

            String hostname = info.getHostAddresses()[0];
            int port = info.getPort();

            properties.put(ZWaveBindingConstants.CONFIGURATION_TCP_HOST, hostname);
            properties.put(ZWaveBindingConstants.CONFIGURATION_TCP_PORT, port);

            result = DiscoveryResultBuilder.create(uid).withProperties(properties).withLabel(label).build();

            logger.trace("Created a DiscoveryResult for device '{}' on host '{}'", info.getName(), hostname);

            return result;
        }

        return null;
    }

    @Override
    public ThingUID getThingUID(ServiceInfo info) {
        if (info != null) {
            if (info.getHostAddresses().length != 1) {
                return null;
            }

            logger.debug("ServiceInfo: {}", info);

            if (info.getType() != null) {
                if (info.getType().equals(getServiceType())) {
                    String id = info.getPropertyString("friendly-name");
                    if (id != null) {
                        logger.debug("Discovered a ZWave TCP/Serial thing with name '{}'", id);
                        return new ThingUID(ZWaveBindingConstants.CONTROLLER_TCP, id);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String getServiceType() {
        return "_zwave2tcp._tcp.local.";
    }
}