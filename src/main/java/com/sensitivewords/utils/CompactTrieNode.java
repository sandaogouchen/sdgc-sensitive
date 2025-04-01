package com.sensitivewords.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CompactTrieNode {
    // 使用数组存储常见字符的子节点
    private final CompactTrieNode[] commonChildren = new CompactTrieNode[256];
    // 使用HashMap存储非常见字符的子节点
    private Map<Character, CompactTrieNode> rareChildren;
    private CompactTrieNode fail;
    private boolean isEndOfWord;
    // 使用索引代替完整敏感词
    private int wordId = -1;

    public CompactTrieNode getChild(char c) {
        if (c < 256) {
            return commonChildren[c];
        } else if (rareChildren != null) {
            return rareChildren.get(c);
        }
        return null;
    }

    public void addChild(char c, CompactTrieNode node) {
        if (c < 256) {
            commonChildren[c] = node;
        } else {
            if (rareChildren == null) {
                rareChildren = new HashMap<>();
            }
            rareChildren.put(c, node);
        }
    }

    // 获取常见字符子节点
    public CompactTrieNode[] getCommonChildren() {
        return commonChildren;
    }

    // 获取非常见字符子节点
    public Map<Character, CompactTrieNode> getRareChildren() {
        return rareChildren;
    }

    // 设置失败指针
    public void setFail(CompactTrieNode fail) {
        this.fail = fail;
    }

    // 获取失败指针
    public CompactTrieNode getFail() {
        return fail;
    }

    // 设置是否为单词结尾
    public void setEndOfWord(boolean isEndOfWord) {
        this.isEndOfWord = isEndOfWord;
    }

    // 判断是否为单词结尾
    public boolean isEndOfWord() {
        return isEndOfWord;
    }

    // 设置单词ID
    public void setWordId(int wordId) {
        this.wordId = wordId;
    }

    // 获取单词ID
    public int getWordId() {
        return wordId;
    }

    // 手动实现 hashCode() 方法
    @Override
    public int hashCode() {
        return Objects.hash(wordId, isEndOfWord);
    }

    // 手动实现 equals() 方法
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        CompactTrieNode that = (CompactTrieNode) obj;
        return wordId == that.wordId && isEndOfWord == that.isEndOfWord;
    }
}