package com.arkaces.eth_ark_channel_service;

import ark_java_client.ArkClient;
import ark_java_client.ArkNetwork;
import ark_java_client.ArkNetworkFactory;
import ark_java_client.HttpArkClientFactory;
import com.arkaces.ApiClient;
import com.arkaces.aces_listener_api.AcesListenerApi;
import com.arkaces.aces_server.aces_service.config.AcesServiceConfig;
import com.arkaces.aces_server.ark_auth.ArkAuthConfig;
import com.arkaces.aces_server.common.api_key_generation.ApiKeyGenerator;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@EntityScan
@Configuration
@EnableScheduling
@EnableJpaRepositories
@Import({AcesServiceConfig.class, ArkAuthConfig.class})
public class ApplicationConfig {

    @Bean
    public ArkClient arkClient(Environment environment) {
        ArkNetworkFactory arkNetworkFactory = new ArkNetworkFactory();
        String arkNetworkConfigPath = environment.getProperty("arkNetworkConfigPath");
        ArkNetwork arkNetwork = arkNetworkFactory.createFromYml(arkNetworkConfigPath);
        HttpArkClientFactory httpArkClientFactory = new HttpArkClientFactory();
        return httpArkClientFactory.create(arkNetwork);
    }

    @Bean
    public AcesListenerApi ethereumListener(Environment environment) {
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(environment.getProperty("ethereumListener.url"));
        if (environment.containsProperty("ethereumListener.apikey")) {
            apiClient.setUsername("token");
            apiClient.setPassword(environment.getProperty("ethereumListener.apikey"));
        }
        return new AcesListenerApi(apiClient);
    }

    @Bean
    public String ethEventCallbackUrl(Environment environment) {
        return environment.getProperty("ethEventCallbackUrl");
    }

    @Bean
    public Integer ethMinConfirmations(Environment environment) {
        return Integer.parseInt(environment.getProperty("ethMinConfirmations"));
    }

    @Bean
    public ApiKeyGenerator apiKeyGenerator() {
        return new ApiKeyGenerator();
    }

    @Bean
    public RestTemplate ethereumRpcRestTemplate(Environment environment) {
        return new RestTemplateBuilder().rootUri(environment.getProperty("ethRpcRootUri")).build();
    }
}
