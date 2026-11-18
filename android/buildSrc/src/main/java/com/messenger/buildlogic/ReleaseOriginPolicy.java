package com.messenger.buildlogic;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Build defaults must be public; explicit in-app self-hosting uses its own policy. */
public final class ReleaseOriginPolicy {
    private ReleaseOriginPolicy() {}

    public static boolean isAllowed(String origin, boolean alphaLan) {
        try {
            URI uri = new URI(origin);
            if (!("https".equals(uri.getScheme()) || (alphaLan && "http".equals(uri.getScheme()))) ||
                uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) ||
                !(uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))) return false;
            return alphaLan || isPublicHost(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isPublicHost(String rawHost) throws Exception {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.contains(":")) {
            // Parsing is numeric-only here: never resolve DNS during a build.
            if (host.contains("%")) return false;
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) return isPublicIpv4(bytes);
            int first = bytes[0] & 255;
            return !address.isAnyLocalAddress() && !address.isLoopbackAddress() &&
                !address.isLinkLocalAddress() && !address.isSiteLocalAddress() &&
                !address.isMulticastAddress() && (first & 224) == 32;
        }
        if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) return false; // No shortened/integer IPv4 spellings.
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].length() > 1 && parts[i].startsWith("0")) return false;
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) return false;
                bytes[i] = (byte) value;
            }
            return isPublicIpv4(bytes);
        }
        if (!host.contains(".") || host.matches("(?i).*(^|\\.)0x[0-9a-f]+.*")) return false;
        for (String suffix : new String[] {"localhost", "localdomain", "local", "lan", "internal", "home", "home.arpa"}) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return false;
        }
        return true;
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254) ||
            (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
            (a == 198 && (b == 18 || b == 19)));
    }
}
