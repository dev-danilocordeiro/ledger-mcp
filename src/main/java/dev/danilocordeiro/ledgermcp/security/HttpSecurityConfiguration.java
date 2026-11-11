package dev.danilocordeiro.ledgermcp.security;

import static org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer.mcpServerOAuth2;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP transport: every request to /mcp needs a valid JWT from the configured issuer.
 * mcp-server-security adds the Protected Resource Metadata endpoint and the
 * WWW-Authenticate challenge MCP clients use to discover the authorization server.
 * Per-tool scopes are enforced after authentication, by {@code ToolCallAuditor}.
 */
@Configuration
@Profile("http")
class HttpSecurityConfiguration {

    @Bean
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                               @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                               @Value("${ledger.http.validate-audience:false}") boolean validateAudience) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .with(mcpServerOAuth2(), mcp -> mcp
                        .authorizationServer(issuer)
                        .validateAudienceClaim(validateAudience))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
