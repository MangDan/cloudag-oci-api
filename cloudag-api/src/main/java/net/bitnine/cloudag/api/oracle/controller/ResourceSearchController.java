package net.bitnine.cloudag.api.oracle.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.oracle.bmc.computeinstanceagent.ComputeInstanceAgentClient;
import com.oracle.bmc.computeinstanceagent.model.InstanceAgentCommandExecutionSummary;
import com.oracle.bmc.computeinstanceagent.requests.ListInstanceAgentCommandExecutionsRequest;
import com.oracle.bmc.computeinstanceagent.responses.ListInstanceAgentCommandExecutionsResponse;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.VolumeAttachment;
import com.oracle.bmc.core.requests.ListVolumeAttachmentsRequest;
import com.oracle.bmc.core.responses.ListVolumeAttachmentsResponse;
import com.oracle.bmc.resourcesearch.ResourceSearchClient;
import com.oracle.bmc.resourcesearch.model.ResourceSummary;
import com.oracle.bmc.resourcesearch.model.SearchDetails;
import com.oracle.bmc.resourcesearch.model.StructuredSearchDetails;
import com.oracle.bmc.resourcesearch.requests.SearchResourcesRequest;
import com.oracle.bmc.resourcesearch.responses.SearchResourcesResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@Slf4j
@RestController
@EnableAutoConfiguration
public class ResourceSearchController {
    @Autowired
    AuthentificationProvider authentificationProvider;

    // 리소스 (인스턴스, VCN, 서브넷, 볼륨) 상태 조회
    @ResponseBody
    @RequestMapping(value = "/oci/api/v1/resources/search", method = RequestMethod.POST)
    public ArrayList<Map<String, String>> searchService(@RequestBody Map<String, Object> param) throws Exception {
        ComputeClient computeClient = null;
        ResourceSearchClient resourceSearchClient = null;
        ComputeInstanceAgentClient computeInstanceAgentClient = null;

        SearchResourcesResponse searchResourcesResponse = null;
        ArrayList<Map<String, String>> results = new ArrayList<Map<String, String>>();
        
        String region = (String) param.get("region");
        String compartmentId = (String) param.get("compartmentId");
        String dbname = (String) param.get("dbname");

        try {
            // 인스턴스에 어태치된 볼륨 정보 획득을 위한 클라이언트
            computeClient = new ComputeClient(authentificationProvider.getAuthenticationDetailsProvider());

            // 리소스 조회를 위한 클라이언트
            resourceSearchClient = new ResourceSearchClient(
                    authentificationProvider.getAuthenticationDetailsProvider());
            
            // 인스턴스 Run Command (외부 스크립트 실행) 상태 조회를 위한 클라이언트
            computeInstanceAgentClient = new ComputeInstanceAgentClient(authentificationProvider.getAuthenticationDetailsProvider());

            // 클라이언트에 리전 설정
            computeClient.setRegion(region);
            resourceSearchClient.setRegion(region);
            computeInstanceAgentClient.setRegion(region);

            // 리소스 조회를 위한 정보 설정 (조회 쿼리 설정)
            SearchDetails searchDetails = StructuredSearchDetails.builder().query(
                    "query Instance, Subnet, Vcn, Volume resources where compartmentId = '"+compartmentId+"' && displayName =~ '"+dbname+"' && lifecycleState != 'Terminated' sorted by timeCreated asc")
                    .matchingContextType(SearchDetails.MatchingContextType.Highlights).build();

            // 리소스 조회를 위한 정보 요청
            SearchResourcesRequest searchResourcesRequest = SearchResourcesRequest.builder()
                    .searchDetails(searchDetails)
                    .tenantId(authentificationProvider.getAuthenticationDetailsProvider().getTenantId()).build();


            // 리소스 조회 정보 응답
            searchResourcesResponse = resourceSearchClient.searchResources(searchResourcesRequest);

            // 조회된 리소스 목록 패치
            for (ResourceSummary resourceSummary : searchResourcesResponse.getResourceSummaryCollection().getItems()) {
                Map<String, String> resultMap = new HashMap<>();
                resultMap.put("displayName", resourceSummary.getDisplayName());         // 리소스 이름
                resultMap.put("resourceType", resourceSummary.getResourceType());       // 리소스 유형 (Instance, VCN, Subnet, Volume)
                resultMap.put("identifier", resourceSummary.getIdentifier());           // OCID
                resultMap.put("lifecycleState", resourceSummary.getLifecycleState());   // 리소스 상태

                // 리소스가 볼륨인 경우 볼륨이 인스턴스에 어태치된 상태도 같이 조회
                if(resourceSummary.getResourceType().equals("Volume")) {
                    ListVolumeAttachmentsResponse listVolumeAttachmentsResponse = computeClient.listVolumeAttachments(ListVolumeAttachmentsRequest.builder().compartmentId(compartmentId).volumeId(resourceSummary.getIdentifier()).build());

                    if(listVolumeAttachmentsResponse.getItems().size() > 0) {
                        VolumeAttachment volumeAttachment = listVolumeAttachmentsResponse.getItems().get(0);

                        resultMap.put("attachmentState", volumeAttachment.getLifecycleState().getValue());  // 어태치 상태
                    } else {
                        resultMap.put("attachmentState", "NOTFOUND");  // 만일 어태치 작업 시작전이라면 상태를 NOTFOUND으로 설정
                    }
                }

                // 리소스가 인스턴스인 경우 인스턴스에 수행하는 Run Command (볼륨 마운트를 위한 OS 스크립트) 상태 조회
                if(resourceSummary.getResourceType().equals("Instance")) {
                    
                    // Run Command 목록 요청
                    ListInstanceAgentCommandExecutionsRequest listInstanceAgentCommandExecutionsRequest = ListInstanceAgentCommandExecutionsRequest.builder().compartmentId(compartmentId).instanceId(resourceSummary.getIdentifier()).build();


                    // Run Command 목록 응답
                    ListInstanceAgentCommandExecutionsResponse listInstanceAgentCommandExecutionsResponse = computeInstanceAgentClient.listInstanceAgentCommandExecutions(listInstanceAgentCommandExecutionsRequest);

                    if(listInstanceAgentCommandExecutionsResponse.getItems().size() > 0) {

                        // Run Command 요약 정보 획득
                        InstanceAgentCommandExecutionSummary instanceAgentCommandExecutionSummary = listInstanceAgentCommandExecutionsResponse.getItems().get(0);

                        // Run Command 상태 정보
                        instanceAgentCommandExecutionSummary.getLifecycleState();
                        resultMap.put("volumeMountState", instanceAgentCommandExecutionSummary.getLifecycleState().getValue());
                    } else {
                        resultMap.put("volumeMountState", "NOTFOUND");  // Run Command가 아직 생성 전이라면 상태를 NOTFOUND로 설정
                    }
                    
                    
                }

                results.add(resultMap);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            resourceSearchClient.close();
        }
        return results;
    }
}
