package org.openbaton.vnfm;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openbaton.catalogue.mano.common.Event;
import org.openbaton.catalogue.mano.common.LifecycleEvent;
import org.openbaton.catalogue.mano.descriptor.InternalVirtualLink;
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.descriptor.VirtualNetworkFunctionDescriptor;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VNFRecordDependency;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.ConfigurationParameter;
import org.openbaton.catalogue.nfvo.DependencyParameters;
import org.openbaton.catalogue.nfvo.Script;
import org.openbaton.catalogue.nfvo.Server;
import org.openbaton.catalogue.nfvo.VNFPackage;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.catalogue.nfvo.messages.Interfaces.NFVMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmGenericMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmInstantiateMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmStartStopMessage;
import org.openbaton.common.vnfm_sdk.VnfmHelper;
import org.openbaton.common.vnfm_sdk.amqp.AbstractVnfmSpringAmqp;
import org.openbaton.common.vnfm_sdk.exception.BadFormatException;
import org.openbaton.common.vnfm_sdk.exception.NotFoundException;
import org.openbaton.common.vnfm_sdk.exception.VnfmSdkException;
import org.openbaton.common.vnfm_sdk.utils.VnfmUtils;
import org.openbaton.nfvo.vim_interfaces.resource_management.ResourceManagement;
import org.openbaton.plugin.utils.PluginStartup;
import org.openbaton.vnfm.utils.DockerRabbitPluginBroker;
import org.openbaton.vnfm.utils.DockerVimCaller;
import org.openbaton.vnfm.utils.NetworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.FileSystemUtils;

/** Created by sakib on 10/28/16. */
public class DockerVnfm extends AbstractVnfmSpringAmqp {

  private Map<String, NetworkService> networkServiceMap;

  @Autowired private VnfmHelper vnfmHelper;

  @Autowired private ConfigurableApplicationContext context;

  private ResourceManagement resourceManagement;

  private Set<PosixFilePermission> permissions;

  private DockerVimCaller client;
  private VimInstance dockerVimInstance;

  @Override
  protected void setup() {
    super.setup();
    try {
      //Start all the plugins that are located in ./plugins
      PluginStartup.startPluginRecursive(
          "./plugins",
          true,
          "localhost",
          "5672",
          15,
          "admin",
          "openbaton",
          "15672",
          "/var/log/openbaton/DockerVnfm");
    } catch (IOException e) {
      e.printStackTrace();
    }

    //Using the docker-plugin directly
    client =
        (DockerVimCaller)
            ((DockerRabbitPluginBroker) context.getBean("dockerRabbitPluginBroker"))
                .getDockerVimCaller(
                    "localhost", "admin", "openbaton", 5672, "docker", "docker", "15672");
  }

  public static void main(String[] args) {
    SpringApplication.run(DockerVnfm.class);
  }

  public DockerVnfm() {
    super();
    networkServiceMap = new HashMap<>();
  }

