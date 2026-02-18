package com.DronaPay.frm.HealthClaim;

import com.DronaPay.generic.utils.TenantPropertiesUtil;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@Slf4j
public class TokenUtil {

    public static String getToken(String tenantid) {
        String token = "";
        
        try {
            Properties props = TenantPropertiesUtil.getTenantProps(tenantid);
            String filePath = props.getProperty("token.filename");
            Path path = Paths.get(filePath);
		    String tokenFile = Files.readAllLines(path).get(0);
            JSONObject tokenJson = new JSONObject(tokenFile);
            token = tokenJson.optString("tokentype") +  " " + tokenJson.optString("token");
        } catch (Exception e) {
            log.error("Error white getting the bearer token " + e);
        }
        return token;
    }
}
