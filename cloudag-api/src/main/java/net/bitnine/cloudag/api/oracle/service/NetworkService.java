package net.bitnine.cloudag.api.oracle.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.AddNetworkSecurityGroupSecurityRulesDetails;
import com.oracle.bmc.core.model.AddSecurityRuleDetails;
import com.oracle.bmc.core.model.AddSecurityRuleDetails.Direction;
import com.oracle.bmc.core.model.AddSecurityRuleDetails.SourceType;
import com.oracle.bmc.core.model.CreateInternetGatewayDetails;
import com.oracle.bmc.core.model.CreateNetworkSecurityGroupDetails;
import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.model.InternetGateway;
import com.oracle.bmc.core.model.NetworkSecurityGroup;
import com.oracle.bmc.core.model.PortRange;
import com.oracle.bmc.core.model.RouteRule;
import com.oracle.bmc.core.model.RouteTable;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.TcpOptions;
import com.oracle.bmc.core.model.UpdateRouteTableDetails;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.AddNetworkSecurityGroupSecurityRulesRequest;
import com.oracle.bmc.core.requests.CreateInternetGatewayRequest;
import com.oracle.bmc.core.requests.CreateNetworkSecurityGroupRequest;
import com.oracle.bmc.core.requests.CreateSubnetRequest;
import com.oracle.bmc.core.requests.CreateVcnRequest;
import com.oracle.bmc.core.requests.DeleteInternetGatewayRequest;
import com.oracle.bmc.core.requests.DeleteNetworkSecurityGroupRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;
import com.oracle.bmc.core.requests.GetInternetGatewayRequest;
import com.oracle.bmc.core.requests.GetNetworkSecurityGroupRequest;
import com.oracle.bmc.core.requests.GetRouteTableRequest;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.core.requests.GetVcnRequest;
import com.oracle.bmc.core.requests.ListInternetGatewaysRequest;
import com.oracle.bmc.core.requests.ListNetworkSecurityGroupsRequest;
import com.oracle.bmc.core.requests.ListRouteTablesRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.core.requests.UpdateRouteTableRequest;
import com.oracle.bmc.core.responses.CreateInternetGatewayResponse;
import com.oracle.bmc.core.responses.CreateNetworkSecurityGroupResponse;
import com.oracle.bmc.core.responses.CreateSubnetResponse;
import com.oracle.bmc.core.responses.CreateVcnResponse;
import com.oracle.bmc.core.responses.GetInternetGatewayResponse;
import com.oracle.bmc.core.responses.GetNetworkSecurityGroupResponse;
import com.oracle.bmc.core.responses.GetRouteTableResponse;
import com.oracle.bmc.core.responses.GetSubnetResponse;
import com.oracle.bmc.core.responses.GetVcnResponse;
import com.oracle.bmc.core.responses.ListInternetGatewaysResponse;
import com.oracle.bmc.core.responses.ListNetworkSecurityGroupsResponse;
import com.oracle.bmc.core.responses.ListRouteTablesResponse;
import com.oracle.bmc.core.responses.ListSubnetsResponse;
import com.oracle.bmc.core.responses.UpdateRouteTableResponse;
import com.oracle.bmc.identity.model.AvailabilityDomain;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@Slf4j
@Service
public class NetworkService {
        @Autowired
        AuthentificationProvider authentificationProvider;

        @Autowired
        IdentityService identityService;

        /**
        * Virtual Cloud Netowork 관련 작업 수행을 위한 클라이언트
        * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm) 
        * @return VirtualNetworkClient 클라이언트 객체
        */
        public VirtualNetworkClient getVirtualNetworkClient(String region) throws IOException {
                VirtualNetworkClient virtualNetworkClient = VirtualNetworkClient.builder()
                                .build(authentificationProvider.getAuthenticationDetailsProvider());
                
                // Client 사용을 위한 리전 설정
                virtualNetworkClient.setRegion(region);

                return virtualNetworkClient;
        }

