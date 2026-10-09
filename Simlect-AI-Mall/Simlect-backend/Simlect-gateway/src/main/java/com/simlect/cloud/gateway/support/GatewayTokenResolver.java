package com.simlect.cloud.gateway.support;

import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

public final class GatewayTokenResolver {

    public static final String TOKEN_WEB = "token";
    public static final String TOKEN_ADMIN = "adminToken";

    private GatewayTokenResolver() {
    }

    public static String resolveWebToken(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String header = request.getHeaders().getFirst(TOKEN_WEB);
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        String query = request.getQueryParams().getFirst(TOKEN_WEB);
        if (StringUtils.hasText(query)) {
            return query.trim();
        }
        return cookieValue(request, TOKEN_WEB);
    }

    public static String resolveAdminToken(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String header = request.getHeaders().getFirst(TOKEN_ADMIN);
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        return cookieValue(request, TOKEN_ADMIN);
    }

    private static String cookieValue(ServerHttpRequest request, String name) {
        MultiValueMap<String, HttpCookie> cookies = request.getCookies();
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }
        HttpCookie cookie = cookies.getFirst(name);
        if (cookie == null || !StringUtils.hasText(cookie.getValue())) {
            return null;
        }
        return cookie.getValue().trim();
    }
}
