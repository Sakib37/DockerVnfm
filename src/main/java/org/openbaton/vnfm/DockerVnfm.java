package org.openbaton.vnfm;

/**
 * Created by sakib on 10/28/16.
 */

import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VNFRecordDependency;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.common.vnfm_sdk.VnfmHelper;
import org.openbaton.common.vnfm_sdk.amqp.AbstractVnfmSpringAmqp;
import org.openbaton.nfvo.vim_interfaces.resource_management.ResourceManagement;
import org.openbaton.plugin.utils.PluginStartup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;



import java.io.IOException;
import java.util.Collection;
import java.util.Map;


@ComponentScan
public class DockerVnfm extends AbstractVnfmSpringAmqp {

    @Autowired
    private VnfmHelper vnfmHelper;

    @Autowired
    private ConfigurableApplicationContext context;

    private ResourceManagement resourceManagement;

    private DockerVimCaller client;

    @Override
    protected void setup() {
        super.setup();
        try {
            //Start all the plugins that are located in ./plugins
            PluginStartup.startPluginRecursive("./plugins", true, "localhost", "5672", 15, "admin", "openbaton", "15672", "/var/log/openbaton/DockerVnfm" );
        } catch (IOException e) {
            e.printStackTrace();
        }

        //Using the docker-plugin directly
        //client = (DockerVimCaller) ((RabbitPluginBroker) context.getBean("rabbitPluginBroker")).getVimDriverCaller("localhost", "admin", "openbaton", 5672, "docker", "docker", "15672");
        client = (DockerVimCaller) ((DockerRabbitPluginBroker)context.getBean("dockerRabbitPluginBroker")).getDockerVimCaller("localhost", "admin", "openbaton", 5672, "docker", "docker", "15672");
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
        log.info("Virtual Network Function Record in Instantiate: ", virtualNetworkFunctionRecord);
        log.debug("Processing allocation of Resources for vnfr: " + virtualNetworkFunctionRecord);
        /**
         * Allocation of Resources
         *  the grant operation is already done before this method
         */
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
