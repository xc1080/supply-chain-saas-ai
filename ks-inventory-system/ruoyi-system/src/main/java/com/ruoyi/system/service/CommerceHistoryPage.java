package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import java.util.*;

/** Bounded server-side history queries; legacy array endpoints keep their existing contracts. */
final class CommerceHistoryPage {
    final int pageNum;
    final int pageSize;
    final String status;

    private CommerceHistoryPage(int pageNum,int pageSize,String status) {
        this.pageNum=pageNum;this.pageSize=pageSize;this.status=status;
    }

    static CommerceHistoryPage of(Map<String,String> query,String... statuses) {
        if(query==null||!Arrays.asList("pageNum","pageSize","status").containsAll(query.keySet()))
            throw new ServiceException("历史查询参数无效",400);
        int page=integer(query.get("pageNum"),1,1000000,"页码");
        int size=integer(query.get("pageSize"),20,100,"每页数量");
        String status=query.getOrDefault("status","ALL");
        if(!"ALL".equals(status)&&!Arrays.asList(statuses).contains(status))
            throw new ServiceException("历史查询状态无效",400);
        return new CommerceHistoryPage(page,size,status);
    }

    int offset(){return (pageNum-1)*pageSize;}

    Map<String,Object> result(long total,List<Map<String,Object>> rows) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("total",total);result.put("rows",rows);result.put("pageNum",pageNum);result.put("pageSize",pageSize);
        return result;
    }

    private static int integer(String value,int fallback,int maximum,String name) {
        if(value==null)return fallback;
        if(!value.matches("[1-9][0-9]{0,6}"))throw new ServiceException(name+"需为正整数",400);
        int number=Integer.parseInt(value);
        if(number>maximum)throw new ServiceException(name+"超出范围",400);
        return number;
    }
}
