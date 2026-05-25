package ipv6parse;

import java.nio.charset.StandardCharsets;

public class PerfRunner {
    public static void main(String[] args) {
        String addr = args.length > 0 ? args[0] : "2001:db8:0:0:0:0:0:1";
        byte[] input = addr.getBytes(StandardCharsets.US_ASCII);
        // warmup
        for (int i = 0; i < 200_000; i++) {
            Ipv6ParserVector.parse(input);
        }
        // measurement
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < 100_000_000; i++) {
            byte[] result = Ipv6ParserVector.parse(input);
            sum += result == null ? 0 : result[0];
        }
        long end = System.nanoTime();
        long elapsed = end - start;
        double ops_s = 100_000_000.0 / (elapsed / 1e9);
        System.out.printf("%.0f ops/s (sum=%d)%n", ops_s, sum);
    }
}
