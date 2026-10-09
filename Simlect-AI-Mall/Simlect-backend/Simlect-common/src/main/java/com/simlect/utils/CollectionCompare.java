package com.simlect.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CollectionCompare<T> {
    // 通用的比较旧数据和新数据中数据的存在关系的工具类
    // 判断当前操作是新增、删除或修改
    // 定义新增数据，删除数据，修改数据的集合
    public static class CompareResult<T> {
        public List<T> addList = new ArrayList<>();
        public List<T> deleteList = new ArrayList<>();
        public List<T> updateList = new ArrayList<>();
    }
    // 比较方法
    public CompareResult<T> compare(List<T> oldList, List<T> newList, Function<T, String> getId) {
        CompareResult<T> result = new CompareResult<>();
        // 将oldList和newList转为map流式
        Map<String, T> oldMap = oldList.stream().collect(Collectors.toMap(getId, Function.identity()));
        Map<String, T> newMap = newList.stream().collect(Collectors.toMap(getId, Function.identity()));
        // 通过for循环依次比较
        // 如果旧数据中没有的，新数据中有的，则新增，如果两者都有的，则修改
        for (Map.Entry<String, T> entry : newMap.entrySet()) {
            if (!oldMap.containsKey(entry.getKey())) {
                result.addList.add(entry.getValue());
            }else {
                result.updateList.add(entry.getValue());
            }
        }
        // 如果旧数据中有的，新数据中没有的，则删除
        for (Map.Entry<String, T> entry : oldMap.entrySet()) {
            if (!newMap.containsKey(entry.getKey())) {
                result.deleteList.add(entry.getValue());
            }
        }
        return result;
    }
}
