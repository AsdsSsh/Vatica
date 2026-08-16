package com.example.vatica.controller;

/** 权限不足（迭代 13.5）：需要平台管理员身份。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
