package net.bitnine.cloudag.api.oracle.service;

import com.oracle.bmc.computeinstanceagent.ComputeInstanceAgentClient;
import com.oracle.bmc.computeinstanceagent.model.CreateInstanceAgentCommandDetails;
import com.oracle.bmc.computeinstanceagent.model.InstanceAgentCommandContent;
import com.oracle.bmc.computeinstanceagent.model.InstanceAgentCommandExecution;
import com.oracle.bmc.computeinstanceagent.model.InstanceAgentCommandOutputViaTextDetails;
import com.oracle.bmc.computeinstanceagent.model.InstanceAgentCommandSourceViaTextDetails;
import com.oracle.bmc.computeinstanceagent.model.InstanceAgentCommandTarget;
import com.oracle.bmc.computeinstanceagent.requests.CreateInstanceAgentCommandRequest;
import com.oracle.bmc.computeinstanceagent.requests.GetInstanceAgentCommandExecutionRequest;
import com.oracle.bmc.computeinstanceagent.responses.CreateInstanceAgentCommandResponse;
import com.oracle.bmc.computeinstanceagent.responses.GetInstanceAgentCommandExecutionResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@Slf4j
@Service
public class InstanceAgentCommandService {
        @Autowired
        AuthentificationProvider authentificationProvider;

        /**
         * 인스턴스 생성 후 인스턴스내에서 스크립트 실행을 위한 Agent
         * 인스턴스 생성 후 자동으로 블록 볼륨 마운트 실행
         * 
         * @param region        OCI Region Identifier
         *                      (https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm)
         * @param compartmentId 컴파트먼트 OCID
         * @param dbname        CloudAG Database 이름
         * @param instanceId    스크립트가 실행되는 인스턴스의 OCID
         * @return void
         */
        @Async
        public void createAndExecutionInstanceAgentCommand(String region, String compartmentId, String dbname,
                        String instanceId) throws Exception {

                // OCI에 생성되는 Instance Agent Command 이름
                String instanceAgentCommandName = dbname + "-agent-command";
                ComputeInstanceAgentClient computeInstanceAgentClient = null;

                try {   
                        // Instance Agent Command Agent Client
                        computeInstanceAgentClient = new ComputeInstanceAgentClient(
                                        authentificationProvider.getAuthenticationDetailsProvider());
                        // 클라이언트를 특정 리전에서 사용하도록 설정
                        computeInstanceAgentClient.setRegion(region);

                        // 실행하기 위한 커맨트 텍스트 ()
                        String command = "sudo /usr/sbin/mkfs.xfs -f /dev/oracleoci/oraclevdb \n"
                                        + "sudo /usr/bin/mount /dev/oracleoci/oraclevdb /mnt/vol1 \n"
                                        + "sudo /usr/bin/mount -a \n"
                                        + "sudo cp -rp /home/agens/agensdata /mnt/vol1 \n";
                        
                        // 커맨드 실행을 위한 기본 정보 설정 (컴파트먼트 아이디, 타임아웃, Instance Agent Command 이름, 실행할 타겟 (인스턴스 아이디), 실행할 커맨드 텍스트)
                        CreateInstanceAgentCommandDetails createInstanceAgentCommandDetails = CreateInstanceAgentCommandDetails
                                        .builder().compartmentId(compartmentId).executionTimeOutInSeconds(3600)
                                        .displayName(instanceAgentCommandName)
                                        .target(InstanceAgentCommandTarget.builder().instanceId(instanceId).build())
                                        .content(InstanceAgentCommandContent.builder()
                                                        .source(InstanceAgentCommandSourceViaTextDetails.builder()
                                                                        .text(command).build())
                                                        .output(InstanceAgentCommandOutputViaTextDetails.builder()
                                                                        .build())
                                                        .build())
                                        .build();

                        // 커맨드 실행 요청
                        CreateInstanceAgentCommandRequest createInstanceAgentCommandRequest = CreateInstanceAgentCommandRequest
                                        .builder().createInstanceAgentCommandDetails(createInstanceAgentCommandDetails)
                                        .build();

                        // 커맨드 실행 응답
                        CreateInstanceAgentCommandResponse createInstanceAgentCommandResponse = computeInstanceAgentClient
                                        .createInstanceAgentCommand(createInstanceAgentCommandRequest);

                        // 커맨드 실행에 대한 정보 요청
                        GetInstanceAgentCommandExecutionRequest getInstanceAgentCommandExecutionRequest = GetInstanceAgentCommandExecutionRequest
                                        .builder()
                                        .instanceAgentCommandId(createInstanceAgentCommandResponse
                                                        .getInstanceAgentCommand().getId())
                                        .instanceId(instanceId).build();

                        log.info("Run command[" + createInstanceAgentCommandResponse.getInstanceAgentCommand().getId()
                                        + "] 실행중...");

                        // 커맨드가 특정 상태(Succeeded, Failed)가 되는 경우 응답
                        GetInstanceAgentCommandExecutionResponse getInstanceAgentCommandExecutionResponse = computeInstanceAgentClient
                                        .getWaiters()
                                        .forInstanceAgentCommandExecution(getInstanceAgentCommandExecutionRequest,
                                                        InstanceAgentCommandExecution.LifecycleState.Succeeded,
                                                        InstanceAgentCommandExecution.LifecycleState.Failed)
                                        .execute();

                        log.info("커맨드 실행 인스턴스 OCID: " + instanceId);
                        log.info("커맨드 실행 결과: ");
                        log.info("  - Delivery State: " + getInstanceAgentCommandExecutionResponse
                                        .getInstanceAgentCommandExecution().getDeliveryState().getValue());
                        log.info("  - Lifecycle State: " + getInstanceAgentCommandExecutionResponse
                                        .getInstanceAgentCommandExecution().getLifecycleState());

                        log.info("Run command[" + createInstanceAgentCommandResponse.getInstanceAgentCommand().getId()
                                        + "] 실행완료");

                        if(getInstanceAgentCommandExecutionResponse.getInstanceAgentCommandExecution().getLifecycleState() == InstanceAgentCommandExecution.LifecycleState.Failed) {
                                log.info("커맨드[" + createInstanceAgentCommandResponse.getInstanceAgentCommand().getId()
                                        + "]가 실행을 실패하였습니다.");
                                log.info("CloudAG ["+instanceId+"] 인스턴스를 확인하십시요.");
                        }
                        else {
                                log.info("커맨드[" + createInstanceAgentCommandResponse.getInstanceAgentCommand().getId()
                                        + "]가 성공적으로 실행되었습니다.");
                                log.info("CloudAG ["+instanceId+"] 서비스 사용 준비 완료.");
                        }

                } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                } finally {
                        computeInstanceAgentClient.close();
                }
        }
}
