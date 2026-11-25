package com.techcodex.vo;

import java.util.List;

/**
 * 分类归并后的下拉数据：顶级分类 + 文档数 + 其下的原分类（含各自文档数）。
 * 前端用它渲染可搜索的两级分类选择器。
 */
public record CategoryGroupVo(String name, long count, List<CategoryVo> children) {

    /** 原分类及其文档数 */
    public record CategoryVo(String name, long count) {
    }
}