  @Override
  protected synchronized void onAction(NFVMessage message)
      throws NotFoundException, BadFormatException {
    VirtualNetworkFunctionRecord virtualNetworkFunctionRecord = null;
    NFVMessage nfvMessage = null;
    OrVnfmGenericMessage orVnfmGenericMessage;
    OrVnfmStartStopMessage orVnfmStartStopMessage;
    NetworkService networkService;

    try {
      switch (message.getAction()) {
        case INSTANTIATE:
          log.info(
              "Received NFVO Message: "
                  + "Instantiating VNFD "
                  + ((OrVnfmInstantiateMessage) message).getVnfd().getName());
          OrVnfmInstantiateMessage orVnfmInstantiateMessage = (OrVnfmInstantiateMessage) message;
          VirtualNetworkFunctionDescriptor vnfd = orVnfmInstantiateMessage.getVnfd();

          Map<String, Collection<VimInstance>> vimInstances =
              orVnfmInstantiateMessage.getVimInstances();
          virtualNetworkFunctionRecord =
              createVirtualNetworkFunctionRecord(
                  orVnfmInstantiateMessage.getVnfd(),
                  orVnfmInstantiateMessage.getVnfdf().getFlavour_key(),
                  orVnfmInstantiateMessage.getVlrs(),
                  orVnfmInstantiateMessage.getExtension(),
                  vimInstances);
          networkService = getNetworkService(orVnfmInstantiateMessage.getExtension().get("nsr-id"));

          // add vnfd and vnfpackage to the network service
          networkService.addVnfd(vnfd);
          networkService.addVnfPackage(orVnfmInstantiateMessage.getVnfPackage(), vnfd.getName());
          networkService.setVnfStatus(vnfd.getName(), "instantiated");
          networkServiceMap.put(vnfd.getName(), networkService);

          dockerVimInstance = getDockerVimInstance(orVnfmInstantiateMessage);

          if (dockerVimInstance != null) {
            if (orVnfmInstantiateMessage.getVnfPackage() != null) {
              if (orVnfmInstantiateMessage.getVnfPackage().getScriptsLink() != null)
                virtualNetworkFunctionRecord =
                    instantiate(
                        virtualNetworkFunctionRecord,
                        orVnfmInstantiateMessage.getVnfPackage().getScriptsLink(),
                        vimInstances);
              else
                virtualNetworkFunctionRecord =
                    instantiate(
                        virtualNetworkFunctionRecord,
                        orVnfmInstantiateMessage.getVnfPackage().getScripts(),
                        vimInstances);
            } else {
              virtualNetworkFunctionRecord =
                  instantiate(virtualNetworkFunctionRecord, null, vimInstances);
            }

            VirtualDeploymentUnit vdu = vnfd.getVdu().iterator().next();
            String serverImage = vdu.getVm_image().iterator().next();

            Iterator<ConfigurationParameter> configIterator =
                virtualNetworkFunctionRecord
                    .getConfigurations()
                    .getConfigurationParameters()
                    .iterator();
            Map<String, String> configParameters = new HashMap<>();
            while (configIterator.hasNext()) {
              ConfigurationParameter currentParam = configIterator.next();
              configParameters.put(currentParam.getConfKey(), currentParam.getValue());
            }
            List<String> portsToExpose = getExposedPorts(configParameters);
            List<String> environmentVariables = getEnvironmentVariables(configParameters);

            // Download required scripts
            String vnfmDirectory = "/tmp/openbaton/dockerVNFM/" + vnfd.getName() + "/";
            File pathToVnfd = new File("/tmp/openbaton/dockerVNFM/" + vnfd.getName());
            FileSystemUtils.deleteRecursively(pathToVnfd);

            prepareScript(orVnfmInstantiateMessage.getVnfPackage(), vnfd.getName());
            environmentVariables =
                updateEnvironmentVariablesFromFile(
                    environmentVariables,
                    "/tmp/openbaton/dockerVNFM/" + vnfd.getName() + "/scripts/default_options");

            log.info("Creating server for VNFD " + vnfd.getName());
            Server server = null;
            try {
              server =
                  client.launchInstance(
                      dockerVimInstance,
                      vnfd.getName(),
                      serverImage,
                      portsToExpose,
                      environmentVariables);
              log.info("Server for VNFD " + vnfd.getName() + " is successfully created");
            } catch (Exception e) {
              log.debug("Failed to create server " + vnfd.getName());
              log.debug(e.toString());
            }

            // Copying scripts to server
            log.info("Copying scripts to server " + vnfd.getName());
            try {
              client.copyArchiveToContainer(
                  dockerVimInstance, server.getId(), vnfmDirectory + "scripts", "/");
              log.info("Successfully copied scripts to '/scripts' in server " + vnfd.getName());
            } catch (Exception e) {
              log.info("Failed to copy scripts to server " + vnfd.getName());
              log.debug(e.getMessage());
            }

            /*// Getting default options from 'default_options' file and set them as ENV variable
            // This is not helpful now as docker do not support set environment variable after creation
            File pathToVnfmScripts =
                new File("/tmp/openbaton/dockerVNFM" + "/" + vnfd.getName() + "/" + "scripts/");
            File[] scripts = pathToVnfmScripts.listFiles();
            List<String> defaultEnvVariables;
            for (File file : scripts) {
              if (file.getName().toLowerCase().contains("default_options")) {
                defaultEnvVariables = getEnvironmentVariablesFromFile(file.getAbsolutePath());
                try {
                  client.setEnvironmentVariable(
                      dockerVimInstance, vnfd.getName(), defaultEnvVariables);
                  log.info("Default ENV variables from default_options file is set");
                } catch (Exception e) {
                  log.debug("Failed to set ENV variables from default_options file");
                  log.debug(e.getMessage());
                }
              }
            }*/

            // Adding server to the networks
            log.info("Adding Server " + vnfd.getName() + " with virtual links");
            Iterator iterator = orVnfmInstantiateMessage.getVnfd().getVirtual_link().iterator();
            while (iterator.hasNext()) {
              String netName = ((InternalVirtualLink) iterator.next()).getName();
              try {
                log.info("Trying to create network '" + netName + "'");
                client.createDockerNetwork(dockerVimInstance, netName);
              } catch (Exception ignore) {
                log.info("Network '" + netName + "' is already available");
              }
              client.connectContainerToNetwork(dockerVimInstance, server.getId(), netName);
            }
            log.info("Successfully connected server " + vnfd.getName() + " with all virtual links");

            log.info("Executing scripts for Lifecycle event INSTANTIATE");
            executeScriptsForEvent(
                virtualNetworkFunctionRecord, Event.INSTANTIATE, dockerVimInstance);
            nfvMessage = VnfmUtils.getNfvMessage(Action.INSTANTIATE, virtualNetworkFunctionRecord);
            log.info("Instantiated vnfr " + vnfd.getName());
          } else {
            log.debug("Failed : No vimInstance of type docker found");
          }
          break;
        case MODIFY:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFR "
                  + ((OrVnfmGenericMessage) message).getVnfr().getName()
                  + " and following vnfrDep: \n"
                  + ((OrVnfmGenericMessage) message).getVnfrd());
          orVnfmGenericMessage = (OrVnfmGenericMessage) message;
          virtualNetworkFunctionRecord = orVnfmGenericMessage.getVnfr();
          VNFRecordDependency vnfrDependency = orVnfmGenericMessage.getVnfrd();
          nsrId = virtualNetworkFunctionRecord.getParent_ns_id();
          networkService = getNetworkService(nsrId);

          for (Map.Entry<String, DependencyParameters> entry :
              vnfrDependency.getParameters().entrySet()) {
            String sourceType = entry.getKey();
            String sourceName = "";
            for (Map.Entry<String, String> nameTypeEntry : vnfrDependency.getIdType().entrySet()) {
              if (nameTypeEntry.getValue().equals(sourceType)) sourceName = nameTypeEntry.getKey();
            }
            DependencyParameters dependencyParameters = entry.getValue();
            Map<String, String> sourceEnvParameter = getRuntimeEnvironmentFromFile(sourceName);
            Map<String, String> updatedDependency =
                organizeDependencies(dependencyParameters.getParameters(), sourceEnvParameter);
            dependencyParameters.setParameters(updatedDependency);

            List<String> parameters = new LinkedList<>();
            for (Map.Entry<String, String> pe : dependencyParameters.getParameters().entrySet()) {
              parameters.add(pe.getKey());
            }

            Map<String, List<String>> sourceParams = new HashMap<>();
            sourceParams.put(sourceName, parameters);
            Map<String, Map<String, List<String>>> targetSourceParams = new HashMap<>();
            targetSourceParams.put(virtualNetworkFunctionRecord.getName(), sourceParams);

            networkService.addDependency(
                virtualNetworkFunctionRecord.getName(), sourceName, parameters);
          }

          executeScriptsForEvent(
              virtualNetworkFunctionRecord, Event.CONFIGURE, vnfrDependency, dockerVimInstance);

          networkService.setVnfStatus(virtualNetworkFunctionRecord.getName(), "modified");
          nfvMessage =
              VnfmUtils.getNfvMessage(
                  Action.MODIFY,
                  this.modify(orVnfmGenericMessage.getVnfr(), orVnfmGenericMessage.getVnfrd()));
          log.info("After modify of " + virtualNetworkFunctionRecord.getName() + ":\n");
          break;
        case START:
          String vnfrName;
          if (message.getClass().getName().toString().toLowerCase().contains("generic")) {
            orVnfmGenericMessage = (OrVnfmGenericMessage) message;
            vnfrName = ((OrVnfmGenericMessage) message).getVnfr().getName();
            log.info(
                "Received NFVO Message: "
                    + message.getAction()
                    + " for VNFR "
                    + vnfrName
                    + " and following vnfrDep: \n"
                    + ((OrVnfmGenericMessage) message).getVnfrd());
            virtualNetworkFunctionRecord = orVnfmGenericMessage.getVnfr();
          } else {
            orVnfmStartStopMessage = (OrVnfmStartStopMessage) message;
            vnfrName =
                ((OrVnfmStartStopMessage) message).getVirtualNetworkFunctionRecord().getName();
            log.info(
                "Received NFVO Message: "
                    + message.getAction()
                    + " for VNFR "
                    + vnfrName
                    + " and following vnfrDep: \n"
                    + ((OrVnfmStartStopMessage) message).getVnfrd());
            virtualNetworkFunctionRecord = orVnfmStartStopMessage.getVirtualNetworkFunctionRecord();
          }

          nsrId = virtualNetworkFunctionRecord.getParent_ns_id();
          log.info("Executing scripts for Lifecycle event START");
          executeScriptsForEvent(virtualNetworkFunctionRecord, Event.START, dockerVimInstance);
          log.info("Started vnfr " + virtualNetworkFunctionRecord.getName());

          nfvMessage = VnfmUtils.getNfvMessage(Action.START, start(virtualNetworkFunctionRecord));
          break;
        case RELEASE_RESOURCES:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFR "
                  + ((OrVnfmGenericMessage) message).getVnfr().getName());

          orVnfmGenericMessage = (OrVnfmGenericMessage) message;
          virtualNetworkFunctionRecord = orVnfmGenericMessage.getVnfr();
          //System.out.println("VNFR : " + virtualNetworkFunctionRecord);
          networkServiceMap.remove(virtualNetworkFunctionRecord.getParent_ns_id());

          // Removing the server
          vnfrName = virtualNetworkFunctionRecord.getName();
          /*File pathToVnfm =
                  new File("/tmp/openbaton/dockerVNFM/" + vnfrName);
          FileSystemUtils.deleteRecursively(pathToVnfm);*/
          client.deleteServerByIdAndWait(dockerVimInstance, vnfrName);
          Iterator<InternalVirtualLink> virtualLinks =
              virtualNetworkFunctionRecord.getVirtual_link().iterator();
          // Trying to remove networks if no more server attached
          while (virtualLinks.hasNext()) {
            InternalVirtualLink internalVirtualLink = virtualLinks.next();
            boolean res = false;
            try {
              res = client.deleteNetwork(dockerVimInstance, internalVirtualLink.getName());
              if (res) {
                log.info("No server connected to network '" + internalVirtualLink.getName() + "'");
                log.info("Network '" + internalVirtualLink.getName() + "' deleted successfully");
              }
            } catch (Exception ignore) {
            }
          }

          virtualNetworkFunctionRecord = orVnfmGenericMessage.getVnfr();
          nfvMessage =
              VnfmUtils.getNfvMessage(
                  Action.RELEASE_RESOURCES, this.terminate(virtualNetworkFunctionRecord));
          break;
      }

