package org.openbaton.vnfm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openbaton.catalogue.mano.common.Event;
import org.openbaton.catalogue.mano.common.Ip;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.FileSystemUtils;

/** Created by sakib on 10/28/16. */
public class DockerVnfm extends AbstractVnfmSpringAmqp {
  @Autowired private VnfmHelper vnfmHelper;

  @Autowired private ConfigurableApplicationContext context;

  private ResourceManagement resourceManagement;

  private Set<PosixFilePermission> permissions;

  private DockerVimCaller client;

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
    //client = (DockerVimCaller) ((RabbitPluginBroker) context.getBean("rabbitPluginBroker")).getVimDriverCaller("localhost", "admin", "openbaton", 5672, "docker", "docker", "15672");
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
  }

  @Override
  protected synchronized void onAction(NFVMessage message)
      throws NotFoundException, BadFormatException {
    VirtualNetworkFunctionRecord virtualNetworkFunctionRecord = null;
    VimInstance dockerVimInstance = null;
    NFVMessage nfvMessage = null;
    OrVnfmGenericMessage orVnfmGenericMessage = null;
    OrVnfmStartStopMessage orVnfmStartStopMessage = null;

    System.out.println("Message Action: " + message.getAction());
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

          for (String s : vimInstances.keySet()) {
            for (int i = 0; i < vimInstances.get(s).toArray().length; i++) {
              if (((VimInstance) vimInstances.get(s).toArray()[i]).getName().equals("docker")) {
                dockerVimInstance = (VimInstance) vimInstances.get(s).toArray()[i];
                break;
              }
            }
          }
          if (dockerVimInstance != null) {
            virtualNetworkFunctionRecord =
                createVirtualNetworkFunctionRecord(
                    orVnfmInstantiateMessage.getVnfd(),
                    orVnfmInstantiateMessage.getVnfdf().getFlavour_key(),
                    orVnfmInstantiateMessage.getVlrs(),
                    orVnfmInstantiateMessage.getExtension(),
                    vimInstances);
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

            String serverName = vnfd.getName();
            VirtualDeploymentUnit vdu = (VirtualDeploymentUnit) vnfd.getVdu().iterator().next();
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
            List<String> environemntVariables = getEnvironmentVariables(configParameters);

            log.info("Creating server for VNFD " + serverName);
            Server server =
                client.launchInstance(
                    dockerVimInstance,
                    serverName,
                    serverImage,
                    portsToExpose,
                    environemntVariables);
            log.info("Server for VNFD " + serverName + " is successfully created");

            // Copying scripts to server
            log.info("Copying scripts to server " + serverName);
            File existingDuplicateDirectory =
                new File("/tmp/openbaton/dockerVNFM" + "/" + serverName);
            FileSystemUtils.deleteRecursively(existingDuplicateDirectory);

            prepareScript(orVnfmInstantiateMessage.getVnfPackage(), serverName);
            try {
              client.copyArchiveToContainer(dockerVimInstance,
                      server.getId(),
                      "/tmp/openbaton/dockerVNFM" + "/" + serverName + "/" + "scripts");
              log.info("Successfully copied scripts to '/scripts' in server " + serverName);
            } catch (Exception e) {
              log.info("Failed to copy scripts to server" + serverName);
              log.debug(e.toString());
            }
            FileSystemUtils.deleteRecursively(existingDuplicateDirectory);

            // Adding server to the networks
            log.info("Adding Server " + serverName + " with virtual links");
            Iterator iterator = orVnfmInstantiateMessage.getVnfd().getVirtual_link().iterator();
            while (iterator.hasNext()) {
              String netName = ((InternalVirtualLink) iterator.next()).getName();
              try {
                log.info("Creating network '" + netName + "'");
                client.createDockerNetwork(dockerVimInstance, netName);
              } catch (Exception ignore) {
                log.info("Network " + netName + " is already available");
              }
              client.connectContainerToNetwork(dockerVimInstance, server.getId(), netName);
            }
            log.info("Successfully connected server " + serverName + " with all virtual links");

            log.info("Executing scripts for Lifecycle event INSTANTIATE");
            executeScriptsForEvent(virtualNetworkFunctionRecord, Event.INSTANTIATE);

          }
          nfvMessage = VnfmUtils.getNfvMessage(Action.INSTANTIATE, virtualNetworkFunctionRecord);
          log.info("Instantiated vnfd " + vnfd.getName());
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
          nsrId = orVnfmStartStopMessage.getVirtualNetworkFunctionRecord().getParent_ns_id();
          System.out.println("VNFR Dependency : " + vnfrDependency);

          for (Map.Entry<String, DependencyParameters> entry :
              vnfrDependency.getParameters().entrySet()) {
            String sourceType = entry.getKey();
            System.out.println("SourceType of vnfrDependency : " + sourceType);
            String sourceName = "";
            for (Map.Entry<String, String> nameTypeEntry : vnfrDependency.getIdType().entrySet()) {
              if (nameTypeEntry.getValue().equals(sourceType)) sourceName = nameTypeEntry.getKey();
            }
            System.out.println("SourceName : " + sourceName);
            DependencyParameters dependencyParameters = entry.getValue();
            List<String> parameters = new LinkedList<>();
            for (Map.Entry<String, String> pe : dependencyParameters.getParameters().entrySet()) {
              parameters.add(pe.getKey());
            }
            Map<String, List<String>> sourceParams = new HashMap<>();
            sourceParams.put(sourceName, parameters);
            Map<String, Map<String, List<String>>> targetSourceParams = new HashMap<>();
            targetSourceParams.put(virtualNetworkFunctionRecord.getName(), sourceParams);

            nfvMessage =
                VnfmUtils.getNfvMessage(
                    Action.MODIFY,
                    this.modify(orVnfmGenericMessage.getVnfr(), orVnfmGenericMessage.getVnfrd()));
            log.info("After modify of " + virtualNetworkFunctionRecord.getName() + ":\n");
            break;
          }
        case START:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFR "
                  + ((OrVnfmStartStopMessage) message).getVirtualNetworkFunctionRecord().getName()
                  + " and following vnfrDep: \n"
                  + ((OrVnfmStartStopMessage) message).getVnfrd());
          orVnfmStartStopMessage = (OrVnfmStartStopMessage) message;
          virtualNetworkFunctionRecord = orVnfmStartStopMessage.getVirtualNetworkFunctionRecord();
          nsrId = orVnfmStartStopMessage.getVirtualNetworkFunctionRecord().getParent_ns_id();

          log.info("After start of " + virtualNetworkFunctionRecord.getName() + ":\n");

          nfvMessage =
              VnfmUtils.getNfvMessage(
                  Action.START, start(orVnfmStartStopMessage.getVirtualNetworkFunctionRecord()));
          break;
        case RELEASE_RESOURCES:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFR "
                  + ((OrVnfmGenericMessage) message).getVnfr().getName());
          log.info("############   IN RELEASE CASE ##############");

          orVnfmGenericMessage = (OrVnfmGenericMessage) message;
          VirtualNetworkFunctionRecord vnfr = orVnfmGenericMessage.getVnfr();
          System.out.println("Generic Message : " + orVnfmGenericMessage);
          System.out.println("VNFR : " + virtualNetworkFunctionRecord);

          nsrId = orVnfmGenericMessage.getVnfr().getParent_ns_id();
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

  public Iterable<String> executeScriptsForEvent(
          VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, Event event)
          throws Exception { //TODO make it parallel
    Map<String, String> env = getMap(virtualNetworkFunctionRecord);
    Collection<String> res = new ArrayList<>();
    LifecycleEvent le =
            VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);

    if (le != null) {
      log.trace(
              "The number of scripts for "
                      + virtualNetworkFunctionRecord.getName()
                      + " are: "
                      + le.getLifecycle_events());
      for (String script : le.getLifecycle_events()) {
        System.out.println("Script for event " + event + script);
        log.info(
                "Sending script: "
                        + script
                        + " to VirtualNetworkFunctionRecord: "
                        + virtualNetworkFunctionRecord.getName());
        for (VirtualDeploymentUnit vdu : virtualNetworkFunctionRecord.getVdu()) {
          for (VNFCInstance vnfcInstance : vdu.getVnfc_instance()) {

            Map<String, String> tempEnv = new HashMap<>();
            for (Ip ip : vnfcInstance.getIps()) {
              log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
              tempEnv.put(ip.getNetName(), ip.getIp());
            }
            log.debug("adding floatingIp: " + vnfcInstance.getFloatingIps());
            for (Ip fip : vnfcInstance.getFloatingIps()) {
              tempEnv.put(fip.getNetName() + "_floatingIp", fip.getIp());
            }

            tempEnv.put("hostname", vnfcInstance.getHostname());
            tempEnv = modifyUnsafeEnvVarNames(tempEnv);
            env.putAll(tempEnv);
            log.info("Environment Variables are: " + env);


            for (String key : tempEnv.keySet()) {
              env.remove(key);
            }
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
      System.out.println("Exit status in get script : " + exitStatus);
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

  private List<String> getExposedPorts(Map<String, String> configParameters) {
    List<String> exposedPorts = new ArrayList<>();
    for (String s : configParameters.keySet()) {
      if (s.toLowerCase().indexOf("port") >= 0) {
        exposedPorts.add(configParameters.get(s));
      }
    }
    return exposedPorts;
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
