package org.globsframework.fix;

import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;
import java.util.Random;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class UtilsBenchmark {

    @Param({"4", "34", "945", "1934", "98345"})
    private int size;

//    private byte[] data;
    private byte[] buffer;
    private int at = 0;

    @Setup
    public void setup() {
//        data = new byte[size];
//        new Random(42).nextBytes(data);
        buffer = new byte[size + 10];
    }

    @Benchmark
    public int testCopy() {
        return Utils.copy(buffer, at, size);
    }

    @Benchmark
    public int testFastCopy() {
        return Utils.fastCopy(buffer, at, size);
    }
}
