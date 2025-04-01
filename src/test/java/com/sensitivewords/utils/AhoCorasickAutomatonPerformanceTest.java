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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class AhoCorasickAutomatonPerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(AhoCorasickAutomatonPerformanceTest.class);
    private static AhoCorasickAutomaton automaton;
    private static List<String> testTexts;
    private static final int TEST_TEXT_COUNT = 10000;
    private static final int TEXT_LENGTH_MIN = 100;
    private static final int TEXT_LENGTH_MAX = 2000;
    private static final Random random = new Random();
    
    @BeforeAll
    public static void setup() {
        logger.info("初始化测试环境...");
        // 1. 初始化敏感词自动机
        automaton = new AhoCorasickAutomaton();
        
        // 2. 加载敏感词
        List<String> sensitiveWords = readCsvToList("tencentData.csv");
        automaton.addSensitiveWords(sensitiveWords);
        logger.info("加载了 {} 个敏感词", sensitiveWords.size());
        
        // 3. 从CSV加载测试文本，而不是生成随机文本
        testTexts = readTestDataFromCsv("test_data.csv");
        logger.info("从CSV加载了 {} 条测试文本", testTexts.size());
        
        // 4. 预热JVM
        warmUp();
    }
    
    private static void warmUp() {
        logger.info("开始预热...");
        for (int i = 0; i < 1000; i++) {
            automaton.containsSensitiveWords(testTexts.get(i % testTexts.size()));
        }
        logger.info("预热完成");
    }
    
    private static List<String> readCsvToList(String filePath) {
        List<String> dataList = new ArrayList<>();
        try (InputStream inputStream = AhoCorasickAutomatonPerformanceTest.class.getClassLoader().getResourceAsStream(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dataList.add(line.trim());
            }
        } catch (Exception e) {
            logger.error("读取敏感词文件失败", e);
        }
        return dataList;
    }
    
    private static List<String> readTestDataFromCsv(String filePath) {
        List<String> dataList = new ArrayList<>();
        try (InputStream inputStream = AhoCorasickAutomatonPerformanceTest.class.getClassLoader().getResourceAsStream(filePath);
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
        logger.info("生成{}个测试文本...", count);
        List<String> texts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            texts.add(generateRandomText(minLength + random.nextInt(maxLength - minLength)));
        }
        // 确保一部分文本包含敏感词
        for (int i = 0; i < count / 10; i++) {
            String text = texts.get(i);
            int position = random.nextInt(text.length() / 2);
            String sensitiveWord = "敏感词" + i;
            texts.set(i, text.substring(0, position) + sensitiveWord + text.substring(position));
        }
        return texts;
    }
    
    private static String generateRandomText(int length) {
        StringBuilder sb = new StringBuilder(length);
        // 使用常见中文字符范围
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
        System.out.println("\n>>> 测试 " + threadCount + " 线程的敏感词检测性能 <<<");
        
        ExecutorService executorService = new ThreadPoolExecutor(
            threadCount, threadCount, 0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10000), 
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 计数器
        LongAdder totalRequests = new LongAdder();
        AtomicLong totalProcessingTime = new AtomicLong(0);
        LongAdder totalCharacters = new LongAdder(); // 添加字符计数器
        
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
                    // 等待所有线程就绪
                    startLatch.await();
                    
                    long endTime = System.currentTimeMillis() + (testDurationSeconds * 1000);
                    long localProcessingTime = 0;
                    int localRequests = 0;
                    long localCharacters = 0; // 本地字符计数
                    
                    // 执行测试，直到时间结束
                    while (System.currentTimeMillis() < endTime) {
                        // 选择测试文本
                        String text = testTexts.get(random.nextInt(testTexts.size()));
                        
                        // 累加字符数
                        localCharacters += text.length();
                        
                        // 测量处理时间
                        long start = System.nanoTime();
                        automaton.containsSensitiveWords(text);
                        long end = System.nanoTime();
                        
                        // 累加处理时间和请求数
                        localProcessingTime += (end - start);
                        localRequests++;
                        
                        // 每1000个请求输出一次日志
                        if (localRequests % 1000 == 0) {
                            logger.debug("线程 {}: 已处理 {} 个请求", threadId, localRequests);
                        }
                    }
                    
                    // 更新全局计数器
                    totalRequests.add(localRequests);
                    totalProcessingTime.addAndGet(localProcessingTime);
                    totalCharacters.add(localCharacters); // 更新全局字符计数
                    
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
        double averageProcessingTimeNs = totalProcessingTime.get() / (double)totalRequests_value;
        double qps = totalRequests_value / (actualTestDuration / 1000.0);
        double averageProcessingTimeMs = averageProcessingTimeNs / 1_000_000.0;
        
        // 计算字符处理速率
        long totalChars = totalCharacters.sum();
        double charsPerSecond = totalChars / (actualTestDuration / 1000.0);
        double gbPerSecond = (charsPerSecond * 2) / (1024.0 * 1024.0 * 1024.0); // 假设每个字符平均2字节
        double averageTextLength = totalChars / (double)totalRequests_value;
        
        logger.info("=== {} 线程性能测试结果 ===", threadCount);
        logger.info("总请求数: {}", totalRequests_value);
        logger.info("总字符数: {}", totalChars);
        logger.info("平均文本长度: {:.2f}字符", averageTextLength);
        logger.info("测试持续时间: {}ms", actualTestDuration);
        logger.info("QPS: {}/s", String.format("%.2f", qps));
        logger.info("字符处理速率: {}/s ({:.2f} GB/s)", 
                   String.format("%.2f", charsPerSecond),
                   gbPerSecond);
        logger.info("平均处理时间: {}ms", String.format("%.6f", averageProcessingTimeMs));
        logger.info("单线程平均QPS: {}/s", String.format("%.2f", qps / threadCount));
        
        // 在测试结束时输出到控制台，确保可见
        System.out.println("*** " + threadCount + " 线程测试结果 ***");
        System.out.println("总请求数: " + totalRequests_value);
        System.out.println("QPS: " + String.format("%.2f", qps) + "/s");
        System.out.println("字符处理速率: " + String.format("%.2f", charsPerSecond) + "/s (" + 
                          String.format("%.2f", gbPerSecond) + " GB/s)");
        System.out.println("平均文本长度: " + String.format("%.2f", averageTextLength) + "字符");
        System.out.println("平均处理时间: " + String.format("%.6f", averageProcessingTimeMs) + "ms");
        System.out.println("*************************\n");
        
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.MINUTES);
    }
    
    @Test
    public void testMaximumThroughput() throws Exception {
        // 找出可用处理器核心数的2倍作为最佳线程数
        int optimalThreads = Runtime.getRuntime().availableProcessors() * 2;
        logger.info("系统处理器核心数: {}, 测试使用线程数: {}", 
                   Runtime.getRuntime().availableProcessors(), optimalThreads);
        
        // 使用最优线程数进行延长测试
        testThroughputWithDifferentThreads(optimalThreads);
    }
} 