package net.bitnine.cloudag.api.oracle.service;

import java.io.IOException;

import com.oracle.bmc.Region;
import com.oracle.bmc.core.BlockstorageClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.AttachParavirtualizedVolumeDetails;
import com.oracle.bmc.core.model.AttachVolumeDetails;
import com.oracle.bmc.core.model.CreateVolumeDetails;
import com.oracle.bmc.core.model.Volume;
import com.oracle.bmc.core.requests.AttachVolumeRequest;
import com.oracle.bmc.core.requests.CreateVolumeRequest;
import com.oracle.bmc.core.requests.DeleteVolumeRequest;
import com.oracle.bmc.core.requests.GetVolumeRequest;
import com.oracle.bmc.core.responses.AttachVolumeResponse;
import com.oracle.bmc.core.responses.CreateVolumeResponse;
import com.oracle.bmc.core.responses.GetVolumeResponse;
import com.oracle.bmc.identity.model.AvailabilityDomain;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@Slf4j
@Service
public class BlockStorageService {
    @Autowired
    AuthentificationProvider authentificationProvider;

    @Autowired
    IdentityService identityService;

    /**
    * Block Volume Storage 관련 작업 수행을 위한 클라이언트
    * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm) 
    * @return BlockstorageClient Block Volume Storage 클라이언트 객체
    */
    public BlockstorageClient getBlockstorageClient(String region) throws IOException {
        BlockstorageClient blockstorageClient = BlockstorageClient.builder()
                .build(authentificationProvider.getAuthenticationDetailsProvider());
        
        // Client 사용을 위한 리전 설정
        blockstorageClient.setRegion(Region.valueOf(region));

        return blockstorageClient;
    }

    /**
    * Block Volume Storage 를 신규로 생성
    * @param dbname CloudAG DB명
    * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
    * @param compartmentId 블록 스토리지를 생성할 컴파트먼트
    * @param vpusPerGB 블록스토리지 성능값 (기본 10)
    * @param sizeInGBs 블록스토리지 용량
    * @return String 생성된 블록스토리지의 OCID 값
    */
    public String createBlockVolume(String dbname, String region, String compartmentId, String vpusPerGB,
            String sizeInGBs) throws Exception {
        BlockstorageClient blockstorageClient = null;
        GetVolumeResponse getVolumeResponse = null;

        // OCI에 생성되는 블록 스토리지 이름
        String blockVolumeName = dbname + "-block-volume";

        try {
            // 블록스토리지 관련 작업을 위한 클라이언트
            blockstorageClient = getBlockstorageClient(region);

            // 리전내의 도메인
            AvailabilityDomain availabilityDomain = identityService.getAvailabilityDomains(region, compartmentId);

            // 볼륨 생성을 위해 필요한 기본 정보를 담고 있는 객체
            // 컴파트먼트, 스토리지이름, 성능(vpu), 용량 설정
            CreateVolumeDetails createVolumeDetails = CreateVolumeDetails.builder()
                    .availabilityDomain(availabilityDomain.getName()).compartmentId(compartmentId)
                    .displayName(blockVolumeName).vpusPerGB(Long.parseLong(vpusPerGB))
                    .sizeInGBs(Long.parseLong(sizeInGBs)).isAutoTuneEnabled(false).build();

            // 볼륨 생성 요청
            CreateVolumeRequest createVolumeRequest = CreateVolumeRequest.builder()
                    .createVolumeDetails(createVolumeDetails).build();

            // 생성된 볼륨에 대한 정보 반환
            CreateVolumeResponse createVolumeResponse = blockstorageClient.createVolume(createVolumeRequest);
            log.info("Block volume Id:" + createVolumeResponse.getVolume().getId());

            // 생성된 볼륨 조회
            GetVolumeRequest getVolumeRequest = GetVolumeRequest.builder()
                    .volumeId(createVolumeResponse.getVolume().getId()).build();
            // 볼륨 프로비저닝 시 특정 상태가 되는 것을 모니터링 (Available 상태 될 때까지 대기 후 결과 반환)
            getVolumeResponse = blockstorageClient.getWaiters()
                    .forVolume(getVolumeRequest, Volume.LifecycleState.Available).execute();

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            blockstorageClient.close();
        }

        return getVolumeResponse.getVolume().getId();
    }

    /**
    * Block Volume Storage를 인스턴스에 추가
    * @param computeClient 인스턴스에 스토리지를 붙이기 위한 작업으로 인스턴스에 대한 작업 수행을 위한 computeClient를 전달 받음
    * @param dbname CloudAG DB명
    * @param instanceId 블록 볼륨 스토리지를 붙이기 위한 인스턴스 아이디
    * @param volumeId 인스턴스에 추가하기 위한 블록 볼륨 아이디
    * @return String 생성된 블록 어태치 OCID 값
    */
    public String attachBlockVolume(ComputeClient computeClient, String dbname, String instanceId, String volumeId)
            throws Exception {

        // OCI에 생성되는 블록 볼륨 어태치 이름
        String attachmentVolumeName = dbname + "-attachment-block-volume";
        
        // 어태치에 대한 기본 정보 (디바이스명, 인스턴스 아이디, 볼륨 아이디)
        AttachVolumeDetails attachVolumeDetails = AttachParavirtualizedVolumeDetails.builder()
                .device("/dev/oracleoci/oraclevdb").displayName(attachmentVolumeName).instanceId(instanceId)
                .isReadOnly(false).isShareable(false).volumeId(volumeId).build();

        // 어태치 작업을 위한 요청 생성
        AttachVolumeRequest attachVolumeRequest = AttachVolumeRequest.builder().attachVolumeDetails(attachVolumeDetails)
                .build();

        // 생성된 작업 수행
        AttachVolumeResponse attachVolumeResponse = computeClient.attachVolume(attachVolumeRequest);

        return attachVolumeResponse.getVolumeAttachment().getId();
    }

    /**
    * Block Volume Storage 삭제
    * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
    * @param instanceId 블록 볼륨 스토리지를 붙이기 위한 인스턴스 아이디
    * @param volumeId 삭제하기 위한 블록 볼륨 아이디
    * @return String 삭제된 블록 볼륨 OCID 값
    */
    public String terminateBlockVolume(String region, String volumeId) throws Exception {
        BlockstorageClient blockstorageClient = null;
        try {
            // 블록스토리지 관련 작업을 위한 클라이언트
            blockstorageClient = getBlockstorageClient(region);

            // 블록 볼륨 삭제 요청 생성
            DeleteVolumeRequest deleteVolumeRequest = DeleteVolumeRequest.builder()
                    .volumeId(volumeId)
                    .build();

            // 블록 볼륨 삭제 작업
            blockstorageClient.deleteVolume(deleteVolumeRequest);

            // 블록 볼륨 삭제 진행 상황 모니터링 (Terminated 상태가 되면 응답)
            GetVolumeRequest getVolumeRequest = GetVolumeRequest.builder().volumeId(volumeId).build();
            blockstorageClient.getWaiters().forVolume(getVolumeRequest, Volume.LifecycleState.Terminated).execute();

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            blockstorageClient.close();
        }
        return volumeId;
    }
}
