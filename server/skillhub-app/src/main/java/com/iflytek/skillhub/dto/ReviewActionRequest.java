package com.iflytek.skillhub.dto;

public record ReviewActionRequest(String comment, Integer version) {
    public ReviewActionRequest(String comment) {
        this(comment, null);
    }
}
