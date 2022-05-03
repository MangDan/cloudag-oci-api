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

        // 기본 인스턴스 이미지의 OCID
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
         * 인스턴스 생성 작업 (네트워크, 인스턴스, 스토리지까지 일괄 생성 및 구성)
         * 비동기로 작업 수행
         * 
         * @param dbname        CloudAG Database 이름
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId 컴파트먼트 OCID
         * @param ocpus         할당되는 OCPU CPU 개수
         * @param memoryInGBs   할당되는 메모리
         * @param vpusPerGB     블록 스토리지 성능 (기본값 10)
         * @param sizeInGBs     블록 스토리지 용량
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

                        // Region내의 Availability Domain (데이터 센터) 이름 
                        availabilityDomain = identityService.getAvailabilityDomains(region, compartmentId).getName();

                        // 컴퓨트 관련 작업을 수행하기 위한 클라이언트
                        computeClient = new ComputeClient(authentificationProvider.getAuthenticationDetailsProvider());
                        computeClient.setRegion(region);
                        
                        // Virtual Cloud Network (VCN) 생성
                        Map<String, String> vcnMap = networkService.createVirtualNetwork(dbname, region, compartmentId);

                        // 해당 인스턴스에 대해서 특정 포트만 오픈하도록 NSG 생성 후 Rule 추가 (오픝 포트 추가)
                        NetworkSecurityGroup networkSecurityGroup = networkService.createNetworkSecurityGroup(
                                        networkService.getVirtualNetworkClient(region), compartmentId,
                                        vcnMap.get("vcnId"), dbname);
                        networkService.addNetworkSecurityGroupSecurityRules(
                                        networkService.getVirtualNetworkClient(region),
                                        networkSecurityGroup, NSG_CIDRBLOCK, NSG_CLOUDAG_VIEWER_OPEN_PORT,
                                        NSG_CLOUDAG_DB_OPEN_PORT);

                        // 인스턴스 생성 작업 시작
                        // 인스턴스 이름 지정
                        String instanceName = dbname + "-instance";

                        // 인스턴스에 SSH 접속을 위한 Public Key 지정
                        String sshPublicKey = new String(Files.readAllBytes(Paths
                                        .get(resourceLoader.getResource("classpath:id_rsa.pub").getFile().getPath())));
                        Map<String, String> metadata = ImmutableMap.<String, String>builder()
                                        .put("ssh_authorized_keys", sshPublicKey).build();

                        // VNIC 생성을 위한 기본 정보
                        CreateVnicDetails createVnicDetails = CreateVnicDetails.builder()
                                        .subnetId(vcnMap.get("subnetId"))
                                        .nsgIds(Arrays.asList(networkSecurityGroup.getId())).build();

                        // 인스턴스 이미지를 가져와서 인스턴스 소스로 이미지 할당
                        GetImageRequest getImageRequest = GetImageRequest.builder().imageId(CLOUDAG_IMAGE_ID).build();
                        GetImageResponse getImageResponse = computeClient.getImage(getImageRequest);
                        InstanceSourceViaImageDetails instanceSourceViaImageDetails = InstanceSourceViaImageDetails
                                        .builder().imageId(getImageResponse.getImage().getId()).build();

                        // Shape 정보를 가져움
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

                        // 인스턴스 Shape 구성 정보 설정
                        LaunchInstanceShapeConfigDetails launchInstanceShapeConfigDetails = LaunchInstanceShapeConfigDetails
                                        .builder().ocpus(Float.valueOf(ocpus)).memoryInGBs(Float.valueOf(memoryInGBs))
                                        .build();

                        // 인스턴스 생성 시작
                        log.info("인스턴스 생성을 위한 기본 생성.....");
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
                        
                        // 인스턴스 진행 상황을 모니터링 하기 위한 Work Request Client 생성
                        workRequestClient = WorkRequestClient.builder()
                                        .build(authentificationProvider.getAuthenticationDetailsProvider());
                        workRequestClient.setRegion(region);

                        // 인스턴스 진행 상황을 모니터링하기 위한 Waiter 생성
                        ComputeWaiters computeWaiters = computeClient.newWaiters(workRequestClient);
                        
                        // 인스턴스 생성 요청 및 응답
                        LaunchInstanceRequest launchInstanceRequest = LaunchInstanceRequest.builder()
                                        .launchInstanceDetails(launchInstanceDetails).build();
                        LaunchInstanceResponse launchInstanceResponse = computeWaiters
                                        .forLaunchInstance(launchInstanceRequest).execute();
                        
                        // 생성된 인스턴스 정보 요청 및 응답
                        GetInstanceRequest getInstanceRequest = GetInstanceRequest.builder()
                                        .instanceId(launchInstanceResponse.getInstance().getId()).build();
                        GetInstanceResponse getInstanceResponse = computeWaiters
                                        .forInstance(getInstanceRequest, Instance.LifecycleState.Running).execute();

                        instance = getInstanceResponse.getInstance();
                        log.info("생성된 인스턴스 아이디: " + instance.getId());

                        // 블록 볼륨 생성 작업
                        String blockVolumeId = blockStorageService.createBlockVolume(dbname, region, compartmentId,
                                        vpusPerGB, sizeInGBs);
                        
                        // 블록 볼륨을 인스턴스에 어태치
                        String volumeAttachmentId = blockStorageService.attachBlockVolume(computeClient, dbname,
                                        instance.getId(), blockVolumeId);
                        
                        // 볼륨 어태치에 대한 정보 요청 및 응답
                        GetVolumeAttachmentRequest getVolumeAttachmentRequest = GetVolumeAttachmentRequest.builder()
                                        .volumeAttachmentId(volumeAttachmentId).build();
                        GetVolumeAttachmentResponse getVolumeAttachmentResponse = computeWaiters.forVolumeAttachment(
                                        getVolumeAttachmentRequest, VolumeAttachment.LifecycleState.Attached).execute();
                        log.info("볼륨 어태치 아이디: " + getVolumeAttachmentResponse.getVolumeAttachment().getId());

                        // 볼륨 마운트를 위한 런커맨드 실행
                        instanceAgentCommandService.createAndExecutionInstanceAgentCommand(region, compartmentId, dbname,
                                        instance.getId());
                        
                        log.info("인스턴스["+instance.getId()+"] 생성 작업 완료, CloudAG 서비스가 활성화되었습니다.");
                } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                } finally {
                        computeClient.close();
                        workRequestClient.close();
                }
        }

        /**
         * 인스턴스 상세 정보 반환
         * 
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId 컴파트먼트 OCID
         * @param instanceId    조회할 인스턴스 OCID
         * @return void
         */
        public Map<String, String> getInstance(String region, String compartmentId, String instanceId)
                        throws Exception {
                ComputeClient computeClient = null;
                VirtualNetworkClient virtualNetworkClient = null;
                Map<String, String> resultMap = new HashMap<String, String>();

                try {
                        // 인스턴스 및 네트워크(VNIC관련)을 위한 클라이언트 생성
                        computeClient = new ComputeClient(authentificationProvider.getAuthenticationDetailsProvider());
                        virtualNetworkClient = new VirtualNetworkClient(
                                        authentificationProvider.getAuthenticationDetailsProvider());

                        // 리전 설정
                        computeClient.setRegion(region);
                        virtualNetworkClient.setRegion(region);

                        // 인스턴스 상세 정보 요청
                        GetInstanceRequest getInstanceRequest = GetInstanceRequest.builder().instanceId(instanceId)
                                        .build();

                        // 인스턴스 상세 정보 응답
                        GetInstanceResponse getInstanceResponse = computeClient.getInstance(getInstanceRequest);

                        // IP 정보 획득을 위해 인스턴스에 Attached Vnic 정보 요청
                        ListVnicAttachmentsRequest listVnicAttachmentsRequest = ListVnicAttachmentsRequest.builder()
                                        .compartmentId(compartmentId).instanceId(instanceId).build();

                        // IP 정보 획득을 위해 인스턴스에 Attached Vnic 정보 응답
                        ListVnicAttachmentsResponse listVnicAttachmentsResponse = computeClient
                                        .listVnicAttachments(listVnicAttachmentsRequest);

                        // Vnic 정보 요청
                        GetVnicRequest getVnicRequest = GetVnicRequest.builder()
                                        .vnicId(listVnicAttachmentsResponse.getItems().get(0).getVnicId()).build();
                        // Vnic 정보 응답
                        GetVnicResponse getVnicResponse = virtualNetworkClient.getVnic(getVnicRequest);

                        // 인스턴스 정보와 Vnic (IP 정보) 정보 반환
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
         * 인스턴스와 관련된 모든 자원 터미네이트 (인스턴스, 스토리지, 네트워크)
         * 
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId 컴파트먼트 OCID
         * @param volumeId      블록볼륨 OCID
         * @param vcnId    VCN OCID
         * @return void
         */
        @Async
        public void terminateAll(String region, String compartmentId, String instanceId, String volumeId, String vcnId)
                        throws IOException {
                ComputeClient computeClient = null;
                try {   
                        // 인스턴스 작업을 위한 클라이언트 생성
                        computeClient = new ComputeClient(authentificationProvider.getAuthenticationDetailsProvider());
                        computeClient.setRegion(region);

                        // 인스턴스 터미네이터 요청
                        TerminateInstanceRequest terminateInstanceRequest = TerminateInstanceRequest.builder()
                                        .instanceId(instanceId).preserveBootVolume(false).build();

                        // 인스턴스 터미네이트 수행
                        computeClient.terminateInstance(terminateInstanceRequest);
                        GetInstanceRequest getInstanceRequest = GetInstanceRequest.builder().instanceId(instanceId).build();
                        GetInstanceResponse getInstanceResponse = computeClient.getWaiters().forInstance(getInstanceRequest, Instance.LifecycleState.Terminated).execute();
                        log.info("인스턴스["+getInstanceResponse.getInstance().getId()+"]가 삭제되었습니다.");

                        // 블록 볼륨 터미네이트
                        if(!volumeId.isBlank()) {
                                String terminatedVolumeId = blockStorageService.terminateBlockVolume(region, volumeId);
                                log.info("볼륨[" + terminatedVolumeId + "]이 삭제되었습니다.");
                        }

                        // VCN 터미네이트
                        if(!vcnId.isBlank()) {
                                String terminatedVcnId = networkService.terminateAllVirtualNetwork(region, compartmentId, vcnId);
                                log.info("["+ terminatedVcnId + "] VCN이 삭제되었습니다.");
                        }

                        log.info("인스턴스와 관련된 모든 자원이 삭제되었습니다.");
                } catch (Exception e) {
                        e.printStackTrace();
                } finally {
                        computeClient.close();
                }
        }
}
