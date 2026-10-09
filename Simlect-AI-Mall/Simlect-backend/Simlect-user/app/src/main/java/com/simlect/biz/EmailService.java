package com.simlect.biz;

// 邮件验证码服务
public interface EmailService {

    public String sendVerificationCode(String toEmail);

}
