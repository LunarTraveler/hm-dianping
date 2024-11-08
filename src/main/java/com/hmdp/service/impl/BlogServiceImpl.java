package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    @Lazy
    private BlogMapper blogMapper;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private FollowMapper followMapper;

    /**
     * 查询热度最高的10（点赞量）条博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        page = blogMapper.selectPage(page, new LambdaQueryWrapper<Blog>()
                .orderByDesc(Blog::getLiked));

        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            // 查询设置博客对应的用户信息
            queryBlogUser(blog);
            // 设置当前的用户是否点赞过这个博客
            setBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据id查询出相应的博客
     * @param id
     * @return
     */
    @Override
    public Result queryByBlogId(long id) {
        Blog blog = blogMapper.selectById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }

        // 查询设置博客对应的用户信息
        queryBlogUser(blog);
        // 设置当前的用户是否点赞过这个博客
        setBlogLiked(blog);

        return Result.ok(blog);
    }

    /**
     * 查询设置博客对应的用户信息
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 设置当前的用户是否点赞过这个博客
     * @param blog
     */
    private void setBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞功能
     * @param blogId
     * @return
     */
    @Override
    public Result likeBlog(Long blogId) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + blogId;

        // 判断他是否点赞过了(用过redis中的zset集合数据类型)
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            int updateRow = blogMapper.update(null, new LambdaUpdateWrapper<Blog>()
                    .setSql("liked = liked + 1").eq(Blog::getId, blogId));
            if (updateRow > 0) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            int updateRow = blogMapper.update(null, new LambdaUpdateWrapper<Blog>()
                    .setSql("liked = liked - 1").eq(Blog::getId, blogId));
            if (updateRow > 0) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogByUserId(long id, Integer current) {
        Page<Blog> page = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        page = blogMapper.selectPage(page, new LambdaQueryWrapper<Blog>()
                .eq(Blog::getUserId, id));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result saveBlog(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);

        int insertRow = blogMapper.insert(blog);
        if (insertRow == 0) {
            return Result.fail("新增博客失败");
        }

        // 推送这篇博客给所有关注的粉丝
        List<Follow> follows = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, userId));
        for (Follow follow : follows) {
            Long followUserId = follow.getUserId();
            // 消息发送到每一个用户的feed-box（是以zset为数据结构的,为了方便滚动分页）
            stringRedisTemplate.opsForZSet().add(FEED_BOX_KEY + followUserId, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long lastMinTimeStamp, Integer offset) {
        Long userId = UserHolder.getUser().getId();

        // 查询对应的feed-box查看里面的信件（是以滚动分页查询的方式）
        // ZREVRANGESCORE key max min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_BOX_KEY + userId, 0, lastMinTimeStamp, offset, SCROLL_PAGE_SIZE);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 结果封装为ScrollResult(list<T>, minTimeStamp, offset)
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTimeStamp = 0L;
        int newOffset = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long curTimeStamp = tuple.getScore().longValue();
            if (curTimeStamp == minTimeStamp) {
                newOffset++;
            } else {
                minTimeStamp = curTimeStamp;
                newOffset = 1;
            }
        }

        // 需要设置相应的信息为了前端的展示效果所需的信息
        List<Blog> blogList = blogMapper.selectList(new LambdaQueryWrapper<Blog>()
                .in(Blog::getId, ids)
                .orderByDesc(Blog::getId));
        blogList.forEach(blog -> {
            // 查询设置博客对应的用户信息
            queryBlogUser(blog);
            // 设置当前的用户是否点赞过这个博客
            setBlogLiked(blog);
        });

        ScrollResult<Blog> scrollResult = new ScrollResult<>();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTimeStamp);
        scrollResult.setOffset(newOffset);
        return Result.ok(scrollResult);
    }

    @Override
    public Result queryBlogLikesById(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 6);
        if (top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析出用户id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", userIds);
        //根据id查询用户
        List<UserDTO> userDTOS = userService.lambdaQuery()
                .in(User::getId,userIds)
                .last("order by field(id,"+join+")")
                .list()
                .stream().map(user ->
                        BeanUtil.copyProperties(user, UserDTO.class)
                ).collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);
    }


}
