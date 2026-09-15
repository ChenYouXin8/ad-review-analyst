package io.github.chenyouxin8.adreview.model;

import java.util.List;

/**
 * 通用分页结果
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {
}
