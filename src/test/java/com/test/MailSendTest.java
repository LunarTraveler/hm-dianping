package com.test;

import com.hmdp.HmDianPingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMailMessage;
import org.springframework.mail.javamail.MimeMessageHelper;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootTest(classes = HmDianPingApplication.class)
public class MailSendTest {

    @Autowired
    private JavaMailSender mailSender;

    private String subject = "邮件标题";

    private String context = "邮件正文";

    private String from = "LunarTravel@163.com";

    private String to = "2961478685@qq.com";

    @Test
    public void testSendSimpleMail() {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setSubject(subject);
        mail.setText(context + "678950");
        mail.setFrom(from);
        mail.setTo(to);

        mailSender.send(mail);
    }

    @Test
    public void testSendComplexMail() throws MessagingException {
        MimeMessage mail = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mail, true);

        helper.setSubject(subject);
        helper.setText(context);
        helper.setFrom(from);
        helper.setTo(to);

        File file = new File("D:/loginUser3.txt");
        helper.addAttachment(file.getName(), file);

        mailSender.send(mail);
    }

}
