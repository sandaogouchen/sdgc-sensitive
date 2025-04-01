package com.sensitivewords.utils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Component
public class AhoCorasickAutomaton {
    // 根节点
    private final CompactTrieNode root = new CompactTrieNode();
    // 为常见敏感词字符创建映射表
    private static final int MAX_CHARS = 10000; // 根据实际情况调整
    private final Map<Character, Integer> charToIndex = new ConcurrentHashMap<>();
    private final char[] indexToChar = new char[MAX_CHARS];
    private int nextCharIndex = 0;
    private final Map<String, String> stringPool = new ConcurrentHashMap<>();
    private final List<String> sensitiveWordsList = new ArrayList<>();

    // 线程池，用于处理敏感词的动态添加
    private final ExecutorService executorService = new ThreadPoolExecutor(
            4, // 核心线程数
            16, // 最大线程数（根据负载调整）
            60, // 空闲线程存活时间
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000), // 任务队列大小
            new CallerRunsPolicy() // 拒绝策略
    );

    private final Cache<CompactTrieNode, Map<Character, CompactTrieNode>> failCache = Caffeine.newBuilder()
            .maximumSize(10000) // 设置最大缓存大小
            .build();

    // 预分配字符索引
    private final int[] charIndexCache = new int[65536]; // 支持所有 Unicode 字符

    // 添加获取敏感词的方法
    public String getSensitiveWord(int wordId) {
        if (wordId >= 0 && wordId < sensitiveWordsList.size()) {
            return sensitiveWordsList.get(wordId);
        }
        return null;
    }
    private static final boolean[] isIgnoredChar = new boolean[65536];
    static {
        // 初始化忽略字符集
        String ignoreChars = " \t\n\r,.;:\"'?!-()[]{}";
        for (char c : ignoreChars.toCharArray()) {
            isIgnoredChar[c] = true;
        }
    }

    private int getCharIndex(char c) {
        int index = charIndexCache[c];
        if (index == 0) {
            if (nextCharIndex >= MAX_CHARS) {
                throw new RuntimeException("字符集超出预设大小");
            }
            index = nextCharIndex++;
            charIndexCache[c] = index;
            indexToChar[index] = c;
        }
        
        return index;
    }

    // 添加字符串池化方法
    private String internString(String str) {
        String pooled = stringPool.get(str);
        if (pooled == null) {
            stringPool.put(str, str);
            return str;
        }
        return pooled;
    }

    // 构建AC自动机
    @PostConstruct
    public void init() {
        try {
            // 读取敏感词字典
            ClassPathResource resource = new ClassPathResource("dic/sensitiveWordsDic.dic");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    addWord(line);
                }
            }
            reader.close();

            // 构建失败指针
            buildFailurePointers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 添加敏感词到Trie树
    private void addWord(String word) {
        // 先在词典池中查找或添加
        word = internString(word);

        CompactTrieNode current = root;
        for (char c : word.toCharArray()) {
            if (current.getChild(c) == null) {
                current.addChild(c, new CompactTrieNode());
            }
            current = current.getChild(c);
        }
        current.setEndOfWord(true);
        // 存储敏感词索引
        current.setWordId(sensitiveWordsList.size());
        sensitiveWordsList.add(word);
    }

    // 构建失败指针
    private void buildFailurePointers() {
        Queue<CompactTrieNode> queue = new LinkedList<>();

        // 将第一级节点的失败指针指向根节点
        for (CompactTrieNode node : root.getCommonChildren()) {
            if (node != null) {
                node.setFail(root);
                queue.offer(node);
            }
        }
        if (root.getRareChildren() != null) {
            for (CompactTrieNode node : root.getRareChildren().values()) {
                node.setFail(root);
                queue.offer(node);
            }
        }

        // BFS构建其他节点的失败指针
        while (!queue.isEmpty()) {
            CompactTrieNode current = queue.poll();

            // 处理常见字符子节点
            for (CompactTrieNode childNode : current.getCommonChildren()) {
                if (childNode != null) {
                    queue.offer(childNode);
                    buildFailurePointerForChild(current, childNode);
                }
            }

            // 处理非常见字符子节点
            if (current.getRareChildren() != null) {
                for (Map.Entry<Character, CompactTrieNode> entry : current.getRareChildren().entrySet()) {
                    queue.offer(entry.getValue());
                    buildFailurePointerForChild(current, entry.getValue());
                }
            }
        }
    }

    private void buildFailurePointerForChild(CompactTrieNode parent, CompactTrieNode child) {
        CompactTrieNode failNode = parent.getFail();
        char c = getCharFromChild(parent, child);

        while (failNode != null && failNode.getChild(c) == null) {
            failNode = failNode.getFail();
        }

        if (failNode == null) {
            child.setFail(root);
        } else {
            child.setFail(failNode.getChild(c));
        }
    }

    private char getCharFromChild(CompactTrieNode parent, CompactTrieNode child) {
        // 在常见字符中查找
        for (int i = 0; i < 256; i++) {
            if (parent.getCommonChildren()[i] == child) {
                return (char) i;
            }
        }
        // 在非常见字符中查找
        if (parent.getRareChildren() != null) {
            for (Map.Entry<Character, CompactTrieNode> entry : parent.getRareChildren().entrySet()) {
                if (entry.getValue() == child) {
                    return entry.getKey();
                }
            }
        }
        throw new IllegalStateException("无法找到子节点对应的字符");
    }

    // 检查文本是否包含敏感词
    public boolean containsSensitiveWords(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
    
        CompactTrieNode current = root;
        int i = 0;
        final int textLength = text.length();
        
        while (i < textLength) {
            char c = text.charAt(i);
            if (isIgnoredChar[c]) {
                i++;
                continue;
            }
            
            // 使用缓存减少失败指针回溯
            CompactTrieNode next = getNextNode(current, c);
            if (next == null) {
                // 当前位置无法匹配，不必每次都回到根节点
                if (current == root) {
                    i++; // 根节点匹配失败才向前移动
                } else {
                    current = root; // 否则回到根节点继续当前位置匹配
                }
            } else {
                current = next;
                i++; // 匹配成功，向前移动一位
                
                // 匹配到敏感词立即返回
                if (current.isEndOfWord()) {
                    return true;
                }
            }
        }
        return false;
    }

    private CompactTrieNode getNextNode(CompactTrieNode current, char c) {
        Map<Character, CompactTrieNode> cache = failCache.get(current, k -> new HashMap<>());
        if (cache.containsKey(c)) {
            return cache.get(c);
        }

        CompactTrieNode next = current.getChild(c);
        if (next == null) {
            CompactTrieNode failNode = current.getFail();
            while (failNode != null && failNode.getChild(c) == null) {
                failNode = failNode.getFail();
            }
            next = (failNode == null) ? root : failNode.getChild(c);
        }

        cache.put(c, next);
        return next;
    }

    // 动态添加敏感词
    public synchronized void addSensitiveWord(String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        addWord(word);
        buildFailurePointers();
    }

    // 批量添加敏感词
    public synchronized void addSensitiveWords(List<String> words) {
        if (words == null || words.isEmpty()) {
            return;
        }
        for (String word : words) {
            if (word != null && !word.trim().isEmpty()) {
                addWord(word.trim());
            }
        }
        buildFailurePointers();
    }

    // 动态添加敏感词（异步）
    public Future<Void> addSensitiveWordAsync(String word) {
        return executorService.submit(() -> {
            addSensitiveWord(word);
            return null;
        });
    }

    // 批量添加敏感词（异步）
    public Future<Void> addSensitiveWordsAsync(List<String> words) {
        return executorService.submit(() -> {
            addSensitiveWords(words);
            return null;
        });
    }

    // 关闭线程池
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }

    // 增量式构建失败指针
    private void buildFailurePointersIncremental(CompactTrieNode node) {
        Queue<CompactTrieNode> queue = new LinkedList<>();
        queue.offer(node);

        while (!queue.isEmpty()) {
            CompactTrieNode current = queue.poll();

            // 处理常见字符子节点
            for (CompactTrieNode childNode : current.getCommonChildren()) {
                if (childNode != null) {
                    queue.offer(childNode);
                    buildFailurePointerForChild(current, childNode);
                }
            }

            // 处理非常见字符子节点
            if (current.getRareChildren() != null) {
                for (Map.Entry<Character, CompactTrieNode> entry : current.getRareChildren().entrySet()) {
                    queue.offer(entry.getValue());
                    buildFailurePointerForChild(current, entry.getValue());
                }
            }
        }
    }

    // 批量匹配敏感词
    public List<String> matchAllSensitiveWords(String text) {
        List<String> matches = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return matches;
        }

        CompactTrieNode current = root;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 使用缓存减少失败指针回溯
            CompactTrieNode next = getNextNode(current, c);
            if (next == null) {
                current = root;
            } else {
                current = next;
            }

            // 匹配到敏感词
            if (current.isEndOfWord()) {
                matches.add(sensitiveWordsList.get(current.getWordId()));
            }
        }
        return matches;
    }
}