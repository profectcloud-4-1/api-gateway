package profect.group1.goormdotcom.apigateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;

import java.util.List;

@EnableWebFluxSecurity
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                            ReactiveJwtDecoder jwtDecoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/api/v1/users/login", "/api/v1/users/register").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, e) -> {
                            log.warn("401 Unauthorized: path={}, reason={}, ex={}",
                                    exchange.getRequest().getPath(), e.getMessage(),
                                    e.getClass().getSimpleName());
                            return Mono.fromRunnable(() ->
                                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED));
                        })
                        .accessDeniedHandler((exchange, e) -> {
                            log.warn("403 AccessDenied: path={}, reason={}, ex={}",
                                    exchange.getRequest().getPath(), e.getMessage(),
                                    e.getClass().getSimpleName());
                            return Mono.fromRunnable(() ->
                                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN));
                        })
                )
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${security.jwt.audience}") String audience
    ) {
        ReactiveJwtDecoder reactiveJwtDecoder;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            reactiveJwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
            log.info("JWT decoder initialized with explicit jwk-set-uri");
        } else if (issuer != null && !issuer.isBlank()) {
            reactiveJwtDecoder = ReactiveJwtDecoders.fromIssuerLocation(issuer);
            log.info("JWT decoder initialized from issuer location: {}", issuer);
        } else {
            throw new IllegalArgumentException("Either jwk-set-uri or issuer-uri must be provided");
        }

        if (reactiveJwtDecoder instanceof NimbusReactiveJwtDecoder nimbus) {
            var withIssuer = (issuer != null && !issuer.isBlank())
                    ? JwtValidators.createDefaultWithIssuer(issuer)
                    : JwtValidators.createDefault();
            var withAudience = new DelegatingOAuth2TokenValidator<Jwt>(withIssuer, new AudienceValidator(audience));
            nimbus.setJwtValidator(withAudience);
            log.info("JWT validators configured: issuer={}, audience={}", issuer, audience);
        }
        return reactiveJwtDecoder;
    }

    static class AudienceValidator implements OAuth2TokenValidator<Jwt> {
        private static final Logger LOG = LoggerFactory.getLogger(AudienceValidator.class);
        private final String requiredAudience;

        AudienceValidator(String requiredAudience) {
            this.requiredAudience = requiredAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            List<String> audiences = token.getAudience();
            if (!CollectionUtils.isEmpty(audiences) && audiences.contains(requiredAudience)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Audience validation success: jti={}, sub={}, aud={}",
                            token.getId(), token.getSubject(), audiences);
                }
                return OAuth2TokenValidatorResult.success();
            }
            OAuth2Error error = new OAuth2Error(
                    "invalid_token",
                    "The required audience is missing or invalid",
                    null
            );
            LOG.warn("Audience validation failed: jti={}, sub={}, aud={}, required={}",
                    token.getId(), token.getSubject(), audiences, requiredAudience);
            return OAuth2TokenValidatorResult.failure(error);
        }
    }
}


