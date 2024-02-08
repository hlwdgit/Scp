package org.example;

public class PerformanceTestUtils {
    public PerformanceTestUtils() {
    }

    public static TestResult test(Do d, Long times, Long duration) {
        long st = System.nanoTime() / 1000000L;
        long testTimes = 0L;
        long costTime = 0L;

        TestResult result;
        for(result = new TestResult(); (times == null || testTimes < times) && (duration == null || costTime < duration); costTime = System.nanoTime() / 1000000L - st) {
            try {
                d.dosome();
            } catch (Exception var11) {
                var11.printStackTrace();
                result.times = result.times == 0L ? -1L : -result.times;
                result.times = result.times > 0L ? -1L : result.times;
                result.useTime = costTime;
                return result;
            }

            ++testTimes;
        }

        result.times = testTimes;
        result.useTime = costTime;
        return result;
    }

    public static void main(String[] args) {
        System.out.println("开始");
        TestResult result = test(() -> {
        }, 1000L, (Long)null);
        System.out.println("result.useTime = " + result.useTime);
        System.out.println("result.times = " + result.times);
    }

    public static class TestResult {
        public long times = 0L;
        public long useTime = 0L;

        public TestResult() {
        }
    }

    @FunctionalInterface
    public interface Do {
        void dosome() throws Exception;
    }
}
