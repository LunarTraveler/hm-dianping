package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final FollowMapper followMapper;

    private final UserMapper userMapper;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 关注或是取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(long followUserId, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();

        if (isFollow) {
            // 要关注 在数据库中生成一条记录
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            int insertRow = followMapper.insert(follow);

            if (insertRow > 0) {
                stringRedisTemplate.opsForSet().add(FOLLOW_LIST_KEY + userId, String.valueOf(followUserId));
            }
        } else {
            // 要取关 在数据库中删除那条记录
            int deleteRow = followMapper.delete(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));

            if (deleteRow > 0) {
                stringRedisTemplate.opsForSet().remove(FOLLOW_LIST_KEY + userId, String.valueOf(followUserId));
            }
        }

        return Result.ok();
    }

    /**
     * 查询两者的关系
     * @param followUserId
     * @return true 为关注了   false 为没有关注
     */
    @Override
    public Result follow(long followUserId) {
        Long userId = UserHolder.getUser().getId();

        Long count = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();

        String currentKey = FOLLOW_LIST_KEY + userId;
        String anotherKey = FOLLOW_LIST_KEY + id;
        Set<String> intersected = stringRedisTemplate.opsForSet().intersect(currentKey, anotherKey);
        if (intersected == null || intersected.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = intersected.stream().map(Long::valueOf).collect(Collectors.toList());

        List<UserDTO> userDTOList = userMapper.selectBatchIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOList);
    }

}
