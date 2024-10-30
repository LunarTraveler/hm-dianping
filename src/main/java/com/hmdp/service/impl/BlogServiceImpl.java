package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
            queryBlogUser(blog);
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

        queryBlogUser(blog);
        setBlogLiked(blog);

        return Result.ok(blog);
    }

    /**
     * 查询博客对应的用户信息
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
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + userId, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞功能
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();

        // 判断他是否点赞过了(用过redis中的set集合数据类型)
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + userId, userId.toString());

        if (score == null) {
            int updateRow = blogMapper.update(null, new LambdaUpdateWrapper<Blog>()
                    .setSql("liked = liked + 1").eq(Blog::getId, id));
            if (updateRow > 0) {
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + userId, userId.toString(), System.currentTimeMillis());
            }
        } else {
            int updateRow = blogMapper.update(null, new LambdaUpdateWrapper<Blog>()
                    .setSql("liked = liked - 1").eq(Blog::getId, id));
            if (updateRow > 0) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + userId, userId.toString());
            }
        }

        return Result.ok();
    }


}
