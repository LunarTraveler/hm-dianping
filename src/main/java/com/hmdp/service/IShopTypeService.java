package com.hmdp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询所有的商店类型
     * @return
     */
    List<ShopType> queryAll() throws JsonProcessingException;
}
