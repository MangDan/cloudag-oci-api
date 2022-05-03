package net.bitnine.cloudag.api.oracle.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import net.bitnine.cloudag.api.oracle.service.IdentityService;
import net.bitnine.cloudag.api.oracle.util.AuthentificationProvider;

@RestController
@EnableAutoConfiguration
public class IdentityController {
    private static final String MAIN_COMPARTMENT_OCID = "ocid1.compartment.oc1..aaaaaaaaweuqqhj2m5e2fgcaltku4lnt663fxrp3wdcfdfwo3xd4y3wewl2q";

    @Autowired AuthentificationProvider authentificationProvider;

    @Autowired
    private IdentityService identityService;
    
    // 컴파트먼트 생성 컨트롤러
    @RequestMapping(value = "/oci/api/v1/identity/compartment/create", method = RequestMethod.POST)
    public String createCompartment(@RequestBody Map<String, Object> param) throws Exception {

        String region = (String) param.get("region");
        String username = (String) param.get("username");
        // OCI에 Compartment 생성
        String compartmentId = identityService.createCompartment(region, MAIN_COMPARTMENT_OCID, username);

        return compartmentId;
    }

    // 컴파트먼트 삭제 컨트롤러
    @RequestMapping(value = "/oci/api/v1/identity/compartment/delete", method = RequestMethod.POST)
    public void deleteCompartment(@RequestBody Map<String, Object> param) throws Exception {

        String region = (String) param.get("region");
        String compartmentId = (String) param.get("compartmentId");
        // OCI에 Compartment 생성
        identityService.deleteCompartment(region, compartmentId);
    }
}
