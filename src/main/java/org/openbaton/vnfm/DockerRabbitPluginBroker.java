package org.openbaton.vnfm;

import org.openbaton.plugin.utils.RabbitPluginBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Created by sakib on 10/30/16.
 */
public class DockerRabbitPluginBroker extends RabbitPluginBroker {
    @Autowired
    private ConfigurableApplicationContext context;


    @Override
    public Object getVimDriverCaller(String type) {
        return context.getBean("DockerVimCaller", type);
    }

    @Override
    public Object getVimDriverCaller(String name, String type) {
        return context.getBean("DockerVimCaller", name, type);
    }

    @Override
    public Object getVimDriverCaller(String name, String type, String managementPort) {
        return context.getBean("DockerVimCaller", name, type, managementPort);
    }

    @Override
    public Object getVimDriverCaller(
            String brokerIp,
            String username,
            String password,
            int port,
            String type,
            String managementPort) {
        return context.getBean(
                "DockerVimCaller", brokerIp, username, password, port, type, managementPort);
    }

    @Override
    public Object getVimDriverCaller(
            String brokerIp,
            String username,
            String password,
            int port,
            String type,
            String name,
            String managementPort) {
        return context.getBean(
                "DockerVimCaller", brokerIp, username, password, port, type, name, managementPort);
    }

    @Override
    public Object getVimDriverCaller(
            String brokerIp, String username, String password, String type, String managementPort) {
        return context.getBean("DockerVimCaller", brokerIp, username, password, type, managementPort);
    }
}
