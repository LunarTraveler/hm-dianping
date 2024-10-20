package com.hmdp.config;

import com.hmdp.inteceptior.LoginInterceptor;
import com.hmdp.inteceptior.RefreshTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class MvcConfiguration implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    private final RefreshTokenInterceptor refreshTokenInterceptor;

    // 多拦截器有先后顺序问题，要么是编码先后，还可以是 .order(number is litter the order is bigger)
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**");

        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/list",
                        "voucher/**");

    }
}
