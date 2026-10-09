package com.simlect.entity.dto;

import java.io.Serializable;

public class SensitiveWordCacheItem implements Serializable {

    private String word;
    private String replaceWord;

    public SensitiveWordCacheItem() {
    }

    public SensitiveWordCacheItem(String word, String replaceWord) {
        this.word = word;
        this.replaceWord = replaceWord;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getReplaceWord() {
        return replaceWord;
    }

    public void setReplaceWord(String replaceWord) {
        this.replaceWord = replaceWord;
    }
}
