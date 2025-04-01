package com.sensitivewords.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.Executors;

public class OptimizedDoubleArrayAhoCorasickTest {
    private static final Logger logger = LoggerFactory.getLogger(OptimizedDoubleArrayAhoCorasickTest.class);
    private static OptimizedDoubleArrayAhoCorasick ac;
    private static List<String> testTexts;
    private static final int TEST_TEXT_COUNT = 10000;
    private static final int TEXT_LENGTH_MIN = 100;
    private static final int TEXT_LENGTH_MAX = 2000;
    private static final Random random = new Random();

    // 在类级别定义线程局部随机数生成器
    private static final ThreadLocal<Random> threadLocalRandom = 
        ThreadLocal.withInitial(() -> new Random(System.nanoTime()));

    @BeforeAll
    public static void setup() {
        logger.info("初始化测试环境...");

        // 1. 加载敏感词
        List<String> sensitiveWords = readCsvToList("tencentData.csv");
        logger.info("加载了 {} 个敏感词", sensitiveWords.size());

        // 2. 初始化AC自动机
        ac = new OptimizedDoubleArrayAhoCorasick(sensitiveWords);

        // 3. 加载测试数据
        testTexts = readTestDataFromCsv("test_Data.csv");
        logger.info("加载了 {} 条测试文本", testTexts.size());

        // 4. 预热
        warmUp();
    }

    private static void warmUp() {
        logger.info("开始预热...");
        
        // 增加预热数据量，确保充分预热
        int warmUpSize = Math.min(500, testTexts.size());
        
        // 使用多线程预热，模拟真实场景
        int warmUpThreads = Runtime.getRuntime().availableProcessors();
        ExecutorService warmUpExecutor = Executors.newFixedThreadPool(warmUpThreads);
        
        // 分批预热，使用与测试相似的并发度
        CountDownLatch warmUpLatch = new CountDownLatch(warmUpThreads);
        for (int t = 0; t < warmUpThreads; t++) {
            final int threadId = t;
            warmUpExecutor.submit(() -> {
                try {
                    int start = threadId * (warmUpSize / warmUpThreads);
                    int end = (threadId + 1) * (warmUpSize / warmUpThreads);
                    
                    for (int i = start; i < end; i++) {
                        ac.replace(testTexts.get(i % testTexts.size()), '*');
                    }
                    
                    logger.info("线程 {} 预热完成", threadId);
                } finally {
                    warmUpLatch.countDown();
                }
            });
        }
        
        try {
            warmUpLatch.await();
            warmUpExecutor.shutdown();
        } catch (InterruptedException e) {
            logger.error("预热中断", e);
        }
        
        logger.info("预热完成，共处理 {} 条文本", warmUpSize);
    }

    private static List<String> readCsvToList(String filePath) {
        List<String> dataList = new ArrayList<>();
        try (InputStream inputStream = OptimizedDoubleArrayAhoCorasickTest.class.getClassLoader().getResourceAsStream(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dataList.add(line.trim());
            }
        } catch (Exception e) {
            logger.error("读取文件失败: {}", filePath, e);
        }
        return dataList;
    }

    private static List<String> readTestDataFromCsv(String filePath) {
        List<String> dataList = new ArrayList<>();
        try (InputStream inputStream = OptimizedDoubleArrayAhoCorasickTest.class.getClassLoader().getResourceAsStream(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < TEST_TEXT_COUNT) {
                if (!line.trim().isEmpty()) {
                    dataList.add(line.trim());
                    count++;
                }
            }
            logger.info("成功读取{}条测试数据", dataList.size());
        } catch (Exception e) {
            logger.error("读取测试数据文件失败: {}", filePath, e);
        }

        // 如果测试数据不足，补充随机生成的数据
        if (dataList.size() < TEST_TEXT_COUNT) {
            logger.info("测试数据不足，补充随机数据...");
            int needToGenerate = TEST_TEXT_COUNT - dataList.size();
            List<String> randomTexts = generateTestTexts(needToGenerate, TEXT_LENGTH_MIN, TEXT_LENGTH_MAX);
            dataList.addAll(randomTexts);
        }

        return dataList;
    }

