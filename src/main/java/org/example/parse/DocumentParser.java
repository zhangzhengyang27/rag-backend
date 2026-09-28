package org.example.parse;

/** 多格式文档解析为 Markdown 文本。 */
public interface DocumentParser {

    /**
     * 将二进制文档解析为 Markdown。
     *
     * @param bytes    文件字节
     * @param fileName 原始文件名（用于按扩展名识别类型）
     * @return Markdown 文本（非空）
     * @throws IllegalArgumentException 类型不支持 / 内容为空 / 解析失败
     */
    String parse(byte[] bytes, String fileName);
}
