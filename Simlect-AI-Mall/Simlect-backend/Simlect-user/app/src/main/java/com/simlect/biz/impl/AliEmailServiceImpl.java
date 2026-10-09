package com.simlect.biz.impl; // 定义该类所在的包路径，用于组织项目结构

// 导入阿里云通用请求类，用于构造API请求
import com.aliyuncs.CommonRequest;
// 导入阿里云通用响应类，用于接收API响应
import com.aliyuncs.CommonResponse;
// 导入阿里云默认客户端配置类，用于创建API客户端
import com.aliyuncs.DefaultAcsClient;
// 导入阿里云API客户端接口，用于发送请求
import com.aliyuncs.IAcsClient;
// 导入阿里云服务端异常类，处理服务端返回的错误
import com.aliyuncs.exceptions.ServerException;
// 导入阿里云HTTP方法枚举，用于设置请求类型（如POST）
import com.aliyuncs.http.MethodType;
// 导入阿里云区域配置类，用于指定服务地域
import com.aliyuncs.profile.DefaultProfile;
// 导入项目自定义的邮件服务接口
import com.simlect.biz.EmailService;
// 导入项目自定义的工具类，用于生成随机数等通用功能
import com.simlect.utils.StringTools;
// 导入Spring的@Value注解，用于从配置文件读取属性值
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
// 导入Spring的@Service注解，将该类标记为Spring管理的服务层组件
import org.springframework.stereotype.Service;

// 使用@Service注解声明这是一个Spring服务Bean，会被自动扫描并注册
@Service
@Slf4j
// 定义类名为AliEmailServiceImpl，实现EmailService接口
public class AliEmailServiceImpl implements EmailService {

    // 使用@Value注解从配置文件中读取阿里云AccessKey ID，注入到该字段
    @Value("${aliyun.access-key-id:}")
    private String accessKeyId; // 存储阿里云API的访问密钥ID

    

    // 从配置文件读取阿里云AccessKey Secret，注入到该字段
    @Value("${aliyun.access-key-secret:}")
    private String accessKeySecret; // 存储阿里云API的访问密钥Secret，需保密

    

    // 从配置文件读取发信地址（例如 notice@example.com），用于指定邮件发送方
    @Value("${aliyun.directmail.account-name:}")
    private String accountName; // 你的发信地址

    

    // 从配置文件读取回信地址配置（通常设为true表示使用控制台配置的回信地址）
    @Value("${aliyun.directmail.reply-to-address:false}")
    private String replyToAddress; // 回信地址，通常设为true

    

    // 标注该方法重写了父接口EmailService中的sendVerificationCode方法
    @Override
    // 方法定义：发送验证码邮件，参数toEmail为接收邮箱地址，返回生成的验证码字符串
    public String sendVerificationCode(String toEmail) {
        // 第1步：生成6位随机数字验证码（调用自定义工具类方法）
        String verificationCode = StringTools.getRandomNumber(6);

        // 第2步：设置邮件内容（这里使用简单的纯文本格式）
        String emailSubject = "【简选Simlect】邮箱验证码"; // 邮件主题
        String emailBody = "您的验证码是：" + verificationCode + "，有效期5分钟。请勿泄露给他人。"; // 邮件正文，包含验证码

        // 第3步：构建并发送API请求
        // 创建默认的区域配置文件，指定服务地域为杭州（cn-hangzhou），并传入密钥ID和密钥Secret
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou", accessKeyId, accessKeySecret);
        // 根据配置创建阿里云API客户端实例
        IAcsClient client = new DefaultAcsClient(profile);
        // 创建一个通用API请求对象
        CommonRequest request = new CommonRequest();
        // 设置请求方法为POST
        request.setSysMethod(MethodType.POST);
        // 设置请求的域名，即邮件推送服务的API入口
        request.setSysDomain("dm.aliyuncs.com");
        // 设置API版本号，固定为2015-11-23
        request.setSysVersion("2015-11-23");
        // 设置操作名称，SingleSendMail表示单发邮件
        request.setSysAction("SingleSendMail");
        // 第4步：设置API请求的业务参数
        // 发信地址（控制台配置的邮件发送方）
        request.putQueryParameter("AccountName", accountName);
        // 地址类型，1表示使用发信地址发送，0表示使用邮件标签发送
        request.putQueryParameter("AddressType", "1");
        // 是否启用回信地址，true表示使用控制台配置的回信地址
        request.putQueryParameter("ReplyToAddress", replyToAddress);
        // 收件人邮箱地址（支持多个，逗号分隔，此处单个）
        request.putQueryParameter("ToAddress", toEmail);
        // 邮件主题
        request.putQueryParameter("Subject", emailSubject);
        // 纯文本邮件正文
        request.putQueryParameter("TextBody", emailBody);
        // 尝试发送请求并处理结果
        try {
            // 调用客户端发送请求，获取响应对象
            CommonResponse response = client.getCommonResponse(request);
            // 输出发送成功的日志，包含阿里云返回的RequestId，用于问题追踪
            log.info("发送成功，RequestId: {}" , response.getData());
            // 返回生成的验证码，供调用方暂存或后续校验
            return verificationCode;
        } catch (ServerException e) {
            // 捕获服务端异常（如业务错误、参数错误等），转换为运行时异常抛出
            throw new RuntimeException(e);
        } catch (com.aliyuncs.exceptions.ClientException e) {
            // 捕获客户端异常（如网络问题、签名错误等），转换为运行时异常抛出
            // 注意：此处使用了完全限定名，虽然上方已导入ClientException，但此处写全名也可编译
            throw new RuntimeException(e);
        }
    }
}