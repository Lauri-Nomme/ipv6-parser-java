package ipv6parse;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 0)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
public class Ipv6Benchmark {

    @Param({
        "2001:db8::1",
        "::1",
        "2001:db8:0:0:0:0:0:1",
        "fe80::1",
        "::ffff:192.168.0.1",
        "2001:db8::c0a8:101",
        "2001:0db8:0000:0000:0000:0000:0000:0001",
        "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
        "1234:5678:9abc:def0:1234:5678:9abc:def0"
    })
    public String address;

    byte[] input;

    @Setup
    public void setup() {
        input = address.getBytes(StandardCharsets.US_ASCII);
    }

    @Benchmark
    public void scalar(Blackhole bh) {
        bh.consume(Ipv6Parser.parse(input));
    }

    @Benchmark
    public void vector(Blackhole bh) {
        bh.consume(Ipv6ParserVector.parse(input));
    }

    @Benchmark
    public void vectorCE(Blackhole bh) {
        bh.consume(Ipv6ParserVectorCE.parse(input));
    }

    @Benchmark
    public void swar(Blackhole bh) {
        bh.consume(Ipv6ParserSWAR.parse(input));
    }

    public static void main(String[] args) throws Exception {
        Options opt = new CommandLineOptions(args);
        new Runner(opt).run();
    }
}
