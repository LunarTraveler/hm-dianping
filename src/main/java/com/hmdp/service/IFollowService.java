package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result follow(long followUserId, boolean isFollow);

    Result follow(long followUserId);

    /**
     * 查询与另外一个用户所共同关注的用户
     * @param id
     * @return
     */
    Result followCommons(Long id);
}
