package profect.group1.goormdotcom.apigateway.config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Map;

@Component
public class JwtHeaderEnrichmentFilter implements GlobalFilter, Ordered {

    // Redis 검증 제거: 의존성/주입 제거

    private static final Logger log = LoggerFactory.getLogger(JwtHeaderEnrichmentFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (log.isDebugEnabled()) {
            log.debug("Gateway filter invoked: path={}, hasAuthorization={}",
                    exchange.getRequest().getPath(), authHeader != null);
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .ofType(JwtAuthenticationToken.class)      // JWT인 경우만 처리
                .flatMap(jwtAuth -> enrichWithJwt(exchange, chain, jwtAuth))
                .switchIfEmpty(Mono.defer(() -> {
                    if (log.isDebugEnabled()) {
                        log.debug("No JwtAuthentication in SecurityContext: path={}", exchange.getRequest().getPath());
                    }
                    return chain.filter(exchange);
                }));   // 인증 없으면 그냥 통과
    }

    private Mono<Void> enrichWithJwt(ServerWebExchange exchange,
                                     GatewayFilterChain chain,
                                     JwtAuthenticationToken jwtAuth) {

        Jwt jwt = jwtAuth.getToken();
        String subject = jwt.getSubject(); // user-id
        String role = extractSingleRole(jwt, jwtAuth);

        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                .header("user-id", subject != null ? subject : "");
        if (role != null && !role.isBlank()) {
            builder.header("user-roles", role);
        }

        ServerHttpRequest mutated = builder.build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    // 401 처리 제거

    private String extractSingleRole(Jwt jwt, AbstractAuthenticationToken auth) {
        Object rolesClaim = jwt.getClaims().get("role");
        if (rolesClaim instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}


