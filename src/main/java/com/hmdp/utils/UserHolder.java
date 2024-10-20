package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 这个主要用于保存当前这个用户的信息
 * 也可以是保存主要的信息（比如是用户的id）由这个去查询到其他的相关信息，并且也比较方便
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> threadLocal = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        threadLocal.set(user);
    }

    public static UserDTO getUser(){
        return threadLocal.get();
    }

    public static void removeUser(){
        threadLocal.remove();
    }
}
