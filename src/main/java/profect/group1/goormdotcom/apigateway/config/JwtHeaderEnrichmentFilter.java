package profect.group1.goormdotcom.apigateway.config;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
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

    private final ReactiveStringRedisTemplate redisTemplate;

    @Autowired
    public JwtHeaderEnrichmentFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .ofType(JwtAuthenticationToken.class)      // JWT인 경우만 처리
                .flatMap(jwtAuth -> enrichWithJwt(exchange, chain, jwtAuth))
                .switchIfEmpty(chain.filter(exchange));   // 인증 없으면 그냥 통과
    }

    private Mono<Void> enrichWithJwt(ServerWebExchange exchange,
                                     GatewayFilterChain chain,
                                     JwtAuthenticationToken jwtAuth) {

        Jwt jwt = jwtAuth.getToken();
        String jti = jwt.getId();
        if (jti == null || jti.isBlank()) {
            return unauthorized(exchange);
        }

        String key = "access:" + jti;
        String subject = jwt.getSubject(); // user-id
        String role = extractSingleRole(jwt, jwtAuth);

        return redisTemplate.hasKey(key)
                .flatMap(exists -> {
                    if (!Boolean.TRUE.equals(exists)) {
                        return unauthorized(exchange);
                    }

                    ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                            .header("user-id", subject != null ? subject : "");
                    if (role != null && !role.isBlank()) {
                        builder.header("user-roles", role);
                    }

                    ServerHttpRequest mutated = builder.build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .onErrorResume(ex -> unauthorized(exchange));  // Redis 에러 → 401
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

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


