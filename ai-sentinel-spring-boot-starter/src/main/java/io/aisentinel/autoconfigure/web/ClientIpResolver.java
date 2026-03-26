package io.aisentinel.autoconfigure.web;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the client IP from a request when the direct remote address is a trusted proxy.
 * Uses rightmost-untrusted selection for X-Forwarded-For and Forwarded (RFC 7239) headers.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /**
     * If {@code trustedProxyEntries} is empty, returns {@link HttpServletRequest#getRemoteAddr()}.
     * If remote is not trusted, returns remote (headers ignored — spoof resistance).
     * Otherwise parses X-Forwarded-For, then Forwarded, then X-Real-IP; falls back to remote.
     */
    public static String resolveClientIp(HttpServletRequest request, List<String> trustedProxyEntries) {
        String remote = request.getRemoteAddr();
        if (remote == null) {
            return "";
        }
        if (trustedProxyEntries == null || trustedProxyEntries.isEmpty()) {
            return remote;
        }
        if (!isTrustedProxy(remote, trustedProxyEntries)) {
            return remote;
        }
        String fromXff = resolveFromXForwardedFor(request.getHeader("X-Forwarded-For"), trustedProxyEntries);
        if (fromXff != null) {
            return fromXff;
        }
        String fromForwarded = resolveFromForwardedHeaders(request, trustedProxyEntries);
        if (fromForwarded != null) {
            return fromForwarded;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return stripIpv6Brackets(realIp.trim());
        }
        return remote;
    }

    static String resolveFromXForwardedFor(String xff, List<String> trustedProxyEntries) {
        if (xff == null || xff.isBlank()) {
            return null;
        }
        List<String> parts = splitCommaList(xff);
        if (parts.isEmpty()) {
            return null;
        }
        for (int i = parts.size() - 1; i >= 0; i--) {
            String ip = normalizeIpToken(parts.get(i));
            if (ip.isEmpty()) {
                continue;
            }
            if (!isTrustedProxy(ip, trustedProxyEntries)) {
                return ip;
            }
        }
        return normalizeIpToken(parts.get(0));
    }

    static String resolveFromForwardedHeaders(HttpServletRequest request, List<String> trustedProxyEntries) {
        List<String> forValues = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders("Forwarded");
        if (headers != null) {
            while (headers.hasMoreElements()) {
                collectForwardedFor(headers.nextElement(), forValues);
            }
        }
        if (forValues.isEmpty()) {
            String single = request.getHeader("Forwarded");
            if (single != null) {
                collectForwardedFor(single, forValues);
            }
        }
        if (forValues.isEmpty()) {
            return null;
        }
        for (int i = forValues.size() - 1; i >= 0; i--) {
            String ip = normalizeIpToken(forValues.get(i));
            if (ip.isEmpty()) {
                continue;
            }
            if (!isTrustedProxy(ip, trustedProxyEntries)) {
                return ip;
            }
        }
        return normalizeIpToken(forValues.get(0));
    }

    static void collectForwardedFor(String headerValue, List<String> out) {
        if (headerValue == null || headerValue.isBlank()) {
            return;
        }
        int i = 0;
        int n = headerValue.length();
        while (i < n) {
            while (i < n && (headerValue.charAt(i) == ' ' || headerValue.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) break;
            int depth = 0;
            int segStart = i;
            while (i < n) {
                char c = headerValue.charAt(i);
                if (c == '"') {
                    depth = (depth + 1) % 2;
                } else if (c == ',' && depth == 0) {
                    break;
                }
                i++;
            }
            String segment = headerValue.substring(segStart, i).trim();
            if (!segment.isEmpty()) {
                extractForFromSegment(segment, out);
            }
            if (i < n && headerValue.charAt(i) == ',') {
                i++;
            }
        }
    }

    private static void extractForFromSegment(String segment, List<String> out) {
        String[] pieces = segment.split(";");
        for (String piece : pieces) {
            String p = piece.trim();
            if (p.length() < 4) continue;
            String lower = p.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("for=")) continue;
            String raw = p.substring(4).trim();
            String v = unwrapQuoted(raw);
            v = stripIpv6Brackets(v);
            if (v.startsWith("unix:") || v.startsWith("obfuscated")) {
                continue;
            }
            int colon = v.lastIndexOf(':');
            if (colon > 0 && v.indexOf(':') == colon && !v.contains("::") && v.indexOf('.') >= 0) {
                v = v.substring(0, colon);
            }
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
    }

    private static String unwrapQuoted(String raw) {
        String v = raw;
        if (v.startsWith("\"")) {
            int end = v.indexOf('"', 1);
            if (end > 1) {
                v = v.substring(1, end);
            }
        }
        return v.trim();
    }

    private static List<String> splitCommaList(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ',')) i++;
            if (i >= n) break;
            int start = i;
            while (i < n && s.charAt(i) != ',') i++;
            String part = s.substring(start, i).trim();
            if (!part.isEmpty()) out.add(part);
            if (i < n && s.charAt(i) == ',') i++;
        }
        return out;
    }

    static String normalizeIpToken(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("\"") && t.length() > 2) {
            int end = t.indexOf('"', 1);
            if (end > 1) t = t.substring(1, end);
        }
        return stripIpv6Brackets(t.trim());
    }

    private static String stripIpv6Brackets(String ip) {
        if (ip != null && ip.startsWith("[") && ip.contains("]")) {
            int end = ip.indexOf(']');
            if (end > 1) {
                return ip.substring(1, end);
            }
        }
        return ip;
    }

    static boolean isTrustedProxy(String ip, List<String> trustedProxyEntries) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        for (String pattern : trustedProxyEntries) {
            if (pattern == null || pattern.isBlank()) continue;
            String p = pattern.trim();
            if (p.equalsIgnoreCase(ip)) {
                return true;
            }
            if (p.contains("/") && matchesCidr(ip, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCidr(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash >= cidr.length() - 1) {
            return false;
        }
        String network = cidr.substring(0, slash).trim();
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            InetAddress net = InetAddress.getByName(network);
            byte[] ab = addr.getAddress();
            byte[] nb = net.getAddress();
            if (ab.length != nb.length) {
                return false;
            }
            int bitsTotal = ab.length * 8;
            if (prefix < 0 || prefix > bitsTotal) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ab[i] != nb[i]) return false;
            }
            if (remBits == 0) return true;
            int mask = (0xFF << (8 - remBits)) & 0xFF;
            return (ab[fullBytes] & mask) == (nb[fullBytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }
}
