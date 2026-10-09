package com.ruoyi.framework.config;

import com.ruoyi.common.exception.ServiceException;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CommerceCustomerAssertionTest {
    private final String secret="test-assertion-secret-only-32-characters";
    private final String owner=String.join("",Collections.nCopies(64,"a"));
    private final String nonce=String.join("",Collections.nCopies(32,"b"));
    private final long now=1800000000L;
    private String signature(){return CommerceCustomerAssertionConfig.sign(secret,"demo","default","POST","/commerce/orders",owner,String.valueOf(now),nonce);}
    private void reject(int code,Runnable action){try{action.run();fail("Expected rejection");}catch(ServiceException error){assertEquals(Integer.valueOf(code),error.getCode());}}
    @Test public void proofBindsEveryDelegationScope(){
        CommerceCustomerAssertionConfig.verify(secret,"demo","default","POST","/commerce/orders",owner,String.valueOf(now),nonce,signature(),now);
        reject(403,()->CommerceCustomerAssertionConfig.verify(secret,"studio","default","POST","/commerce/orders",owner,String.valueOf(now),nonce,signature(),now));
        reject(403,()->CommerceCustomerAssertionConfig.verify(secret,"demo","other","POST","/commerce/orders",owner,String.valueOf(now),nonce,signature(),now));
        reject(403,()->CommerceCustomerAssertionConfig.verify(secret,"demo","default","GET","/commerce/orders",owner,String.valueOf(now),nonce,signature(),now));
        reject(403,()->CommerceCustomerAssertionConfig.verify(secret,"demo","default","POST","/commerce/orders/other",owner,String.valueOf(now),nonce,signature(),now));
        reject(403,()->CommerceCustomerAssertionConfig.verify(secret,"demo","default","POST","/commerce/orders",String.join("",Collections.nCopies(64,"c")),String.valueOf(now),nonce,signature(),now));
    }
    @Test public void expiredMissingAndUnconfiguredProofsFailClosed(){
        reject(403,()->CommerceCustomerAssertionConfig.verify(secret,"demo","default","POST","/commerce/orders",owner,String.valueOf(now),nonce,signature(),now+61));
        reject(403,()->CommerceCustomerAssertionConfig.verify(secret,"demo","default","POST","/commerce/orders",owner,null,nonce,signature(),now));
        reject(503,()->CommerceCustomerAssertionConfig.verify("","demo","default","POST","/commerce/orders",owner,String.valueOf(now),nonce,signature(),now));
    }
}
