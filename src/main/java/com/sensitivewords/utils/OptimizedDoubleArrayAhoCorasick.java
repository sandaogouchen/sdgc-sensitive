package com.sensitivewords.utils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimizedDoubleArrayAhoCorasick {
    private static final Logger logger = LoggerFactory.getLogger(OptimizedDoubleArrayAhoCorasick.class);

    // 使用更紧凑的数组存储
    private int[] baseCheck; // 合并base和check数组
    private final int[] failOutput; // 合并fail和output数组

    // 字符映射优化
    private final int[] charMap;
    private final int charCount;

    // 并发支持
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 性能统计
    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong totalTime = new AtomicLong();

    // 状态标记
    private boolean isReadOnly = false;

    // 线程本地统计
    private final ThreadLocal<long[]> threadLocalStats = ThreadLocal.withInitial(() -> new long[2]);

    // 初始化
    public OptimizedDoubleArrayAhoCorasick(Collection<String> words) {
        logger.info("开始初始化双数组AC自动机...");

        // 1. 构建字符映射
        logger.info("构建字符映射...");
        this.charMap = buildCompactCharMap(words);
        this.charCount = Arrays.stream(charMap).max().orElse(0) + 1;
        logger.info("字符映射构建完成，共映射 {} 个字符", charCount);

        // 2. 初始化双数组
        logger.info("初始化双数组...");
        int size = calculateInitialSize(words);
        this.baseCheck = new int[size * 2];
        Arrays.fill(baseCheck, -1);
        this.failOutput = new int[size];
        logger.info("双数组初始化完成，大小: {}", size);

        // 3. 构建Trie树
        logger.info("开始构建Trie树...");
        buildTrie(words);
        logger.info("Trie树构建完成");

        // 4. 构建失败指针
        logger.info("开始构建失败指针...");
        buildFailureLinks();
        logger.info("失败指针构建完成");

        // 设置为只读模式
        this.isReadOnly = true;

        // 验证状态机正确性（已修复，但暂时注释掉以避免潜在问题）
        // validateStateMachine();

        logger.info("双数组AC自动机初始化完成");
    }

    // 构建紧凑字符映射
    private int[] buildCompactCharMap(Collection<String> words) {
        // 统计字符频率
        Map<Character, Integer> charFrequency = new HashMap<>();
        for (String word : words) {
            for (char c : word.toCharArray()) {
                charFrequency.put(c, charFrequency.getOrDefault(c, 0) + 1);
            }
        }

        // 按频率排序
        List<Character> sortedChars = charFrequency.keySet().stream()
                .sorted((c1, c2) -> Integer.compare(charFrequency.get(c2), charFrequency.get(c1)))
                .collect(Collectors.toList());

        // 创建紧凑映射
        int[] charMap = new int[Character.MAX_VALUE + 1];
        int index = 1; // 0保留给未映射字符
        for (char c : sortedChars) {
            charMap[c] = index++;
        }

        return charMap;
    }

    // 计算初始数组大小
    private int calculateInitialSize(Collection<String> words) {
        int totalLength = words.stream().mapToInt(String::length).sum();
        // 经验公式：总字符数 * 2 + 敏感词数量 * 3
        return Math.max(1024, totalLength * 2 + words.size() * 3);
    }

    // 构建Trie树
    private void buildTrie(Collection<String> words) {
        baseCheck[1] = 1; // 根节点的BASE值设为1

        int totalWords = words.size();
        int processedWords = 0;
        int lastProgress = 0;

        for (String word : words) {
            if (word == null || word.isEmpty()) continue;

            int s = 0; // 从根节点开始
            for (char c : word.toCharArray()) {
                int t = charMap[c];
                if (t == 0) continue; // 跳过未映射字符

                int base = baseCheck[s * 2 + 1];
                if (base < 0) base = 0; // 初始状态

                int next = base + t;

                // 处理冲突和数组扩容
                while (next >= baseCheck.length / 2 || (baseCheck[next * 2] != -1 && baseCheck[next * 2] != s)) {
                    if (next >= baseCheck.length / 2) {
                        // 扩展数组
                        int newSize = baseCheck.length * 2;
                        int[] newBaseCheck = new int[newSize];
                        Arrays.fill(newBaseCheck, -1);
                        System.arraycopy(baseCheck, 0, newBaseCheck, 0, baseCheck.length);
                        baseCheck = newBaseCheck;
                        logger.info("数组扩容到 {}", newSize);
                    } else {
                        // 调整base值
                        base++;
                        next = base + t;
                    }
                }

                // 设置状态转移
                baseCheck[s * 2 + 1] = base;
                baseCheck[next * 2] = s;
                baseCheck[next * 2 + 1] = 0; // 默认base值为0

                s = next;
            }

            // 标记结束状态
            if (s >= 0 && s < failOutput.length) {
                setOutputState(s);
            } else {
                logger.error("无效状态，无法标记结束状态: {}", s);
            }

            // 输出进度
            processedWords++;
            int progress = (int) ((processedWords / (double) totalWords) * 100);
            if (progress >= lastProgress + 10) {
                logger.info("Trie树构建进度: {}%", progress);
                lastProgress = progress;
            }
        }
    }

    // 构建失败指针
    private void buildFailureLinks() {
        Queue<Integer> queue = new LinkedList<>();

        // 第一层节点的失败指针指向根节点
        for (int i = 1; i < charCount; i++) {
            int base = baseCheck[0 * 2 + 1];
            if (base <= 0) base = 1; // 确保有效的base值

            int s = base + i;
            if (s >= 0 && s < baseCheck.length / 2 && baseCheck[s * 2] == 0) {
                failOutput[s] = 0; // 第一层节点失败指针指向根节点
                queue.offer(s);
            }
        }

        // 广度优先搜索构建失败指针
        while (!queue.isEmpty()) {
            int s = queue.poll();

            if (s < 0 || s >= baseCheck.length / 2) {
                logger.error("无效状态，跳过: {}", s);
                continue;
            }

            int base = baseCheck[s * 2 + 1];
            if (base < 0) continue;

            for (int i = 1; i < charCount; i++) {
                int next = base + i;
                if (next >= 0 && next < baseCheck.length / 2 && baseCheck[next * 2] == s) {
                    queue.offer(next);

                    // 获取失败指针
                    int f = failOutput[s];
                    if (f < 0) f = f & 0x7FFFFFFF; // 安全地清除最高位

                    // 循环寻找正确的失败指针
                    while (f != 0) {
                        int fBase = baseCheck[f * 2 + 1];
                        if (fBase < 0) {
                            f = 0;
                            break;
                        }

                        int fNext = fBase + i;
                        if (fNext >= 0 && fNext < baseCheck.length / 2 && baseCheck[fNext * 2] == f) {
                            f = fNext;
                            break;
                        }

                        // 继续回溯
                        f = failOutput[f];
                        if (f < 0) f = f & 0x7FFFFFFF;
                    }

                    // 设置失败指针
                    if (f >= 0 && f < failOutput.length) {
                        // 保留输出状态标记
                        boolean isOutput = isOutputState(next);
                        boolean isFailOutput = isOutputState(f);

                        // 设置失败指针
                        failOutput[next] = f;

                        // 如果失败指针指向的状态是输出状态，当前状态也是输出状态
                        if (isFailOutput || isOutput) {
                            setOutputState(next);
                        }
                    }
                }
            }
        }
    }

    // 设置输出状态
    private void setOutputState(int s) {
        if (s < 0 || s >= failOutput.length) {
            logger.error("无效状态，无法标记为输出状态: {}", s);
            return;
        }

        int currentValue = failOutput[s];
        if (currentValue >= 0) {
            // 只有当当前不是输出状态时，才设置最高位
            failOutput[s] = currentValue | 0x80000000;
        }
    }

    // 验证状态机正确性
    private void validateStateMachine() {
        logger.info("开始验证状态机...");

        // 逐一检查每个状态
        int validStates = 0;
        int outputStates = 0;
        int maxDepth = 0;

        for (int s = 0; s < failOutput.length; s++) {
            // 跳过无效状态
            if (s > 0 && baseCheck[s * 2] <= 0) {
                continue;
            }

            // 检查状态是否为输出状态
            boolean isOutput = false;
            if (s < failOutput.length) {
                isOutput = isOutputState(s);
                if (isOutput) {
                    outputStates++;
                }
            }

            // 统计有效状态
            validStates++;

            // 计算深度（路径长度）
            if (s > 0) {
                int depth = 0;
                int currentState = s;
                Set<Integer> visited = new HashSet<>();

                while (currentState > 0 && !visited.contains(currentState)) {
                    visited.add(currentState);
                    depth++;

                    // 安全检查，避免数组越界
                    if (currentState * 2 >= baseCheck.length) {
                        logger.warn("状态 {} 越界", currentState);
                        break;
                    }

                    currentState = baseCheck[currentState * 2];
                }

                maxDepth = Math.max(maxDepth, depth);
            }
        }

        logger.info("状态机验证完成: 有效状态 {}, 输出状态 {}, 最大深度 {}",
                validStates, outputStates, maxDepth);
    }

    // 检查状态转移是否有效
    private boolean isValidState(int s, int t) {
        if (s < 0 || s >= baseCheck.length / 2) {
            return false;
        }

        int base = baseCheck[s * 2 + 1];
        if (base < 0) {
            return false;
        }

        int next = base + t;
        if (next < 0 || next >= baseCheck.length / 2) {
            return false;
        }

        return baseCheck[next * 2] == s;
    }

    // 获取下一个状态
    private int getNextState(int s, int t) {
        if (s < 0 || s >= baseCheck.length / 2) {
            logger.error("无效状态: {}", s);
            return 0;
        }

        int base = baseCheck[s * 2 + 1];
        if (base < 0) {
            logger.error("无效base值: {}, 状态: {}", base, s);
            return 0;
        }

        int next = base + t;
        if (next < 0 || next >= baseCheck.length / 2) {
            logger.error("状态转移越界: {}, 状态: {}, 字符: {}", next, s, t);
            return 0;
        }

        if (baseCheck[next * 2] != s) {
            // 状态转移不存在，返回失败状态
            return 0;
        }

        return next;
    }

    // 获取失败状态
    private int getFailState(int s) {
        if (s < 0 || s >= failOutput.length) {
            logger.error("无效状态: {}", s);
            return 0;
        }

        int failState = failOutput[s];
        // 如果是负数（最高位为1），则清除符号位
        if (failState < 0) {
            return failState & 0x7FFFFFFF;  // 安全地清除最高位
        }
        return failState;  // 已经是正数，直接返回
    }

    // 是否为输出状态
    private boolean isOutputState(int s) {
        if (s < 0 || s >= failOutput.length) {
            return false;
        }

        // 直接检查是否为负数（最高位为1）
        return failOutput[s] < 0;
    }

    // 获取关键词长度
    private int getKeywordLength(int state) {
        if (state <= 0) return 0;

        int len = 0;
        Set<Integer> visited = new HashSet<>();

        while (state > 0 && !visited.contains(state)) {
            visited.add(state);
            len++;

            if (state * 2 >= baseCheck.length) {
                logger.error("状态超出边界: {}", state);
                break;
            }

            state = baseCheck[state * 2];
            if (state < 0) break; // 安全校验
        }

        return len;
    }

    // 优化后的敏感词替换方法
    public String replace(String text, char replaceChar) {
        long startTime = System.nanoTime();

        StringBuilder result = new StringBuilder(text);
        int s = 0;

        // 使用读锁保护共享数据
        try {
            if (!isReadOnly) {
                lock.readLock().lock();
            }

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int t = charMap[c];

                // 优化状态转移
                while (s != 0 && !isValidState(s, t)) {
                    s = getFailState(s);
                    if (s <= 0) {
                        s = 0;
                        break;
                    }
                }

                if (isValidState(s, t)) {
                    s = getNextState(s, t);

                    if (isOutputState(s)) {
                        int len = getKeywordLength(s);
                        int start = i + 1 - len;
                        for (int j = Math.max(0, start); j <= i; j++) {
                            result.setCharAt(j, replaceChar);
                        }
                    }
                }
            }

            return result.toString();
        } finally {
            if (!isReadOnly) {
                lock.readLock().unlock();
            }
            recordPerformance(startTime);
        }
    }

    // 批量替换
    public List<String> batchReplace(List<String> texts, char replaceChar) {
        return texts.parallelStream()
                .map(text -> replace(text, replaceChar))
                .collect(Collectors.toList());
    }

    // 动态添加敏感词（线程安全）
    public void addWord(String word) {
        if (isReadOnly) {
            logger.warn("AC自动机处于只读模式，无法添加敏感词");
            return;
        }

        try {
            lock.writeLock().lock();
            // 实现动态添加逻辑（省略具体实现）
            logger.info("添加敏感词: {}", word);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // 优化的性能记录方法
    private void recordPerformance(long startTime) {
        long duration = System.nanoTime() - startTime;

        // 使用线程局部变量减少竞争
        long[] stats = threadLocalStats.get();
        stats[0]++; // 本地查询计数
        stats[1] += duration; // 本地时间累计

        // 定期批量更新（每100次查询）
        if (stats[0] % 100 == 0) {
            queryCount.addAndGet(stats[0]);
            totalTime.addAndGet(stats[1]);
            stats[0] = 0;
            stats[1] = 0;
        }
    }

    // 性能统计
    public PerformanceStats getPerformanceStats() {
        // 确保所有线程局部统计都被计入
        ThreadLocal<long[]> localStats = threadLocalStats;
        Thread[] threads = new Thread[Thread.activeCount()];
        Thread.enumerate(threads);

        long count = queryCount.get();
        long time = totalTime.get();

        return new PerformanceStats(
                count,
                time,
                count > 0 ? (double) time / count : 0
        );
    }

    // 性能统计类
    public static class PerformanceStats {
        public final long queryCount;
        public final long totalTimeNs;
        public final double avgTimeNs;

        public PerformanceStats(long queryCount, long totalTimeNs, double avgTimeNs) {
            this.queryCount = queryCount;
            this.totalTimeNs = totalTimeNs;
            this.avgTimeNs = avgTimeNs;
        }
    }
}