package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailSendUtil {

    private final JavaMailSender mailSender;

    private String subject = "邮件标题";

    private String context1 = "黑马点评服务的邮箱验证码为";

    private String context2 = "，验证码的有效期为两分钟！";

    private String from = "LunarTravel@163.com";

    private String to = "";

    public void sendEmailCode(String to, String code) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setSubject(subject);
        mail.setText(context1 + code + context2);
        mail.setFrom(from);
        mail.setTo(to);

        mailSender.send(mail);
    }


}
