package com.sykessec.calendarsync.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Renders an otpauth:// URI as a QR code, as an inline SVG data URI.
 *
 * SVG rather than PNG so that zxing-javase is not needed: that artifact writes
 * through BufferedImage and ImageIO, i.e. the java.desktop module, which is a
 * lot of desktop graphics stack to carry in a headless server image in order to
 * draw black squares. A BitMatrix is already a grid of booleans, so emitting
 * one <rect> per run of dark modules is a dozen lines and scales to whatever
 * size the page asks for without going blurry on a phone.
 *
 * Delivered as a data: URI rather than from an HTTP endpoint, for three
 * reasons. A new MVC endpoint would need its own authorizeHttpRequests rule or
 * VaadinSecurityConfigurer answers it with a bare 403 (the same trap the
 * /oauth2/** rule documents); it would need its own authorization so that one
 * user cannot fetch another's QR; and it would put a TOTP secret in a URL,
 * which is the kind of thing that ends up in an access log. Built inside an
 * already-authenticated view, the image is never a fetchable resource at all.
 */
public final class TotpQrCode {

    /** Quiet zone in modules. Below four, some scanners will not lock on. */
    private static final int QUIET_ZONE = 4;

    private TotpQrCode() {
    }

    /** A ready-to-use value for an &lt;img src&gt;. */
    public static String asDataUri(String otpauthUri) {
        String svg = toSvg(otpauthUri);
        return "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    static String toSvg(String otpauthUri) {
        BitMatrix matrix;
        try {
            // Size 0 lets zxing choose the smallest symbol that fits, so the
            // viewBox below is in modules and the browser does the scaling.
            matrix = new QRCodeWriter().encode(otpauthUri, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, QUIET_ZONE,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        } catch (WriterException e) {
            throw new IllegalStateException("Could not encode the enrolment QR code", e);
        }

        int width = matrix.getWidth();
        int height = matrix.getHeight();
        StringBuilder svg = new StringBuilder(1024);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height)
                .append("\" shape-rendering=\"crispEdges\" role=\"img\">");
        // White is painted explicitly: a transparent background inverts against
        // this app's dark theme, and an inverted QR code will not scan.
        svg.append("<rect width=\"").append(width).append("\" height=\"").append(height)
                .append("\" fill=\"#ffffff\"/>");
        svg.append("<path fill=\"#000000\" d=\"");
        for (int y = 0; y < height; y++) {
            int runStart = -1;
            for (int x = 0; x <= width; x++) {
                boolean dark = x < width && matrix.get(x, y);
                if (dark && runStart < 0) {
                    runStart = x;
                } else if (!dark && runStart >= 0) {
                    // One horizontal run becomes one path segment rather than
                    // one rect per module; on a 45x45 symbol that is the
                    // difference between ~300 nodes and ~2000.
                    svg.append('M').append(runStart).append(' ').append(y)
                            .append('h').append(x - runStart).append("v1H").append(runStart).append('z');
                    runStart = -1;
                }
            }
        }
        svg.append("\"/></svg>");
        return svg.toString();
    }
}
