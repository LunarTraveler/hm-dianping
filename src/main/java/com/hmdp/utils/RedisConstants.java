package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_SHOP_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final String LOCK_ORDER_KEY = "lock:order:";
    public static final String LOCK_MSG_KEY = "lock:message:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_BOX_KEY = "feed:box:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String FOLLOW_LIST_KEY = "follow:list:";
    public static final Integer SCROLL_PAGE_SIZE = 3;

    public static final String LOGIN_COUNT_LIMIT_KEY = "limit:user:";
    public static final String ONE_LEVER_LIMIT_KEY = "limit:one:";
    public static final String TWO_LEVER_LIMIT_KEY = "limit:two:";

    public static final Integer ONE_LEVER_LIMIT_TIME = 5;
    public static final Integer TWO_LEVER_LIMIT_TIME = 30;

}
