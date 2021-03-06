package net.bitnine.cloudag.api.oracle.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.ComputeWaiters;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateVnicDetails;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceSourceViaImageDetails;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.model.LaunchInstanceShapeConfigDetails;
import com.oracle.bmc.core.model.NetworkSecurityGroup;
import com.oracle.bmc.core.model.Shape;
import com.oracle.bmc.core.model.VolumeAttachment;
import com.oracle.bmc.core.requests.GetImageRequest;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.GetVolumeAttachmentRequest;
import com.oracle.bmc.core.requests.LaunchInstanceRequest;
import com.oracle.bmc.core.requests.ListShapesRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.requests.TerminateInstanceRequest;
import com.oracle.bmc.core.responses.GetImageResponse;
import com.oracle.bmc.core.responses.GetInstanceResponse;
import com.oracle.bmc.core.responses.GetVnicResponse;
import com.oracle.bmc.core.responses.GetVolumeAttachmentResponse;
import com.oracle.bmc.core.responses.LaunchInstanceResponse;
import com.oracle.bmc.core.responses.ListShapesResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.workrequests.WorkRequestClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@Slf4j
@Service
public class InstanceService {
        private static final String NSG_CIDRBLOCK = "0.0.0.0/0";
        private static final int NSG_CLOUDAG_VIEWER_OPEN_PORT = 4000;
        private static final int NSG_CLOUDAG_DB_OPEN_PORT = 5432;
        // private static final String CLOUDAG_IMAGE_ID =
        // "ocid1.image.oc1.ap-seoul-1.aaaaaaaajknsaiquuovy4kel5xohfgmsnpog7js5dzlqydakwokz65euvhva";

        // ?????? ???????????? ???????????? OCID
        private static final String CLOUDAG_IMAGE_ID = "ocid1.image.oc1.ap-seoul-1.aaaaaaaajknsaiquuovy4kel5xohfgmsnpog7js5dzlqydakwokz65euvhva";

        @Autowired
        private ResourceLoader resourceLoader;

        @Autowired
        AuthentificationProvider authentificationProvider;

        @Autowired
        private IdentityService identityService;

        @Autowired
        private NetworkService networkService;

        @Autowired
        private BlockStorageService blockStorageService;

        @Autowired
        private InstanceAgentCommandService instanceAgentCommandService;

