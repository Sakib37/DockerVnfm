package org.openbaton.vnfm;

import org.openbaton.plugin.utils.RabbitPluginBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

/** Created by sakib on 10/30/16. */
@Service
public class DockerRabbitPluginBroker {

  @Autowired private ConfigurableApplicationContext context;

  static Logger log = LoggerFactory.getLogger(org.openbaton.plugin.utils.RabbitPluginBroker.class);

  public Object getDockerVimCaller(String type) {
    return context.getBean("dockerVimCaller", type);
  }

  public Object getDockerVimCaller(String name, String type) {
    return context.getBean("dockerVimCaller", name, type);
  }

  public Object getDockerVimCaller(String name, String type, String managementPort) {
    return context.getBean("dockerVimCaller", name, type, managementPort);
  }

  public Object getDockerVimCaller(
      String brokerIp,
      String username,
      String password,
      int port,
      String type,
      String managementPort) {
    return context.getBean(
        "dockerVimCaller", brokerIp, username, password, port, type, managementPort);
  }

  public Object getDockerVimCaller(
      String brokerIp,
      String username,
      String password,
      int port,
      String type,
      String name,
      String managementPort) {
    return context.getBean(
        "dockerVimCaller", brokerIp, username, password, port, type, name, managementPort);
  }

  public Object getDockerVimCaller(
      String brokerIp, String username, String password, String type, String managementPort) {
    return context.getBean("dockerVimCaller", brokerIp, username, password, type, managementPort);
  }

  /*
  Monitoring plugin
  */

  public Object getMonitoringPluginCaller(String type) {
    return context.getBean("monitoringPluginCaller", type);
  }

  public Object getMonitoringPluginCaller(String name, String type) {
    return context.getBean("monitoringPluginCaller", name, type);
  }

  public Object getMonitoringPluginCaller(String name, String type, String managementPort) {
    return context.getBean("monitoringPluginCaller", name, type, managementPort);
  }

  public Object getMonitoringPluginCaller(
      String brokerIp,
      String username,
      String password,
      int port,
      String type,
      String managementPort) {
    return context.getBean(
        "monitoringPluginCaller", brokerIp, username, password, port, type, managementPort);
  }

  public Object getMonitoringPluginCaller(
      String brokerIp,
      String username,
      String password,
      int port,
      String type,
      String name,
      String managementPort) {
    return context.getBean(
        "monitoringPluginCaller", brokerIp, username, password, port, type, name, managementPort);
  }

  public Object getMonitoringPluginCaller(
      String brokerIp, String username, String password, String type, String managementPort) {
    return context.getBean(
        "monitoringPluginCaller", brokerIp, username, password, type, managementPort);
  }
}
