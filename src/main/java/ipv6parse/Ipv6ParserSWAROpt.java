package ipv6parse;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public class Ipv6ParserSWAROpt {

    private static final VarHandle LONG_HANDLE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    public static byte[] parse(byte[] input) {
        return parse(input, 0, input.length);
    }

    public static byte[] parse(byte[] input, int off, int len) {
        if (len < 2 || len > 45) return null;

        int numChunks = (len + 7) / 8;
        long colonBits = 0;
        long dotBits   = 0;
        byte[] nibBuf  = new byte[32];
        int totalNib   = 0;

        for (int ci = 0; ci < numChunks; ci++) {
            int byteOff = ci * 8;
            int chunkLen = Math.min(8, len - byteOff);
            long chunk = loadLong(input, off + byteOff, chunkLen);

            long colons = findByte(chunk, (byte) ':');
            long dots   = findByte(chunk, (byte) '.');

            long validBits = -1L >>> (64 - chunkLen * 8);
            colons &= validBits;
            dots   &= validBits;

            long colonCompact = Long.compress(colons >>> 7, 0x0101010101010101L);
            colonBits |= colonCompact << (ci * 8);

            long dotCompact = Long.compress(dots >>> 7, 0x0101010101010101L);
            dotBits |= dotCompact << (ci * 8);

            long delimBytes = ((colons | dots) >>> 7) * 0xFFL;
            long hexMask = ~delimBytes & validBits;

            int nHex = Long.bitCount(hexMask) >>> 3;
            if (nHex == 0) continue;

            long compressed = Long.compress(chunk, hexMask);

            // validate and convert to nibbles in one pass
            long cv = compressed;
            for (int k = 0; k < nHex; k++) {
                int b = (int)(cv & 0xFF);
                if (!isHexByte(b)) return null;
                cv >>>= 8;
            }

            long nibbled = swarHexConvert(compressed);
            long nv = nibbled;
            for (int k = 0; k < nHex; k++) {
                nibBuf[totalNib + k] = (byte)(nv & 0xFF);
                nv >>>= 8;
            }
            totalNib += nHex;
        }

        int[] col = new int[8];
        int nc = 0;
        long bits = colonBits;
        while (bits != 0 && nc < 8) {
            col[nc++] = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
        }
        if (nc == 0) return null;

        int ccPairs = 0;
        for (int i = 1; i < nc; i++) {
            if (col[i] == col[i - 1] + 1) ccPairs++;
        }
        if (ccPairs > 1) return null;

        boolean hasDot = dotBits != 0;
        int dotCount = Long.bitCount(dotBits);

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
                if (lastEmptyIdx >= 0 && i != lastEmptyIdx + 1)
                    emptiesConsecutive = false;
                lastEmptyIdx = i;
            }
        }
        if (emptyCount > 0 && !emptiesConsecutive) return null;
        if (ccPairs == 1 && (emptyCount < 1 || emptyCount > 3)) return null;

        int hexGroups = hasDot ? 6 : 8;
        int hexSegs   = segs - (hasDot ? 1 : 0) - emptyCount;
        int pad       = hexGroups - hexSegs;
        if (pad < 0) return null;
        if (emptyCount > 0 && pad < 1) return null;
        if (emptyCount == 0 && pad != 0) return null;

        for (int i = 0; i < segs; i++) {
            boolean isLast = i == segs - 1;
            boolean isHex  = !isLast || !hasDot;
            if (segStart[i] < segEnd[i] && isHex) {
                int span = segEnd[i] - segStart[i];
                if (span < 1 || span > 4) return null;
            }
        }

        int[] grpSizes = new int[hexGroups];
        int gi = 0;
        boolean padDone = false;
        for (int si = 0; si < segs; si++) {
            boolean isLast = si == segs - 1;
            boolean isHex  = !isLast || !hasDot;
            boolean empty  = segStart[si] == segEnd[si];
            if (empty && isHex && !padDone) {
                for (int p = 0; p < pad; p++) grpSizes[gi++] = 0;
                padDone = true;
            } else if (!empty && isHex) {
                grpSizes[gi++] = segEnd[si] - segStart[si];
            }
        }

        byte[] out = new byte[16];
        int oi = 0;
        int nibblePos = 0;

        for (int g = 0; g < hexGroups; g++) {
            int size = grpSizes[g];
            if (size == 0) {
                out[oi++] = 0; out[oi++] = 0;
                continue;
            }
            int v = 0;
            for (int k = 0; k < size; k++) {
                v = (v << 4) | (nibBuf[nibblePos++] & 0xFF);
            }
            out[oi++] = (byte)(v >> 8);
            out[oi++] = (byte)(v);
        }

        if (hasDot) {
            if (dotCount != 3) return null;
            int ipv4Off = col[nc - 1] + 1;
            Ipv6Parser.ipv4Suffix(input, off + ipv4Off, len - ipv4Off, out, 12);
            oi += 4;
        }

        return oi == 16 ? out : null;
    }

    private static long loadLong(byte[] buf, int off, int len) {
        if (len == 8) {
            return (long) LONG_HANDLE.get(buf, off);
        }
        long v = 0;
        for (int i = 0; i < len; i++) {
            v |= (buf[off + i] & 0xFFL) << (i * 8);
        }
        return v;
    }

    private static long findByte(long chunk, byte b) {
        long pat = b & 0xFFL;
        pat |= pat << 8;  pat |= pat << 16;  pat |= pat << 32;
        long xor = chunk ^ pat;
        return ((xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L);
    }

    private static long swarHexConvert(long v) {
        long t = v - 0x3030303030303030L;
        long gt9 = t + 0xF6F6F6F6F6F6F6F6L;
        long isLetter = ~gt9 & 0x8080808080808080L;
        long adj = (isLetter >>> 5) | (isLetter >>> 6) | (isLetter >>> 7);
        long isLower = t & 0x2020202020202020L;
        return t - (adj | isLower);
    }

    private static boolean isHexByte(int b) {
        return (b >= '0' && b <= '9') ||
               (b >= 'A' && b <= 'F') ||
               (b >= 'a' && b <= 'f');
    }
}
