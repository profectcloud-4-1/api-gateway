package profect.group1.goormdotcom.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import java.util.List;

@EnableWebFluxSecurity
@Configuration
public class SecurityConfig {

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
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${security.jwt.audience}") String audience
    ) {
        // 1) jwk-set-uri가 주어지면 OIDC 디스커버리 없이 직접 JWKS를 사용합니다.
        // 2) 아니면 issuer 기반으로 OIDC 디스커버리를 통해 JWKS를 가져옵니다.
        ReactiveJwtDecoder reactiveJwtDecoder;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            reactiveJwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } else if (issuer != null && !issuer.isBlank()) {
            // issuer 기반으로 OIDC 디스커버리 문서(/.well-known/openid-configuration)를 조회하고,
            // 거기서 jwks_uri를 따라 JWKS(공개키 세트)를 자동으로 내려받아 서명 검증에 사용합니다.
            reactiveJwtDecoder = ReactiveJwtDecoders.fromIssuerLocation(issuer);
        } else {
            throw new IllegalArgumentException("Either jwk-set-uri or issuer-uri must be provided");
        }

        if (reactiveJwtDecoder instanceof NimbusReactiveJwtDecoder nimbus) {
            var withIssuer = (issuer != null && !issuer.isBlank())
                    ? JwtValidators.createDefaultWithIssuer(issuer)
                    : JwtValidators.createDefault();
            var withAudience = new DelegatingOAuth2TokenValidator<Jwt>(withIssuer, new AudienceValidator(audience));
            nimbus.setJwtValidator(withAudience);
        }
        return reactiveJwtDecoder;
    }

    static class AudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String requiredAudience;

        AudienceValidator(String requiredAudience) {
            this.requiredAudience = requiredAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            // aud 클레임에 필수 audience 값이 포함되어 있는지 검증합니다.
            // 포함되어 있지 않으면 invalid_token 에러로 실패 처리합니다.
            List<String> audiences = token.getAudience();
            if (!CollectionUtils.isEmpty(audiences) && audiences.contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            OAuth2Error error = new OAuth2Error(
                    "invalid_token",
                    "The required audience is missing or invalid",
                    null
            );
            return OAuth2TokenValidatorResult.failure(error);
        }
    }
}


