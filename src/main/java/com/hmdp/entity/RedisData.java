package com.hmdp.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {

    private LocalDateTime expireTime;
    // 这里从redis中获取出来时是JSONObject,需要再次转换
    private Object data;

}
