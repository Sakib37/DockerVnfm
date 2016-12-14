package org.openbaton.vnfm;

import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VirtualNetworkFunctionDescriptor;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VNFRecordDependency;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.Script;
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
    permissions = new HashSet<>();
    permissions.add(PosixFilePermission.GROUP_EXECUTE);
    permissions.add(PosixFilePermission.OTHERS_EXECUTE);
    permissions.add(PosixFilePermission.OWNER_EXECUTE);
    permissions.add(PosixFilePermission.OTHERS_READ);
    permissions.add(PosixFilePermission.GROUP_READ);
    permissions.add(PosixFilePermission.OWNER_READ);
    permissions.add(PosixFilePermission.OTHERS_WRITE);
    permissions.add(PosixFilePermission.GROUP_WRITE);
    permissions.add(PosixFilePermission.OWNER_WRITE);
  }

  @Override
  protected synchronized void onAction(NFVMessage message)
      throws NotFoundException, BadFormatException {
    VirtualNetworkFunctionRecord virtualNetworkFunctionRecord = null;
    NFVMessage nfvMessage = null;
    OrVnfmGenericMessage orVnfmGenericMessage = null;
    OrVnfmStartStopMessage orVnfmStartStopMessage = null;
    System.out.println(message.getAction());
    OrVnfmInstantiateMessage orVnfmInstantiateMessage = (OrVnfmInstantiateMessage) message;
    System.out.println(orVnfmInstantiateMessage);
    System.out.println("Virtual Link Record: " + orVnfmInstantiateMessage.getVlrs());
    System.out.println("VNFD : " + orVnfmInstantiateMessage.getVnfd());
    System.out.println("VimInstance : " + orVnfmInstantiateMessage.getVimInstances());
    System.out.println("Keys: " + orVnfmInstantiateMessage.getKeys());
    System.out.println("VLRS : " + orVnfmInstantiateMessage.getVlrs());
    System.out.println("Vnf Package: " + orVnfmInstantiateMessage.getVnfPackage());
    try {
      switch (message.getAction()) {
        case INSTANTIATE:
          log.info(
              "Received NFVO Message: "
                  + "Instantiating VNFD "
                  + ((OrVnfmInstantiateMessage) message).getVnfd().getName());
          //OrVnfmInstantiateMessage orVnfmInstantiateMessage = (OrVnfmInstantiateMessage) message;
          orVnfmInstantiateMessage = (OrVnfmInstantiateMessage) message;
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
          String serverName = orVnfmInstantiateMessage.getVnfd().getName();

          nfvMessage = VnfmUtils.getNfvMessage(Action.INSTANTIATE, virtualNetworkFunctionRecord);
          log.info("Instantiated vnfd " + vnfd.getName());
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
    log.debug("Processing allocation of Resources for vnfr: " + virtualNetworkFunctionRecord);
    /** Allocation of Resources the grant operation is already done before this method */
    System.out.println("VNFR in instantiate method : " + virtualNetworkFunctionRecord);
    System.out.println("Scripts in instantiate method : " + scripts);
    log.debug("Allocated all Resources for vnfr: " + virtualNetworkFunctionRecord);
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
