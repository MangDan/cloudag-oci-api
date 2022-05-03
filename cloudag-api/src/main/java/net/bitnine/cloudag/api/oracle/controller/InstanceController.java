package net.bitnine.cloudag.api.oracle.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import net.bitnine.cloudag.api.oracle.service.InstanceService;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@Slf4j
@RestController
@EnableAutoConfiguration
public class InstanceController{
    @Autowired AuthentificationProvider authentificationProvider;
    
    @Autowired
    private InstanceService instanceService;

    // 인스턴스 생성 컨트롤러
    @RequestMapping(value = "/oci/api/v1/instance/create", method = RequestMethod.POST)
    public String createInstance(@RequestBody Map<String, Object> param) throws Exception {
        log.info("인스턴스 생성 작업 시작");
        
        String compartmentId = (String) param.get("compartmentId");
        String dbname = (String) param.get("dbname");
        String region = (String) param.get("region");
        String ocpus = (String) param.get("ocpus");
        String memoryInGBs = (String) param.get("memoryInGBs");
        String vpusPerGB = (String) param.get("vpusPerGB"); //10
        String sizeInGBs = (String) param.get("sizeInGBs");

        // Async
        instanceService.createInstance(dbname, region, compartmentId, ocpus, memoryInGBs, vpusPerGB, sizeInGBs);

        return compartmentId;
    }

    // 인스턴스 상세 정보 요청 컨트롤러
    @ResponseBody
    @RequestMapping(value = "/oci/api/v1/instance/get", method = RequestMethod.POST)
    public Map<String, String> getInstance(@RequestBody Map<String, Object> param) throws Exception {

        String region = (String) param.get("region");
        String compartmentId = (String) param.get("compartmentId");
        String instanceId = (String) param.get("instanceId");

        Map<String, String> resultMap = instanceService.getInstance(region, compartmentId, instanceId);
        
        return resultMap;
    }
    
    // 인스턴스와 관련된 모든 자원 삭제를 위한 컨트롤러
    @RequestMapping(value = "/oci/api/v1/instance/terminateAll", method = RequestMethod.POST)
    public String terminateAll(@RequestBody Map<String, Object> param) throws Exception {
        log.info("인스턴스와 관련된 모든 자원에 대한 삭제 작업을 시작합니다.");
        
        String resultMsg = "Start instance terminate.";
        String region = (String) param.get("region");
        String compartmentId = (String) param.get("compartmentId");
        String instanceId = (String) param.get("instanceId");
        String vcnId = (String) param.get("vcnId");
        String volumeId = (String) param.get("volumeId");

        // Async
        instanceService.terminateAll(region, compartmentId, instanceId, volumeId, vcnId);

        return resultMsg;
    }
}
