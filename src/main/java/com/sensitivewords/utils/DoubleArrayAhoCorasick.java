package com.sensitivewords.utils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;

public class DoubleArrayAhoCorasick {
    // 双数组结构
    private int[] base;  // 状态转移基址
    private int[] check; // 状态验证
    
    // 字符映射
    private int[] charMap;
    private int charCount;
    
    // 敏感词信息
    private String[] keywords;
    private int[] fail;  // 失败指针
    private int[] output; // 输出状态
    
    // 初始化
    public DoubleArrayAhoCorasick(Collection<String> words) {
        // 1. 构建字符映射
        buildCharMap(words);
        
        // 2. 初始化双数组
        int size = estimateSize(words);
        base = new int[size];
        check = new int[size];
        Arrays.fill(base, -1);
        Arrays.fill(check, -1);
        
        // 3. 构建Trie树
        buildTrie(words);
        
        // 4. 构建失败指针
        buildFailureLinks();
    }
    
    // 字符映射构建
    private void buildCharMap(Collection<String> words) {
        // ... 实现字符映射 ...
    }
    
    // 估计双数组大小
    private int estimateSize(Collection<String> words) {
        // ... 根据敏感词数量和长度估计大小 ...
        return words.size() * 10; // 示例值
    }
    
    // 构建Trie树
    private void buildTrie(Collection<String> words) {
        base[0] = 1; // 根节点
        
        for (String word : words) {
            int s = 0; // 从根节点开始
            for (char c : word.toCharArray()) {
                int t = charMap[c];
                int next = base[s] + t;
                
                if (check[next] == -1) {
                    check[next] = s;
                    base[next] = base[s];
                    s = next;
                } else if (check[next] == s) {
                    s = next;
                } else {
                    // 处理冲突
                    resolveConflict(s, t);
                }
            }
            // 标记结束状态
            output[s] = 1;
        }
    }
    
    // 构建失败指针
    private void buildFailureLinks() {
        Queue<Integer> queue = new LinkedList<>();
        
        // 第一层节点的失败指针指向根节点
        for (int i = 1; i < charCount; i++) {
            int s = base[0] + i;
            if (check[s] == 0) {
                fail[s] = 0;
                queue.offer(s);
            }
        }
        
        // 广度优先搜索构建失败指针
        while (!queue.isEmpty()) {
            int s = queue.poll();
            for (int i = 1; i < charCount; i++) {
                int next = base[s] + i;
                if (check[next] == s) {
                    int f = fail[s];
                    while (f != 0 && base[f] + i >= check.length) {
                        f = fail[f];
                    }
                    if (base[f] + i < check.length && check[base[f] + i] == f) {
                        fail[next] = base[f] + i;
                    } else {
                        fail[next] = 0;
                    }
                    queue.offer(next);
                }
            }
        }
    }
    
    // 处理冲突
    private void resolveConflict(int s, int t) {
        // ... 实现冲突解决策略 ...
    }
    
    // 敏感词替换
    public String replace(String text, char replaceChar) {
        StringBuilder result = new StringBuilder(text);
        int s = 0;
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int t = charMap[c];
            
            while (s != 0 && (base[s] + t >= check.length || check[base[s] + t] != s)) {
                s = fail[s];
            }
            
            if (base[s] + t < check.length && check[base[s] + t] == s) {
                s = base[s] + t;
                
                if (output[s] == 1) {
                    // 找到敏感词，进行替换
                    int len = getKeywordLength(s);
                    int start = i + 1 - len;
                    for (int j = start; j <= i; j++) {
                        result.setCharAt(j, replaceChar);
                    }
                }
            }
        }
        
        return result.toString();
    }
    
    // 获取敏感词长度
    private int getKeywordLength(int s) {
        // ... 实现获取敏感词长度 ...
        return 0;
    }
} 