      if (nfvMessage != null) {
        log.debug("send to NFVO");
        vnfmHelper.sendToNfvo(nfvMessage);
      }
    } catch (Exception e) {
      log.error("ERROR: ", e);
      if (e instanceof VnfmSdkException) {
        VnfmSdkException vnfmSdkException = (VnfmSdkException) e;
        if (vnfmSdkException.getVnfr() != null) {
          log.debug("sending VNFR with version: " + vnfmSdkException.getVnfr().getHb_version());
          vnfmHelper.sendToNfvo(
              VnfmUtils.getNfvErrorMessage(vnfmSdkException.getVnfr(), vnfmSdkException, nsrId));
          return;
        }
      } else if (e.getCause() instanceof VnfmSdkException) {
        VnfmSdkException vnfmSdkException = (VnfmSdkException) e.getCause();
        if (vnfmSdkException.getVnfr() != null) {
          log.debug("sending VNFR with version: " + vnfmSdkException.getVnfr().getHb_version());
          vnfmHelper.sendToNfvo(
              VnfmUtils.getNfvErrorMessage(vnfmSdkException.getVnfr(), vnfmSdkException, nsrId));
          return;
        }
      }
      vnfmHelper.sendToNfvo(VnfmUtils.getNfvErrorMessage(virtualNetworkFunctionRecord, e, nsrId));
    }
  }

  private Map<String, String> organizeDependencies(
      Map<String, String> parameters, Map<String, String> sourceEnvParameter) {
    for (String s : parameters.keySet()) {
      if (parameters.get(s) == null || parameters.get(s).isEmpty()) {
        try {
          parameters.put(s, sourceEnvParameter.get(s));
        } catch (Exception ignore) {
        }
      }
    }
    return parameters;
  }

  /**
   * Get a NetworkService object from the networkServiceMap. If it does not contain the requested
   * NetworkService yet, create and add it.
   *
   * @param id
   * @return the requested NetworkService
   */
  private synchronized NetworkService getNetworkService(String id) {
    if (networkServiceMap.containsKey(id)) return networkServiceMap.get(id);
    else {
      NetworkService networkService = new NetworkService();
      networkServiceMap.put(id, networkService);
      networkService.setId(id);
      return networkService;
    }
  }

  public Iterable<String> executeScriptsForEvent(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      Event event,
      VimInstance dockerVimInstance)
      throws Exception {
    Map<String, String> enVariables = getMap(virtualNetworkFunctionRecord);
    List<String> env = getEnvironmentVariables(enVariables);
    Collection<String> res = new ArrayList<>();
    String vnfrName = virtualNetworkFunctionRecord.getName();

    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);
    List<Server> servers = client.listServer(dockerVimInstance);
    Server vnfcInstance = new Server();
    for (Server server : servers) {
      if (vnfrName.contains(server.getName())) {
        vnfcInstance = server;
      }
    }

    if (le != null) {
      log.info("The number of scripts for " + vnfrName + " are: " + le.getLifecycle_events());
      for (String script : le.getLifecycle_events()) {
        log.info(
            "Executing script '"
                + script
                + "' in VirtualNetworkFunctionRecord: "
                + virtualNetworkFunctionRecord.getName());
        String scriptPath = "/tmp/openbaton/dockerVNFM/" + vnfrName + "/scripts/" + script;
        Map<String, String> tempEnvMap = getRuntimeEnvironmentFromFile(vnfrName);
        Map<String, List<String>> ips = vnfcInstance.getIps();
        for (String s : ips.keySet()) {
          // By default every docker container connects with bridge network driver
          if (!s.equals("bridge")) {
            log.info(
                "IP address of '"
                    + vnfrName
                    + "' in network '"
                    + s
                    + "' is '"
                    + ips.get(s).iterator().next());
            tempEnvMap.put(s, ips.get(s).iterator().next());
          }
        }

        tempEnvMap = modifyUnsafeEnvVarNames(tempEnvMap);
        for (String s : tempEnvMap.keySet()) {
          env.add(s + "=" + tempEnvMap.get(s));
        }
        log.info("Environment Variables are: " + env);
        writeRunTimeEnvToFile(vnfrName, env);
        // Prepare the script with runtime environment variables
        prepareScriptWithRunTimeEnv(vnfrName, scriptPath);
        client.copyArchiveToContainer(dockerVimInstance, vnfrName, scriptPath, "/scripts/");
        client.setEnvironmentVariable(dockerVimInstance, vnfrName, env);
        //Thread.sleep(10);

        try {
          String scriptLocation = "/scripts/" + script;
          client.execCommand(dockerVimInstance, vnfrName, scriptLocation);
          log.info("Script '" + script + "' Successfully executed");
        } catch (Exception e) {
          log.debug("Error occured while executing " + script + "on VNFC" + vnfrName);
          log.debug(e.toString());
        }
      }
    }
    return res;
  }

  /*
   * Prepares the script with runtime variable available in 'vnfr/runTimeEnvironment' file*/
  private void prepareScriptWithRunTimeEnv(String vnfrName, String scriptPath) throws IOException {
    String filePath = "/tmp/openbaton/dockerVNFM/" + vnfrName + "/runTimeEnvironment";
    List<String> readLines = Files.readAllLines(Paths.get(filePath), Charset.forName("UTF-8"));
    String runTimeEnv = "";
    for (String readLine : readLines) {
      runTimeEnv += ("export " + readLine + "\n");
    }
    List<String> scriptLines = Files.readAllLines(Paths.get(scriptPath), Charset.forName("UTF-8"));
    int lineNumForInsert = 2;
    for (String scriptLine : scriptLines) {
      if (scriptLine != null && scriptLine != "" && scriptLine.length() > 1) {
        if (scriptLine.charAt(0) != '#') {
          lineNumForInsert = scriptLines.indexOf(scriptLine) - 1;
          break;
        }
      }
    }
    scriptLines.add(lineNumForInsert, runTimeEnv);
    File script = new File(scriptPath);
    try (Writer writer = new BufferedWriter(new FileWriter(script))) {
      for (String scriptLine : scriptLines) {
        writer.write(scriptLine + "\n");
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void writeRunTimeEnvToFile(String vnfrName, List<String> env) {
    Set<String> nonDuplicateEnvSet = new HashSet<String>(env);
    env.clear();
    env.addAll(nonDuplicateEnvSet);
    String filePath = "/tmp/openbaton/dockerVNFM/" + vnfrName + "/runTimeEnvironment";
    File runTimeEnvFile = new File(filePath);
    if (runTimeEnvFile.exists()) runTimeEnvFile.delete();
    try (Writer writer = new BufferedWriter(new FileWriter(runTimeEnvFile))) {
      for (String runTimeVar : env) {
        writer.write(runTimeVar + "\n");
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private Map<String, String> getRuntimeEnvironmentFromFile(String vnfrName) throws IOException {
    Map<String, String> runTimeEnv = new HashMap<>();
    String filePath = "/tmp/openbaton/dockerVNFM/" + vnfrName + "/runTimeEnvironment";
    if ((new File(filePath)).exists()) {
      List<String> lines = Files.readAllLines(Paths.get(filePath), Charset.forName("UTF-8"));
      for (String line : lines) {
        if (line != null && line.length() > 3) {
          try {
            String[] currentEnv = line.split("=");
            runTimeEnv.put(currentEnv[0], currentEnv[1]);
          } catch (Exception ignore) {
          }
        }
      }
    }
    return runTimeEnv;
  }

  public Iterable<String> executeScriptsForEvent(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      Event event,
      VNFRecordDependency dependency,
      VimInstance dockerVimInstance)
      throws Exception {
    Map<String, String> envVars = getMap(virtualNetworkFunctionRecord);
    List<String> env = getEnvironmentVariables(envVars);
    Collection<String> res = new ArrayList<>();
    String vnfrName = virtualNetworkFunctionRecord.getName();

    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);
    List<Server> servers = client.listServer(dockerVimInstance);
    Server vnfcInstance = new Server();
    for (Server server : servers) {
      if (vnfrName.contains(server.getName())) {
        vnfcInstance = server;
      }
    }

    if (le != null) {
      for (String script : le.getLifecycle_events()) {
        String type = null;
        if (script.contains("_")) {
          type = script.substring(0, script.indexOf('_'));
          log.info(
              "Executing script '"
                  + script
                  + "' in VirtualNetworkFunctionRecord: "
                  + virtualNetworkFunctionRecord.getName());
        }

        // runTimeEnvironment already filled in '...../openbaton/vnfrname/runTimeEnvironment' file.
        // Native Ips and other parameters will be loaded from this file.
        String scriptPath = "/tmp/openbaton/dockerVNFM/" + vnfrName + "/scripts/" + script;
        Map<String, String> tempEnvMap = getRuntimeEnvironmentFromFile(vnfrName);
        if (dependency.getParameters() != null) {
          for (String sourceType : dependency.getParameters().keySet()) {
            if (script.contains("_")) {
              //Adding foreign parameters such as ip
              log.debug("Fetching parameter from dependency of type: " + type);
            }

            Map<String, String> parameters = dependency.getParameters().get(type).getParameters();
            if (parameters.entrySet() != null) {
              for (Map.Entry<String, String> param : parameters.entrySet()) {
                log.info("adding param: " + type + "_" + param.getKey() + " = " + param.getValue());
                tempEnvMap.put(type + "_" + param.getKey(), param.getValue());
              }
            }
          }

          tempEnvMap = modifyUnsafeEnvVarNames(tempEnvMap);
          for (String s : tempEnvMap.keySet()) {
            env.add(s + "=" + tempEnvMap.get(s));
          }
          log.info("Environment Variables are: " + env);
          writeRunTimeEnvToFile(vnfrName, env);
          // Prepare the script with runtime environment variables
          prepareScriptWithRunTimeEnv(vnfrName, scriptPath);
          client.copyArchiveToContainer(dockerVimInstance, vnfrName, scriptPath, "/scripts/");
          client.setEnvironmentVariable(dockerVimInstance, vnfrName, env);
          //Thread.sleep(10);

          try {
            String scriptLocation = "/scripts/" + script;
            client.execCommand(dockerVimInstance, vnfrName, scriptLocation);
            log.info("Script '" + script + "' Successfully executed with dependency");
          } catch (Exception e) {
            log.debug("Error occured while executing " + script + "on VNFC" + vnfrName);
            log.debug(e.toString());
          }
        }
      }
    }
    return res;
  }

  private void prepareScript(VNFPackage vnfPackage, String vnfdName) throws IOException {
    (new File("/tmp/openbaton/dockerVNFM" + "/" + vnfdName + "/" + "scripts/")).mkdirs();
    if (vnfPackage.getScriptsLink() != null && !vnfPackage.getScriptsLink().equals("")) {
      getScriptsFromScriptsLink(vnfPackage.getScriptsLink(), vnfdName);
    } else {
      Set<Script> scripts = vnfPackage.getScripts();
      for (Script script : scripts) {
        File scriptFile =
            new File(
                "/tmp/openbaton/dockerVNFM/"
                    + "/"
                    + vnfdName
                    + "/"
                    + "scripts/"
                    + script.getName());
        if (!scriptFile.exists()) scriptFile.createNewFile();
        try {
          Files.write(
              Paths.get(scriptFile.getAbsolutePath()),
              script.getPayload(),
              StandardOpenOption.APPEND);
        } catch (IOException e) {
          log.error("Could not write to script file " + script.getName());
        }
      }
    }
  }

  private void getScriptsFromScriptsLink(String scriptsLink, String vnfdName) {
    log.info("Start fetching git repository from " + scriptsLink + " for VNFD " + vnfdName);
    ProcessBuilder pb =
        new ProcessBuilder(
            "/bin/bash",
            "-c",
            "cd /tmp/openbaton/dockerVNFM/"
                + vnfdName
                + "/"
                + "scripts"
                + " && git clone "
                + scriptsLink
                + " .");
    Process execute = null;
    int exitStatus = -1;
    try {
      execute = pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
      exitStatus = execute.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    if (exitStatus == 0) {
      log.info("Successfully fetched git repository");
    } else log.error("Could not fetch git repository");
  }

  private List<String> getEnvironmentVariables(Map<String, String> configParameters) {
    List<String> environmentVariables = new ArrayList<>();
    for (String s : configParameters.keySet()) {
      String currentVariable = s + "=" + configParameters.get(s);
      environmentVariables.add(currentVariable);
    }
    return environmentVariables;
  }

  private List<String> updateEnvironmentVariablesFromFile(
      List<String> environmentVariables, String filePath) throws IOException {
    List<String> lines = Files.readAllLines(Paths.get(filePath), Charset.forName("UTF-8"));
    for (String line : lines) {
      // Replacing spaces also replaces the spaces in $PACKAGES
      if (line != null && line.length() > 3) {
        environmentVariables.add(line.replaceAll("\\s+=\\s+", "=").replaceAll("\"", ""));
        //environmentVariables.add(line.replaceAll("\"", ""));
      }
    }
    return environmentVariables;
  }

  private Map<String, String> getMap(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {
    Map<String, String> res = new HashMap<>();
    for (ConfigurationParameter configurationParameter :
        virtualNetworkFunctionRecord.getProvides().getConfigurationParameters()) {
      res.put(configurationParameter.getConfKey(), configurationParameter.getValue());
    }
    for (ConfigurationParameter configurationParameter :
        virtualNetworkFunctionRecord.getConfigurations().getConfigurationParameters()) {
      res.put(configurationParameter.getConfKey(), configurationParameter.getValue());
    }
    res = modifyUnsafeEnvVarNames(res);
    return res;
  }

  private List<String> getExposedPorts(Map<String, String> configParameters) {
    List<String> exposedPorts = new ArrayList<>();
    for (String s : configParameters.keySet()) {
      if (s.toLowerCase().indexOf("port") >= 0) {
        exposedPorts.add(configParameters.get(s));
      }
    }
    return exposedPorts;
  }

  private VimInstance getDockerVimInstance(OrVnfmInstantiateMessage orVnfmInstantiateMessage) {
    VimInstance dockerVimInstance = null;
    Map<String, Collection<VimInstance>> vimInstances = orVnfmInstantiateMessage.getVimInstances();

    for (String s : vimInstances.keySet()) {
      for (int i = 0; i < vimInstances.get(s).toArray().length; i++) {
        if (((VimInstance) vimInstances.get(s).toArray()[i]).getName().equals("docker")) {
          dockerVimInstance = (VimInstance) vimInstances.get(s).toArray()[i];
          break;
        }
      }
    }
    return dockerVimInstance;
  }

  private Map<String, String> modifyUnsafeEnvVarNames(Map<String, String> env) {

    Map<String, String> result = new HashMap<>();

    for (Map.Entry<String, String> entry : env.entrySet()) {
      result.put(entry.getKey().replaceAll("[^A-Za-z0-9_]", "_"), entry.getValue());
    }

    return result;
  }

  /**
   * This operation allows creating a VNF instance.
   *
   * @param virtualNetworkFunctionRecord
   * @param scripts
   * @param vimInstances
   */
  @Override
  public VirtualNetworkFunctionRecord instantiate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      Object scripts,
      Map<String, Collection<VimInstance>> vimInstances)
      throws Exception {
    /** Allocation of Resources the grant operation is already done before this method */
    log.debug("Allocating all Resources for vnfr: " + virtualNetworkFunctionRecord);
    return virtualNetworkFunctionRecord;
  }

  /** This operation allows retrieving VNF instance state and attributes. */
  @Override
  public void query() {}

  /** This operation allows scaling (out/in, up/down) a VNF instance. */
  @Override
  public VirtualNetworkFunctionRecord scale(
      Action scaleInOrOut,
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFComponent component,
      Object scripts,
      VNFRecordDependency dependency)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  /** This operation allows verifying if the VNF instantiation is possible. */
  @Override
  public void checkInstantiationFeasibility() {}

  /** This operation is called when one the VNFs fails */
  @Override
  public VirtualNetworkFunctionRecord heal(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance component,
      String cause)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  /**
   * This operation allows applying a minor/limited software update (e.g. patch) to a VNF instance.
   */
  @Override
  public VirtualNetworkFunctionRecord updateSoftware(
      Script script, VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  /**
   * This operation allows making structural changes (e.g. configuration, topology, behavior,
   * redundancy model) to a VNF instance.
   *
   * @param virtualNetworkFunctionRecord
   * @param dependency
   */
  @Override
  public VirtualNetworkFunctionRecord modify(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFRecordDependency dependency)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  /** This operation allows deploying a new software release to a VNF instance. */
  @Override
  public void upgradeSoftware() {}

  /**
   * This operation allows terminating gracefully or forcefully a previously created VNF instance.
   *
   * @param virtualNetworkFunctionRecord
   */
  @Override
  public VirtualNetworkFunctionRecord terminate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void handleError(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {}

  @Override
  protected void checkEMS(String hostname) {}

  @Override
  protected void checkEmsStarted(String hostname) throws RuntimeException {}

  @Override
  public VirtualNetworkFunctionRecord start(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord stop(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return null;
  }

  @Override
  public VirtualNetworkFunctionRecord startVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {
    return null;
  }

  @Override
  public VirtualNetworkFunctionRecord stopVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {
    return null;
  }

  @Override
  public VirtualNetworkFunctionRecord configure(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord resume(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstance,
      VNFRecordDependency dependency)
      throws Exception {
    return null;
  }

  /**
   * This operation allows providing notifications on state changes of a VNF instance, related to
   * the VNF Lifecycle.
   */
  @Override
  public void NotifyChange() {}
}
