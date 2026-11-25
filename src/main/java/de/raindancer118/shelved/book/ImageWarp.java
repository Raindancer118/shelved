package de.raindancer118.shelved.book;

import java.awt.image.BufferedImage;

/** Turns a photographed rectangle (a back cover, a spine, a fore edge) seen at an angle into a straight picture. */
public final class ImageWarp {

    private ImageWarp() {
    }

    /**
     * @param quad the corners in the photo, in pixels: top left, top right, bottom right, bottom left
     *             ("top" being the head of the book)
     */
    public static BufferedImage straighten(BufferedImage src, double[][] quad) {
        int w = (int) Math.round((dist(quad[0], quad[1]) + dist(quad[3], quad[2])) / 2);
        int h = (int) Math.round((dist(quad[0], quad[3]) + dist(quad[1], quad[2])) / 2);
        if (w < 8 || h < 8) {
            throw new IllegalArgumentException("Fläche zu klein");
        }
        double[] m = homography(new double[][]{{0, 0}, {w, 0}, {w, h}, {0, h}}, quad);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double u = x + 0.5, v = y + 0.5;
                double z = m[6] * u + m[7] * v + 1;
                out.setRGB(x, y, sample(src, (m[0] * u + m[1] * v + m[2]) / z - 0.5, (m[3] * u + m[4] * v + m[5]) / z - 0.5));
            }
        }
        return out;
    }

    /**
     * Rough corners (from a model's estimate) moved onto the face's real outline: each side is shifted, at both ends
     * independently and by up to {@code radius} of the photo's diagonal (or of four times the face's short side), to where inside and outside differ the most
     * along its whole length. The corners are then where neighbouring sides meet.
     */
    public static double[][] refine(BufferedImage img, double[][] quad, double radius) {
        // a narrow face (a spine, a fore edge) gets a small search radius: else its long sides jump onto a line
        // printed on the neighbouring cover
        double shortSide = Math.min(Math.min(dist(quad[0], quad[1]), dist(quad[3], quad[2])), Math.min(dist(quad[0], quad[3]), dist(quad[1], quad[2])));
        int r = Math.max(2, (int) Math.round(radius * Math.min(Math.hypot(img.getWidth(), img.getHeight()), 4 * shortSide)));
        double[][][] sides = new double[4][][];
        for (int i = 0; i < 4; i++) {
            sides[i] = bestSide(img, quad[i], quad[(i + 1) % 4], r);
        }
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] c = intersect(sides[(i + 3) % 4], sides[i]);
            out[i] = c == null || Math.hypot(c[0] - quad[i][0], c[1] - quad[i][1]) > 2.5 * r ? quad[i] : c;
        }
        return out;
    }

    private static double[][] bestSide(BufferedImage img, double[] a, double[] b, int r) {
        double len = dist(a, b);
        double nx = (b[1] - a[1]) / len, ny = -(b[0] - a[0]) / len; // outward for corners in clockwise order
        int coarse = Math.max(1, r / 12);
        int[] best = {0, 0}; // without a clearly stronger edge nearby, the side stays where it was
        double bestScore = edgeScore(img, a[0], a[1], b[0], b[1], nx, ny);
        for (int pass = 0; pass < 2; pass++) {
            int step = pass == 0 ? coarse : 1;
            int ca = best[0], cb = best[1], span = pass == 0 ? r : coarse;
            for (int sa = ca - span; sa <= ca + span; sa += step) {
                for (int sb = cb - span; sb <= cb + span; sb += step) {
                    double score = edgeScore(img, a[0] + sa * nx, a[1] + sa * ny, b[0] + sb * nx, b[1] + sb * ny, nx, ny);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new int[]{sa, sb};
                    }
                }
            }
        }
        return new double[][]{{a[0] + best[0] * nx, a[1] + best[0] * ny}, {b[0] + best[1] * nx, b[1] + best[1] * ny}};
    }

    /** How much the colour changes across the line, summed along its middle 80 %. */
    private static double edgeScore(BufferedImage img, double ax, double ay, double bx, double by, double nx, double ny) {
        double sum = 0;
        for (int k = 1; k <= 24; k++) {
            double t = 0.1 + 0.8 * k / 25;
            double x = ax + (bx - ax) * t, y = ay + (by - ay) * t;
            int out = px(img, (int) Math.round(x + 2 * nx), (int) Math.round(y + 2 * ny));
            int in = px(img, (int) Math.round(x - 2 * nx), (int) Math.round(y - 2 * ny));
            sum += Math.abs(((out >> 16) & 255) - ((in >> 16) & 255)) + Math.abs(((out >> 8) & 255) - ((in >> 8) & 255))
                    + Math.abs((out & 255) - (in & 255));
        }
        return sum;
    }

    private static double[] intersect(double[][] l1, double[][] l2) {
        double x1 = l1[0][0], y1 = l1[0][1], x2 = l1[1][0], y2 = l1[1][1];
        double x3 = l2[0][0], y3 = l2[0][1], x4 = l2[1][0], y4 = l2[1][1];
        double d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(d) < 1e-9) {
            return null;
        }
        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d;
        return new double[]{x1 + t * (x2 - x1), y1 + t * (y2 - y1)};
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** The 3x3 projective map (last entry 1) taking the four {@code from} points onto the four {@code to} points. */
    static double[] homography(double[][] from, double[][] to) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = from[i][0], y = from[i][1], X = to[i][0], Y = to[i][1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -x * X, -y * X, X};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -x * Y, -y * Y, Y};
        }
        for (int c = 0; c < 8; c++) { // Gaussian elimination with partial pivoting
            int p = c;
            for (int r = c + 1; r < 8; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[p][c])) {
                    p = r;
                }
            }
            double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            if (Math.abs(a[c][c]) < 1e-12) {
                throw new IllegalArgumentException("Ecken liegen auf einer Linie");
            }
            for (int r = 0; r < 8; r++) {
                if (r != c) {
                    double f = a[r][c] / a[c][c];
                    for (int k = c; k < 9; k++) {
                        a[r][k] -= f * a[c][k];
                    }
                }
            }
        }
        double[] m = new double[8];
        for (int i = 0; i < 8; i++) {
            m[i] = a[i][8] / a[i][i];
        }
        return m;
    }

    private static int sample(BufferedImage img, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        int c00 = px(img, x0, y0), c10 = px(img, x0 + 1, y0), c01 = px(img, x0, y0 + 1), c11 = px(img, x0 + 1, y0 + 1);
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double top = ((c00 >> shift) & 255) * (1 - fx) + ((c10 >> shift) & 255) * fx;
            double bottom = ((c01 >> shift) & 255) * (1 - fx) + ((c11 >> shift) & 255) * fx;
            rgb |= ((int) Math.round(top * (1 - fy) + bottom * fy)) << shift;
        }
        return rgb;
    }

    private static int px(BufferedImage img, int x, int y) {
        return img.getRGB(Math.clamp(x, 0, img.getWidth() - 1), Math.clamp(y, 0, img.getHeight() - 1));
    }
}
