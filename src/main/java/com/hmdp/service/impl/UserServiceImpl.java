package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.EmailSendUtil;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final UserMapper userMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final EmailSendUtil emailSendUtil;

    /**
     * 发送手机验证码
     * @param phone
     */
    @Override
    public Result sendPhoneCode(String phone) {
        // 验证手机号（这个其实要前端做就行了）
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 设置有限期为两分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 发送验证码（其实这里是通过手机验证码发送的，要调用第三方服务）
        // emailSendUtil.sendEmailCode("2961478685@qq.com", code);
        log.info("发送验证码 : {}", code);

        // 更新当前用户的发送验证码的次数
        stringRedisTemplate.opsForZSet().add(LOGIN_COUNT_LIMIT_KEY + phone, String.valueOf(System.currentTimeMillis()), System.currentTimeMillis());

        return Result.ok();
    }

    /**
     * 发送邮箱验证码
     * @param email
     * @return
     */
    @Override
    public Result sendMailCode(String email) {
        // 定义常量
        final String ONE_LEVEL_KEY = LOGIN_CODE_KEY + email + ":1";
        final String TWO_LEVEL_KEY = LOGIN_CODE_KEY + email + ":2";
        final String COUNT_KEY = LOGIN_COUNT_LIMIT_KEY + email;

        // 对于一级限制的判断
        String oneLevelLimit = stringRedisTemplate.opsForValue().get(ONE_LEVEL_KEY);
        if ("1".equals(oneLevelLimit)) {
            return Result.fail("您在过去5分钟之内3次验证失败，需要等5分钟后再请求");
        }

        // 对于二级限制的判断
        String twoLevelLimit = stringRedisTemplate.opsForValue().get(TWO_LEVEL_KEY);
        if ("1".equals(twoLevelLimit)) {
            return Result.fail("您在过去30分钟之内5次验证失败，需要等30分钟后再请求");
        }

        // 检查一分钟之内发送验证码的次数
        if (isLimitExceeded(COUNT_KEY, 1, 1)) {
            return Result.fail("距离上次发送时间不足1分钟，请1分钟后重试");
        }

        // 检查过去5分钟的发送次数（一级限制）
        if (isLimitExceeded(COUNT_KEY, 5, 3)) {
            stringRedisTemplate.opsForValue().set(ONE_LEVEL_KEY, "1", 5, TimeUnit.MINUTES);
            return Result.fail("您在过去5分钟之内3次验证失败，需要等5分钟后再请求");
        }

        // 检查过去30分钟的发送次数（二级限制）
        if (isLimitExceeded(COUNT_KEY, 30, 5)) {
            stringRedisTemplate.opsForValue().set(TWO_LEVEL_KEY, "1", 30, TimeUnit.MINUTES);
            return Result.fail("您在过去30分钟之内5次验证失败，需要等30分钟后再请求");
        }

        // 再次确保邮箱格式的正确
        if (RegexUtils.isEmailInvalid(email)) {
            return Result.fail("邮箱格式错误");
        }

        // 生成6位数字验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存到redis用于验证 ttl = 2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + email, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 发送验证码
        emailSendUtil.sendEmailCode(email, code);

        // 更新当前用户的发送验证码的次数
        stringRedisTemplate.opsForZSet().add(COUNT_KEY, String.valueOf(System.currentTimeMillis()), System.currentTimeMillis());

        return Result.ok();
    }

    // 判断是否超出限流次数
    private boolean isLimitExceeded(String key, int minutes, int limit) {
        long timeAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(minutes);
        Long count = stringRedisTemplate.opsForZSet().count(key, timeAgo, System.currentTimeMillis());
        return count != null && count >= limit;
    }



    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 验证手机号（这个其实要前端做就行了）
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        // 比较验证码
        // Object code = session.getAttribute("code");
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());

        if (code == null || !code.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }

        // select * from tb_user where phone = #{phone}
        LambdaQueryWrapper<User> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(User::getPhone, loginForm.getPhone());
        User user = userMapper.selectOne(lambdaQueryWrapper);
        if (user == null) {
            // 用户不存在创建用户
            User newUser = new User();
            newUser.setPhone(loginForm.getPhone());
            newUser.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            // 其他的信息登录之后在完善
            userMapper.insert(newUser);
        }

        // 保存用户到session中
        // session.setAttribute("user", user);
        String token = UUID.randomUUID().toString(true);
        // 隐私数据不要传递给前端
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 存储到redis中去，这里的是hash存储，所以要传递map键值对的形式
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 这里应该设置为秒的，但是为了不反复测试直接设置为天
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.DAYS);

        return Result.ok(token);
    }

    /**
     * 根据用户id查询用户，返回不带有隐私信息
     * @param id
     * @return
     */
    @Override
    public Result queryUserById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 签到功能
     * @return
     */
    @Override
    public Result sign() {
        // 获取到当前用户和日期
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        int day = now.getDayOfMonth();
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));

        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    /**
     * 统计连续签到的次数
     * @return
     */
    @Override
    public Result countSign() {
        // 获取到当前用户和日期
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        int day = now.getDayOfMonth();
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":yyyy:MM"));

        List<Long> bitField = stringRedisTemplate.opsForValue().bitField(key, 
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0));
        if (bitField == null || bitField.isEmpty()) {
            return Result.ok(0);
        }

        Long bitList = bitField.get(0);
        if (bitList == null || bitList == 0) {
            return Result.ok(0);
        }

        int countBit = 0;
        while (bitList > 0) {
            if ((bitList & 1) == 0) {
                break;
            }
            countBit += 1;
            // 无符号右移一位
            bitList >>>= 1;
        }
        return Result.ok(countBit);
    }


}
