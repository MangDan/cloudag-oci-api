package net.bitnine.cloudag.api.oracle.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.oracle.bmc.Region;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.model.CreateCompartmentDetails;
import com.oracle.bmc.identity.model.CreatePolicyDetails;
import com.oracle.bmc.identity.model.UpdatePolicyDetails;
import com.oracle.bmc.identity.requests.CreateCompartmentRequest;
import com.oracle.bmc.identity.requests.CreatePolicyRequest;
import com.oracle.bmc.identity.requests.DeleteCompartmentRequest;
import com.oracle.bmc.identity.requests.GetCompartmentRequest;
import com.oracle.bmc.identity.requests.GetPolicyRequest;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.identity.requests.UpdatePolicyRequest;
import com.oracle.bmc.identity.responses.CreateCompartmentResponse;
import com.oracle.bmc.identity.responses.CreatePolicyResponse;
import com.oracle.bmc.identity.responses.GetPolicyResponse;
import com.oracle.bmc.identity.responses.ListAvailabilityDomainsResponse;
import com.oracle.bmc.identity.responses.ListCompartmentsResponse;
import com.oracle.bmc.identity.responses.UpdatePolicyResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@Slf4j
@Service
public class IdentityService {

    // 
    private static final String CLOUDAG_POLICY_ID = "ocid1.policy.oc1..aaaaaaaagvmvpt2qziysamuwjpyd2s7ev2vdzficyrpekadpuxhhoatpa2qa";
    @Autowired
    AuthentificationProvider authentificationProvider;

    /**
    * 인증 (Identity) 관련 작업 수행을 위한 클라이언트
    * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm) 
    * @return IdentityClient Identity 클라이언트 객체
    */
    public IdentityClient getIdentityClient(String region) throws IOException {
        IdentityClient identityClient = IdentityClient.builder()
                .build(authentificationProvider.getAuthenticationDetailsProvider());
        
        // Client 사용을 위한 리전 설정
        identityClient.setRegion(Region.valueOf(region));

        return identityClient;
    }

    /**
    * 리전내의 데이터센터 (Availability Domain)에 대한 정보 반환
    * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm) 
    * @param compartmentId 컴파트먼트 아이디
    * @return AvailabilityDomain AvailabilityDomain 객체
    */
    public AvailabilityDomain getAvailabilityDomains(String region, String compartmentId) throws IOException {

        IdentityClient identityClient = null;
        AvailabilityDomain availabilityDomain = null;
        try {
            identityClient = getIdentityClient(region);

            // Availability Domain 조회 응답
            ListAvailabilityDomainsResponse listAvailabilityDomainsResponse = identityClient.listAvailabilityDomains(
                    ListAvailabilityDomainsRequest.builder().compartmentId(compartmentId).build());

            // Availiability Domain 정보 획득
            List<AvailabilityDomain> availabilityDomains = listAvailabilityDomainsResponse.getItems();
            availabilityDomain = availabilityDomains.get(0);

            log.info("Found Availability Domain: " + availabilityDomain.getName());
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }
        return availabilityDomain;
    }

    /**
    * 컴파트먼트 생성
    * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm) 
    * @param parentCompartmentId 생성할 컴파트먼트의 부모 컴파트먼트 아이디 
    * @param compartment_name 컴파트먼트 이름
    * @return String 생성된 컴파트먼트의 OCID
    */
    public String createCompartment(String region, String parentCompartmentId, String compartment_name) throws Exception {
        String compartmentId = "";
        IdentityClient identityClient = null;
        try {
            log.info(compartment_name + " 컴파트먼트 생성 시작");

            // IdentityClient 
            identityClient = new IdentityClient(authentificationProvider.getAuthenticationDetailsProvider());
            identityClient.setRegion(region);   // 클라이언트 사용을 위한 리전 설정

            // 컴파트먼트 목록 조회 요청
            ListCompartmentsRequest listCompartmentsRequest = ListCompartmentsRequest.builder()
                    .compartmentId(authentificationProvider.getAuthenticationDetailsProvider().getTenantId())
                    .accessLevel(ListCompartmentsRequest.AccessLevel.Any).compartmentIdInSubtree(true)
                    .name(compartment_name).build();

            // 컴파트먼트 목록 조회 응답
            ListCompartmentsResponse listCompartmentsResponse = identityClient.listCompartments(listCompartmentsRequest);

            log.info("조회한 컴파트먼트 개수: " + Integer.toString(listCompartmentsResponse.getItems().size()));

            // 동일한 이름의 컴파트먼트가 있으면 신규로 생성하지 않고 기존 컴파트먼트의 아이디를 가져옴
            if (listCompartmentsResponse.getItems().size() > 0) {
                compartmentId = listCompartmentsResponse.getItems().get(0).getId();
            } else {

                // 컴파트먼트 생성 정보 설정 (컴파트먼트명, 부모 컴파트먼트 아이디)
                CreateCompartmentDetails createCompartmentDetails = CreateCompartmentDetails.builder()
                        .compartmentId(parentCompartmentId).name(compartment_name)
                        .description("Compartment for " + compartment_name).build();

                // 컴파트먼트 생성 요청
                CreateCompartmentRequest createCompartmentRequest = CreateCompartmentRequest.builder()
                        .createCompartmentDetails(createCompartmentDetails).build();

                // 컴파트먼트 생성 응답
                CreateCompartmentResponse response = identityClient.createCompartment(createCompartmentRequest);
                compartmentId = response.getCompartment().getId();
            }

            log.info(compartment_name + " 컴파트먼트 생성 완료");
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            identityClient.close();
        }

        return compartmentId;
    }

