package com.simlect.utils;

import com.simlect.constants.Constants;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieHelper {

    private static final String COOKIE_PATH = "/";

    public String resolveWebToken(HttpServletRequest request) {
        String token = request.getHeader(Constants.TOKEN_WEB);
        if (!StringTools.isEmpty(token)) {
            return token;
        }
        return getCookieValue(request, Constants.TOKEN_WEB);
    }

    public String resolveAdminToken(HttpServletRequest request) {
        String token = request.getHeader(Constants.TOKEN_ADMIN);
        if (!StringTools.isEmpty(token)) {
            return token;
        }
        return getCookieValue(request, Constants.TOKEN_ADMIN);
    }

    public void writeWebTokenCookie(HttpServletRequest request, HttpServletResponse response, String token) {
        writeTokenCookie(request, response, Constants.TOKEN_WEB, token);
    }

    public void writeAdminTokenCookie(HttpServletRequest request, HttpServletResponse response, String token) {
        writeTokenCookie(request, response, Constants.TOKEN_ADMIN, token);
    }

    public void clearWebTokenCookie(HttpServletRequest request, HttpServletResponse response) {
        clearTokenCookie(request, response, Constants.TOKEN_WEB);
    }

    public void clearAdminTokenCookie(HttpServletRequest request, HttpServletResponse response) {
        clearTokenCookie(request, response, Constants.TOKEN_ADMIN);
    }

    private void writeTokenCookie(HttpServletRequest request, HttpServletResponse response, String name, String token) {
        ResponseCookie cookie = ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(request.isSecure())
                .path(COOKIE_PATH)
                .maxAge(Constants.REDIS_KEY_EXPIRES_DAY)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearTokenCookie(HttpServletRequest request, HttpServletResponse response, String name) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .path(COOKIE_PATH)
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && !StringTools.isEmpty(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
