package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询热度前10的blog
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 通过具体的blog的id查询博客
     * @param id
     * @return
     */
    Result queryByBlogId(long id);

    /**
     * 点赞功能
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 查询一个用户的所有博客记录
     * @param id
     * @param current
     * @return
     */
    Result queryBlogByUserId(long id, Integer current);

    /**
     * 发布博客并且推送给所有关注的粉丝（使用的是推模式）
     * @param blog
     */
    Result saveBlog(Blog blog);

    /**
     * 滚动分页查询boke
     * @param lastMinTimeStamp
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long lastMinTimeStamp, Integer offset);
}