    private static List<String> generateTestTexts(int count, int minLength, int maxLength) {
        List<String> texts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            texts.add(generateRandomText(minLength + random.nextInt(maxLength - minLength)));
        }
        return texts;
    }

    private static String generateRandomText(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = (char) (0x4e00 + random.nextInt(0x9fa5 - 0x4e00));
            sb.append(c);
        }
        return sb.toString();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 8, 16, 32})
    public void testThroughputWithDifferentThreads(int threadCount) throws Exception {
        logger.info("=== 开始测试 {} 线程的性能 ===", threadCount);

        ExecutorService executorService = new ThreadPoolExecutor(
                threadCount, threadCount, 0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10000),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 计数器
        LongAdder totalRequests = new LongAdder();
        AtomicLong totalProcessingTime = new AtomicLong(0);
        LongAdder totalCharacters = new LongAdder();

        // 测试时间（秒）
        int testDurationSeconds = 10;

        // 同步开始的CountDownLatch
        CountDownLatch startLatch = new CountDownLatch(1);
        // 同步结束的CountDownLatch
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        // 创建并启动测试线程
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    // 使用线程局部随机数生成器
                    Random localRandom = threadLocalRandom.get();
                    
                    long endTime = System.currentTimeMillis() + (testDurationSeconds * 1000);
                    long localProcessingTime = 0;
                    int localRequests = 0;
                    long localCharacters = 0;
                    
                    while (System.currentTimeMillis() < endTime) {
                        // 使用线程局部随机数
                        String text = testTexts.get(localRandom.nextInt(testTexts.size()));
                        localCharacters += text.length();
                        
                        long start = System.nanoTime();
                        ac.replace(text, '*');
                        long end = System.nanoTime();
                        
                        localProcessingTime += (end - start);
                        localRequests++;
                    }
                    
                    // 批量更新统计数据，减少原子操作次数
                    totalRequests.add(localRequests);
                    totalProcessingTime.addAndGet(localProcessingTime);
                    totalCharacters.add(localCharacters);
                    
                    logger.debug("线程 {} 完成，处理了 {} 个请求，{} 个字符",
                            threadId, localRequests, localCharacters);
                } catch (Exception e) {
                    logger.error("测试线程异常", e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 开始测试
        long testStartTime = System.currentTimeMillis();
        startLatch.countDown();
        logger.info("所有线程开始测试...");

        // 等待测试完成
        endLatch.await();
        long testEndTime = System.currentTimeMillis();
        long actualTestDuration = testEndTime - testStartTime;

        // 计算结果
        long totalRequests_value = totalRequests.sum();
        double averageProcessingTimeNs = totalProcessingTime.get() / (double) totalRequests_value;
        double qps = totalRequests_value / (actualTestDuration / 1000.0);
        double averageProcessingTimeMs = averageProcessingTimeNs / 1_000_000.0;

        // 字符处理速率
        long totalChars = totalCharacters.sum();
        double charsPerSecond = totalChars / (actualTestDuration / 1000.0);
        double gbPerSecond = (charsPerSecond * 2) / (1024.0 * 1024.0 * 1024.0);
        double averageTextLength = totalChars / (double) totalRequests_value;

        // 输出结果
        System.out.println("\n*** " + threadCount + " 线程测试结果 ***");
        System.out.println("总请求数: " + totalRequests_value);
        System.out.println("总字符数: " + totalChars);
        System.out.println("平均文本长度: " + String.format("%.2f", averageTextLength) + "字符");
        System.out.println("测试持续时间: " + actualTestDuration + "ms");
        System.out.println("QPS: " + String.format("%.2f", qps) + "/s");
        System.out.println("字符处理速率: " + String.format("%.2f", charsPerSecond) + "字符/s (" +
                String.format("%.2f", gbPerSecond) + " GB/s)");
        System.out.println("平均处理时间: " + String.format("%.6f", averageProcessingTimeMs) + "ms");
        System.out.println("*************************\n");

        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.MINUTES);
    }
}