        /**
         * ???????????? ?????? ?????? (????????????, ????????????, ?????????????????? ?????? ?????? ??? ??????)
         * ???????????? ?????? ??????
         * 
         * @param dbname        CloudAG Database ??????
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId ??????????????? OCID
         * @param ocpus         ???????????? OCPU CPU ??????
         * @param memoryInGBs   ???????????? ?????????
         * @param vpusPerGB     ?????? ???????????? ?????? (????????? 10)
         * @param sizeInGBs     ?????? ???????????? ??????
         * @return void
         */
        @Async
        public void createInstance(String dbname, String region, String compartmentId, String ocpus, String memoryInGBs,
                        String vpusPerGB, String sizeInGBs) throws Exception {
                Instance instance = null;
                ComputeClient computeClient = null;
                WorkRequestClient workRequestClient = null;
                try {
                        String availabilityDomain;

                        // Region?????? Availability Domain (????????? ??????) ?????? 
                        availabilityDomain = identityService.getAvailabilityDomains(region, compartmentId).getName();

                        // ????????? ?????? ????????? ???????????? ?????? ???????????????
                        computeClient = new ComputeClient(authentificationProvider.getAuthenticationDetailsProvider());
                        computeClient.setRegion(region);
                        
                        // Virtual Cloud Network (VCN) ??????
                        Map<String, String> vcnMap = networkService.createVirtualNetwork(dbname, region, compartmentId);

                        // ?????? ??????????????? ????????? ?????? ????????? ??????????????? NSG ?????? ??? Rule ?????? (?????? ?????? ??????)
                        NetworkSecurityGroup networkSecurityGroup = networkService.createNetworkSecurityGroup(
                                        networkService.getVirtualNetworkClient(region), compartmentId,
                                        vcnMap.get("vcnId"), dbname);
                        networkService.addNetworkSecurityGroupSecurityRules(
                                        networkService.getVirtualNetworkClient(region),
                                        networkSecurityGroup, NSG_CIDRBLOCK, NSG_CLOUDAG_VIEWER_OPEN_PORT,
                                        NSG_CLOUDAG_DB_OPEN_PORT);

                        // ???????????? ?????? ?????? ??????
                        // ???????????? ?????? ??????
                        String instanceName = dbname + "-instance";

                        // ??????????????? SSH ????????? ?????? Public Key ??????
                        String sshPublicKey = new String(Files.readAllBytes(Paths
                                        .get(resourceLoader.getResource("classpath:id_rsa.pub").getFile().getPath())));
                        Map<String, String> metadata = ImmutableMap.<String, String>builder()
                                        .put("ssh_authorized_keys", sshPublicKey).build();

                        // VNIC ????????? ?????? ?????? ??????
                        CreateVnicDetails createVnicDetails = CreateVnicDetails.builder()
                                        .subnetId(vcnMap.get("subnetId"))
                                        .nsgIds(Arrays.asList(networkSecurityGroup.getId())).build();

                        // ???????????? ???????????? ???????????? ???????????? ????????? ????????? ??????
                        GetImageRequest getImageRequest = GetImageRequest.builder().imageId(CLOUDAG_IMAGE_ID).build();
                        GetImageResponse getImageResponse = computeClient.getImage(getImageRequest);
                        InstanceSourceViaImageDetails instanceSourceViaImageDetails = InstanceSourceViaImageDetails
                                        .builder().imageId(getImageResponse.getImage().getId()).build();

                        // Shape ????????? ?????????
                        ListShapesRequest listShapesRequest = ListShapesRequest.builder().compartmentId(compartmentId)
                                        .availabilityDomain(availabilityDomain).build();
                        ListShapesResponse listShapesResponse = computeClient.listShapes(listShapesRequest);
                        List<Shape> shapes = listShapesResponse.getItems();
                        if (shapes.isEmpty()) {
                                throw new IllegalStateException("No available shape was found.");
                        }
                        List<Shape> vmShapes = shapes.stream()
                                        .filter(shape -> shape.getShape().equals("VM.Standard.E4.Flex"))
                                        .collect(Collectors.toList());
                        if (vmShapes.isEmpty()) {
                                throw new IllegalStateException("No available VM shape was found.");
                        }
                        Shape shape = vmShapes.get(0);

                        // Option
                        // LaunchInstanceAgentConfigDetails launchInstanceAgentConfigDetails =
                        // LaunchInstanceAgentConfigDetails.builder().isMonitoringDisabled(false).build();

                        // ???????????? Shape ?????? ?????? ??????
                        LaunchInstanceShapeConfigDetails launchInstanceShapeConfigDetails = LaunchInstanceShapeConfigDetails
                                        .builder().ocpus(Float.valueOf(ocpus)).memoryInGBs(Float.valueOf(memoryInGBs))
                                        .build();

                        // ???????????? ?????? ??????
                        log.info("???????????? ????????? ?????? ?????? ??????.....");
                        LaunchInstanceDetails launchInstanceDetails = LaunchInstanceDetails.builder()
                                        .availabilityDomain(availabilityDomain).compartmentId(compartmentId)
                                        .displayName(instanceName)
                                        // faultDomain is optional parameter
                                        .faultDomain("FAULT-DOMAIN-1").sourceDetails(instanceSourceViaImageDetails)
                                        .metadata(metadata)
                                        // .extendedMetadata(extendedMetadata)
                                        .shape(shape.getShape()).shapeConfig(launchInstanceShapeConfigDetails)
                                        .createVnicDetails(createVnicDetails)
                                        // agentConfig is an optional parameter
                                        // .agentConfig(launchInstanceAgentConfigDetails)
                                        .definedTags(new HashMap<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>>() {
                                                {
                                                        put("CloudAG-Tags",
                                                                        new HashMap<java.lang.String, java.lang.Object>() {
                                                                                {
                                                                                        put("cloudag-instance", "YES");
                                                                                }
                                                                        });
                                                }
                                        }).build();
                        
                        // ???????????? ?????? ????????? ???????????? ?????? ?????? Work Request Client ??????
                        workRequestClient = WorkRequestClient.builder()
                                        .build(authentificationProvider.getAuthenticationDetailsProvider());
                        workRequestClient.setRegion(region);

                        // ???????????? ?????? ????????? ?????????????????? ?????? Waiter ??????
                        ComputeWaiters computeWaiters = computeClient.newWaiters(workRequestClient);
                        
                        // ???????????? ?????? ?????? ??? ??????
                        LaunchInstanceRequest launchInstanceRequest = LaunchInstanceRequest.builder()
                                        .launchInstanceDetails(launchInstanceDetails).build();
                        LaunchInstanceResponse launchInstanceResponse = computeWaiters
                                        .forLaunchInstance(launchInstanceRequest).execute();
                        
                        // ????????? ???????????? ?????? ?????? ??? ??????
                        GetInstanceRequest getInstanceRequest = GetInstanceRequest.builder()
                                        .instanceId(launchInstanceResponse.getInstance().getId()).build();
                        GetInstanceResponse getInstanceResponse = computeWaiters
                                        .forInstance(getInstanceRequest, Instance.LifecycleState.Running).execute();

                        instance = getInstanceResponse.getInstance();
                        log.info("????????? ???????????? ?????????: " + instance.getId());

                        // ?????? ?????? ?????? ??????
                        String blockVolumeId = blockStorageService.createBlockVolume(dbname, region, compartmentId,
                                        vpusPerGB, sizeInGBs);
                        
                        // ?????? ????????? ??????????????? ?????????
                        String volumeAttachmentId = blockStorageService.attachBlockVolume(computeClient, dbname,
                                        instance.getId(), blockVolumeId);
                        
                        // ?????? ???????????? ?????? ?????? ?????? ??? ??????
                        GetVolumeAttachmentRequest getVolumeAttachmentRequest = GetVolumeAttachmentRequest.builder()
                                        .volumeAttachmentId(volumeAttachmentId).build();
                        GetVolumeAttachmentResponse getVolumeAttachmentResponse = computeWaiters.forVolumeAttachment(
                                        getVolumeAttachmentRequest, VolumeAttachment.LifecycleState.Attached).execute();
                        log.info("?????? ????????? ?????????: " + getVolumeAttachmentResponse.getVolumeAttachment().getId());

                        // ?????? ???????????? ?????? ???????????? ??????
                        instanceAgentCommandService.createAndExecutionInstanceAgentCommand(region, compartmentId, dbname,
                                        instance.getId());
                        
                        log.info("????????????["+instance.getId()+"] ?????? ?????? ??????, CloudAG ???????????? ????????????????????????.");
                } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                } finally {
                        computeClient.close();
                        workRequestClient.close();
                }
        }

        /**
         * ???????????? ?????? ?????? ??????
         * 
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId ??????????????? OCID
         * @param instanceId    ????????? ???????????? OCID
         * @return void
         */
        public Map<String, String> getInstance(String region, String compartmentId, String instanceId)
                        throws Exception {
                ComputeClient computeClient = null;
                VirtualNetworkClient virtualNetworkClient = null;
                Map<String, String> resultMap = new HashMap<String, String>();

                try {
                        // ???????????? ??? ????????????(VNIC??????)??? ?????? ??????????????? ??????
                        computeClient = new ComputeClient(authentificationProvider.getAuthenticationDetailsProvider());
                        virtualNetworkClient = new VirtualNetworkClient(
                                        authentificationProvider.getAuthenticationDetailsProvider());

                        // ?????? ??????
                        computeClient.setRegion(region);
                        virtualNetworkClient.setRegion(region);

                        // ???????????? ?????? ?????? ??????
                        GetInstanceRequest getInstanceRequest = GetInstanceRequest.builder().instanceId(instanceId)
                                        .build();

                        // ???????????? ?????? ?????? ??????
                        GetInstanceResponse getInstanceResponse = computeClient.getInstance(getInstanceRequest);

                        // IP ?????? ????????? ?????? ??????????????? Attached Vnic ?????? ??????
                        ListVnicAttachmentsRequest listVnicAttachmentsRequest = ListVnicAttachmentsRequest.builder()
                                        .compartmentId(compartmentId).instanceId(instanceId).build();

                        // IP ?????? ????????? ?????? ??????????????? Attached Vnic ?????? ??????
                        ListVnicAttachmentsResponse listVnicAttachmentsResponse = computeClient
                                        .listVnicAttachments(listVnicAttachmentsRequest);

                        // Vnic ?????? ??????
                        GetVnicRequest getVnicRequest = GetVnicRequest.builder()
                                        .vnicId(listVnicAttachmentsResponse.getItems().get(0).getVnicId()).build();
                        // Vnic ?????? ??????
                        GetVnicResponse getVnicResponse = virtualNetworkClient.getVnic(getVnicRequest);

                        // ???????????? ????????? Vnic (IP ??????) ?????? ??????
                        resultMap.put("name", getInstanceResponse.getInstance().getDisplayName());
                        resultMap.put("id", getInstanceResponse.getInstance().getId());
                        resultMap.put("region", getInstanceResponse.getInstance().getRegion());
                        resultMap.put("shape", getInstanceResponse.getInstance().getShape());
                        resultMap.put("timeCreated", getInstanceResponse.getInstance().getTimeCreated().toString());
                        resultMap.put("ocpus",
                                        getInstanceResponse.getInstance().getShapeConfig().getOcpus().toString());
                        resultMap.put("memoryInGBs",
                                        getInstanceResponse.getInstance().getShapeConfig().getMemoryInGBs().toString());
                        resultMap.put("memoryInGBs",
                                        getInstanceResponse.getInstance().getShapeConfig().getMemoryInGBs().toString());
                        resultMap.put("publicIp", getVnicResponse.getVnic().getPublicIp());
                        resultMap.put("privateIp", getVnicResponse.getVnic().getPrivateIp());

                } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                } finally {
                        computeClient.close();
                        virtualNetworkClient.close();
                }
                return resultMap;
        }

        /**
         * ??????????????? ????????? ?????? ?????? ??????????????? (????????????, ????????????, ????????????)
         * 
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId ??????????????? OCID
         * @param volumeId      ???????????? OCID
         * @param vcnId    VCN OCID
         * @return void
         */
        @Async
        public void terminateAll(String region, String compartmentId, String instanceId, String volumeId, String vcnId)
                        throws IOException {
                ComputeClient computeClient = null;
                try {   
                        // ???????????? ????????? ?????? ??????????????? ??????
                        computeClient = new ComputeClient(authentificationProvider.getAuthenticationDetailsProvider());
                        computeClient.setRegion(region);

                        // ???????????? ??????????????? ??????
                        TerminateInstanceRequest terminateInstanceRequest = TerminateInstanceRequest.builder()
                                        .instanceId(instanceId).preserveBootVolume(false).build();

                        // ???????????? ??????????????? ??????
                        computeClient.terminateInstance(terminateInstanceRequest);
                        GetInstanceRequest getInstanceRequest = GetInstanceRequest.builder().instanceId(instanceId).build();
                        GetInstanceResponse getInstanceResponse = computeClient.getWaiters().forInstance(getInstanceRequest, Instance.LifecycleState.Terminated).execute();
                        log.info("????????????["+getInstanceResponse.getInstance().getId()+"]??? ?????????????????????.");

                        // ?????? ?????? ???????????????
                        if(!volumeId.isBlank()) {
                                String terminatedVolumeId = blockStorageService.terminateBlockVolume(region, volumeId);
                                log.info("??????[" + terminatedVolumeId + "]??? ?????????????????????.");
                        }

                        // VCN ???????????????
                        if(!vcnId.isBlank()) {
                                String terminatedVcnId = networkService.terminateAllVirtualNetwork(region, compartmentId, vcnId);
                                log.info("["+ terminatedVcnId + "] VCN??? ?????????????????????.");
                        }

                        log.info("??????????????? ????????? ?????? ????????? ?????????????????????.");
                } catch (Exception e) {
                        e.printStackTrace();
                } finally {
                        computeClient.close();
                }
        }
}