        /**
         * Virtual Cloud Network 생성
         * 
         * @param dbname        CloudAG Database 이름
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId 컴파트먼트 OCID
         * @return Map<String, String> vcn과 subnet ocid 반환
         */
        public Map<String, String> createVirtualNetwork(String dbname, String region, String compartmentId)
                        throws Exception {
                Map<String, String> resultMap = new HashMap<String, String>();
                String vcnName = dbname + "-vcn";
                String internetGatewayName = dbname + "-gateway";
                String subnetName = dbname + "-subnet";

                AvailabilityDomain availabilityDomain = null;
                VirtualNetworkClient virtualNetworkClient = null;
                Vcn vcn = null;

                try {   
                        // VCN이 생성되는 리전내의 데이터센서 정보 확득
                        availabilityDomain = identityService.getAvailabilityDomains(region, compartmentId);

                        // VCN의 기본 CIDR 블럭
                        String networkCidrBlock = "10.0.0.0/16";

                        // VCN 작업을 위한 Client
                        virtualNetworkClient = getVirtualNetworkClient(region);

                        // VCN 생성을 위한 정보 구성
                        CreateVcnDetails createVcnDetails = CreateVcnDetails.builder().cidrBlock(networkCidrBlock)
                                        .compartmentId(compartmentId).displayName(vcnName).build();

                        // VCN 생성 요청
                        CreateVcnRequest createVcnRequest = CreateVcnRequest.builder()
                                        .createVcnDetails(createVcnDetails).build();
                        // VCN 생성 응답
                        CreateVcnResponse createVcnResponse = null;

                        try {   
                                // VCN 생성
                                createVcnResponse = virtualNetworkClient.createVcn(createVcnRequest);
                        } catch (Exception be) {
                                try {
                                        // VCN 생성 시 오류가 발생하는 경우 10초후에 재 생성
                                        log.info(be.getMessage());
                                        Thread.sleep(10000);
                                        createVcnResponse = virtualNetworkClient.createVcn(createVcnRequest);
                                } catch (Exception e) {
                                        throw e;
                                }
                        }

                        // VCN 정보 요청
                        GetVcnRequest getVcnRequest = GetVcnRequest.builder().vcnId(createVcnResponse.getVcn().getId())
                                        .build();
                        // VCN 정보 응답
                        GetVcnResponse getVcnResponse = virtualNetworkClient.getWaiters()
                                        .forVcn(getVcnRequest, Vcn.LifecycleState.Available).execute();
                        vcn = getVcnResponse.getVcn();
                        log.info("생성된 VCN OCID: " + vcn.getId());

                        // Internet Gateway 생성을 위한 정보 구성
                        CreateInternetGatewayDetails createInternetGatewayDetails = CreateInternetGatewayDetails
                                        .builder().compartmentId(compartmentId).displayName(internetGatewayName)
                                        .isEnabled(true).vcnId(vcn.getId()).build();

                        // Internet Gateway 생성 요청
                        CreateInternetGatewayRequest createInternetGatewayRequest = CreateInternetGatewayRequest
                                        .builder().createInternetGatewayDetails(createInternetGatewayDetails).build();
                        
                        // Internet Gateway 생성 응답
                        CreateInternetGatewayResponse createInternetGatewayResponse = virtualNetworkClient
                                        .createInternetGateway(createInternetGatewayRequest);

                        // Internet Gateway 정보 요청
                        GetInternetGatewayRequest getInternetGatewayRequest = GetInternetGatewayRequest.builder()
                                        .igId(createInternetGatewayResponse.getInternetGateway().getId()).build();
                        
                        // Internet Gateway 정보 응답
                        GetInternetGatewayResponse getInternetGatewayResponse = virtualNetworkClient.getWaiters()
                                        .forInternetGateway(getInternetGatewayRequest,
                                                        InternetGateway.LifecycleState.Available)
                                        .execute();
                        
                        // Internet Gateway 정보 획득
                        InternetGateway internetGateway = getInternetGatewayResponse.getInternetGateway();
                        log.info("생성된 Internet Gateway OCID: " + internetGateway.getId());

                        // Internet Gateway를 Route Table에 설정을 위해 Route Table 정보 요청
                        GetRouteTableRequest getRouteTableRequest = GetRouteTableRequest.builder()
                                        .rtId(vcn.getDefaultRouteTableId()).build();
                        
                        // Route Table 정보 응답
                        GetRouteTableResponse getRouteTableResponse = virtualNetworkClient
                                        .getRouteTable(getRouteTableRequest);
                        
                        // Route Table의 라우팅 규칙 목록
                        List<RouteRule> routeRules = getRouteTableResponse.getRouteTable().getRouteRules();

                        // Internet Gateway 라우팅 규칙 생성
                        RouteRule internetAccessRoute = RouteRule.builder().destination("0.0.0.0/0")
                                        .destinationType(RouteRule.DestinationType.CidrBlock)
                                        .networkEntityId(internetGateway.getId()).build();
                        
                        // 생성한 Internet Gateway 라우팅 규칙을 Route Table에 추가
                        routeRules.add(internetAccessRoute);
                        UpdateRouteTableDetails updateRouteTableDetails = UpdateRouteTableDetails.builder()
                                        .routeRules(routeRules).build();
                        UpdateRouteTableRequest updateRouteTableRequest = UpdateRouteTableRequest.builder()
                                        .updateRouteTableDetails(updateRouteTableDetails)
                                        .rtId(vcn.getDefaultRouteTableId()).build();
                        virtualNetworkClient.updateRouteTable(updateRouteTableRequest);

                        // Route Table 정보
                        getRouteTableResponse = virtualNetworkClient.getWaiters()
                                        .forRouteTable(getRouteTableRequest, RouteTable.LifecycleState.Available)
                                        .execute();
                        routeRules = getRouteTableResponse.getRouteTable().getRouteRules();
                        log.info("Route Table에 Internet Gateway Route Rule 추가");

                        // 서브넷 생성을 위한 정보 구성
                        CreateSubnetDetails createSubnetDetails = CreateSubnetDetails.builder()
                                        .availabilityDomain(availabilityDomain.getName()).compartmentId(compartmentId)
                                        .displayName(subnetName).cidrBlock(networkCidrBlock).vcnId(vcn.getId())
                                        .routeTableId(vcn.getDefaultRouteTableId()).build();
                        
                        // 서브넷 생성 요청
                        CreateSubnetRequest createSubnetRequest = CreateSubnetRequest.builder()
                                        .createSubnetDetails(createSubnetDetails).build();
                        
                        // 서브넷 생성 응답
                        CreateSubnetResponse createSubnetResponse = virtualNetworkClient
                                        .createSubnet(createSubnetRequest);

                        // 생성된 서브넷 정보 요청
                        GetSubnetRequest getSubnetRequest = GetSubnetRequest.builder()
                                        .subnetId(createSubnetResponse.getSubnet().getId()).build();
                        
                        // 생성된 서브넷 정보 응답
                        GetSubnetResponse getSubnetResponse = virtualNetworkClient.getWaiters()
                                        .forSubnet(getSubnetRequest, Subnet.LifecycleState.Available).execute();
                        Subnet subnet = getSubnetResponse.getSubnet();

                        log.info("생성된 서브넷 OCID: " + subnet.getId());

                        resultMap.put("vcnId", vcn.getId());
                        resultMap.put("subnetId", subnet.getId());
                } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                } finally {
                        virtualNetworkClient.close();
                }
                return resultMap;
        }

        /**
         * 인스턴스에 특정 포트 오픈을 위한 Network Security Group 생성
         * 
         * @param virtualNetworkClient        Virtual Network Client
         * @param region                        OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId 컴파트먼트 OCID
         * @param vcnId         VCN OCID
         * @param dbname        CloudAG Database 이름
         * @return NetworkSecurityGroup
         */
        public NetworkSecurityGroup createNetworkSecurityGroup(VirtualNetworkClient virtualNetworkClient,
                        String compartmentId, String vcnId, String dbname) throws Exception {
                
                // OCI에 생성할 Network Security Group 이름
                String networkSecurityGroupName = dbname + "-nsg";

                // 생성할 Network Security Group 정보 구성
                CreateNetworkSecurityGroupDetails createNetworkSecurityGroupDetails = CreateNetworkSecurityGroupDetails
                                .builder().compartmentId(compartmentId).displayName(networkSecurityGroupName)
                                .vcnId(vcnId).build();
                
                // Network Security Group 생성 요청
                CreateNetworkSecurityGroupRequest createNetworkSecurityGroupRequest = CreateNetworkSecurityGroupRequest
                                .builder().createNetworkSecurityGroupDetails(createNetworkSecurityGroupDetails).build();
                
                // Network Security Group 생성 응답
                CreateNetworkSecurityGroupResponse createNetworkSecurityGroupResponse = virtualNetworkClient
                                .createNetworkSecurityGroup(createNetworkSecurityGroupRequest);
                
                // 생성된 Network Security Group 정보 요청
                GetNetworkSecurityGroupRequest getNetworkSecurityGroupRequest = GetNetworkSecurityGroupRequest.builder()
                                .networkSecurityGroupId(
                                                createNetworkSecurityGroupResponse.getNetworkSecurityGroup().getId())
                                .build();
                
                // 생성된 Network Security Group 정보 응답
                GetNetworkSecurityGroupResponse getNetworkSecurityGroupResponse = virtualNetworkClient.getWaiters()
                                .forNetworkSecurityGroup(getNetworkSecurityGroupRequest,
                                                NetworkSecurityGroup.LifecycleState.Available)
                                .execute();
                
                // 생성된 Network Security Group 정보
                NetworkSecurityGroup networkSecurityGroup = getNetworkSecurityGroupResponse.getNetworkSecurityGroup();

                log.info("생성된 Network Security Group OCID: " + networkSecurityGroup.getId());

                return networkSecurityGroup;
        }

        /**
         * Network Security Group에 Security Rule 추가
         * 
         * @param virtualNetworkClient        Virtual Network Client
         * @param NetworkSecurityGroup        NetworkSecurityGroup
         * @param networkCidrBlock            설정할 CIDR 블럭
         * @param viewerPort                  CloudAG Viewer Port
         * @param dbPort                      CloudAG DB Port
         * @return void
         */
        public void addNetworkSecurityGroupSecurityRules(VirtualNetworkClient virtualNetworkClient,
                        NetworkSecurityGroup networkSecurityGroup, String networkCidrBlock, int viewerPort,
                        int dbPort) {

                // 추가할 Security Rule 정보 설정 (For viewer)
                AddSecurityRuleDetails addSecurityRuleDetailsForViewer = AddSecurityRuleDetails.builder()
                                .description("Incoming HTTP connections for CloudAG Viewer")
                                .direction(Direction.Ingress).protocol("6").source(networkCidrBlock)
                                .sourceType(SourceType.CidrBlock)
                                .tcpOptions(TcpOptions.builder().destinationPortRange(
                                                PortRange.builder().min(viewerPort).max(viewerPort).build()).build())
                                .build();
                
                // 추가할 Security Rule 정보 설정 (For DB)
                AddSecurityRuleDetails addSecurityRuleDetailsForDB = AddSecurityRuleDetails.builder()
                                .description("Incoming HTTP connections for CloudAG Database")
                                .direction(Direction.Ingress).protocol("6").source(networkCidrBlock)
                                .sourceType(SourceType.CidrBlock)
                                .tcpOptions(TcpOptions.builder()
                                                .destinationPortRange(
                                                                PortRange.builder().min(dbPort).max(dbPort).build())
                                                .build())
                                .build();
                
                // Network Security Group에 Security Rule을 추가하기 위한 정보 생성
                AddNetworkSecurityGroupSecurityRulesDetails addNetworkSecurityGroupSecurityRulesDetails = AddNetworkSecurityGroupSecurityRulesDetails
                                .builder().securityRules(Arrays.asList(addSecurityRuleDetailsForViewer,
                                                addSecurityRuleDetailsForDB))
                                .build();
                // Network Security Group에 Security Rule 추가 요청
                AddNetworkSecurityGroupSecurityRulesRequest addNetworkSecurityGroupSecurityRulesRequest = AddNetworkSecurityGroupSecurityRulesRequest
                                .builder().networkSecurityGroupId(networkSecurityGroup.getId())
                                .addNetworkSecurityGroupSecurityRulesDetails(
                                                addNetworkSecurityGroupSecurityRulesDetails)
                                .build();
                virtualNetworkClient.addNetworkSecurityGroupSecurityRules(addNetworkSecurityGroupSecurityRulesRequest);
        }

        /**
         * Virtual Cloud Network 삭제 (관련된 모든 네트워크 자원 삭제)
         * 
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId 컴파트먼트 OCID
         * @param vcnId            vcn OCID
         * @return String  삭제된 VCN OCID 반환
         */
        public String terminateAllVirtualNetwork(String region, String compartmentId, String vcnId) throws Exception {
                VirtualNetworkClient virtualNetworkClient = null;
                try {
                        // VCN 작업을 위한 클라이언트
                        virtualNetworkClient = getVirtualNetworkClient(region);
                        virtualNetworkClient.setRegion(region);

                        // Subnet 삭제를 위해 서브넷 목록 조회 요청
                        ListSubnetsRequest listSubnetsRequest = ListSubnetsRequest.builder()
                                        .compartmentId(compartmentId).vcnId(vcnId).build();
                        // Subnet 삭제를 위해 서브넷 목록 조회 응답
                        ListSubnetsResponse listSubnetsResponse = virtualNetworkClient.listSubnets(listSubnetsRequest);

                        // 조회된 모든 서브넷 삭제
                        for (Subnet subnet : listSubnetsResponse.getItems()) {
                                String subnetId = subnet.getId();

                                // 서브넷 삭ㅈ ㅔ요청
                                DeleteSubnetRequest deleteSubnetRequest = DeleteSubnetRequest.builder()
                                                .subnetId(subnetId).build();

                                // 서브넷 삭제
                                virtualNetworkClient.deleteSubnet(deleteSubnetRequest);


                                // 삭제 요청된 서브넷 정보 요청
                                GetSubnetRequest getSubnetRequest = GetSubnetRequest.builder().subnetId(subnetId)
                                                .build();
                                
                                // 서브넷 상태 조회 (Terminated 되면 응답)
                                virtualNetworkClient.getWaiters()
                                                .forSubnet(getSubnetRequest, Subnet.LifecycleState.Terminated)
                                                .execute();

                                log.info("서브넷 [" + subnetId + "] 이 삭제되었습니다.");
                        }

                        // 모든 NSG 삭제를 위해 NSG 목록 조회 요청
                        ListNetworkSecurityGroupsRequest listNetworkSecurityGroupsRequest = ListNetworkSecurityGroupsRequest
                                        .builder().compartmentId(compartmentId).vcnId(vcnId).build();
                        
                        // 모든 NSG 삭제를 위해 NSG 목록 조회 응답
                        ListNetworkSecurityGroupsResponse listNetworkSecurityGroupsResponse = virtualNetworkClient
                                        .listNetworkSecurityGroups(listNetworkSecurityGroupsRequest);

                        // NSG 삭제
                        for (NetworkSecurityGroup networkSecurityGroup : listNetworkSecurityGroupsResponse.getItems()) {
                                String networkSecurityGroupId = networkSecurityGroup.getId();
                                
                                // NSG 삭제 요청
                                DeleteNetworkSecurityGroupRequest deleteNetworkSecurityGroupRequest = DeleteNetworkSecurityGroupRequest
                                                .builder().networkSecurityGroupId(networkSecurityGroupId).build();
                                
                                // NSG 삭제
                                virtualNetworkClient.deleteNetworkSecurityGroup(deleteNetworkSecurityGroupRequest);

                                // 삭제 요청된 NSG 정보 조회
                                GetNetworkSecurityGroupRequest getNetworkSecurityGroupRequest = GetNetworkSecurityGroupRequest
                                                .builder().networkSecurityGroupId(networkSecurityGroupId).build();
                                
                                // NSG 상태 조회 (Terminated 되면 응답)
                                virtualNetworkClient
                                                .getWaiters()
                                                .forNetworkSecurityGroup(getNetworkSecurityGroupRequest,
                                                                NetworkSecurityGroup.LifecycleState.Terminated)
                                                .execute();

                                log.info("Network Security Group ["
                                                + networkSecurityGroupId
                                                + "] 이 삭제되었습니다.");
                        }

                        // Internet Gateway를 Route Table에서 제거하기 위해 Route Table에 설정된 Route Rule 목록 조회 요청
                        ListRouteTablesRequest listRouteTablesRequest = ListRouteTablesRequest.builder()
                                        .compartmentId(compartmentId).vcnId(vcnId).build();
                        
                        // Internet Gateway를 Route Table에서 제거하기 위해 Route Table에 설정된 Route Rule 목록 조회 응답
                        ListRouteTablesResponse listRouteTablesResponse = virtualNetworkClient
                                        .listRouteTables(listRouteTablesRequest);

                        List<RouteRule> routeRules = new ArrayList<>();
                        for (RouteTable routeTable : listRouteTablesResponse.getItems()) {

                                // Route Table에서 Route Rule 제거를 위한 업데이트 정보 설정
                                UpdateRouteTableDetails updateRouteTableDetails = UpdateRouteTableDetails.builder()
                                                .routeRules(routeRules).build();
                                
                                // Route Table에서 Route Rule 제거를 위한 업데이트 요청
                                UpdateRouteTableRequest updateRouteTableRequest = UpdateRouteTableRequest.builder()
                                                .rtId(routeTable.getId())
                                                .updateRouteTableDetails(updateRouteTableDetails).build();

                                // Route Table에서 Route Rule 제거 응답
                                UpdateRouteTableResponse updateRouteTableResponse = virtualNetworkClient.updateRouteTable(updateRouteTableRequest);
                                log.info("Route Table이 ["+updateRouteTableResponse.getRouteTable().getId()+"] 업데이트 되었습니다. (Internet Gateway Route Rule 제거)");
                        }

                        // Internet Gateway 삭제를 위해 모든 Internet Gateway 목록 조회 요청
                        ListInternetGatewaysRequest listInternetGatewaysRequest = ListInternetGatewaysRequest.builder().compartmentId(compartmentId).vcnId(vcnId).build();

                        // Internet Gateway 삭제를 위해 모든 Internet Gateway 목록 조회 응답
                        ListInternetGatewaysResponse listInternetGatewaysResponse = virtualNetworkClient.listInternetGateways(listInternetGatewaysRequest);

                        for(InternetGateway internetGateway : listInternetGatewaysResponse.getItems()) {
                                String InternetGatewayId = internetGateway.getId();

                                // Internet Gateway 삭제 요청
                                DeleteInternetGatewayRequest deleteInternetGatewayRequest = DeleteInternetGatewayRequest.builder().igId(InternetGatewayId).build();

                                // Internet Gateway 삭제 
                                virtualNetworkClient.deleteInternetGateway(deleteInternetGatewayRequest);


                                // 삭제 요청된 Internet Gateway 정보 요청
                                GetInternetGatewayRequest getInternetGatewayRequest = GetInternetGatewayRequest.builder().igId(InternetGatewayId).build();

                                // 삭제 요청된 Internet Gateway 상태 조회 (Terminated되 면 응답)
                                virtualNetworkClient.getWaiters().forInternetGateway(getInternetGatewayRequest, InternetGateway.LifecycleState.Terminated).execute();

                                log.info("Internet Gateway ["+ InternetGatewayId+"]가 삭제되었습니다.");
                        }

                        // VCN 삭제 요청
                        DeleteVcnRequest deleteVcnRequest = DeleteVcnRequest.builder().vcnId(vcnId).build();

                        // VCN 삭제
                        virtualNetworkClient.deleteVcn(deleteVcnRequest);
                        
                        // 삭제 요청된 VCN 정보 요청
                        GetVcnRequest getVcnRequest = GetVcnRequest.builder().vcnId(vcnId).build();

                        // 삭제 요청된 VCN 상태 조회 (Terminated 상태이면 응답)
                        virtualNetworkClient.getWaiters().forVcn(getVcnRequest, Vcn.LifecycleState.Terminated).execute();
                        
                        log.info("Vcn: [" + vcnId + "]이 삭제되었습니다.");

                } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                } finally {
                        virtualNetworkClient.close();
                }
                return vcnId;
        }

}
