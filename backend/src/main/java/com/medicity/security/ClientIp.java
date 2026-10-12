package com.medicity.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The caller's address, as the audit log and the per-address limits see it.
 *
 * <p>Behind the hosting proxy, Tomcat's RemoteIpValve
 * ({@code server.forward-headers-strategy: native}) resolves the forwarded
 * client address, trusting {@code X-Forwarded-For} only from the proxies listed
 * in {@code server.tomcat.remoteip.internal-proxies}, so a client cannot spoof
 * its address with its own header. If that list does not include the real
 * proxy, this returns the proxy's address instead (see the README).
 */
public final class ClientIp {

    private ClientIp() {}

    /** Absent outside an HTTP request (a scheduled job, a test calling a service). */
    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
