package com.ruoyi.framework.datasource;

import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.core.tenant.CommerceShopContext;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Collections;

/** Bounded admission; MySQL remains the sole inventory/order authority. Redis never decrements stock. */
@Component
@Profile("local")
public class SeckillAdmission {
    private final RedisTemplate<Object,Object> redis;
    private final RedisScript<Long> script;
    public SeckillAdmission(RedisTemplate<Object,Object> redis, RedisScript<Long> limitScript) { this.redis = redis; this.script = limitScript; }
    public void admit(String activity, Object owner) {
        if (!(owner instanceof String) || !((String)owner).matches("[a-f0-9]{64}") || !activity.matches("[A-Za-z0-9_-]{1,32}")) throw new ServiceException("请求标识错误", 400);
        String tenant="seckill:"+TenantContext.id();
        check(tenant+":all",500);
        check(tenant+":shop:"+CommerceShopContext.id(),200);
        String scope=tenant+":"+CommerceShopContext.id()+":"+activity;
        check(scope + ":all", 200); check(scope + ":owner:" + owner, 5);
    }
    private void check(String key, int limit) {
        try {
            Long count = redis.execute(script, Collections.singletonList(key), limit, 1);
            if (count == null || count > limit) throw new ServiceException("请求较多，请稍后重试同一请求编号", 429);
        } catch (ServiceException ex) { throw ex; }
        catch (RuntimeException ex) { throw new ServiceException("限流服务暂不可用，请稍后重试", 503); }
    }
}
