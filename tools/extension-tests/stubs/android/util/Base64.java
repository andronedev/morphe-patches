package android.util;

public final class Base64 {
    public static final int URL_SAFE = 8;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;

    public static String encodeToString(byte[] input, int flags) {
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input);
        return encoded;
    }
}
