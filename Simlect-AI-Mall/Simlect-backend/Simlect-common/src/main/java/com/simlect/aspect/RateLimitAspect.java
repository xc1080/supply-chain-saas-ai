package com.simlect.aspect;  // 定义包路径，aspect 目录存放 AOP 切面类

// 导入限流注解，用于标记需要限流的方法
import com.simlect.annotation.RateLimit;
// 导入限流服务，封装了 Redis 限流逻辑
import com.simlect.component.CouponRushRateLimitService;
// 导入响应码枚举，用于定义业务异常状态码
import com.simlect.component.RedisComponent;
import com.simlect.constants.Constants;
import com.simlect.entity.enums.ResponseCodeEnum;
// 导入业务异常类，用于抛出限流异常
import com.simlect.exception.BusinessException;
// 导入字符串工具类，用于空值判断
import com.simlect.utils.AuthCookieHelper;
import com.simlect.utils.StringTools;
// 导入 Jakarta 资源注入注解，替代 Spring 的 @Autowired
import jakarta.annotation.Resource;
// 导入 HTTP 请求对象，用于获取请求信息
import jakarta.servlet.http.HttpServletRequest;
// 导入 AOP 连接点对象，用于获取被拦截方法的信息
import org.aspectj.lang.JoinPoint;
// 导入切面注解，声明这是一个 AOP 切面
import org.aspectj.lang.annotation.Aspect;
// 导入前置通知注解，在目标方法执行前执行
import org.aspectj.lang.annotation.Before;
// 导入方法签名对象，用于获取方法的元数据
import org.aspectj.lang.reflect.MethodSignature;
// 导入 Spring 组件注解，将类注册为 Spring Bean
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
// 导入请求上下文持有者，用于获取当前请求
import org.springframework.web.context.request.RequestContextHolder;
// 导入 Servlet 请求属性对象，用于获取 HttpServletRequest
import org.springframework.web.context.request.ServletRequestAttributes;

// 导入反射方法类
import java.lang.reflect.Method;

@Component  // 注册为 Spring 组件，使其被 Spring 容器管理
@Aspect     // 声明为 AOP 切面类
public class RateLimitAspect {

    // 注入限流服务，用于执行实际的限流逻辑
    // 复用优惠券抢购限流的redis服务
    @Resource
    private CouponRushRateLimitService rateLimitService;
    @Autowired
    private RedisComponent redisComponent;

    @Resource
    private AuthCookieHelper authCookieHelper;

    @Before("@annotation(com.simlect.annotation.RateLimit)")
    public void beforeLimit(JoinPoint point) {
        // 从连接点获取方法签名（包含方法名、参数等信息）
        MethodSignature signature = (MethodSignature) point.getSignature();
        // 获取被拦截的方法对象
        Method method = signature.getMethod();
        // 获取方法上的 @RateLimit 注解实例
        RateLimit limit = method.getAnnotation(RateLimit.class);
        // 如果注解不存在，直接返回，不做限流处理
        if (limit == null) {
            return;
        }

        // 获取当前 HTTP 请求对象
        HttpServletRequest request = currentRequest();
        // 根据限流类型构建 Redis 限流键
        String key = buildKey(request, limit);

        // 调用限流服务检查是否允许访问
        // 参数：限流键、时间窗口（秒）、最大请求数
        if (!rateLimitService.tryAcquire(key, limit.windowSeconds(), limit.maxCount())) {
            // 如果限流检查失败，抛出业务异常
            throw new BusinessException(limit.message());
        }
    }

    private HttpServletRequest currentRequest() {
        // 从请求上下文持有者获取请求属性
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        // 如果请求属性为空，说明不在 Web 请求上下文，抛出 500 异常
        if (attrs == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
        // 返回 HTTP 请求对象
        return attrs.getRequest();
    }

    private String buildKey(HttpServletRequest request, RateLimit limit) {
        // 限流键前缀
        String prefix = Constants.REDIS_RATE_LIMIT;

        // 如果是按 IP 限流
        if (limit.limitType() == RateLimit.LimitType.IP) {
            // 获取客户端真实 IP
            String ip = getClientIp(request);
            // 构建键：rate:limit:ip:{IP地址}
            return prefix + "ip:" + ip;
        } else {
            // 如果是按用户限流，从请求头获取 token
            String token = authCookieHelper.resolveWebToken(request);
            // 如果 token 为空，说明用户未登录，抛出未登录异常
            if (StringTools.isEmpty(token)) {
                throw new BusinessException(ResponseCodeEnum.CODE_901);
            }
            // 根据token查userId
            String userId = redisComponent.getUserIdByToken(token);
            // 构建键：rate:limit:user:{token}
            return prefix + "user:" + userId;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        // 1. 优先获取 X-Forwarded-For（多个代理时，第一个为真实 IP）
        String ip = request.getHeader("X-Forwarded-For");
        // 如果为空或为 unknown，尝试下一个请求头
        if (StringTools.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        // 继续尝试 WL-Proxy-Client-IP（WebLogic 代理）
        if (StringTools.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        // 继续尝试 X-Real-IP（Nginx 等反向代理常用）
        if (StringTools.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        // 最后使用 RemoteAddr（直接连接时的客户端 IP）
        if (StringTools.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果 X-Forwarded-For 包含多个 IP（通过多个代理），取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        // 返回最终的客户端 IP
        return ip;
    }
}
