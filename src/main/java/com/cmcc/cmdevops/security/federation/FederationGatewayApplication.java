package com.cmcc.cmdevops.security.federation;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(FederationProperties.class)
public class FederationGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(FederationGatewayApplication.class, args);
    }
}
