package com.glucose.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

@Data
@AllArgsConstructor
public class PageResult<T> {

    private long total;
    private long current;
    private List<T> records;

    public static <T> PageResult<T> of(long total, long current, List<T> records) {
        return new PageResult<>(total, current, records);
    }

    public static <S, T> PageResult<T> map(PageResult<S> source, Function<S, T> mapper) {
        return new PageResult<>(source.getTotal(), source.getCurrent(),
                source.getRecords().stream().map(mapper).toList());
    }
}
