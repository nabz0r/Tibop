package fr.lettrine.game;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record BetaLink(String origin, String invitation, GameLanguage language) {
    private static final String APP = "lettrine://beta/v1/?";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PARAMETERS = Pattern.compile("origin=([^&]+)(?:&invite=([0-9a-f]{32}))?(?:&lang=(en))?");

    BetaLink(String origin, String invitation) {
        this(origin, invitation, invitation == null ? null : GameLanguage.FRENCH);
    }

    static BetaLink parse(String value) {
        if (value == null || value.length() > 720) return null;
        try {
            if (value.startsWith(APP)) {
                Matcher fields = PARAMETERS.matcher(value.substring(APP.length()));
                if (!fields.matches()) return null;
                return verified(URLDecoder.decode(fields.group(1), StandardCharsets.UTF_8.name()), fields.group(2), fields.group(3));
            }
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !("/beta".equals(path) || "/beta/".equals(path))
                    || uri.getRawQuery() != null) return null;
            String invitation = uri.getRawFragment();
            String language = invitation != null && invitation.startsWith("en/") ? "en" : null;
            return verified("https://" + uri.getRawAuthority(), language == null ? invitation : invitation.substring(3), language);
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException invalid) {
            return null;
        }
    }

    private static BetaLink verified(String address, String invitation, String language) {
        String origin = SocialClient.origin(address);
        if (origin == null || !origin.startsWith("https://")) return null;
        if (invitation == null) return language == null ? new BetaLink(origin, null) : null;
        if (!TOKEN.matcher(invitation).matches()) return null;
        return new BetaLink(origin, invitation, GameLanguage.fromCode(language));
    }
}
