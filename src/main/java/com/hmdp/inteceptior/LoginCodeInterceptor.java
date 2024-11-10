package com.hmdp.inteceptior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_COUNT_LIMIT_KEY;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoginCodeInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    public static final DefaultRedisScript<List> LOGIN_LIMIT_SCRIPT;

    static {
        LOGIN_LIMIT_SCRIPT = new DefaultRedisScript<>();
        LOGIN_LIMIT_SCRIPT.setLocation(new ClassPathResource("loginLimit.lua"));
        LOGIN_LIMIT_SCRIPT.setResultType(List.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取参数
        String phone = request.getParameter("phone");
        if (phone == null) {
            return false;
        }

        // 执行lua脚本判断是否限流
        List<String> keys = Arrays.asList(
                LOGIN_CODE_KEY + phone + ":1",
                LOGIN_CODE_KEY + phone + ":2",
                LOGIN_COUNT_LIMIT_KEY + phone
        );

        List<String> args = Arrays.asList(
                String.valueOf(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30)),
                String.valueOf(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5)),
                String.valueOf(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)),
                String.valueOf(System.currentTimeMillis())
        );

        List<String> result = stringRedisTemplate.execute(LOGIN_LIMIT_SCRIPT, keys, args.toArray());
        log.info("{}, {}", result.get(0), result.get(1));

        if (result != null && result.size() == 2) {
            if ("err".equals(result.get(0))) {
                log.error(result.get(1));
                // 使用 Result 对象封装失败消息
                Result res = Result.fail(result.get(1));
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(res));
                return false;
            }
        }

        return true;
    }


}
