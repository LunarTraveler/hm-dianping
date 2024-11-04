package com.test;

import com.hmdp.HmDianPingApplication;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 编程式事务测试使用（用于目标方法是调用了其他的方法）
 */
@SpringBootTest(classes = HmDianPingApplication.class)
@Slf4j
public class TransactionalTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    public void testTransactional1() {
        // 编程式事务管理(比较复杂使用这种方案)
        TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());

        User user = new User();
        user.setId(1012L);
        user.setPassword("123456");

        try {
            userMapper.updateById(user);
            // int i = 1 / 0;
            // 提交事务
            transactionManager.commit(transaction);
        } catch (Exception e) {
            // 回滚事务
            transactionManager.rollback(transaction);
            log.error(e.getMessage());
        }
    }

    @Test
    public void testTransactional2() {
        User user = new User();
        user.setId(1012L);
        user.setPassword("123456");

        // 使用了模板类可以自动判断是否有异常和错误来提交或是回滚 (需要一个返回值<T>什么类型都可以的)
        transactionTemplate.execute((status) -> {
            userMapper.updateById(user);
            return Boolean.TRUE;
        });
    }


}
