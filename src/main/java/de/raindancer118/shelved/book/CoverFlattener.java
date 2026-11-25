package de.raindancer118.shelved.book;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishers hand in 3D mockups to the trade catalogues: the book at a slight angle, a light page block along one
 * side, white around it. Only the front is the cover. Pure white corners mark such a picture; the front is the
 * part where nearly every row and column carries colour. A white front carries almost no colour; there the thin
 * outlines decide: front and page block each have one, so right and top show two lines and the inner one is the
 * front's edge. Anything that doesn't come out book-shaped stays as it is.
 */
final class CoverFlattener {

    private static final int WHITE = 245;      // corners at least this bright on every channel
    private static final int INK = 215;        // darker than this counts as cover; the page block is lighter
    private static final double FILL = 0.6;    // share of the fullest row/column a row/column needs to count as front
    private static final double INSET = 0.012; // trims the sliver a slanted edge leaves in the box
    private static final int BACKGROUND = 250; // the white around a mockup
    private static final int LINE = 225;       // an outline pixel
    private static final double LINE_LENGTH = 0.6; // an outline runs along most of the object
    private static final double BAND = 0.2;    // the page block sits within this share of the object's right and top

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
        if (ys != null && xs != null) {
            BufferedImage front = cut(img, xs[0], ys[0], xs[1], ys[1]);
            if (front != null) {
                return front;
            }
        }
        BufferedImage front = byOutlines(img);
        return front == null ? img : front;
    }

    /** White fronts: the object's left and bottom edge, the inner of the two outlines on the right and at the top. */
    private static BufferedImage byOutlines(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int ox0 = w, ox1 = -1, oy0 = h, oy1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (luminance(img.getRGB(x, y)) < BACKGROUND) {
                    ox0 = Math.min(ox0, x);
                    ox1 = Math.max(ox1, x);
                    oy0 = Math.min(oy0, y);
                    oy1 = Math.max(oy1, y);
                }
            }
        }
        if (ox1 <= ox0 || oy1 <= oy0) {
            return null;
        }
        int ow = ox1 - ox0, oh = oy1 - oy0;
        List<int[]> vertical = new ArrayList<>(), horizontal = new ArrayList<>();
        for (int x = (int) (ox1 - BAND * ow); x <= ox1; x++) {
            int n = 0;
            for (int y = oy0; y <= oy1; y++) {
                n += luminance(img.getRGB(x, y)) < LINE ? 1 : 0;
            }
            if (n >= LINE_LENGTH * oh) {
                extend(vertical, x);
            }
        }
        for (int y = oy0; y <= (int) (oy0 + BAND * oh); y++) {
            int n = 0;
            for (int x = ox0; x <= ox1; x++) {
                n += luminance(img.getRGB(x, y)) < LINE ? 1 : 0;
            }
            if (n >= LINE_LENGTH * ow) {
                extend(horizontal, y);
            }
        }
        if (vertical.size() < 2 || horizontal.size() < 2) {
            return null; // no double outline: not a mockup, or one this can't read
        }
        int right = vertical.get(vertical.size() - 2)[0] - 1;
        int top = horizontal.get(1)[1] + 1;
        return cut(img, ox0, top, right, oy1);
    }

    /** Groups neighbouring line positions into one line (an outline can be two or three pixels wide). */
    private static void extend(List<int[]> lines, int at) {
        if (!lines.isEmpty() && at - lines.getLast()[1] <= 2) {
            lines.getLast()[1] = at;
        } else {
            lines.add(new int[]{at, at});
        }
    }

    /** The box minus a hair, if it is book-shaped; otherwise null. */
    private static BufferedImage cut(BufferedImage img, int left, int top, int right, int bottom) {
        int w = img.getWidth(), h = img.getHeight();
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
            return null;
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

    private static int luminance(int rgb) {
        return (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3;
    }

    private static boolean white(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        return ((rgb >> 16) & 255) >= WHITE && ((rgb >> 8) & 255) >= WHITE && (rgb & 255) >= WHITE;
    }

    private static boolean ink(int rgb) {
        return ((rgb >> 16) & 255) < INK || ((rgb >> 8) & 255) < INK || (rgb & 255) < INK;
    }
}
