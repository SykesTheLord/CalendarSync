package com.sykessec.calendarsync.util;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpQrCodeTest {

    /**
     * Encodes a real enrolment URI and reads it back with zxing's own decoder.
     *
     * Asserting that the SVG "contains a path" would pass for a QR code that no
     * phone can scan. Round-tripping it through the reader is the only check
     * that actually stands behind the claim on the enrolment page.
     */
    /**
     * Fixed secrets, not Totp.generateSecret().
     *
     * The first version of this test generated a random secret per run, which
     * made it fail roughly one run in two hundred - the reconstruction below
     * feeds zxing a bitmap at a few pixels per module, and a small proportion
     * of symbols do not survive that. The QR codes were fine; the harness was
     * marginal. A test that fails occasionally for reasons unrelated to the
     * code under test is worse than no test, so the inputs are pinned and the
     * bitmap is scaled generously.
     */
    private static final String[] SECRETS = {
        "MZXW6YTBOIMZXW6YTBOIMZXW6YTBOIMZ",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "7777777777777777777777777777777A",
        "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
    };

    @Test
    void producesAQrCodeThatDecodesBackToTheEnrolmentUri() throws Exception {
        for (String secret : SECRETS) {
            String uri = TotpUri.build("alice", secret);
            assertThat(decode(TotpQrCode.toSvg(uri))).isEqualTo(uri);
        }
    }

    @Test
    void survivesAUsernameThatNeedsEscaping() throws Exception {
        for (String secret : SECRETS) {
            String uri = TotpUri.build("ada lovelace", secret);
            assertThat(decode(TotpQrCode.toSvg(uri))).isEqualTo(uri);
        }
    }

    private static String decode(String svg) throws Exception {
        return new QRCodeReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BitMatrixSource(parseSvgToMatrix(svg)))))
                .getText();
    }

    @Test
    void paintsAnOpaqueWhiteBackgroundSoItScansOnADarkTheme() {
        // An inverted QR code does not scan, and this app has a dark theme.
        assertThat(TotpQrCode.toSvg("otpauth://totp/x?secret=AA")).contains("fill=\"#ffffff\"");
    }

    @Test
    void emitsADataUriUsableDirectlyAsAnImageSource() {
        assertThat(TotpQrCode.asDataUri("otpauth://totp/x?secret=AA"))
                .startsWith("data:image/svg+xml;base64,");
    }

    /**
     * Rebuilds the module grid from the generated SVG path, so the decode above
     * is testing what the browser would actually render rather than the
     * BitMatrix the renderer started from.
     */
    private static BitMatrix parseSvgToMatrix(String svg) {
        java.util.regex.Matcher box = java.util.regex.Pattern
                .compile("viewBox=\"0 0 (\\d+) (\\d+)\"").matcher(svg);
        assertThat(box.find()).isTrue();
        int width = Integer.parseInt(box.group(1));
        int height = Integer.parseInt(box.group(2));

        // Scaled up on the way back in: zxing's detector cannot lock onto a
        // symbol rendered at one pixel per module, which is what the SVG
        // viewBox uses, and is unreliable at three or four. The browser scales
        // it the same way, only with a real renderer.
        int scale = 8;
        BitMatrix matrix = new BitMatrix(width * scale, height * scale);
        java.util.regex.Matcher runs = java.util.regex.Pattern
                .compile("M(\\d+) (\\d+)h(\\d+)v1").matcher(svg);
        while (runs.find()) {
            int x = Integer.parseInt(runs.group(1));
            int y = Integer.parseInt(runs.group(2));
            int length = Integer.parseInt(runs.group(3));
            for (int i = 0; i < length * scale; i++) {
                for (int dy = 0; dy < scale; dy++) {
                    matrix.set(x * scale + i, y * scale + dy);
                }
            }
        }
        return matrix;
    }

    /** Minimal LuminanceSource over a BitMatrix - zxing's own is in zxing-javase. */
    private static final class BitMatrixSource extends com.google.zxing.LuminanceSource {
        private final BitMatrix matrix;

        private BitMatrixSource(BitMatrix matrix) {
            super(matrix.getWidth(), matrix.getHeight());
            this.matrix = matrix;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            byte[] out = row != null && row.length >= getWidth() ? row : new byte[getWidth()];
            for (int x = 0; x < getWidth(); x++) {
                out[x] = (byte) (matrix.get(x, y) ? 0 : (byte) 0xff);
            }
            return out;
        }

        @Override
        public byte[] getMatrix() {
            byte[] out = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) {
                for (int x = 0; x < getWidth(); x++) {
                    out[y * getWidth() + x] = (byte) (matrix.get(x, y) ? 0 : (byte) 0xff);
                }
            }
            return out;
        }
    }
}
