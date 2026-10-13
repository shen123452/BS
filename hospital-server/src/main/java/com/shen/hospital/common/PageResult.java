package com.shen.hospital.common;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;

public record PageResult<T>(List<T> items, long total, long page, long pageSize) {
    public static <E, T> PageResult<T> from(IPage<E> source, java.util.function.Function<E, T> mapper) {
        return new PageResult<>(source.getRecords().stream().map(mapper).toList(), source.getTotal(), source.getCurrent(), source.getSize());
    }
}
