package com.example.vatica.secret;

/**
 * 主密钥提供者（迭代 13 I13-1）：唯一能拿到主密钥原始字节的抽象。
 * 生产后续可替换为云 KMS 实现；本期为本地文件实现 {@link FileMasterKeyProvider}。
 */
public interface MasterKeyProvider {

    /** 主密钥原始字节（32 字节）；调用方仅用于信封加解密，严禁写日志。 */
    byte[] rawKey();
}