    /**
    * 컴파트먼트 삭제
    * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm) 
    * @param compartmentId 컴파트먼트의 OCID
    * @return String 삭제된 컴파트먼트의 OCID
    */
    @Async
    public String deleteCompartment(String region, String compartmentId) {
        IdentityClient identityClient = null;
        try {
            log.info("["+compartmentId+"] 컴파트먼트 삭제 시작");

            // 컴파트먼트 삭제 작업을 위한 클라이언트
            identityClient = new IdentityClient(authentificationProvider.getAuthenticationDetailsProvider());
            identityClient.setRegion(region);


            // 컴파트먼트 삭제 요청
            DeleteCompartmentRequest deleteCompartmentRequest = DeleteCompartmentRequest.builder()
                    .compartmentId(compartmentId).build();

            // 컴파트먼트 삭제
            identityClient.deleteCompartment(deleteCompartmentRequest);


            // 삭제 요청된 컴파트먼트 정보 요청
            GetCompartmentRequest getCompartmentRequest = GetCompartmentRequest.builder().compartmentId(compartmentId).build();
            
            // 삭제 요청된 컴파트먼트 상태 조회 (Deleted 상태가 되면 응답)
            identityClient.getWaiters().forCompartment(getCompartmentRequest, Compartment.LifecycleState.Deleted).execute();

            log.info("컴파트먼트 [" + compartmentId + "]가 삭제되었습니다.");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            identityClient.close();
        }
        return compartmentId;
    }

    // 사용 안함
    public String createPolicy(String region, String compartmentId, String policyName, ArrayList<String> statements)
            throws Exception {
        IdentityClient identityClient = null;
        CreatePolicyResponse createPolicyResponse = null;

        try {
            identityClient = getIdentityClient(region);

            CreatePolicyDetails createPolicyDetails = CreatePolicyDetails.builder().compartmentId(compartmentId)
                    .name(policyName).description(policyName).statements(statements).build();

            CreatePolicyRequest createPolicyRequest = CreatePolicyRequest.builder()
                    .createPolicyDetails(createPolicyDetails).build();
            
            createPolicyResponse = identityClient.createPolicy(createPolicyRequest);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            identityClient.close();
        }
        return createPolicyResponse.getPolicy().getId();
    }

    // 사용 안함
    public String updatePolicyStatement(String region, String compartmentId) throws Exception {
        IdentityClient identityClient = null;

        try {
            identityClient = getIdentityClient(region);

            GetPolicyRequest getPolicyRequest = GetPolicyRequest.builder().policyId(CLOUDAG_POLICY_ID).build();
            GetPolicyResponse getPolicyResponse = identityClient.getPolicy(getPolicyRequest);

            List<String> statements = getPolicyResponse.getPolicy().getStatements();

            String newStatement = "Allow group APIGroup to manage all-resources in compartment id " + compartmentId;

            statements.add(newStatement);

            UpdatePolicyDetails updatePolicyDetails = UpdatePolicyDetails.builder().description("")
                    .statements(statements).build();

            UpdatePolicyRequest updatePolicyRequest = UpdatePolicyRequest.builder()
                    .policyId("ocid1.policy.oc1..aaaaaaaagvmvpt2qziysamuwjpyd2s7ev2vdzficyrpekadpuxhhoatpa2qa")
                    .updatePolicyDetails(updatePolicyDetails).build();

            /* Send request to the Client */
            UpdatePolicyResponse response = identityClient.updatePolicy(updatePolicyRequest);

            return response.getPolicy().getId();
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            identityClient.close();
        }
    }
}
