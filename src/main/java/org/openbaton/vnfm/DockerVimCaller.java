package org.openbaton.vnfm;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.exceptions.NotFoundException;
import org.openbaton.exceptions.PluginException;
import org.openbaton.exceptions.VimDriverException;
import org.openbaton.plugin.utils.PluginCaller;
import org.openbaton.vim.drivers.VimDriverCaller;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/** Created by sakib on 10/30/16. */
@Service
@Scope("prototype")
public class DockerVimCaller extends VimDriverCaller {

  private PluginCaller pluginCaller;

  public DockerVimCaller(String type) throws IOException, TimeoutException, NotFoundException {
    super(type);
  }

  public DockerVimCaller(String name, String type)
      throws IOException, TimeoutException, NotFoundException {
    super(name, type);
  }

  public DockerVimCaller(String name, String type, String managementPort)
      throws IOException, TimeoutException, NotFoundException {
    super(name, type, managementPort);
  }

  public DockerVimCaller(
      String brokerIp,
      String username,
      String password,
      int port,
      String type,
      String managementPort)
      throws IOException, TimeoutException, NotFoundException {
    super(brokerIp, username, password, port, type, managementPort);
  }

  public DockerVimCaller(
      String brokerIp,
      String username,
      String password,
      int port,
      String type,
      String name,
      String managementPort)
      throws IOException, TimeoutException, NotFoundException {
    super(brokerIp, username, password, port, type, name, managementPort);
  }

  public DockerVimCaller(
      String brokerIp, String username, String password, String type, String managementPort)
      throws IOException, TimeoutException, NotFoundException {
    super(brokerIp, username, password, type, managementPort);
  }

  // Additional docker-plugin methods will be added here

  public void connectContainerToNetwork(
      VimInstance vimInstance, String containerId, String networkId) throws VimDriverException {
    List<Serializable> params = new LinkedList<>();
    params.add(vimInstance);
    params.add(containerId);
    params.add(networkId);
    try {
      pluginCaller.executeRPC("connectContainerToNetwork", params, null);
    } catch (IOException | PluginException | InterruptedException e) {
      throw new VimDriverException(e.getMessage());
    }
  }

  public void disconnectContainerFromNetwork(
      VimInstance vimInstance, String containerId, String networkId) throws VimDriverException {
    List<Serializable> params = new LinkedList<>();
    params.add(vimInstance);
    params.add(containerId);
    params.add(networkId);
    try {
      pluginCaller.executeRPC("disconnectContainerFromNetwork", params, null);
    } catch (IOException | PluginException | InterruptedException e) {
      throw new VimDriverException(e.getMessage());
    }
  }

  public void createVolume(VimInstance vimInstance, String volumeName) throws Exception {
    List<Serializable> params = new LinkedList<>();
    params.add(vimInstance);
    params.add(volumeName);
    try {
      pluginCaller.executeRPC("createVolume", params, null);
    } catch (IOException | PluginException | InterruptedException e) {
      throw new Exception(e.getMessage());
    }
  }

  public void deleteVolume(VimInstance vimInstance, String volumeName) throws Exception {
    List<Serializable> params = new LinkedList<>();
    params.add(vimInstance);
    params.add(volumeName);
    try {
      pluginCaller.executeRPC("deleteVolume", params, null);
    } catch (IOException | PluginException | InterruptedException e) {
      throw new Exception(e.getMessage());
    }
  }

  public void copyArchiveToContainer(
      VimInstance vimInstance, String containerId, String pathToarchive) throws Exception {
    List<Serializable> params = new LinkedList<>();
    params.add(vimInstance);
    params.add(containerId);
    params.add(pathToarchive);
    try {
      pluginCaller.executeRPC("copyArchiveToContainer", params, null);
    } catch (IOException | PluginException | InterruptedException e) {
      throw new Exception(e.getMessage());
    }
  }

  public boolean execScript(VimInstance vimInstance, String containerId, String script)
      throws Exception {
    List<Serializable> params = new LinkedList<>();
    params.add(vimInstance);
    params.add(containerId);
    params.add(script);
    boolean result;
    try {
      result = (boolean) pluginCaller.executeRPC("execScript", params, null);
    } catch (IOException | PluginException | InterruptedException e) {
      throw new Exception(e.getMessage());
    }
    return result;
  }
}
