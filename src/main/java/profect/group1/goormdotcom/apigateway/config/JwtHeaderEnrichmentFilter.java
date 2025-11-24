package profect.group1.goormdotcom.apigateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtHeaderEnrichmentFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // SecurityContext의 Authentication을 우선 사용, 없으면 exchange.getPrincipal()로 폴백
        Mono<Authentication> authMono = ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .switchIfEmpty(exchange.getPrincipal()
                        .filter(Authentication.class::isInstance)
                        .cast(Authentication.class));

        return authMono
                .flatMap(auth -> enrichIfJwt(exchange, chain, auth))
                .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Void> enrichIfJwt(ServerWebExchange exchange, GatewayFilterChain chain, Principal principal) {
        if (!(principal instanceof Authentication authentication)) {
            return chain.filter(exchange);
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return chain.filter(exchange);
        }

        Jwt jwt = jwtAuth.getToken();
        String subject = jwt.getSubject(); // user-id 로 사용
        String role = extractSingleRole(jwt, jwtAuth);

        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                .header("user-id", subject != null ? subject : "");
        if (role != null && !role.isBlank()) {
            builder.header("user-roles", role);
        }
        ServerHttpRequest mutatedRequest = builder.build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @SuppressWarnings("unchecked")
    private String extractSingleRole(Jwt jwt, AbstractAuthenticationToken auth) {
        Object rolesClaim = jwt.getClaims().get("role");
        if (rolesClaim instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    @Override
    public int getOrder() {
        // 인증 이후, 라우팅 전에 동작하도록 기본 우선순위
        return 0;
    }
}


