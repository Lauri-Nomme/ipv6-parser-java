package ipv6parse;

public class Ipv6Parser {

    public static byte[] parse(byte[] input) {
        return parse(input, 0, input.length);
    }

    public static byte[] parse(byte[] input, int off, int len) {
        if (len < 2 || len > 45) return null;

        int[] col = new int[8];
        int nc = 0;
        for (int i = off; i < off + len; i++) {
            if (input[i] == ':') col[nc++] = i - off;
        }
        if (nc == 0) return null;

        int ccPairs = 0;
        for (int i = 1; i < nc; i++) {
            if (col[i] == col[i - 1] + 1) ccPairs++;
        }
        if (ccPairs > 1) return null;

        boolean hasDot = false;
        int dotCount = 0;
        for (int i = off; i < off + len; i++) {
            if (input[i] == '.') { hasDot = true; dotCount++; }
        }

        int segs = nc + 1;
        int[] segStart = new int[segs];
        int[] segEnd   = new int[segs];
        segStart[0] = 0;
        for (int i = 0; i < nc; i++) {
            segEnd[i] = col[i];
            segStart[i + 1] = col[i] + 1;
        }
        segEnd[nc] = len;

        int emptyCount = 0;
        int firstEmpty = -1;
        boolean emptiesConsecutive = true;
        int lastEmptyIdx = -2;
        for (int i = 0; i < segs; i++) {
            boolean e = segStart[i] == segEnd[i];
            if (e) {
                emptyCount++;
                if (firstEmpty < 0) firstEmpty = i;
                if (lastEmptyIdx >= 0 && i != lastEmptyIdx + 1) emptiesConsecutive = false;
                lastEmptyIdx = i;
            }
        }
        if (emptyCount > 0 && !emptiesConsecutive) return null;
        if (ccPairs == 1 && (emptyCount < 1 || emptyCount > 3)) return null;

        int hexGroups = hasDot ? 6 : 8;
        int hexSegs   = segs - (hasDot ? 1 : 0) - emptyCount;
        int pad = hexGroups - hexSegs;
        if (pad < 0) return null;
        if (emptyCount > 0 && pad < 1) return null;
        if (emptyCount == 0 && pad != 0) return null;

        for (int i = 0; i < segs; i++) {
            boolean isLast = (i == segs - 1);
            boolean isHex  = !isLast || !hasDot;
            if (segStart[i] < segEnd[i] && isHex) {
                int span = segEnd[i] - segStart[i];
                if (span < 1 || span > 4) return null;
                for (int j = segStart[i]; j < segEnd[i]; j++) {
                    if (hexVal(input[off + j]) < 0) return null;
                }
            }
        }

        byte[] out = new byte[16];
        int oi = 0;
        boolean ddInserted = false;
        for (int i = 0; i < segs; i++) {
            boolean isEmpty = segStart[i] == segEnd[i];
            boolean isLast  = (i == segs - 1);
            boolean isHex   = !isLast || !hasDot;

            if (isEmpty) {
                if (!ddInserted) {
                    for (int p = 0; p < pad; p++) {
                        out[oi++] = 0; out[oi++] = 0;
                    }
                    ddInserted = true;
                }
            } else if (isHex) {
                hexGroup(input, off + segStart[i], segEnd[i] - segStart[i], out, oi);
                oi += 2;
            } else {
                ipv4Suffix(input, off + segStart[i], segEnd[i] - segStart[i], out, oi);
                oi += 4;
            }
        }
        return oi == 16 ? out : null;
    }

    static void hexGroup(byte[] b, int off, int len, byte[] out, int oi) {
        int v = 0;
        for (int i = 0; i < len; i++) v = (v << 4) | hexVal(b[off + i]);
        out[oi]     = (byte)(v >> 8);
        out[oi + 1] = (byte)(v);
    }

    static void ipv4Suffix(byte[] b, int off, int len, byte[] out, int oi) {
        int[] dotPos = new int[3];
        int di = 0;
        for (int i = 0; i < len; i++) if (b[off + i] == '.') dotPos[di++] = i;
        int[] segEnds = { dotPos[0], dotPos[1], dotPos[2], len };
        int prev = 0;
        for (int oct = 0; oct < 4; oct++) {
            int v = 0;
            for (int j = prev; j < segEnds[oct]; j++)
                v = v * 10 + (b[off + j] - '0');
            out[oi + oct] = (byte)v;
            prev = segEnds[oct] + 1;
        }
    }

    static int hexVal(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }
}
