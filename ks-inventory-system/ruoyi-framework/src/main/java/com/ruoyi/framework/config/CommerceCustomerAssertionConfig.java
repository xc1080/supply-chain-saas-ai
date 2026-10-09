package com.ruoyi.framework.config;

import com.ruoyi.common.core.tenant.TenantContext;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.CommerceAccessPolicy;
import com.ruoyi.system.service.CommercePermission;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** A bearer token alone cannot delegate arbitrary customer identities. Assertions bind tenant/shop/action/owner. */
@Configuration
@Profile({"local","commerce"})
public class CommerceCustomerAssertionConfig implements WebMvcConfigurer {
    public static final String VERIFIED_OWNER="commerce.verifiedCustomerOwner";
    private final Environment environment;private final RedisTemplate<Object,Object> redis;
    public CommerceCustomerAssertionConfig(Environment environment,RedisTemplate<Object,Object> redis){this.environment=environment;this.redis=redis;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
                if(!CommerceAccessPolicy.customerService())return true;
                String tenant=TenantContext.id(),shop=request.getHeader("X-Shop-ID");
                if(shop==null||shop.isEmpty())shop="default";
                String owner=request.getHeader("X-Customer-Owner");if(owner==null)owner="";
                String stamp=request.getHeader("X-Customer-Timestamp"),nonce=request.getHeader("X-Customer-Nonce");
                String signature=request.getHeader("X-Customer-Signature");
                String secret=environment.getProperty("commerce.customer-assertion-secrets."+tenant,"");
                verify(secret,tenant,shop,request.getMethod(),request.getRequestURI(),owner,stamp,nonce,signature,System.currentTimeMillis()/1000);
                CommercePermission permission=handler instanceof HandlerMethod?((HandlerMethod)handler).getMethodAnnotation(CommercePermission.class):null;
                if(permission!=null&&permission.ownerScoped()){
                    require(owner.matches("[a-f0-9]{64}"),"客户委托缺少所有者",403);
                    if("GET".equals(request.getMethod()))require(owner.equals(request.getParameter("ownerId")),"客户委托与查询所有者不符",403);
                }
                try{
                    Boolean claimed=redis.opsForValue().setIfAbsent("customer-assertion:"+tenant+":"+nonce,"used",120,TimeUnit.SECONDS);
                    require(Boolean.TRUE.equals(claimed),"客户委托已使用，请重试原业务请求",403);
                }catch(ServiceException error){throw error;}
                catch(RuntimeException error){throw new ServiceException("客户身份核验暂不可用",503);}
                request.setAttribute(VERIFIED_OWNER,owner);return true;
            }
        }).addPathPatterns("/commerce/**").order(-100);
    }
    public static void verify(String secret,String tenant,String shop,String method,String path,String owner,String stamp,String nonce,String signature,long now){
        require(secret!=null&&secret.length()>=32,"客户委托密钥未配置",503);
        require(stamp!=null&&stamp.matches("[0-9]{10}")&&nonce!=null&&nonce.matches("[a-f0-9]{32}")&&signature!=null&&signature.matches("[a-f0-9]{64}"),"客户委托格式无效",403);
        require(Math.abs(now-Long.parseLong(stamp))<=60,"客户委托已过期",403);
        require(owner.isEmpty()||owner.matches("[a-f0-9]{64}"),"客户委托所有者无效",403);
        String expected=sign(secret,tenant,shop,method,path,owner,stamp,nonce);
        require(MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)),"客户委托签名无效",403);
    }
    public static String sign(String secret,String tenant,String shop,String method,String path,String owner,String stamp,String nonce){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String payload=String.join("\n",tenant,shop,method,path,owner,stamp,nonce);
            StringBuilder hex=new StringBuilder();for(byte item:mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)))hex.append(String.format("%02x",item));
            return hex.toString();
        }catch(Exception error){throw new IllegalStateException("Customer assertion signing failed",error);}
    }
    private static void require(boolean condition,String message,int code){if(!condition)throw new ServiceException(message,code);}
}
