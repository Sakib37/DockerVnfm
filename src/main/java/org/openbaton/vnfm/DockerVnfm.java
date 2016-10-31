package org.openbaton.vnfm;

/**
 * Created by sakib on 10/28/16.
 */
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VNFDConnectionPoint;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VNFRecordDependency;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.catalogue.security.Key;
import org.openbaton.common.vnfm_sdk.VnfmHelper;
import org.openbaton.common.vnfm_sdk.amqp.AbstractVnfmSpringAmqp;
import org.openbaton.exceptions.VimException;
import org.openbaton.nfvo.vim_interfaces.resource_management.ResourceManagement;
import org.openbaton.plugin.utils.PluginStartup;
import org.openbaton.plugin.utils.RabbitPluginBroker;
import org.openbaton.vim.drivers.VimDriverCaller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;



import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class DockerVnfm extends AbstractVnfmSpringAmqp {

    @Autowired
    private VnfmHelper vnfmHelper;

    @Autowired
    private ConfigurableApplicationContext context;

    private ResourceManagement resourceManagement;

    private VimDriverCaller client;

    private DockerVimCaller dockerVimClient;

    @Override
    protected void setup() {
        super.setup();
        try {
            //Start all the plugins that are located in ./plugins
            PluginStartup.startPluginRecursive("./plugins", true, "localhost", "5672", 15, "admin", "openbaton", "15672", "/var/log/openbaton/DockerVnfm" );
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Fetching the OpenstackVim using the openstack-plugin
        //resourceManagement = (ResourceManagement) context.getBean("openstackVIM", 19345, "15672");
        //Using the docker-plugin directly
        client = (VimDriverCaller) ((RabbitPluginBroker) context.getBean("rabbitPluginBroker")).getVimDriverCaller("localhost", "admin", "openbaton", 5672, "docker", "docker", "15672");
        //dockerVimClient = (DockerVimCaller) ((DockerRabbitPluginBroker)context.getBean("dockerRabbitPluginBroker")).getVimDriverCaller("localhost", "admin", "openbaton", 5672, "docker", "docker", "15672");
    }

    public static void main(String[] args){
        SpringApplication.run(DockerVnfm.class);
    }

    /**
     * This operation allows creating a VNF instance.
     *
     * @param virtualNetworkFunctionRecord
     * @param scripts
     * @param vimInstances
     */
    @Override
    public VirtualNetworkFunctionRecord instantiate(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, Object scripts, Map<String, Collection<VimInstance>> vimInstances) throws Exception {
        log.debug("Processing allocation of Resources for vnfr: " + virtualNetworkFunctionRecord);
        /**
         * Allocation of Resources
         *  the grant operation is already done before this method
         */
        System.out.println("In instantiate method of DockerVnfm");
        log.debug("Processing allocation of Recources for vnfr: " + virtualNetworkFunctionRecord);
        for (VirtualDeploymentUnit vdu : virtualNetworkFunctionRecord.getVdu()) {
            System.out.println("VDU : " + vdu);
            VimInstance vimInstance = vimInstances.get(vdu.getParent_vdu()).iterator().next();
            List<Future<VNFCInstance>> vnfcInstancesFuturePerVDU = new ArrayList<>();
            log.debug("Creating " + vdu.getVnfc().size() + " VMs");
            for (VNFComponent vnfComponent : vdu.getVnfc()) {
                Map<String, String> floatingIps = new HashMap<>();
                Set<Key> keys = new HashSet<>();
                for (VNFDConnectionPoint connectionPoint : vnfComponent.getConnection_point()) {
                    if (connectionPoint.getFloatingIp() != null && !connectionPoint.getFloatingIp().equals("")) {
                        floatingIps.put(connectionPoint.getVirtual_link_reference(), connectionPoint.getFloatingIp());
                    }
                }
                Future<VNFCInstance> allocate = null;
                try {
                    allocate = resourceManagement.allocate(vimInstance, vdu, virtualNetworkFunctionRecord, vnfComponent, "", floatingIps, keys);
                    vnfcInstancesFuturePerVDU.add(allocate);
                } catch (VimException e) {
                    log.error(e.getMessage());
                    if (log.isDebugEnabled())
                        log.error(e.getMessage(), e);
                }
            }
            //Print ids of deployed VNFCInstances
            for (Future<VNFCInstance> vnfcInstanceFuture : vnfcInstancesFuturePerVDU) {
                try {
                    VNFCInstance vnfcInstance = vnfcInstanceFuture.get();
                    vdu.getVnfc_instance().add(vnfcInstance);
                    log.debug("Created VNFCInstance with id: " + vnfcInstance);
                } catch (InterruptedException e) {
                    log.error(e.getMessage());
                    if (log.isDebugEnabled())
                        log.error(e.getMessage(), e);
                    //throw new RuntimeException(e.getMessage(), e);
                } catch (ExecutionException e) {
                    log.error(e.getMessage());
                    if (log.isDebugEnabled())
                        log.error(e.getMessage(), e);
                    //throw new RuntimeException(e.getMessage(), e);
                }
            }
        }
        log.debug("Allocated all Resources for vnfr: " + virtualNetworkFunctionRecord);
        return virtualNetworkFunctionRecord;
    }

    /**
     * This operation allows retrieving
     * VNF instance state and attributes.
     */
    @Override
    public void query() {

    }

    /**
     * This operation allows scaling
     * (out/in, up/down) a VNF instance.
     */
    @Override
    public VirtualNetworkFunctionRecord scale(Action scaleInOrOut, VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance component, Object scripts, VNFRecordDependency dependency) throws Exception {
        return virtualNetworkFunctionRecord;
    }

    /**
     * This operation allows verifying if
     * the VNF instantiation is possible.
     */
    @Override
    public void checkInstantiationFeasibility() {

    }

    /**
     * This operation is called when one the VNFs fails
     */
    @Override
    public VirtualNetworkFunctionRecord heal(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance component, String cause) throws Exception {
        return virtualNetworkFunctionRecord;
    }

    /**
     * This operation allows applying a minor/limited
     * software update (e.g. patch) to a VNF instance.
     */
    @Override
    public void updateSoftware() {

    }

    /**
     * This operation allows making structural changes
     * (e.g. configuration, topology, behavior,
     * redundancy model) to a VNF instance.
     *
     * @param virtualNetworkFunctionRecord
     * @param dependency
     */
    @Override
    public VirtualNetworkFunctionRecord modify(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFRecordDependency dependency) throws Exception {
        return virtualNetworkFunctionRecord;
    }

    /**
     * This operation allows deploying a new
     * software release to a VNF instance.
     */
    @Override
    public void upgradeSoftware() {

    }

    /**
     * This operation allows terminating gracefully
     * or forcefully a previously created VNF instance.
     * @param virtualNetworkFunctionRecord
     */
    @Override
    public VirtualNetworkFunctionRecord terminate(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
        return virtualNetworkFunctionRecord;
    }

    @Override
    public void handleError(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {

    }

    @Override
    protected void checkEMS(String hostname) {

    }

    @Override
    protected void checkEmsStarted(String hostname) throws RuntimeException {

    }

    @Override
    public VirtualNetworkFunctionRecord start(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
        return virtualNetworkFunctionRecord;
    }

    @Override
    public VirtualNetworkFunctionRecord configure(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
        return virtualNetworkFunctionRecord;
    }

    /**
     * This operation allows providing notifications on state changes
     * of a VNF instance, related to the VNF Lifecycle.
     */
    @Override
    public void NotifyChange() {

    }
}
