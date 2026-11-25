package de.raindancer118.shelved.book;

import java.awt.image.BufferedImage;

/**
 * Publishers hand in 3D mockups to the trade catalogues: the book at a slight angle, a light page block along one
 * side, white around it. Only the front is the cover. Pure white corners mark such a picture; the front is the
 * part where nearly every row and column carries colour. Anything that doesn't come out book-shaped stays as it is.
 */
final class CoverFlattener {

    private static final int WHITE = 245;      // corners at least this bright on every channel
    private static final int INK = 215;        // darker than this counts as cover; the page block is lighter
    private static final double FILL = 0.6;    // share of the fullest row/column a row/column needs to count as front
    private static final double INSET = 0.012; // trims the sliver a slanted edge leaves in the box

    private CoverFlattener() {
    }

    static BufferedImage flatten(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (w < 40 || h < 40 || !white(img, 1, 1) || !white(img, w - 2, 1) || !white(img, 1, h - 2) || !white(img, w - 2, h - 2)) {
            return img;
        }
        int[] rows = new int[h];
        int[] cols = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (ink(img.getRGB(x, y))) {
                    rows[y]++;
                    cols[x]++;
                }
            }
        }
        int[] ys = longestRun(rows), xs = longestRun(cols);
        if (ys == null || xs == null) {
            return img;
        }
        int top = ys[0], bottom = ys[1], left = xs[0], right = xs[1];
        int dx = (int) Math.round((right - left) * INSET), dy = (int) Math.round((bottom - top) * INSET);
        left += dx;
        right -= dx;
        top += dy;
        bottom -= dy;
        int bw = right - left + 1, bh = bottom - top + 1;
        double aspect = (double) bw / bh;
        double share = (double) bw * bh / ((double) w * h);
        // book covers are portrait, between paperback (0.6) and square-ish picture books; the front fills most of it
        if (bw <= 0 || bh <= 0 || aspect < 0.5 || aspect > 0.9 || share < 0.4 || share > 0.97) {
            return img;
        }
        BufferedImage out = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
        out.createGraphics().drawImage(img.getSubimage(left, top, bw, bh), 0, 0, null);
        return out;
    }

    /** The longest stretch of rows (or columns) that are nearly full: the front, not the page block's outline behind a gap. */
    private static int[] longestRun(int[] counts) {
        int limit = Math.max(1, (int) (max(counts) * FILL));
        int[] best = null;
        int start = -1;
        for (int i = 0; i <= counts.length; i++) {
            boolean full = i < counts.length && counts[i] >= limit;
            if (full && start < 0) {
                start = i;
            } else if (!full && start >= 0) {
                if (best == null || i - 1 - start > best[1] - best[0]) {
                    best = new int[]{start, i - 1};
                }
                start = -1;
            }
        }
        return best;
    }

    private static int max(int[] counts) {
        int m = 0;
        for (int c : counts) {
            m = Math.max(m, c);
        }
        return m;
    }

    private static boolean white(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        return ((rgb >> 16) & 255) >= WHITE && ((rgb >> 8) & 255) >= WHITE && (rgb & 255) >= WHITE;
    }

    private static boolean ink(int rgb) {
        return ((rgb >> 16) & 255) < INK || ((rgb >> 8) & 255) < INK || (rgb & 255) < INK;
    }
}
