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
        * Virtual Cloud Netowork ?????? ?????? ????????? ?????? ???????????????
        * @param region OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm) 
        * @return VirtualNetworkClient ??????????????? ??????
        */
        public VirtualNetworkClient getVirtualNetworkClient(String region) throws IOException {
                VirtualNetworkClient virtualNetworkClient = VirtualNetworkClient.builder()
                                .build(authentificationProvider.getAuthenticationDetailsProvider());
                
                // Client ????????? ?????? ?????? ??????
                virtualNetworkClient.setRegion(region);

                return virtualNetworkClient;
        }

        /**
         * Virtual Cloud Network ??????
         * 
         * @param dbname        CloudAG Database ??????
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId ??????????????? OCID
         * @return Map<String, String> vcn??? subnet ocid ??????
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
                        // VCN??? ???????????? ???????????? ??????????????? ?????? ??????
                        availabilityDomain = identityService.getAvailabilityDomains(region, compartmentId);

                        // VCN??? ?????? CIDR ??????
                        String networkCidrBlock = "10.0.0.0/16";

                        // VCN ????????? ?????? Client
                        virtualNetworkClient = getVirtualNetworkClient(region);

                        // VCN ????????? ?????? ?????? ??????
                        CreateVcnDetails createVcnDetails = CreateVcnDetails.builder().cidrBlock(networkCidrBlock)
                                        .compartmentId(compartmentId).displayName(vcnName).build();

                        // VCN ?????? ??????
                        CreateVcnRequest createVcnRequest = CreateVcnRequest.builder()
                                        .createVcnDetails(createVcnDetails).build();
                        // VCN ?????? ??????
                        CreateVcnResponse createVcnResponse = null;

                        try {   
                                // VCN ??????
                                createVcnResponse = virtualNetworkClient.createVcn(createVcnRequest);
                        } catch (Exception be) {
                                try {
                                        // VCN ?????? ??? ????????? ???????????? ?????? 10????????? ??? ??????
                                        log.info(be.getMessage());
                                        Thread.sleep(10000);
                                        createVcnResponse = virtualNetworkClient.createVcn(createVcnRequest);
                                } catch (Exception e) {
                                        throw e;
                                }
                        }

                        // VCN ?????? ??????
                        GetVcnRequest getVcnRequest = GetVcnRequest.builder().vcnId(createVcnResponse.getVcn().getId())
                                        .build();
                        // VCN ?????? ??????
                        GetVcnResponse getVcnResponse = virtualNetworkClient.getWaiters()
                                        .forVcn(getVcnRequest, Vcn.LifecycleState.Available).execute();
                        vcn = getVcnResponse.getVcn();
                        log.info("????????? VCN OCID: " + vcn.getId());

                        // Internet Gateway ????????? ?????? ?????? ??????
                        CreateInternetGatewayDetails createInternetGatewayDetails = CreateInternetGatewayDetails
                                        .builder().compartmentId(compartmentId).displayName(internetGatewayName)
                                        .isEnabled(true).vcnId(vcn.getId()).build();

                        // Internet Gateway ?????? ??????
                        CreateInternetGatewayRequest createInternetGatewayRequest = CreateInternetGatewayRequest
                                        .builder().createInternetGatewayDetails(createInternetGatewayDetails).build();
                        
                        // Internet Gateway ?????? ??????
                        CreateInternetGatewayResponse createInternetGatewayResponse = virtualNetworkClient
                                        .createInternetGateway(createInternetGatewayRequest);

                        // Internet Gateway ?????? ??????
                        GetInternetGatewayRequest getInternetGatewayRequest = GetInternetGatewayRequest.builder()
                                        .igId(createInternetGatewayResponse.getInternetGateway().getId()).build();
                        
                        // Internet Gateway ?????? ??????
                        GetInternetGatewayResponse getInternetGatewayResponse = virtualNetworkClient.getWaiters()
                                        .forInternetGateway(getInternetGatewayRequest,
                                                        InternetGateway.LifecycleState.Available)
                                        .execute();
                        
                        // Internet Gateway ?????? ??????
                        InternetGateway internetGateway = getInternetGatewayResponse.getInternetGateway();
                        log.info("????????? Internet Gateway OCID: " + internetGateway.getId());

                        // Internet Gateway??? Route Table??? ????????? ?????? Route Table ?????? ??????
                        GetRouteTableRequest getRouteTableRequest = GetRouteTableRequest.builder()
                                        .rtId(vcn.getDefaultRouteTableId()).build();
                        
                        // Route Table ?????? ??????
                        GetRouteTableResponse getRouteTableResponse = virtualNetworkClient
                                        .getRouteTable(getRouteTableRequest);
                        
                        // Route Table??? ????????? ?????? ??????
                        List<RouteRule> routeRules = getRouteTableResponse.getRouteTable().getRouteRules();

                        // Internet Gateway ????????? ?????? ??????
                        RouteRule internetAccessRoute = RouteRule.builder().destination("0.0.0.0/0")
                                        .destinationType(RouteRule.DestinationType.CidrBlock)
                                        .networkEntityId(internetGateway.getId()).build();
                        
                        // ????????? Internet Gateway ????????? ????????? Route Table??? ??????
                        routeRules.add(internetAccessRoute);
                        UpdateRouteTableDetails updateRouteTableDetails = UpdateRouteTableDetails.builder()
                                        .routeRules(routeRules).build();
                        UpdateRouteTableRequest updateRouteTableRequest = UpdateRouteTableRequest.builder()
                                        .updateRouteTableDetails(updateRouteTableDetails)
                                        .rtId(vcn.getDefaultRouteTableId()).build();
                        virtualNetworkClient.updateRouteTable(updateRouteTableRequest);

                        // Route Table ??????
                        getRouteTableResponse = virtualNetworkClient.getWaiters()
                                        .forRouteTable(getRouteTableRequest, RouteTable.LifecycleState.Available)
                                        .execute();
                        routeRules = getRouteTableResponse.getRouteTable().getRouteRules();
                        log.info("Route Table??? Internet Gateway Route Rule ??????");

                        // ????????? ????????? ?????? ?????? ??????
                        CreateSubnetDetails createSubnetDetails = CreateSubnetDetails.builder()
                                        .availabilityDomain(availabilityDomain.getName()).compartmentId(compartmentId)
                                        .displayName(subnetName).cidrBlock(networkCidrBlock).vcnId(vcn.getId())
                                        .routeTableId(vcn.getDefaultRouteTableId()).build();
                        
                        // ????????? ?????? ??????
                        CreateSubnetRequest createSubnetRequest = CreateSubnetRequest.builder()
                                        .createSubnetDetails(createSubnetDetails).build();
                        
                        // ????????? ?????? ??????
                        CreateSubnetResponse createSubnetResponse = virtualNetworkClient
                                        .createSubnet(createSubnetRequest);

                        // ????????? ????????? ?????? ??????
                        GetSubnetRequest getSubnetRequest = GetSubnetRequest.builder()
                                        .subnetId(createSubnetResponse.getSubnet().getId()).build();
                        
                        // ????????? ????????? ?????? ??????
                        GetSubnetResponse getSubnetResponse = virtualNetworkClient.getWaiters()
                                        .forSubnet(getSubnetRequest, Subnet.LifecycleState.Available).execute();
                        Subnet subnet = getSubnetResponse.getSubnet();

                        log.info("????????? ????????? OCID: " + subnet.getId());

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
         * ??????????????? ?????? ?????? ????????? ?????? Network Security Group ??????
         * 
         * @param virtualNetworkClient        Virtual Network Client
         * @param region                        OCI Region Identifier (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId ??????????????? OCID
         * @param vcnId         VCN OCID
         * @param dbname        CloudAG Database ??????
         * @return NetworkSecurityGroup
         */
        public NetworkSecurityGroup createNetworkSecurityGroup(VirtualNetworkClient virtualNetworkClient,
                        String compartmentId, String vcnId, String dbname) throws Exception {
                
                // OCI??? ????????? Network Security Group ??????
                String networkSecurityGroupName = dbname + "-nsg";

                // ????????? Network Security Group ?????? ??????
                CreateNetworkSecurityGroupDetails createNetworkSecurityGroupDetails = CreateNetworkSecurityGroupDetails
                                .builder().compartmentId(compartmentId).displayName(networkSecurityGroupName)
                                .vcnId(vcnId).build();
                
                // Network Security Group ?????? ??????
                CreateNetworkSecurityGroupRequest createNetworkSecurityGroupRequest = CreateNetworkSecurityGroupRequest
                                .builder().createNetworkSecurityGroupDetails(createNetworkSecurityGroupDetails).build();
                
                // Network Security Group ?????? ??????
                CreateNetworkSecurityGroupResponse createNetworkSecurityGroupResponse = virtualNetworkClient
                                .createNetworkSecurityGroup(createNetworkSecurityGroupRequest);
                
                // ????????? Network Security Group ?????? ??????
                GetNetworkSecurityGroupRequest getNetworkSecurityGroupRequest = GetNetworkSecurityGroupRequest.builder()
                                .networkSecurityGroupId(
                                                createNetworkSecurityGroupResponse.getNetworkSecurityGroup().getId())
                                .build();
                
                // ????????? Network Security Group ?????? ??????
                GetNetworkSecurityGroupResponse getNetworkSecurityGroupResponse = virtualNetworkClient.getWaiters()
                                .forNetworkSecurityGroup(getNetworkSecurityGroupRequest,
                                                NetworkSecurityGroup.LifecycleState.Available)
                                .execute();
                
                // ????????? Network Security Group ??????
                NetworkSecurityGroup networkSecurityGroup = getNetworkSecurityGroupResponse.getNetworkSecurityGroup();

                log.info("????????? Network Security Group OCID: " + networkSecurityGroup.getId());

                return networkSecurityGroup;
        }

        /**
         * Network Security Group??? Security Rule ??????
         * 
         * @param virtualNetworkClient        Virtual Network Client
         * @param NetworkSecurityGroup        NetworkSecurityGroup
         * @param networkCidrBlock            ????????? CIDR ??????
         * @param viewerPort                  CloudAG Viewer Port
         * @param dbPort                      CloudAG DB Port
         * @return void
         */
        public void addNetworkSecurityGroupSecurityRules(VirtualNetworkClient virtualNetworkClient,
                        NetworkSecurityGroup networkSecurityGroup, String networkCidrBlock, int viewerPort,
                        int dbPort) {

                // ????????? Security Rule ?????? ?????? (For viewer)
                AddSecurityRuleDetails addSecurityRuleDetailsForViewer = AddSecurityRuleDetails.builder()
                                .description("Incoming HTTP connections for CloudAG Viewer")
                                .direction(Direction.Ingress).protocol("6").source(networkCidrBlock)
                                .sourceType(SourceType.CidrBlock)
                                .tcpOptions(TcpOptions.builder().destinationPortRange(
                                                PortRange.builder().min(viewerPort).max(viewerPort).build()).build())
                                .build();
                
                // ????????? Security Rule ?????? ?????? (For DB)
                AddSecurityRuleDetails addSecurityRuleDetailsForDB = AddSecurityRuleDetails.builder()
                                .description("Incoming HTTP connections for CloudAG Database")
                                .direction(Direction.Ingress).protocol("6").source(networkCidrBlock)
                                .sourceType(SourceType.CidrBlock)
                                .tcpOptions(TcpOptions.builder()
                                                .destinationPortRange(
                                                                PortRange.builder().min(dbPort).max(dbPort).build())
                                                .build())
                                .build();
                
                // Network Security Group??? Security Rule??? ???????????? ?????? ?????? ??????
                AddNetworkSecurityGroupSecurityRulesDetails addNetworkSecurityGroupSecurityRulesDetails = AddNetworkSecurityGroupSecurityRulesDetails
                                .builder().securityRules(Arrays.asList(addSecurityRuleDetailsForViewer,
                                                addSecurityRuleDetailsForDB))
                                .build();
                // Network Security Group??? Security Rule ?????? ??????
                AddNetworkSecurityGroupSecurityRulesRequest addNetworkSecurityGroupSecurityRulesRequest = AddNetworkSecurityGroupSecurityRulesRequest
                                .builder().networkSecurityGroupId(networkSecurityGroup.getId())
                                .addNetworkSecurityGroupSecurityRulesDetails(
                                                addNetworkSecurityGroupSecurityRulesDetails)
                                .build();
                virtualNetworkClient.addNetworkSecurityGroupSecurityRules(addNetworkSecurityGroupSecurityRulesRequest);
        }

        /**
         * Virtual Cloud Network ?????? (????????? ?????? ???????????? ?????? ??????)
         * 
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId ??????????????? OCID
         * @param vcnId            vcn OCID
         * @return String  ????????? VCN OCID ??????
         */
        public String terminateAllVirtualNetwork(String region, String compartmentId, String vcnId) throws Exception {
                VirtualNetworkClient virtualNetworkClient = null;
                try {
                        // VCN ????????? ?????? ???????????????
                        virtualNetworkClient = getVirtualNetworkClient(region);
                        virtualNetworkClient.setRegion(region);

                        // Subnet ????????? ?????? ????????? ?????? ?????? ??????
                        ListSubnetsRequest listSubnetsRequest = ListSubnetsRequest.builder()
                                        .compartmentId(compartmentId).vcnId(vcnId).build();
                        // Subnet ????????? ?????? ????????? ?????? ?????? ??????
                        ListSubnetsResponse listSubnetsResponse = virtualNetworkClient.listSubnets(listSubnetsRequest);

                        // ????????? ?????? ????????? ??????
                        for (Subnet subnet : listSubnetsResponse.getItems()) {
                                String subnetId = subnet.getId();

                                // ????????? ?????? ?????????
                                DeleteSubnetRequest deleteSubnetRequest = DeleteSubnetRequest.builder()
                                                .subnetId(subnetId).build();

                                // ????????? ??????
                                virtualNetworkClient.deleteSubnet(deleteSubnetRequest);


                                // ?????? ????????? ????????? ?????? ??????
                                GetSubnetRequest getSubnetRequest = GetSubnetRequest.builder().subnetId(subnetId)
                                                .build();
                                
                                // ????????? ?????? ?????? (Terminated ?????? ??????)
                                virtualNetworkClient.getWaiters()
                                                .forSubnet(getSubnetRequest, Subnet.LifecycleState.Terminated)
                                                .execute();

                                log.info("????????? [" + subnetId + "] ??? ?????????????????????.");
                        }

                        // ?????? NSG ????????? ?????? NSG ?????? ?????? ??????
                        ListNetworkSecurityGroupsRequest listNetworkSecurityGroupsRequest = ListNetworkSecurityGroupsRequest
                                        .builder().compartmentId(compartmentId).vcnId(vcnId).build();
                        
                        // ?????? NSG ????????? ?????? NSG ?????? ?????? ??????
                        ListNetworkSecurityGroupsResponse listNetworkSecurityGroupsResponse = virtualNetworkClient
                                        .listNetworkSecurityGroups(listNetworkSecurityGroupsRequest);

                        // NSG ??????
                        for (NetworkSecurityGroup networkSecurityGroup : listNetworkSecurityGroupsResponse.getItems()) {
                                String networkSecurityGroupId = networkSecurityGroup.getId();
                                
                                // NSG ?????? ??????
                                DeleteNetworkSecurityGroupRequest deleteNetworkSecurityGroupRequest = DeleteNetworkSecurityGroupRequest
                                                .builder().networkSecurityGroupId(networkSecurityGroupId).build();
                                
                                // NSG ??????
                                virtualNetworkClient.deleteNetworkSecurityGroup(deleteNetworkSecurityGroupRequest);

                                // ?????? ????????? NSG ?????? ??????
                                GetNetworkSecurityGroupRequest getNetworkSecurityGroupRequest = GetNetworkSecurityGroupRequest
                                                .builder().networkSecurityGroupId(networkSecurityGroupId).build();
                                
                                // NSG ?????? ?????? (Terminated ?????? ??????)
                                virtualNetworkClient
                                                .getWaiters()
                                                .forNetworkSecurityGroup(getNetworkSecurityGroupRequest,
                                                                NetworkSecurityGroup.LifecycleState.Terminated)
                                                .execute();

                                log.info("Network Security Group ["
                                                + networkSecurityGroupId
                                                + "] ??? ?????????????????????.");
                        }

                        // Internet Gateway??? Route Table?????? ???????????? ?????? Route Table??? ????????? Route Rule ?????? ?????? ??????
                        ListRouteTablesRequest listRouteTablesRequest = ListRouteTablesRequest.builder()
                                        .compartmentId(compartmentId).vcnId(vcnId).build();
                        
                        // Internet Gateway??? Route Table?????? ???????????? ?????? Route Table??? ????????? Route Rule ?????? ?????? ??????
                        ListRouteTablesResponse listRouteTablesResponse = virtualNetworkClient
                                        .listRouteTables(listRouteTablesRequest);

                        List<RouteRule> routeRules = new ArrayList<>();
                        for (RouteTable routeTable : listRouteTablesResponse.getItems()) {

                                // Route Table?????? Route Rule ????????? ?????? ???????????? ?????? ??????
                                UpdateRouteTableDetails updateRouteTableDetails = UpdateRouteTableDetails.builder()
                                                .routeRules(routeRules).build();
                                
                                // Route Table?????? Route Rule ????????? ?????? ???????????? ??????
                                UpdateRouteTableRequest updateRouteTableRequest = UpdateRouteTableRequest.builder()
                                                .rtId(routeTable.getId())
                                                .updateRouteTableDetails(updateRouteTableDetails).build();

                                // Route Table?????? Route Rule ?????? ??????
                                UpdateRouteTableResponse updateRouteTableResponse = virtualNetworkClient.updateRouteTable(updateRouteTableRequest);
                                log.info("Route Table??? ["+updateRouteTableResponse.getRouteTable().getId()+"] ???????????? ???????????????. (Internet Gateway Route Rule ??????)");
                        }

                        // Internet Gateway ????????? ?????? ?????? Internet Gateway ?????? ?????? ??????
                        ListInternetGatewaysRequest listInternetGatewaysRequest = ListInternetGatewaysRequest.builder().compartmentId(compartmentId).vcnId(vcnId).build();

                        // Internet Gateway ????????? ?????? ?????? Internet Gateway ?????? ?????? ??????
                        ListInternetGatewaysResponse listInternetGatewaysResponse = virtualNetworkClient.listInternetGateways(listInternetGatewaysRequest);

                        for(InternetGateway internetGateway : listInternetGatewaysResponse.getItems()) {
                                String InternetGatewayId = internetGateway.getId();

                                // Internet Gateway ?????? ??????
                                DeleteInternetGatewayRequest deleteInternetGatewayRequest = DeleteInternetGatewayRequest.builder().igId(InternetGatewayId).build();

                                // Internet Gateway ?????? 
                                virtualNetworkClient.deleteInternetGateway(deleteInternetGatewayRequest);


                                // ?????? ????????? Internet Gateway ?????? ??????
                                GetInternetGatewayRequest getInternetGatewayRequest = GetInternetGatewayRequest.builder().igId(InternetGatewayId).build();

                                // ?????? ????????? Internet Gateway ?????? ?????? (Terminated??? ??? ??????)
                                virtualNetworkClient.getWaiters().forInternetGateway(getInternetGatewayRequest, InternetGateway.LifecycleState.Terminated).execute();

                                log.info("Internet Gateway ["+ InternetGatewayId+"]??? ?????????????????????.");
                        }

                        // VCN ?????? ??????
                        DeleteVcnRequest deleteVcnRequest = DeleteVcnRequest.builder().vcnId(vcnId).build();

                        // VCN ??????
                        virtualNetworkClient.deleteVcn(deleteVcnRequest);
                        
                        // ?????? ????????? VCN ?????? ??????
                        GetVcnRequest getVcnRequest = GetVcnRequest.builder().vcnId(vcnId).build();

                        // ?????? ????????? VCN ?????? ?????? (Terminated ???????????? ??????)
                        virtualNetworkClient.getWaiters().forVcn(getVcnRequest, Vcn.LifecycleState.Terminated).execute();
                        
                        log.info("Vcn: [" + vcnId + "]??? ?????????????????????.");

                } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                } finally {
                        virtualNetworkClient.close();
                }
                return vcnId;
        }

}
