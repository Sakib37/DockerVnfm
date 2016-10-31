package org.openbaton.vnfm;

import org.openbaton.catalogue.nfvo.Server;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.exceptions.NotFoundException;
import org.openbaton.exceptions.PluginException;
import org.openbaton.exceptions.VimDriverException;
import org.openbaton.plugin.utils.PluginCaller;
import org.openbaton.vim.drivers.VimDriverCaller;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Created by sakib on 10/30/16.
 */
public class DockerVimCaller extends VimDriverCaller {
    // Additional docker-plugin methods will be added here

    private PluginCaller pluginCaller;


    public DockerVimCaller(String type) throws IOException, TimeoutException, NotFoundException {
        super(type);
    }

    public DockerVimCaller(String name, String type) throws IOException, TimeoutException, NotFoundException {
        super(name, type);
    }

    public DockerVimCaller(String name, String type, String managementPort) throws IOException, TimeoutException, NotFoundException {
        super(name, type, managementPort);
    }

    public DockerVimCaller(String brokerIp, String username, String password, int port, String type, String managementPort) throws IOException, TimeoutException, NotFoundException {
        super(brokerIp, username, password, port, type, managementPort);
    }

    public DockerVimCaller(String brokerIp, String username, String password, int port, String type, String name, String managementPort) throws IOException, TimeoutException, NotFoundException {
        super(brokerIp, username, password, port, type, name, managementPort);
    }

    public DockerVimCaller(String brokerIp, String username, String password, String type, String managementPort) throws IOException, TimeoutException, NotFoundException {
        super(brokerIp, username, password, type, managementPort);
    }

    public void createVolume(VimInstance vimInstance, String volumeName) throws Exception {
        List<Serializable> params = new LinkedList<>();
        params.add(vimInstance);

        try {
            pluginCaller.executeRPC("createVolume", params, null);
        }catch (IOException | PluginException | InterruptedException e) {
            throw new Exception(e.getMessage());
        }

    }


}
