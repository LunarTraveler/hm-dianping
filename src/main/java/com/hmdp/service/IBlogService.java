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
}
